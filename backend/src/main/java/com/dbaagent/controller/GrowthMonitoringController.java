package com.dbaagent.controller;

import com.dbaagent.model.GrowthAlertConfiguration;
import com.dbaagent.model.GrowthAnomaly;
import com.dbaagent.model.TableStatsHistory;
import com.dbaagent.repository.GrowthAlertConfigurationRepository;
import com.dbaagent.repository.GrowthAnomalyRepository;
import com.dbaagent.repository.TableStatsHistoryRepository;
import com.dbaagent.service.GrowthDataCleanupService;
import com.dbaagent.service.TableGrowthMonitoringService;
import com.dbaagent.service.security.AccessControlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Growth Monitoring REST Controller
 * Provides API endpoints for growth monitoring data and configuration
 */
@Slf4j
@RestController
@RequestMapping("/growth-monitoring")
@RequiredArgsConstructor
public class GrowthMonitoringController {

    private final TableStatsHistoryRepository historyRepository;
    private final GrowthAnomalyRepository anomalyRepository;
    private final GrowthAlertConfigurationRepository configRepository;
    private final TableGrowthMonitoringService monitoringService;
    private final GrowthDataCleanupService cleanupService;
    private final AccessControlService accessControlService;

    /**
     * Get growth history for tables
     * Query params: tableName (optional), days (default 7)
     */
    @GetMapping("/history/{connectionId}")
    public ResponseEntity<Map<String, Object>> getGrowthHistory(
            @PathVariable String connectionId,
            @RequestParam(required = false) String tableName,
            @RequestParam(defaultValue = "7") int days) {
        accessControlService.assertCanReadConnectionContent(connectionId);

        Map<String, Object> response = new HashMap<>();

        try {
            LocalDateTime since = LocalDateTime.now().minusDays(days);
            List<TableStatsHistory> history;

            if (tableName != null && !tableName.isEmpty()) {
                history = historyRepository.findByConnectionIdAndTableNameAndSnapshotTimestampBetweenOrderBySnapshotTimestampAsc(
                        connectionId, tableName, since, LocalDateTime.now());
            } else {
                history = historyRepository.findByConnectionIdAndSnapshotTimestampBetweenOrderBySnapshotTimestampAsc(
                        connectionId, since, LocalDateTime.now());
            }

            response.put("success", true);
            response.put("history", history);
            response.put("count", history.size());
            response.put("days", days);

            return ResponseEntity.ok(response);

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to fetch growth history: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Get detected anomalies
     * Query params: tableName (optional), unacknowledgedOnly (default false), days (default 30)
     */
    @GetMapping("/anomalies/{connectionId}")
    public ResponseEntity<Map<String, Object>> getAnomalies(
            @PathVariable String connectionId,
            @RequestParam(required = false) String tableName,
            @RequestParam(defaultValue = "false") boolean unacknowledgedOnly,
            @RequestParam(defaultValue = "30") int days) {
        accessControlService.assertCanReadConnectionContent(connectionId);


        Map<String, Object> response = new HashMap<>();

        try {
            List<GrowthAnomaly> anomalies;
            LocalDateTime since = LocalDateTime.now().minusDays(days);

            if (tableName != null && !tableName.isEmpty()) {
                if (unacknowledgedOnly) {
                    anomalies = anomalyRepository.findByConnectionIdAndTableNameAndAcknowledgedFalseOrderByDetectionTimestampDesc(
                            connectionId, tableName);
                } else {
                    anomalies = anomalyRepository.findRecentAnomaliesForTable(connectionId, tableName, since);
                }
            } else {
                if (unacknowledgedOnly) {
                    anomalies = anomalyRepository.findByConnectionIdAndAcknowledgedFalseOrderByDetectionTimestampDesc(connectionId);
                } else {
                    anomalies = anomalyRepository.findRecentAnomalies(connectionId, since);
                }
            }

            // Calculate statistics
            long totalCount = anomalies.size();
            long unacknowledgedCount = anomalies.stream().filter(a -> !a.getAcknowledged()).count();
            long criticalCount = anomalies.stream().filter(a -> a.getSeverity() == GrowthAnomaly.Severity.CRITICAL).count();
            long warningCount = anomalies.stream().filter(a -> a.getSeverity() == GrowthAnomaly.Severity.WARNING).count();

            Map<String, Long> statistics = new HashMap<>();
            statistics.put("total", totalCount);
            statistics.put("unacknowledged", unacknowledgedCount);
            statistics.put("critical", criticalCount);
            statistics.put("warning", warningCount);

            response.put("success", true);
            response.put("anomalies", anomalies);
            response.put("statistics", statistics);

            return ResponseEntity.ok(response);

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to fetch anomalies: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Acknowledge an anomaly
     */
    @PostMapping("/anomalies/{anomalyId}/acknowledge")
    public ResponseEntity<Map<String, Object>> acknowledgeAnomaly(
            @PathVariable String anomalyId,
            @RequestBody Map<String, String> body) {

        Map<String, Object> response = new HashMap<>();

        try {
            Optional<GrowthAnomaly> anomalyOpt = anomalyRepository.findById(anomalyId);

            if (anomalyOpt.isEmpty()) {
                response.put("success", false);
                response.put("message", "Anomaly not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            GrowthAnomaly anomaly = anomalyOpt.get();
            accessControlService.assertCanManageConnectionContent(anomaly.getConnectionId());
            String acknowledgedBy = body.getOrDefault("acknowledgedBy", "system");

            anomaly.acknowledge(acknowledgedBy);
            anomalyRepository.save(anomaly);

            response.put("success", true);
            response.put("anomaly", anomaly);
            response.put("message", "Anomaly acknowledged successfully");

            return ResponseEntity.ok(response);

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to acknowledge anomaly: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Get alert configuration
     * Query param: tableName (optional, returns table-specific or global config)
     */
    @GetMapping("/config/{connectionId}")
    public ResponseEntity<Map<String, Object>> getConfiguration(
            @PathVariable String connectionId,
            @RequestParam(required = false) String tableName) {
        accessControlService.assertCanReadConnectionContent(connectionId);

        Map<String, Object> response = new HashMap<>();

        try {
            if (tableName != null && !tableName.isEmpty()) {
                // Get specific table config or effective config
                Optional<GrowthAlertConfiguration> configOpt = configRepository
                        .findEffectiveConfiguration(connectionId, tableName);

                if (configOpt.isPresent()) {
                    response.put("success", true);
                    response.put("configuration", configOpt.get());
                } else {
                    response.put("success", false);
                    response.put("message", "No configuration found");
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
                }
            } else {
                // Get all configurations for connection
                List<GrowthAlertConfiguration> configs = configRepository.findByConnectionId(connectionId);
                response.put("success", true);
                response.put("configurations", configs);
                response.put("count", configs.size());
            }

            return ResponseEntity.ok(response);

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to fetch configuration: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Save or update alert configuration
     */
    @PostMapping("/config")
    public ResponseEntity<Map<String, Object>> saveConfiguration(
            @RequestBody GrowthAlertConfiguration config) {
        accessControlService.assertCanManageConnectionContent(config.getConnectionId());

        Map<String, Object> response = new HashMap<>();

        try {
            // Check if config already exists
            Optional<GrowthAlertConfiguration> existingOpt = configRepository
                    .findByConnectionIdAndTableName(config.getConnectionId(), config.getTableName());

            GrowthAlertConfiguration saved;
            if (existingOpt.isPresent()) {
                // Update existing
                GrowthAlertConfiguration existing = existingOpt.get();
                existing.setPercentageGrowthWarning(config.getPercentageGrowthWarning());
                existing.setPercentageGrowthCritical(config.getPercentageGrowthCritical());
                existing.setAbsoluteGrowthWarningBytes(config.getAbsoluteGrowthWarningBytes());
                existing.setAbsoluteGrowthCriticalBytes(config.getAbsoluteGrowthCriticalBytes());
                existing.setRowSpikeWarning(config.getRowSpikeWarning());
                existing.setRowSpikeCritical(config.getRowSpikeCritical());
                existing.setRowSpikePercentage(config.getRowSpikePercentage());
                existing.setZScoreThreshold(config.getZScoreThreshold());
                existing.setHistoricalWindowHours(config.getHistoricalWindowHours());
                existing.setNotificationChannels(config.getNotificationChannels());
                existing.setEmailRecipients(config.getEmailRecipients());
                existing.setWebhookUrl(config.getWebhookUrl());
                existing.setMinHoursBetweenAlerts(config.getMinHoursBetweenAlerts());
                existing.setIsEnabled(config.getIsEnabled());
                saved = configRepository.save(existing);
            } else {
                // Create new
                saved = configRepository.save(config);
            }

            response.put("success", true);
            response.put("configuration", saved);
            response.put("message", "Configuration saved successfully");

            return ResponseEntity.ok(response);

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to save configuration: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Get growth trends data for charts
     */
    @GetMapping("/trends/{connectionId}")
    public ResponseEntity<Map<String, Object>> getGrowthTrends(
            @PathVariable String connectionId,
            @RequestParam(required = false) String tableName,
            @RequestParam(defaultValue = "30") int days) {
        accessControlService.assertCanReadConnectionContent(connectionId);

        Map<String, Object> response = new HashMap<>();

        try {
            LocalDateTime since = LocalDateTime.now().minusDays(days);
            List<TableStatsHistory> history;

            if (tableName != null && !tableName.isEmpty()) {
                history = historyRepository.findByConnectionIdAndTableNameAndSnapshotTimestampBetweenOrderBySnapshotTimestampAsc(
                        connectionId, tableName, since, LocalDateTime.now());
            } else {
                history = historyRepository.findByConnectionIdAndSnapshotTimestampBetweenOrderBySnapshotTimestampAsc(
                        connectionId, since, LocalDateTime.now());
            }

            // Transform data for Recharts
            List<Map<String, Object>> sizeOverTime = history.stream().map(h -> {
                Map<String, Object> point = new HashMap<>();
                point.put("timestamp", h.getSnapshotTimestamp().toString());
                point.put("table", h.getTableName());
                point.put("sizeBytes", h.getSizeBytes());
                point.put("sizeGB", h.getSizeBytes() != null ? h.getSizeBytes() / (1024.0 * 1024 * 1024) : 0);
                return point;
            }).collect(Collectors.toList());

            List<Map<String, Object>> growthOverTime = history.stream()
                    .filter(h -> h.getSizeGrowthPercent() != null)
                    .map(h -> {
                        Map<String, Object> point = new HashMap<>();
                        point.put("timestamp", h.getSnapshotTimestamp().toString());
                        point.put("table", h.getTableName());
                        point.put("growthPercent", h.getSizeGrowthPercent());
                        point.put("growthBytes", h.getSizeGrowthBytes());
                        return point;
                    }).collect(Collectors.toList());

            List<Map<String, Object>> rowCountOverTime = history.stream().map(h -> {
                Map<String, Object> point = new HashMap<>();
                point.put("timestamp", h.getSnapshotTimestamp().toString());
                point.put("table", h.getTableName());
                point.put("rowCount", h.getRowCount());
                return point;
            }).collect(Collectors.toList());

            Map<String, Object> trends = new HashMap<>();
            trends.put("sizeOverTime", sizeOverTime);
            trends.put("growthOverTime", growthOverTime);
            trends.put("rowCountOverTime", rowCountOverTime);

            response.put("success", true);
            response.put("trends", trends);
            response.put("days", days);

            return ResponseEntity.ok(response);

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to fetch trends: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Manual trigger for snapshot capture (testing/debugging)
     * Runs asynchronously to avoid timeout issues with large databases
     */
    @PostMapping("/capture/{connectionId}")
    public ResponseEntity<Map<String, Object>> manualCapture(@PathVariable String connectionId) {
        accessControlService.assertCanManageConnectionContent(connectionId);
        Map<String, Object> response = new HashMap<>();

        try {
            log.info("Starting async snapshot capture for connection: {}", connectionId);

            // Run capture in background thread to avoid HTTP timeout
            new Thread(() -> {
                try {
                    monitoringService.manualTrigger(connectionId);
                    log.info("Completed async snapshot capture for connection: {}", connectionId);
                } catch (org.springframework.web.server.ResponseStatusException e) {
                    throw e;
                } catch (Exception e) {
                    log.error("Async snapshot capture failed for connection {}: {}", connectionId, e.getMessage(), e);
                }
            }, "manual-capture-" + connectionId).start();

            response.put("success", true);
            response.put("message", "Snapshot capture started in background. Check logs for progress.");

            return ResponseEntity.ok(response);

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Manual trigger failed for connection {}: {}", connectionId, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Manual trigger failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Manual trigger for data cleanup (testing/debugging)
     */
    @PostMapping("/cleanup")
    public ResponseEntity<Map<String, Object>> manualCleanup() {
        Map<String, Object> response = new HashMap<>();

        try {
            Map<String, Integer> result = cleanupService.manualCleanup();

            response.put("success", true);
            response.put("result", result);
            response.put("message", "Manual cleanup completed successfully");

            return ResponseEntity.ok(response);

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Manual cleanup failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
}
