package dev.rovernotes;

import java.net.http.HttpClient;
import java.time.Duration;

import org.springframework.http.client.JdkClientHttpRequestFactory;

/**
 * Request factories for the services this application calls over plain HTTP.
 *
 * <p>Pinned to HTTP/1.1, which is not a default worth inheriting. The JDK's client
 * prefers HTTP/2 and opens a cleartext connection with an h2c upgrade. A server that
 * speaks only HTTP/1.1 has to refuse that, and uvicorn's refusal leaves the rest of the
 * request mis-framed: the body arrives unparseable and the service reports the field as
 * missing.
 *
 * <p>That failure is worth naming because nothing about it points at the cause. The
 * request is well formed, the body is byte-correct, the {@code Content-Type} carries the
 * right boundary, and replaying the exact same bytes with curl succeeds. The only
 * evidence is a line in the server's log — {@code Unsupported upgrade request} — beside a
 * 422 that reads as a bad document.
 *
 * <p>Nothing is lost by pinning. Both services this talks to are HTTP/1.1: TEI tolerates
 * the upgrade attempt and answers anyway, and the Python parsing service does not.
 */
public final class HttpClients {

    private HttpClients() {
    }

    /** A factory that will not attempt an HTTP/2 upgrade, with the given read timeout. */
    public static JdkClientHttpRequestFactory http11(Duration readTimeout) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(
                HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build());
        factory.setReadTimeout(readTimeout);
        return factory;
    }
}
