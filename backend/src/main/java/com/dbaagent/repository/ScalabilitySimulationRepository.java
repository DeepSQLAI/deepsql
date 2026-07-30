package com.dbaagent.repository;

import com.dbaagent.model.ScalabilitySimulation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface ScalabilitySimulationRepository extends JpaRepository<ScalabilitySimulation, String> {

    /**
     * Find all simulations for a connection.
     */
    List<ScalabilitySimulation> findByConnectionIdOrderBySimulatedAtDesc(String connectionId);

    /**
     * Find latest simulation for a connection.
     */
    Optional<ScalabilitySimulation> findFirstByConnectionIdOrderBySimulatedAtDesc(String connectionId);

    /**
     * Find simulations by growth scenario.
     */
    List<ScalabilitySimulation> findByConnectionIdAndGrowthScenario(String connectionId, String growthScenario);

    /**
     * Find simulations by risk level.
     */
    List<ScalabilitySimulation> findByConnectionIdAndOverallRiskLevel(
        String connectionId, ScalabilitySimulation.RiskLevel riskLevel);

    /**
     * Find top N most recent simulations.
     */
    @Query("SELECT s FROM ScalabilitySimulation s WHERE s.connectionId = :connectionId " +
           "ORDER BY s.simulatedAt DESC LIMIT :limit")
    List<ScalabilitySimulation> findTopByConnectionIdOrderBySimulatedAtDesc(String connectionId, int limit);

    /**
     * Count simulations for a connection.
     */
    long countByConnectionId(String connectionId);
}
