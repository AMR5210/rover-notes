package dev.rovernotes.notes;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jdbc.repository.query.Query;
import org.springframework.data.repository.CrudRepository;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data JDBC, deliberately not JPA — see docs/ARCHITECTURE.md. Queries are plain SQL, which
 * matters more once the retrieval module starts writing fusion queries by hand.
 *
 * <p>Every method filters on {@code owner_id}. There is no unfiltered accessor.
 */
interface DocumentRepository extends CrudRepository<Document, UUID> {

    @Query("""
            select * from documents
             where owner_id = :ownerId
             order by updated_at desc
             limit :limit offset :offset
            """)
    List<Document> findByOwner(@Param("ownerId") UUID ownerId,
                              @Param("limit") int limit,
                              @Param("offset") int offset);

    /**
     * The same page, narrowed to one topic.
     *
     * <p>A separate method rather than a nullable parameter on the one above, because
     * "every document" and "documents with no topic" are different questions and a single
     * {@code topic_id = :topicId or :topicId is null} predicate cannot ask the second one.
     * The third form below is that question.
     */
    @Query("""
            select * from documents
             where owner_id = :ownerId
               and topic_id = :topicId
             order by updated_at desc
             limit :limit offset :offset
            """)
    List<Document> findByOwnerAndTopic(@Param("ownerId") UUID ownerId,
                                       @Param("topicId") UUID topicId,
                                       @Param("limit") int limit,
                                       @Param("offset") int offset);

    /**
     * The documents this owner has not filed anywhere.
     *
     * <p>The busiest filter of the three on any library that predates topics, where every
     * document is in it.
     */
    @Query("""
            select * from documents
             where owner_id = :ownerId
               and topic_id is null
             order by updated_at desc
             limit :limit offset :offset
            """)
    List<Document> findByOwnerWithoutTopic(@Param("ownerId") UUID ownerId,
                                           @Param("limit") int limit,
                                           @Param("offset") int offset);

    @Query("select * from documents where id = :id and owner_id = :ownerId")
    Optional<Document> findByIdAndOwner(@Param("id") UUID id, @Param("ownerId") UUID ownerId);

    @Query("""
            select count(*) from documents
             where owner_id = :ownerId
            """)
    long countByOwner(@Param("ownerId") UUID ownerId);

    @Query("""
            select count(*) from documents
             where owner_id = :ownerId
               and topic_id = :topicId
            """)
    long countByOwnerAndTopic(@Param("ownerId") UUID ownerId, @Param("topicId") UUID topicId);

    @Query("""
            select count(*) from documents
             where owner_id = :ownerId
               and topic_id is null
            """)
    long countByOwnerWithoutTopic(@Param("ownerId") UUID ownerId);

    /**
     * Trigram title search — the cheap exact path that answers "find the file about X"
     * in single-digit milliseconds, before any embedding work happens. See docs/ARCHITECTURE.md.
     */
    @Query("""
            select * from documents
             where owner_id = :ownerId
               and title % :term
             order by similarity(title, :term) desc
             limit :limit
            """)
    List<Document> searchByTitle(@Param("ownerId") UUID ownerId,
                                 @Param("term") String term,
                                 @Param("limit") int limit);
}
