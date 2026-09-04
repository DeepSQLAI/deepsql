package com.dbaagent.repository;

import com.dbaagent.model.LlmUsage;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;

public interface LlmUsageRepository extends JpaRepository<LlmUsage, Long> {

    /**
     * Totals for a window. Aggregated in SQL rather than by summing entities in Java —
     * this table grows by one row per model call, so loading a month of them to add up a
     * cost column would be the most expensive query in the product.
     */
    @Query("""
            SELECT new com.dbaagent.dto.LlmUsageTotals(
                COUNT(u),
                COALESCE(SUM(u.promptTokens), 0L),
                COALESCE(SUM(u.completionTokens), 0L),
                COALESCE(SUM(u.totalTokens), 0L),
                COALESCE(SUM(u.estimatedCostUsd), 0),
                SUM(CASE WHEN u.estimatedCostUsd IS NULL THEN 1L ELSE 0L END),
                SUM(CASE WHEN u.succeeded = false THEN 1L ELSE 0L END))
            FROM LlmUsage u
            WHERE u.createdAt >= :since
            """)
    com.dbaagent.dto.LlmUsageTotals totalsSince(@Param("since") LocalDateTime since);

    @Query("""
            SELECT new com.dbaagent.dto.LlmUsageGroup(
                COALESCE(u.feature, 'unknown'),
                COUNT(u),
                COALESCE(SUM(u.totalTokens), 0L),
                COALESCE(SUM(u.estimatedCostUsd), 0))
            FROM LlmUsage u
            WHERE u.createdAt >= :since
            GROUP BY u.feature
            ORDER BY COALESCE(SUM(u.estimatedCostUsd), 0) DESC, COUNT(u) DESC
            """)
    List<com.dbaagent.dto.LlmUsageGroup> byFeatureSince(@Param("since") LocalDateTime since);

    @Query("""
            SELECT new com.dbaagent.dto.LlmUsageGroup(
                COALESCE(u.username, 'background'),
                COUNT(u),
                COALESCE(SUM(u.totalTokens), 0L),
                COALESCE(SUM(u.estimatedCostUsd), 0))
            FROM LlmUsage u
            WHERE u.createdAt >= :since
            GROUP BY u.username
            ORDER BY COALESCE(SUM(u.estimatedCostUsd), 0) DESC, COUNT(u) DESC
            """)
    List<com.dbaagent.dto.LlmUsageGroup> byUserSince(@Param("since") LocalDateTime since);

    @Query("""
            SELECT new com.dbaagent.dto.LlmUsageGroup(
                COALESCE(u.model, 'unknown'),
                COUNT(u),
                COALESCE(SUM(u.totalTokens), 0L),
                COALESCE(SUM(u.estimatedCostUsd), 0))
            FROM LlmUsage u
            WHERE u.createdAt >= :since
            GROUP BY u.model
            ORDER BY COALESCE(SUM(u.estimatedCostUsd), 0) DESC, COUNT(u) DESC
            """)
    List<com.dbaagent.dto.LlmUsageGroup> byModelSince(@Param("since") LocalDateTime since);

    /** Daily buckets for the spend chart. */
    @Query("""
            SELECT new com.dbaagent.dto.LlmUsageDailyPoint(
                CAST(u.createdAt AS java.time.LocalDate),
                COUNT(u),
                COALESCE(SUM(u.totalTokens), 0L),
                COALESCE(SUM(u.estimatedCostUsd), 0))
            FROM LlmUsage u
            WHERE u.createdAt >= :since
            GROUP BY CAST(u.createdAt AS java.time.LocalDate)
            ORDER BY CAST(u.createdAt AS java.time.LocalDate) ASC
            """)
    List<com.dbaagent.dto.LlmUsageDailyPoint> dailySince(@Param("since") LocalDateTime since);

    Page<LlmUsage> findByCreatedAtGreaterThanEqualOrderByCreatedAtDesc(
            LocalDateTime since, Pageable pageable);

    /** Models seen in the window that have no configured price, for the operator nudge. */
    @Query("""
            SELECT DISTINCT u.model FROM LlmUsage u
            WHERE u.createdAt >= :since AND u.estimatedCostUsd IS NULL
            """)
    List<String> unpricedModelsSince(@Param("since") LocalDateTime since);

    /**
     * Every model the ledger has ever seen, busiest first.
     *
     * <p>Deliberately unwindowed, unlike the reporting queries: the pricing editor is
     * about configuration, not about a reporting period, and a model that went quiet last
     * month still needs its rate visible and editable.
     */
    @Query("SELECT u.model FROM LlmUsage u GROUP BY u.model ORDER BY COUNT(u) DESC")
    List<String> distinctModels();

    @Modifying
    @Query("DELETE FROM LlmUsage u WHERE u.createdAt < :before")
    int deleteByCreatedAtBefore(@Param("before") LocalDateTime before);
}
