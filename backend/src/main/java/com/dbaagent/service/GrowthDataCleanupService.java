package com.dbaagent.service;

import com.dbaagent.model.GrowthAnomaly;
import com.dbaagent.repository.GrowthAnomalyRepository;
import com.dbaagent.repository.TableStatsHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Growth Data Cleanup Service
 * Automatically deletes old growth monitoring data based on retention policy
 * Runs daily at 2 AM
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class GrowthDataCleanupService {

    private final TableStatsHistoryRepository historyRepository;
    private final GrowthAnomalyRepository anomalyRepository;

    // Retention period: 90 days
    private static final int RETENTION_DAYS = 90;

    /**
     * Cleanup old data - runs daily at 2 AM
     * Cron: "second minute hour day month weekday"
     * "0 0 2 * * *" = Daily at 2:00 AM
     */
    @Transactional
    public void cleanupOldData() {
        log.info("Starting growth monitoring data cleanup (retention: {} days)...", RETENTION_DAYS);

        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(RETENTION_DAYS);
        log.info("Deleting data older than: {}", cutoffDate);

        try {
            // 1. Delete old table snapshots
            int deletedSnapshots = deleteOldSnapshots(cutoffDate);

            // 2. Delete old acknowledged anomalies
            int deletedAnomalies = deleteOldAcknowledgedAnomalies(cutoffDate);

            log.info("Cleanup complete. Deleted {} snapshots and {} acknowledged anomalies",
                    deletedSnapshots, deletedAnomalies);

        } catch (Exception e) {
            log.error("Error during data cleanup", e);
        }
    }

    /**
     * Delete old table snapshots
     */
    private int deleteOldSnapshots(LocalDateTime cutoffDate) {
        try {
            int deleted = historyRepository.deleteBySnapshotTimestampBefore(cutoffDate);
            log.info("Deleted {} old table snapshot records", deleted);
            return deleted;
        } catch (Exception e) {
            log.error("Failed to delete old snapshots", e);
            return 0;
        }
    }

    /**
     * Delete old acknowledged anomalies
     * Keep unacknowledged anomalies regardless of age (need attention)
     */
    private int deleteOldAcknowledgedAnomalies(LocalDateTime cutoffDate) {
        try {
            List<GrowthAnomaly> oldAnomalies = anomalyRepository.findAll().stream()
                    .filter(a -> a.getAcknowledged() != null && a.getAcknowledged())
                    .filter(a -> a.getDetectionTimestamp() != null && a.getDetectionTimestamp().isBefore(cutoffDate))
                    .toList();

            if (!oldAnomalies.isEmpty()) {
                anomalyRepository.deleteAll(oldAnomalies);
                log.info("Deleted {} old acknowledged anomaly records", oldAnomalies.size());
                return oldAnomalies.size();
            }

            return 0;
        } catch (Exception e) {
            log.error("Failed to delete old acknowledged anomalies", e);
            return 0;
        }
    }

    /**
     * Manual trigger for testing
     */
    public Map<String, Integer> manualCleanup() {
        log.info("Manual cleanup triggered");

        LocalDateTime cutoffDate = LocalDateTime.now().minusDays(RETENTION_DAYS);
        int snapshots = deleteOldSnapshots(cutoffDate);
        int anomalies = deleteOldAcknowledgedAnomalies(cutoffDate);

        return Map.of(
            "deleted_snapshots", snapshots,
            "deleted_anomalies", anomalies,
            "retention_days", RETENTION_DAYS
        );
    }
}
