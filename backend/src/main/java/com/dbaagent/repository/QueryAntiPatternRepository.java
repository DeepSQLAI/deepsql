package com.dbaagent.repository;

import com.dbaagent.model.QueryAntiPattern;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface QueryAntiPatternRepository extends JpaRepository<QueryAntiPattern, String> {

    /**
     * Find all query anti-patterns for a connection.
     */
    List<QueryAntiPattern> findByConnectionIdOrderByTotalExecutionTimeMsDesc(String connectionId);

    /**
     * Find anti-patterns by type.
     */
    List<QueryAntiPattern> findByConnectionIdAndPatternType(String connectionId, String patternType);

    /**
     * Find anti-patterns by severity.
     */
    List<QueryAntiPattern> findByConnectionIdAndSeverity(
        String connectionId, QueryAntiPattern.Severity severity);

    /**
     * Find existing anti-pattern by fingerprint and type.
     */
    Optional<QueryAntiPattern> findByConnectionIdAndQueryFingerprintAndPatternType(
        String connectionId, String queryFingerprint, String patternType);

    /**
     * Find top N most impactful anti-patterns.
     */
    @Query("SELECT q FROM QueryAntiPattern q WHERE q.connectionId = :connectionId " +
           "ORDER BY q.totalExecutionTimeMs DESC LIMIT :limit")
    List<QueryAntiPattern> findTopByConnectionIdOrderByTotalExecutionTimeMs(String connectionId, int limit);

    /**
     * Count anti-patterns by severity.
     */
    long countByConnectionIdAndSeverity(String connectionId, QueryAntiPattern.Severity severity);

    /**
     * Count total anti-patterns for a connection.
     */
    long countByConnectionId(String connectionId);
}
