package dev.rovernotes.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.rovernotes.TestAccounts;
import dev.rovernotes.TestDatabase;
import dev.rovernotes.retrieval.RetrievalService;
import dev.rovernotes.usage.SpendLimit;
import dev.rovernotes.usage.UsageRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;

/**
 * What the loop actually sends the API, read off the wire.
 *
 * <p>Prompt caching cannot be verified from the code that configures it. A breakpoint is a
 * field on a content block in the request body, placed by the Anthropic model class rather
 * than by anything here, and the API's response to one that is misplaced or too short is to
 * process the request normally and return no error. Asserting that the options object holds
 * cache options would therefore pass whether or not a single {@code cache_control} ever
 * reached the wire.
 *
 * <p>So the model talks to a loopback server instead of Anthropic, and the assertions are
 * on the JSON it receives. The same reasoning as {@code RerankClientTest}: stubbing at the
 * HTTP boundary keeps the contract under test rather than agreeing with whatever the code
 * happens to send. Nothing here reaches a real API or spends anything.
 *
 * <p>What is <em>not</em> settled here is whether caching saves money. That depends on how
 * often the model searches more than once, which only a run against the real API can say;
 * the arithmetic behind the default is in {@link AgentAnswerService}, and {@code llm_usage}
 * records reads and writes separately so a run can be checked against it.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {
        "rover.agent.max-searches=2",
        "rover.agent.search-limit=3",
        "spring.ai.anthropic.api-key=not-a-real-key"
})
class AgentPromptCachingTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The {@code type} a marked search result carries in the request body. */
    private static final String TOOL_RESULT = "tool_result";

    /**
     * A tool definition has no {@code type} field, so this is what a mark on one reads as.
     */
    private static final String TOOL_DEFINITIONS = "unnamed";

    /**
     * Long enough to clear the breakpoint threshold, as a real search result is.
     *
     * <p>Three passages of a 1,600-character window is what {@code search-limit=3} returns
     * here, so a chunk this size makes the tool result the same order of magnitude as one
     * the retrieval service would actually produce.
     */
    private static final String PASSAGE = "a passage of retrieved text. ".repeat(60);

    private static HttpServer server;

    /** Every request body the model sent, in order. */
    private static final List<String> requests = new ArrayList<>();

    /** How many calls have been answered, which selects the scripted reply. */
    private static final AtomicInteger answered = new AtomicInteger();

    @DynamicPropertySource
    static void anthropicOnLoopback(DynamicPropertyRegistry registry) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (InputStream in = exchange.getRequestBody()) {
                synchronized (requests) {
                    requests.add(new String(in.readAllBytes(), StandardCharsets.UTF_8));
                }
            }
            // Three tool calls, then an answer. The third is what takes the loop to its
            // search limit and triggers the final turn, which is the request carrying the
            // most history and so the one with the most to read from cache.
            byte[] payload = (answered.getAndIncrement() < 3 ? searchesAgain() : answers())
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(200, payload.length);
            exchange.getResponseBody().write(payload);
            exchange.close();
        });
        server.start();
        registry.add("spring.ai.anthropic.base-url",
                () -> "http://127.0.0.1:" + server.getAddress().getPort());
    }

    private static String message(String content, String stopReason) {
        return """
                {"id":"msg_stub","type":"message","role":"assistant","model":"claude-sonnet-5",
                 "content":[%s],"stop_reason":"%s",
                 "usage":{"input_tokens":10,"output_tokens":5}}
                """.formatted(content, stopReason);
    }

    private static String searchesAgain() {
        return message("""
                {"type":"tool_use","id":"toolu_%s","name":"search",
                 "input":{"query":"something to look for"}}
                """.formatted(UUID.randomUUID().toString().substring(0, 8)), "tool_use");
    }

    private static String answers() {
        return message("""
                {"type":"text","text":"Answered from the passages found [1]."}
                """, "end_turn");
    }

    @Autowired
    AgentAnswerService agent;

    @Autowired
    JdbcClient jdbc;

    @Autowired
    ChatModel chatModel;

    @Autowired
    ToolCallingManager toolCallingManager;

    @Autowired
    SpendLimit spend;

    @Autowired
    CitationPages citationPages;

    @MockitoBean
    RetrievalService retrieval;

    @MockitoBean
    UsageRecorder usage;

    private UUID owner;

    @BeforeEach
    void setUp() {
        owner = TestAccounts.create(jdbc);
        synchronized (requests) {
            requests.clear();
        }
        answered.set(0);
        when(retrieval.search(any(), anyString(), anyInt())).thenReturn(List.of(
                chunk("first", PASSAGE), chunk("second", PASSAGE), chunk("third", PASSAGE)));
    }

    private static RetrievalService.RetrievedChunk chunk(String title, String content) {
        return new RetrievalService.RetrievedChunk(UUID.randomUUID(), UUID.randomUUID(),
                title, content, 0, content.length(), 1.0);
    }

    /**
     * Every request that has a search result behind it marks one, and it is the newest.
     *
     * <p>That placement is what makes the cached prefix advance. The prefix runs to the
     * marked block, so a breakpoint on the most recent search result covers the question,
     * every earlier search and the one just returned; the next iteration reads all of it
     * and writes only what it added.
     *
     * <p>The alternative is not "no caching" but caching that does not move. Only four
     * breakpoints are allowed per request and Spring AI's default minimum block length is
     * one character, so the strategy left at its defaults marks the system prompt and the
     * question — short prefixes the API declines to cache — and reaches its limit near the
     * front of the conversation. Asserting the exact set of marked blocks per request, and
     * not merely that some block is marked, is what separates the two.
     */
    @Test
    void marksTheNewestSearchResultSoTheCachedPrefixAdvances() {
        agent.answer(owner, "a question");

        List<List<String>> markedBlocks = requests.stream()
                .map(AgentPromptCachingTest::blockTypesCarryingCacheControl)
                .toList();

        assertThat(markedBlocks)
                .as("four calls: three that search and the final turn")
                .hasSize(4);
        assertThat(markedBlocks.get(0))
                .as("no search has returned yet, so there is nothing to cache")
                .doesNotContain(TOOL_RESULT);
        assertThat(markedBlocks.get(1)).containsExactlyInAnyOrder(TOOL_DEFINITIONS, TOOL_RESULT);
        assertThat(markedBlocks.get(2)).containsExactlyInAnyOrder(TOOL_DEFINITIONS, TOOL_RESULT);
        assertThat(markedBlocks.get(3))
                .as("the final turn has no tools left to mark")
                .containsExactly(TOOL_RESULT);
    }

    /**
     * The final turn withdraws the tools and keeps the caching.
     *
     * <p>It is the longest request the loop makes, so sending it without cache options —
     * which is what a prompt built with no options at all would do, falling back to
     * application defaults that say nothing about caching — would leave the largest prefix
     * of the whole question paying full input rate.
     */
    @Test
    void keepsCachingOnTheFinalTurnThatHasNoTools() {
        agent.answer(owner, "a question");

        JsonNode last = parse(requests.getLast());
        assertThat(last.has("tools") && !last.get("tools").isEmpty())
                .as("the final turn is asked to answer, not to search again")
                .isFalse();
        assertThat(blockTypesCarryingCacheControl(requests.getLast())).isNotEmpty();
    }

    /**
     * Nothing short is marked.
     *
     * <p>The cached prefix runs to the marked block, so a breakpoint on the question or on
     * the system prompt covers only what precedes it — a few hundred tokens, under the
     * API's minimum, cached neither time it is sent. The tool definitions are marked by
     * Spring AI whenever this strategy is in use and are not reachable from configuration;
     * they are recorded here as a known no-op rather than asserted away, since a request
     * may carry four breakpoints and this loop needs one.
     */
    @Test
    void leavesTheShortBlocksAtTheFrontOfTheConversationUnmarked() {
        agent.answer(owner, "a question");

        assertThat(requests.stream()
                .flatMap(body -> blockTypesCarryingCacheControl(body).stream())
                .distinct())
                .as("the system prompt, the question and the model's own turns are all "
                        + "well under the API's minimum cacheable prefix")
                .containsOnly(TOOL_RESULT, TOOL_DEFINITIONS);
    }

    /**
     * The switch reaches the wire.
     *
     * <p>A configuration flag that changes nothing is a failure this project has met
     * before, and it is invisible: a request with caching switched off and a request that
     * silently never marked anything are the same request. Building the service directly
     * rather than standing up a second application context keeps this to one constructor
     * argument, which is exactly what the property sets.
     */
    @Test
    void sendsNothingMarkedWhenCachingIsSwitchedOff() {
        new AgentAnswerService(retrieval, chatModel, toolCallingManager, usage, spend,
                citationPages, 2, 3, false, "claude-sonnet-5", 4096, 1)
                .answer(owner, "a question");

        assertThat(requests.stream()
                .flatMap(body -> blockTypesCarryingCacheControl(body).stream()))
                .isEmpty();
    }

    private static JsonNode parse(String body) {
        try {
            return JSON.readTree(body);
        } catch (IOException e) {
            throw new IllegalStateException("could not parse the request body", e);
        }
    }

    /**
     * The {@code type} of every content block carrying a {@code cache_control} field.
     *
     * <p>Walked rather than matched on text so the assertion says which blocks were marked
     * and how many, which is the whole question; counting occurrences of the field name
     * would pass for a request that marked the wrong four blocks.
     */
    private static List<String> blockTypesCarryingCacheControl(String body) {
        List<String> marked = new ArrayList<>();
        collect(parse(body), marked);
        return marked;
    }

    private static void collect(JsonNode node, List<String> marked) {
        if (node.isObject()) {
            if (node.has("cache_control") && !node.get("cache_control").isNull()) {
                marked.add(node.path("type").asText("unnamed"));
            }
            node.properties().forEach(entry -> collect(entry.getValue(), marked));
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, marked));
        }
    }
}
