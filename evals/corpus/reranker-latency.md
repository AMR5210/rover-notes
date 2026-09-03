# Reranker latency budget

Reranking is the most expensive stage on the read path, so its cost is bounded by
construction.

Scoring runs as a single ONNX Runtime batch of 40 query-document pairs, with sequences
truncated to 512 tokens, on 4 CPU threads. Batching all pairs into one call rather than
issuing them serially is what keeps the stage inside its budget, since per-call overhead
dominates at this model size.

The stage is given 200 ms. On timeout it returns the fused order unchanged rather than
waiting, so a slow reranker degrades ranking quality instead of failing the request. That
fallback fires on roughly 1 in 500 requests, almost always on a cold start before the
model is resident in memory.

Depth is the tuning knob. Scoring 40 candidates and returning 10 was chosen over scoring
the full fused list because the additional pairs cost proportionally more time and moved
nDCG@10 by less than the measurement noise on the golden set.
