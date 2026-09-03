package dev.rovernotes.notes;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.UUID;

import dev.rovernotes.TestAccounts;
import dev.rovernotes.TestDatabase;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Turning a character offset back into a printed page number.
 *
 * <p>This is what makes a citation into a PDF checkable. Retrieval knows a chunk spans
 * characters 51,200 to 51,900; a reader has a document with numbered pages and no way to
 * act on that. The lookup is a range containment, and the cases that matter are the
 * boundaries — an offset on the seam between two pages belongs to exactly one of them.
 */
@SpringBootTest
@ActiveProfiles("local")
class DocumentPagesTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    DocumentPages pages;

    @Autowired
    NoteService notes;

    @Autowired
    JdbcClient jdbc;

    private UUID owner;

    @BeforeEach
    void account() {
        owner = TestAccounts.create(jdbc);
    }

    private UUID document(String content) {
        return notes.create(owner, "A paginated document", content).id();
    }

    /** Three pages of a hundred characters each. */
    private static List<DocumentPages.Page> threePages() {
        return List.of(
                new DocumentPages.Page(1, 0, 100, 0),
                new DocumentPages.Page(2, 100, 200, 1),
                new DocumentPages.Page(3, 200, 300, 0));
    }

    @Test
    void anOffsetResolvesToThePageItFallsOn() {
        UUID id = document("x".repeat(300));
        pages.replace(id, threePages());

        UUID key = UUID.randomUUID();
        assertThat(pages.pagesFor(List.of(new DocumentPages.Span(key, id, 150))))
                .containsEntry(key, 2);
    }

    @Test
    void aPageBoundaryBelongsToTheLaterPage() {
        // Half-open spans, matching the spans on chunks. An offset counted on both sides
        // would give a citation two page numbers and the caller no way to choose.
        UUID id = document("x".repeat(300));
        pages.replace(id, threePages());

        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        var found = pages.pagesFor(List.of(
                new DocumentPages.Span(first, id, 99),
                new DocumentPages.Span(second, id, 100)));

        assertThat(found).containsEntry(first, 1).containsEntry(second, 2);
    }

    @Test
    void spansFromSeveralDocumentsAreResolvedInOneQuery() {
        // An answer's citations usually span more than one document, and the point of
        // taking a list is that they cost one round trip rather than one each.
        UUID first = document("x".repeat(300));
        UUID second = document("y".repeat(300));
        pages.replace(first, threePages());
        pages.replace(second, threePages());

        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        var found = pages.pagesFor(List.of(
                new DocumentPages.Span(a, first, 250),
                new DocumentPages.Span(b, second, 50)));

        assertThat(found).containsEntry(a, 3).containsEntry(b, 1);
    }

    @Test
    void everySpanKeepsItsOwnAnswer() {
        // The zip in SQL is the part that could silently pair the wrong offset with the
        // wrong document. Two documents with deliberately different answers at the same
        // offset would come back identical if the arrays were mismatched.
        UUID first = document("x".repeat(300));
        UUID second = document("y".repeat(300));
        pages.replace(first, threePages());
        pages.replace(second, List.of(new DocumentPages.Page(1, 0, 300, 0)));

        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        var found = pages.pagesFor(List.of(
                new DocumentPages.Span(a, first, 250),
                new DocumentPages.Span(b, second, 250)));

        assertThat(found).containsEntry(a, 3).containsEntry(b, 1);
    }

    @Test
    void aDocumentWithNoPagesAnswersNothingRatherThanFailing() {
        // The ordinary case: a note typed into the interface is not paginated, and a
        // citation into one has no page to name.
        UUID id = document("a typed note");

        UUID key = UUID.randomUUID();
        assertThat(pages.pagesFor(List.of(new DocumentPages.Span(key, id, 5)))).isEmpty();
    }

    @Test
    void anOffsetPastTheLastPageAnswersNothing() {
        UUID id = document("x".repeat(300));
        pages.replace(id, threePages());

        UUID key = UUID.randomUUID();
        assertThat(pages.pagesFor(List.of(new DocumentPages.Span(key, id, 5000)))).isEmpty();
    }

    @Test
    void noSpansCostsNoQuery() {
        assertThat(pages.pagesFor(List.of())).isEmpty();
    }

    @Test
    void replacingPagesRemovesTheOnesTheDocumentNoLongerHas() {
        // A re-upload of a shorter file. Left behind, page 3 would resolve a citation to
        // a page the reader's copy does not have.
        UUID id = document("x".repeat(300));
        pages.replace(id, threePages());
        pages.replace(id, List.of(new DocumentPages.Page(1, 0, 100, 0)));

        assertThat(pageNumbers(id)).containsExactly(1);
    }

    @Test
    void tablesAreRecordedAgainstThePageTheyWereFoundOn() {
        UUID id = document("x".repeat(300));
        pages.replace(id, threePages());

        assertThat(jdbc.sql("""
                        select number from document_pages
                         where document_id = :id and tables > 0
                        """)
                .param("id", id)
                .query(Integer.class)
                .list()).containsExactly(2);
    }

    @Test
    void deletingADocumentTakesItsPagesWithIt() {
        UUID id = document("x".repeat(300));
        pages.replace(id, threePages());

        notes.delete(owner, id);

        assertThat(pageNumbers(id)).isEmpty();
    }

    private List<Integer> pageNumbers(UUID documentId) {
        return jdbc.sql("select number from document_pages where document_id = :id order by number")
                .param("id", documentId)
                .query(Integer.class)
                .list();
    }
}
