package com.dbaagent.repository;

import com.dbaagent.model.PerformanceAction;
import com.dbaagent.model.PerformanceAction.ActionCategory;
import com.dbaagent.model.PerformanceAction.ActionSource;
import com.dbaagent.model.PerformanceAction.ActionStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PerformanceActionRepository extends JpaRepository<PerformanceAction, String> {

    /**
     * Find all actions for a connection ordered by ROI descending.
     */
    List<PerformanceAction> findByConnectionIdOrderByRoiDesc(String connectionId);

    /**
     * Find actions for a connection with pagination.
     */
    Page<PerformanceAction> findByConnectionId(String connectionId, Pageable pageable);

    /**
     * Find actions by connection and status.
     */
    List<PerformanceAction> findByConnectionIdAndStatusOrderByRoiDesc(
            String connectionId, ActionStatus status);

    /**
     * Find actions by connection and category.
     */
    List<PerformanceAction> findByConnectionIdAndCategoryOrderByRoiDesc(
            String connectionId, ActionCategory category);

    /**
     * Find actions by connection, category, and status.
     */
    List<PerformanceAction> findByConnectionIdAndCategoryAndStatusOrderByRoiDesc(
            String connectionId, ActionCategory category, ActionStatus status);

    /**
     * Find actions by source.
     */
    List<PerformanceAction> findByConnectionIdAndSourceOrderByRoiDesc(
            String connectionId, ActionSource source);

    /**
     * Find top N actions by ROI for a connection.
     */
    @Query("SELECT pa FROM PerformanceAction pa WHERE pa.connectionId = :connectionId " +
           "AND pa.status = :status ORDER BY pa.roi DESC")
    List<PerformanceAction> findTopByConnectionIdAndStatus(
            @Param("connectionId") String connectionId,
            @Param("status") ActionStatus status,
            Pageable pageable);

    /**
     * Find pending actions for a connection (default for UI).
     */
    default List<PerformanceAction> findPendingActions(String connectionId) {
        return findByConnectionIdAndStatusOrderByRoiDesc(connectionId, ActionStatus.PENDING);
    }

    /**
     * Find pending actions with limit.
     */
    default List<PerformanceAction> findTopPendingActions(String connectionId, int limit) {
        return findTopByConnectionIdAndStatus(
                connectionId, ActionStatus.PENDING, Pageable.ofSize(limit));
    }

    /**
     * Check if an action already exists by source reference.
     */
    Optional<PerformanceAction> findByConnectionIdAndSourceReferenceId(
            String connectionId, String sourceReferenceId);

    /**
     * Check if an action exists by target (to avoid duplicates).
     */
    Optional<PerformanceAction> findByConnectionIdAndCategoryAndTargetObjectAndTargetSecondary(
            String connectionId, ActionCategory category, String targetObject, String targetSecondary);

    /**
     * Count actions by status for a connection.
     */
    long countByConnectionIdAndStatus(String connectionId, ActionStatus status);

    /**
     * Count actions by category for a connection.
     */
    long countByConnectionIdAndCategory(String connectionId, ActionCategory category);

    /**
     * Count pending actions by category.
     */
    @Query("SELECT pa.category, COUNT(pa) FROM PerformanceAction pa " +
           "WHERE pa.connectionId = :connectionId AND pa.status = 'PENDING' " +
           "GROUP BY pa.category")
    List<Object[]> countPendingByCategory(@Param("connectionId") String connectionId);

    /**
     * Get average ROI for pending actions.
     */
    @Query("SELECT AVG(pa.roi) FROM PerformanceAction pa " +
           "WHERE pa.connectionId = :connectionId AND pa.status = 'PENDING'")
    Double getAverageRoiForPending(@Param("connectionId") String connectionId);

    /**
     * Get total potential impact (sum of impact scores for pending actions).
     */
    @Query("SELECT SUM(pa.impactScore) FROM PerformanceAction pa " +
           "WHERE pa.connectionId = :connectionId AND pa.status = 'PENDING'")
    Long getTotalPotentialImpact(@Param("connectionId") String connectionId);

    /**
     * Delete old completed/dismissed actions.
     */
    @Modifying
    @Query("DELETE FROM PerformanceAction pa WHERE pa.connectionId = :connectionId " +
           "AND pa.status IN ('COMPLETED', 'DISMISSED') AND pa.resolvedAt < :before")
    int deleteOldResolvedActions(
            @Param("connectionId") String connectionId,
            @Param("before") LocalDateTime before);

    /**
     * Delete all actions for a connection (for refresh).
     */
    @Modifying
    void deleteByConnectionId(String connectionId);

    /**
     * Delete pending actions by source (for refresh from specific source).
     */
    @Modifying
    void deleteByConnectionIdAndSourceAndStatus(
            String connectionId, ActionSource source, ActionStatus status);

    /**
     * Find actions with cached analysis.
     */
    List<PerformanceAction> findByConnectionIdAndHasCachedAnalysisTrueOrderByRoiDesc(
            String connectionId);

    /**
     * Summary statistics query.
     */
    @Query("SELECT " +
           "COUNT(CASE WHEN pa.status = 'PENDING' THEN 1 END) as pending, " +
           "COUNT(CASE WHEN pa.status = 'COMPLETED' THEN 1 END) as completed, " +
           "COUNT(CASE WHEN pa.status = 'DISMISSED' THEN 1 END) as dismissed, " +
           "MAX(pa.roi) as topRoi, " +
           "AVG(pa.impactScore) as avgImpact " +
           "FROM PerformanceAction pa WHERE pa.connectionId = :connectionId")
    Object[] getSummaryStats(@Param("connectionId") String connectionId);
}
