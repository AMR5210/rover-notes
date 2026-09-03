# Tracing and metrics

Every request carries one trace from the HTTP entry point through to the model call and
back.

Instrumentation is Micrometer with an OpenTelemetry bridge, exporting spans over OTLP and
metrics to Prometheus. The read path emits one span per stage (embed, dense, lexical,
fuse, rerank, synthesise), so a latency regression is attributable to a stage rather than
to the endpoint as a whole.

The p95 target for retrieval, measured from request to fused candidate list and excluding
synthesis, is 150 ms. The committed baseline reports a mean of 19.5 ms and a p95 of 30.4
ms across 27 eval queries, which leaves most of the budget unspent and is the number any
new stage is charged against.

Trace context propagates to the Python service over the W3C traceparent header, so a slow
parse appears in the same trace as the ingestion request that triggered it. Sampling is
set to 100 percent: at fewer than 10 queries per second, storing every trace costs less
than reasoning about which ones were dropped.
