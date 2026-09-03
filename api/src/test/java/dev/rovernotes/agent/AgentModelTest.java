package dev.rovernotes.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import dev.rovernotes.TestDatabase;
import dev.rovernotes.retrieval.RetrievalService;
import org.junit.jupiter.api.Test;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * The loop asks the model the configuration names, not the one the library defaults to.
 *
 * <p>This existed as a defect, and it is the kind that produces a clean-looking number
 * rather than a failure. {@code AnthropicChatOptions.builder().build()} does not leave the
 * model null for configuration to fill in — it carries {@code claude-haiku-4-5}, Spring
 * AI's own default — and runtime options that name a model are not overridden by the
 * configured one. So the loop ran on Haiku whatever the configuration said, while the
 * single pass, which goes through {@code ChatClient}, ran on what was configured.
 *
 * <p>What that cost is the comparison the two paths exist for. Scoring the loop against
 * the single pass would have varied the model and the technique together, and reported the
 * sum as the loop's effect. Measured against the running application before the fix: the
 * same question answered through {@code /api/ask} recorded {@code claude-sonnet-5} and
 * through {@code /api/ask?agent=true} recorded {@code claude-haiku-4-5}, one process, a
 * second apart.
 */
@SpringBootTest
@ActiveProfiles("local")
class AgentModelTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    AgentAnswerService agent;

    @Autowired
    RetrievalService retrieval;

    @Value("${spring.ai.anthropic.chat.options.model}")
    String configuredModel;

    /** A real tools object: the callback provider refuses a null one. */
    private RetrievalTools tools() {
        return new RetrievalTools(retrieval, UUID.randomUUID(), new SourceLedger(), 6);
    }

    @Test
    void theLoopUsesTheConfiguredModelRatherThanTheLibraryDefault() {
        var options = agent.chatOptions(tools());

        assertThat(options.getModel()).isEqualTo(configuredModel);
    }

    @Test
    void theLibraryDefaultIsNotTheConfiguredOne() {
        // The premise of the test above. If Spring AI's default ever becomes the same
        // model this project configures, the assertion would pass without the code
        // setting anything, and the defect could return unnoticed.
        assertThat(AnthropicChatOptions.builder().build().getModel())
                .isNotEqualTo(configuredModel);
    }

    @Test
    void theLoopCarriesTheConfiguredTokenBound() {
        assertThat(agent.chatOptions(tools()).getMaxTokens()).isEqualTo(4096);
    }
}
