package com.dbaagent.controller;

import com.dbaagent.model.*;
import com.dbaagent.repository.*;
import com.dbaagent.service.EventCorrelationService;
import com.dbaagent.service.SentinelAnalyticsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Demo Data Generator for Sentinel-DBA
 * Creates sample resource limits, forecasts, events, and recommendations
 */
@RestController
@RequestMapping("/sentinel/demo")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class SentinelDemoDataController {

    private final ResourceLimitsRepository limitsRepository;
    private final CapacityForecastRepository forecastRepository;
    private final DatabaseEventRepository eventRepository;
    private final SentinelRecommendationRepository recommendationRepository;
    private final EventCorrelationService eventCorrelation;
    private final SentinelAnalyticsService sentinelAnalytics;

    /**
     * POST /api/sentinel/demo/generate/{connectionId}
     * Generate complete demo data for Sentinel-DBA
     */
    @PostMapping("/generate/{connectionId}")
    public ResponseEntity<Map<String, Object>> generateDemoData(@PathVariable String connectionId) {
        try {
            log.info("Generating Sentinel demo data for connection: {}", connectionId);

            Map<String, Object> results = new HashMap<>();

            // 1. Create ResourceLimits
            ResourceLimits limits = createResourceLimits(connectionId);
            results.put("resourceLimits", limits);

            // 2. Create sample forecasts (Death Clock)
            List<CapacityForecast> forecasts = createSampleForecasts(connectionId);
            results.put("forecasts", forecasts.size());

            // 3. Create sample events
            List<DatabaseEvent> events = createSampleEvents(connectionId);
            results.put("events", events.size());

            // 4. Create sample recommendations
            List<SentinelRecommendation> recommendations = createSampleRecommendations(connectionId, forecasts);
            results.put("recommendations", recommendations.size());

            results.put("success", true);
            results.put("message", "Demo data generated successfully");

            return ResponseEntity.ok(results);

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to generate demo data: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", "Failed to generate demo data",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * DELETE /api/sentinel/demo/cleanup/{connectionId}
     * Clean up demo data
     */
    @DeleteMapping("/cleanup/{connectionId}")
    public ResponseEntity<Map<String, Object>> cleanupDemoData(@PathVariable String connectionId) {
        try {
            log.info("Cleaning up Sentinel demo data for connection: {}", connectionId);

            // Delete in reverse order of dependencies
            recommendationRepository.deleteAll(recommendationRepository.findByConnectionIdOrderByPriorityAscCreatedAtDesc(connectionId));
            forecastRepository.deleteAll(forecastRepository.findByConnectionIdOrderByForecastDateDesc(connectionId));
            eventRepository.deleteAll(eventRepository.findRecentEvents(connectionId, LocalDateTime.now().minusYears(1)));
            limitsRepository.findByConnectionId(connectionId).ifPresent(limitsRepository::delete);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Demo data cleaned up successfully"
            ));

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to cleanup demo data: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", "Failed to cleanup demo data",
                    "message", e.getMessage()
            ));
        }
    }

    // ========== HELPER METHODS ==========

    private ResourceLimits createResourceLimits(String connectionId) {
        // Delete existing if any
        limitsRepository.findByConnectionId(connectionId).ifPresent(limitsRepository::delete);

        ResourceLimits limits = ResourceLimits.builder()
                .connectionId(connectionId)
                .maxStorageBytes(107374182400L)  // 100 GB
                .currentStorageBytes(85899345920L)  // 80 GB (80% used)
                .maxIops(10000)
                .currentIops(7500)  // 75% used
                .maxConnections(500)
                .currentConnections(350)  // 70% used
                .maxCpuPercent(80)
                .storageWarningPercent(70.0)
                .storageCriticalPercent(85.0)
                .iopsWarningPercent(70.0)
                .iopsCriticalPercent(85.0)
                .connectionWarningPercent(70.0)
                .connectionCriticalPercent(85.0)
                .cpuWarningPercent(70.0)
                .cpuCriticalPercent(85.0)
                .build();

        return limitsRepository.save(limits);
    }

    private List<CapacityForecast> createSampleForecasts(String connectionId) {
        List<CapacityForecast> forecasts = new ArrayList<>();

        // Critical: Table exhausting in 7 days
        forecasts.add(createForecast(
                connectionId,
                "USERS",
                7,  // days to exhaustion
                92.0,  // current %
                1.5,  // velocity %/day
                0.3,  // acceleration
                CapacityForecast.GrowthPattern.EXPONENTIAL,
                9,  // risk score
                8   // confidence
        ));

        // Warning: Table exhausting in 45 days
        forecasts.add(createForecast(
                connectionId,
                "ORDERS",
                45,
                75.0,
                0.8,
                0.1,
                CapacityForecast.GrowthPattern.LINEAR,
                6,
                7
        ));

        // Info: Table exhausting in 120 days
        forecasts.add(createForecast(
                connectionId,
                "PRODUCTS",
                120,
                60.0,
                0.3,
                -0.05,
                CapacityForecast.GrowthPattern.LINEAR,
                3,
                6
        ));

        return forecastRepository.saveAll(forecasts);
    }

    private CapacityForecast createForecast(
            String connectionId,
            String tableName,
            int daysToExhaustion,
            double currentPercent,
            double velocity,
            double acceleration,
            CapacityForecast.GrowthPattern pattern,
            int riskScore,
            int confidence) {

        LocalDateTime exhaustionDate = LocalDateTime.now().plusDays(daysToExhaustion);

        return CapacityForecast.builder()
                .connectionId(connectionId)
                .tableName(tableName)
                .forecastDate(LocalDateTime.now())
                .storageExhaustionDate(exhaustionDate)
                .currentStoragePercent(currentPercent)
                .dailyStorageGrowthRate(velocity)
                .storageAcceleration(acceleration)
                .growthPattern(pattern)
                .patternConfidence(confidence)
                .riskScore(riskScore)
                .confidenceScore(confidence)
                .build();
    }

    private List<DatabaseEvent> createSampleEvents(String connectionId) {
        List<DatabaseEvent> events = new ArrayList<>();

        // Recent deployment (2 hours ago)
        events.add(eventCorrelation.logDeploymentEvent(
                connectionId,
                "v2.5.0",
                "release-2.5.0",
                List.of("USERS", "ORDERS", "PAYMENTS"),
                "ci-cd-pipeline"
        ));

        // Schema change yesterday
        DatabaseEvent schemaChange = DatabaseEvent.builder()
                .connectionId(connectionId)
                .eventType(DatabaseEvent.EventType.SCHEMA_CHANGE)
                .eventName("Add indexes to USERS table")
                .eventTimestamp(LocalDateTime.now().minusDays(1))
                .affectedTables(List.of("USERS"))
                .affectedIndexes(List.of("idx_users_email", "idx_users_created_at"))
                .initiatedBy("dba-team")
                .description("Added performance indexes to improve query speed")
                .build();
        events.add(eventRepository.save(schemaChange));

        // Bulk load 3 days ago
        events.add(eventCorrelation.logBulkDataEvent(
                connectionId,
                "PRODUCTS",
                true,  // isLoad
                500000L,  // 500k rows
                "data-migration-job"
        ));

        return events;
    }

    private List<SentinelRecommendation> createSampleRecommendations(
            String connectionId,
            List<CapacityForecast> forecasts) {

        List<SentinelRecommendation> recommendations = new ArrayList<>();

        // Critical: Partition the USERS table
        if (!forecasts.isEmpty()) {
            CapacityForecast criticalForecast = forecasts.get(0);

            recommendations.add(SentinelRecommendation.builder()
                    .connectionId(connectionId)
                    .tableName(criticalForecast.getTableName())
                    .recommendationType(SentinelRecommendation.RecommendationType.PARTITION)
                    .priority(SentinelRecommendation.Priority.IMMEDIATE)
                    .title("Partition USERS table by date to prevent storage exhaustion")
                    .description("Table USERS will exhaust storage in 7 days. Implement time-based partitioning " +
                            "to archive old data and reduce table size. Estimated savings: 45 GB.")
                    .estimatedStorageSavingsBytes(45L * 1024 * 1024 * 1024)  // 45 GB
                    .estimatedPerformanceImprovementPercent(25.0)
                    .estimatedDowntimeHours(2.0)
                    .forecastId(criticalForecast.getId())
                    .status(SentinelRecommendation.Status.PENDING)
                    .build());

            // High: Optimize ORDERS table
            recommendations.add(SentinelRecommendation.builder()
                    .connectionId(connectionId)
                    .tableName("ORDERS")
                    .recommendationType(SentinelRecommendation.RecommendationType.OPTIMIZE)
                    .priority(SentinelRecommendation.Priority.HOUR_24)
                    .title("Optimize ORDERS table to reclaim fragmented space")
                    .description("Table fragmentation detected. Run OPTIMIZE TABLE to reclaim 12 GB of wasted space.")
                    .estimatedStorageSavingsBytes(12L * 1024 * 1024 * 1024)  // 12 GB
                    .estimatedPerformanceImprovementPercent(15.0)
                    .estimatedDowntimeHours(0.5)
                    .status(SentinelRecommendation.Status.PENDING)
                    .build());

            // Medium: Archive old PRODUCTS data
            recommendations.add(SentinelRecommendation.builder()
                    .connectionId(connectionId)
                    .tableName("PRODUCTS")
                    .recommendationType(SentinelRecommendation.RecommendationType.ARCHIVE_DATA)
                    .priority(SentinelRecommendation.Priority.WEEK)
                    .title("Archive discontinued products older than 2 years")
                    .description("Move inactive product records to cold storage to free up space and improve query performance.")
                    .estimatedStorageSavingsBytes(8L * 1024 * 1024 * 1024)  // 8 GB
                    .estimatedPerformanceImprovementPercent(10.0)
                    .estimatedDowntimeHours(0.0)
                    .status(SentinelRecommendation.Status.PENDING)
                    .build());
        }

        return recommendationRepository.saveAll(recommendations);
    }
}
