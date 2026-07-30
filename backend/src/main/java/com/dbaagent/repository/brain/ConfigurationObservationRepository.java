package com.dbaagent.repository.brain;

import com.dbaagent.model.brain.ConfigurationObservation;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for ConfigurationObservation entities.
 */
@Repository
public interface ConfigurationObservationRepository extends JpaRepository<ConfigurationObservation, String> {

    /**
     * Find observations for a connection ordered by time.
     */
    List<ConfigurationObservation> findByConnectionIdOrderByObservedAtDesc(
        String connectionId, Pageable pageable);

    /**
     * Find observations by type.
     */
    List<ConfigurationObservation> findByConnectionIdAndObservationTypeOrderByObservedAtDesc(
        String connectionId, ConfigurationObservation.ObservationType type);

    /**
     * Find the baseline observation for a connection.
     */
    @Query("SELECT o FROM ConfigurationObservation o WHERE o.connectionId = :connId " +
           "AND o.observationType = 'BASELINE' ORDER BY o.observedAt DESC LIMIT 1")
    Optional<ConfigurationObservation> findLatestBaseline(@Param("connId") String connectionId);

    /**
     * Find observations within a time range.
     */
    List<ConfigurationObservation> findByConnectionIdAndObservedAtBetweenOrderByObservedAtAsc(
        String connectionId, LocalDateTime start, LocalDateTime end);

    /**
     * Count observations for a connection.
     */
    long countByConnectionId(String connectionId);

    /**
     * Find observations with positive improvement.
     */
    @Query("SELECT o FROM ConfigurationObservation o WHERE o.connectionId = :connId " +
           "AND o.improvementPercent > 0 ORDER BY o.improvementPercent DESC")
    List<ConfigurationObservation> findSuccessfulObservations(
        @Param("connId") String connectionId, Pageable pageable);

    /**
     * Get the best performing observation.
     */
    @Query("SELECT o FROM ConfigurationObservation o WHERE o.connectionId = :connId " +
           "AND o.latencyP50After IS NOT NULL ORDER BY o.latencyP50After ASC LIMIT 1")
    Optional<ConfigurationObservation> findBestPerforming(@Param("connId") String connectionId);

    /**
     * Find observations by workload fingerprint.
     */
    List<ConfigurationObservation> findByWorkloadFingerprintOrderByObservedAtDesc(
        String workloadFingerprint);

    /**
     * Count recommended observations.
     */
    @Query("SELECT COUNT(o) FROM ConfigurationObservation o WHERE o.connectionId = :connId " +
           "AND o.observationType = 'RECOMMENDED'")
    long countRecommendedObservations(@Param("connId") String connectionId);
}
