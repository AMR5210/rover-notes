package dev.rovernotes.ingestion;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import dev.rovernotes.EmbeddingClient;
import dev.rovernotes.notes.ContentHash;
import dev.rovernotes.notes.DocumentChanged;
import dev.rovernotes.notes.NoteService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

/**
 * Turns document changes into indexed chunks.
 *
 * <p>Driven by {@link DocumentChanged}, which the notes module publishes inside its own
 * write transaction. Spring Modulith persists that publication alongside the write and
 * replays it if this listener has not completed, so an indexing job cannot be lost
 * because the process restarted mid-run. See docs/ARCHITECTURE.md.
 *
 * <p>Week 2 keeps the pipeline deliberately plain: split, embed, store. Contextual
 * annotation and semantic chunking arrive in Week 4, each measured against the eval
 * baseline before it is kept.
 */
@Service
public class IngestionService {

    private static final Logger log = LoggerFactory.getLogger(IngestionService.class);

    /** TEI batches internally; this bounds request size rather than model throughput. */
    private static final int EMBED_BATCH = 32;

    private final NoteService notes;
    private final ChunkingStrategy chunker;
    private final ChunkContext context;
    private final EmbeddingClient embeddings;
    private final ChunkRepository chunks;
    private final IngestionMetrics metrics;

    IngestionService(NoteService notes,
                     ChunkingStrategy chunker,
                     ChunkContext context,
                     EmbeddingClient embeddings,
                     ChunkRepository chunks,
                     IngestionMetrics metrics) {
        this.notes = notes;
        this.chunker = chunker;
        this.context = context;
        this.embeddings = embeddings;
        this.chunks = chunks;
        this.metrics = metrics;
    }

    @ApplicationModuleListener
    void on(DocumentChanged event) {
        switch (event.kind()) {
            case DELETED -> {
                chunks.deleteByDocument(event.documentId());
                log.debug("removed chunks for deleted document {}", event.documentId());
            }
            case CREATED, UPDATED -> reindex(event);
        }
    }

    /**
     * Indexes a document, embedding only the chunks whose embedding input is new.
     *
     * <p>Re-indexing used to delete every chunk and embed the document again, so editing
     * one paragraph of a long document paid for all of it and re-importing an unchanged
     * file paid for it twice. The reuse key is a hash of the exact string handed to the
     * embedding model rather than of the chunk's own text, because those differ when
     * chunk context is on: renaming a document changes what every chunk embeds to while
     * leaving every chunk's content untouched.
     *
     * <p>Rows are kept and moved rather than deleted and rewritten. That is what makes
     * the saving real — the vector stays in the row it is already in, and nothing reads
     * it back into the application to write it out again.
     */
    private void reindex(DocumentChanged event) {
        var document = notes.get(event.ownerId(), event.documentId());
        var pieces = chunker.chunk(document.content());

        if (pieces.isEmpty()) {
            chunks.deleteByDocument(event.documentId());
            log.debug("document {} has no indexable content", event.documentId());
            return;
        }

        // What is embedded and what is stored differ when chunk context is on: the
        // embedding carries the document's title and section heading so a passage is
        // findable by words its own text assumes, while the stored content stays
        // exactly the span it came from so a citation still points at real text.
        List<String> inputs = pieces.stream()
                .map(piece -> context.forEmbedding(document.title(), document.content(), piece))
                .toList();
        List<String> embeddingHashes = inputs.stream().map(ContentHash::of).toList();

        // Claim one existing row per chunk whose embedding input is unchanged. Claiming
        // rather than looking up, because a document may contain the same passage twice
        // and two occurrences need two rows.
        Map<String, Deque<UUID>> reusable = chunks.reusableByHash(event.documentId());
        UUID[] reused = new UUID[pieces.size()];
        List<Integer> missing = new ArrayList<>();
        for (int i = 0; i < pieces.size(); i++) {
            Deque<UUID> rows = reusable.get(embeddingHashes.get(i));
            UUID row = rows == null ? null : rows.poll();
            if (row == null) {
                missing.add(i);
            } else {
                reused[i] = row;
            }
        }

        Map<Integer, float[]> vectors = new HashMap<>();
        for (int start = 0; start < missing.size(); start += EMBED_BATCH) {
            List<Integer> batch =
                    missing.subList(start, Math.min(start + EMBED_BATCH, missing.size()));
            List<float[]> computed = embeddings.embed(batch.stream().map(inputs::get).toList());
            for (int i = 0; i < batch.size(); i++) {
                vectors.put(batch.get(i), computed.get(i));
            }
        }

        // Whatever went unclaimed holds text this document no longer contains. Removed
        // before the rest are repositioned, so a chunk moving into an ordinal a deleted
        // row held does not depend on the constraint being deferred to get there.
        chunks.deleteByIds(reusable.values().stream().flatMap(Collection::stream).toList());

        for (int i = 0; i < pieces.size(); i++) {
            var piece = pieces.get(i);
            var chunk = new ChunkRepository.StoredChunk(
                    piece.ordinal(),
                    piece.content(),
                    // Null when context is off, so the lexical index falls back to the
                    // chunk's own text and the committed baseline is untouched.
                    inputs.get(i).equals(piece.content()) ? null : inputs.get(i),
                    ContentHash.of(piece.content()),
                    embeddingHashes.get(i),
                    estimateTokens(piece.content()),
                    piece.charStart(),
                    piece.charEnd(),
                    vectors.get(i));

            if (reused[i] == null) {
                chunks.insert(event.documentId(), event.ownerId(), chunk);
            } else {
                chunks.reposition(reused[i], chunk);
            }
        }

        metrics.chunksIndexed(missing.size(), pieces.size() - missing.size());
        log.info("indexed document {} into {} chunks: {} embedded, {} reused",
                event.documentId(), pieces.size(), missing.size(),
                pieces.size() - missing.size());
    }

    /**
     * Rough token estimate for reporting only, at about four characters per token for
     * English prose. Nothing depends on its accuracy; a real tokenizer on this path
     * would cost more than the number is worth.
     */
    private static int estimateTokens(String text) {
        return Math.max(1, text.length() / 4);
    }
}
