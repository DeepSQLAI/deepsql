package com.dbaagent.repository;

import com.dbaagent.model.QueryFingerprint;
import org.springframework.data.domain.Pageable;
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
public interface QueryFingerprintRepository extends JpaRepository<QueryFingerprint, String> {

    /**
     * Find by connection and fingerprint hash
     */
    Optional<QueryFingerprint> findByConnectionIdAndFingerprint(String connectionId, String fingerprint);

    /**
     * Find all fingerprints for a connection, ordered by last seen
     */
    List<QueryFingerprint> findByConnectionIdOrderByLastSeenAtDesc(String connectionId);

    /**
     * Find regressing queries for a connection
     */
    List<QueryFingerprint> findByConnectionIdAndIsRegressingTrueOrderByTrendPercentageDesc(String connectionId);

    /**
     * Find top slow queries by current average time
     */
    @Query("SELECT qf FROM QueryFingerprint qf WHERE qf.connectionId = :connectionId " +
           "ORDER BY qf.currentAvgTimeMs DESC")
    List<QueryFingerprint> findTopSlowQueries(@Param("connectionId") String connectionId, Pageable pageable);

    /**
     * Find queries by trend direction
     */
    List<QueryFingerprint> findByConnectionIdAndTrendDirectionOrderByTrendPercentageDesc(
        String connectionId, QueryFingerprint.TrendDirection trendDirection);

    /**
     * Find recently seen queries
     */
    @Query("SELECT qf FROM QueryFingerprint qf WHERE qf.connectionId = :connectionId " +
           "AND qf.lastSeenAt >= :since ORDER BY qf.lastSeenAt DESC")
    List<QueryFingerprint> findRecentlySeen(
        @Param("connectionId") String connectionId,
        @Param("since") LocalDateTime since);

    /**
     * Find queries with critical performance
     */
    @Query("SELECT qf FROM QueryFingerprint qf WHERE qf.connectionId = :connectionId " +
           "AND qf.currentAvgTimeMs >= :thresholdMs ORDER BY qf.currentAvgTimeMs DESC")
    List<QueryFingerprint> findCriticalQueries(
        @Param("connectionId") String connectionId,
        @Param("thresholdMs") Double thresholdMs);

    /**
     * Count fingerprints by trend direction
     */
    long countByConnectionIdAndTrendDirection(String connectionId, QueryFingerprint.TrendDirection direction);

    /**
     * Count regressing queries
     */
    long countByConnectionIdAndIsRegressingTrue(String connectionId);

    /**
     * Find queries by query type
     */
    List<QueryFingerprint> findByConnectionIdAndQueryTypeOrderByCurrentAvgTimeMsDesc(
        String connectionId, String queryType);

    /**
     * Delete old fingerprints not seen recently
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM QueryFingerprint qf WHERE qf.connectionId = :connectionId " +
           "AND qf.lastSeenAt < :before")
    void deleteOldFingerprints(
        @Param("connectionId") String connectionId,
        @Param("before") LocalDateTime before);

    /**
     * Find fingerprints affecting a specific table (using native query for JSON/array column search)
     */
    @Query(value = "SELECT * FROM query_fingerprint qf WHERE qf.connection_id = :connectionId " +
           "AND qf.affected_tables::text LIKE CONCAT('%', :tableName, '%') ORDER BY qf.current_avg_time_ms DESC",
           nativeQuery = true)
    List<QueryFingerprint> findByAffectedTable(
        @Param("connectionId") String connectionId,
        @Param("tableName") String tableName);

    /**
     * Find multiple fingerprints by their hashes
     */
    List<QueryFingerprint> findByConnectionIdAndFingerprintIn(String connectionId, List<String> fingerprints);

    /**
     * Delete all fingerprints for a connection
     */
    void deleteByConnectionId(String connectionId);
}
