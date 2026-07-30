package com.dbaagent.repository.brain;

import com.dbaagent.model.brain.KnobRanking;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Repository for KnobRanking entities.
 */
@Repository
public interface KnobRankingRepository extends JpaRepository<KnobRanking, String> {

    /**
     * Find rankings for a connection and target metric, ordered by rank.
     */
    List<KnobRanking> findByConnectionIdAndTargetMetricOrderByRankAsc(
        String connectionId, KnobRanking.TargetMetric targetMetric);

    /**
     * Find top N rankings for a connection.
     */
    @Query("SELECT k FROM KnobRanking k WHERE k.connectionId = :connId " +
           "AND k.targetMetric = :metric ORDER BY k.rank ASC")
    List<KnobRanking> findTopKnobs(
        @Param("connId") String connectionId,
        @Param("metric") KnobRanking.TargetMetric targetMetric,
        Pageable pageable);

    /**
     * Find ranking for a specific knob.
     */
    Optional<KnobRanking> findByConnectionIdAndKnobNameAndTargetMetric(
        String connectionId, String knobName, KnobRanking.TargetMetric targetMetric);

    /**
     * Find all rankings for a connection.
     */
    List<KnobRanking> findByConnectionIdOrderByTargetMetricAscRankAsc(String connectionId);

    /**
     * Delete rankings for a connection and target metric.
     */
    @Modifying
    void deleteByConnectionIdAndTargetMetric(
        String connectionId, KnobRanking.TargetMetric targetMetric);

    /**
     * Find high-impact knobs (top 10 by impact score).
     */
    @Query("SELECT k FROM KnobRanking k WHERE k.connectionId = :connId " +
           "ORDER BY k.impactScore DESC")
    List<KnobRanking> findHighImpactKnobs(
        @Param("connId") String connectionId, Pageable pageable);

    /**
     * Count knobs identified for a connection.
     */
    long countByConnectionId(String connectionId);

    /**
     * Find knobs that require restart.
     */
    List<KnobRanking> findByConnectionIdAndRequiresRestartTrue(String connectionId);
}
