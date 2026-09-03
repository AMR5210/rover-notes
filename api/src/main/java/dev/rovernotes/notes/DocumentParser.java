package dev.rovernotes.notes;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonProperty;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Extracts an uploaded file into text, by asking the Python service.
 *
 * <p>Parsing runs there rather than here for the same reason embedding runs in TEI: the
 * libraries that do it well are Python ones, and the work is CPU-bound and bursty, so it
 * suits a service that can be given its own capacity. What comes back is the shape this
 * side already indexes — one string — plus the page ranges that let a citation into it
 * name a page.
 *
 * <p>The timeout is generous by the standards of the read path and deliberately so. A
 * caller uploading a long document is waiting on work that scales with the file, not on a
 * query, and cutting it off at a read-path budget would refuse exactly the documents this
 * exists for. It is still bounded: the service caps pages and bytes, and an unbounded wait
 * here would hold a request thread on a service that has stopped answering.
 */
@Component
public class DocumentParser {

    private final RestClient client;

    DocumentParser(@Value("${rover.parsing.url:http://localhost:8000}") String baseUrl,
                   @Value("${rover.parsing.timeout:120s}") Duration timeout) {
        this.client = RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(dev.rovernotes.HttpClients.http11(timeout))
                .build();
    }

    /**
     * Parses a PDF into text and page ranges.
     *
     * @throws UnreadableFile when the service reports the bytes are not a readable PDF,
     *                        which is the caller's mistake rather than a fault here
     */
    public Parsed parsePdf(String filename, byte[] content) {
        MultiValueMap<String, Object> form = new LinkedMultiValueMap<>();
        form.add("file", new NamedResource(content, filename));

        try {
            Parsed parsed = client.post()
                    .uri("/parse/pdf")
                    // The content type is deliberately not set here. Spring's form
                    // converter generates `multipart/form-data; boundary=...`, and
                    // setting the bare media type replaces that — boundary and all. A
                    // multipart body whose header carries no boundary has no parts any
                    // parser can find, and the far side reports the field as missing,
                    // which reads as a bad request rather than a malformed one.
                    .body(form)
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.value() == 422 || status.value() == 400) {
                            throw new UnreadableFile(filename, detailOf(response));
                        }
                        if (!status.is2xxSuccessful()) {
                            throw new ParsingUnavailable(
                                    "parsing service returned " + status);
                        }
                        return response.bodyTo(Parsed.class);
                    });

            if (parsed == null) {
                throw new ParsingUnavailable("parsing service returned an empty body");
            }
            return parsed;
        } catch (RestClientException e) {
            throw new ParsingUnavailable("parsing service is unreachable", e);
        }
    }

    /**
     * Fetches a web page and returns the readable part of it.
     *
     * <p>The fetch happens in the Python service rather than here, and not only because
     * the extractor lives there: retrieving a URL a caller chose is server-side request
     * forgery unless every address the name resolves to is checked first, and on every
     * redirect. That guard is written once, beside the fetch it guards.
     *
     * @throws UnreadableFile when the URL is refused — a private address, a page that
     *                        would not load, one with nothing worth indexing
     */
    public Clipped clipUrl(String url) {
        try {
            Clipped clipped = client.post()
                    .uri("/parse/url")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("url", url))
                    .exchange((request, response) -> {
                        HttpStatusCode status = response.getStatusCode();
                        if (status.value() == 422 || status.value() == 400) {
                            throw new UnreadableFile(url, detailOf(response));
                        }
                        if (!status.is2xxSuccessful()) {
                            throw new ParsingUnavailable("parsing service returned " + status);
                        }
                        return response.bodyTo(Clipped.class);
                    });

            if (clipped == null) {
                throw new ParsingUnavailable("parsing service returned an empty body");
            }
            return clipped;
        } catch (RestClientException e) {
            throw new ParsingUnavailable("parsing service is unreachable", e);
        }
    }

    /** A fetched page: where it actually came from, what it is called, and its text. */
    public record Clipped(
            @JsonProperty("url") String url,
            @JsonProperty("title") String title,
            @JsonProperty("text") String text) {}

    /**
     * A file upload needs a filename in its part header, and a plain byte array has none.
     *
     * <p>Without it the service receives the part as a field rather than a file, and
     * rejects the request for a missing upload — which reads as a bad request against a
     * request that was correct.
     */
    private static final class NamedResource extends ByteArrayResource {

        private final String filename;

        private NamedResource(byte[] content, String filename) {
            super(content);
            this.filename = filename;
        }

        @Override
        public String getFilename() {
            return filename;
        }
    }

    /**
     * What the parsing service returns: one string, and where its pages sit in it.
     *
     * <p>Field names are given explicitly. The service is a Python one and serialises in
     * snake_case; nothing here configures a naming strategy, so a record component named
     * {@code charStart} would silently deserialise to zero — which is a page range that
     * looks valid and resolves every citation to page one.
     */
    public record Parsed(
            @JsonProperty("text") String text,
            @JsonProperty("pages") List<Page> pages,
            @JsonProperty("table_count") int tableCount,
            @JsonProperty("truncated") boolean truncated,
            @JsonProperty("empty_pages") List<Integer> emptyPages) {

        /**
         * True when no page produced any text, by extraction or by recognition.
         *
         * <p>A document like this is stored, indexed to nothing, and never returned by a
         * search — the failure the caller should hear about at upload rather than
         * discover from a query that comes back empty.
         */
        public boolean unreadable() {
            return !pages.isEmpty() && emptyPages != null && emptyPages.size() == pages.size();
        }

        /** The page ranges in the form the repository stores. */
        public List<DocumentPages.Page> pageRanges() {
            return pages.stream()
                    .map(p -> new DocumentPages.Page(p.number(), p.charStart(), p.charEnd(),
                            p.tables()))
                    .toList();
        }
    }

    /** One page's span, named as the service serialises it. */
    public record Page(
            @JsonProperty("number") int number,
            @JsonProperty("char_start") int charStart,
            @JsonProperty("char_end") int charEnd,
            @JsonProperty("tables") int tables,
            @JsonProperty("ocr") boolean ocr) {}

    /**
     * What the service said about why it refused.
     *
     * <p>Read and carried rather than discarded. The parsing service answers 422 for two
     * unrelated things — a file it could not parse, and a request it could not validate —
     * and without the detail both arrive here as "could not read your file", which sends
     * somebody to inspect a document when the fault is in the request that carried it.
     */
    private static String detailOf(org.springframework.http.client.ClientHttpResponse response) {
        try {
            String body = new String(response.getBody().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            return body.isBlank() ? "no detail given" : body;
        } catch (java.io.IOException e) {
            return "detail unavailable: " + e.getMessage();
        }
    }

    /**
     * The parsing service could not be reached, or answered with a fault of its own.
     *
     * <p>Distinct from {@link UnreadableFile} because the two need opposite responses.
     * An unreadable file is answered 422 and the caller should try a different file; this
     * is answered 503 and the same file will work once the service is back. Collapsing
     * them sends somebody away to re-scan a PDF that was always fine.
     */
    public static class ParsingUnavailable extends RuntimeException {

        public ParsingUnavailable(String detail, Throwable cause) {
            super(detail, cause);
        }

        public ParsingUnavailable(String detail) {
            super(detail);
        }
    }

    /** The bytes or the URL are not something this parser can read. */
    public static class UnreadableFile extends RuntimeException {

        public UnreadableFile(String subject) {
            super("could not read '" + subject + "'");
        }

        public UnreadableFile(String subject, String detail) {
            super("could not read '" + subject + "': " + detail);
        }
    }
}
