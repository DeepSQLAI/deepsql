package com.dbaagent.repository;

import com.dbaagent.model.WorkloadDriftMetrics;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for WorkloadDriftMetrics
 * Manages workload pattern and invisible growth tracking
 */
@Repository
public interface WorkloadDriftMetricsRepository extends JpaRepository<WorkloadDriftMetrics, String> {

    /**
     * Find latest metrics for a table
     */
    Optional<WorkloadDriftMetrics> findFirstByConnectionIdAndTableNameOrderByMeasurementTimestampDesc(
            String connectionId, String tableName);

    /**
     * Find previous metrics for comparison
     */
    Optional<WorkloadDriftMetrics> findFirstByConnectionIdAndTableNameAndMeasurementTimestampBeforeOrderByMeasurementTimestampDesc(
            String connectionId, String tableName, LocalDateTime before);

    /**
     * Find all metrics for a table
     */
    List<WorkloadDriftMetrics> findByConnectionIdAndTableNameOrderByMeasurementTimestampDesc(
            String connectionId, String tableName);

    /**
     * Find metrics within time range
     */
    List<WorkloadDriftMetrics> findByConnectionIdAndMeasurementTimestampBetweenOrderByMeasurementTimestampDesc(
            String connectionId, LocalDateTime start, LocalDateTime end);

    /**
     * Find tables with high fragmentation
     */
    @Query("SELECT m FROM WorkloadDriftMetrics m WHERE m.connectionId = :connectionId " +
           "AND m.fragmentationPercent > :threshold " +
           "ORDER BY m.fragmentationPercent DESC")
    List<WorkloadDriftMetrics> findHighFragmentation(
            @Param("connectionId") String connectionId,
            @Param("threshold") Double threshold);

    /**
     * Find tables with index sprawl
     */
    @Query("SELECT m FROM WorkloadDriftMetrics m WHERE m.connectionId = :connectionId " +
           "AND m.indexToDataRatio > :threshold " +
           "ORDER BY m.indexToDataRatio DESC")
    List<WorkloadDriftMetrics> findIndexSprawl(
            @Param("connectionId") String connectionId,
            @Param("threshold") Double threshold);

    /**
     * Find tables with poor cache performance
     */
    @Query("SELECT m FROM WorkloadDriftMetrics m WHERE m.connectionId = :connectionId " +
           "AND m.cacheHitRatio < :threshold " +
           "ORDER BY m.cacheHitRatio ASC")
    List<WorkloadDriftMetrics> findPoorCachePerformance(
            @Param("connectionId") String connectionId,
            @Param("threshold") Double threshold);

    /**
     * Delete old metrics
     */
    void deleteByMeasurementTimestampBefore(LocalDateTime cutoffDate);
}
