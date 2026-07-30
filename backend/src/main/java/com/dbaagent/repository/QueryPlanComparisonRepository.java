package com.dbaagent.repository;

import com.dbaagent.model.QueryPlanComparison;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface QueryPlanComparisonRepository extends JpaRepository<QueryPlanComparison, String> {

    /**
     * Find comparisons for a connection
     */
    List<QueryPlanComparison> findByConnectionIdOrderByComparedAtDesc(String connectionId);

    /**
     * Find recent comparisons
     */
    List<QueryPlanComparison> findTop50ByConnectionIdOrderByComparedAtDesc(String connectionId);

    /**
     * Find regressions
     */
    List<QueryPlanComparison> findByConnectionIdAndIsRegressionTrueOrderByComparedAtDesc(String connectionId);

    /**
     * Find unacknowledged regressions
     */
    List<QueryPlanComparison> findByConnectionIdAndIsRegressionTrueAndIsAcknowledgedFalseOrderByComparedAtDesc(
            String connectionId);

    /**
     * Find comparisons by severity
     */
    List<QueryPlanComparison> findByConnectionIdAndSeverityOrderByComparedAtDesc(
            String connectionId, QueryPlanComparison.Severity severity);

    /**
     * Find comparisons for a query
     */
    List<QueryPlanComparison> findByConnectionIdAndQueryHashOrderByComparedAtDesc(
            String connectionId, String queryHash);

    /**
     * Count regressions
     */
    long countByConnectionIdAndIsRegressionTrue(String connectionId);

    /**
     * Count unacknowledged regressions
     */
    long countByConnectionIdAndIsRegressionTrueAndIsAcknowledgedFalse(String connectionId);

    /**
     * Acknowledge comparisons
     */
    @Modifying
    @Query("UPDATE QueryPlanComparison c SET c.isAcknowledged = true, c.acknowledgedBy = :user, c.acknowledgedAt = :time WHERE c.id IN :ids")
    int acknowledgeByIds(@Param("ids") List<String> ids, @Param("user") String user, @Param("time") LocalDateTime time);

    /**
     * Delete old comparisons
     */
    @Modifying
    @Query("DELETE FROM QueryPlanComparison c WHERE c.connectionId = :connectionId AND c.comparedAt < :before")
    int deleteOldComparisons(@Param("connectionId") String connectionId, @Param("before") LocalDateTime before);

    /**
     * Delete all comparisons for a connection
     */
    void deleteByConnectionId(String connectionId);
}
