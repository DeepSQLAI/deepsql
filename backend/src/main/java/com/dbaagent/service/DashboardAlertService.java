package com.dbaagent.service;

import com.dbaagent.model.DashboardAlert;
import com.dbaagent.model.SavedDashboard;
import com.dbaagent.repository.DashboardAlertRepository;
import com.dbaagent.repository.SavedDashboardRepository;
import jakarta.mail.MessagingException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Evaluates a dashboard's natural-language alert condition on a schedule (see
 * {@code DashboardAlertScheduler}) by handing the same DeepSQL agent that builds
 * dashboards a single bounded task: run one read-only check and answer YES/NO.
 *
 * <p>No other dashboard tool can bolt an AI-authored condition onto arbitrary
 * artifact HTML the way this works — the agent already has brain/schema context
 * and a verified execute_sql tool; evaluating "alert if p95 latency > 2s" is the
 * same grounded-SQL capability the dashboard-generation flow already exercises,
 * just with a one-line answer instead of a whole HTML document.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardAlertService {

    private final DashboardAlertRepository alertRepository;
    private final SavedDashboardRepository savedDashboardRepository;
    private final AgentBridgeService agentBridgeService;
    private final AgentChatClient agentChatClient;
    private final EmailService emailService;
    private final WebhookService webhookService;

    @Transactional
    public DashboardAlert createAlert(UUID dashboardId, String createdByUsername, DashboardAlert draft) {
        requireConditionText(draft.getConditionText());
        SavedDashboard dashboard = savedDashboardRepository.findById(dashboardId)
            .orElseThrow(() -> new IllegalArgumentException("Dashboard not found with id: " + dashboardId));
        DashboardAlert alert = new DashboardAlert();
        alert.setDashboardId(dashboardId);
        alert.setConnectionId(dashboard.getConnectionId());
        alert.setCreatedByUsername(createdByUsername);
        alert.setConditionText(draft.getConditionText());
        if (draft.getChannels() != null) alert.setChannels(draft.getChannels());
        alert.setEmailRecipients(draft.getEmailRecipients());
        alert.setWebhookUrl(draft.getWebhookUrl());
        if (draft.getCheckIntervalMinutes() != null) alert.setCheckIntervalMinutes(draft.getCheckIntervalMinutes());
        if (draft.getCooldownMinutes() != null) alert.setCooldownMinutes(draft.getCooldownMinutes());
        return alertRepository.save(alert);
    }

    public List<DashboardAlert> getAlertsForDashboard(UUID dashboardId) {
        return alertRepository.findByDashboardIdOrderByCreatedAtDesc(dashboardId);
    }

    @Transactional
    public DashboardAlert updateAlert(UUID alertId, DashboardAlert updates) {
        DashboardAlert existing = requireAlert(alertId);
        if (updates.getConditionText() != null) {
            requireConditionText(updates.getConditionText());
            existing.setConditionText(updates.getConditionText());
        }
        if (updates.getChannels() != null) existing.setChannels(updates.getChannels());
        if (updates.getEmailRecipients() != null) existing.setEmailRecipients(updates.getEmailRecipients());
        if (updates.getWebhookUrl() != null) existing.setWebhookUrl(updates.getWebhookUrl());
        if (updates.getIsEnabled() != null) existing.setIsEnabled(updates.getIsEnabled());
        if (updates.getCheckIntervalMinutes() != null) existing.setCheckIntervalMinutes(updates.getCheckIntervalMinutes());
        if (updates.getCooldownMinutes() != null) existing.setCooldownMinutes(updates.getCooldownMinutes());
        return alertRepository.save(existing);
    }

    @Transactional
    public void deleteAlert(UUID alertId) {
        alertRepository.deleteById(alertId);
    }

    private DashboardAlert requireAlert(UUID id) {
        return alertRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Alert not found with id: " + id));
    }

    private void requireConditionText(String conditionText) {
        if (conditionText == null || conditionText.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "conditionText is required");
        }
    }

    // ── scheduled evaluation ────────────────────────────────────────────────

    public List<DashboardAlert> findDue() {
        return alertRepository.findDue(LocalDateTime.now());
    }

    /** Evaluate one alert: run the bounded agent check, record the result, dispatch if it fires. */
    @Transactional
    public void evaluate(UUID alertId) {
        DashboardAlert alert = requireAlert(alertId);
        alert.setLastCheckedAt(LocalDateTime.now());
        try {
            Verdict verdict = runCheck(alert);
            alert.setLastVerdict(verdict.fired() ? "FIRED" : "OK");
            alert.setLastReason(verdict.reason());
            alert.setLastError(null);
            if (verdict.fired() && canFire(alert)) {
                dispatch(alert, verdict);
                alert.setLastFiredAt(LocalDateTime.now());
            }
        } catch (Exception e) {
            log.warn("Alert {} evaluation failed: {}", alertId, e.getMessage());
            alert.setLastVerdict("ERROR");
            alert.setLastError(e.getMessage() == null ? "evaluation failed" : e.getMessage());
        } finally {
            alertRepository.save(alert);
        }
    }

    private boolean canFire(DashboardAlert alert) {
        if (alert.getLastFiredAt() == null) return true;
        long minutesSinceFired = Duration.between(alert.getLastFiredAt(), LocalDateTime.now()).toMinutes();
        return minutesSinceFired >= alert.getCooldownMinutes();
    }

    private record Verdict(boolean fired, String reason) { }

    private Verdict runCheck(DashboardAlert alert) {
        String profile = agentBridgeService.ensureProfileForUser(alert.getCreatedByUsername(), alert.getConnectionId());
        // Fresh, isolated session per check — not the owner's own chat thread, and
        // never reused across evaluations (a stale multi-turn context is exactly
        // wrong for "answer this one bounded question right now").
        String sessionId = agentChatClient.ensureSession(profile, null);
        if (sessionId == null) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "The DeepSQL agent is unavailable right now.");
        }
        AgentChatClient.AgentReply reply = agentChatClient.sendAndAwait(sessionId, buildCheckTask(alert));
        if (!reply.ok()) {
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "The agent couldn't evaluate the condition: " + (reply.error() == null ? "it ended early" : reply.error()));
        }
        return parseVerdict(reply.text());
    }

    private String buildCheckTask(DashboardAlert alert) {
        return """
            Evaluate ONE alert condition for DeepSQL connection %s. This is a bounded, read-only
            check — you do not need to build or edit any dashboard.

            Condition: %s

            Do this, in order:
            1. Ground just enough to run the check: get_brain_context and/or get_schema only if you
               need to find the right table/column. Obey any business rules about which table/column
               a concept in the condition refers to.
            2. Write ONE read-only SELECT (table-qualified columns) that directly answers whether the
               condition is currently true. Run it with execute_sql and read the actual rows back —
               do not guess or assume a result.
            3. Decide: is the condition true right now, based on what the query actually returned?

            Reply with EXACTLY two lines, nothing else — no other tool calls after your last one, no
            markdown, no code fences:
            YES or NO (whether the condition is true right now)
            <one short sentence citing the actual number(s) you found, e.g. "p95 latency is 2.4s">
            """.formatted(alert.getConnectionId(), alert.getConditionText());
    }

    private Verdict parseVerdict(String text) {
        if (text == null || text.isBlank()) return new Verdict(false, "The agent returned no answer.");
        String[] lines = text.trim().split("\\R", 2);
        boolean fired = lines[0].trim().equalsIgnoreCase("YES");
        String reason = lines.length > 1 ? lines[1].trim() : "";
        return new Verdict(fired, reason.isBlank() ? (fired ? "Condition met." : "Condition not met.") : reason);
    }

    // ── dispatch ────────────────────────────────────────────────────────────

    private void dispatch(DashboardAlert alert, Verdict verdict) {
        SavedDashboard dashboard = savedDashboardRepository.findById(alert.getDashboardId()).orElse(null);
        String dashboardName = dashboard == null ? "Dashboard" : dashboard.getName();
        List<String> channels = Arrays.stream(alert.getChannels().split(","))
            .map(String::trim).filter(s -> !s.isEmpty()).toList();

        if (channels.contains("email") && alert.getEmailRecipients() != null && !alert.getEmailRecipients().isBlank()) {
            List<String> recipients = Arrays.stream(alert.getEmailRecipients().split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
            try {
                emailService.sendDashboardAlert(dashboardName, alert.getConditionText(), verdict.reason(), recipients);
            } catch (MessagingException e) {
                log.error("Failed to email dashboard alert {}: {}", alert.getId(), e.getMessage());
            }
        }
        if (channels.contains("webhook") && alert.getWebhookUrl() != null && !alert.getWebhookUrl().isBlank()) {
            try {
                webhookService.sendDashboardAlert(alert.getWebhookUrl(), dashboardName, alert.getConditionText(), verdict.reason());
            } catch (Exception e) {
                log.error("Failed to webhook dashboard alert {}: {}", alert.getId(), e.getMessage());
            }
        }
        log.info("Dashboard alert fired: dashboard={} condition=\"{}\" reason=\"{}\"",
            dashboardName, alert.getConditionText(), verdict.reason());
    }
}
