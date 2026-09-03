package dev.rovernotes.ingestion;

import java.util.ArrayList;
import java.util.List;

import dev.rovernotes.ingestion.ChunkingStrategy.TextChunk;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Splits document text into overlapping windows.
 *
 * <p>This is the Week 2 baseline: fixed-size character windows snapped to word
 * boundaries. Semantic chunking replaces it in Week 4, and the eval harness decides
 * whether that change is kept.
 *
 * <p>Window size is expressed in characters rather than tokens. At roughly four
 * characters per token for English prose, the default 1,600-character window lands
 * near the 400-token target without needing a tokenizer on this path.
 *
 * <p>Overlap matters because a fact split across a boundary is retrievable from
 * neither side. Carrying the tail of each window into the next keeps boundary-spanning
 * sentences intact in at least one chunk.
 */
@Component
@ConditionalOnProperty(name = "rover.ingestion.chunking", havingValue = "fixed",
        matchIfMissing = true)
public class Chunker implements ChunkingStrategy {

    private final int windowChars;
    private final int overlapChars;

    public Chunker() {
        this(1600, 200);
    }

    Chunker(int windowChars, int overlapChars) {
        if (windowChars <= 0) {
            throw new IllegalArgumentException("windowChars must be positive");
        }
        if (overlapChars < 0 || overlapChars >= windowChars) {
            throw new IllegalArgumentException("overlapChars must be in [0, windowChars)");
        }
        this.windowChars = windowChars;
        this.overlapChars = overlapChars;
    }

    @Override
    public List<TextChunk> chunk(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        List<TextChunk> chunks = new ArrayList<>();
        int cursor = 0;
        int ordinal = 0;

        while (cursor < text.length()) {
            int end = Math.min(cursor + windowChars, text.length());

            // Snap back to a word boundary so chunks do not split mid-word, unless
            // doing so would leave the window implausibly short (a single long token).
            if (end < text.length()) {
                int boundary = text.lastIndexOf(' ', end);
                if (boundary > cursor + windowChars / 2) {
                    end = boundary;
                }
            }

            String body = text.substring(cursor, end).strip();
            if (!body.isEmpty()) {
                chunks.add(new TextChunk(ordinal++, body, cursor, end));
            }

            if (end >= text.length()) {
                break;
            }

            // Step forward by the window minus the overlap, then snap back to the start
            // of a word. Without this the overlap region can begin mid-token, which
            // splits a word across the boundary even though the window end was aligned.
            int next = Math.max(end - overlapChars, cursor + 1);
            int wordStart = text.lastIndexOf(' ', next);
            if (wordStart > cursor) {
                next = wordStart + 1;
            }
            cursor = next;
        }

        return chunks;
    }
}
