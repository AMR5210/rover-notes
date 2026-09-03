package dev.rovernotes;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.NoSuchElementException;
import java.util.UUID;

import dev.rovernotes.notes.Document;
import dev.rovernotes.notes.NoteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * What one account can reach of another's, with two accounts that actually exist.
 *
 * <p>Every query in every module filters on {@code owner_id}, and until V3 that was the
 * only thing separating two people's data: a query that omitted the filter would have
 * returned someone else's rows, and a write that invented an owner would have produced
 * rows nobody could reach. The foreign key added in V3 does not fix the first — a missing
 * filter is still a bug — but it makes the second impossible, and it gives deleting an
 * account a defined meaning.
 *
 * <p>Two owners rather than one is the point. Before this the system had only ever run
 * with the fixed development owner, so the isolation the schema was built for had never
 * been exercised against a second party.
 */
@SpringBootTest
@ActiveProfiles("local")
class OwnerIsolationTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    @Autowired
    NoteService notes;

    @Autowired
    JdbcClient jdbc;

    private UUID alice;
    private UUID bob;

    @BeforeEach
    void twoAccounts() {
        jdbc.sql("delete from llm_usage").update();
        jdbc.sql("delete from documents").update();
        alice = TestAccounts.create(jdbc);
        bob = TestAccounts.create(jdbc);
    }

    @Test
    void neitherAccountSeesTheOthersDocuments() {
        notes.create(alice, "alice-private", "the contents of a note Alice wrote");
        notes.create(bob, "bob-private", "the contents of a note Bob wrote");

        assertThat(notes.count(alice)).isEqualTo(1);
        assertThat(notes.list(alice, 50, 0)).singleElement()
                .extracting(Document::title).isEqualTo("alice-private");
        assertThat(notes.list(bob, 50, 0)).singleElement()
                .extracting(Document::title).isEqualTo("bob-private");
    }

    @Test
    void anIdentifierBelongingToSomeoneElseIsNotFoundRatherThanRefused() {
        Document hers = notes.create(alice, "alice-private", "something Bob should not read");

        // Not found rather than forbidden. A refusal would confirm the identifier exists,
        // which is a fact about Alice that Bob supplied only a guess for.
        assertThatThrownBy(() -> notes.get(bob, hers.id()))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void oneAccountCannotDeleteAnothersDocument() {
        Document hers = notes.create(alice, "alice-private", "something Bob should not remove");

        assertThatThrownBy(() -> notes.delete(bob, hers.id()))
                .isInstanceOf(NoSuchElementException.class);
        assertThat(notes.count(alice)).isEqualTo(1);
    }

    @Test
    void aDocumentCannotBeWrittenForAnAccountThatDoesNotExist() {
        // The constraint V3 exists for. Before it, this produced a row that every query
        // filtered out and nothing ever reported.
        UUID nobody = UUID.randomUUID();

        assertThatThrownBy(() -> notes.create(nobody, "orphan", "written for nobody"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void deletingAnAccountRemovesWhatItWroteAndLeavesTheOtherAccountAlone() {
        notes.create(alice, "alice-private", "a note that should go with the account");
        notes.create(bob, "bob-private", "a note that should survive");

        jdbc.sql("delete from users where id = :id").param("id", alice).update();

        assertThat(notes.count(alice)).isZero();
        assertThat(notes.count(bob)).isEqualTo(1);
    }

    @Test
    void deletingAnAccountKeepsTheCostItIncurredAndDropsTheAttribution() {
        // Spend is a fact about money that was spent, so the row outlives the account. The
        // column is nullable for exactly this case, which is why the constraint sets null
        // here and cascades everywhere else.
        spend(alice, "0.25");
        spend(bob, "0.10");

        jdbc.sql("delete from users where id = :id").param("id", alice).update();

        // numeric(12, 6), so the comparison is on the value rather than its rendering.
        assertThat(totalCost()).isEqualByComparingTo("0.35");
        assertThat(unattributedRows()).isEqualTo(1);
        assertThat(rowsFor(bob)).isEqualTo(1);
    }

    private void spend(UUID owner, String usd) {
        jdbc.sql("""
                        insert into llm_usage (owner_id, model_id, task, cost_usd)
                        values (:owner, 'anthropic/claude-sonnet-5', 'synthesis', cast(:cost as numeric))
                        """)
                .param("owner", owner)
                .param("cost", usd)
                .update();
    }

    private java.math.BigDecimal totalCost() {
        return jdbc.sql("select sum(cost_usd) from llm_usage")
                .query(java.math.BigDecimal.class).single();
    }

    private int unattributedRows() {
        return jdbc.sql("select count(*) from llm_usage where owner_id is null")
                .query(Integer.class).single();
    }

    private int rowsFor(UUID owner) {
        return jdbc.sql("select count(*) from llm_usage where owner_id = :owner")
                .param("owner", owner).query(Integer.class).single();
    }
}
