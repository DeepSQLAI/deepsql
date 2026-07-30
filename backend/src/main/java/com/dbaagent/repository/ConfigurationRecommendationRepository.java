package com.dbaagent.repository;

import com.dbaagent.model.ConfigurationRecommendation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Repository for ConfigurationRecommendation entity
 * Provides data access methods for configuration tuning recommendations
 */
@Repository
public interface ConfigurationRecommendationRepository extends JpaRepository<ConfigurationRecommendation, String> {

    /**
     * Find all recommendations for a connection, ordered by priority and creation date
     */
    List<ConfigurationRecommendation> findByConnectionIdOrderByPriorityAscCreatedAtDesc(String connectionId);

    /**
     * Find recommendations by connection and status
     */
    List<ConfigurationRecommendation> findByConnectionIdAndStatusOrderByPriorityAscCreatedAtDesc(
        String connectionId,
        ConfigurationRecommendation.Status status
    );

    /**
     * Find latest recommendations (most recent analysis)
     */
    @Query("SELECT c FROM ConfigurationRecommendation c WHERE c.connectionId = :connectionId " +
           "AND c.createdAt = (SELECT MAX(c2.createdAt) FROM ConfigurationRecommendation c2 " +
           "WHERE c2.connectionId = :connectionId) " +
           "ORDER BY c.priority ASC")
    List<ConfigurationRecommendation> findLatestByConnectionId(@Param("connectionId") String connectionId);

    /**
     * Find pending recommendations by category
     */
    List<ConfigurationRecommendation> findByConnectionIdAndStatusAndCategoryOrderByPriorityAsc(
        String connectionId,
        ConfigurationRecommendation.Status status,
        String category
    );

    /**
     * Count pending recommendations
     */
    Long countByConnectionIdAndStatus(String connectionId, ConfigurationRecommendation.Status status);

    /**
     * Delete old recommendations (cleanup)
     */
    void deleteByConnectionIdAndCreatedAtBefore(String connectionId, LocalDateTime before);

    /**
     * Find recommendation by parameter name
     */
    ConfigurationRecommendation findFirstByConnectionIdAndParameterNameOrderByCreatedAtDesc(
        String connectionId,
        String parameterName
    );
}
