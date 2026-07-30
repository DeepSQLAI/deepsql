package com.dbaagent.repository.brain;

import com.dbaagent.model.brain.WorkloadMetricsSnapshot;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for WorkloadMetricsSnapshot entities.
 */
@Repository
public interface WorkloadMetricsSnapshotRepository extends JpaRepository<WorkloadMetricsSnapshot, String> {

    /**
     * Find snapshots for a connection ordered by time.
     */
    List<WorkloadMetricsSnapshot> findByConnectionIdOrderByCollectedAtDesc(
        String connectionId, Pageable pageable);

    /**
     * Find snapshots within a time range.
     */
    List<WorkloadMetricsSnapshot> findByConnectionIdAndCollectedAtBetweenOrderByCollectedAtAsc(
        String connectionId, LocalDateTime start, LocalDateTime end);

    /**
     * Count snapshots for a connection.
     */
    long countByConnectionId(String connectionId);

    /**
     * Find the latest snapshot for a connection.
     */
    @Query("SELECT w FROM WorkloadMetricsSnapshot w WHERE w.connectionId = :connId " +
           "ORDER BY w.collectedAt DESC LIMIT 1")
    WorkloadMetricsSnapshot findLatestByConnectionId(@Param("connId") String connectionId);

    /**
     * Find snapshots with a specific fingerprint (for similarity matching).
     */
    List<WorkloadMetricsSnapshot> findByWorkloadFingerprint(String fingerprint);

    /**
     * Delete old snapshots (keep recent ones for training).
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM WorkloadMetricsSnapshot w WHERE w.connectionId = :connId " +
           "AND w.collectedAt < :cutoff")
    void deleteOldSnapshots(@Param("connId") String connectionId,
                           @Param("cutoff") LocalDateTime cutoff);

    /**
     * Get snapshots with reduced metrics (after factor analysis).
     */
    @Query("SELECT w FROM WorkloadMetricsSnapshot w WHERE w.connectionId = :connId " +
           "AND w.reducedMetrics IS NOT NULL ORDER BY w.collectedAt DESC")
    List<WorkloadMetricsSnapshot> findWithReducedMetrics(
        @Param("connId") String connectionId, Pageable pageable);
}
