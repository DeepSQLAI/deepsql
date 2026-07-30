package com.dbaagent.repository;

import com.dbaagent.model.ActiveQuery;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface ActiveQueryRepository extends JpaRepository<ActiveQuery, String> {

    /**
     * Find the most recent snapshot of active queries for a connection
     */
    @Query("SELECT aq FROM ActiveQuery aq " +
           "WHERE aq.connectionId = :connectionId " +
           "AND aq.capturedAt = (SELECT MAX(aq2.capturedAt) FROM ActiveQuery aq2 WHERE aq2.connectionId = :connectionId) " +
           "ORDER BY aq.durationSeconds DESC NULLS LAST")
    List<ActiveQuery> findLatestSnapshot(@Param("connectionId") String connectionId);

    /**
     * Find all snapshots for a connection within a time range
     */
    @Query("SELECT aq FROM ActiveQuery aq " +
           "WHERE aq.connectionId = :connectionId " +
           "AND aq.capturedAt BETWEEN :startTime AND :endTime " +
           "ORDER BY aq.capturedAt DESC, aq.durationSeconds DESC")
    List<ActiveQuery> findByTimeRange(
        @Param("connectionId") String connectionId,
        @Param("startTime") LocalDateTime startTime,
        @Param("endTime") LocalDateTime endTime
    );

    /**
     * Find active queries by state
     */
    @Query("SELECT aq FROM ActiveQuery aq " +
           "WHERE aq.connectionId = :connectionId " +
           "AND aq.capturedAt = (SELECT MAX(aq2.capturedAt) FROM ActiveQuery aq2 WHERE aq2.connectionId = :connectionId) " +
           "AND aq.state = :state " +
           "ORDER BY aq.durationSeconds DESC")
    List<ActiveQuery> findByState(
        @Param("connectionId") String connectionId,
        @Param("state") String state
    );

    /**
     * Find active queries by priority
     */
    @Query("SELECT aq FROM ActiveQuery aq " +
           "WHERE aq.connectionId = :connectionId " +
           "AND aq.capturedAt = (SELECT MAX(aq2.capturedAt) FROM ActiveQuery aq2 WHERE aq2.connectionId = :connectionId) " +
           "AND aq.priority = :priority " +
           "ORDER BY aq.durationSeconds DESC")
    List<ActiveQuery> findByPriority(
        @Param("connectionId") String connectionId,
        @Param("priority") ActiveQuery.Priority priority
    );

    /**
     * Find active queries by query type
     */
    @Query("SELECT aq FROM ActiveQuery aq " +
           "WHERE aq.connectionId = :connectionId " +
           "AND aq.capturedAt = (SELECT MAX(aq2.capturedAt) FROM ActiveQuery aq2 WHERE aq2.connectionId = :connectionId) " +
           "AND aq.queryType = :queryType " +
           "ORDER BY aq.durationSeconds DESC")
    List<ActiveQuery> findByQueryType(
        @Param("connectionId") String connectionId,
        @Param("queryType") String queryType
    );

    /**
     * Find long-running queries (duration > threshold seconds)
     */
    @Query("SELECT aq FROM ActiveQuery aq " +
           "WHERE aq.connectionId = :connectionId " +
           "AND aq.capturedAt = (SELECT MAX(aq2.capturedAt) FROM ActiveQuery aq2 WHERE aq2.connectionId = :connectionId) " +
           "AND aq.durationSeconds >= :thresholdSeconds " +
           "ORDER BY aq.durationSeconds DESC")
    List<ActiveQuery> findLongRunning(
        @Param("connectionId") String connectionId,
        @Param("thresholdSeconds") Long thresholdSeconds
    );

    /**
     * Find blocked queries
     */
    @Query("SELECT aq FROM ActiveQuery aq " +
           "WHERE aq.connectionId = :connectionId " +
           "AND aq.capturedAt = (SELECT MAX(aq2.capturedAt) FROM ActiveQuery aq2 WHERE aq2.connectionId = :connectionId) " +
           "AND aq.isBlocked = true " +
           "ORDER BY aq.durationSeconds DESC")
    List<ActiveQuery> findBlocked(@Param("connectionId") String connectionId);

    /**
     * Find queries by user
     */
    @Query("SELECT aq FROM ActiveQuery aq " +
           "WHERE aq.connectionId = :connectionId " +
           "AND aq.capturedAt = (SELECT MAX(aq2.capturedAt) FROM ActiveQuery aq2 WHERE aq2.connectionId = :connectionId) " +
           "AND aq.user = :user " +
           "ORDER BY aq.durationSeconds DESC")
    List<ActiveQuery> findByUser(
        @Param("connectionId") String connectionId,
        @Param("user") String user
    );

    /**
     * Find queries by database
     */
    @Query("SELECT aq FROM ActiveQuery aq " +
           "WHERE aq.connectionId = :connectionId " +
           "AND aq.capturedAt = (SELECT MAX(aq2.capturedAt) FROM ActiveQuery aq2 WHERE aq2.connectionId = :connectionId) " +
           "AND aq.database = :database " +
           "ORDER BY aq.durationSeconds DESC")
    List<ActiveQuery> findByDatabase(
        @Param("connectionId") String connectionId,
        @Param("database") String database
    );

    /**
     * Count queries by state
     */
    @Query("SELECT aq.state, COUNT(aq) FROM ActiveQuery aq " +
           "WHERE aq.connectionId = :connectionId " +
           "AND aq.capturedAt = (SELECT MAX(aq2.capturedAt) FROM ActiveQuery aq2 WHERE aq2.connectionId = :connectionId) " +
           "GROUP BY aq.state")
    List<Object[]> countByState(@Param("connectionId") String connectionId);

    /**
     * Count queries by query type
     */
    @Query("SELECT aq.queryType, COUNT(aq) FROM ActiveQuery aq " +
           "WHERE aq.connectionId = :connectionId " +
           "AND aq.capturedAt = (SELECT MAX(aq2.capturedAt) FROM ActiveQuery aq2 WHERE aq2.connectionId = :connectionId) " +
           "GROUP BY aq.queryType")
    List<Object[]> countByQueryType(@Param("connectionId") String connectionId);

    /**
     * Count queries by priority
     */
    @Query("SELECT aq.priority, COUNT(aq) FROM ActiveQuery aq " +
           "WHERE aq.connectionId = :connectionId " +
           "AND aq.capturedAt = (SELECT MAX(aq2.capturedAt) FROM ActiveQuery aq2 WHERE aq2.connectionId = :connectionId) " +
           "GROUP BY aq.priority")
    List<Object[]> countByPriority(@Param("connectionId") String connectionId);

    /**
     * Delete old snapshots (keep only recent ones)
     */
    @Modifying
    @Query("DELETE FROM ActiveQuery aq " +
           "WHERE aq.connectionId = :connectionId " +
           "AND aq.capturedAt < :before")
    int deleteOldSnapshots(
        @Param("connectionId") String connectionId,
        @Param("before") LocalDateTime before
    );

    /**
     * Get distinct users
     */
    @Query("SELECT DISTINCT aq.user FROM ActiveQuery aq " +
           "WHERE aq.connectionId = :connectionId " +
           "AND aq.capturedAt = (SELECT MAX(aq2.capturedAt) FROM ActiveQuery aq2 WHERE aq2.connectionId = :connectionId) " +
           "AND aq.user IS NOT NULL " +
           "ORDER BY aq.user")
    List<String> findDistinctUsers(@Param("connectionId") String connectionId);

    /**
     * Get distinct databases
     */
    @Query("SELECT DISTINCT aq.database FROM ActiveQuery aq " +
           "WHERE aq.connectionId = :connectionId " +
           "AND aq.capturedAt = (SELECT MAX(aq2.capturedAt) FROM ActiveQuery aq2 WHERE aq2.connectionId = :connectionId) " +
           "AND aq.database IS NOT NULL " +
           "ORDER BY aq.database")
    List<String> findDistinctDatabases(@Param("connectionId") String connectionId);
}
