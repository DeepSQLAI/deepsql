package com.dbaagent.repository.brain;

import com.dbaagent.model.brain.PlanExecution;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for PlanExecution entities.
 */
@Repository
public interface PlanExecutionRepository extends JpaRepository<PlanExecution, String> {

    /**
     * Find executions for a query fingerprint.
     */
    List<PlanExecution> findByConnectionIdAndQueryFingerprintOrderByExecutedAtDesc(
        String connectionId, String queryFingerprint, Pageable pageable);

    /**
     * Find executions by plan signature.
     */
    List<PlanExecution> findByPlanSignatureOrderByExecutedAtDesc(
        String planSignature, Pageable pageable);

    /**
     * Find recent executions for a connection.
     */
    List<PlanExecution> findByConnectionIdOrderByExecutedAtDesc(
        String connectionId, Pageable pageable);

    /**
     * Count executions for a connection.
     */
    long countByConnectionId(String connectionId);

    /**
     * Find executions with significant cardinality error.
     */
    @Query("SELECT p FROM PlanExecution p WHERE p.connectionId = :connId " +
           "AND (p.cardinalityErrorRatio > 10 OR p.cardinalityErrorRatio < 0.1) " +
           "ORDER BY p.executedAt DESC")
    List<PlanExecution> findWithSignificantCardinalityError(
        @Param("connId") String connectionId, Pageable pageable);

    /**
     * Find executions for a query with multiple different plans.
     */
    @Query("SELECT DISTINCT p.planSignature FROM PlanExecution p " +
           "WHERE p.connectionId = :connId AND p.queryFingerprint = :fingerprint")
    List<String> findDistinctPlanSignatures(
        @Param("connId") String connectionId,
        @Param("fingerprint") String queryFingerprint);

    /**
     * Calculate average execution time for a plan signature.
     */
    @Query("SELECT AVG(p.actualExecutionMs) FROM PlanExecution p " +
           "WHERE p.connectionId = :connId AND p.planSignature = :signature")
    Double calculateAverageExecutionTime(
        @Param("connId") String connectionId,
        @Param("signature") String planSignature);

    /**
     * Find executions within a time range.
     */
    List<PlanExecution> findByConnectionIdAndExecutedAtBetween(
        String connectionId, LocalDateTime start, LocalDateTime end);

    /**
     * Calculate average cardinality error for a connection.
     */
    @Query("SELECT AVG(p.cardinalityErrorRatio) FROM PlanExecution p " +
           "WHERE p.connectionId = :connId AND p.cardinalityErrorRatio IS NOT NULL")
    Double calculateAverageCardinalityError(@Param("connId") String connectionId);

    /**
     * Find the best performing plan for a query.
     */
    @Query("SELECT p FROM PlanExecution p WHERE p.connectionId = :connId " +
           "AND p.queryFingerprint = :fingerprint ORDER BY p.actualExecutionMs ASC LIMIT 1")
    PlanExecution findBestPlan(
        @Param("connId") String connectionId,
        @Param("fingerprint") String queryFingerprint);

    /**
     * Count executions by source.
     */
    long countByConnectionIdAndExecutionSource(String connectionId, PlanExecution.ExecutionSource source);

    /**
     * Count executions with cardinality data (both estimated and actual rows present).
     */
    @Query("SELECT COUNT(p) FROM PlanExecution p WHERE p.connectionId = :connId " +
           "AND p.estimatedRows IS NOT NULL AND p.actualRows IS NOT NULL")
    long countWithCardinalityData(@Param("connId") String connectionId);

    /**
     * Count accurate estimates (within 2x of actual).
     */
    @Query("SELECT COUNT(p) FROM PlanExecution p WHERE p.connectionId = :connId " +
           "AND p.estimatedRows IS NOT NULL AND p.actualRows IS NOT NULL " +
           "AND p.cardinalityErrorRatio >= 0.5 AND p.cardinalityErrorRatio <= 2.0")
    long countAccurateEstimates(@Param("connId") String connectionId);

    /**
     * Count overestimates (estimated > 2x actual).
     */
    @Query("SELECT COUNT(p) FROM PlanExecution p WHERE p.connectionId = :connId " +
           "AND p.estimatedRows IS NOT NULL AND p.actualRows IS NOT NULL " +
           "AND p.cardinalityErrorRatio > 2.0")
    long countOverestimates(@Param("connId") String connectionId);

    /**
     * Count underestimates (estimated < 0.5x actual).
     */
    @Query("SELECT COUNT(p) FROM PlanExecution p WHERE p.connectionId = :connId " +
           "AND p.estimatedRows IS NOT NULL AND p.actualRows IS NOT NULL " +
           "AND p.cardinalityErrorRatio < 0.5")
    long countUnderestimates(@Param("connId") String connectionId);

    /**
     * Find executions missing estimated rows (need EXPLAIN).
     * Only returns SELECT queries that can be explained.
     * Handles MySQL slow log format with SET timestamp prefix.
     */
    @Query("SELECT p FROM PlanExecution p WHERE p.connectionId = :connId " +
           "AND p.estimatedRows IS NULL " +
           "AND (UPPER(p.queryText) LIKE 'SELECT%' " +
           "OR UPPER(p.queryText) LIKE 'SET TIMESTAMP%SELECT%' " +
           "OR UPPER(p.queryText) LIKE 'USE %SELECT%') " +
           "ORDER BY p.executedAt DESC")
    List<PlanExecution> findMissingEstimatedRows(
        @Param("connId") String connectionId, Pageable pageable);

    /**
     * Count executions missing estimated rows.
     * Handles MySQL slow log format with SET timestamp prefix.
     */
    @Query("SELECT COUNT(p) FROM PlanExecution p WHERE p.connectionId = :connId " +
           "AND p.estimatedRows IS NULL " +
           "AND (UPPER(p.queryText) LIKE 'SELECT%' " +
           "OR UPPER(p.queryText) LIKE 'SET TIMESTAMP%SELECT%' " +
           "OR UPPER(p.queryText) LIKE 'USE %SELECT%')")
    long countMissingEstimatedRows(@Param("connId") String connectionId);

    /**
     * Delete all executions for a connection.
     * Used to clear data before re-backfilling with original queries.
     */
    void deleteByConnectionId(String connectionId);
}
