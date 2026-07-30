package com.dbaagent.repository;

import com.dbaagent.model.IndexRecommendationEntity;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for IndexRecommendationEntity entity
 * Provides data access methods for index recommendations
 */
@Repository
public interface IndexRecommendationRepository extends JpaRepository<IndexRecommendationEntity, String> {

    /**
     * Find all recommendations for a connection, ordered by priority and creation date
     */
    List<IndexRecommendationEntity> findByConnectionIdOrderByPriorityAscCreatedAtDesc(String connectionId);

    /**
     * Find recommendations by connection and status
     */
    List<IndexRecommendationEntity> findByConnectionIdAndStatusOrderByPriorityAscCreatedAtDesc(
        String connectionId,
        IndexRecommendationEntity.Status status
    );

    /**
     * Find recommendations by connection and priority
     */
    List<IndexRecommendationEntity> findByConnectionIdAndPriorityOrderByCreatedAtDesc(
        String connectionId,
        IndexRecommendationEntity.Priority priority
    );

    /**
     * Count pending recommendations for a connection
     */
    Long countByConnectionIdAndStatus(String connectionId, IndexRecommendationEntity.Status status);

    /**
     * Find recommendations for a specific table
     */
    List<IndexRecommendationEntity> findByConnectionIdAndTableNameOrderByPriorityAscCreatedAtDesc(
        String connectionId,
        String tableName
    );

    /**
     * Find recent recommendations with limit
     */
    @Query("SELECT i FROM IndexRecommendationEntity i WHERE i.connectionId = :connectionId " +
           "ORDER BY i.createdAt DESC")
    List<IndexRecommendationEntity> findRecentByConnectionId(
        @Param("connectionId") String connectionId,
        Pageable pageable
    );

    /**
     * Find high priority pending recommendations
     */
    @Query("SELECT i FROM IndexRecommendationEntity i WHERE i.connectionId = :connectionId " +
           "AND i.status = 'PENDING' AND i.priority = 'HIGH' " +
           "ORDER BY i.createdAt DESC")
    List<IndexRecommendationEntity> findHighPriorityPending(@Param("connectionId") String connectionId);

    /**
     * Delete old dismissed recommendations
     */
    void deleteByConnectionIdAndStatus(String connectionId, IndexRecommendationEntity.Status status);

    /**
     * Look up an existing recommendation for a (connection, table, columns, kind)
     * candidate regardless of status. Used by the recurrence-aware accumulator so
     * re-observed patterns bump occurrence_count instead of inserting a duplicate
     * row. Kind is part of the key so DROP and CREATE candidates on the same
     * table cannot collide.
     */
    List<IndexRecommendationEntity> findByConnectionIdAndTableNameAndColumnNamesAndKind(
        String connectionId,
        String tableName,
        String columnNames,
        IndexRecommendationEntity.Kind kind
    );

    /**
     * Top-N ranked pending recommendations for the CLI/MCP top tool.
     *
     * Ranking (industry standard, mirrors pganalyze / Microsoft DTA):
     *   1. priority — HIGH > MEDIUM > LOW (alphabetic ASC over the enum names)
     *   2. net benefit = workloadScoreMs - writeCostScore — workload time
     *      saved minus write-amplification cost. The DBA-grade ROI metric.
     *   3. occurrenceCount — recurrence across refresh cycles is a strong
     *      tiebreaker when net benefits are close.
     *   4. lastSeenAt — recency tiebreaker so identical scores prefer
     *      candidates seen this cycle over candidates seen a week ago.
     */
    @Query("SELECT i FROM IndexRecommendationEntity i " +
           "WHERE i.connectionId = :connectionId AND i.status = 'PENDING' " +
           "ORDER BY i.priority ASC, " +
           "(COALESCE(i.workloadScoreMs, 0) - COALESCE(i.writeCostScore, 0)) DESC, " +
           "i.occurrenceCount DESC, " +
           "i.lastSeenAt DESC")
    List<IndexRecommendationEntity> findTopPending(
        @Param("connectionId") String connectionId,
        Pageable pageable
    );

    /**
     * Bulk-delete PENDING rows that have not been re-observed in any refresh
     * cycle since `cutoff`. Keeps the accumulator from growing forever when a
     * workload changes and old candidates stop appearing.
     */
    @Modifying
    @Query("DELETE FROM IndexRecommendationEntity i " +
           "WHERE i.connectionId = :connectionId AND i.status = 'PENDING' " +
           "AND i.lastSeenAt IS NOT NULL AND i.lastSeenAt < :cutoff")
    int deleteStalePending(
        @Param("connectionId") String connectionId,
        @Param("cutoff") LocalDateTime cutoff
    );
}
