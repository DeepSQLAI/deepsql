package com.dbaagent.service;

import com.dbaagent.model.GrowthAlertConfiguration;
import com.dbaagent.model.GrowthAnomaly;
import com.dbaagent.model.PlaybookAlert;
import com.dbaagent.repository.GrowthAlertConfigurationRepository;
import com.dbaagent.repository.GrowthAnomalyRepository;
import com.dbaagent.repository.PlaybookAlertRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Notification Service for Growth Monitoring
 * Orchestrates multi-channel alert delivery (in-app, email, webhook)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final PlaybookAlertRepository playbookAlertRepository;
    private final GrowthAlertConfigurationRepository configRepository;
    private final GrowthAnomalyRepository anomalyRepository;
    private final EmailService emailService;
    private final WebhookService webhookService;

    /**
     * Send alert through configured channels
     */
    public void sendAlert(GrowthAnomaly anomaly, GrowthAlertConfiguration config) {
        // Check throttling
        if (!config.canSendAlert()) {
            log.info("Alert throttled for table: {} (min {} hours between alerts)",
                    anomaly.getTableName(), config.getMinHoursBetweenAlerts());
            return;
        }

        List<String> sentChannels = new ArrayList<>();

        // 1. Always create in-app alert via PlaybookAlert
        PlaybookAlert inAppAlert = createInAppAlert(anomaly);
        playbookAlertRepository.save(inAppAlert);
        sentChannels.add("in-app");

        // Link alert ID back to anomaly
        anomaly.setAlertId(inAppAlert.getId());
        anomalyRepository.save(anomaly);

        // 2. Send email if configured
        if (config.getNotificationChannels() != null &&
            config.getNotificationChannels().contains("email") &&
            config.getEmailRecipients() != null &&
            !config.getEmailRecipients().isEmpty()) {

            try {
                emailService.sendGrowthAlert(anomaly, config.getEmailRecipients());
                sentChannels.add("email");
                log.info("Email alert sent for table: {}", anomaly.getTableName());
            } catch (Exception e) {
                log.error("Failed to send email alert for table: {}", anomaly.getTableName(), e);
            }
        }

        // 3. Send webhook if configured
        if (config.getNotificationChannels() != null &&
            config.getNotificationChannels().contains("webhook") &&
            config.getWebhookUrl() != null &&
            !config.getWebhookUrl().isEmpty()) {

            try {
                webhookService.sendGrowthAlert(anomaly, config.getWebhookUrl());
                sentChannels.add("webhook");
                log.info("Webhook alert sent for table: {}", anomaly.getTableName());
            } catch (Exception e) {
                log.error("Failed to send webhook alert for table: {}", anomaly.getTableName(), e);
            }
        }

        // Update alert with channels sent
        inAppAlert.setChannelsSent(sentChannels);
        playbookAlertRepository.save(inAppAlert);

        // Mark alert sent in config (for throttling)
        config.markAlertSent();
        configRepository.save(config);

        log.info("Alert sent for table: {} via channels: {}", anomaly.getTableName(), sentChannels);
    }

    /**
     * Create PlaybookAlert for in-app display
     */
    private PlaybookAlert createInAppAlert(GrowthAnomaly anomaly) {
        return PlaybookAlert.builder()
                .playbookRunId("growth-monitoring") // Special identifier for growth monitoring
                .connectionId(anomaly.getConnectionId())
                .severity(mapSeverity(anomaly.getSeverity()))
                .title("Table Growth Alert: " + anomaly.getTableName())
                .message(anomaly.getDescription())
                .findings(buildFindings(anomaly))
                .recommendations(buildRecommendations(anomaly))
                .build();
    }

    /**
     * Map GrowthAnomaly.Severity to PlaybookAlert.Severity
     */
    private PlaybookAlert.Severity mapSeverity(GrowthAnomaly.Severity severity) {
        return switch (severity) {
            case INFO -> PlaybookAlert.Severity.INFO;
            case WARNING -> PlaybookAlert.Severity.WARNING;
            case CRITICAL -> PlaybookAlert.Severity.CRITICAL;
        };
    }

    /**
     * Build findings map for PlaybookAlert
     */
    private Map<String, Object> buildFindings(GrowthAnomaly anomaly) {
        Map<String, Object> findings = new HashMap<>();
        findings.put("anomaly_type", anomaly.getAnomalyType().getDisplayName());
        findings.put("table_name", anomaly.getTableName());
        findings.put("detection_time", anomaly.getDetectionTimestamp().toString());

        if (anomaly.getCurrentSizeBytes() != null) {
            findings.put("current_size", formatBytes(anomaly.getCurrentSizeBytes()));
        }
        if (anomaly.getPreviousSizeBytes() != null) {
            findings.put("previous_size", formatBytes(anomaly.getPreviousSizeBytes()));
        }
        if (anomaly.getSizeGrowthBytes() != null) {
            findings.put("size_growth", formatBytes(anomaly.getSizeGrowthBytes()));
        }
        if (anomaly.getSizeGrowthPercent() != null) {
            findings.put("growth_percentage", String.format("%.1f%%", anomaly.getSizeGrowthPercent()));
        }
        if (anomaly.getCurrentRowCount() != null) {
            findings.put("current_row_count", String.format("%,d", anomaly.getCurrentRowCount()));
        }
        if (anomaly.getRowCountGrowth() != null) {
            findings.put("row_count_growth", String.format("%,d", anomaly.getRowCountGrowth()));
        }
        if (anomaly.getZScore() != null) {
            findings.put("z_score", String.format("%.2f", anomaly.getZScore()));
        }

        return findings;
    }

    /**
     * Build recommendations list for PlaybookAlert
     */
    private List<Map<String, Object>> buildRecommendations(GrowthAnomaly anomaly) {
        List<Map<String, Object>> recommendations = new ArrayList<>();

        switch (anomaly.getAnomalyType()) {
            case PERCENTAGE_GROWTH:
            case ABSOLUTE_GROWTH:
                addRecommendation(recommendations, "high",
                        "Investigate recent application changes or data ingestion processes");
                addRecommendation(recommendations, "high",
                        "Review slow query logs for bulk INSERT/UPDATE operations");
                addRecommendation(recommendations, "medium",
                        "Consider implementing data archival or partitioning strategy");
                break;

            case ROW_SPIKE:
                addRecommendation(recommendations, "high",
                        "Check for batch data import or ETL processes");
                addRecommendation(recommendations, "high",
                        "Verify application logic isn't creating duplicate records");
                addRecommendation(recommendations, "medium",
                        "Review table indexes to ensure query performance isn't degraded");
                break;

            case STATISTICAL_ANOMALY:
                addRecommendation(recommendations, "high",
                        "Investigate unusual growth pattern - deviates from historical trend");
                addRecommendation(recommendations, "medium",
                        "Review recent deployments or configuration changes");
                addRecommendation(recommendations, "low",
                        "Monitor table growth over next 24 hours to identify pattern");
                break;
        }

        // Add common recommendations
        addRecommendation(recommendations, "medium",
                "Monitor disk space to ensure sufficient capacity");
        addRecommendation(recommendations, "low",
                "Review backup and retention policies for this table");

        return recommendations;
    }

    /**
     * Add a recommendation to the list
     */
    private void addRecommendation(List<Map<String, Object>> recommendations,
                                   String priority,
                                   String action) {
        Map<String, Object> recommendation = new HashMap<>();
        recommendation.put("priority", priority);
        recommendation.put("action", action);
        recommendations.add(recommendation);
    }

    /**
     * Format bytes to human-readable string
     */
    private String formatBytes(Long bytes) {
        if (bytes == null) return "N/A";

        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
