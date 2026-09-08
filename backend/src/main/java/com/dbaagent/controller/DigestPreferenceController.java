package com.dbaagent.controller;

import com.dbaagent.model.DigestDeliveryMethod;
import com.dbaagent.model.PersonaTag;
import com.dbaagent.model.UserDigestPreference;
import com.dbaagent.service.DigestPreferenceSeedService;
import com.dbaagent.service.security.AccessControlService;
import com.dbaagent.service.UserDigestPreferenceService;
import com.dbaagent.service.SlackDailyDigestService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for managing per-user digest preferences.
 *
 * <p>Users can manage their own digest subscriptions. Admins can manage any user's
 * preferences via the admin endpoints in {@link SlackAdminController}.
 */
@RestController
@RequestMapping("/digest/preferences")
@RequiredArgsConstructor
@Slf4j
public class DigestPreferenceController {

    private final UserDigestPreferenceService preferenceService;
    private final AccessControlService accessControlService;
    private final DigestPreferenceSeedService seedService;
    private final SlackDailyDigestService digestService;

    /**
     * Get the current user's digest preferences.
     */
    @GetMapping
    public ResponseEntity<List<UserDigestPreference>> getMyPreferences() {
        String username = accessControlService.requireCurrentUsername();
        return ResponseEntity.ok(preferenceService.getPreferencesForUser(username));
    }

    /**
     * Create a new digest preference for the current user.
     */
    @PostMapping
    public ResponseEntity<UserDigestPreference> createPreference(@RequestBody CreatePreferenceRequest request) {
        String username = accessControlService.requireCurrentUsername();

        DigestDeliveryMethod method;
        if (request.deliveryMethod == null || request.deliveryMethod.isBlank()) {
            method = DigestDeliveryMethod.SLACK_DM;
        } else {
            method = DigestDeliveryMethod.fromString(request.deliveryMethod);
            if (method == null) {
                throw new IllegalArgumentException("Unknown delivery method: " + request.deliveryMethod);
            }
        }

        if (method == DigestDeliveryMethod.EMAIL) {
            throw new IllegalArgumentException("Email digest delivery is not available yet");
        }

        if (request.connectionId != null && !request.connectionId.isBlank()) {
            accessControlService.assertCanReadConnectionContent(request.connectionId);
        }

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
     * Update an existing preference.
     */
    @PutMapping("/{id}")
    public ResponseEntity<UserDigestPreference> updatePreference(
            @PathVariable Long id,
            @RequestBody UpdatePreferenceRequest request) {

        String username = accessControlService.requireCurrentUsername();

        UserDigestPreference existing = preferenceService.getPreference(id)
            .orElseThrow(() -> new IllegalArgumentException("Preference not found: " + id));

        if (!existing.getUsername().equals(username) && !accessControlService.isCurrentUserAdmin()) {
            throw new IllegalArgumentException("Cannot update another user's preference");
        }

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
     * Enable or disable a preference.
     */
    @PatchMapping("/{id}/enabled")
    public ResponseEntity<UserDigestPreference> setEnabled(
            @PathVariable Long id,
            @RequestBody Map<String, Boolean> body) {

        String username = accessControlService.requireCurrentUsername();

        UserDigestPreference existing = preferenceService.getPreference(id)
            .orElseThrow(() -> new IllegalArgumentException("Preference not found: " + id));

        if (!existing.getUsername().equals(username) && !accessControlService.isCurrentUserAdmin()) {
            throw new IllegalArgumentException("Cannot update another user's preference");
        }

        Boolean enabled = body.get("enabled");
        if (enabled == null) {
            throw new IllegalArgumentException("Missing 'enabled' field");
        }

        UserDigestPreference updated = preferenceService.setEnabled(id, enabled);
        return ResponseEntity.ok(updated);
    }

    /**
     * Delete a preference.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deletePreference(@PathVariable Long id) {
        String username = accessControlService.requireCurrentUsername();

        UserDigestPreference existing = preferenceService.getPreference(id)
            .orElseThrow(() -> new IllegalArgumentException("Preference not found: " + id));

        if (!existing.getUsername().equals(username) && !accessControlService.isCurrentUserAdmin()) {
            throw new IllegalArgumentException("Cannot delete another user's preference");
        }

        preferenceService.deletePreference(id);
        return ResponseEntity.noContent().build();
    }

    /**
     * Get available persona tags.
     */
    @GetMapping("/persona-tags")
    public ResponseEntity<List<Map<String, String>>> getPersonaTags() {
        List<Map<String, String>> tags = List.of(
            Map.of("value", "DBA", "label", PersonaTag.DBA.getDisplayName(), "description", PersonaTag.DBA.getDescription()),
            Map.of("value", "APP_ENG", "label", PersonaTag.APP_ENG.getDisplayName(), "description", PersonaTag.APP_ENG.getDescription()),
            Map.of("value", "DATA_ENG", "label", PersonaTag.DATA_ENG.getDisplayName(), "description", PersonaTag.DATA_ENG.getDescription()),
            Map.of("value", "EXEC", "label", PersonaTag.EXEC.getDisplayName(), "description", PersonaTag.EXEC.getDescription())
        );
        return ResponseEntity.ok(tags);
    }

    /**
     * Get available delivery methods.
     */
    @GetMapping("/delivery-methods")
    public ResponseEntity<List<Map<String, String>>> getDeliveryMethods() {
        // Only advertise methods this PR actually delivers. EMAIL/WhatsApp are PR4.
        List<Map<String, String>> methods = List.of(
            Map.of("value", "SLACK_DM", "label", DigestDeliveryMethod.SLACK_DM.getDisplayName(), "description", DigestDeliveryMethod.SLACK_DM.getDescription()),
            Map.of("value", "SLACK_CHANNEL", "label", DigestDeliveryMethod.SLACK_CHANNEL.getDisplayName(), "description", DigestDeliveryMethod.SLACK_CHANNEL.getDescription())
        );
        return ResponseEntity.ok(methods);
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

    // ─────────────────────────────────────────────────────────────────────────
    // Admin: Seed & Status endpoints
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Preview what preferences would be seeded from singleton config.
     * Admin only.
     */
    @GetMapping("/admin/seed/preview")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SeedPreviewResponse> previewSeed() {
        DigestPreferenceSeedService.SeedResult result = seedService.previewSeed();
        return ResponseEntity.ok(new SeedPreviewResponse(
            result.usersProcessed(),
            result.preferencesCreated(),
            result.skipped(),
            result.preferences().stream()
                .map(p -> new PreferencePreview(p.getUsername(), p.getConnectionId(),
                    p.getPersonaTag() != null ? p.getPersonaTag().name() : null))
                .toList()
        ));
    }

    /**
     * Seed preferences for all Slack-linked users from singleton config.
     * Admin only. Idempotent: skips users with existing preferences.
     */
    @PostMapping("/admin/seed")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<SeedResultResponse> executeSeed() {
        DigestPreferenceSeedService.SeedResult result = seedService.executeSeed();
        log.info("Admin seeded {} digest preferences for {} users",
            result.preferencesCreated(), result.usersProcessed());
        return ResponseEntity.ok(new SeedResultResponse(
            result.usersProcessed(),
            result.preferencesCreated(),
            result.skipped()
        ));
    }

    /**
     * Seed preferences for the current user.
     * Available to any authenticated user.
     */
    @PostMapping("/seed/me")
    public ResponseEntity<SeedResultResponse> seedForCurrentUser() {
        String username = accessControlService.requireCurrentUsername();
        DigestPreferenceSeedService.SeedResult result = seedService.seedPreferencesForUser(username, false);
        return ResponseEntity.ok(new SeedResultResponse(
            1,
            result.preferencesCreated(),
            result.skipped()
        ));
    }

    /**
     * Get current digest mode info.
     */
    @GetMapping("/status")
    public ResponseEntity<DigestStatusResponse> getStatus() {
        SlackDailyDigestService.DigestModeInfo modeInfo = digestService.getDigestModeInfo();
        return ResponseEntity.ok(new DigestStatusResponse(
            modeInfo.perUserMode(),
            modeInfo.enabledPreferences(),
            modeInfo.distinctUsers()
        ));
    }

    public record SeedPreviewResponse(
        int usersProcessed,
        int wouldCreate,
        List<String> wouldSkip,
        List<PreferencePreview> preferences
    ) {}

    public record PreferencePreview(String username, String connectionId, String personaTag) {}

    public record SeedResultResponse(int usersProcessed, int preferencesCreated, List<String> skipped) {}

    public record DigestStatusResponse(boolean perUserMode, long enabledPreferences, int distinctUsers) {}
}
