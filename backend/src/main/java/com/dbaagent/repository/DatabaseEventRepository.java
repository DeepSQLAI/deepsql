package com.dbaagent.repository;

import com.dbaagent.model.DatabaseEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for DatabaseEvent
 * Manages deployment and schema change event tracking
 */
@Repository
public interface DatabaseEventRepository extends JpaRepository<DatabaseEvent, String> {

    /**
     * Find all events for a connection
     */
    List<DatabaseEvent> findByConnectionIdOrderByEventTimestampDesc(String connectionId);

    /**
     * Find events by type
     */
    List<DatabaseEvent> findByConnectionIdAndEventTypeOrderByEventTimestampDesc(
            String connectionId, DatabaseEvent.EventType eventType);

    /**
     * Find events within time range
     */
    List<DatabaseEvent> findByConnectionIdAndEventTimestampBetweenOrderByEventTimestampDesc(
            String connectionId, LocalDateTime start, LocalDateTime end);

    /**
     * Find events within correlation window (±24 hours of timestamp)
     */
    @Query("SELECT e FROM DatabaseEvent e WHERE e.connectionId = :connectionId " +
           "AND e.eventTimestamp >= :windowStart AND e.eventTimestamp <= :windowEnd " +
           "ORDER BY e.eventTimestamp DESC")
    List<DatabaseEvent> findEventsInCorrelationWindow(
            @Param("connectionId") String connectionId,
            @Param("windowStart") LocalDateTime windowStart,
            @Param("windowEnd") LocalDateTime windowEnd);

    /**
     * Find high-risk events (schema changes, deployments, migrations)
     */
    @Query("SELECT e FROM DatabaseEvent e WHERE e.connectionId = :connectionId " +
           "AND e.eventType IN ('SCHEMA_CHANGE', 'DEPLOYMENT', 'MIGRATION', 'BULK_LOAD') " +
           "ORDER BY e.eventTimestamp DESC")
    List<DatabaseEvent> findHighRiskEvents(@Param("connectionId") String connectionId);

    /**
     * Find events by deployment version
     */
    List<DatabaseEvent> findByConnectionIdAndDeploymentVersionOrderByEventTimestampDesc(
            String connectionId, String deploymentVersion);

    /**
     * Find recent events (last N hours)
     */
    @Query("SELECT e FROM DatabaseEvent e WHERE e.connectionId = :connectionId " +
           "AND e.eventTimestamp >= :since ORDER BY e.eventTimestamp DESC")
    List<DatabaseEvent> findRecentEvents(
            @Param("connectionId") String connectionId,
            @Param("since") LocalDateTime since);

    /**
     * Delete old events
     */
    void deleteByEventTimestampBefore(LocalDateTime cutoffDate);
}
