package com.dbaagent.repository;

import com.dbaagent.model.GrowthAnomaly;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface GrowthAnomalyRepository extends JpaRepository<GrowthAnomaly, String> {

    /**
     * Find unacknowledged anomalies for a connection, ordered by most recent first
     */
    List<GrowthAnomaly> findByConnectionIdAndAcknowledgedFalseOrderByDetectionTimestampDesc(String connectionId);

    /**
     * Find unacknowledged anomalies for a specific table
     */
    List<GrowthAnomaly> findByConnectionIdAndTableNameAndAcknowledgedFalseOrderByDetectionTimestampDesc(
        String connectionId,
        String tableName
    );

    /**
     * Find recent anomalies within a time range
     */
    @Query("SELECT a FROM GrowthAnomaly a WHERE a.connectionId = :connectionId " +
           "AND a.detectionTimestamp >= :since " +
           "ORDER BY a.detectionTimestamp DESC")
    List<GrowthAnomaly> findRecentAnomalies(
        @Param("connectionId") String connectionId,
        @Param("since") LocalDateTime since
    );

    /**
     * Find recent anomalies for a specific table
     */
    @Query("SELECT a FROM GrowthAnomaly a WHERE a.connectionId = :connectionId " +
           "AND a.tableName = :tableName " +
           "AND a.detectionTimestamp >= :since " +
           "ORDER BY a.detectionTimestamp DESC")
    List<GrowthAnomaly> findRecentAnomaliesForTable(
        @Param("connectionId") String connectionId,
        @Param("tableName") String tableName,
        @Param("since") LocalDateTime since
    );

    /**
     * Count unacknowledged anomalies for a connection
     */
    long countByConnectionIdAndAcknowledgedFalse(String connectionId);

    /**
     * Count unacknowledged anomalies for a specific table
     */
    long countByConnectionIdAndTableNameAndAcknowledgedFalse(String connectionId, String tableName);

    /**
     * Find anomalies by severity
     */
    List<GrowthAnomaly> findByConnectionIdAndSeverityOrderByDetectionTimestampDesc(
        String connectionId,
        GrowthAnomaly.Severity severity
    );

    /**
     * Find anomalies by type
     */
    List<GrowthAnomaly> findByConnectionIdAndAnomalyTypeOrderByDetectionTimestampDesc(
        String connectionId,
        GrowthAnomaly.AnomalyType anomalyType
    );

    /**
     * Find all anomalies for a connection (with optional filters)
     */
    List<GrowthAnomaly> findByConnectionIdOrderByDetectionTimestampDesc(String connectionId);
}
