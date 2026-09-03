package dev.rovernotes.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import dev.rovernotes.retrieval.RetrievalService;
import org.junit.jupiter.api.Test;

/**
 * What a bracketed number means when passages arrive from several searches.
 *
 * <p>The property under test is stability. A single retrieval pass can number its results
 * as it renders them and be correct by construction; a loop cannot, because the same
 * passage frequently comes back from more than one search and the model writes {@code [3]}
 * without knowing which one produced it. Numbering per search would give an answer whose
 * citations are right only in the order they were written.
 *
 * <p>No Spring context: this is bookkeeping over a list, and running it as a unit test
 * means the cases can be exercised in milliseconds rather than one application per case.
 */
class SourceLedgerTest {

    private static RetrievalService.RetrievedChunk chunk(String title, String content) {
        return new RetrievalService.RetrievedChunk(UUID.randomUUID(), UUID.randomUUID(),
                title, content, 0, content.length(), 1.0);
    }

    @Test
    void numbersPassagesFromOneInTheOrderTheyWereFirstSeen() {
        SourceLedger ledger = new SourceLedger();

        List<SourceLedger.Entry> entries =
                ledger.add(List.of(chunk("a", "first"), chunk("b", "second")));

        assertThat(entries).extracting(SourceLedger.Entry::number).containsExactly(1, 2);
    }

    @Test
    void continuesNumberingAcrossSearchesRatherThanStartingOver() {
        SourceLedger ledger = new SourceLedger();
        ledger.add(List.of(chunk("a", "first"), chunk("b", "second")));

        List<SourceLedger.Entry> second = ledger.add(List.of(chunk("c", "third")));

        // Restarting at 1 here would make [1] mean two different passages in one answer.
        assertThat(second).extracting(SourceLedger.Entry::number).containsExactly(3);
        assertThat(ledger.size()).isEqualTo(3);
    }

    @Test
    void keepsAPassagesNumberWhenALaterSearchReturnsItAgain() {
        // The case that makes this class exist. Two searches over one corpus overlap often,
        // and a passage that changed number between them would leave every earlier citation
        // pointing somewhere else.
        SourceLedger ledger = new SourceLedger();
        RetrievalService.RetrievedChunk repeated = chunk("a", "first");
        ledger.add(List.of(repeated, chunk("b", "second")));

        List<SourceLedger.Entry> again = ledger.add(List.of(chunk("c", "third"), repeated));

        assertThat(again).extracting(SourceLedger.Entry::number).containsExactly(3, 1);
        assertThat(ledger.size()).as("a repeat is not a new source").isEqualTo(3);
    }

    @Test
    void reportsEveryPassageSeenRatherThanOnlyTheOnesCited() {
        // The ledger does not know what the answer cited. Filtering here would drop the
        // passage behind a number the model got wrong, and the generation eval counts
        // citations that point nowhere — it cannot count what was removed before it looked.
        SourceLedger ledger = new SourceLedger();
        ledger.add(List.of(chunk("a", "first"), chunk("b", "second")));

        assertThat(ledger.citations()).hasSize(2)
                .extracting(AnswerService.Citation::number).containsExactly(1, 2);
    }

    @Test
    void rendersAPassageWithTheNumberItIsCitedBy() {
        SourceLedger ledger = new SourceLedger();

        String rendered = ledger.add(List.of(chunk("freight", "loads are assigned by deadline")))
                .getFirst().rendered();

        // Framed rather than bare: passage text is attacker-controlled, so the model has
        // to be able to tell where quoted material begins and ends. See Passages.
        assertThat(rendered).startsWith("<passage id=\"1\" title=\"freight\">")
                .contains("loads are assigned by deadline")
                .endsWith("</passage>");
    }

    @Test
    void carriesTheSpanNeededToHighlightThePassage() {
        // The property the whole citation design rests on: a number resolves to a span in a
        // document, not just to a document.
        SourceLedger ledger = new SourceLedger();
        RetrievalService.RetrievedChunk hit = chunk("freight", "a passage worth citing");
        ledger.add(List.of(hit));

        AnswerService.Citation citation = ledger.citations().getFirst();
        assertThat(citation.chunkId()).isEqualTo(hit.chunkId());
        assertThat(citation.charEnd()).isEqualTo(hit.charEnd());
    }
}
