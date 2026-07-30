package com.dbaagent.repository;

import com.dbaagent.model.IngestionJobEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

@Repository
public interface IngestionJobRepository extends JpaRepository<IngestionJobEntity, String> {

    /**
     * Find active (non-terminal) job for a connection
     */
    @Query("SELECT j FROM IngestionJobEntity j WHERE j.connectionId = :connectionId " +
           "AND j.status IN ('PENDING', 'RUNNING', 'INTERRUPTED') " +
           "ORDER BY j.createdAt DESC")
    Optional<IngestionJobEntity> findActiveJobForConnection(@Param("connectionId") String connectionId);

    /**
     * Find all jobs for a connection, ordered by creation time
     */
    List<IngestionJobEntity> findByConnectionIdOrderByCreatedAtDesc(String connectionId);

    /**
     * Find recent jobs for a connection (limit to last N)
     */
    @Query("SELECT j FROM IngestionJobEntity j WHERE j.connectionId = :connectionId " +
           "ORDER BY j.createdAt DESC LIMIT :limit")
    List<IngestionJobEntity> findRecentJobsForConnection(
        @Param("connectionId") String connectionId,
        @Param("limit") int limit
    );

    /**
     * Find all RUNNING jobs (for startup recovery)
     */
    List<IngestionJobEntity> findByStatus(IngestionJobEntity.Status status);

    /**
     * Find all RUNNING or PENDING jobs (for startup recovery)
     */
    @Query("SELECT j FROM IngestionJobEntity j WHERE j.status IN ('RUNNING', 'PENDING')")
    List<IngestionJobEntity> findAllActiveJobs();

    /**
     * Find stale RUNNING jobs (no heartbeat for specified duration)
     */
    @Query("SELECT j FROM IngestionJobEntity j WHERE j.status = 'RUNNING' " +
           "AND j.lastHeartbeat < :cutoff")
    List<IngestionJobEntity> findStaleRunningJobs(@Param("cutoff") Instant cutoff);

    /**
     * Find INTERRUPTED jobs that can be resumed
     */
    @Query("SELECT j FROM IngestionJobEntity j WHERE j.status = 'INTERRUPTED' " +
           "AND j.resumeAttempts < 3 ORDER BY j.createdAt DESC")
    List<IngestionJobEntity> findResumableJobs();

    /**
     * Find INTERRUPTED job for a specific connection
     */
    @Query("SELECT j FROM IngestionJobEntity j WHERE j.connectionId = :connectionId " +
           "AND j.status = 'INTERRUPTED' AND j.resumeAttempts < 3 " +
           "ORDER BY j.createdAt DESC")
    Optional<IngestionJobEntity> findResumableJobForConnection(@Param("connectionId") String connectionId);

    /**
     * Delete old completed jobs (cleanup)
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM IngestionJobEntity j WHERE j.status IN ('COMPLETED', 'FAILED', 'CANCELLED') " +
           "AND j.completedAt < :cutoff")
    void deleteOldCompletedJobs(@Param("cutoff") Instant cutoff);

    /**
     * Count active jobs (for rate limiting)
     */
    @Query("SELECT COUNT(j) FROM IngestionJobEntity j WHERE j.status IN ('PENDING', 'RUNNING')")
    long countActiveJobs();
}
