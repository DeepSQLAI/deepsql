package com.dbaagent.controller;

import com.dbaagent.model.DigestDeliveryMethod;
import com.dbaagent.model.PersonaTag;
import com.dbaagent.model.UserDigestPreference;
import com.dbaagent.service.security.AccessControlService;
import com.dbaagent.service.UserDigestPreferenceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
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
        List<Map<String, String>> methods = List.of(
            Map.of("value", "SLACK_DM", "label", DigestDeliveryMethod.SLACK_DM.getDisplayName(), "description", DigestDeliveryMethod.SLACK_DM.getDescription()),
            Map.of("value", "SLACK_CHANNEL", "label", DigestDeliveryMethod.SLACK_CHANNEL.getDisplayName(), "description", DigestDeliveryMethod.SLACK_CHANNEL.getDescription()),
            Map.of("value", "EMAIL", "label", DigestDeliveryMethod.EMAIL.getDisplayName(), "description", DigestDeliveryMethod.EMAIL.getDescription())
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
}
