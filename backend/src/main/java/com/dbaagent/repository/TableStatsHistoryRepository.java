package com.dbaagent.repository;

import com.dbaagent.model.TableStatsHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface TableStatsHistoryRepository extends JpaRepository<TableStatsHistory, String> {

    /**
     * Find the most recent snapshot for a specific table
     */
    Optional<TableStatsHistory> findFirstByConnectionIdAndTableNameOrderBySnapshotTimestampDesc(
        String connectionId,
        String tableName
    );

    /**
     * Find snapshots for a table within a time range
     */
    List<TableStatsHistory> findByConnectionIdAndTableNameAndSnapshotTimestampBetweenOrderBySnapshotTimestampAsc(
        String connectionId,
        String tableName,
        LocalDateTime startTime,
        LocalDateTime endTime
    );

    /**
     * Find snapshots for all tables in a connection within a time range
     */
    List<TableStatsHistory> findByConnectionIdAndSnapshotTimestampBetweenOrderBySnapshotTimestampAsc(
        String connectionId,
        LocalDateTime startTime,
        LocalDateTime endTime
    );

    /**
     * Find historical snapshots for calculating Z-score (last N hours)
     */
    @Query("SELECT h FROM TableStatsHistory h WHERE h.connectionId = :connectionId " +
           "AND h.tableName = :tableName " +
           "AND h.snapshotTimestamp >= :since " +
           "ORDER BY h.snapshotTimestamp ASC")
    List<TableStatsHistory> findHistoricalWindow(
        @Param("connectionId") String connectionId,
        @Param("tableName") String tableName,
        @Param("since") LocalDateTime since
    );

    /**
     * Delete old snapshots before a cutoff date (for data retention)
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM TableStatsHistory h WHERE h.snapshotTimestamp < :cutoffDate")
    int deleteBySnapshotTimestampBefore(@Param("cutoffDate") LocalDateTime cutoffDate);

    /**
     * Get all unique table names for a connection that have snapshots
     */
    @Query("SELECT DISTINCT h.tableName FROM TableStatsHistory h WHERE h.connectionId = :connectionId")
    List<String> findDistinctTableNamesByConnectionId(@Param("connectionId") String connectionId);

    /**
     * Count snapshots for a specific table
     */
    long countByConnectionIdAndTableName(String connectionId, String tableName);

    /**
     * Find latest snapshot for each table in a connection
     */
    @Query("SELECT h FROM TableStatsHistory h WHERE h.connectionId = :connectionId " +
           "AND h.snapshotTimestamp = (SELECT MAX(h2.snapshotTimestamp) FROM TableStatsHistory h2 " +
           "WHERE h2.connectionId = h.connectionId AND h2.tableName = h.tableName)")
    List<TableStatsHistory> findLatestSnapshotsForConnection(@Param("connectionId") String connectionId);
}
