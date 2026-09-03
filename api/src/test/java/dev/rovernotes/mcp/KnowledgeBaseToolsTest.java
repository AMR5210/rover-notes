package dev.rovernotes.mcp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.util.List;
import java.util.UUID;

import dev.rovernotes.EmbeddingClient;
import dev.rovernotes.TestDatabase;
import dev.rovernotes.notes.Document;
import dev.rovernotes.notes.NoteService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * The tool surface an agent sees.
 *
 * <p>What is asserted here is mostly about restraint: that {@code search} returns
 * snippets rather than documents, that {@code list_documents} returns no document text at
 * all, and that limits are capped. An agent pays for every returned token out of the
 * budget it needs to reason with, so a tool that returns too much is a defect even when
 * every value in it is correct.
 */
@SpringBootTest
@ActiveProfiles("local")
class KnowledgeBaseToolsTest {

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        TestDatabase.register(registry);
    }

    /** The owner the local profile attributes every request to. */
    private static final UUID DEV_OWNER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Autowired
    KnowledgeBaseTools tools;

    @Autowired
    NoteService notes;

    @Autowired
    org.springframework.jdbc.core.simple.JdbcClient jdbc;

    @MockitoBean
    EmbeddingClient embeddings;

    private UUID longDocument;

    @BeforeEach
    void seed() {
        notes.list(DEV_OWNER, 100, 0).forEach(d -> notes.delete(DEV_OWNER, d.id()));

        float[] vector = new float[384];
        java.util.Arrays.fill(vector, 0.01f);
        Mockito.when(embeddings.embedOne(Mockito.anyString())).thenReturn(vector);
        Mockito.when(embeddings.embed(Mockito.anyList())).thenAnswer(call -> {
            List<String> texts = call.getArgument(0);
            return texts.stream().map(t -> vector).toList();
        });

        longDocument = notes.create(DEV_OWNER, "retrieval-notes",
                "RRF fuses ranked lists rather than scores. " + "padding ".repeat(200)).id();
        notes.create(DEV_OWNER, "deployment-notes", "Terraform provisions the cluster.");

        // A write returns before its text is searchable. Asserting on search before
        // indexing finishes measures the race, not the tools.
        await().atMost(java.time.Duration.ofSeconds(20))
                .pollInterval(java.time.Duration.ofMillis(100))
                .until(() -> jdbc.sql("select count(*) from chunks where owner_id = :owner")
                        .param("owner", DEV_OWNER).query(Long.class).single() >= 2);
    }

    @Test
    void searchReturnsSnippetsShortEnoughToReasonOver() {
        var result = tools.search("ranked lists", 5, null);

        assertThat(result.passages()).isNotEmpty();
        assertThat(result.passages()).allSatisfy(passage -> {
            // The full document is over 1,600 characters; a tool that returned it whole
            // would spend an agent's context on padding.
            assertThat(passage.snippet().length()).isLessThanOrEqualTo(401);
            assertThat(passage.documentId()).isNotBlank();
        });
    }

    @Test
    void searchReportsTheChannelThatActuallyAnswered() {
        // Not always the one asked for: the router picks a channel for identifier
        // lookups, and search degrades to lexical when embedding is unavailable.
        var result = tools.search("ranked lists", 5, null);

        assertThat(result.mode()).isIn("hybrid", "dense", "lexical");
    }

    @Test
    void searchCapsTheResultCount() {
        // An agent asking for a thousand passages is asking for its own context to be
        // filled with them.
        assertThat(tools.search("ranked lists", 1000, null).passages()).hasSizeLessThanOrEqualTo(20);
    }

    @Test
    void anUnknownChannelFallsBackRatherThanFailing() {
        // Rejecting the argument costs the agent a turn to discover what it should have
        // said, and it can still be given a useful answer.
        assertThat(tools.search("ranked lists", 5, "nonsense").passages()).isNotEmpty();
    }

    @Test
    void getDocumentReturnsTheWholeTextAndItsLength() {
        var document = tools.getDocument(longDocument.toString(), null, null);

        assertThat(document.title()).isEqualTo("retrieval-notes");
        assertThat(document.content()).startsWith("RRF fuses ranked lists");
        assertThat(document.totalChars()).isEqualTo(document.content().length());
    }

    @Test
    void getDocumentReturnsJustTheRequestedSpan() {
        // The span offsets search returns address the document's text directly, which is
        // what lets an agent read exactly the passage it was shown.
        var document = tools.getDocument(longDocument.toString(), 0, 22);

        assertThat(document.content()).isEqualTo("RRF fuses ranked lists");
        assertThat(document.totalChars()).isGreaterThan(22);
    }

    @Test
    void anOutOfRangeSpanIsClampedRatherThanThrowing() {
        var document = tools.getDocument(longDocument.toString(), -50, 10_000_000);

        assertThat(document.content()).isNotEmpty();
    }

    @Test
    void listingReturnsMetadataAndNeverDocumentText() {
        // Browsing must not become a way to read the whole corpus without searching it.
        var listing = tools.listDocuments(null, null);

        assertThat(listing.total()).isEqualTo(2);
        assertThat(listing.documents()).isNotEmpty();
        assertThat(listing.documents()).allSatisfy(summary -> {
            assertThat(summary.title()).isNotBlank();
            assertThat(summary.documentId()).isNotBlank();
        });
        // The record has no content component at all, which is the strongest form of this
        // guarantee: there is no field for a document body to leak through.
        assertThat(KnowledgeBaseTools.Summary.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly("documentId", "title", "updatedAt");
    }

    @Test
    void listingCapsItsPageSize() {
        assertThat(tools.listDocuments(1000, null).documents()).hasSizeLessThanOrEqualTo(50);
    }

    @Test
    void everyToolDeclaresItselfReadOnly() throws Exception {
        // The defaults are the opposite: Spring AI marks a tool destructive and
        // open-world until it says otherwise, and the specification asks clients to
        // prompt for confirmation on tool calls. Left unset, reading a note looks to a
        // client exactly like deleting one.
        for (String method : List.of("search", "getDocument", "listDocuments")) {
            var annotation = java.util.Arrays.stream(KnowledgeBaseTools.class.getMethods())
                    .filter(m -> m.getName().equals(method))
                    .findFirst().orElseThrow()
                    .getAnnotation(org.springframework.ai.mcp.annotation.McpTool.class);

            assertThat(annotation).as("%s is not exposed as a tool", method).isNotNull();
            assertThat(annotation.annotations().readOnlyHint()).as("%s readOnly", method).isTrue();
            assertThat(annotation.annotations().destructiveHint())
                    .as("%s destructive", method).isFalse();
            assertThat(annotation.annotations().idempotentHint())
                    .as("%s idempotent", method).isTrue();
            // These tools reach one database and nothing beyond it.
            assertThat(annotation.annotations().openWorldHint())
                    .as("%s openWorld", method).isFalse();
        }
    }

    @Test
    void everyToolIsScopedToTheCallersOwner() {
        // A tool surface is not a trust boundary of its own. A document belonging to
        // somebody else must be invisible to all three tools.
        Document stranger = notes.create(dev.rovernotes.TestAccounts.create(jdbc), "someone-elses-notes",
                "RRF fuses ranked lists for another owner entirely.");

        assertThat(tools.listDocuments(50, 0).documents())
                .extracting(KnowledgeBaseTools.Summary::title)
                .doesNotContain("someone-elses-notes");
        assertThat(tools.search("ranked lists", 20, null).passages())
                .extracting(KnowledgeBaseTools.Passage::title)
                .doesNotContain("someone-elses-notes");
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> tools.getDocument(stranger.id().toString(), null, null))).isNotNull();
    }
}
