package com.dbaagent.repository;

import com.dbaagent.model.SchemaChange;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface SchemaChangeRepository extends JpaRepository<SchemaChange, String> {

    /**
     * Find all changes for a connection ordered by detection time descending
     */
    List<SchemaChange> findByConnectionIdOrderByDetectedAtDesc(String connectionId);

    /**
     * Find recent changes for a connection with limit
     */
    List<SchemaChange> findTop50ByConnectionIdOrderByDetectedAtDesc(String connectionId);

    /**
     * Find unacknowledged changes
     */
    List<SchemaChange> findByConnectionIdAndIsAcknowledgedFalseOrderByDetectedAtDesc(String connectionId);

    /**
     * Find changes by severity
     */
    List<SchemaChange> findByConnectionIdAndSeverityOrderByDetectedAtDesc(
            String connectionId, SchemaChange.Severity severity);

    /**
     * Find changes by type
     */
    List<SchemaChange> findByConnectionIdAndChangeTypeOrderByDetectedAtDesc(
            String connectionId, SchemaChange.ChangeType changeType);

    /**
     * Find changes for a specific table
     */
    List<SchemaChange> findByConnectionIdAndTableNameOrderByDetectedAtDesc(
            String connectionId, String tableName);

    /**
     * Find changes within a time range
     */
    List<SchemaChange> findByConnectionIdAndDetectedAtBetweenOrderByDetectedAtDesc(
            String connectionId, LocalDateTime startTime, LocalDateTime endTime);

    /**
     * Find breaking changes
     */
    List<SchemaChange> findByConnectionIdAndIsBreakingChangeTrueOrderByDetectedAtDesc(String connectionId);

    /**
     * Find changes between two snapshots
     */
    List<SchemaChange> findByFromSnapshotIdAndToSnapshotIdOrderByDetectedAtDesc(
            String fromSnapshotId, String toSnapshotId);

    /**
     * Count unacknowledged changes
     */
    long countByConnectionIdAndIsAcknowledgedFalse(String connectionId);

    /**
     * Count changes by severity
     */
    long countByConnectionIdAndSeverity(String connectionId, SchemaChange.Severity severity);

    /**
     * Acknowledge all changes for a connection
     */
    @Modifying
    @Query("UPDATE SchemaChange c SET c.isAcknowledged = true, c.acknowledgedBy = :user, c.acknowledgedAt = :time WHERE c.connectionId = :connectionId AND c.isAcknowledged = false")
    int acknowledgeAll(@Param("connectionId") String connectionId, @Param("user") String user, @Param("time") LocalDateTime time);

    /**
     * Acknowledge specific changes
     */
    @Modifying
    @Query("UPDATE SchemaChange c SET c.isAcknowledged = true, c.acknowledgedBy = :user, c.acknowledgedAt = :time WHERE c.id IN :ids")
    int acknowledgeByIds(@Param("ids") List<String> ids, @Param("user") String user, @Param("time") LocalDateTime time);

    /**
     * Delete old changes
     */
    @Modifying
    @Query("DELETE FROM SchemaChange c WHERE c.connectionId = :connectionId AND c.detectedAt < :before")
    int deleteOldChanges(@Param("connectionId") String connectionId, @Param("before") LocalDateTime before);

    /**
     * Delete all changes for a connection
     */
    void deleteByConnectionId(String connectionId);
}
