"""ML plane: embeddings and reranking.

A thin orchestration layer in front of HF Text Embeddings Inference, which already
provides dynamic batching, ONNX runtime support, and warm model loading. This service
adds the request shapes and validation the rest of the system needs.
"""

import asyncio
import io
import logging
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager
from typing import Annotated, Literal

import httpx
from fastapi import FastAPI, File, HTTPException, UploadFile
from pydantic import BaseModel, Field

from ml_service.config import settings

# Parsing is an optional extra — pdfplumber, trafilatura and the rest are heavy and only
# the upload paths need them. Imported at module level they made the whole service fail to
# start without them, which took embeddings and reranking down over a dependency neither
# uses. Imported inside the endpoints instead, so a service installed without the extra
# serves everything else and refuses only what it genuinely cannot do.

_client: httpx.AsyncClient | None = None


async def _warm_late_interaction() -> None:
    """Loads the ColBERT weights ahead of the first request that needs them.

    Deferred to the first call, the load lands inside somebody's search: it measures
    about eleven seconds on this machine, and longer on a host that is busy, which is
    exactly the moment after a deploy when the host is busy. It was measured doing worse
    than that — a SciFact evaluation run failed outright because the first query's load
    pushed the request past the client's timeout, and the closed connection surfaced as an
    unreadable response body rather than as anything naming a model load.

    Run as a background task rather than awaited, so the service answers health checks and
    every other endpoint while the weights load. Failure is logged and dropped: reranking
    by late interaction is not the default, and a service that would not start because an
    optional model could not be fetched would take embeddings down with it.
    """
    try:
        from ml_service.reranking import warm

        await asyncio.to_thread(
            warm, settings.late_interaction_model, settings.late_interaction_device
        )
        logging.getLogger(__name__).info(
            "late-interaction model ready: %s", settings.late_interaction_model
        )
    except ImportError:
        # Installed without the extra, which is a supported deployment.
        logging.getLogger(__name__).debug("late-interaction extra not installed; not warming")
    except Exception as exc:
        logging.getLogger(__name__).warning("could not warm the late-interaction model: %s", exc)


@asynccontextmanager
async def lifespan(_: FastAPI) -> AsyncIterator[None]:
    global _client
    _client = httpx.AsyncClient(timeout=settings.request_timeout_seconds)
    warming = asyncio.create_task(_warm_late_interaction())
    try:
        yield
    finally:
        # Cancelled rather than awaited: on shutdown nobody is waiting for the weights,
        # and leaving the task unreferenced makes asyncio warn that it was destroyed
        # while pending.
        warming.cancel()
        await _client.aclose()
        _client = None


def client() -> httpx.AsyncClient:
    if _client is None:  # pragma: no cover - only reachable outside the lifespan
        raise RuntimeError("HTTP client not initialised")
    return _client


app = FastAPI(title="Rover ML Service", version="0.1.0", lifespan=lifespan)


class EmbedRequest(BaseModel):
    texts: Annotated[list[str], Field(min_length=1, max_length=512)]


class EmbedResponse(BaseModel):
    embeddings: list[list[float]]
    dim: int


class RerankRequest(BaseModel):
    query: str
    documents: Annotated[list[str], Field(min_length=1)]
    top_k: int = 10
    # Which reranker scores the candidates. Named on the request rather than fixed by
    # configuration because the two are being compared: an evaluation run has to be able
    # to ask for each against the same retrieval without restarting the service.
    strategy: Literal["cross-encoder", "late-interaction"] = "cross-encoder"


class RerankResult(BaseModel):
    index: int
    score: float


class RerankResponse(BaseModel):
    results: list[RerankResult]
    # Echoed because the request's default is a choice this service makes, and a caller
    # comparing two strategies should not have to assume which one answered.
    strategy: str = "cross-encoder"


class ParsedPage(BaseModel):
    """Where one page's text sits in the extracted string."""

    number: int
    char_start: int
    char_end: int
    tables: int
    # True when this page carried no text layer and was recognised from an image
    # instead. Reported because recognised text carries errors an extracted layer does
    # not, which is worth knowing when tracing a wrong answer back to a page.
    ocr: bool = False


class ClipRequest(BaseModel):
    url: str


class ClipResponse(BaseModel):
    """A fetched page reduced to what is worth indexing."""

    url: str
    title: str
    text: str


class ParseResponse(BaseModel):
    text: str
    pages: list[ParsedPage]
    table_count: int
    truncated: bool
    # Pages that produced no text by either route. A document where this covers every
    # page will index to nothing and never be retrievable, so the caller is told rather
    # than left to find out when a search comes back empty.
    empty_pages: list[int] = []


@app.get("/health")
async def health() -> dict[str, str]:
    return {"status": "ok"}


@app.post("/embed", response_model=EmbedResponse)
async def embed(request: EmbedRequest) -> EmbedResponse:
    """Embed a batch of texts.

    Called once per chunk at ingest and once per query at retrieval, which is why
    this runs locally rather than against a per-token API.
    """
    response = await client().post(
        f"{settings.embeddings_url}/embed", json={"inputs": request.texts}
    )
    if response.status_code != 200:
        raise HTTPException(status_code=502, detail=f"embedding backend: {response.text}")

    vectors: list[list[float]] = response.json()
    if vectors and len(vectors[0]) != settings.embedding_dim:
        # Fail loudly: a dimension mismatch against the vector(384) column would
        # otherwise surface as a confusing insert error much later.
        raise HTTPException(
            status_code=500,
            detail=f"expected dim {settings.embedding_dim}, backend returned {len(vectors[0])}",
        )
    return EmbedResponse(embeddings=vectors, dim=settings.embedding_dim)


@app.post("/rerank", response_model=RerankResponse)
async def rerank(request: RerankRequest) -> RerankResponse:
    """Cross-encoder rerank.

    The embedding model is a bi-encoder: query and document are encoded separately and
    never interact. A cross-encoder scores the pair jointly, which is far more accurate
    and far too slow to run over the whole corpus — hence the two-stage structure of
    cheap recall followed by expensive precision. See docs/ARCHITECTURE.md.
    """
    candidates = request.documents[: settings.rerank_candidates]

    if request.strategy == "late-interaction":
        return await _rerank_late_interaction(request, candidates)

    response = await client().post(
        f"{settings.reranker_url}/rerank",
        json={"query": request.query, "texts": candidates, "raw_scores": False},
    )
    if response.status_code != 200:
        raise HTTPException(status_code=502, detail=f"reranker backend: {response.text}")

    scored = [RerankResult(index=item["index"], score=item["score"]) for item in response.json()]
    scored.sort(key=lambda r: r.score, reverse=True)
    return RerankResponse(results=scored[: request.top_k], strategy="cross-encoder")


async def _rerank_late_interaction(request: RerankRequest, candidates: list[str]) -> RerankResponse:
    """Scores with a ColBERT model in this process rather than the reranker container.

    Run on a worker thread: the scoring is a synchronous torch forward pass, and leaving
    it on the event loop would stall every other request for its duration.
    """
    try:
        from ml_service.reranking import rerank_late
    except ImportError as exc:
        raise HTTPException(
            status_code=501,
            detail="this service was installed without the late-interaction extra; "
            "reinstall with `uv sync --extra late-interaction`",
        ) from exc

    try:
        ranked = await asyncio.to_thread(
            rerank_late,
            request.query,
            candidates,
            settings.late_interaction_model,
            settings.late_interaction_device,
        )
    except RuntimeError as exc:
        # Covers the model failing to download or load. A 503 rather than a 500: the
        # request is well formed and the same one succeeds once the model is present.
        raise HTTPException(status_code=503, detail=f"late-interaction reranker: {exc}") from exc

    return RerankResponse(
        results=[RerankResult(index=index, score=score) for index, score in ranked][
            : request.top_k
        ],
        strategy="late-interaction",
    )


@app.post("/parse/pdf", response_model=ParseResponse)
async def parse_pdf_upload(file: Annotated[UploadFile, File()]) -> ParseResponse:
    """Extract an uploaded PDF into indexable text and its page ranges.

    Parsing lives here rather than in the API because the libraries that do it well are
    Python ones, and because the work is CPU-bound and bursty — a shape that suits a
    service that can be scaled separately from the request path that serves reads.

    The response carries page ranges rather than page text so the caller stores one
    string, which is what the chunker and the citation spans already assume. A caller
    that wants the text of page 34 slices it; a caller that wants the page a citation
    landed on compares offsets.
    """
    body = await file.read(settings.max_upload_bytes + 1)
    if len(body) > settings.max_upload_bytes:
        raise HTTPException(
            status_code=413,
            detail=f"file exceeds {settings.max_upload_bytes} bytes",
        )
    if not body:
        raise HTTPException(status_code=400, detail="file is empty")

    try:
        from pdfplumber.utils.exceptions import PdfminerException

        from ml_service.parsing import parse_pdf
    except ImportError as exc:
        raise HTTPException(
            status_code=501,
            detail="this service was installed without the parsing extra; "
            "reinstall with `uv sync --extra parsing`",
        ) from exc

    try:
        parsed = await asyncio.to_thread(
            parse_pdf, io.BytesIO(body), max_pages=settings.max_pdf_pages
        )
    except PdfminerException as exc:
        # A file that is not a PDF is the caller's mistake, not this service failing.
        raise HTTPException(status_code=422, detail=f"could not read as PDF: {exc}") from exc

    return ParseResponse(
        text=parsed.text,
        pages=[
            ParsedPage(
                number=page.number,
                char_start=page.char_start,
                char_end=page.char_end,
                tables=page.tables,
                ocr=page.ocr,
            )
            for page in parsed.pages
        ],
        table_count=parsed.table_count,
        truncated=len(parsed.pages) == settings.max_pdf_pages,
        empty_pages=parsed.empty_pages,
    )


@app.post("/parse/url", response_model=ClipResponse)
async def parse_url(request: ClipRequest) -> ClipResponse:
    """Fetches a web page and extracts the readable part of it.

    The fetch is guarded: a service that retrieves an arbitrary URL on request is a proxy
    into whatever the caller cannot reach themselves, so every address the hostname
    resolves to is checked before connecting and again on each redirect. See
    `ml_service.parsing.web` for what that covers and the one case it does not.

    Run on a worker thread. Fetching and extraction both block, and holding the event
    loop for the duration would stall every other request behind one slow page.
    """
    try:
        from ml_service.parsing import web
    except ImportError as exc:
        raise HTTPException(
            status_code=501,
            detail="this service was installed without the parsing extra; "
            "reinstall with `uv sync --extra parsing`",
        ) from exc

    try:
        clipped = await asyncio.to_thread(web.clip, request.url)
    except web.UnfetchableUrlError as exc:
        # The caller chose the URL, so the reason is theirs to act on: a refused address,
        # a page that would not load, one with nothing to index.
        raise HTTPException(status_code=422, detail=str(exc)) from exc

    return ClipResponse(url=clipped.url, title=clipped.title, text=clipped.text)
