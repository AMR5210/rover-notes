package dev.rovernotes.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.rovernotes.retrieval.RetrievalService;

/**
 * The passages an answer may cite, numbered once and kept that way.
 *
 * <p>A single retrieval pass can number its results as it renders them, because there is
 * only one list and the model sees it whole. A loop cannot: passages arrive across several
 * searches, the same passage often comes back from more than one of them, and the model
 * writes {@code [3]} at a point in the answer without knowing which search produced it.
 *
 * <p>So a number is assigned when a chunk is first seen and never reassigned. A chunk
 * returned again by a later search keeps the number it already had, which is what makes a
 * bracketed reference mean the same thing everywhere in the answer. Numbering per search
 * instead would produce an answer whose citations are correct only in the order they were
 * written.
 *
 * <p>Not thread-safe, and not intended to be: one ledger belongs to one question.
 */
class SourceLedger {

    private final Map<UUID, Numbered> byChunk = new LinkedHashMap<>();

    private record Numbered(int number, RetrievalService.RetrievedChunk chunk) {}

    /**
     * Adds what a search returned and reports the numbers to render it under.
     *
     * @return the passages in the order given, each with the number it is cited by
     */
    List<Entry> add(List<RetrievalService.RetrievedChunk> hits) {
        List<Entry> entries = new ArrayList<>(hits.size());
        for (RetrievalService.RetrievedChunk hit : hits) {
            Numbered existing = byChunk.get(hit.chunkId());
            if (existing == null) {
                existing = new Numbered(byChunk.size() + 1, hit);
                byChunk.put(hit.chunkId(), existing);
            }
            entries.add(new Entry(existing.number(), existing.chunk()));
        }
        return entries;
    }

    int size() {
        return byChunk.size();
    }

    /**
     * Every passage seen, in the order the numbers were assigned.
     *
     * <p>Returned whole rather than filtered to what the answer actually cited. A number
     * the model wrote must resolve even if it turns out to be unsupported, because the
     * generation eval counts citations that point nowhere and cannot count what was
     * silently dropped.
     */
    List<AnswerService.Citation> citations() {
        return byChunk.values().stream()
                .map(n -> new AnswerService.Citation(n.number(), n.chunk().chunkId(),
                        n.chunk().documentId(), n.chunk().title(), n.chunk().charStart(),
                        n.chunk().charEnd(), n.chunk().score()))
                .toList();
    }

    /** A passage and the number an answer cites it by. */
    record Entry(int number, RetrievalService.RetrievedChunk chunk) {

        String rendered() {
            return Passages.render(number, chunk.title(), chunk.content());
        }
    }
}
