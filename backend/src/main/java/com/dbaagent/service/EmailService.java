package com.dbaagent.service;

import com.dbaagent.model.GrowthAnomaly;
import com.dbaagent.model.PlaybookAlert;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Email Service for auth, alerting, and admin notifications.
 * SMTP settings are resolved at send time from system settings with env fallbacks.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class EmailService {

    private final SystemConfigService systemConfigService;

    @Value("${EMAIL_HOST:}")
    private String defaultHost;

    @Value("${EMAIL_PORT:587}")
    private int defaultPort;

    @Value("${EMAIL_USERNAME:}")
    private String defaultUsername;

    @Value("${EMAIL_PASSWORD:}")
    private String defaultPassword;

    @Value("${EMAIL_FROM:noreply@dba-agent.com}")
    private String defaultFromEmail;

    @Value("${EMAIL_STARTTLS:true}")
    private boolean defaultStartTls;

    @Value("${EMAIL_SSL:false}")
    private boolean defaultSsl;

    public void sendLoginOtp(String recipient, String code, int ttlMinutes) throws MessagingException {
        JavaMailSenderImpl mailSender = buildMailSender();
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(resolveFromEmail());
        helper.setTo(recipient);
        helper.setSubject("Your DeepSQL sign-in code");
        helper.setText(buildOtpEmailHtml(code, ttlMinutes), true);
        mailSender.send(message);
        log.info("OTP email sent to {}", recipient);
    }

    public void sendInviteEmail(String recipient, String inviteUrl, String role, boolean bootstrap) throws MessagingException {
        JavaMailSenderImpl mailSender = buildMailSender();
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(resolveFromEmail());
        helper.setTo(recipient);
        helper.setSubject(bootstrap ? "Complete your DeepSQL admin setup" : "You're invited to DeepSQL");
        helper.setText(buildInviteEmailHtml(inviteUrl, role, bootstrap), true);
        mailSender.send(message);
        log.info("Invite email sent to {}", recipient);
    }

    public void sendGrowthAlert(GrowthAnomaly anomaly, List<String> recipients) throws MessagingException {
        JavaMailSenderImpl mailSender = buildMailSender();
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        helper.setFrom(resolveFromEmail());
        helper.setTo(recipients.toArray(new String[0]));
        helper.setSubject(buildSubject(anomaly));
        helper.setText(buildEmailHtml(anomaly), true);

        mailSender.send(message);
        log.info("Email alert sent to {} recipients for table: {}", recipients.size(), anomaly.getTableName());
    }

    public void sendSlowQueryAlert(PlaybookAlert alert, List<String> recipients) throws MessagingException {
        JavaMailSenderImpl mailSender = buildMailSender();
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

        helper.setFrom(resolveFromEmail());
        helper.setTo(recipients.toArray(new String[0]));
        helper.setSubject(buildSlowQuerySubject(alert));
        helper.setText(buildSlowQueryEmailHtml(alert), true);

        mailSender.send(message);
        log.info("Slow query email alert sent to {} recipients: {}", recipients.size(), alert.getTitle());
    }

    public boolean isConfigured() {
        MailConfig config = resolveMailConfig();
        return isNonBlank(config.host())
            && config.port() > 0
            && isNonBlank(config.username())
            && isNonBlank(config.password())
            && isNonBlank(config.fromEmail());
    }

    public Map<String, Object> currentSettingsSummary() {
        MailConfig config = resolveMailConfig();
        Map<String, Object> summary = new LinkedHashMap<>();
        summary.put("host", config.host());
        summary.put("port", config.port());
        summary.put("username", config.username());
        summary.put("fromEmail", config.fromEmail());
        summary.put("startTls", config.startTls());
        summary.put("ssl", config.ssl());
        summary.put("configured", isConfigured());
        summary.put("passwordConfigured", isNonBlank(config.password()));
        return summary;
    }

    public void sendTestEmail(String recipient) throws MessagingException {
        JavaMailSenderImpl mailSender = buildMailSender();
        MimeMessage message = mailSender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom(resolveFromEmail());
        helper.setTo(recipient);
        helper.setSubject("DeepSQL SMTP test email");
        helper.setText("""
            <!DOCTYPE html>
            <html>
            <body style="font-family: Arial, sans-serif; color: #111827; background: #f9fafb; padding: 24px;">
              <div style="max-width: 520px; margin: 0 auto; background: white; border: 1px solid #e5e7eb; border-radius: 16px; padding: 32px;">
                <h1 style="margin: 0 0 12px; font-size: 24px;">DeepSQL email test</h1>
                <p style="margin: 0; color: #4b5563;">Your workspace SMTP configuration is working.</p>
              </div>
            </body>
            </html>
            """, true);
        mailSender.send(message);
        log.info("SMTP test email sent to {}", recipient);
    }

    private JavaMailSenderImpl buildMailSender() {
        MailConfig config = resolveMailConfig();
        if (!isNonBlank(config.host())) {
            throw new IllegalStateException("SMTP host is not configured");
        }
        if (!isNonBlank(config.username())) {
            throw new IllegalStateException("SMTP username is not configured");
        }
        if (!isNonBlank(config.password())) {
            throw new IllegalStateException("SMTP password is not configured");
        }

        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost(config.host());
        sender.setPort(config.port());
        sender.setUsername(config.username());
        sender.setPassword(config.password());
        sender.setDefaultEncoding("UTF-8");

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "true");
        props.put("mail.smtp.starttls.enable", Boolean.toString(config.startTls()));
        props.put("mail.smtp.ssl.enable", Boolean.toString(config.ssl()));
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");
        props.put("mail.smtp.writetimeout", "10000");
        return sender;
    }

    private MailConfig resolveMailConfig() {
        String host = systemConfigService.getOrEnv("smtp.host", defaultHost);
        int port = parsePort(systemConfigService.getOrEnv("smtp.port", Integer.toString(defaultPort)));
        String username = systemConfigService.getOrEnv("smtp.username", defaultUsername);
        String password = systemConfigService.getOrEnv("smtp.password", defaultPassword);
        String fromEmail = systemConfigService.getOrEnv("smtp.from", defaultFromEmail);
        boolean startTls = parseBoolean(systemConfigService.getOrEnv("smtp.starttls", Boolean.toString(defaultStartTls)), defaultStartTls);
        boolean ssl = parseBoolean(systemConfigService.getOrEnv("smtp.ssl", Boolean.toString(defaultSsl)), defaultSsl);
        return new MailConfig(host, port, username, password, fromEmail, startTls, ssl);
    }

    private String resolveFromEmail() {
        return resolveMailConfig().fromEmail();
    }

    private int parsePort(String value) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return defaultPort;
        }
    }

    private boolean parseBoolean(String value, boolean fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return Boolean.parseBoolean(value);
    }

    private boolean isNonBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String buildSubject(GrowthAnomaly anomaly) {
        String severityIcon = switch (anomaly.getSeverity()) {
            case CRITICAL -> "🚨";
            case WARNING -> "⚠️";
            case INFO -> "ℹ️";
        };

        return String.format("%s [%s] Table Growth Alert: %s",
            severityIcon,
            anomaly.getSeverity(),
            anomaly.getTableName());
    }

    private String buildOtpEmailHtml(String code, int ttlMinutes) {
        return """
            <!DOCTYPE html>
            <html>
            <body style="font-family: Arial, sans-serif; color: #111827; background: #f9fafb; padding: 24px;">
              <div style="max-width: 520px; margin: 0 auto; background: white; border: 1px solid #e5e7eb; border-radius: 16px; padding: 32px;">
                <h1 style="margin: 0 0 12px; font-size: 24px;">DeepSQL sign-in code</h1>
                <p style="margin: 0 0 24px; color: #4b5563;">Use this one-time code to sign in to DeepSQL. It expires in %d minutes.</p>
                <div style="font-size: 32px; letter-spacing: 8px; font-weight: 700; padding: 18px 24px; background: #111827; color: white; border-radius: 12px; display: inline-block;">%s</div>
                <p style="margin-top: 24px; color: #6b7280; font-size: 14px;">If you didn’t request this, you can ignore this email.</p>
              </div>
            </body>
            </html>
            """.formatted(ttlMinutes, code);
    }

    private String buildInviteEmailHtml(String inviteUrl, String role, boolean bootstrap) {
        String heading = bootstrap ? "Finish setting up DeepSQL" : "You've been invited to DeepSQL";
        String copy = bootstrap
            ? "Use the secure one-time link below to activate your first admin account."
            : "Use the secure one-time link below to activate your account.";
        return """
            <!DOCTYPE html>
            <html>
            <body style="font-family: Arial, sans-serif; color: #111827; background: #f9fafb; padding: 24px;">
              <div style="max-width: 560px; margin: 0 auto; background: white; border: 1px solid #e5e7eb; border-radius: 16px; padding: 32px;">
                <h1 style="margin: 0 0 12px; font-size: 24px;">%s</h1>
                <p style="margin: 0 0 18px; color: #4b5563;">%s</p>
                <p style="margin: 0 0 18px; color: #4b5563;">Role: <strong>%s</strong></p>
                <a href="%s" style="display: inline-block; padding: 12px 20px; background: #111827; color: white; text-decoration: none; border-radius: 999px; font-weight: 600;">Open secure activation link</a>
                <p style="margin-top: 20px; color: #6b7280; font-size: 14px; word-break: break-all;">If the button doesn't work, copy this link into your browser:<br>%s</p>
              </div>
            </body>
            </html>
            """.formatted(heading, copy, role, inviteUrl, inviteUrl);
    }

    private String buildEmailHtml(GrowthAnomaly anomaly) {
        String severityColor = anomaly.getSeverityColor();
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>");
        html.append("<html>");
        html.append("<head><meta charset=\"UTF-8\"></head>");
        html.append("<body style=\"font-family: Arial, sans-serif; line-height: 1.6; color: #333;\">");
        html.append("<div style=\"background-color: ").append(severityColor)
            .append("; color: white; padding: 20px; border-radius: 8px 8px 0 0;\">");
        html.append("<h1 style=\"margin: 0;\">Table Growth Alert</h1>");
        html.append("<p style=\"margin: 5px 0 0 0; opacity: 0.9;\">")
            .append(anomaly.getAnomalyType().getDisplayName())
            .append(" - ").append(anomaly.getSeverity()).append("</p>");
        html.append("</div>");
        html.append("<div style=\"padding: 20px; background-color: #f9fafb; border: 1px solid #e5e7eb; border-top: none; border-radius: 0 0 8px 8px;\">");
        html.append("<div style=\"background-color: white; padding: 15px; border-radius: 6px; margin-bottom: 20px;\">");
        html.append("<h2 style=\"margin-top: 0; color: #1f2937;\">Summary</h2>");
        html.append("<p style=\"font-size: 16px;\">").append(anomaly.getDescription()).append("</p>");
        html.append("</div>");
        html.append("<div style=\"background-color: white; padding: 15px; border-radius: 6px; margin-bottom: 20px;\">");
        html.append("<h2 style=\"margin-top: 0; color: #1f2937;\">Details</h2>");
        html.append("<table style=\"width: 100%; border-collapse: collapse;\">");

        addDetailRow(html, "Table Name", anomaly.getTableName());
        addDetailRow(html, "Detection Time", anomaly.getDetectionTimestamp().format(formatter));

        if (anomaly.getCurrentSizeBytes() != null) {
            addDetailRow(html, "Current Size", formatBytes(anomaly.getCurrentSizeBytes()));
        }
        if (anomaly.getPreviousSizeBytes() != null) {
            addDetailRow(html, "Previous Size", formatBytes(anomaly.getPreviousSizeBytes()));
        }
        if (anomaly.getSizeGrowthBytes() != null) {
            addDetailRow(html, "Size Growth", formatBytes(anomaly.getSizeGrowthBytes()));
        }
        if (anomaly.getSizeGrowthPercent() != null) {
            addDetailRow(html, "Growth Percentage", String.format("%.1f%%", anomaly.getSizeGrowthPercent()));
        }
        if (anomaly.getCurrentRowCount() != null) {
            addDetailRow(html, "Current Row Count", String.format("%,d", anomaly.getCurrentRowCount()));
        }
        if (anomaly.getRowCountGrowth() != null) {
            addDetailRow(html, "Row Count Growth", String.format("%,d", anomaly.getRowCountGrowth()));
        }
        if (anomaly.getZScore() != null) {
            addDetailRow(html, "Statistical Z-Score", String.format("%.2f", anomaly.getZScore()));
        }

        html.append("</table>");
        html.append("</div>");
        html.append("<div style=\"background-color: #fef3c7; padding: 15px; border-radius: 6px; border-left: 4px solid #f59e0b;\">");
        html.append("<h3 style=\"margin-top: 0; color: #92400e;\">Recommended Actions</h3>");
        html.append("<ul style=\"margin: 10px 0;\">");
        html.append("<li>Investigate recent application changes or data ingestion processes</li>");
        html.append("<li>Review slow query logs for bulk INSERT/UPDATE operations</li>");
        html.append("<li>Monitor disk space to ensure sufficient capacity</li>");
        html.append("<li>Consider implementing data archival if growth continues</li>");
        html.append("</ul>");
        html.append("</div>");
        html.append("</div>");
        html.append("<div style=\"text-align: center; padding: 20px; color: #6b7280; font-size: 12px;\">");
        html.append("<p>This alert was generated by DBA Agent Growth Monitoring System</p>");
        html.append("<p>To configure alert settings, please visit the DBA Agent dashboard</p>");
        html.append("</div>");
        html.append("</body>");
        html.append("</html>");
        return html.toString();
    }

    private void addDetailRow(StringBuilder html, String label, String value) {
        html.append("<tr>");
        html.append("<td style=\"padding: 8px; border-bottom: 1px solid #e5e7eb; font-weight: 600; color: #4b5563;\">")
            .append(label).append(":</td>");
        html.append("<td style=\"padding: 8px; border-bottom: 1px solid #e5e7eb; color: #1f2937;\">")
            .append(value).append("</td>");
        html.append("</tr>");
    }

    private String formatBytes(Long bytes) {
        if (bytes == null) return "N/A";
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.2f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.2f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private String buildSlowQuerySubject(PlaybookAlert alert) {
        String severityIcon = switch (alert.getSeverity()) {
            case CRITICAL -> "🚨";
            case WARNING -> "⚠️";
            case INFO -> "ℹ️";
        };

        return String.format("%s [%s] Slow Query Alert: %s",
            severityIcon,
            alert.getSeverity(),
            alert.getTitle());
    }

    private String buildSlowQueryEmailHtml(PlaybookAlert alert) {
        String severityColor = switch (alert.getSeverity()) {
            case CRITICAL -> "#ef4444";
            case WARNING -> "#f59e0b";
            case INFO -> "#3b82f6";
        };
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html>");
        html.append("<html>");
        html.append("<head><meta charset=\"UTF-8\"></head>");
        html.append("<body style=\"font-family: Arial, sans-serif; line-height: 1.6; color: #333;\">");
        html.append("<div style=\"background-color: ").append(severityColor)
            .append("; color: white; padding: 20px; border-radius: 8px 8px 0 0;\">");
        html.append("<h1 style=\"margin: 0;\">Slow Query Alert</h1>");
        html.append("<p style=\"margin: 5px 0 0 0; opacity: 0.9;\">")
            .append(alert.getSeverity()).append(" - ").append(alert.getTitle()).append("</p>");
        html.append("</div>");
        html.append("<div style=\"padding: 20px; background-color: #f9fafb; border: 1px solid #e5e7eb; border-top: none; border-radius: 0 0 8px 8px;\">");
        html.append("<div style=\"background-color: white; padding: 15px; border-radius: 6px; margin-bottom: 20px;\">");
        html.append("<h2 style=\"margin-top: 0; color: #1f2937;\">Summary</h2>");
        html.append("<pre style=\"font-size: 14px; white-space: pre-wrap; word-wrap: break-word; background: #f3f4f6; padding: 12px; border-radius: 4px;\">")
            .append(alert.getMessage()).append("</pre>");
        html.append("</div>");

        if (alert.getFindings() != null && !alert.getFindings().isEmpty()) {
            html.append("<div style=\"background-color: white; padding: 15px; border-radius: 6px; margin-bottom: 20px;\">");
            html.append("<h2 style=\"margin-top: 0; color: #1f2937;\">Details</h2>");
            html.append("<table style=\"width: 100%; border-collapse: collapse;\">");
            for (Map.Entry<String, Object> entry : alert.getFindings().entrySet()) {
                addDetailRow(html, formatKey(entry.getKey()), String.valueOf(entry.getValue()));
            }
            html.append("</table>");
            html.append("</div>");
        }

        if (alert.getRecommendations() != null && !alert.getRecommendations().isEmpty()) {
            html.append("<div style=\"background-color: #fef3c7; padding: 15px; border-radius: 6px; border-left: 4px solid #f59e0b;\">");
            html.append("<h3 style=\"margin-top: 0; color: #92400e;\">Recommended Actions</h3>");
            html.append("<ul style=\"margin: 10px 0;\">");
            for (Map<String, Object> rec : alert.getRecommendations()) {
                String action = rec.get("action") != null ? rec.get("action").toString() : "";
                String desc = rec.get("description") != null ? rec.get("description").toString() : "";
                html.append("<li><strong>").append(action).append("</strong>: ").append(desc).append("</li>");
            }
            html.append("</ul>");
            html.append("</div>");
        }

        html.append("</div>");
        html.append("<div style=\"text-align: center; padding: 20px; color: #6b7280; font-size: 12px;\">");
        html.append("<p>This alert was generated by DBA Agent Slow Query Monitoring</p>");
        if (alert.getCreatedAt() != null) {
            html.append("<p>Generated at: ").append(alert.getCreatedAt().format(formatter)).append("</p>");
        }
        html.append("</div>");
        html.append("</body>");
        html.append("</html>");
        return html.toString();
    }

    private String formatKey(String key) {
        if (key == null || key.isEmpty()) return key;
        StringBuilder result = new StringBuilder();
        result.append(Character.toUpperCase(key.charAt(0)));
        for (int i = 1; i < key.length(); i++) {
            char c = key.charAt(i);
            if (Character.isUpperCase(c)) {
                result.append(' ');
            }
            result.append(c);
        }
        return result.toString();
    }

    private record MailConfig(
        String host,
        int port,
        String username,
        String password,
        String fromEmail,
        boolean startTls,
        boolean ssl
    ) {
    }
}
