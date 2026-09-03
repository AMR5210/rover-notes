package dev.rovernotes.ingestion;

import java.util.Locale;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Situates a chunk in its document before the chunk is embedded.
 *
 * <p>A chunk taken out of the middle of a document loses what the document was about. A
 * passage reading "the useful bound is the number of cores multiplied by a small factor"
 * is a good answer to a question about pool sizing and contains neither the word pool nor
 * the word sizing, so nothing in its own text puts it near that question in embedding
 * space. Prefixing the document's title and the heading of the section it came from
 * restores exactly the words the passage assumes the reader already has.
 *
 * <p>This is the deterministic form of contextual retrieval. The published technique asks
 * a language model to write a sentence describing each chunk's place in its document,
 * which is strictly more informative and costs one model call per chunk. Headings are
 * free and are already written by the document's author. Running the free version first
 * answers whether context helps this corpus at all; if it does not, the paid version is
 * unlikely to, and if it does, the gap between them is what the model call would buy.
 *
 * <p>The prefix is applied to the text that gets embedded and nowhere else. The stored
 * {@code content} stays exactly the span it came from, because an answer cites a passage
 * by character offset and a chunk whose text no longer matched its own span would produce
 * a wrong citation.
 */
@Component
public class ChunkContext {

    /** How much of a chunk's surroundings to fold into its embedding. */
    public enum Mode {
        /** Embed the chunk's own text alone. */
        NONE,
        /** Prefix the document title and the enclosing section heading. */
        HEADING
    }

    private final Mode mode;

    ChunkContext(@Value("${rover.ingestion.chunk-context}") Mode mode) {
        this.mode = mode;
    }

    /**
     * The text to embed for {@code chunk}, which is not always the text to store.
     *
     * @param title        the document's title, used as the outer context
     * @param documentText the whole document, searched backwards for the enclosing heading
     */
    public String forEmbedding(String title, String documentText,
                               ChunkingStrategy.TextChunk chunk) {
        if (mode == Mode.NONE) {
            return chunk.content();
        }

        String heading = enclosingHeading(documentText, chunk.charStart());
        StringBuilder prefix = new StringBuilder();
        if (title != null && !title.isBlank()) {
            prefix.append(readable(title));
        }
        if (heading != null && !heading.isBlank()) {
            if (!prefix.isEmpty()) {
                prefix.append(" — ");
            }
            prefix.append(heading);
        }
        return prefix.isEmpty() ? chunk.content() : prefix + "\n\n" + chunk.content();
    }

    /**
     * The nearest Markdown heading at or above {@code offset}.
     *
     * <p>Searched backwards from the chunk's start rather than forwards, because a chunk
     * belongs to the section it opens inside, not to the next one it happens to reach. A
     * chunk that begins before any heading has none, and takes the title alone.
     */
    private static String enclosingHeading(String documentText, int offset) {
        if (documentText == null) {
            return null;
        }
        int cursor = Math.min(offset, documentText.length());
        while (cursor > 0) {
            int lineStart = documentText.lastIndexOf('\n', cursor - 1) + 1;
            String line = documentText.substring(lineStart,
                    Math.min(documentText.length(), lineEnd(documentText, lineStart)));
            String stripped = line.strip();
            // Only sub-headings. The top-level heading restates the title, which is
            // already the outer half of the prefix.
            if (stripped.startsWith("## ")) {
                return stripped.substring(3).strip();
            }
            if (lineStart == 0) {
                return null;
            }
            cursor = lineStart - 1;
        }
        return null;
    }

    private static int lineEnd(String text, int from) {
        int newline = text.indexOf('\n', from);
        return newline < 0 ? text.length() : newline;
    }

    /**
     * A slug read as words. Document titles here are filename stems, so
     * {@code connection-pooling} is three tokens to the tokenizer and one unhelpful one to
     * the embedding model.
     */
    private static String readable(String title) {
        return title.replace('-', ' ').replace('_', ' ').strip().toLowerCase(Locale.ROOT);
    }
}
