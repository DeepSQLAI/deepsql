package com.dbaagent.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Turns database / schema failures from the index advisor + recommendation
 * endpoints into a structured, actionable JSON error instead of Spring's
 * opaque {@code {"error":"Internal Server Error"}} (HTTP 500 with no message).
 *
 * Scoped via {@code assignableTypes} to exactly the two index controllers, so
 * it changes nothing about how the rest of the API surfaces errors.
 *
 * The motivating failure: the {@code index_recommendations} table on a
 * deployment was missing a column the current {@code IndexRecommendationEntity}
 * expects (the project has no Flyway — schema is managed by Hibernate
 * {@code ddl-auto=update}, which doesn't reliably add columns). Every
 * {@code deepsql indexes} subcommand that touches the entity returned a bare
 * 500, giving the CLI nothing to act on. Now the caller gets the real cause
 * and a hint.
 */
@RestControllerAdvice(assignableTypes = {
        IndexAdvisorController.class,
        IndexRecommendationController.class
})
@Slf4j
public class IndexAdvisorExceptionHandler {

    /**
     * A query referencing a column/table the database doesn't have surfaces as
     * a {@link DataAccessException} (e.g. {@code BadSqlGrammarException},
     * {@code InvalidDataAccessResourceUsageException}); a swallowed error inside
     * a read-only transaction surfaces at commit as a {@link TransactionException}
     * ({@code UnexpectedRollbackException}). Both mean the same thing to the
     * caller: the persisted index data is currently unreadable.
     */
    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<Map<String, Object>> handleDataAccess(Exception ex) {
        log.error("Index endpoint data-access failure: {}", ex.getMessage(), ex);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.SERVICE_UNAVAILABLE.value());
        body.put("error", "Index data temporarily unreadable");
        body.put("message",
                "The index_recommendations store could not be read. This usually means the "
                        + "table schema is out of date with the running backend. Restart the "
                        + "backend so Hibernate (ddl-auto=update) applies pending columns; if the "
                        + "problem persists, the index_recommendations table needs the latest "
                        + "advisor columns (kind, occurrence_count, workload_score_ms, "
                        + "write_cost_score, evidence_count, hypopg_*).");
        body.put("cause", rootMessage(ex));
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(body);
    }

    /**
     * An authorization denial is a deliberate answer, not a failure of this feature.
     * {@code handleGeneric} below matches {@code Exception}, so without this more specific
     * handler a {@code ResponseStatusException} from
     * {@code assertCanReadConnectionContent} was reported as
     * {@code 500 "Index operation failed"} — the denial still held, but the caller could
     * not tell "not yours" from "the index store is broken", and the message named the
     * wrong subsystem. Verified: a non-granted user hitting
     * {@code /index-advisor/{id}/health-report} got a 500 whose body carried the 403 text.
     */
    @ExceptionHandler(org.springframework.web.server.ResponseStatusException.class)
    public ResponseEntity<Map<String, Object>> handleStatus(
            org.springframework.web.server.ResponseStatusException ex) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", ex.getStatusCode().value());
        body.put("error", ex.getStatusCode().toString());
        body.put("message", ex.getReason() != null ? ex.getReason() : ex.getMessage());
        return ResponseEntity.status(ex.getStatusCode()).body(body);
    }

    /** Any other uncaught error from these endpoints → a clean message, not an opaque 500. */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGeneric(Exception ex) {
        log.error("Index endpoint unexpected failure: {}", ex.getMessage(), ex);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", HttpStatus.INTERNAL_SERVER_ERROR.value());
        body.put("error", "Index operation failed");
        body.put("message", rootMessage(ex));
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    /** Unwrap to the most specific cause so the caller sees "column kind does not exist", not a wrapper. */
    private static String rootMessage(Throwable t) {
        Throwable cur = t;
        while (cur.getCause() != null && cur.getCause() != cur) {
            cur = cur.getCause();
        }
        String msg = cur.getMessage();
        return msg != null ? msg.lines().findFirst().orElse(msg) : cur.getClass().getSimpleName();
    }
}
