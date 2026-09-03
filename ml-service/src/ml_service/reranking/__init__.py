"""Late-interaction reranking.

Kept behind an optional install for the same reason the parsing package is: it pulls
torch and sentence-transformers, which are an order of magnitude larger than the rest of
this service, and a deployment that reranks with the cross-encoder needs neither.
"""

from ml_service.reranking.late_interaction import rerank_late, warm

__all__ = ["rerank_late", "warm"]
