package com.dbaagent.service;

import com.dbaagent.model.GrowthAnomaly;
import com.dbaagent.model.PlaybookAlert;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * Webhook Service for Growth Monitoring Alerts
 * Sends alerts to configured webhook URLs (including Slack)
 */
@Service
@Slf4j
public class WebhookService {

    private final WebClient webClient;

    public WebhookService() {
        this.webClient = WebClient.builder()
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    /**
     * Send growth anomaly alert to webhook
     */
    public void sendGrowthAlert(GrowthAnomaly anomaly, String webhookUrl) {
        Map<String, Object> payload = buildWebhookPayload(anomaly);

        try {
            webClient.post()
                    .uri(webhookUrl)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(10))
                    .doOnSuccess(response -> log.info("Webhook alert sent successfully for table: {}", anomaly.getTableName()))
                    .doOnError(error -> log.error("Webhook alert failed for table: {}", anomaly.getTableName(), error))
                    .subscribe(); // Fire and forget

        } catch (Exception e) {
            log.error("Failed to send webhook alert for table: {}", anomaly.getTableName(), e);
            throw new RuntimeException("Webhook alert failed", e);
        }
    }

    /**
     * Build webhook payload (generic format compatible with most webhooks)
     */
    private Map<String, Object> buildWebhookPayload(GrowthAnomaly anomaly) {
        Map<String, Object> payload = new HashMap<>();

        // Check if URL is Slack-specific
        // Slack webhooks contain "hooks.slack.com" in the URL
        // For now, we'll use a generic format that works for most webhooks

        payload.put("event_type", "table_growth_anomaly");
        payload.put("severity", anomaly.getSeverity().toString());
        payload.put("anomaly_type", anomaly.getAnomalyType().getDisplayName());
        payload.put("table_name", anomaly.getTableName());
        payload.put("connection_id", anomaly.getConnectionId());

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        payload.put("detection_time", anomaly.getDetectionTimestamp().format(formatter));

        payload.put("description", anomaly.getDescription());

        // Add metrics
        Map<String, Object> metrics = new HashMap<>();
        if (anomaly.getCurrentSizeBytes() != null) {
            metrics.put("current_size_bytes", anomaly.getCurrentSizeBytes());
            metrics.put("current_size_formatted", formatBytes(anomaly.getCurrentSizeBytes()));
        }
        if (anomaly.getPreviousSizeBytes() != null) {
            metrics.put("previous_size_bytes", anomaly.getPreviousSizeBytes());
            metrics.put("previous_size_formatted", formatBytes(anomaly.getPreviousSizeBytes()));
        }
        if (anomaly.getSizeGrowthBytes() != null) {
            metrics.put("size_growth_bytes", anomaly.getSizeGrowthBytes());
            metrics.put("size_growth_formatted", formatBytes(anomaly.getSizeGrowthBytes()));
        }
        if (anomaly.getSizeGrowthPercent() != null) {
            metrics.put("size_growth_percent", String.format("%.1f%%", anomaly.getSizeGrowthPercent()));
        }
        if (anomaly.getCurrentRowCount() != null) {
            metrics.put("current_row_count", anomaly.getCurrentRowCount());
        }
        if (anomaly.getRowCountGrowth() != null) {
            metrics.put("row_count_growth", anomaly.getRowCountGrowth());
        }
        if (anomaly.getZScore() != null) {
            metrics.put("z_score", String.format("%.2f", anomaly.getZScore()));
        }

        payload.put("metrics", metrics);

        // Add Slack-compatible text field for fallback
        String severityEmoji = switch (anomaly.getSeverity()) {
            case CRITICAL -> "🚨";
            case WARNING -> "⚠️";
            case INFO -> "ℹ️";
        };

        payload.put("text", String.format("%s [%s] Table Growth Alert: %s - %s",
                severityEmoji,
                anomaly.getSeverity(),
                anomaly.getTableName(),
                anomaly.getDescription()));

        return payload;
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

    /**
     * Send a fired dashboard alert to a webhook (including Slack incoming-webhook URLs).
     */
    public void sendDashboardAlert(String webhookUrl, String dashboardName, String condition, String reason) {
        Map<String, Object> payload = buildDashboardAlertPayload(dashboardName, condition, reason);

        try {
            webClient.post()
                    .uri(webhookUrl)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(10))
                    .doOnSuccess(response -> log.info("Webhook dashboard alert sent successfully for: {}", dashboardName))
                    .doOnError(error -> log.error("Webhook dashboard alert failed for: {}", dashboardName, error))
                    .subscribe(); // Fire and forget
        } catch (Exception e) {
            log.error("Failed to send webhook dashboard alert for: {}", dashboardName, e);
            throw new RuntimeException("Webhook dashboard alert failed", e);
        }
    }

    private Map<String, Object> buildDashboardAlertPayload(String dashboardName, String condition, String reason) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("event_type", "dashboard_alert");
        payload.put("dashboard_name", dashboardName);
        payload.put("condition", condition);
        payload.put("reason", reason);
        payload.put("text", String.format("🔔 Dashboard alert: %s\nCondition: %s\n%s", dashboardName, condition, reason));
        payload.put("blocks", java.util.List.of(
            Map.of("type", "header", "text", Map.of("type", "plain_text", "text", "🔔 " + dashboardName, "emoji", true)),
            Map.of("type", "section", "text", Map.of("type", "mrkdwn",
                "text", "*Condition:* " + condition + "\n*Why it fired:* " + reason))
        ));
        return payload;
    }

    /**
     * Send slow query alert to webhook (including Slack)
     */
    public void sendSlowQueryAlert(PlaybookAlert alert, String webhookUrl) {
        Map<String, Object> payload = buildSlowQueryWebhookPayload(alert);

        try {
            webClient.post()
                    .uri(webhookUrl)
                    .bodyValue(payload)
                    .retrieve()
                    .bodyToMono(String.class)
                    .timeout(Duration.ofSeconds(10))
                    .doOnSuccess(response -> log.info("Webhook slow query alert sent successfully: {}", alert.getTitle()))
                    .doOnError(error -> log.error("Webhook slow query alert failed: {}", alert.getTitle(), error))
                    .subscribe(); // Fire and forget

        } catch (Exception e) {
            log.error("Failed to send webhook slow query alert: {}", alert.getTitle(), e);
            throw new RuntimeException("Webhook slow query alert failed", e);
        }
    }

    /**
     * Build webhook payload for slow query alert
     */
    private Map<String, Object> buildSlowQueryWebhookPayload(PlaybookAlert alert) {
        Map<String, Object> payload = new HashMap<>();

        payload.put("event_type", "slow_query_alert");
        payload.put("severity", alert.getSeverity().toString());
        payload.put("title", alert.getTitle());
        payload.put("connection_id", alert.getConnectionId());

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        if (alert.getCreatedAt() != null) {
            payload.put("created_at", alert.getCreatedAt().format(formatter));
        }

        payload.put("message", alert.getMessage());

        // Add findings
        if (alert.getFindings() != null) {
            payload.put("findings", alert.getFindings());
        }

        // Add recommendations
        if (alert.getRecommendations() != null) {
            payload.put("recommendations", alert.getRecommendations());
        }

        // Add Slack-compatible text field for fallback
        String severityEmoji = switch (alert.getSeverity()) {
            case CRITICAL -> "🚨";
            case WARNING -> "⚠️";
            case INFO -> "ℹ️";
        };

        payload.put("text", String.format("%s [%s] %s\n%s",
                severityEmoji,
                alert.getSeverity(),
                alert.getTitle(),
                alert.getMessage()));

        // For Slack Block Kit format (optional)
        if (alert.getMessage() != null && alert.getMessage().length() < 3000) {
            payload.put("blocks", buildSlackBlocks(alert, severityEmoji));
        }

        return payload;
    }

    /**
     * Build Slack Block Kit blocks for richer formatting
     */
    private Object buildSlackBlocks(PlaybookAlert alert, String severityEmoji) {
        return java.util.List.of(
            Map.of(
                "type", "header",
                "text", Map.of(
                    "type", "plain_text",
                    "text", severityEmoji + " " + alert.getTitle(),
                    "emoji", true
                )
            ),
            Map.of(
                "type", "section",
                "text", Map.of(
                    "type", "mrkdwn",
                    "text", "*Severity:* " + alert.getSeverity() + "\n*Connection:* " + alert.getConnectionId()
                )
            ),
            Map.of(
                "type", "section",
                "text", Map.of(
                    "type", "mrkdwn",
                    "text", "```" + truncate(alert.getMessage(), 2000) + "```"
                )
            ),
            Map.of(
                "type", "context",
                "elements", java.util.List.of(
                    Map.of(
                        "type", "mrkdwn",
                        "text", "Generated by DBA Agent Slow Query Monitoring"
                    )
                )
            )
        );
    }

    /**
     * Truncate string to max length
     */
    private String truncate(String str, int maxLength) {
        if (str == null) return "";
        if (str.length() <= maxLength) return str;
        return str.substring(0, maxLength - 3) + "...";
    }
}
