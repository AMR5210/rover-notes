"""Service configuration.

Model choices here are load-bearing, not defaults picked at random — see
`docs/ARCHITECTURE.md`. Both models are small on purpose:
they run on CPU and are called on every chunk at ingest and every query at retrieval,
which is exactly the profile where self-hosting beats per-token API pricing.
"""

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_prefix="ROVER_", env_file=".env")

    # HF Text Embeddings Inference endpoints (docker-compose services).
    embeddings_url: str = "http://localhost:8081"
    reranker_url: str = "http://localhost:8082"

    # bge-small-en-v1.5: 384 dimensions, ~33M params. Must match the vector(384)
    # column in the baseline migration — changing it means a new column and a backfill.
    embedding_dim: int = 384

    # Cross-encoder input size. The binding constraint is CPU latency: ~22M-param
    # MiniLM handles ~40 pairs in roughly 50-150ms, where a 570M-param reranker
    # would take 1-3 seconds. Trades a little tail recall for a latency budget
    # that actually closes.
    rerank_candidates: int = 40

    # Late-interaction reranking. 32M parameters and a 64-dimension projection per
    # token, chosen over the 149M alternative because this one is reranking 40 passages
    # on the same CPU that is serving the request; the larger model is the better one
    # wherever there is a GPU to put it on.
    late_interaction_model: str = "mixedbread-ai/mxbai-edge-colbert-v0-32m"
    late_interaction_device: str = "cpu"

    batch_size: int = 32
    request_timeout_seconds: float = 30.0

    # Bounds on one upload. Parsing is CPU-bound and runs while a caller waits, so
    # without these the request time is a property of the file rather than of the
    # service. 25 MB and 500 pages are well above any note somebody writes and well
    # below what would hold a worker for minutes.
    max_upload_bytes: int = 25 * 1024 * 1024
    max_pdf_pages: int = 500


settings = Settings()
