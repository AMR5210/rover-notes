package dev.rovernotes.ingestion;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * How much indexing is still outstanding.
 *
 * <p>Writing a document and being able to search it are separated by the outbox: the
 * write returns once the row commits, and chunking and embedding happen after. That is
 * the right shape for the write path and the wrong thing to leave unsaid in an interface,
 * where a document that has been added but is not yet searchable looks like one that was
 * lost.
 *
 * <p>The same number is on the metrics endpoint as {@code rover.ingestion.backlog}, and
 * this exists rather than the interface reading that one. Actuator is an operator's
 * surface — reachable on a different port in most deployments and restricted in the rest
 * — so pointing a browser at it would either widen what is exposed or work only in
 * development.
 *
 * <p>It lives in this module because {@code event_publication} is this module's business.
 * Answering it from {@code notes} would have that module reach into the internals of
 * this one, which is the arrangement {@code ModularityTests} exists to refuse.
 */
@RestController
@RequestMapping("/api/ingestion")
class IngestionStatusController {

    private final JdbcClient jdbc;

    IngestionStatusController(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @GetMapping("/status")
    Status status() {
        Long pending = jdbc
                .sql("select count(*) from event_publication where completion_date is null")
                .query(Long.class)
                .single();
        return new Status(pending == null ? 0 : pending);
    }

    /**
     * @param pending documents whose indexing has not finished, zero in the steady state
     */
    record Status(long pending) {}
}
