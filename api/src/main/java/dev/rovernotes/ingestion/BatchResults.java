package dev.rovernotes.ingestion;

import java.util.HashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Reads a batch's JSONL results into answers keyed by the id they were asked under.
 *
 * <p>Keyed rather than ordered, and that is the whole point of this class existing. The
 * API's documentation is explicit that results arrive in any order, so a caller that zips
 * them against its input list mislabels every row the moment the order differs — and it
 * differs silently, producing a corpus where each chunk carries a sentence describing a
 * different chunk. Nothing downstream could detect that.
 *
 * <p>A line that failed, was cancelled or expired is left out rather than recorded as an
 * empty answer. The caller's question is "which of these came back", and an empty string
 * is a valid answer to a different question.
 */
final class BatchResults {

    private static final Logger log = LoggerFactory.getLogger(BatchResults.class);

    private static final ObjectMapper JSON = new ObjectMapper();

    private BatchResults() {
    }

    /**
     * Parses JSONL into {@code custom_id -> text}, keeping only the requests that succeeded.
     *
     * <p>An unparseable line costs itself rather than the batch. These arrive as one HTTP
     * response of many thousand lines, and discarding every result because one is
     * malformed would throw away work that has already been paid for.
     */
    static Map<String, String> succeeded(String jsonl) {
        Map<String, String> answers = new HashMap<>();
        int failed = 0;

        for (String line : jsonl.split("\n")) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonNode node = JSON.readTree(line);
                JsonNode result = node.path("result");

                if (!"succeeded".equals(result.path("type").asText())) {
                    failed++;
                    continue;
                }

                String text = text(result.path("message"));
                if (!text.isBlank()) {
                    answers.put(node.path("custom_id").asText(), text);
                }
            } catch (com.fasterxml.jackson.core.JacksonException e) {
                failed++;
            }
        }

        if (failed > 0) {
            log.warn("{} of {} batch results were not usable", failed, failed + answers.size());
        }
        return answers;
    }

    /**
     * The text of a message, concatenated across its blocks.
     *
     * <p>Concatenated rather than taking the first, because a response can arrive as
     * several text blocks and reading only the first truncates it at a boundary the
     * caller never chose.
     */
    private static String text(JsonNode message) {
        StringBuilder text = new StringBuilder();
        for (JsonNode block : message.path("content")) {
            if ("text".equals(block.path("type").asText())) {
                text.append(block.path("text").asText());
            }
        }
        return text.toString().strip();
    }
}
