package dev.rovernotes.notes;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import dev.rovernotes.TestAccounts;
import dev.rovernotes.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Uploading a PDF, from the request to what is left in the database.
 *
 * <p>The parsing service is mocked. What it does with a PDF is covered by its own suite
 * in Python, and what matters here is the part neither suite sees on its own: that the
 * extracted text is stored as an ordinary document, that its page ranges are recorded
 * against it, and that a file which cannot be read is answered as the caller's mistake.
 *
 * <p>Driven over real HTTP with a hand-built multipart body rather than through MockMvc.
 * Multipart parsing is done by the container, and a test that hands the controller a
 * ready-made file object skips the part most likely to be misconfigured — which is how
 * the 1 MB default file-size limit would have gone unnoticed.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class PdfUploadTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    private static final String TEXT = """
            Timeouts by path.

            | path | budget |
            |---|---|
            | read | 150ms |
            """;

    private static final Pattern ID = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"");

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    DocumentPages pages;

    @MockitoBean
    DocumentParser parser;

    @MockitoBean
    FileStore files;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void account() {
        TestAccounts.create(jdbc);
    }

    private static DocumentParser.Parsed parsed(boolean truncated) {
        return new DocumentParser.Parsed(
                TEXT,
                List.of(new DocumentParser.Page(1, 0, 20, 0, false),
                        new DocumentParser.Page(2, 20, TEXT.length(), 1, false)),
                1,
                truncated,
                List.of());
    }

    /** A multipart body with one file part, and optionally a title field. */
    private static byte[] multipart(String boundary, String filename, byte[] content,
                                    String title) throws IOException {
        var body = new ByteArrayOutputStream();
        if (title != null) {
            body.write(("--" + boundary + "\r\n"
                    + "Content-Disposition: form-data; name=\"title\"\r\n\r\n"
                    + title + "\r\n").getBytes(StandardCharsets.UTF_8));
        }
        body.write(("--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\n"
                + "Content-Type: application/pdf\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.write(content);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return body.toByteArray();
    }

    private HttpResponse<String> upload(byte[] content, String title) throws Exception {
        return upload(content, title, "timeouts.pdf");
    }

    private HttpResponse<String> upload(byte[] content, String title, String filename)
            throws Exception {
        String boundary = "----rover" + UUID.randomUUID();
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/notes/upload"))
                        .header("content-type", "multipart/form-data; boundary=" + boundary)
                        .POST(HttpRequest.BodyPublishers.ofByteArray(
                                multipart(boundary, filename, content, title)))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> clip(String url) throws Exception {
        return http.send(
                HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/notes/clip"))
                        .header("content-type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(
                                "{\"url\":\"" + url + "\"}"))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> fetchFile(UUID id) throws Exception {
        return http.send(
                HttpRequest.newBuilder(
                                URI.create("http://localhost:" + port + "/api/notes/" + id + "/file"))
                        .GET()
                        .build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private static UUID idOf(String body) {
        Matcher matcher = ID.matcher(body);
        assertThat(matcher.find()).as("response carries a document id: %s", body).isTrue();
        return UUID.fromString(matcher.group(1));
    }

    @Test
    void anUploadedPdfBecomesADocumentWithItsExtractedText() throws Exception {
        Mockito.when(parser.parsePdf(Mockito.eq("timeouts.pdf"), Mockito.any()))
                .thenReturn(parsed(false));

        var response = upload("%PDF-1.4 pretend".getBytes(StandardCharsets.UTF_8), null);

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("\"sourceType\":\"pdf\"");
        assertThat(response.body()).contains("timeouts.pdf");
        assertThat(jdbc.sql("select content from documents where id = :id")
                .param("id", idOf(response.body()))
                .query(String.class)
                .single()).isEqualTo(TEXT);
    }

    @Test
    void thePageRangesAreRecordedAgainstTheDocument() throws Exception {
        // The reason this path exists at all: without these rows a citation into the
        // document is a character offset and nothing a reader can turn to.
        Mockito.when(parser.parsePdf(Mockito.any(), Mockito.any())).thenReturn(parsed(false));

        UUID id = idOf(upload("%PDF-1.4 pretend".getBytes(StandardCharsets.UTF_8), null).body());

        UUID key = UUID.randomUUID();
        assertThat(pages.pagesFor(List.of(new DocumentPages.Span(key, id, 50))))
                .containsEntry(key, 2);
    }

    @Test
    void theFilenameReachesTheParserAndBecomesTheTitle() throws Exception {
        Mockito.when(parser.parsePdf(Mockito.any(), Mockito.any())).thenReturn(parsed(false));

        var response = upload("%PDF-1.4 pretend".getBytes(StandardCharsets.UTF_8), null,
                "quarterly-report.pdf");

        assertThat(response.body()).contains("quarterly-report.pdf");
        Mockito.verify(parser).parsePdf(Mockito.eq("quarterly-report.pdf"), Mockito.any());
    }

    @Test
    void anExplicitTitleIsUsedInsteadOfTheFilename() throws Exception {
        Mockito.when(parser.parsePdf(Mockito.any(), Mockito.any())).thenReturn(parsed(false));

        var response = upload("%PDF-1.4 pretend".getBytes(StandardCharsets.UTF_8),
                "Service timeouts");

        assertThat(response.body()).contains("Service timeouts");
    }

    @Test
    void theUploadedBytesReachTheParserUnchanged() throws Exception {
        // A multipart body that lost or gained a byte would still parse on the far side
        // and produce a document, so the bytes are checked rather than the outcome.
        Mockito.when(parser.parsePdf(Mockito.any(), Mockito.any())).thenReturn(parsed(false));
        byte[] content = new byte[]{'%', 'P', 'D', 'F', '-', '1', '.', '4', 0x0A, 0x00, (byte) 0xFF};

        upload(content, null);

        Mockito.verify(parser).parsePdf(Mockito.any(), Mockito.argThat(
                bytes -> java.util.Arrays.equals(bytes, content)));
    }

    @Test
    void aFileThatCannotBeReadIsTheCallersMistake() throws Exception {
        // 422, not 500. The upload arrived intact and the service is working; the file
        // was not what it claimed to be.
        Mockito.when(parser.parsePdf(Mockito.any(), Mockito.any()))
                .thenThrow(new DocumentParser.UnreadableFile("timeouts.pdf"));

        var response = upload("not a pdf".getBytes(StandardCharsets.UTF_8), null);

        assertThat(response.statusCode()).isEqualTo(422);
        assertThat(response.body()).contains("unreadable_file");
    }

    @Test
    void anEmptyUploadIsRefusedBeforeItIsParsed() throws Exception {
        var response = upload(new byte[0], null);

        assertThat(response.statusCode()).isEqualTo(400);
        // Stored, it would be a document with no text that indexes to nothing and can
        // never be retrieved — visible in a list and unreachable by search.
        Mockito.verify(parser, Mockito.never()).parsePdf(Mockito.any(), Mockito.any());
    }

    @Test
    void aTruncatedDocumentIsStillStored() throws Exception {
        // Refusing would lose the pages that were read. The caller gets the document and
        // the log records the cut, which is the more useful of the two failures.
        Mockito.when(parser.parsePdf(Mockito.any(), Mockito.any())).thenReturn(parsed(true));

        assertThat(upload("%PDF-1.4 pretend".getBytes(StandardCharsets.UTF_8), null).statusCode())
                .isEqualTo(201);
    }

    @Test
    void anUploadedDocumentIsIndexedLikeAnyOther() throws Exception {
        // The point of storing the extracted text as ordinary content: nothing downstream
        // needs a PDF-shaped path. If the event were not published the document would sit
        // in the list unsearchable.
        Mockito.when(parser.parsePdf(Mockito.any(), Mockito.any())).thenReturn(parsed(false));

        UUID id = idOf(upload("%PDF-1.4 pretend".getBytes(StandardCharsets.UTF_8), null).body());

        assertThat(jdbc.sql("""
                        select count(*) from event_publication
                         where serialized_event like :pattern
                        """)
                .param("pattern", "%" + id + "%")
                .query(Long.class)
                .single()).isPositive();
    }

    @Test
    void theOriginalFileIsKept() throws Exception {
        // What makes "page 34" actionable: the reader is handed the document that has a
        // page 34, not the extracted text where pages are character ranges.
        Mockito.when(parser.parsePdf(Mockito.any(), Mockito.any())).thenReturn(parsed(false));
        Mockito.when(files.put(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn("s3://rover-uploads/owner/document");

        UUID id = idOf(upload("%PDF-1.4 pretend".getBytes(StandardCharsets.UTF_8), null).body());

        assertThat(jdbc.sql("select source_uri from documents where id = :id")
                .param("id", id)
                .query(String.class)
                .optional()
                .orElse(null)).startsWith("s3://");
    }

    @Test
    void anUploadStillSucceedsWhenObjectStorageIsUnavailable() throws Exception {
        // Best effort by design. The answer is built from the extracted text, so a
        // bucket that is unreachable costs the original file rather than the ingest —
        // and refusing the upload would discard work that succeeded.
        Mockito.when(parser.parsePdf(Mockito.any(), Mockito.any())).thenReturn(parsed(false));
        Mockito.doThrow(new IllegalStateException("bucket is unreachable"))
                .when(files).put(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any());

        var response = upload("%PDF-1.4 pretend".getBytes(StandardCharsets.UTF_8), null);

        assertThat(response.statusCode()).isEqualTo(201);
        UUID id = idOf(response.body());
        assertThat(jdbc.sql("select content from documents where id = :id")
                .param("id", id)
                .query(String.class)
                .single()).isEqualTo(TEXT);
        assertThat(jdbc.sql("select source_uri from documents where id = :id")
                .param("id", id)
                .query(String.class)
                .optional()
                .orElse(null)).isNull();
    }

    @Test
    void theStoredOriginalIsServedBack() throws Exception {
        // The point of keeping it: an answer citing page 34 is only checkable if the
        // reader can open the document that has a page 34.
        byte[] content = "%PDF-1.4 the original".getBytes(StandardCharsets.UTF_8);
        Mockito.when(parser.parsePdf(Mockito.any(), Mockito.any())).thenReturn(parsed(false));
        Mockito.when(files.put(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn("s3://rover-uploads/owner/document");
        Mockito.when(files.get(Mockito.any(), Mockito.any())).thenReturn(Optional.of(content));

        UUID id = idOf(upload(content, null).body());
        var response = fetchFile(id);

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo(content);
        assertThat(response.headers().firstValue("content-type"))
                .hasValue("application/pdf");
        assertThat(response.headers().firstValue("content-disposition"))
                .hasValueSatisfying(value -> assertThat(value).startsWith("inline"));
    }

    @Test
    void aDocumentIngestedWithoutItsOriginalHasNoFileToServe() throws Exception {
        // Storage was unavailable at ingest, so source_uri is null. 404 rather than an
        // error: the document is fine, it simply has no file behind it.
        Mockito.when(parser.parsePdf(Mockito.any(), Mockito.any())).thenReturn(parsed(false));
        Mockito.when(files.put(Mockito.any(), Mockito.any(), Mockito.any(), Mockito.any()))
                .thenReturn(null);

        UUID id = idOf(upload("%PDF-1.4 pretend".getBytes(StandardCharsets.UTF_8), null).body());

        assertThat(fetchFile(id).statusCode()).isEqualTo(404);
        // Not even attempted: the document says there is nothing to fetch.
        Mockito.verify(files, Mockito.never()).get(Mockito.any(), Mockito.any());
    }

    @Test
    void anotherOwnersFileIsNotServed() throws Exception {
        // The store has no idea who is asking, so ownership is enforced by looking the
        // document up as its owner first. Serving by key alone would let anyone who could
        // guess two identifiers read another account's uploads.
        UUID stranger = TestAccounts.create(jdbc);
        UUID theirs = jdbc.sql("""
                        insert into documents (owner_id, title, source_type, source_uri,
                                               content, content_hash)
                        values (:owner, 'their report', 'pdf', 's3://bucket/key', 'text', 'hash')
                        returning id
                        """)
                .param("owner", stranger)
                .query(UUID.class)
                .single();

        assertThat(fetchFile(theirs).statusCode()).isEqualTo(404);
        Mockito.verify(files, Mockito.never()).get(Mockito.any(), Mockito.any());
    }

    @Test
    void aDocumentThatDoesNotExistHasNoFile() throws Exception {
        assertThat(fetchFile(UUID.randomUUID()).statusCode()).isEqualTo(404);
    }

    @Test
    void aClippedPageBecomesADocumentRecordingWhereItCameFrom() throws Exception {
        Mockito.when(parser.clipUrl("https://example.com/article"))
                .thenReturn(new DocumentParser.Clipped(
                        "https://example.com/moved", "Ranked lists", "Body text worth indexing."));

        var response = clip("https://example.com/article");

        assertThat(response.statusCode()).isEqualTo(201);
        assertThat(response.body()).contains("\"sourceType\":\"web\"");
        UUID id = idOf(response.body());
        // The destination, not the URL supplied: after a redirect that is what a reader
        // following the citation should open.
        assertThat(jdbc.sql("select source_uri from documents where id = :id")
                .param("id", id)
                .query(String.class)
                .single()).isEqualTo("https://example.com/moved");
    }

    @Test
    void aClippedPageIsIndexedLikeAnyOtherDocument() throws Exception {
        Mockito.when(parser.clipUrl(Mockito.any()))
                .thenReturn(new DocumentParser.Clipped(
                        "https://example.com/a", "Ranked lists", "Body text worth indexing."));

        UUID id = idOf(clip("https://example.com/article").body());

        assertThat(jdbc.sql("""
                        select count(*) from event_publication
                         where serialized_event like :pattern
                        """)
                .param("pattern", "%" + id + "%")
                .query(Long.class)
                .single()).isPositive();
    }

    @Test
    void aRefusedUrlIsAnsweredAsTheCallersMistake() throws Exception {
        // A private address, a page that would not load, one with nothing to index.
        Mockito.when(parser.clipUrl(Mockito.any()))
                .thenThrow(new DocumentParser.UnreadableFile("http://localhost/admin"));

        assertThat(clip("http://localhost/admin").statusCode()).isEqualTo(422);
    }

    @Test
    void aFileLargerThanOneMegabyteIsAccepted() throws Exception {
        // Boot's default max-file-size is 1 MB, which would refuse most of the documents
        // this endpoint exists for — and refuse them with a container error rather than
        // this controller's. The configured bound is 25 MB.
        Mockito.when(parser.parsePdf(Mockito.any(), Mockito.any())).thenReturn(parsed(false));
        byte[] large = new byte[3 * 1024 * 1024];
        System.arraycopy("%PDF-1.4".getBytes(StandardCharsets.UTF_8), 0, large, 0, 8);

        assertThat(upload(large, null).statusCode()).isEqualTo(201);
    }
}
