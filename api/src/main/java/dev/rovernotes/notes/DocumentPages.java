package dev.rovernotes.notes;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

/**
 * The page ranges of documents that have pages.
 *
 * <p>Written once when a file is ingested and read when a citation is rendered. Notes
 * typed into the interface have no rows here at all, which is the case the read path has
 * to stay cheap for: most documents in this corpus are notes, and a lookup that costs a
 * query per citation would be paid mostly by documents that can never answer it.
 */
@Repository
public class DocumentPages {

    private final JdbcClient jdbc;

    DocumentPages(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Replaces the page ranges of a document.
     *
     * <p>Replaces rather than appends, because re-uploading a file is the same operation
     * as uploading it: whatever the new text is, these are its pages. Rows are deleted
     * first rather than upserted so a document that grew shorter does not keep pages past
     * its end, which would resolve a citation to a page the reader does not have.
     */
    @Transactional
    public void replace(UUID documentId, List<Page> pages) {
        jdbc.sql("delete from document_pages where document_id = :documentId")
                .param("documentId", documentId)
                .update();

        for (Page page : pages) {
            jdbc.sql("""
                            insert into document_pages
                                (document_id, number, char_start, char_end, tables)
                            values (:documentId, :number, :charStart, :charEnd, :tables)
                            """)
                    .param("documentId", documentId)
                    .param("number", page.number())
                    .param("charStart", page.charStart())
                    .param("charEnd", page.charEnd())
                    .param("tables", page.tables())
                    .update();
        }
    }

    /**
     * The page each of these spans falls on, for the documents that have pages.
     *
     * <p>One query for a whole answer's citations rather than one per citation. An answer
     * carries up to ten sources and most of them are usually from the same document, so
     * the per-citation form would repeat the same range scan several times over.
     *
     * <p>A span missing from the result means the document is not paginated, which is the
     * ordinary case for a typed note and not an error.
     */
    public Map<UUID, Integer> pagesFor(List<Span> spans) {
        if (spans.isEmpty()) {
            return Map.of();
        }

        // Passed as parallel arrays and zipped in SQL: a variable number of (document,
        // offset) pairs has no clean form as bound parameters otherwise, and building the
        // predicate by string concatenation would put caller-supplied values in the
        // statement text.
        List<UUID> keys = spans.stream().map(Span::key).toList();
        List<UUID> documentIds = spans.stream().map(Span::documentId).toList();
        List<Integer> offsets = spans.stream().map(Span::charStart).toList();

        return jdbc.sql("""
                        select asked.key as key, p.number as number
                          from unnest(
                                   cast(:keys as uuid[]),
                                   cast(:documentIds as uuid[]),
                                   cast(:offsets as int[])
                               ) as asked(key, document_id, char_start)
                          join document_pages p
                            on p.document_id = asked.document_id
                           and asked.char_start >= p.char_start
                           and asked.char_start < p.char_end
                        """)
                .param("keys", keys.toArray(UUID[]::new))
                .param("documentIds", documentIds.toArray(UUID[]::new))
                .param("offsets", offsets.toArray(Integer[]::new))
                .query((rs, rowNum) -> Map.entry((UUID) rs.getObject("key"), rs.getInt("number")))
                .list()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (first, second) -> first));
    }

    /** One page of a document, and the span of extracted text it holds. */
    public record Page(int number, int charStart, int charEnd, int tables) {}

    /**
     * A question about one span: which page does this offset in this document fall on.
     *
     * <p>{@code key} is whatever the caller wants the answer filed under — a chunk id, in
     * practice — so the result can be matched back without the caller reconstructing
     * pairs.
     */
    public record Span(UUID key, UUID documentId, int charStart) {}
}
