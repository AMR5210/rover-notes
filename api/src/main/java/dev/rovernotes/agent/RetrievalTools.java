package dev.rovernotes.agent;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import dev.rovernotes.retrieval.RetrievalService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

/**
 * The one tool the agent has, bound to one question.
 *
 * <p>A new instance per question rather than a shared bean, because it carries two pieces
 * of per-question state that must not leak between callers: the owner whose corpus is
 * being searched, and the ledger deciding what number each passage is cited by. Passing
 * the owner as a tool argument instead would make it something the model could choose,
 * which is the last thing it should be.
 *
 * <p>Searching is the whole tool set. The roadmap's Week 7 note is deliberate about this —
 * a small set first, and more only where the eval harness shows the addition earns its
 * cost. Reading a whole document is the obvious next candidate and is not here yet,
 * because a chunk already carries its surrounding context and a document would spend a lot
 * of tokens to add the rest.
 */
class RetrievalTools {

    private final RetrievalService retrieval;
    private final UUID ownerId;
    private final SourceLedger ledger;
    private final int limit;

    /**
     * Searches made so far. The loop reads this to decide whether the agent is making
     * progress or circling, which a model cannot be relied on to notice about itself.
     */
    private final AtomicInteger searches = new AtomicInteger();

    RetrievalTools(RetrievalService retrieval, UUID ownerId, SourceLedger ledger, int limit) {
        this.retrieval = retrieval;
        this.ownerId = ownerId;
        this.ledger = ledger;
        this.limit = limit;
    }

    /**
     * The description is the interface.
     *
     * <p>It is what the model reads to decide whether to call this and what to send, so it
     * says what the corpus is and what a good query looks like. The instruction to search
     * again is the behaviour that separates a loop from a single pass.
     *
     * <p><strong>The test it asks the model to apply is about coverage, not
     * sufficiency.</strong> Asking the model to search again "if the results do not answer
     * the question" cannot catch the case a loop exists for: a question with two parts
     * returns passages that answer the first part, and those read as an answer. Measured
     * over the 36 two-hop questions, that wording produced exactly one search per question
     * every time — the model applies the test it is given and passes it.
     *
     * <p>This is the late-binding problem, and it is why the test has to be about coverage
     * rather than sufficiency: the query for a second hop cannot be written until the first
     * is resolved, so what the model has to check is which parts of the question the
     * passages do not speak to, not whether what it holds reads as an answer. Anthropic's
     * own guidance on writing tools puts the description first among the things that steer
     * this, and prefers many narrow searches to one broad one.
     */
    @Tool(description = """
            Search the user's personal notes and return the passages that match, each
            numbered for citation. Numbers are stable: a passage keeps the same number
            however many times it is returned.

            Prefer specific wording drawn from the question, and prefer several narrow
            searches to one broad one.

            Before answering, split the question into the separate things it asks and check
            each one against the passages you hold. A question can ask about two subjects at
            once, and passages that fully answer the first will read as an answer while the
            second is still missing. If any part of the question is not covered by a passage
            you have, search for that part on its own, in its own words — not the whole
            question again. A second search is cheap; an answer missing half its question is
            not.""")
    String search(@ToolParam(description = "What to look for, in natural language")
                  String query) {
        searches.incrementAndGet();
        List<RetrievalService.RetrievedChunk> hits = retrieval.search(ownerId, query, limit);
        if (hits.isEmpty()) {
            // Said plainly, because an empty result is information: it is the difference
            // between "search again" and "the notes do not cover this".
            return "No passages matched that query. Nothing in the notes matched these "
                    + "terms; try different wording, or say the notes do not cover it.";
        }

        // A coverage check was appended here — "name the passage covering each part of the
        // question, and search for any part you cannot name one for" — on the reasoning
        // that what a tool returns steers what the agent does next, at the point the
        // decision is actually taken. Measured over the four questions that need a second
        // hop, it changed nothing: 2.00 model calls per question before and after, 0 of 4
        // recovered either way. Removed rather than left in, because it is tokens on every
        // tool result in every request for an effect that was looked for and not found.
        return ledger.add(hits).stream()
                .map(SourceLedger.Entry::rendered)
                .reduce((a, b) -> a + "\n\n" + b)
                .orElseThrow();
    }

    int searchCount() {
        return searches.get();
    }
}
