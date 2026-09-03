package dev.rovernotes.notes;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Reads and writes topics.
 *
 * <p>Hand-written SQL via {@link JdbcClient}, like the rest of the modules that own a
 * table outright: the one query that matters here counts documents per topic in the same
 * pass that lists them, which is a join rather than a mapped collection.
 *
 * <p>Every statement filters on {@code owner_id}, including the ones that already have a
 * primary key to go on. A rename that matched on id alone would let anyone holding a
 * topic's identifier rename someone else's.
 */
@Repository
class TopicRepository {

    private final JdbcClient jdbc;

    TopicRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Every topic this owner has, alphabetically, each with how many documents sit in it.
     *
     * <p>A left join so a topic with nothing in it still appears — one is created before
     * anything is filed under it, and a new topic vanishing from the list it was just
     * added to would read as the creation having failed.
     *
     * <p>Sorted by name rather than by creation date or size. The list is a set of
     * choices to find one's way around, and the position of a name that does not move is
     * what makes it findable twice.
     */
    List<Topic> listByOwner(UUID ownerId) {
        return jdbc.sql("""
                        select t.id, t.owner_id, t.name, t.created_at,
                               count(d.id) as document_count
                          from topics t
                          left join documents d on d.topic_id = t.id
                         where t.owner_id = :ownerId
                         group by t.id, t.owner_id, t.name, t.created_at
                         order by lower(t.name)
                        """)
                .param("ownerId", ownerId)
                .query(TopicRepository::map)
                .list();
    }

    Optional<Topic> findByIdAndOwner(UUID id, UUID ownerId) {
        return jdbc.sql("""
                        select t.id, t.owner_id, t.name, t.created_at,
                               count(d.id) as document_count
                          from topics t
                          left join documents d on d.topic_id = t.id
                         where t.id = :id and t.owner_id = :ownerId
                         group by t.id, t.owner_id, t.name, t.created_at
                        """)
                .param("id", id)
                .param("ownerId", ownerId)
                .query(TopicRepository::map)
                .optional();
    }

    /**
     * Creates a topic and returns it as stored.
     *
     * <p>{@code returning} rather than a second select: the row carries a generated id and
     * a database-side timestamp, and reading them back in the insert is one round trip
     * where a follow-up query would be two.
     *
     * @throws org.springframework.dao.DuplicateKeyException if this owner already has a
     *         topic of that name — the unique constraint from V8, surfaced rather than
     *         pre-checked, because a check followed by an insert is two statements that
     *         can disagree.
     */
    Topic create(UUID ownerId, String name) {
        return jdbc.sql("""
                        insert into topics (owner_id, name)
                        values (:ownerId, :name)
                        returning id, owner_id, name, created_at, 0 as document_count
                        """)
                .param("ownerId", ownerId)
                .param("name", name)
                .query(TopicRepository::map)
                .single();
    }

    /** @return false if this owner has no such topic. */
    boolean rename(UUID id, UUID ownerId, String name) {
        return jdbc.sql("""
                        update topics set name = :name
                         where id = :id and owner_id = :ownerId
                        """)
                .param("name", name)
                .param("id", id)
                .param("ownerId", ownerId)
                .update() > 0;
    }

    /**
     * Removes a topic. Documents filed under it keep their content and lose their label —
     * {@code on delete set null (topic_id)} in V8.
     *
     * @return false if this owner has no such topic.
     */
    boolean delete(UUID id, UUID ownerId) {
        return jdbc.sql("delete from topics where id = :id and owner_id = :ownerId")
                .param("id", id)
                .param("ownerId", ownerId)
                .update() > 0;
    }

    private static Topic map(java.sql.ResultSet rs, int rowNum) throws java.sql.SQLException {
        return new Topic(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("owner_id"),
                rs.getString("name"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getLong("document_count"));
    }
}
