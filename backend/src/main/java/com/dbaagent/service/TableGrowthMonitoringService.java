package com.dbaagent.service;

import com.dbaagent.model.DatabaseConnection;
import com.dbaagent.model.DatabaseObject;
import com.dbaagent.model.ResourceLimits;
import com.dbaagent.model.TableStats;
import com.dbaagent.model.TableStatsHistory;
import com.dbaagent.repository.ResourceLimitsRepository;
import com.dbaagent.repository.TableStatsHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Table Growth Monitoring Service
 * Captures hourly snapshots of table statistics and triggers anomaly detection
 *
 * Enhanced with Sentinel-DBA:
 * - Generates capacity forecasts after each snapshot
 * - Correlates anomalies with database events
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TableGrowthMonitoringService {

    private final CredentialService credentialService;
    private final QueryExecutorService queryExecutorService;
    private final TableStatsHistoryRepository historyRepository;
    private final GrowthAnomalyDetectionService anomalyDetectionService;
    private final ResourceLimitsRepository resourceLimitsRepository;

    // Sentinel-DBA services
    private final SentinelAnalyticsService sentinelAnalytics;
    private final EventCorrelationService eventCorrelation;

    /**
     * Capture table snapshots every minute (for testing/development)
     * Cron: "second minute hour day month weekday"
     * "0 * * * * *" = Every minute at second 0
     */
    public void captureMinuteSnapshots() {
        captureSnapshotsForCadence("MINUTE");
    }

    /**
     * Capture table snapshots every hour at minute 0
     * Cron: "second minute hour day month weekday"
     * "0 0 * * * *" = At the start of every hour
     */
    public void captureHourlySnapshots() {
        captureSnapshotsForCadence("HOUR");
    }

    /**
     * Capture table snapshots every day at midnight
     * Cron: "second minute hour day month weekday"
     * "0 0 0 * * *" = At midnight every day
     */
    public void captureDailySnapshots() {
        captureSnapshotsForCadence("DAY");
    }

    /**
     * Capture snapshots for connections with specific cadence
     */
    private void captureSnapshotsForCadence(String cadence) {
        log.info("═══════════════════════════════════════════════════════════════");
        log.info("Starting {} snapshot collection cycle...", cadence);
        log.info("═══════════════════════════════════════════════════════════════");

        try {
            List<DatabaseConnection> connections = credentialService.getAllConnections();
            log.info("Found {} total database connections", connections.size());

            // Filter connections by cadence
            List<DatabaseConnection> targetConnections = connections.stream()
                    .filter(conn -> {
                        ResourceLimits limits = resolveOrCreateResourceLimits(conn);
                        if (!Boolean.TRUE.equals(limits.getIsEnabled())) {
                            log.debug("Connection {} has growth monitoring disabled, skipping", conn.getConnectionName());
                            return false;
                        }
                        String connectionCadence = normalizeCadence(limits.getCollectionCadence());
                        boolean matches = cadence.equals(connectionCadence);
                        if (matches) {
                            log.info("✓ Connection {} configured for {} collection", conn.getConnectionName(), cadence);
                        }
                        return matches;
                    })
                    .toList();

            if (targetConnections.isEmpty()) {
                log.info("No connections configured for {} collection cadence", cadence);
                log.info("═══════════════════════════════════════════════════════════════");
                return;
            }

            log.info("Processing {} connections with {} cadence", targetConnections.size(), cadence);
            log.info("───────────────────────────────────────────────────────────────");

            int totalSnapshots = 0;
            int totalAnomalies = 0;
            int totalTables = 0;

            for (DatabaseConnection connection : targetConnections) {
                try {
                    log.info("📊 Processing connection: {} ({})", connection.getConnectionName(), connection.getId());
                    int[] counts = captureConnectionSnapshots(connection);
                    totalSnapshots += counts[0];
                    totalAnomalies += counts[1];
                    totalTables += counts[2];
                    log.info("✓ Completed {}: {} tables, {} snapshots, {} anomalies",
                            connection.getConnectionName(), counts[2], counts[0], counts[1]);
                } catch (Exception e) {
                    log.error("✗ Failed to capture snapshots for connection: {} ({})",
                            connection.getConnectionName(), connection.getId(), e);
                }
                log.info("───────────────────────────────────────────────────────────────");
            }

            log.info("═══════════════════════════════════════════════════════════════");
            log.info("✓ {} collection cycle complete", cadence);
            log.info("Summary: {} connections, {} tables, {} snapshots, {} anomalies",
                    targetConnections.size(), totalTables, totalSnapshots, totalAnomalies);
            log.info("═══════════════════════════════════════════════════════════════");

        } catch (Exception e) {
            log.error("✗ Error during {} snapshot collection", cadence, e);
            log.info("═══════════════════════════════════════════════════════════════");
        }
    }

    private ResourceLimits resolveOrCreateResourceLimits(DatabaseConnection connection) {
        return resourceLimitsRepository.findByConnectionId(connection.getId())
                .orElseGet(() -> {
                    ResourceLimits defaults = ResourceLimits.builder()
                            .id(UUID.randomUUID().toString())
                            .connectionId(connection.getId())
                            .isEnabled(true)
                            .collectionCadence("HOUR")
                            .build();
                    ResourceLimits saved = resourceLimitsRepository.save(defaults);
                    log.info(
                            "Initialized default growth monitoring for connection {} with cadence {}",
                            connection.getConnectionName(),
                            saved.getCollectionCadence()
                    );
                    return saved;
                });
    }

    private String normalizeCadence(String cadence) {
        if (cadence == null || cadence.isBlank()) {
            return "HOUR";
        }
        String normalized = cadence.trim().toUpperCase();
        return switch (normalized) {
            case "MINUTE", "HOUR", "DAY" -> normalized;
            default -> "HOUR";
        };
    }

    /**
     * Capture snapshots for all tables in a connection
     * @return Array: [snapshotCount, anomalyCount, tableCount]
     *
     * Processes tables in batches to prevent connection pool exhaustion
     */
    private int[] captureConnectionSnapshots(DatabaseConnection connection) {
        log.debug("  → Fetching tables for connection: {}", connection.getConnectionName());

        int snapshotCount = 0;
        int anomalyCount = 0;
        int tableCount = 0;

        try {
            List<DatabaseObject> tables = queryExecutorService.getDatabaseObjects(connection.getId());
            log.debug("  → Found {} database objects", tables.size());

            // Filter to only process tables (not views, procedures, etc.)
            List<DatabaseObject> tablesOnly = tables.stream()
                    .filter(obj -> "TABLE".equalsIgnoreCase(obj.getType()))
                    .toList();

            tableCount = tablesOnly.size();
            log.info("  → Processing {} tables for snapshot collection", tableCount);

            // Process tables in batches to prevent connection pool exhaustion
            final int BATCH_SIZE = 50;
            int batchCount = (tableCount + BATCH_SIZE - 1) / BATCH_SIZE;

            for (int batchNum = 0; batchNum < batchCount; batchNum++) {
                int startIdx = batchNum * BATCH_SIZE;
                int endIdx = Math.min(startIdx + BATCH_SIZE, tableCount);
                List<DatabaseObject> batch = tablesOnly.subList(startIdx, endIdx);

                log.debug("  → Processing batch {}/{} ({} tables)",
                         batchNum + 1, batchCount, batch.size());

                // Process each table in the batch
                for (int i = 0; i < batch.size(); i++) {
                    DatabaseObject table = batch.get(i);
                    int tableNum = startIdx + i + 1;

                    try {
                        log.debug("    [{}/{}] Capturing snapshot: {}", tableNum, tableCount, table.getName());
                        boolean anomalyDetected = captureSnapshot(connection.getId(), table.getName());
                        snapshotCount++;
                        if (anomalyDetected) {
                            anomalyCount++;
                            log.warn("    ⚠️  Anomaly detected in table: {}", table.getName());
                        }
                    } catch (Exception e) {
                        log.error("    ✗ Failed to capture snapshot for table: {} - {}",
                                table.getName(), e.getMessage());
                    }
                }

                // Brief pause between batches to allow connection pool to recycle
                if (batchNum < batchCount - 1) {
                    try {
                        Thread.sleep(100); // 100ms pause between batches
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("Batch processing interrupted");
                        break;
                    }
                }
            }

            log.info("  → Snapshot collection summary: {}/{} tables successful, {} anomalies",
                    snapshotCount, tableCount, anomalyCount);

        } catch (Exception e) {
            log.error("  ✗ Failed to get database objects for connection: {} - {}",
                    connection.getConnectionName(), e.getMessage());
        }

        return new int[]{snapshotCount, anomalyCount, tableCount};
    }

    /**
     * Capture a single table snapshot
     * @return true if anomaly was detected
     *
     * @Transactional ensures all repository operations use a single database connection,
     * reducing connection pool pressure from 4-5 connections per table to just 1.
     */
    @Transactional
    private boolean captureSnapshot(String connectionId, String tableName) {
        try {
            // Get current table statistics using existing service
            TableStats stats = queryExecutorService.getTableStats(connectionId, tableName);

            // Build snapshot from current stats
            TableStatsHistory snapshot = buildSnapshot(connectionId, tableName, stats);

            // Get previous snapshot for growth calculation
            Optional<TableStatsHistory> previousOpt = historyRepository
                    .findFirstByConnectionIdAndTableNameOrderBySnapshotTimestampDesc(
                            connectionId, tableName);

            if (previousOpt.isPresent()) {
                TableStatsHistory previous = previousOpt.get();

                // Calculate growth metrics
                snapshot.calculateGrowthMetrics(previous);

                // Save snapshot
                TableStatsHistory saved = historyRepository.save(snapshot);

                log.debug("      ✓ Snapshot saved: {} - Size: {} MB, Growth: {} bytes ({:.2f}%)",
                        tableName,
                        stats.getSizeBytes() / (1024 * 1024),
                        snapshot.getSizeGrowthBytes(),
                        snapshot.getSizeGrowthPercent() != null ? snapshot.getSizeGrowthPercent() : 0.0);

                // Trigger anomaly detection (enhanced with Sentinel analytics)
                boolean anomalyDetected = anomalyDetectionService.detectAnomalies(saved, previous);

                // === SENTINEL-DBA FORECASTING ===
                // Generate capacity forecast every snapshot to track growth trends
                try {
                    sentinelAnalytics.forecastResourceExhaustion(connectionId, tableName);
                    log.debug("      ✓ Sentinel forecast updated for table: {}", tableName);
                } catch (Exception e) {
                    log.debug("      ⚠️  Sentinel forecast skipped for table {}: {}",
                            tableName, e.getMessage());
                }

                return anomalyDetected;

            } else {
                // First snapshot for this table - no comparison possible
                TableStatsHistory saved = historyRepository.save(snapshot);
                log.info("      ℹ️  First snapshot captured for table: {} - Size: {} MB",
                        tableName, stats.getSizeBytes() / (1024 * 1024));

                // Record a NEW_TABLE anomaly so the table's arrival is visible
                try {
                    anomalyDetectionService.createNewTableAnomaly(saved);
                } catch (Exception e) {
                    log.warn("      ⚠️  Failed to record NEW_TABLE anomaly for {}: {}",
                            tableName, e.getMessage());
                }

                return false;
            }

        } catch (Exception e) {
            log.error("      ✗ Failed to capture snapshot for table: {} - {}", tableName, e.getMessage());
            return false;
        }
    }

    /**
     * Build TableStatsHistory from TableStats
     */
    private TableStatsHistory buildSnapshot(String connectionId, String tableName, TableStats stats) {
        return TableStatsHistory.builder()
                .connectionId(connectionId)
                .tableName(tableName)
                .snapshotTimestamp(LocalDateTime.now())
                .sizeBytes(stats.getSizeBytes())
                .rowCount(stats.getRowCount())
                .indexSizeBytes(stats.getIndexSizeBytes())
                .dataSizeBytes(stats.getDataSize())
                .allocatedBytes(stats.getAllocatedBytes())
                .bloatBytes(stats.getBloatBytes())
                .bloatPercent(stats.getBloatPercent())
                .build();
    }

    /*
     * Correlate unattributed anomalies with events - runs every 6 hours
     */
    public void correlateAnomaliesWithEvents() {
        log.info("Starting Sentinel event correlation for unattributed anomalies...");

        try {
            List<DatabaseConnection> connections = credentialService.getAllConnections();

            for (DatabaseConnection connection : connections) {
                try {
                    log.debug("Correlating anomalies for connection: {}", connection.getConnectionName());
                    eventCorrelation.correlateRecentAnomalies(connection.getId());
                } catch (Exception e) {
                    log.error("Failed to correlate anomalies for connection: {}",
                            connection.getConnectionName(), e);
                }
            }

            log.info("Event correlation complete");

        } catch (Exception e) {
            log.error("Error during event correlation", e);
        }
    }

    /**
     * Manual trigger for testing/debugging
     */
    public void manualTrigger(String connectionId) {
        log.info("Manual snapshot capture triggered for connection: {}", connectionId);

        try {
            DatabaseConnection connection = credentialService.getAllConnections().stream()
                    .filter(c -> c.getId().equals(connectionId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Connection not found: " + connectionId));

            int[] counts = captureConnectionSnapshots(connection);
            log.info("Manual capture complete. Snapshots: {}, Anomalies: {}", counts[0], counts[1]);

        } catch (Exception e) {
            log.error("Manual trigger failed for connection: {}", connectionId, e);
            throw new RuntimeException("Manual trigger failed", e);
        }
    }
}
