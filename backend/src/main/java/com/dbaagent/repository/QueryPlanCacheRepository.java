package com.dbaagent.repository;

import com.dbaagent.model.QueryPlanCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface QueryPlanCacheRepository extends JpaRepository<QueryPlanCache, String> {

    /**
     * Find the latest plan for a query
     */
    Optional<QueryPlanCache> findFirstByConnectionIdAndQueryHashOrderByCapturedAtDesc(
            String connectionId, String queryHash);

    /**
     * Find baseline plan for a query
     */
    Optional<QueryPlanCache> findFirstByConnectionIdAndQueryHashAndIsBaselineTrueOrderByCapturedAtDesc(
            String connectionId, String queryHash);

    /**
     * Find all plans for a query ordered by time
     */
    List<QueryPlanCache> findByConnectionIdAndQueryHashOrderByCapturedAtDesc(
            String connectionId, String queryHash);

    /**
     * Find recent plans for a connection
     */
    List<QueryPlanCache> findTop50ByConnectionIdOrderByCapturedAtDesc(String connectionId);

    /**
     * Find plans with sequential scans
     */
    List<QueryPlanCache> findByConnectionIdAndUsesSeqScanTrueOrderByCapturedAtDesc(String connectionId);

    /**
     * Check if a plan with the same hash exists
     */
    boolean existsByConnectionIdAndQueryHashAndPlanHash(String connectionId, String queryHash, String planHash);

    /**
     * Find plans captured within a time range
     */
    List<QueryPlanCache> findByConnectionIdAndCapturedAtBetweenOrderByCapturedAtDesc(
            String connectionId, LocalDateTime startTime, LocalDateTime endTime);

    /**
     * Find plans by table (uses native query for PostgreSQL array ANY)
     */
    @Query(value = "SELECT * FROM query_plan_cache p WHERE p.connection_id = :connectionId AND :tableName = ANY(p.tables_involved) ORDER BY p.captured_at DESC", nativeQuery = true)
    List<QueryPlanCache> findByConnectionIdAndTableInvolved(@Param("connectionId") String connectionId,
                                                             @Param("tableName") String tableName);

    /**
     * Count plans for a query
     */
    long countByConnectionIdAndQueryHash(String connectionId, String queryHash);

    /**
     * Delete old plans
     */
    @Modifying
    @Query("DELETE FROM QueryPlanCache p WHERE p.connectionId = :connectionId AND p.capturedAt < :before AND p.isBaseline = false")
    int deleteOldPlans(@Param("connectionId") String connectionId, @Param("before") LocalDateTime before);

    /**
     * Delete all plans for a connection
     */
    void deleteByConnectionId(String connectionId);

    /**
     * Clear baseline status for a query
     */
    @Modifying
    @Query("UPDATE QueryPlanCache p SET p.isBaseline = false WHERE p.connectionId = :connectionId AND p.queryHash = :queryHash")
    int clearBaseline(@Param("connectionId") String connectionId, @Param("queryHash") String queryHash);
}
