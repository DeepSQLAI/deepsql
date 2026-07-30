package com.dbaagent.repository;

import com.dbaagent.dto.SlowQueryHistorySummary;
import com.dbaagent.model.SlowQueryHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository interface for SlowQueryHistory entity
 * Provides database access methods for Slow Query analysis history
 */
@Repository
public interface SlowQueryHistoryRepository extends JpaRepository<SlowQueryHistory, String> {

    /**
     * Find all slow query history summaries for a specific connection (without analysisData to avoid OOM)
     */
    @Query("SELECT s.id as id, s.connectionId as connectionId, s.userId as userId, " +
           "s.timeRange as timeRange, s.slowQueryThresholdMs as slowQueryThresholdMs, " +
           "s.totalSlowQueries as totalSlowQueries, s.overallHealth as overallHealth, " +
           "s.criticalCount as criticalCount, s.highCount as highCount, " +
           "s.totalDatabaseTimeMs as totalDatabaseTimeMs, s.createdAt as createdAt, " +
           "s.updatedAt as updatedAt " +
           "FROM SlowQueryHistory s WHERE s.connectionId = :connectionId " +
           "ORDER BY s.createdAt DESC")
    List<SlowQueryHistorySummary> findSummariesByConnectionId(@Param("connectionId") String connectionId);

    /**
     * Find the most recent history entry for a connection (full entity with analysisData)
     */
    Optional<SlowQueryHistory> findFirstByConnectionIdOrderByCreatedAtDesc(String connectionId);

    /**
     * Find all slow query history for a specific connection, ordered by most recent first
     * @deprecated Use findSummariesByConnectionId to avoid OOM with large analysisData
     */
    @Deprecated
    List<SlowQueryHistory> findByConnectionIdOrderByCreatedAtDesc(String connectionId);

    /**
     * Find IDs for cleanup paging
     */
    @Query("SELECT s.id FROM SlowQueryHistory s WHERE s.connectionId = :connectionId " +
           "ORDER BY s.createdAt DESC")
    List<String> findIdsByConnectionIdOrderByCreatedAtDesc(
        @Param("connectionId") String connectionId,
        Pageable pageable
    );

    /**
     * Find slow query history for a connection with paging
     */
    @Query("SELECT s FROM SlowQueryHistory s WHERE s.connectionId = :connectionId " +
           "ORDER BY s.createdAt DESC")
    List<SlowQueryHistory> findByConnectionIdOrderByCreatedAtDesc(
        @Param("connectionId") String connectionId,
        Pageable pageable
    );

    /**
     * Find recent slow query history summaries for a specific connection with limit
     */
    @Query("SELECT s.id as id, s.connectionId as connectionId, s.userId as userId, " +
           "s.timeRange as timeRange, s.slowQueryThresholdMs as slowQueryThresholdMs, " +
           "s.totalSlowQueries as totalSlowQueries, s.overallHealth as overallHealth, " +
           "s.criticalCount as criticalCount, s.highCount as highCount, " +
           "s.totalDatabaseTimeMs as totalDatabaseTimeMs, s.createdAt as createdAt, " +
           "s.updatedAt as updatedAt " +
           "FROM SlowQueryHistory s WHERE s.connectionId = :connectionId " +
           "ORDER BY s.createdAt DESC")
    List<SlowQueryHistorySummary> findRecentSummariesByConnectionId(
        @Param("connectionId") String connectionId,
        Pageable pageable
    );

    /**
     * Find recent slow query history for a specific connection with limit
     * @deprecated Use findRecentSummariesByConnectionId to avoid OOM
     */
    @Deprecated
    @Query("SELECT s FROM SlowQueryHistory s WHERE s.connectionId = :connectionId " +
           "ORDER BY s.createdAt DESC")
    List<SlowQueryHistory> findRecentByConnectionId(
        @Param("connectionId") String connectionId,
        Pageable pageable
    );

    /**
     * Find recent slow query history across all connections.
     */
    @Query("SELECT s FROM SlowQueryHistory s ORDER BY s.createdAt DESC")
    List<SlowQueryHistory> findRecent(Pageable pageable);

    /**
     * Find all slow query history for a specific user
     */
    List<SlowQueryHistory> findByUserIdOrderByCreatedAtDesc(String userId);

    /**
     * Find slow query history summaries by time range (without analysisData)
     */
    @Query("SELECT s.id as id, s.connectionId as connectionId, s.userId as userId, " +
           "s.timeRange as timeRange, s.slowQueryThresholdMs as slowQueryThresholdMs, " +
           "s.totalSlowQueries as totalSlowQueries, s.overallHealth as overallHealth, " +
           "s.criticalCount as criticalCount, s.highCount as highCount, " +
           "s.totalDatabaseTimeMs as totalDatabaseTimeMs, s.createdAt as createdAt, " +
           "s.updatedAt as updatedAt " +
           "FROM SlowQueryHistory s WHERE s.connectionId = :connectionId " +
           "AND s.timeRange = :timeRange " +
           "ORDER BY s.createdAt DESC")
    List<SlowQueryHistorySummary> findSummariesByConnectionIdAndTimeRange(
        @Param("connectionId") String connectionId,
        @Param("timeRange") String timeRange
    );

    /**
     * Find slow query history by time range
     * @deprecated Use findSummariesByConnectionIdAndTimeRange to avoid OOM
     */
    @Deprecated
    @Query("SELECT s FROM SlowQueryHistory s WHERE s.connectionId = :connectionId " +
           "AND s.timeRange = :timeRange " +
           "ORDER BY s.createdAt DESC")
    List<SlowQueryHistory> findByConnectionIdAndTimeRange(
        @Param("connectionId") String connectionId,
        @Param("timeRange") String timeRange
    );

    /**
     * Find slow query history created after a specific date
     */
    @Query("SELECT s FROM SlowQueryHistory s WHERE s.connectionId = :connectionId " +
           "AND s.createdAt >= :since " +
           "ORDER BY s.createdAt DESC")
    List<SlowQueryHistory> findByConnectionIdSince(
        @Param("connectionId") String connectionId,
        @Param("since") LocalDateTime since
    );

    /**
     * Find slow query history created after a specific date with paging
     */
    @Query("SELECT s FROM SlowQueryHistory s WHERE s.connectionId = :connectionId " +
           "AND s.createdAt >= :since " +
           "ORDER BY s.createdAt DESC")
    List<SlowQueryHistory> findByConnectionIdSince(
        @Param("connectionId") String connectionId,
        @Param("since") LocalDateTime since,
        Pageable pageable
    );

    /**
     * Find slow query history by health status
     */
    @Query("SELECT s FROM SlowQueryHistory s WHERE s.connectionId = :connectionId " +
           "AND s.overallHealth = :health " +
           "ORDER BY s.createdAt DESC")
    List<SlowQueryHistory> findByConnectionIdAndHealth(
        @Param("connectionId") String connectionId,
        @Param("health") String health
    );

    /**
     * Count total slow query analyses for a connection
     */
    long countByConnectionId(String connectionId);

    /**
     * Find all by connection for cleanup
     */
    @Query("SELECT s FROM SlowQueryHistory s WHERE s.connectionId = :connectionId " +
           "ORDER BY s.createdAt DESC")
    List<SlowQueryHistory> findAllByConnectionIdForCleanup(
        @Param("connectionId") String connectionId
    );

    /**
     * Find top 10 recent analyses for Brain Score calculation
     * Note: Uses native query limit for efficiency
     */
    @Query("SELECT s FROM SlowQueryHistory s WHERE s.connectionId = :connectionId " +
           "ORDER BY s.createdAt DESC")
    List<SlowQueryHistory> findTop10ByConnectionIdOrderByAnalyzedAtDesc(
        @Param("connectionId") String connectionId,
        Pageable pageable
    );

    /**
     * Find top N recent analyses (convenience overload)
     */
    default List<SlowQueryHistory> findTop10ByConnectionIdOrderByAnalyzedAtDesc(String connectionId) {
        return findTop10ByConnectionIdOrderByAnalyzedAtDesc(connectionId,
            org.springframework.data.domain.PageRequest.of(0, 10));
    }

    /**
     * Delete all slow query history for a connection
     */
    void deleteByConnectionId(String connectionId);

    /**
     * Delete all slow query history
     */
    void deleteAll();

    @Query("""
        SELECT MAX(s.createdAt)
        FROM SlowQueryHistory s
        WHERE s.connectionId = :connectionId
        """)
    LocalDateTime findLatestCreatedAt(@Param("connectionId") String connectionId);
}
