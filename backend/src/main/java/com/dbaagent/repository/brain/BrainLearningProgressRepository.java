package com.dbaagent.repository.brain;

import com.dbaagent.model.brain.BrainLearningProgress;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for BrainLearningProgress entities.
 */
@Repository
public interface BrainLearningProgressRepository extends JpaRepository<BrainLearningProgress, String> {

    /**
     * Find progress by connection ID.
     */
    Optional<BrainLearningProgress> findByConnectionId(String connectionId);

    /**
     * Find connections that are Brain V2 ready.
     */
    List<BrainLearningProgress> findByBrainV2ReadyTrue();

    /**
     * Find connections above a readiness threshold.
     */
    @Query("SELECT p FROM BrainLearningProgress p WHERE p.readinessPercent >= :threshold " +
           "ORDER BY p.readinessPercent DESC")
    List<BrainLearningProgress> findAboveReadinessThreshold(@Param("threshold") Double threshold);

    /**
     * Find connections that need more data collection.
     */
    @Query("SELECT p FROM BrainLearningProgress p WHERE p.readinessPercent < 50 " +
           "ORDER BY p.updatedAt ASC")
    List<BrainLearningProgress> findNeedingMoreData();

    /**
     * Check if progress exists for a connection.
     */
    boolean existsByConnectionId(String connectionId);

    /**
     * Find average readiness across all connections.
     */
    @Query("SELECT AVG(p.readinessPercent) FROM BrainLearningProgress p")
    Double calculateAverageReadiness();

    /**
     * Count connections at each stage.
     */
    @Query("SELECT " +
           "SUM(CASE WHEN p.readinessPercent < 20 THEN 1 ELSE 0 END) AS initializing, " +
           "SUM(CASE WHEN p.readinessPercent >= 20 AND p.readinessPercent < 40 THEN 1 ELSE 0 END) AS learning, " +
           "SUM(CASE WHEN p.readinessPercent >= 40 AND p.readinessPercent < 60 THEN 1 ELSE 0 END) AS training, " +
           "SUM(CASE WHEN p.readinessPercent >= 60 AND p.readinessPercent < 80 THEN 1 ELSE 0 END) AS validating, " +
           "SUM(CASE WHEN p.readinessPercent >= 80 THEN 1 ELSE 0 END) AS ready " +
           "FROM BrainLearningProgress p")
    Object[] countByStage();
}
