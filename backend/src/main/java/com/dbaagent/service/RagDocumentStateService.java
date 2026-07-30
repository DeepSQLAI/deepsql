package com.dbaagent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

@Service
@RequiredArgsConstructor
@Slf4j
public class RagDocumentStateService {

    public record RagDocumentState(String contentHash, boolean hasEmbedding) {
    }

    private final JdbcTemplate jdbcTemplate;

    public Map<String, RagDocumentState> findDocumentStates(Collection<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return Map.of();
        }

        try {
            String placeholders = placeholders(ids.size());
            return jdbcTemplate.query(
                "SELECT id, content_hash, embedding IS NOT NULL AS has_embedding " +
                    "FROM rag_documents WHERE id IN (" + placeholders + ")",
                ids.toArray(),
                rs -> {
                    Map<String, RagDocumentState> states = new LinkedHashMap<>();
                    while (rs.next()) {
                        states.put(
                            rs.getString("id"),
                            new RagDocumentState(
                                rs.getString("content_hash"),
                                rs.getBoolean("has_embedding")
                            )
                        );
                    }
                    return states;
                }
            );
        } catch (Exception e) {
            log.debug("Could not load rag document states: {}", e.getMessage());
            return Map.of();
        }
    }

    public Map<String, Long> getTypeCounts(String connectionId) {
        return countByType(connectionId, false);
    }

    public Map<String, Long> getEmbeddedTypeCounts(String connectionId) {
        return countByType(connectionId, true);
    }

    public void deleteStaleDeterministicDocuments(
        String connectionId,
        Collection<String> deterministicTypes,
        String currentRunId
    ) {
        if (connectionId == null || connectionId.isBlank()
            || deterministicTypes == null || deterministicTypes.isEmpty()
            || currentRunId == null || currentRunId.isBlank()) {
            return;
        }

        try {
            String placeholders = placeholders(deterministicTypes.size());
            List<Object> args = new java.util.ArrayList<>();
            args.add(connectionId);
            args.addAll(deterministicTypes);
            args.add(currentRunId);

            int deleted = jdbcTemplate.update(
                "DELETE FROM rag_documents " +
                    "WHERE connection_id = ? " +
                    "AND type IN (" + placeholders + ") " +
                    "AND (last_seen_run_id IS NULL OR last_seen_run_id <> ?)",
                args.toArray()
            );

            if (deleted > 0) {
                log.info("Deleted {} stale deterministic RAG documents for connection {}", deleted, connectionId);
            }
        } catch (Exception e) {
            log.warn("Failed to delete stale deterministic RAG documents for {}: {}", connectionId, e.getMessage());
        }
    }

    private Map<String, Long> countByType(String connectionId, boolean embeddedOnly) {
        if (connectionId == null || connectionId.isBlank()) {
            return Map.of();
        }

        try {
            String sql = "SELECT type, COUNT(*) AS doc_count FROM rag_documents " +
                "WHERE connection_id = ? " +
                (embeddedOnly ? "AND embedding IS NOT NULL " : "") +
                "GROUP BY type";

            return jdbcTemplate.query(sql, ps -> ps.setString(1, connectionId), rs -> {
                Map<String, Long> stats = new LinkedHashMap<>();
                while (rs.next()) {
                    stats.put(rs.getString("type"), rs.getLong("doc_count"));
                }
                return stats;
            });
        } catch (Exception e) {
            log.debug("Could not load rag document counts for {}: {}", connectionId, e.getMessage());
            return Collections.emptyMap();
        }
    }

    private String placeholders(int count) {
        StringJoiner joiner = new StringJoiner(", ");
        for (int i = 0; i < count; i++) {
            joiner.add("?");
        }
        return joiner.toString();
    }
}
