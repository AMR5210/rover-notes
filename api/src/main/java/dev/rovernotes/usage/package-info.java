/**
 * Per-request cost attribution for model calls.
 *
 * <p>Its own module rather than part of the module that happens to call a model first.
 * Synthesis is one task among several the schema anticipates — contextualisation, an
 * LLM judge, an agent loop — and each will want to record against the same table with
 * the same cost arithmetic.
 *
 * <p>See docs/ARCHITECTURE.md for why generation is hosted while embeddings are not, and
 * {@code `docs/ARCHITECTURE.md`} for the spend thresholds this data is meant to answer.
 */
@org.springframework.modulith.ApplicationModule(displayName = "Usage")
package dev.rovernotes.usage;
