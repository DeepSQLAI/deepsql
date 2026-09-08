package com.dbaagent.controller;

import com.dbaagent.model.DigestDeliveryMethod;
import com.dbaagent.model.PersonaTag;
import com.dbaagent.model.SlackDigestConfig;
import com.dbaagent.model.SlackDigestLog;
import com.dbaagent.model.UserDigestPreference;
import com.dbaagent.repository.SlackDigestConfigRepository;
import com.dbaagent.repository.SlackDigestLogRepository;
import com.dbaagent.service.SlackBotService;
import com.dbaagent.service.SlackDailyDigestService;
import com.dbaagent.service.UserDigestPreferenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Admin endpoints for Slack bot and digest management.
 *
 * <p>Includes both global digest configuration and per-user preference management.
 */
@RestController
@RequestMapping("/admin/slack")
@RequiredArgsConstructor
@PreAuthorize("hasRole('ADMIN')")
public class SlackAdminController {

    private final SlackBotService slackBotService;
    private final SlackDailyDigestService slackDailyDigestService;
    private final SlackDigestLogRepository digestLogRepository;
    private final SlackDigestConfigRepository digestConfigRepository;
    private final UserDigestPreferenceService preferenceService;

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        SlackBotService.StatusSnapshot status = slackBotService.status();
        return ResponseEntity.ok(Map.of(
            "started", status.started(),
            "running", status.running(),
            "lastError", status.lastError() != null ? status.lastError() : "",
            "botUserId", status.botUserId() != null ? status.botUserId() : "",
            "config", status.config()
        ));
    }

    @PostMapping("/digest/trigger")
    public ResponseEntity<Map<String, Object>> triggerDigest() {
        SlackDailyDigestService.TriggerResult result = slackDailyDigestService.triggerDigest();
        return ResponseEntity.ok(Map.of("triggered", result.triggered(), "message", result.message()));
    }

    @GetMapping("/digests")
    public ResponseEntity<Page<SlackDigestLog>> listDigests(
            @RequestParam(required = false) String connectionId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PageRequest pageable = PageRequest.of(page, size);
        Page<SlackDigestLog> result = connectionId != null && !connectionId.isBlank()
            ? digestLogRepository.findByConnectionIdAndChannelIdIsNullOrderBySentAtDesc(connectionId, pageable)
            : digestLogRepository.findAllByChannelIdIsNullOrderBySentAtDesc(pageable);
        return ResponseEntity.ok(result);
    }

    @GetMapping("/digest/config")
    public ResponseEntity<SlackDigestConfig> getConfig() {
        SlackDigestConfig config = digestConfigRepository.findById(1L)
            .orElseGet(() -> {
                SlackDigestConfig def = new SlackDigestConfig();
                return digestConfigRepository.save(def);
            });
        return ResponseEntity.ok(config);
    }

    @PutMapping("/digest/config")
    public ResponseEntity<SlackDigestConfig> updateConfig(@RequestBody Map<String, String> body) {
        SlackDigestConfig config = digestConfigRepository.findById(1L)
            .orElseGet(SlackDigestConfig::new);
        if (body.containsKey("cronExpression")) {
            config.setCronExpression(body.get("cronExpression"));
        }
        config.setUpdatedAt(LocalDateTime.now());
        return ResponseEntity.ok(digestConfigRepository.save(config));
    }

    // ==================== PER-USER PREFERENCE MANAGEMENT ====================

    /**
     * Get digest preferences for a specific user.
     */
    @GetMapping("/digest/preferences/{username}")
    public ResponseEntity<List<UserDigestPreference>> getUserPreferences(@PathVariable String username) {
        return ResponseEntity.ok(preferenceService.getPreferencesForUser(username));
    }

    /**
     * Get all users with enabled digest preferences.
     */
    @GetMapping("/digest/preferences/users")
    public ResponseEntity<List<String>> getUsersWithPreferences() {
        return ResponseEntity.ok(preferenceService.getUsersWithEnabledPreferences());
    }

    /**
     * Get digest preference statistics.
     */
    @GetMapping("/digest/preferences/stats")
    public ResponseEntity<Map<String, Object>> getPreferenceStats() {
        boolean hasPreferences = preferenceService.hasAnyPreferences();
        long enabledCount = preferenceService.countEnabled();
        List<String> users = preferenceService.getUsersWithEnabledPreferences();

        return ResponseEntity.ok(Map.of(
            "hasAnyPreferences", hasPreferences,
            "enabledCount", enabledCount,
            "usersWithPreferences", users.size(),
            "mode", hasPreferences ? "per-user" : "singleton"
        ));
    }

    /**
     * Create a digest preference for a user (admin can create for any user).
     */
    @PostMapping("/digest/preferences/{username}")
    public ResponseEntity<UserDigestPreference> createUserPreference(
            @PathVariable String username,
            @RequestBody CreatePreferenceRequest request) {

        DigestDeliveryMethod method = request.deliveryMethod != null
            ? DigestDeliveryMethod.fromString(request.deliveryMethod)
            : DigestDeliveryMethod.SLACK_DM;

        PersonaTag persona = request.personaTag != null
            ? PersonaTag.fromString(request.personaTag)
            : null;

        UserDigestPreference preference = preferenceService.createPreference(
            username,
            request.connectionId,
            method,
            persona,
            request.cronExpression,
            request.timezone
        );

        return ResponseEntity.ok(preference);
    }

    /**
     * Update a user's digest preference.
     */
    @PutMapping("/digest/preferences/id/{id}")
    public ResponseEntity<UserDigestPreference> updateUserPreference(
            @PathVariable Long id,
            @RequestBody UpdatePreferenceRequest request) {

        PersonaTag persona = request.personaTag != null
            ? PersonaTag.fromString(request.personaTag)
            : null;

        UserDigestPreference updated = preferenceService.updatePreference(
            id,
            request.enabled,
            persona,
            request.cronExpression,
            request.timezone
        );

        return ResponseEntity.ok(updated);
    }

    /**
     * Delete a user's digest preference.
     */
    @DeleteMapping("/digest/preferences/id/{id}")
    public ResponseEntity<Void> deleteUserPreference(@PathVariable Long id) {
        preferenceService.deletePreference(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Delete all digest preferences for a user.
     */
    @DeleteMapping("/digest/preferences/{username}")
    public ResponseEntity<Void> deleteAllUserPreferences(@PathVariable String username) {
        preferenceService.deleteAllPreferencesForUser(username);
        return ResponseEntity.noContent().build();
    }

    public record CreatePreferenceRequest(
        String connectionId,
        String deliveryMethod,
        String personaTag,
        String cronExpression,
        String timezone
    ) {}

    public record UpdatePreferenceRequest(
        Boolean enabled,
        String personaTag,
        String cronExpression,
        String timezone
    ) {}
}
