"""Configuration tests.

The embedding dimension and rerank candidate count are referenced from both the
database schema and the retrieval latency budget, so they are worth asserting
directly rather than relying on the defaults staying put.
"""

from ml_service.config import Settings


def test_embedding_dim_matches_schema() -> None:
    """Must match the vector(384) column in V1__baseline.sql."""
    assert Settings().embedding_dim == 384


def test_rerank_candidates_within_cpu_latency_budget() -> None:
    """Cross-encoder input size is bounded to keep p95 retrieval under 150ms."""
    assert 0 < Settings().rerank_candidates <= 50


def test_service_urls_are_configured() -> None:
    settings = Settings()
    assert settings.embeddings_url.startswith("http")
    assert settings.reranker_url.startswith("http")
