package com.dbaagent.repository;

import com.dbaagent.model.AnalysisHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository interface for AnalysisHistory entity
 * Provides database access methods for EXPLAIN Plan analysis history
 */
@Repository
public interface AnalysisHistoryRepository extends JpaRepository<AnalysisHistory, String> {

    /**
     * Find all analysis history for a specific connection, ordered by most recent first
     */
    List<AnalysisHistory> findByConnectionIdOrderByCreatedAtDesc(String connectionId);

    /**
     * Find recent analysis history for a specific connection with limit
     */
    @Query("SELECT a FROM AnalysisHistory a WHERE a.connectionId = :connectionId " +
           "ORDER BY a.createdAt DESC")
    List<AnalysisHistory> findRecentByConnectionId(
        @Param("connectionId") String connectionId,
        Pageable pageable
    );

    /**
     * Find all analysis history for a specific user
     */
    List<AnalysisHistory> findByUserIdOrderByCreatedAtDesc(String userId);

    /**
     * Find analysis history created after a specific date
     */
    @Query("SELECT a FROM AnalysisHistory a WHERE a.connectionId = :connectionId " +
           "AND a.createdAt >= :since " +
           "ORDER BY a.createdAt DESC")
    List<AnalysisHistory> findByConnectionIdSince(
        @Param("connectionId") String connectionId,
        @Param("since") LocalDateTime since
    );

    /**
     * Find analysis history created after a specific date with paging
     */
    @Query("SELECT a FROM AnalysisHistory a WHERE a.connectionId = :connectionId " +
           "AND a.createdAt >= :since " +
           "ORDER BY a.createdAt DESC")
    List<AnalysisHistory> findByConnectionIdSince(
        @Param("connectionId") String connectionId,
        @Param("since") LocalDateTime since,
        Pageable pageable
    );

    /**
     * Count total analyses for a connection
     */
    long countByConnectionId(String connectionId);

    /**
     * Delete old history entries beyond a certain limit
     */
    @Query("SELECT a FROM AnalysisHistory a WHERE a.connectionId = :connectionId " +
           "ORDER BY a.createdAt DESC")
    List<AnalysisHistory> findAllByConnectionIdForCleanup(
        @Param("connectionId") String connectionId
    );

    /**
     * Find IDs for cleanup paging
     */
    @Query("SELECT a.id FROM AnalysisHistory a WHERE a.connectionId = :connectionId " +
           "ORDER BY a.createdAt DESC")
    List<String> findIdsByConnectionIdOrderByCreatedAtDesc(
        @Param("connectionId") String connectionId,
        Pageable pageable
    );
}
