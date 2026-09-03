package dev.rovernotes.agent;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import dev.rovernotes.TestAccounts;
import dev.rovernotes.TestDatabase;
import dev.rovernotes.notes.DocumentPages;
import dev.rovernotes.retrieval.RetrievalService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A citation into a paginated document names the page it is printed on.
 *
 * <p>This is what the upload path is ultimately for. Retrieval knows a passage spans
 * characters 51,200 to 51,900 of a document; a reader has a PDF and needs a page. Without
 * this step the answer is correct and unverifiable, which for a citation is most of the
 * way to being useless.
 *
 * <p>Driven at the citation-resolution step rather than through a model, because what is
 * being checked is the mapping and not the prose. The two answer paths build their
 * citation lists in different places and both pass through here; that they do is asserted
 * in {@code AnswerServiceTest} and {@code AgentAnswerServiceTest} by the shape of what
 * they return.
 */
@SpringBootTest
@ActiveProfiles("local")
class CitationPageTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    CitationPages citationPages;

    @Autowired
    DocumentPages pages;

    @Autowired
    dev.rovernotes.notes.NoteService notes;

    @Autowired
    JdbcClient jdbc;

    private UUID owner;

    @BeforeEach
    void account() {
        owner = TestAccounts.create(jdbc);
    }

    private UUID paginated() {
        UUID id = notes.create(owner, "A report", "x".repeat(300)).id();
        pages.replace(id, List.of(
                new DocumentPages.Page(1, 0, 100, 0),
                new DocumentPages.Page(2, 100, 200, 1),
                new DocumentPages.Page(3, 200, 300, 0)));
        return id;
    }

    private static AnswerService.Citation citation(int number, UUID documentId, int charStart) {
        return new AnswerService.Citation(number, UUID.randomUUID(), documentId,
                "A report", charStart, charStart + 40, 0.9);
    }

    @Test
    void aCitationIntoAPaginatedDocumentCarriesItsPage() {
        UUID id = paginated();

        var resolved = citationPages.resolve(List.of(citation(1, id, 150)));

        assertThat(resolved).singleElement()
                .extracting(AnswerService.Citation::page)
                .isEqualTo(2);
    }

    @Test
    void citationsOntoDifferentPagesEachGetTheirOwn() {
        // The failure worth guarding: one lookup for the whole answer could hand every
        // citation the first page it found and nothing about the answer would look wrong.
        UUID id = paginated();

        var resolved = citationPages.resolve(List.of(
                citation(1, id, 10), citation(2, id, 150), citation(3, id, 250)));

        assertThat(resolved).extracting(AnswerService.Citation::page)
                .containsExactly(1, 2, 3);
    }

    @Test
    void aCitationIntoATypedNoteHasNoPage() {
        // The ordinary case. A page number invented for a document that has none would be
        // worse than its absence: it reads as checkable and cannot be checked.
        UUID id = notes.create(owner, "A typed note", "nothing paginated here").id();

        var resolved = citationPages.resolve(List.of(citation(1, id, 5)));

        assertThat(resolved).singleElement()
                .extracting(AnswerService.Citation::page)
                .isNull();
    }

    @Test
    void anAnswerMixingBothKeepsEachCitationsOwnAnswer() {
        // An answer drawn from a PDF and a note at once, which is the normal shape of a
        // corpus that has both.
        UUID pdf = paginated();
        UUID note = notes.create(owner, "A typed note", "nothing paginated here").id();

        var resolved = citationPages.resolve(List.of(
                citation(1, pdf, 250), citation(2, note, 3)));

        assertThat(resolved).extracting(AnswerService.Citation::page)
                .containsExactly(3, null);
    }

    @Test
    void everythingElseAboutTheCitationSurvivesResolution() {
        // Rebuilding the record is where a field quietly goes missing, and a citation
        // that lost its span would stop highlighting while the page still looked right.
        UUID id = paginated();
        var original = citation(7, id, 150);

        var resolved = citationPages.resolve(List.of(original)).getFirst();

        assertThat(resolved.number()).isEqualTo(7);
        assertThat(resolved.chunkId()).isEqualTo(original.chunkId());
        assertThat(resolved.documentId()).isEqualTo(id);
        assertThat(resolved.title()).isEqualTo("A report");
        assertThat(resolved.charStart()).isEqualTo(150);
        assertThat(resolved.charEnd()).isEqualTo(190);
        assertThat(resolved.score()).isEqualTo(0.9);
    }

    @Test
    void noCitationsIsNotAQuery() {
        assertThat(citationPages.resolve(List.of())).isEmpty();
    }

    @Test
    void aCitationBuiltFromARetrievedChunkStartsWithNoPage() {
        // The compact constructor the two answer paths use. If it defaulted to a number
        // rather than null, an unresolved citation would claim a page it never had.
        var chunk = new RetrievalService.RetrievedChunk(
                UUID.randomUUID(), UUID.randomUUID(), "A report", "text", 0, 10, 0.5);
        var built = new AnswerService.Citation(1, chunk.chunkId(), chunk.documentId(),
                chunk.title(), chunk.charStart(), chunk.charEnd(), chunk.score());

        assertThat(built.page()).isNull();
    }
}
