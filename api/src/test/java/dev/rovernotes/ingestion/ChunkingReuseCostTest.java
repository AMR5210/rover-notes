package dev.rovernotes.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

import dev.rovernotes.EmbeddingClient;
import dev.rovernotes.TestDatabase;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * What each chunker costs to re-index after an edit.
 *
 * <p>Chunk reuse keeps a vector when the text that produced it is unchanged, so how much
 * it saves is decided by the chunker: a fixed window makes every chunk's text a function
 * of the character count before it, and one inserted sentence near the top moves every
 * boundary after it. Semantic chunking is what would close that, and this measures
 * whether it does.
 *
 * <p>Both chunkers are driven directly rather than through indexing. The quantity is a
 * property of the split — which chunk texts survive an edit — and going through the
 * database would measure the same thing more slowly with a container in the way.
 *
 * <p>Run against the real embedding server, because semantic boundaries come from
 * sentence embeddings and a stub would place them arbitrarily, which is the one thing
 * this cannot afford to invent.
 *
 * <p>Skipped where that server is not running. This records a measurement rather than
 * gating a regression — its numbers are in {@code docs/RESULTS.md} — so a machine
 * without the local stack should report the rest of the suite rather than one failure
 * about a service the suite does not otherwise need.
 */
@SpringBootTest
@ActiveProfiles("local")
class ChunkingReuseCostTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    private static final Path CORPUS = Path.of("..", "evals", "corpus");

    /** The committed defaults, so the numbers describe what actually runs. */
    private static final int WINDOW = 1600;
    private static final int OVERLAP = 200;
    private static final double PERCENTILE = 95;
    private static final int MIN_CHARS = 300;
    private static final int MAX_CHARS = 2400;

    @Autowired
    EmbeddingClient embeddings;

    /** One edit and what it does to a document, named for the report. */
    private record Edit(String name, UnaryOperator<String> apply) {}

    /** What one chunker paid to re-index one edited document. */
    private record Cost(int chunksAfter, int chunksReembedded, int sentencesEmbedded) {

        int totalEmbedded() {
            return chunksReembedded + sentencesEmbedded;
        }
    }

    private static List<Edit> edits() {
        return List.of(
                new Edit("no change", text -> text),
                new Edit("typo fixed mid-document, same length", ChunkingReuseCostTest::flipMiddle),
                new Edit("sentence inserted near the top",
                        text -> insertAt(text, firstParagraphEnd(text),
                                " This sentence was added during an edit.")),
                new Edit("paragraph appended",
                        text -> text + "\n\n## Addendum\n\nA later note appended to the end of "
                                + "the document, describing a detail nobody wrote down at "
                                + "the time.\n"),
                new Edit("paragraph removed from the middle", ChunkingReuseCostTest::dropMiddle));
    }

    /** Whether the embedding server this measurement needs is actually up. */
    private boolean embeddingServerIsUp() {
        try {
            embeddings.embed(List.of("a probe"));
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Test
    void semanticChunkingIsComparedWithFixedWindowsOnReindexCost() throws IOException {
        assumeTrue(embeddingServerIsUp(),
                "the embedding server is not running; start it with `make up`");

        var fixed = new Chunker(WINDOW, OVERLAP);
        var semantic = new SemanticChunker(embeddings, PERCENTILE, MIN_CHARS, MAX_CHARS);

        List<Path> documents = longestDocuments(4);
        assertThat(documents).isNotEmpty();

        Map<String, List<Cost>> fixedCosts = new HashMap<>();
        Map<String, List<Cost>> semanticCosts = new HashMap<>();

        for (Path document : documents) {
            String original = Files.readString(document);
            for (Edit edit : edits()) {
                String edited = edit.apply().apply(original);
                fixedCosts.computeIfAbsent(edit.name(), key -> new ArrayList<>())
                        .add(cost(fixed, original, edited, false));
                semanticCosts.computeIfAbsent(edit.name(), key -> new ArrayList<>())
                        .add(cost(semantic, original, edited, true));
            }
        }

        report(documents.size(), fixedCosts, semanticCosts);

        // The property the gap turns on, and it does not hold. Semantic chunking re-embeds
        // every sentence to find its boundaries, and that happens before reuse can apply.
        for (Edit edit : edits()) {
            int fixedTotal = total(fixedCosts.get(edit.name()));
            int semanticTotal = total(semanticCosts.get(edit.name()));
            assertThat(semanticTotal)
                    .as("re-index cost for '%s': semantic %d vs fixed %d embedded texts",
                            edit.name(), semanticTotal, fixedTotal)
                    .isGreaterThan(fixedTotal);
        }
    }

    /**
     * Chunk texts that the edited document does not share with the original.
     *
     * <p>A multiset difference rather than a set one, so a document containing the same
     * passage twice is counted twice — which is what indexing does, since two occurrences
     * claim two rows.
     */
    private static Cost cost(ChunkingStrategy chunker, String original, String edited,
                             boolean embedsSentences) {
        Map<String, Integer> available = new HashMap<>();
        for (var piece : chunker.chunk(original)) {
            available.merge(piece.content(), 1, Integer::sum);
        }

        var after = chunker.chunk(edited);
        int reembedded = 0;
        for (var piece : after) {
            Integer left = available.get(piece.content());
            if (left == null || left == 0) {
                reembedded++;
            } else {
                available.put(piece.content(), left - 1);
            }
        }

        return new Cost(after.size(), reembedded,
                embedsSentences ? sentenceCount(edited) : 0);
    }

    /** How many texts the semantic chunker embeds to decide where the boundaries go. */
    private static int sentenceCount(String text) {
        BreakIterator sentences = BreakIterator.getSentenceInstance(Locale.ENGLISH);
        sentences.setText(text);
        int count = 0;
        for (int start = sentences.first(), end = sentences.next();
             end != BreakIterator.DONE;
             start = end, end = sentences.next()) {
            if (!text.substring(start, end).isBlank()) {
                count++;
            }
        }
        return count;
    }

    private static int total(List<Cost> costs) {
        return costs.stream().mapToInt(Cost::totalEmbedded).sum();
    }

    private static void report(int documents,
                               Map<String, List<Cost>> fixed,
                               Map<String, List<Cost>> semantic) {
        System.out.printf("%n  Re-index cost over %d documents, texts embedded%n", documents);
        System.out.println("  " + "-".repeat(78));
        System.out.printf("  %-38s %10s %10s %16s%n",
                "edit", "fixed", "semantic", "semantic split");
        System.out.println("  " + "-".repeat(78));
        for (String name : fixed.keySet().stream().sorted().toList()) {
            List<Cost> f = fixed.get(name);
            List<Cost> s = semantic.get(name);
            int semanticChunks = s.stream().mapToInt(Cost::chunksReembedded).sum();
            int semanticSentences = s.stream().mapToInt(Cost::sentencesEmbedded).sum();
            System.out.printf("  %-38s %10d %10d %6d chunk + %d sent%n",
                    name, total(f), total(s), semanticChunks, semanticSentences);
        }
        System.out.println("  " + "-".repeat(78));
        System.out.printf("  %d chunks across the %d documents: fixed %d, semantic %d%n",
                fixed.get("no change").stream().mapToInt(Cost::chunksAfter).sum(),
                documents,
                fixed.get("no change").stream().mapToInt(Cost::chunksAfter).sum(),
                semantic.get("no change").stream().mapToInt(Cost::chunksAfter).sum());
        System.out.println();
        System.out.println("  Chunks alone, with boundary detection set aside:");
        for (String name : fixed.keySet().stream().sorted().toList()) {
            System.out.printf("  %-38s %10d %10d%n", name,
                    fixed.get(name).stream().mapToInt(Cost::chunksReembedded).sum(),
                    semantic.get(name).stream().mapToInt(Cost::chunksReembedded).sum());
        }
    }

    private static List<Path> longestDocuments(int count) throws IOException {
        try (var files = Files.list(CORPUS)) {
            return files.filter(path -> path.toString().endsWith(".md"))
                    .sorted((a, b) -> Long.compare(size(b), size(a)))
                    .limit(count)
                    .toList();
        }
    }

    private static long size(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            return 0;
        }
    }

    private static String flipMiddle(String text) {
        int at = text.length() / 2;
        while (at < text.length() && !Character.isLetter(text.charAt(at))) {
            at++;
        }
        char replacement = text.charAt(at) == 'e' ? 'a' : 'e';
        return text.substring(0, at) + replacement + text.substring(at + 1);
    }

    private static String insertAt(String text, int at, String insertion) {
        return text.substring(0, at) + insertion + text.substring(at);
    }

    private static int firstParagraphEnd(String text) {
        int at = text.indexOf("\n\n");
        return at < 0 ? text.length() : at;
    }

    private static String dropMiddle(String text) {
        String[] paragraphs = text.split("\n\n");
        if (paragraphs.length < 3) {
            return text;
        }
        var kept = new ArrayList<>(List.of(paragraphs));
        kept.remove(kept.size() / 2);
        return String.join("\n\n", kept);
    }
}
