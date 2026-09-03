package dev.rovernotes.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

/**
 * Reading a batch's results, which do not come back in the order they were sent.
 *
 * <p>That is stated outright in the API's documentation, and it is the failure this whole
 * class exists to prevent: a caller that zips results against its input list mislabels
 * every row the moment the order differs, producing a corpus where each chunk carries a
 * sentence describing some other chunk. Nothing downstream could detect it — the text is
 * well formed, the row count is right, and retrieval simply gets quietly worse.
 *
 * <p>The JSONL below is the shape the documentation prints, kept literal rather than
 * generated, so these tests fail if the shape is misread rather than agreeing with
 * whatever this project believes it to be.
 */
class BatchResultsTest {

    private static String succeeded(String customId, String text) {
        return """
                {"custom_id":"%s","result":{"type":"succeeded","message":{\
                "id":"msg_01","type":"message","role":"assistant","model":"claude-haiku-4-5",\
                "content":[{"type":"text","text":"%s"}],"stop_reason":"end_turn",\
                "usage":{"input_tokens":10,"output_tokens":4}}}}"""
                .formatted(customId, text);
    }

    @Test
    void resultsAreKeyedByTheIdTheyWereAskedUnder() {
        // Deliberately returned in the opposite order to how they would have been sent.
        String jsonl = succeeded("chunk-two", "About the second chunk.") + "\n"
                + succeeded("chunk-one", "About the first chunk.");

        var answers = BatchResults.succeeded(jsonl);

        assertThat(answers)
                .containsEntry("chunk-one", "About the first chunk.")
                .containsEntry("chunk-two", "About the second chunk.");
    }

    @Test
    void aFailedRequestIsAbsentRatherThanEmpty() {
        // "Which came back" is the caller's question, and an empty string answers a
        // different one — it would be written to the column as a valid annotation.
        String jsonl = succeeded("chunk-one", "About the first chunk.") + "\n"
                + """
                {"custom_id":"chunk-two","result":{"type":"errored","error":{"type":"error",\
                "error":{"type":"invalid_request_error","message":"too long"}}}}""";

        var answers = BatchResults.succeeded(jsonl);

        assertThat(answers).containsOnlyKeys("chunk-one");
    }

    @Test
    void cancelledAndExpiredResultsAreAbsentToo() {
        String jsonl = """
                {"custom_id":"a","result":{"type":"canceled"}}
                {"custom_id":"b","result":{"type":"expired"}}
                """ + succeeded("c", "kept");

        assertThat(BatchResults.succeeded(jsonl)).containsOnlyKeys("c");
    }

    @Test
    void aFailedResultIsExcludedByItsTypeRatherThanByHavingNoText() {
        // The type is the documented discriminator, and it has to be what decides. A
        // result that failed part-way can still carry text; excluded only by emptiness,
        // that partial output would be written to the column as a finished annotation.
        String jsonl = """
                {"custom_id":"chunk-one","result":{"type":"errored",\
                "message":{"content":[{"type":"text","text":"a partial sentence"}]},\
                "error":{"type":"error","error":{"type":"overloaded_error"}}}}""";

        assertThat(BatchResults.succeeded(jsonl)).isEmpty();
    }

    @Test
    void aMessageSplitAcrossBlocksIsJoinedRatherThanTruncated() {
        // Reading only the first block cuts the sentence at a boundary the caller never
        // chose, and the result still looks like a sentence.
        String jsonl = """
                {"custom_id":"chunk-one","result":{"type":"succeeded","message":{\
                "content":[{"type":"text","text":"This chunk describes "},\
                {"type":"text","text":"the retry bound."}]}}}""";

        assertThat(BatchResults.succeeded(jsonl))
                .containsEntry("chunk-one", "This chunk describes the retry bound.");
    }

    @Test
    void aNonTextBlockIsSkipped() {
        String jsonl = """
                {"custom_id":"chunk-one","result":{"type":"succeeded","message":{\
                "content":[{"type":"thinking","thinking":"considering"},\
                {"type":"text","text":"The answer."}]}}}""";

        assertThat(BatchResults.succeeded(jsonl)).containsEntry("chunk-one", "The answer.");
    }

    @Test
    void oneMalformedLineCostsItselfRatherThanTheBatch() {
        // These arrive as one response of many thousand lines. Discarding all of them
        // because one is truncated throws away work already paid for.
        String jsonl = succeeded("chunk-one", "kept") + "\n"
                + "{\"custom_id\":\"chunk-two\",\"result\":{\"type\":\"succ"
                + "\n" + succeeded("chunk-three", "also kept");

        var answers = BatchResults.succeeded(jsonl);

        assertThat(answers).containsOnlyKeys("chunk-one", "chunk-three");
    }

    @Test
    void anEmptyAnswerIsNotRecorded() {
        // A model that returned nothing has not annotated the chunk, and writing "" would
        // mark it done.
        String jsonl = succeeded("chunk-one", "") + "\n" + succeeded("chunk-two", "kept");

        assertThat(BatchResults.succeeded(jsonl)).containsOnlyKeys("chunk-two");
    }

    @Test
    void blankLinesAreIgnored() {
        String jsonl = "\n" + succeeded("chunk-one", "kept") + "\n\n";

        assertThat(BatchResults.succeeded(jsonl)).containsOnlyKeys("chunk-one");
    }

    @Test
    void noResultsIsAnEmptyMapRatherThanAFailure() {
        assertThat(BatchResults.succeeded("")).isEmpty();
    }
}
