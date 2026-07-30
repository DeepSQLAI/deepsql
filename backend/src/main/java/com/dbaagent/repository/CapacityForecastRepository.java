package com.dbaagent.repository;

import com.dbaagent.model.CapacityForecast;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository for CapacityForecast
 * Manages Death Clock predictions and growth analysis
 */
@Repository
public interface CapacityForecastRepository extends JpaRepository<CapacityForecast, String> {

    /**
     * Find latest forecast for a connection
     */
    Optional<CapacityForecast> findFirstByConnectionIdOrderByForecastDateDesc(String connectionId);

    /**
     * Find latest forecast for a specific table
     */
    Optional<CapacityForecast> findFirstByConnectionIdAndTableNameOrderByForecastDateDesc(
            String connectionId, String tableName);

    /**
     * Find all forecasts for a connection
     */
    List<CapacityForecast> findByConnectionIdOrderByForecastDateDesc(String connectionId);

    /**
     * Find forecasts within date range
     */
    List<CapacityForecast> findByConnectionIdAndForecastDateBetweenOrderByForecastDateDesc(
            String connectionId, LocalDateTime start, LocalDateTime end);

    /**
     * Find critical forecasts (high risk, high confidence)
     */
    @Query("SELECT f FROM CapacityForecast f WHERE f.connectionId = :connectionId " +
           "AND f.riskScore >= 7 AND f.confidenceScore >= 7 " +
           "ORDER BY f.riskScore DESC, f.storageExhaustionDate ASC")
    List<CapacityForecast> findCriticalForecasts(@Param("connectionId") String connectionId);

    /**
     * Find forecasts with imminent exhaustion (within days)
     */
    @Query("SELECT f FROM CapacityForecast f WHERE f.connectionId = :connectionId " +
           "AND f.storageExhaustionDate <= :threshold " +
           "ORDER BY f.storageExhaustionDate ASC")
    List<CapacityForecast> findImminentExhaustion(
            @Param("connectionId") String connectionId,
            @Param("threshold") LocalDateTime threshold);

    /**
     * Find forecasts by growth pattern
     */
    List<CapacityForecast> findByConnectionIdAndGrowthPatternOrderByForecastDateDesc(
            String connectionId, CapacityForecast.GrowthPattern pattern);

    /**
     * Delete old forecasts (keep only recent)
     */
    void deleteByForecastDateBefore(LocalDateTime cutoffDate);
}
