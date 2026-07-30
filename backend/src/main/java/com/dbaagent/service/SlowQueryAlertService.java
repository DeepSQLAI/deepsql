package com.dbaagent.service;

import com.dbaagent.model.PlaybookAlert;
import com.dbaagent.model.SlowQuery;
import com.dbaagent.model.SlowQueryAnalysis;
import com.dbaagent.repository.PlaybookAlertRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;

/**
 * Service for managing slow query alerts.
 * Integrates with the PlaybookAlert system for notifications.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlowQueryAlertService {

    private final PlaybookAlertRepository alertRepository;
    private final EmailService emailService;
    private final WebhookService webhookService;

    // Alert thresholds
    private static final double CRITICAL_AVG_TIME_MS = 5000;  // 5 seconds
    private static final double HIGH_AVG_TIME_MS = 1000;       // 1 second
    private static final int CRITICAL_QUERY_COUNT = 10;        // Critical queries threshold
    private static final double REGRESSION_THRESHOLD = 2.0;    // 2x slower triggers alert

    @Data
    @Builder
    public static class SlowQueryAlertSummary {
        private int totalAlerts;
        private int criticalAlerts;
        private int warningAlerts;
        private int infoAlerts;
        private int unacknowledgedCount;
        private List<PlaybookAlert> recentAlerts;
    }

    /**
     * Process analysis with default configuration (for auto-processing after ingestion)
     */
    public List<PlaybookAlert> processAnalysisForAlerts(String connectionId, SlowQueryAnalysis analysis) {
        // Use default enabled configuration
        AlertConfiguration defaultConfig = AlertConfiguration.builder()
            .enabled(true)
            .criticalThresholdMs(CRITICAL_AVG_TIME_MS)
            .highThresholdMs(HIGH_AVG_TIME_MS)
            .criticalQueryCountThreshold(CRITICAL_QUERY_COUNT)
            .regressionThreshold(REGRESSION_THRESHOLD)
            .notificationChannels(List.of("BROWSER"))  // Default to browser only
            .build();
        return processAnalysisForAlerts(connectionId, analysis, defaultConfig);
    }

    /**
     * Check analysis results and create alerts for critical queries
     */
    public List<PlaybookAlert> processAnalysisForAlerts(
        String connectionId,
        SlowQueryAnalysis analysis,
        AlertConfiguration config
    ) {
        if (config == null || !config.isEnabled()) {
            log.debug("Slow query alerts disabled for connection: {}", connectionId);
            return Collections.emptyList();
        }

        List<PlaybookAlert> alerts = new ArrayList<>();
        String runId = "slow-query-" + UUID.randomUUID().toString().substring(0, 8);

        // Check for critical slow queries
        if (analysis.getTopSlowQueries() != null) {
            int criticalCount = 0;
            int highCount = 0;

            for (SlowQuery query : analysis.getTopSlowQueries()) {
                Double avgTime = query.getAvgExecutionTimeMs();
                if (avgTime == null) continue;

                double criticalThreshold = config.getCriticalThresholdMs() > 0 ?
                    config.getCriticalThresholdMs() : CRITICAL_AVG_TIME_MS;
                double highThreshold = config.getHighThresholdMs() > 0 ?
                    config.getHighThresholdMs() : HIGH_AVG_TIME_MS;

                if (avgTime >= criticalThreshold) {
                    criticalCount++;
                    // Create individual alert for critical queries
                    PlaybookAlert alert = createQueryAlert(connectionId, runId, query, PlaybookAlert.Severity.CRITICAL);
                    alerts.add(alertRepository.save(alert));
                } else if (avgTime >= highThreshold) {
                    highCount++;
                }
            }

            // Create summary alert if threshold exceeded
            int criticalQueryThreshold = config.getCriticalQueryCountThreshold() > 0 ?
                config.getCriticalQueryCountThreshold() : CRITICAL_QUERY_COUNT;

            if (criticalCount >= criticalQueryThreshold) {
                PlaybookAlert summaryAlert = createSummaryAlert(
                    connectionId, runId, analysis, criticalCount, highCount);
                alerts.add(alertRepository.save(summaryAlert));
            }
        }

        // Check overall health
        if ("CRITICAL".equals(analysis.getOverallHealth())) {
            PlaybookAlert healthAlert = PlaybookAlert.builder()
                .playbookRunId(runId)
                .connectionId(connectionId)
                .severity(PlaybookAlert.Severity.CRITICAL)
                .title("Database Health: CRITICAL")
                .message(String.format(
                    "Slow query analysis detected CRITICAL health status. " +
                    "Total slow queries: %d, Total database time: %.2f seconds",
                    analysis.getTotalSlowQueries(),
                    analysis.getTotalDatabaseTimeMs() != null ? analysis.getTotalDatabaseTimeMs() / 1000.0 : 0
                ))
                .findings(Map.of(
                    "health", analysis.getOverallHealth(),
                    "totalSlowQueries", analysis.getTotalSlowQueries(),
                    "threshold", analysis.getSlowQueryThresholdMs()
                ))
                .channelsSent(config.getNotificationChannels())
                .build();
            alerts.add(alertRepository.save(healthAlert));
        }

        log.info("Created {} slow query alerts for connection: {}", alerts.size(), connectionId);
        return alerts;
    }

    /**
     * Create alert for performance regression
     */
    public PlaybookAlert createRegressionAlert(
        String connectionId,
        String queryId,
        String queryText,
        double previousAvgMs,
        double currentAvgMs,
        double regressionFactor
    ) {
        String runId = "regression-" + UUID.randomUUID().toString().substring(0, 8);

        PlaybookAlert.Severity severity = regressionFactor >= 5.0 ?
            PlaybookAlert.Severity.CRITICAL :
            regressionFactor >= 2.0 ? PlaybookAlert.Severity.WARNING : PlaybookAlert.Severity.INFO;

        PlaybookAlert alert = PlaybookAlert.builder()
            .playbookRunId(runId)
            .connectionId(connectionId)
            .severity(severity)
            .title(String.format("Query Performance Regression: %.1fx slower", regressionFactor))
            .message(String.format(
                "Query %s has regressed from %.2fms to %.2fms average execution time (%.1fx slower).\n\n" +
                "Query: %s",
                queryId != null ? queryId.substring(0, Math.min(8, queryId.length())) : "unknown",
                previousAvgMs,
                currentAvgMs,
                regressionFactor,
                truncateQuery(queryText, 200)
            ))
            .findings(Map.of(
                "queryId", queryId != null ? queryId : "",
                "previousAvgMs", previousAvgMs,
                "currentAvgMs", currentAvgMs,
                "regressionFactor", regressionFactor,
                "queryText", truncateQuery(queryText, 500)
            ))
            .recommendations(List.of(
                Map.of(
                    "action", "Review recent changes",
                    "description", "Check for recent schema changes, data growth, or code changes that might have affected this query"
                ),
                Map.of(
                    "action", "Run EXPLAIN ANALYZE",
                    "description", "Analyze the query execution plan to identify bottlenecks"
                ),
                Map.of(
                    "action", "Check index usage",
                    "description", "Verify that appropriate indexes exist and are being used"
                )
            ))
            .build();

        return alertRepository.save(alert);
    }

    /**
     * Get alert summary for a connection
     */
    public SlowQueryAlertSummary getAlertSummary(String connectionId) {
        List<PlaybookAlert> allAlerts = alertRepository.findByConnectionId(connectionId);
        List<PlaybookAlert> unacknowledged = alertRepository.findByConnectionIdAndAcknowledgedFalse(connectionId);

        // Filter to slow query related alerts
        List<PlaybookAlert> slowQueryAlerts = allAlerts.stream()
            .filter(a -> a.getPlaybookRunId() != null &&
                (a.getPlaybookRunId().startsWith("slow-query-") ||
                 a.getPlaybookRunId().startsWith("regression-")))
            .toList();

        int critical = (int) slowQueryAlerts.stream()
            .filter(a -> a.getSeverity() == PlaybookAlert.Severity.CRITICAL)
            .count();
        int warning = (int) slowQueryAlerts.stream()
            .filter(a -> a.getSeverity() == PlaybookAlert.Severity.WARNING)
            .count();
        int info = (int) slowQueryAlerts.stream()
            .filter(a -> a.getSeverity() == PlaybookAlert.Severity.INFO)
            .count();

        // Get recent alerts (last 24 hours)
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        List<PlaybookAlert> recentAlerts = slowQueryAlerts.stream()
            .filter(a -> a.getCreatedAt() != null && a.getCreatedAt().isAfter(since))
            .sorted(Comparator.comparing(PlaybookAlert::getCreatedAt).reversed())
            .limit(10)
            .toList();

        return SlowQueryAlertSummary.builder()
            .totalAlerts(slowQueryAlerts.size())
            .criticalAlerts(critical)
            .warningAlerts(warning)
            .infoAlerts(info)
            .unacknowledgedCount((int) unacknowledged.stream()
                .filter(a -> a.getPlaybookRunId() != null &&
                    (a.getPlaybookRunId().startsWith("slow-query-") ||
                     a.getPlaybookRunId().startsWith("regression-")))
                .count())
            .recentAlerts(recentAlerts)
            .build();
    }

    /**
     * Acknowledge an alert
     */
    public PlaybookAlert acknowledgeAlert(String alertId, String userId) {
        PlaybookAlert alert = alertRepository.findById(alertId)
            .orElseThrow(() -> new IllegalArgumentException("Alert not found: " + alertId));

        alert.acknowledge(userId);
        return alertRepository.save(alert);
    }

    /**
     * Acknowledge all alerts for a connection
     */
    public int acknowledgeAllAlerts(String connectionId, String userId) {
        List<PlaybookAlert> unacknowledged = alertRepository.findByConnectionIdAndAcknowledgedFalse(connectionId);
        int count = 0;
        for (PlaybookAlert alert : unacknowledged) {
            if (alert.getPlaybookRunId() != null &&
                (alert.getPlaybookRunId().startsWith("slow-query-") ||
                 alert.getPlaybookRunId().startsWith("regression-"))) {
                alert.acknowledge(userId);
                alertRepository.save(alert);
                count++;
            }
        }
        return count;
    }

    private PlaybookAlert createQueryAlert(
        String connectionId,
        String runId,
        SlowQuery query,
        PlaybookAlert.Severity severity
    ) {
        return PlaybookAlert.builder()
            .playbookRunId(runId)
            .connectionId(connectionId)
            .severity(severity)
            .title(String.format("Critical Slow Query: %.2fms avg", query.getAvgExecutionTimeMs()))
            .message(String.format(
                "Query with average execution time of %.2fms detected.\n\n" +
                "Query ID: %s\n" +
                "Call Count: %,d\n" +
                "Total Time: %.2fs\n" +
                "Rows Examined: %,d\n\n" +
                "Query: %s",
                query.getAvgExecutionTimeMs(),
                query.getQueryId() != null ? query.getQueryId() : "unknown",
                query.getCallCount() != null ? query.getCallCount() : 0,
                query.getTotalExecutionTimeMs() != null ? query.getTotalExecutionTimeMs() / 1000.0 : 0,
                query.getRowsExamined() != null ? query.getRowsExamined() : 0,
                truncateQuery(query.getQueryText(), 200)
            ))
            .findings(Map.of(
                "queryId", query.getQueryId() != null ? query.getQueryId() : "",
                "avgExecutionTimeMs", query.getAvgExecutionTimeMs(),
                "callCount", query.getCallCount() != null ? query.getCallCount() : 0,
                "rowsExamined", query.getRowsExamined() != null ? query.getRowsExamined() : 0
            ))
            .recommendations(buildQueryRecommendations(query))
            .build();
    }

    private PlaybookAlert createSummaryAlert(
        String connectionId,
        String runId,
        SlowQueryAnalysis analysis,
        int criticalCount,
        int highCount
    ) {
        return PlaybookAlert.builder()
            .playbookRunId(runId)
            .connectionId(connectionId)
            .severity(PlaybookAlert.Severity.CRITICAL)
            .title(String.format("Slow Query Alert: %d critical queries detected", criticalCount))
            .message(String.format(
                "Multiple slow queries detected:\n\n" +
                "- Critical queries (>5s): %d\n" +
                "- High severity queries (>1s): %d\n" +
                "- Total slow queries: %d\n" +
                "- Total database time: %.2fs\n\n" +
                "Review the slow query analysis for details.",
                criticalCount,
                highCount,
                analysis.getTotalSlowQueries(),
                analysis.getTotalDatabaseTimeMs() != null ? analysis.getTotalDatabaseTimeMs() / 1000.0 : 0
            ))
            .findings(Map.of(
                "criticalCount", criticalCount,
                "highCount", highCount,
                "totalSlowQueries", analysis.getTotalSlowQueries(),
                "health", analysis.getOverallHealth()
            ))
            .build();
    }

    private List<Map<String, Object>> buildQueryRecommendations(SlowQuery query) {
        List<Map<String, Object>> recommendations = new ArrayList<>();

        if (query.hasPoorEfficiency()) {
            recommendations.add(Map.of(
                "action", "Add Index",
                "description", "Query has poor efficiency (rows examined >> rows returned). Consider adding an index."
            ));
        }

        if (query.getSuggestions() != null) {
            for (String suggestion : query.getSuggestions()) {
                recommendations.add(Map.of(
                    "action", "Optimization",
                    "description", suggestion
                ));
            }
        }

        if (recommendations.isEmpty()) {
            recommendations.add(Map.of(
                "action", "Analyze Query",
                "description", "Run EXPLAIN to identify performance bottlenecks"
            ));
        }

        return recommendations;
    }

    private String truncateQuery(String query, int maxLength) {
        if (query == null) return "";
        if (query.length() <= maxLength) return query;
        return query.substring(0, maxLength) + "...";
    }

    /**
     * Send notifications for an alert through configured channels
     */
    public void sendNotifications(PlaybookAlert alert, AlertConfiguration config) {
        if (config == null || config.getNotificationChannels() == null) {
            return;
        }

        List<String> sentChannels = new ArrayList<>();
        List<String> channels = config.getNotificationChannels();

        // Send email if configured
        if (channels.contains("EMAIL") && config.getEmailRecipients() != null && !config.getEmailRecipients().isEmpty()) {
            try {
                emailService.sendSlowQueryAlert(alert, config.getEmailRecipients());
                sentChannels.add("EMAIL");
                log.info("Email notification sent for slow query alert: {}", alert.getTitle());
            } catch (Exception e) {
                log.error("Failed to send email notification for alert: {}", alert.getTitle(), e);
            }
        }

        // Send to Slack/webhook if configured
        if ((channels.contains("SLACK") || channels.contains("WEBHOOK")) &&
            config.getWebhookUrl() != null && !config.getWebhookUrl().isEmpty()) {
            try {
                webhookService.sendSlowQueryAlert(alert, config.getWebhookUrl());
                sentChannels.add(channels.contains("SLACK") ? "SLACK" : "WEBHOOK");
                log.info("Webhook notification sent for slow query alert: {}", alert.getTitle());
            } catch (Exception e) {
                log.error("Failed to send webhook notification for alert: {}", alert.getTitle(), e);
            }
        }

        // Update alert with channels sent
        if (!sentChannels.isEmpty()) {
            alert.setChannelsSent(sentChannels);
            alertRepository.save(alert);
        }
    }

    /**
     * Extended alert configuration with notification settings
     */
    @Data
    @Builder
    public static class AlertConfiguration {
        private boolean enabled;
        private double criticalThresholdMs;
        private double highThresholdMs;
        private int criticalQueryCountThreshold;
        private double regressionThreshold;
        private List<String> notificationChannels;  // BROWSER, EMAIL, SLACK, WEBHOOK
        private List<String> emailRecipients;
        private String webhookUrl;
    }
}
