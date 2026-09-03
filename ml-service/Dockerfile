# Build and run the Python service: embeddings and reranking proxies, PDF and web
# parsing, and the evaluation harness.
FROM python:3.12-slim AS runtime
WORKDIR /app

# tesseract is a system package and not installable from PyPI. Without it a scanned PDF
# parses to nothing and the response names the pages that produced none, so the service
# runs either way — it is installed because a deployment that accepts uploads should
# handle a scan.
RUN apt-get update \
    && apt-get install --no-install-recommends -y tesseract-ocr \
    && rm -rf /var/lib/apt/lists/*

COPY --from=ghcr.io/astral-sh/uv:0.9.5 /uv /usr/local/bin/uv

# Dependencies before source, so a code change does not re-resolve them.
COPY pyproject.toml README.md* ./
COPY src src

# The parsing extra is installed; late-interaction is not. It brings torch and
# sentence-transformers, which add gigabytes to the image for a reranker that is off by
# default. Build with --build-arg EXTRAS="--extra parsing --extra late-interaction" for
# an image that serves it.
ARG EXTRAS="--extra parsing"
RUN uv sync --no-dev ${EXTRAS} --frozen 2>/dev/null || uv sync --no-dev ${EXTRAS}

RUN groupadd --system rover && useradd --system --gid rover --home /app rover \
    && chown -R rover:rover /app
USER rover

EXPOSE 8000
ENV PATH="/app/.venv/bin:$PATH"

CMD ["uvicorn", "ml_service.main:app", "--host", "0.0.0.0", "--port", "8000"]
