package com.dbaagent.repository.brain;

import com.dbaagent.model.brain.TuningExperiment;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for TuningExperiment entities.
 */
@Repository
public interface TuningExperimentRepository extends JpaRepository<TuningExperiment, String> {

    /**
     * Find experiments for a connection by status.
     */
    List<TuningExperiment> findByConnectionIdAndStatusOrderByCreatedAtDesc(
        String connectionId, TuningExperiment.ExperimentStatus status);

    /**
     * Find experiments for a connection by status (without ordering).
     */
    List<TuningExperiment> findByConnectionIdAndStatus(
        String connectionId, TuningExperiment.ExperimentStatus status);

    /**
     * Find all running experiments.
     */
    List<TuningExperiment> findByStatus(TuningExperiment.ExperimentStatus status);

    /**
     * Find experiments for a connection ordered by time.
     */
    List<TuningExperiment> findByConnectionIdOrderByCreatedAtDesc(
        String connectionId, Pageable pageable);

    /**
     * Find completed experiments with positive improvement.
     */
    @Query("SELECT e FROM TuningExperiment e WHERE e.connectionId = :connId " +
           "AND e.status = 'COMPLETED' AND e.overallImprovementPercent > 0 " +
           "ORDER BY e.overallImprovementPercent DESC")
    List<TuningExperiment> findSuccessfulExperiments(
        @Param("connId") String connectionId, Pageable pageable);

    /**
     * Count experiments by status.
     */
    long countByConnectionIdAndStatus(String connectionId, TuningExperiment.ExperimentStatus status);

    /**
     * Find experiments that should be checked for completion.
     */
    @Query("SELECT e FROM TuningExperiment e WHERE e.status = 'RUNNING' " +
           "AND e.startedAt < :cutoff")
    List<TuningExperiment> findExperimentsToCheck(@Param("cutoff") LocalDateTime cutoff);

    /**
     * Get the latest experiment for a connection.
     */
    @Query("SELECT e FROM TuningExperiment e WHERE e.connectionId = :connId " +
           "ORDER BY e.createdAt DESC LIMIT 1")
    Optional<TuningExperiment> findLatest(@Param("connId") String connectionId);

    /**
     * Calculate average improvement for successful experiments.
     */
    @Query("SELECT AVG(e.overallImprovementPercent) FROM TuningExperiment e " +
           "WHERE e.connectionId = :connId AND e.status = 'COMPLETED' " +
           "AND e.overallImprovementPercent > 0")
    Double calculateAverageImprovement(@Param("connId") String connectionId);

    /**
     * Find experiments by recommendation ID.
     */
    List<TuningExperiment> findByRecommendationId(String recommendationId);

    /**
     * Check if there's a running experiment.
     */
    boolean existsByConnectionIdAndStatus(String connectionId, TuningExperiment.ExperimentStatus status);
}
