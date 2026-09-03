"""Reranking by late interaction (ColBERT).

The two rerankers here answer the same question with different amounts of information.
The cross-encoder concatenates the query and one passage and pushes the pair through a
transformer, which is accurate and costs a forward pass per pair — the reason it runs
over 40 candidates rather than the corpus. Late interaction encodes each side once into
one vector *per token*, then scores a pair by MaxSim: for every query token, the best
match among the passage's tokens, summed.

What that buys is a term-level account of why a passage ranked. A single-vector
bi-encoder has to carry a whole passage in one point and loses the rare term that a
question turns on; MaxSim keeps a vector for that term and lets it be matched directly.
It is the property this system's lexical channel exists to supply, obtained from the
embedding rather than from a separate index.

The passage side is encoded per request rather than at ingest. Storing token vectors is
the deployment that makes late interaction fast — the published systems index them — and
it multiplies the vector store by the token count of the corpus. Encoding 40 candidates
at query time keeps the storage unchanged and is what the measurement below is against;
indexing them is the next step if the score justifies it.

PyLate is used rather than raw transformers because ColBERT's scoring is not only MaxSim.
Queries are padded with mask tokens and scored as if those were terms, and punctuation on
the passage side is dropped; both change the score, both are easy to omit, and omitting
either produces a model that runs and ranks slightly wrong.
"""

from __future__ import annotations

import threading
from typing import TYPE_CHECKING, Any, cast

if TYPE_CHECKING:  # pragma: no cover - import-time only for type checking
    from pylate import models

_model: models.ColBERT | None = None
# Loading takes ~11 seconds and every request would otherwise pay it. The lock is around
# construction alone: uvicorn serves this from a thread pool, and two requests arriving
# before the first load finishes would otherwise build the model twice.
_lock = threading.Lock()


class LateInteractionUnavailableError(RuntimeError):
    """Raised when the optional dependencies are not installed."""


def _load(model_name: str, device: str) -> models.ColBERT:
    """Builds the model once and returns it thereafter.

    The lock is taken on every call rather than only on the miss. An uncontended lock is
    tens of nanoseconds against a forward pass measured in tens of milliseconds, and the
    double-checked form it replaces is the version of this that two requests arriving
    during startup get wrong.
    """
    global _model
    with _lock:
        if _model is None:
            try:
                from pylate import models
            except ImportError as exc:  # pragma: no cover - exercised by the 501 path
                raise LateInteractionUnavailableError(
                    "this service was installed without the late-interaction extra; "
                    "reinstall with `uv sync --extra late-interaction`"
                ) from exc
            _model = models.ColBERT(model_name_or_path=model_name, device=device)
        return _model


def warm(model_name: str, device: str = "cpu") -> None:
    """Loads the model ahead of the first request, for a caller that would rather wait."""
    _load(model_name, device)


def rerank_late(
    query: str,
    documents: list[str],
    model_name: str,
    device: str = "cpu",
) -> list[tuple[int, float]]:
    """Scores each document against the query, best first.

    Returns ``(original_index, score)`` so the caller can reorder its own candidates;
    the scores are MaxSim sums and are comparable within one call but not across calls,
    since their scale follows the query's token count.
    """
    if not documents:
        return []

    import numpy as np
    import torch

    model = _load(model_name, device)

    # `encode` is annotated as returning a mapping, but for a list of strings it returns
    # one float32 array per input, shaped [tokens, 64]. Verified against pylate 1.6 —
    # the annotation covers a different call shape than the one used here.
    query_embedding = cast(list[Any], model.encode([query], is_query=True))
    document_embeddings = cast(list[Any], model.encode(documents, is_query=False))

    queries = torch.tensor(np.asarray(query_embedding[0]))  # [Q, H]

    lengths = [len(embedding) for embedding in document_embeddings]
    padded = torch.nn.utils.rnn.pad_sequence(
        [torch.tensor(embedding) for embedding in document_embeddings], batch_first=True
    )  # [N, T, H]

    # Similarity of every query token against every token of every passage.
    similarity = torch.einsum("qh,nth->qnt", queries, padded)

    # Padding is masked to negative infinity rather than to zero.
    #
    # `pad_sequence` fills with zero vectors, which have cosine 0 against every query
    # token. Zero beats any genuinely negative similarity, so the max for a query token
    # that matches a short passage badly is taken from the padding instead of from the
    # passage, and the passage scores as though that token were neutral. The effect is a
    # length bias: the shorter the passage, the more padding it has to be rescued by.
    #
    # PyLate's own `documents_mask` does not address this — it multiplies the scores by
    # the mask, which sets padded positions to exactly the zero that is the problem.
    # Verified against pylate 1.6: a single real token at similarity -1 scores 0.0 both
    # with the mask and without it.
    positions = torch.arange(padded.shape[1]).unsqueeze(0)
    real = positions < torch.tensor(lengths).unsqueeze(1)  # [N, T]
    similarity = similarity.masked_fill(~real.unsqueeze(0), float("-inf"))

    # MaxSim: the best-matching passage token for each query token, summed.
    scored = similarity.max(dim=-1).values.sum(dim=0)  # [N]

    ranked = sorted(
        ((index, float(score)) for index, score in enumerate(scored.tolist())),
        key=lambda pair: pair[1],
        reverse=True,
    )
    return ranked
