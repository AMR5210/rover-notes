package dev.rovernotes.ingestion;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import dev.rovernotes.TestAccounts;
import dev.rovernotes.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * What the interface reads to say how much indexing is outstanding.
 *
 * <p>Driven over HTTP rather than by calling the controller, because the point of this
 * endpoint is that a browser can reach it: the same number is already on the metrics
 * endpoint, and the reason for a second one is that actuator is an operator's surface a
 * browser should not be sent to.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class IngestionStatusTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    @BeforeEach
    void account() {
        TestAccounts.create(jdbc);
    }

    private HttpResponse<String> status() throws IOException, InterruptedException {
        return http.send(
                HttpRequest.newBuilder(URI.create(
                        "http://localhost:" + port + "/api/ingestion/status")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void reportsNothingOutstandingOnAQuietSystem() throws Exception {
        jdbc.sql("delete from event_publication").update();

        HttpResponse<String> response = status();

        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).isEqualTo("{\"pending\":0}");
    }

    @Test
    void countsPublicationsThatHaveNotCompleted() throws Exception {
        jdbc.sql("delete from event_publication").update();
        // Written directly rather than by creating documents: what is under test is the
        // reading of the outbox, and a real write would race the worker that drains it.
        outstanding("dev.rovernotes.notes.DocumentChanged");
        outstanding("dev.rovernotes.notes.DocumentChanged");

        assertThat(status().body()).isEqualTo("{\"pending\":2}");
    }

    @Test
    void aCompletedPublicationIsNoLongerOutstanding() throws Exception {
        jdbc.sql("delete from event_publication").update();
        outstanding("dev.rovernotes.notes.DocumentChanged");
        jdbc.sql("update event_publication set completion_date = now()").update();

        // The distinction the interface depends on: the row stays, and the banner has to
        // clear anyway. Counting rows rather than incomplete ones would leave it up for
        // the life of the database.
        assertThat(status().body()).isEqualTo("{\"pending\":0}");
    }

    private void outstanding(String eventType) {
        jdbc.sql("""
                insert into event_publication
                    (id, listener_id, event_type, serialized_event, publication_date)
                values (gen_random_uuid(), 'test-listener', :type, '{}', now())
                """)
                .param("type", eventType)
                .update();
    }
}
