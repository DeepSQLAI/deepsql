package com.dbaagent.repository;

import com.dbaagent.model.TableGrowthPrediction;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface TableGrowthPredictionRepository extends JpaRepository<TableGrowthPrediction, String> {

    /**
     * Find all predictions for a simulation.
     */
    List<TableGrowthPrediction> findByScalabilitySimulationId(String scalabilitySimulationId);

    /**
     * Find all predictions for a connection.
     */
    List<TableGrowthPrediction> findByConnectionIdOrderByPredictedRowsDesc(String connectionId);

    /**
     * Find predictions by priority.
     */
    List<TableGrowthPrediction> findByConnectionIdAndPriority(
        String connectionId, TableGrowthPrediction.Priority priority);

    /**
     * Find tables with partitioning issues.
     */
    List<TableGrowthPrediction> findByConnectionIdAndLacksPartitioningTrue(String connectionId);

    /**
     * Find tables with missing indexes.
     */
    List<TableGrowthPrediction> findByConnectionIdAndMissingCriticalIndexesTrue(String connectionId);

    /**
     * Find top N largest predicted tables.
     */
    @Query("SELECT t FROM TableGrowthPrediction t WHERE t.connectionId = :connectionId " +
           "ORDER BY t.predictedRows DESC LIMIT :limit")
    List<TableGrowthPrediction> findTopByConnectionIdOrderByPredictedRowsDesc(String connectionId, int limit);

    /**
     * Find high-priority tables with issues.
     */
    @Query("SELECT t FROM TableGrowthPrediction t WHERE t.connectionId = :connectionId " +
           "AND (t.lacksPartitioning = true OR t.missingCriticalIndexes = true) " +
           "ORDER BY t.priority DESC, t.predictedRows DESC")
    List<TableGrowthPrediction> findHighRiskTablesByConnectionId(String connectionId);
}
