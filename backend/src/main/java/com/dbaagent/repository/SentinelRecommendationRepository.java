package com.dbaagent.repository;

import com.dbaagent.model.SentinelRecommendation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for SentinelRecommendation
 * Manages AI-generated actionable recommendations
 */
@Repository
public interface SentinelRecommendationRepository extends JpaRepository<SentinelRecommendation, String> {

    /**
     * Find all recommendations for a connection
     */
    List<SentinelRecommendation> findByConnectionIdOrderByPriorityAscCreatedAtDesc(String connectionId);

    /**
     * Find pending recommendations
     */
    List<SentinelRecommendation> findByConnectionIdAndStatusOrderByPriorityAscCreatedAtDesc(
            String connectionId, SentinelRecommendation.Status status);

    /**
     * Find recommendations by priority
     */
    List<SentinelRecommendation> findByConnectionIdAndPriorityOrderByCreatedAtDesc(
            String connectionId, SentinelRecommendation.Priority priority);

    /**
     * Find recommendations for a specific table
     */
    List<SentinelRecommendation> findByConnectionIdAndTableNameOrderByPriorityAscCreatedAtDesc(
            String connectionId, String tableName);

    /**
     * Find immediate priority pending recommendations
     */
    @Query("SELECT r FROM SentinelRecommendation r WHERE r.connectionId = :connectionId " +
           "AND r.status = 'PENDING' AND r.priority = 'IMMEDIATE' " +
           "ORDER BY r.createdAt DESC")
    List<SentinelRecommendation> findImmediatePending(@Param("connectionId") String connectionId);

    /**
     * Find overdue recommendations
     */
    @Query("SELECT r FROM SentinelRecommendation r WHERE r.connectionId = :connectionId " +
           "AND r.status = 'PENDING' " +
           "AND ((r.priority = 'IMMEDIATE' AND r.createdAt < :oneHourAgo) " +
           "OR (r.priority = 'HOUR_24' AND r.createdAt < :oneDayAgo) " +
           "OR (r.priority = 'HOUR_48' AND r.createdAt < :twoDaysAgo)) " +
           "ORDER BY r.priority ASC, r.createdAt ASC")
    List<SentinelRecommendation> findOverdue(
            @Param("connectionId") String connectionId,
            @Param("oneHourAgo") LocalDateTime oneHourAgo,
            @Param("oneDayAgo") LocalDateTime oneDayAgo,
            @Param("twoDaysAgo") LocalDateTime twoDaysAgo);

    /**
     * Find recommendations by forecast
     */
    List<SentinelRecommendation> findByForecastIdOrderByPriorityAsc(String forecastId);

    /**
     * Find recommendations by anomaly
     */
    List<SentinelRecommendation> findByAnomalyIdOrderByPriorityAsc(String anomalyId);

    /**
     * Count pending recommendations by connection
     */
    long countByConnectionIdAndStatus(String connectionId, SentinelRecommendation.Status status);

    /**
     * Delete old completed/dismissed recommendations
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM SentinelRecommendation r WHERE r.status IN ('COMPLETED', 'DISMISSED') " +
           "AND r.updatedAt < :cutoffDate")
    void deleteOldCompletedRecommendations(@Param("cutoffDate") LocalDateTime cutoffDate);
}
