package dev.rovernotes.notes;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
 * The topic endpoints over real HTTP.
 *
 * <p>Driven through the container rather than through the service, because what this
 * covers is the part the service does not have: the status codes, and the {@code topic}
 * query parameter, whose three cases are a string the controller reads by hand.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
class TopicControllerTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    /** The owner the local profile attributes an unauthenticated request to. */
    private static final UUID DEV_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private static final Pattern ID = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"");

    @LocalServerPort
    int port;

    @Autowired
    JdbcClient jdbc;

    private final HttpClient http = HttpClient.newHttpClient();

    /**
     * A clean library before each test.
     *
     * <p>The owner is fixed by the profile rather than generated per test, so unlike the
     * service suites these methods all write as the same person and would otherwise see
     * each other's topics. Documents go first: they reference topics, and clearing them
     * here keeps the counts each test asserts about its own rows.
     */
    @BeforeEach
    void account() {
        TestAccounts.create(jdbc, DEV_OWNER);
        jdbc.sql("delete from documents where owner_id = :owner").param("owner", DEV_OWNER).update();
        jdbc.sql("delete from topics where owner_id = :owner").param("owner", DEV_OWNER).update();
    }

    private HttpResponse<String> send(String method, String path, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .header("content-type", "application/json")
                .method(method, body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(body))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static String idOf(String json) {
        Matcher matcher = ID.matcher(json);
        assertThat(matcher.find()).as("an id in %s", json).isTrue();
        return matcher.group(1);
    }

    @Test
    void createsListsRenamesAndDeletes() throws Exception {
        HttpResponse<String> created =
                send("POST", "/api/topics", "{\"name\":\"Machine learning\"}");
        assertThat(created.statusCode()).isEqualTo(201);
        String id = idOf(created.body());
        assertThat(created.body()).contains("\"documentCount\":0");

        assertThat(send("GET", "/api/topics", null).body()).contains("Machine learning");

        HttpResponse<String> renamed =
                send("PUT", "/api/topics/" + id, "{\"name\":\"Retrieval\"}");
        assertThat(renamed.statusCode()).isEqualTo(200);
        assertThat(renamed.body()).contains("Retrieval").doesNotContain("Machine learning");

        assertThat(send("DELETE", "/api/topics/" + id, null).statusCode()).isEqualTo(204);
        assertThat(send("GET", "/api/topics", null).body()).doesNotContain("Retrieval");
    }

    @Test
    void answersASecondTopicOfTheSameNameWithAConflict() throws Exception {
        send("POST", "/api/topics", "{\"name\":\"Machine learning\"}");

        HttpResponse<String> again =
                send("POST", "/api/topics", "{\"name\":\"Machine learning\"}");

        assertThat(again.statusCode()).isEqualTo(409);
        assertThat(again.body()).contains("duplicate_topic");
    }

    @Test
    void answersAnUnknownTopicWithNotFound() throws Exception {
        assertThat(send("PUT", "/api/topics/" + UUID.randomUUID(), "{\"name\":\"x\"}").statusCode())
                .isEqualTo(404);
        assertThat(send("DELETE", "/api/topics/" + UUID.randomUUID(), null).statusCode())
                .isEqualTo(404);
    }

    @Test
    void filtersTheLibraryByTopicAndByHavingNone() throws Exception {
        String topicId = idOf(send("POST", "/api/topics", "{\"name\":\"Machine learning\"}").body());

        send("POST", "/api/notes",
                "{\"title\":\"Reranking\",\"content\":\"per-token scoring\",\"topicId\":\""
                        + topicId + "\"}");
        send("POST", "/api/notes", "{\"title\":\"Unfiled\",\"content\":\"belongs nowhere\"}");

        String all = send("GET", "/api/notes", null).body();
        assertThat(all).contains("Reranking").contains("Unfiled").contains("\"total\":2");

        String inTopic = send("GET", "/api/notes?topic=" + topicId, null).body();
        assertThat(inTopic).contains("Reranking").doesNotContain("Unfiled")
                .contains("\"total\":1");

        String unfiled = send("GET", "/api/notes?topic=none", null).body();
        assertThat(unfiled).contains("Unfiled").doesNotContain("Reranking")
                .contains("\"total\":1");
    }

    @Test
    void refusesATopicFilterThatIsNeitherAnIdNorNone() throws Exception {
        HttpResponse<String> response = send("GET", "/api/notes?topic=machine-learning", null);

        assertThat(response.statusCode()).isEqualTo(400);
        assertThat(response.body()).contains("invalid_topic_filter");
    }

    @Test
    void reportsHowManyDocumentsAreInEachTopic() throws Exception {
        String topicId = idOf(send("POST", "/api/topics", "{\"name\":\"Machine learning\"}").body());
        send("POST", "/api/notes",
                "{\"title\":\"Reranking\",\"content\":\"per-token scoring\",\"topicId\":\""
                        + topicId + "\"}");

        assertThat(send("GET", "/api/topics", null).body()).contains("\"documentCount\":1");
    }

    @Test
    void filesANoteUnderATopicWhenItIsUpdated() throws Exception {
        String topicId = idOf(send("POST", "/api/topics", "{\"name\":\"Machine learning\"}").body());
        String noteId = idOf(send("POST", "/api/notes",
                "{\"title\":\"Reranking\",\"content\":\"per-token scoring\"}").body());

        HttpResponse<String> updated = send("PUT", "/api/notes/" + noteId,
                "{\"title\":\"Reranking\",\"content\":\"per-token scoring\",\"topicId\":\""
                        + topicId + "\"}");

        assertThat(updated.statusCode()).isEqualTo(200);
        assertThat(updated.body()).contains("\"topicId\":\"" + topicId + "\"");
    }
}
