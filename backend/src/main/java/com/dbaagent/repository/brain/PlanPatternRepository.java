package com.dbaagent.repository.brain;

import com.dbaagent.model.brain.PlanPattern;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for PlanPattern entities.
 */
@Repository
public interface PlanPatternRepository extends JpaRepository<PlanPattern, String> {

    /**
     * Find pattern by plan signature.
     */
    Optional<PlanPattern> findByConnectionIdAndPlanSignature(
        String connectionId, String planSignature);

    /**
     * Find patterns for a connection.
     */
    List<PlanPattern> findByConnectionIdOrderByEffectivenessScoreDesc(
        String connectionId, Pageable pageable);

    /**
     * Find global patterns (connectionId is null).
     */
    @Query("SELECT p FROM PlanPattern p WHERE p.connectionId IS NULL " +
           "ORDER BY p.effectivenessScore DESC")
    List<PlanPattern> findGlobalPatterns(Pageable pageable);

    /**
     * Find patterns by type.
     */
    List<PlanPattern> findByConnectionIdAndPatternType(
        String connectionId, PlanPattern.PatternType patternType);

    /**
     * Find reliable patterns (high effectiveness, enough usage).
     */
    @Query("SELECT p FROM PlanPattern p WHERE p.connectionId = :connId " +
           "AND p.effectivenessScore >= :minScore AND p.usageCount >= :minUsage " +
           "ORDER BY p.effectivenessScore DESC")
    List<PlanPattern> findReliablePatterns(
        @Param("connId") String connectionId,
        @Param("minScore") Double minScore,
        @Param("minUsage") Integer minUsage);

    /**
     * Find patterns containing a specific table.
     */
    @Query(value = "SELECT * FROM plan_pattern WHERE connection_id = :connId " +
                   "AND tables_involved @> :tableJson",
           nativeQuery = true)
    List<PlanPattern> findPatternsWithTable(
        @Param("connId") String connectionId,
        @Param("tableJson") String tableJson);

    /**
     * Find patterns with similar query patterns (text search).
     */
    @Query("SELECT p FROM PlanPattern p WHERE p.connectionId = :connId " +
           "AND p.queryPattern LIKE :pattern")
    List<PlanPattern> findByQueryPatternContaining(
        @Param("connId") String connectionId,
        @Param("pattern") String pattern);

    /**
     * Count patterns for a connection.
     */
    long countByConnectionId(String connectionId);

    /**
     * Find most used patterns.
     */
    @Query("SELECT p FROM PlanPattern p WHERE p.connectionId = :connId " +
           "ORDER BY p.usageCount DESC")
    List<PlanPattern> findMostUsedPatterns(
        @Param("connId") String connectionId, Pageable pageable);

    /**
     * Find patterns with optimized query suggestions.
     */
    @Query("SELECT p FROM PlanPattern p WHERE p.connectionId = :connId " +
           "AND p.optimizedQuery IS NOT NULL AND p.effectivenessScore >= :minScore")
    List<PlanPattern> findPatternsWithOptimizations(
        @Param("connId") String connectionId,
        @Param("minScore") Double minScore);

    /**
     * Delete patterns that haven't been used recently.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM PlanPattern p WHERE p.connectionId = :connId " +
           "AND p.usageCount < :minUsage AND p.effectivenessScore < :minScore")
    void deleteIneffectivePatterns(
        @Param("connId") String connectionId,
        @Param("minUsage") Integer minUsage,
        @Param("minScore") Double minScore);
}
