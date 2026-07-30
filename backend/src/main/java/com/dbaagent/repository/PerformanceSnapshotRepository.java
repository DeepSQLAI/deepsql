package com.dbaagent.repository;

import com.dbaagent.model.PerformanceSnapshot;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for Performance Snapshots
 * Supports AWS RDS Performance Insights-style queries
 */
@Repository
public interface PerformanceSnapshotRepository extends JpaRepository<PerformanceSnapshot, String> {

    /**
     * Get snapshots for a connection within a time range
     */
    List<PerformanceSnapshot> findByConnectionIdAndSnapshotTimeBetweenOrderBySnapshotTimeAsc(
        String connectionId,
        LocalDateTime startTime,
        LocalDateTime endTime
    );

    /**
     * Get recent snapshots for a connection
     */
    @Query("SELECT p FROM PerformanceSnapshot p WHERE p.connectionId = :connectionId " +
           "ORDER BY p.snapshotTime DESC LIMIT :limit")
    List<PerformanceSnapshot> findRecentByConnectionId(
        @Param("connectionId") String connectionId,
        @Param("limit") int limit
    );

    /**
     * Get latest snapshot for a connection
     */
    PerformanceSnapshot findFirstByConnectionIdOrderBySnapshotTimeDesc(String connectionId);

    /**
     * Delete snapshots older than a certain date
     */
    void deleteBySnapshotTimeBefore(LocalDateTime cutoffTime);

    /**
     * Find snapshots for a connection older than a certain date
     */
    List<PerformanceSnapshot> findByConnectionIdAndSnapshotTimeBefore(
        String connectionId,
        LocalDateTime cutoffTime
    );

    /**
     * Count snapshots for a connection
     */
    long countByConnectionId(String connectionId);

    /**
     * Get average CPU for time range
     */
    @Query("SELECT AVG(p.cpuPercent) FROM PerformanceSnapshot p " +
           "WHERE p.connectionId = :connectionId " +
           "AND p.snapshotTime BETWEEN :startTime AND :endTime")
    Double getAverageCpu(
        @Param("connectionId") String connectionId,
        @Param("startTime") LocalDateTime startTime,
        @Param("endTime") LocalDateTime endTime
    );

    /**
     * Get average connections for time range
     */
    @Query("SELECT AVG(p.activeConnections) FROM PerformanceSnapshot p " +
           "WHERE p.connectionId = :connectionId " +
           "AND p.snapshotTime BETWEEN :startTime AND :endTime")
    Double getAverageConnections(
        @Param("connectionId") String connectionId,
        @Param("startTime") LocalDateTime startTime,
        @Param("endTime") LocalDateTime endTime
    );
}
