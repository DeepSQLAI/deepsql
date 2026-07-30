package com.dbaagent.service;

import com.dbaagent.model.SecurityEventOutcome;
import com.dbaagent.model.SecurityEventType;
import com.dbaagent.model.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class AdminSettingsService {

    private final SystemConfigService systemConfigService;
    private final EmailService emailService;
    private final SecurityEventService securityEventService;
    private final SlackBotService slackBotService;

    public Map<String, Object> getEmailSettings() {
        return new LinkedHashMap<>(emailService.currentSettingsSummary());
    }

    @Transactional
    public Map<String, Object> updateEmailSettings(EmailSettingsRequest request, User actor, String requestId) {
        setConfig("smtp.host", request.host(), false, "SMTP host");
        setConfig("smtp.port", request.port() != null ? Integer.toString(request.port()) : null, false, "SMTP port");
        setConfig("smtp.username", request.username(), false, "SMTP username");
        if (request.password() != null && !request.password().isBlank()) {
            setConfig("smtp.password", request.password(), true, "SMTP password");
        }
        setConfig("smtp.from", request.fromEmail(), false, "SMTP from address");
        if (request.startTls() != null) {
            setConfig("smtp.starttls", Boolean.toString(request.startTls()), false, "SMTP STARTTLS");
        }
        if (request.ssl() != null) {
            setConfig("smtp.ssl", Boolean.toString(request.ssl()), false, "SMTP SSL");
        }

        securityEventService.log(SecurityEventService.EventRequest.builder()
            .eventType(SecurityEventType.SMTP_CONFIG_UPDATED)
            .outcome(SecurityEventOutcome.SUCCESS)
            .actorUserId(actor.getId())
            .userId(actor.getId())
            .email(actor.getEmail())
            .requestId(requestId)
            .targetResource("settings:smtp")
            .build());
        return getEmailSettings();
    }

    public void testEmailSettings(String recipient, User actor, String requestId) {
        try {
            emailService.sendTestEmail(recipient);
            securityEventService.log(SecurityEventService.EventRequest.builder()
                .eventType(SecurityEventType.SMTP_TEST_SUCCEEDED)
                .outcome(SecurityEventOutcome.SUCCESS)
                .actorUserId(actor.getId())
                .userId(actor.getId())
                .email(actor.getEmail())
                .requestId(requestId)
                .targetResource("settings:smtp")
                .metadata(Map.of("recipient", recipient))
                .build());
        } catch (Exception e) {
            securityEventService.log(SecurityEventService.EventRequest.builder()
                .eventType(SecurityEventType.SMTP_TEST_FAILED)
                .outcome(SecurityEventOutcome.FAILURE)
                .actorUserId(actor.getId())
                .userId(actor.getId())
                .email(actor.getEmail())
                .requestId(requestId)
                .targetResource("settings:smtp")
                .reason(e.getMessage())
                .metadata(Map.of("recipient", recipient))
                .build());
            throw new IllegalArgumentException(e.getMessage() != null ? e.getMessage() : "Could not send test email");
        }
    }

    public Map<String, Object> getSecuritySettings() {
        boolean enabled = systemConfigService.getBoolean("security.workspace.email2fa.enabled");
        boolean smtpConfigured = emailService.isConfigured();
        return Map.of(
            "workspaceEmail2faEnabled", enabled,
            "smtpConfigured", smtpConfigured,
            "canEnableWorkspaceEmail2fa", smtpConfigured
        );
    }

    @Transactional
    public Map<String, Object> updateSecuritySettings(SecuritySettingsRequest request, User actor, String requestId) {
        boolean enableEmail2fa = Boolean.TRUE.equals(request.workspaceEmail2faEnabled());
        if (enableEmail2fa && !emailService.isConfigured()) {
            throw new IllegalArgumentException("Configure SMTP before enabling workspace email 2FA.");
        }
        systemConfigService.set(
            "security.workspace.email2fa.enabled",
            Boolean.toString(enableEmail2fa),
            false,
            "Workspace-wide email 2FA toggle"
        );
        securityEventService.log(SecurityEventService.EventRequest.builder()
            .eventType(enableEmail2fa
                ? SecurityEventType.WORKSPACE_EMAIL_2FA_ENABLED
                : SecurityEventType.WORKSPACE_EMAIL_2FA_DISABLED)
            .outcome(SecurityEventOutcome.SUCCESS)
            .actorUserId(actor.getId())
            .userId(actor.getId())
            .email(actor.getEmail())
            .requestId(requestId)
            .targetResource("settings:security")
            .build());
        return getSecuritySettings();
    }

    public Map<String, Object> getSlackSettings() {
        return Map.of(
            "enabled", systemConfigService.getBoolean("slack.enabled"),
            "socketModeEnabled", systemConfigService.getBoolean("slack.socketModeEnabled"),
            "deepsqlBotUsername", systemConfigService.getOrDefault("slack.deepsqlBotUsername", ""),
            "appTokenConfigured", !systemConfigService.getOrDefault("slack.appToken", "").isBlank(),
            "botTokenConfigured", !systemConfigService.getOrDefault("slack.botToken", "").isBlank(),
            "signingSecretConfigured", !systemConfigService.getOrDefault("slack.signingSecret", "").isBlank()
        );
    }

    @Transactional
    public Map<String, Object> updateSlackSettings(SlackSettingsRequest request, User actor, String requestId) {
        if (request.enabled() != null) {
            systemConfigService.set("slack.enabled", Boolean.toString(request.enabled()), false, "Slack enabled");
        }
        if (request.socketModeEnabled() != null) {
            systemConfigService.set("slack.socketModeEnabled", Boolean.toString(request.socketModeEnabled()), false, "Slack Socket Mode enabled");
        }
        if (request.deepsqlBotUsername() != null) {
            systemConfigService.set("slack.deepsqlBotUsername", request.deepsqlBotUsername(), false, "Slack DeepSQL bot username");
        }
        if (request.appToken() != null && !request.appToken().isBlank()) {
            systemConfigService.set("slack.appToken", request.appToken(), true, "Slack app token");
        }
        if (request.botToken() != null && !request.botToken().isBlank()) {
            systemConfigService.set("slack.botToken", request.botToken(), true, "Slack bot token");
        }
        if (request.signingSecret() != null && !request.signingSecret().isBlank()) {
            systemConfigService.set("slack.signingSecret", request.signingSecret(), true, "Slack signing secret");
        }

        securityEventService.log(SecurityEventService.EventRequest.builder()
            .eventType(SecurityEventType.SLACK_CONFIG_UPDATED)
            .outcome(SecurityEventOutcome.SUCCESS)
            .actorUserId(actor.getId())
            .userId(actor.getId())
            .email(actor.getEmail())
            .requestId(requestId)
            .targetResource("settings:slack")
            .build());
        slackBotService.refreshFromConfig();
        return getSlackSettings();
    }

    private void setConfig(String key, String value, boolean sensitive, String description) {
        if (value == null) {
            return;
        }
        systemConfigService.set(key, value.trim(), sensitive, description);
    }

    public record EmailSettingsRequest(
        String host,
        Integer port,
        String username,
        String password,
        String fromEmail,
        Boolean startTls,
        Boolean ssl
    ) {
    }

    public record SecuritySettingsRequest(Boolean workspaceEmail2faEnabled) {
    }

    public record SlackSettingsRequest(
        Boolean enabled,
        Boolean socketModeEnabled,
        String appToken,
        String botToken,
        String signingSecret,
        String deepsqlBotUsername
    ) {
    }
}
