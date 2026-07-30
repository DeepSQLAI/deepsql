package com.dbaagent.repository;

import com.dbaagent.model.LockContention;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface LockContentionRepository extends JpaRepository<LockContention, String> {

    /**
     * Find all active (unresolved) lock contentions for a connection
     */
    @Query("SELECT lc FROM LockContention lc " +
           "WHERE lc.connectionId = :connectionId " +
           "AND lc.resolved = false " +
           "ORDER BY lc.waitDurationSeconds DESC, lc.detectedAt DESC")
    List<LockContention> findActiveContentions(@Param("connectionId") String connectionId);

    /**
     * Find all lock contentions (active and resolved) for a connection
     */
    @Query("SELECT lc FROM LockContention lc " +
           "WHERE lc.connectionId = :connectionId " +
           "ORDER BY lc.detectedAt DESC")
    List<LockContention> findByConnectionId(@Param("connectionId") String connectionId);

    /**
     * Find contentions by severity
     */
    @Query("SELECT lc FROM LockContention lc " +
           "WHERE lc.connectionId = :connectionId " +
           "AND lc.severity = :severity " +
           "AND lc.resolved = false " +
           "ORDER BY lc.waitDurationSeconds DESC")
    List<LockContention> findBySeverity(
        @Param("connectionId") String connectionId,
        @Param("severity") LockContention.Severity severity
    );

    /**
     * Find contentions blocking a specific PID
     */
    @Query("SELECT lc FROM LockContention lc " +
           "WHERE lc.connectionId = :connectionId " +
           "AND lc.blockedPid = :pid " +
           "AND lc.resolved = false")
    List<LockContention> findByBlockedPid(
        @Param("connectionId") String connectionId,
        @Param("pid") String pid
    );

    /**
     * Find contentions caused by a specific blocking PID
     */
    @Query("SELECT lc FROM LockContention lc " +
           "WHERE lc.connectionId = :connectionId " +
           "AND lc.blockingPid = :pid " +
           "AND lc.resolved = false")
    List<LockContention> findByBlockingPid(
        @Param("connectionId") String connectionId,
        @Param("pid") String pid
    );

    /**
     * Mark all contentions involving a PID as resolved
     */
    @Modifying
    @Query("UPDATE LockContention lc " +
           "SET lc.resolved = true, " +
           "lc.resolvedAt = :resolvedAt, " +
           "lc.resolutionMethod = :resolutionMethod " +
           "WHERE lc.connectionId = :connectionId " +
           "AND (lc.blockingPid = :pid OR lc.blockedPid = :pid) " +
           "AND lc.resolved = false")
    int markResolvedByPid(
        @Param("connectionId") String connectionId,
        @Param("pid") String pid,
        @Param("resolvedAt") LocalDateTime resolvedAt,
        @Param("resolutionMethod") String resolutionMethod
    );

    /**
     * Auto-resolve stale contentions (PIDs that no longer exist)
     */
    @Modifying
    @Query("UPDATE LockContention lc " +
           "SET lc.resolved = true, " +
           "lc.resolvedAt = :resolvedAt, " +
           "lc.resolutionMethod = 'AUTO_RESOLVED' " +
           "WHERE lc.id IN :ids")
    int markResolvedBatch(
        @Param("ids") List<String> ids,
        @Param("resolvedAt") LocalDateTime resolvedAt
    );

    /**
     * Count active contentions by severity
     */
    @Query("SELECT lc.severity, COUNT(lc) FROM LockContention lc " +
           "WHERE lc.connectionId = :connectionId " +
           "AND lc.resolved = false " +
           "GROUP BY lc.severity")
    List<Object[]> countBySeverity(@Param("connectionId") String connectionId);

    /**
     * Find contentions for a specific table
     */
    @Query("SELECT lc FROM LockContention lc " +
           "WHERE lc.connectionId = :connectionId " +
           "AND lc.tableName = :tableName " +
           "AND lc.resolved = false " +
           "ORDER BY lc.waitDurationSeconds DESC")
    List<LockContention> findByTable(
        @Param("connectionId") String connectionId,
        @Param("tableName") String tableName
    );

    /**
     * Delete old resolved contentions
     */
    @Modifying
    @Query("DELETE FROM LockContention lc " +
           "WHERE lc.connectionId = :connectionId " +
           "AND lc.resolved = true " +
           "AND lc.resolvedAt < :before")
    int deleteOldResolved(
        @Param("connectionId") String connectionId,
        @Param("before") LocalDateTime before
    );

    /**
     * Find contentions detected within a recent window (used by daily digest).
     * Includes both active and resolved so the digest can summarise overnight events.
     */
    @Query("SELECT lc FROM LockContention lc " +
           "WHERE lc.connectionId = :connectionId " +
           "AND lc.detectedAt >= :since " +
           "ORDER BY lc.severity DESC, lc.waitDurationSeconds DESC, lc.detectedAt DESC")
    List<LockContention> findRecentForDigest(
        @Param("connectionId") String connectionId,
        @Param("since") LocalDateTime since
    );
}
