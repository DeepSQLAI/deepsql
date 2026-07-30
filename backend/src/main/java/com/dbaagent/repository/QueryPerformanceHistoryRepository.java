package com.dbaagent.repository;

import com.dbaagent.model.QueryPerformanceHistory;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface QueryPerformanceHistoryRepository extends JpaRepository<QueryPerformanceHistory, Long> {

    List<QueryPerformanceHistory> findByConnectionIdOrderByExecutionTimestampDesc(String connectionId);

    List<QueryPerformanceHistory> findByConnectionIdAndQueryHashOrderByExecutionTimestampDesc(
            String connectionId,
            String queryHash
    );

    @Query("SELECT h FROM QueryPerformanceHistory h WHERE h.connectionId = :connectionId " +
           "AND h.queryHash = :queryHash " +
           "AND h.executionTimestamp >= :startTime " +
           "AND h.executionTimestamp <= :endTime " +
           "ORDER BY h.executionTimestamp ASC")
    List<QueryPerformanceHistory> findByConnectionAndQueryInTimeRange(
            @Param("connectionId") String connectionId,
            @Param("queryHash") String queryHash,
            @Param("startTime") LocalDateTime startTime,
            @Param("endTime") LocalDateTime endTime
    );

    @Query("SELECT DISTINCT h.queryHash FROM QueryPerformanceHistory h WHERE h.connectionId = :connectionId")
    List<String> findDistinctQueryHashesByConnectionId(@Param("connectionId") String connectionId);

    @Query("SELECT h FROM QueryPerformanceHistory h WHERE h.connectionId = :connectionId " +
           "AND h.executionTimestamp >= :startTime ORDER BY h.executionTimestamp DESC")
    List<QueryPerformanceHistory> findRecentHistory(
            @Param("connectionId") String connectionId,
            @Param("startTime") LocalDateTime startTime
    );

    @Query("SELECT h FROM QueryPerformanceHistory h WHERE h.connectionId = :connectionId " +
           "AND h.executionTimestamp >= :startTime ORDER BY h.executionTimestamp DESC")
    List<QueryPerformanceHistory> findRecentHistory(
            @Param("connectionId") String connectionId,
            @Param("startTime") LocalDateTime startTime,
            Pageable pageable
    );

    void deleteByExecutionTimestampBefore(LocalDateTime timestamp);

    void deleteByConnectionId(String connectionId);
}
