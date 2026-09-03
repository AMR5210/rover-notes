package dev.rovernotes.ingestion;

import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.pgvector.PGvector;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Writes chunks and their embeddings.
 *
 * <p>Hand-written SQL via {@link JdbcClient} rather than a mapped entity: the
 * {@code embedding} column is a pgvector type that binds through
 * {@link PGvector}, and the retrieval queries this table supports are ranking queries
 * that benefit from being written directly. See docs/ARCHITECTURE.md.
 */
@Repository
public class ChunkRepository {

    private final JdbcClient jdbc;

    ChunkRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * The rows of this document that already hold an embedding, grouped by the text that
     * produced it.
     *
     * <p>A queue per hash rather than a single id, because a document may legitimately
     * contain the same passage twice — a repeated heading, a boilerplate paragraph. Two
     * identical chunks need two rows, so the caller claims one row per occurrence and
     * whatever is left unclaimed is text the document no longer contains.
     *
     * <p>Ordered by ordinal so a document whose chunks all match keeps its rows in the
     * positions they already hold, and the update that follows is a no-op per row rather
     * than a reshuffle.
     */
    public Map<String, Deque<UUID>> reusableByHash(UUID documentId) {
        var rows = jdbc.sql("""
                        select id, embedding_hash
                          from chunks
                         where document_id = :documentId
                           and embedding_hash is not null
                           and embedding is not null
                         order by ordinal
                        """)
                .param("documentId", documentId)
                .query((rs, rowNum) -> Map.entry(rs.getString("embedding_hash"),
                        (UUID) rs.getObject("id")))
                .list();

        Map<String, Deque<UUID>> byHash = new HashMap<>();
        for (var row : rows) {
            byHash.computeIfAbsent(row.getKey(), key -> new ArrayDeque<>()).add(row.getValue());
        }
        return byHash;
    }

    /**
     * Moves an existing row to the position its text now occupies.
     *
     * <p>The embedding and the hash that identifies it are deliberately not written: this
     * is only called for a chunk whose embedding input was found unchanged, and rewriting
     * the vector with the value it already holds is the work this whole path exists to
     * avoid.
     *
     * <p>{@code content} can still change while the embedding input does not, when chunk
     * context is off and only whitespace outside the chunk moved, so it is written here
     * along with the offsets a citation is resolved through.
     */
    public void reposition(UUID id, StoredChunk chunk) {
        jdbc.sql("""
                        update chunks
                           set ordinal      = :ordinal,
                               content      = :content,
                               contextualized_content = :contextualized,
                               content_hash = :contentHash,
                               token_count  = :tokenCount,
                               char_start   = :charStart,
                               char_end     = :charEnd
                         where id = :id
                        """)
                .param("id", id)
                .param("ordinal", chunk.ordinal())
                .param("content", chunk.content())
                .param("contextualized", chunk.contextualizedContent())
                .param("contentHash", chunk.contentHash())
                .param("tokenCount", chunk.tokenCount())
                .param("charStart", chunk.charStart())
                .param("charEnd", chunk.charEnd())
                .update();
    }

    /** Writes a chunk whose text is new to this document, with the vector just computed. */
    public void insert(UUID documentId, UUID ownerId, StoredChunk chunk) {
        jdbc.sql("""
                        insert into chunks
                            (document_id, owner_id, ordinal, content, contextualized_content,
                             content_hash, embedding_hash, token_count, char_start, char_end,
                             embedding)
                        values
                            (:documentId, :ownerId, :ordinal, :content, :contextualized,
                             :contentHash, :embeddingHash, :tokenCount, :charStart, :charEnd,
                             :embedding)
                        """)
                .param("documentId", documentId)
                .param("ownerId", ownerId)
                .param("ordinal", chunk.ordinal())
                .param("content", chunk.content())
                .param("contextualized", chunk.contextualizedContent())
                .param("contentHash", chunk.contentHash())
                .param("embeddingHash", chunk.embeddingHash())
                .param("tokenCount", chunk.tokenCount())
                .param("charStart", chunk.charStart())
                .param("charEnd", chunk.charEnd())
                .param("embedding", new PGvector(chunk.embedding()))
                .update();
    }

    /** Removes rows holding text the document no longer contains. */
    public void deleteByIds(Collection<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }
        jdbc.sql("delete from chunks where id in (:ids)")
                .param("ids", ids)
                .update();
    }

    public void deleteByDocument(UUID documentId) {
        jdbc.sql("delete from chunks where document_id = :documentId")
                .param("documentId", documentId)
                .update();
    }

    public int countByDocument(UUID documentId) {
        return jdbc.sql("select count(*) from chunks where document_id = :documentId")
                .param("documentId", documentId)
                .query(Integer.class)
                .single();
    }

    /**
     * A chunk ready to be written.
     *
     * <p>{@code embedding} and {@code embeddingHash} are null for a chunk being reused,
     * where the row already holds both and neither is rewritten.
     */
    public record StoredChunk(
            int ordinal,
            String content,
            /**
             * What the dense channel embedded, and now what the lexical channel indexes.
             *
             * <p>Null when chunk context is off, which is what the {@code coalesce} in the
             * {@code tsv} expression falls back through. Stored separately from
             * {@code content} rather than replacing it, because {@code char_start} and
             * {@code char_end} index into {@code content} and a citation resolves through
             * them.
             */
            String contextualizedContent,
            String contentHash,
            String embeddingHash,
            Integer tokenCount,
            int charStart,
            int charEnd,
            float[] embedding
    ) {}
}
