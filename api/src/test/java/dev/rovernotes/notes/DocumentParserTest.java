package dev.rovernotes.notes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Reading the parsing service's answer off the wire.
 *
 * <p>Against a loopback server rather than a mocked client, because what is being checked
 * is deserialisation and a mock is the one thing that cannot get it wrong. The service is
 * a Python one and serialises snake_case; nothing here configures a naming strategy, so a
 * field that is not named explicitly deserialises to its zero value in silence. A page
 * range of 0 to 0 is not an error anywhere — it is a page that looks empty, and every
 * citation into the document resolves to page one.
 *
 * <p>The bodies below are written as literal JSON rather than serialised from the records
 * under test. Round-tripping through the same annotations that are being checked would
 * agree with itself whatever those annotations said.
 */
class DocumentParserTest {

    private HttpServer server;
    private DocumentParser parser;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private final List<String> requestHeaders = new CopyOnWriteArrayList<>();
    private volatile String responseBody = "{}";
    private volatile int responseStatus = 200;

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        com.sun.net.httpserver.HttpHandler handler = exchange -> {
            requestHeaders.add(exchange.getRequestHeaders().entrySet().toString());
            requests.add(new String(exchange.getRequestBody().readAllBytes(),
                    StandardCharsets.UTF_8));
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(responseStatus, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        };
        server.createContext("/parse/pdf", handler);
        server.createContext("/parse/url", handler);
        server.start();
        parser = new DocumentParser(
                "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(10));
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private static final String TWO_PAGES = """
            {
              "text": "Page one.\\n\\nPage two.\\n\\n",
              "pages": [
                {"number": 1, "char_start": 0,  "char_end": 11, "tables": 0, "ocr": false},
                {"number": 2, "char_start": 11, "char_end": 22, "tables": 2, "ocr": true}
              ],
              "table_count": 2,
              "truncated": false,
              "empty_pages": []
            }
            """;

    @Test
    void pageSpansAreReadFromTheirSnakeCaseNames() {
        // The failure this guards: charStart deserialising to zero would give every page
        // the range 0 to 0, which resolves every citation in the document to page one and
        // looks like valid data all the way down.
        responseBody = TWO_PAGES;

        var parsed = parser.parsePdf("report.pdf", "%PDF".getBytes(StandardCharsets.UTF_8));

        assertThat(parsed.pages()).hasSize(2);
        assertThat(parsed.pages().get(1).charStart()).isEqualTo(11);
        assertThat(parsed.pages().get(1).charEnd()).isEqualTo(22);
        assertThat(parsed.pages().get(1).tables()).isEqualTo(2);
    }

    @Test
    void theTableCountIsReadFromItsSnakeCaseName() {
        responseBody = TWO_PAGES;

        assertThat(parser.parsePdf("report.pdf", "%PDF".getBytes(StandardCharsets.UTF_8))
                .tableCount()).isEqualTo(2);
    }

    @Test
    void aPageReadByOcrIsReportedAsSuch() {
        // Recognised text carries errors an extracted layer does not, so this has to
        // survive the hop between the two services rather than being lost in it.
        responseBody = TWO_PAGES;

        var parsed = parser.parsePdf("report.pdf", "%PDF".getBytes(StandardCharsets.UTF_8));

        assertThat(parsed.pages().get(0).ocr()).isFalse();
        assertThat(parsed.pages().get(1).ocr()).isTrue();
    }

    @Test
    void pageRangesConvertToTheFormTheRepositoryStores() {
        responseBody = TWO_PAGES;

        var ranges = parser.parsePdf("report.pdf", "%PDF".getBytes(StandardCharsets.UTF_8))
                .pageRanges();

        assertThat(ranges).containsExactly(
                new DocumentPages.Page(1, 0, 11, 0),
                new DocumentPages.Page(2, 11, 22, 2));
    }

    @Test
    void aDocumentWhereNoPageProducedTextIsReportedUnreadable() {
        // A scan with no text layer that OCR could not read either. Stored, it indexes to
        // nothing and never comes back from a search, so the caller is told at upload.
        responseBody = """
                {
                  "text": "",
                  "pages": [
                    {"number": 1, "char_start": 0, "char_end": 0, "tables": 0, "ocr": false},
                    {"number": 2, "char_start": 0, "char_end": 0, "tables": 0, "ocr": false}
                  ],
                  "table_count": 0,
                  "truncated": false,
                  "empty_pages": [1, 2]
                }
                """;

        assertThat(parser.parsePdf("scan.pdf", "%PDF".getBytes(StandardCharsets.UTF_8))
                .unreadable()).isTrue();
    }

    @Test
    void aDocumentWithSomeReadablePagesIsNotUnreadable() {
        responseBody = """
                {
                  "text": "Page two.\\n\\n",
                  "pages": [
                    {"number": 1, "char_start": 0, "char_end": 0,  "tables": 0, "ocr": false},
                    {"number": 2, "char_start": 0, "char_end": 11, "tables": 0, "ocr": true}
                  ],
                  "table_count": 0,
                  "truncated": false,
                  "empty_pages": [1]
                }
                """;

        assertThat(parser.parsePdf("scan.pdf", "%PDF".getBytes(StandardCharsets.UTF_8))
                .unreadable()).isFalse();
    }

    @Test
    void theFileIsSentAsAFilePartRatherThanAField() {
        // Without a filename in the part header the service reads it as a form field and
        // rejects the request for a missing upload — a bad request against a correct one.
        responseBody = TWO_PAGES;

        parser.parsePdf("quarterly-report.pdf", "%PDF".getBytes(StandardCharsets.UTF_8));

        assertThat(requests).singleElement().satisfies(body -> {
            assertThat(body).contains("name=\"file\"");
            assertThat(body).contains("filename=\"quarterly-report.pdf\"");
        });
    }

    @Test
    void aClippedPageIsReadFromTheResponse() {
        responseBody = """
                {
                  "url": "https://example.com/moved",
                  "title": "Retrieval fuses ranked lists",
                  "text": "Reciprocal rank fusion combines two ranked lists."
                }
                """;

        var clipped = parser.clipUrl("https://example.com/article");

        assertThat(clipped.title()).isEqualTo("Retrieval fuses ranked lists");
        assertThat(clipped.text()).startsWith("Reciprocal rank fusion");
    }

    @Test
    void theUrlReportedBackIsWhereThePageActuallyCameFrom() {
        // After a redirect the destination is what a reader following the citation should
        // open, so it is the URL recorded rather than the one the caller supplied.
        responseBody = """
                {
                  "url": "https://example.com/moved",
                  "title": "A title",
                  "text": "Body text long enough to be worth indexing."
                }
                """;

        assertThat(parser.clipUrl("https://example.com/article").url())
                .isEqualTo("https://example.com/moved");
    }

    @Test
    void aRefusedUrlIsTheCallersMistake() {
        // A private address, a page that would not load, one with nothing to index. The
        // request was well formed; what failed was the URL it named.
        responseStatus = 422;
        responseBody = "{\"detail\":\"resolves to 127.0.0.1, which is not a public address\"}";

        assertThatThrownBy(() -> parser.clipUrl("http://localhost/admin"))
                .isInstanceOf(DocumentParser.UnreadableFile.class)
                .hasMessageContaining("localhost");
    }

    @Test
    void aParsingServiceFailureOnAClipIsNotAMisnamedBadUrl() {
        responseStatus = 500;
        responseBody = "{\"detail\":\"boom\"}";

        assertThatThrownBy(() -> parser.clipUrl("https://example.com/article"))
                .isInstanceOf(DocumentParser.ParsingUnavailable.class);
    }

    @Test
    void theUrlIsSentAsJson() {
        responseBody = """
                {"url": "https://example.com/a", "title": "t", "text": "body text here"}
                """;

        parser.clipUrl("https://example.com/article");

        assertThat(requests).singleElement()
                .satisfies(body -> assertThat(body).contains("https://example.com/article"));
    }

    @Test
    void theRequestDoesNotAttemptAnHttp2Upgrade() {
        // The JDK's client prefers HTTP/2 and opens a cleartext connection with an h2c
        // upgrade. uvicorn speaks HTTP/1.1 only; its refusal leaves the rest of the
        // request mis-framed, so a correct multipart body arrives unparseable and the
        // service reports the file field as missing.
        //
        // Nothing about that failure points at the cause — the body is byte-correct and
        // replaying it with curl succeeds — so the header is asserted directly. This
        // server accepts the upgrade attempt happily, which is exactly why the rest of
        // this suite did not catch it.
        responseBody = TWO_PAGES;

        parser.parsePdf("report.pdf", "%PDF".getBytes(StandardCharsets.UTF_8));

        assertThat(requestHeaders).singleElement().satisfies(headers -> {
            assertThat(headers).doesNotContainIgnoringCase("upgrade");
            assertThat(headers).doesNotContainIgnoringCase("http2-settings");
        });
    }

    @Test
    void aClipRequestDoesNotAttemptAnHttp2UpgradeEither() {
        responseBody = """
                {"url": "https://example.com/a", "title": "t", "text": "body text here"}
                """;

        parser.clipUrl("https://example.com/article");

        assertThat(requestHeaders).singleElement().satisfies(headers -> {
            assertThat(headers).doesNotContainIgnoringCase("upgrade");
            assertThat(headers).doesNotContainIgnoringCase("http2-settings");
        });
    }

    @Test
    void aRejectedFileIsTheCallersMistake() {
        responseStatus = 422;
        responseBody = "{\"detail\":\"could not read as PDF\"}";

        assertThatThrownBy(() -> parser.parsePdf("not.pdf", new byte[]{1, 2}))
                .isInstanceOf(DocumentParser.UnreadableFile.class)
                .hasMessageContaining("not.pdf");
    }

    @Test
    void aServiceFailureIsNotReportedAsABadFile() {
        // A 500 from the parser is this system's problem, and dressing it as an
        // unreadable file would send somebody to look at the document instead.
        responseStatus = 500;
        responseBody = "{\"detail\":\"boom\"}";

        assertThatThrownBy(() -> parser.parsePdf("report.pdf", new byte[]{1, 2}))
                .isInstanceOf(DocumentParser.ParsingUnavailable.class);
    }

    /*
     * The three ways the service can be absent rather than unhappy with the input.
     *
     * Each was an IllegalStateException, which nothing handled, so each reached the
     * caller as a bare 500 — the same answer a corrupt file got. They are separated here
     * because the interface tells the reader to try the file again in one case and to
     * try a different file in the other, and it decides which from the type.
     */

    @Test
    void aServiceInstalledWithoutTheParsingExtraIsUnavailableRatherThanABadFile() {
        // What `uv sync` without `--extra parsing` produces: the service is up and
        // answers 501 on this path alone. The file is fine and will parse once the
        // dependency is installed.
        responseStatus = 501;
        responseBody = "{\"detail\":\"installed without the parsing extra\"}";

        assertThatThrownBy(() -> parser.parsePdf("report.pdf", new byte[]{1, 2}))
                .isInstanceOf(DocumentParser.ParsingUnavailable.class);
    }

    @Test
    void aServiceThatIsNotListeningIsUnavailable() throws IOException {
        // The case that produced the 500 in practice: `make api` without `make ml`.
        server.stop(0);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        DocumentParser unreachable = new DocumentParser(
                "http://127.0.0.1:" + server.getAddress().getPort(), Duration.ofSeconds(2));

        assertThatThrownBy(() -> unreachable.parsePdf("report.pdf", new byte[]{1, 2}))
                .isInstanceOf(DocumentParser.ParsingUnavailable.class)
                .hasMessageContaining("unreachable");
    }

    @Test
    void anEmptyBodyFromTheServiceIsUnavailableRatherThanAnEmptyDocument() {
        // A 200 with nothing in it. Storing the result would commit a document with no
        // text, which searches as a document that exists and answers nothing.
        responseStatus = 200;
        responseBody = "";

        assertThatThrownBy(() -> parser.parsePdf("report.pdf", new byte[]{1, 2}))
                .isInstanceOf(DocumentParser.ParsingUnavailable.class);
    }

    @Test
    void aRefusedUrlIsStillTheCallersMistakeAfterTheSplit() {
        // The guard on the split itself: 422 must not have been swept into the new type.
        responseStatus = 422;
        responseBody = "{\"detail\":\"private address\"}";

        assertThatThrownBy(() -> parser.clipUrl("http://169.254.169.254/"))
                .isInstanceOf(DocumentParser.UnreadableFile.class);
    }
}
