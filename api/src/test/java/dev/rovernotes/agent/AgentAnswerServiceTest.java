package dev.rovernotes.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import dev.rovernotes.TestAccounts;
import dev.rovernotes.TestDatabase;
import dev.rovernotes.retrieval.RetrievalService;
import dev.rovernotes.usage.UsageRecorder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.TestPropertySource;

/**
 * The loop, with the model scripted rather than called.
 *
 * <p>What is under test is the loop's own behaviour: that it searches again when the model
 * asks, that a passage keeps its number across searches, that every model call is priced,
 * and that a model which never stops asking is stopped. None of that needs a real model,
 * and a real one could not be made to produce each case reliably anyway — a scripted
 * response can be told to loop forever, which is the case that matters most.
 *
 * <p>Whether the loop answers <em>better</em> is a different question, and not one a test
 * can settle. That is the generation eval's job, and until it has run the loop stays off.
 */
@SpringBootTest
@ActiveProfiles("local")
@TestPropertySource(properties = {"rover.agent.max-searches=2", "rover.agent.search-limit=3"})
class AgentAnswerServiceTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    AgentAnswerService agent;

    @Autowired
    JdbcClient jdbc;

    @MockitoBean
    ChatModel chatModel;

    @MockitoBean
    RetrievalService retrieval;

    @MockitoBean
    UsageRecorder usage;

    private UUID owner;

    /** Responses the model will give, in order. */
    private final Deque<ChatResponse> scripted = new ArrayDeque<>();

    @BeforeEach
    void setUp() {
        owner = TestAccounts.create(jdbc);
        scripted.clear();
        when(chatModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                .thenAnswer(invocation -> scripted.isEmpty()
                        ? says("Nothing left to say.")
                        : scripted.poll());
    }

    private static ChatResponse says(String text) {
        return new ChatResponse(List.of(new Generation(new AssistantMessage(text))));
    }

    /** A response asking for one search, as the model would return one. */
    private static ChatResponse searchesFor(String query) {
        return new ChatResponse(List.of(new Generation(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall(
                        UUID.randomUUID().toString(), "function", "search",
                        "{\"query\":\"" + query + "\"}")))
                .build())));
    }

    private static RetrievalService.RetrievedChunk chunk(String title, String content) {
        return new RetrievalService.RetrievedChunk(UUID.randomUUID(), UUID.randomUUID(),
                title, content, 0, content.length(), 1.0);
    }

    /**
     * The tools actually reach the model.
     *
     * <p>Worth its own test because the failure is silent. The generic
     * {@code ToolCallingChatOptions} builder accepts tool callbacks and the Anthropic model
     * does not receive them — the request goes out with no tools, and the model, told in
     * the system prompt that it has a search tool, writes an imitation of tool-call syntax
     * into its answer. Nothing throws, the answer reads plausibly, and it cites nothing.
     * Asserting the provider's own options type is what pins the correction.
     */
    @Test
    void handsTheModelOptionsThatActuallyCarryTheTool() {
        scripted.add(says("Answered."));

        agent.answer(owner, "a question");

        var prompt = org.mockito.ArgumentCaptor
                .forClass(org.springframework.ai.chat.prompt.Prompt.class);
        verify(chatModel, atLeast(1)).call(prompt.capture());
        var options = prompt.getValue().getOptions();
        assertThat(options).isInstanceOf(
                org.springframework.ai.anthropic.AnthropicChatOptions.class);
        assertThat(((org.springframework.ai.model.tool.ToolCallingChatOptions) options)
                .getToolCallbacks())
                .as("a request with no tool callbacks reaches the model with no tools")
                .hasSize(1);
    }

    @Test
    void answersWithoutSearchingWhenTheModelDoesNotAskTo() {
        scripted.add(says("The notes do not cover this."));

        AnswerService.Answer answer = agent.answer(owner, "anything");

        assertThat(answer.content()).isEqualTo("The notes do not cover this.");
        assertThat(answer.citations()).isEmpty();
    }

    @Test
    void searchesAgainWhenTheFirstAttemptDoesNotAnswer() {
        // The whole reason for a loop. A single pass retrieves once against the question as
        // asked; this retrieves again with wording the model chose after reading the first
        // result.
        when(retrieval.search(any(), anyString(), anyInt()))
                .thenReturn(List.of(chunk("a", "not what was asked about")))
                .thenReturn(List.of(chunk("b", "the passage that answers it")));
        scripted.add(searchesFor("first wording"));
        scripted.add(searchesFor("better wording"));
        scripted.add(says("Answered from the second search [2]."));

        AnswerService.Answer answer = agent.answer(owner, "a question");

        verify(retrieval, atLeast(2)).search(any(), anyString(), anyInt());
        assertThat(answer.content()).contains("[2]");
        assertThat(answer.citations()).hasSize(2);
    }

    @Test
    void keepsAPassagesNumberWhenTheSecondSearchReturnsItAgain() {
        RetrievalService.RetrievedChunk repeated = chunk("a", "a passage both searches find");
        when(retrieval.search(any(), anyString(), anyInt()))
                .thenReturn(List.of(repeated))
                .thenReturn(List.of(chunk("b", "something new"), repeated));
        scripted.add(searchesFor("first"));
        scripted.add(searchesFor("second"));
        scripted.add(says("Done [1]."));

        AnswerService.Answer answer = agent.answer(owner, "a question");

        // Two distinct passages, and the repeat did not take a third number. Without this,
        // a [1] written after the first search would point at a different passage by the
        // time the answer was read.
        assertThat(answer.citations()).hasSize(2);
        assertThat(answer.citations().getFirst().chunkId()).isEqualTo(repeated.chunkId());
        assertThat(answer.citations().getFirst().number()).isEqualTo(1);
    }

    @Test
    void pricesEveryModelCallRatherThanOnlyTheLast() {
        // The reason the loop is written out rather than delegated to ChatClient. An answer
        // that searched twice costs three model calls, and a system that recorded one would
        // under-report spend by the amount the loop added.
        when(retrieval.search(any(), anyString(), anyInt()))
                .thenReturn(List.of(chunk("a", "something")));
        scripted.add(searchesFor("first"));
        scripted.add(searchesFor("second"));
        scripted.add(says("Answered."));

        agent.answer(owner, "a question");

        verify(usage, org.mockito.Mockito.times(3)).record(any(UsageRecorder.Call.class));
    }

    @Test
    void stopsAModelThatKeepsSearching() {
        // The failure mode that turns one question into an open-ended bill. The script never
        // stops asking, so the bound is the only thing that ends this.
        when(retrieval.search(any(), anyString(), anyInt()))
                .thenReturn(List.of(chunk("a", "something")));
        for (int i = 0; i < 20; i++) {
            scripted.add(searchesFor("again " + i));
        }

        AnswerService.Answer answer = agent.answer(owner, "a question");

        // max-searches is 2 here, so the loop stops and asks for an answer from what it has
        // rather than cutting off with nothing.
        verify(retrieval, org.mockito.Mockito.atMost(3)).search(any(), anyString(), anyInt());
        assertThat(answer.content()).isNotBlank();
    }

    @Test
    void saysSoRatherThanReturningNothingWhenNoAnswerIsProduced() {
        // A response carrying only tool calls and no text would otherwise come back empty,
        // which reads to a caller exactly like "the notes do not cover this".
        when(retrieval.search(any(), anyString(), anyInt())).thenReturn(List.of());
        scripted.add(new ChatResponse(List.of()));

        assertThat(agent.answer(owner, "a question").content())
                .isEqualTo("No answer was produced for this question.");
    }

    @Test
    void tellsTheModelWhenASearchFoundNothing() {
        // An empty result is information: it is the difference between searching again and
        // concluding the notes do not cover it. Returning an empty string would leave the
        // model to guess which.
        when(retrieval.search(any(), anyString(), anyInt())).thenReturn(List.of());
        scripted.add(searchesFor("something absent"));
        scripted.add(says("The notes do not cover this."));

        AnswerService.Answer answer = agent.answer(owner, "a question");

        assertThat(answer.content()).isEqualTo("The notes do not cover this.");
        assertThat(answer.citations()).isEmpty();
    }
}
