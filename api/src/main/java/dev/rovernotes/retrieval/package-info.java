/**
 * Hybrid retrieval: route, recall, fuse, rerank.
 *
 * <p>Runs dense (pgvector HNSW) and sparse (tsvector) channels in parallel, fuses them
 * with Reciprocal Rank Fusion, then reranks the survivors with a cross-encoder. The
 * fusion and ranking SQL is hand-written and owned here, which keeps the ranking logic
 * explicit and straightforward to tune. See docs/ARCHITECTURE.md.
 *
 * <p>Baseline lands in Week 2, the real pipeline in Weeks 4-5, each stage measured
 * against the eval harness before it is kept.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Retrieval")
package dev.rovernotes.retrieval;
