package com.dbaagent.service;

import com.dbaagent.model.DigestDeliveryMethod;
import com.dbaagent.model.PersonaTag;
import com.dbaagent.model.SlackDigestConfig;
import com.dbaagent.model.UserDigestPreference;
import com.dbaagent.repository.SlackDigestConfigRepository;
import com.dbaagent.repository.UserDigestPreferenceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Service for managing per-user digest preferences.
 *
 * <p>Provides CRUD operations and preference resolution logic for role-aware digests.
 * When no user-specific preferences exist, falls back to the global singleton config.
 *
 * <h3>Preference Resolution</h3>
 * <ol>
 *   <li>Connection-specific preference for the user</li>
 *   <li>User-wide preference (connectionId = null)</li>
 *   <li>Global singleton fallback</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class UserDigestPreferenceService {

    private final UserDigestPreferenceRepository preferenceRepository;
    private final SlackDigestConfigRepository configRepository;

    /**
     * Get all preferences for a user.
     */
    public List<UserDigestPreference> getPreferencesForUser(String username) {
        return preferenceRepository.findByUsernameOrderByConnectionIdAscCreatedAtDesc(username);
    }

    /**
     * Get enabled preferences for a user and connection.
     * Includes both connection-specific and user-wide preferences.
     */
    public List<UserDigestPreference> getEnabledPreferences(String username, String connectionId) {
        return preferenceRepository.findEnabledForUserAndConnection(username, connectionId);
    }

    /**
     * Get all users who should receive a digest for a connection.
     */
    public List<UserDigestPreference> getRecipientsForConnection(String connectionId) {
        return preferenceRepository.findEnabledForConnection(connectionId);
    }

    /**
     * Check if the system has any per-user preferences configured.
     * When false, operates entirely in legacy singleton mode.
     */
    public boolean hasAnyPreferences() {
        return preferenceRepository.hasAnyPreferences();
    }

    /**
     * Check if a user has any digest preferences.
     */
    public boolean hasPreferences(String username) {
        return preferenceRepository.existsByUsername(username);
    }

    /**
     * Get the global default cron expression.
     */
    public String getGlobalCronExpression() {
        return configRepository.findById(1L)
            .map(SlackDigestConfig::getCronExpression)
            .orElse("0 0 9 * * *");
    }

    /**
     * Create or update a digest preference.
     */
    @Transactional
    public UserDigestPreference savePreference(UserDigestPreference preference) {
        preference.setUpdatedAt(LocalDateTime.now());
        UserDigestPreference saved = preferenceRepository.save(preference);
        log.info("Saved digest preference {} for user {} (conn={}, method={})",
            saved.getId(), saved.getUsername(), saved.getConnectionId(), saved.getDeliveryMethod());
        return saved;
    }

    /**
     * Create a new preference for a user.
     */
    @Transactional
    public UserDigestPreference createPreference(
            String username,
            String connectionId,
            DigestDeliveryMethod deliveryMethod,
            PersonaTag personaTag,
            String cronExpression,
            String timezone) {

        Optional<UserDigestPreference> existing = preferenceRepository
            .findByUsernameAndConnectionIdAndDeliveryMethod(username, connectionId, deliveryMethod);

        if (existing.isPresent()) {
            throw new IllegalArgumentException(
                "Preference already exists for user=" + username +
                ", connection=" + connectionId +
                ", method=" + deliveryMethod
            );
        }

        UserDigestPreference preference = UserDigestPreference.builder()
            .username(username)
            .connectionId(connectionId)
            .enabled(true)
            .personaTag(personaTag)
            .cronExpression(cronExpression)
            .deliveryMethod(deliveryMethod)
            .timezone(timezone)
            .build();

        return savePreference(preference);
    }

    /**
     * Update an existing preference.
     */
    @Transactional
    public UserDigestPreference updatePreference(
            Long preferenceId,
            Boolean enabled,
            PersonaTag personaTag,
            String cronExpression,
            String timezone) {

        UserDigestPreference preference = preferenceRepository.findById(preferenceId)
            .orElseThrow(() -> new IllegalArgumentException("Preference not found: " + preferenceId));

        if (enabled != null) {
            preference.setEnabled(enabled);
        }
        if (personaTag != null) {
            preference.setPersonaTag(personaTag);
        }
        if (cronExpression != null) {
            preference.setCronExpression(cronExpression.isBlank() ? null : cronExpression);
        }
        if (timezone != null) {
            preference.setTimezone(timezone.isBlank() ? null : timezone);
        }

        return savePreference(preference);
    }

    /**
     * Enable or disable a preference.
     */
    @Transactional
    public UserDigestPreference setEnabled(Long preferenceId, boolean enabled) {
        UserDigestPreference preference = preferenceRepository.findById(preferenceId)
            .orElseThrow(() -> new IllegalArgumentException("Preference not found: " + preferenceId));

        preference.setEnabled(enabled);
        return savePreference(preference);
    }

    /**
     * Delete a preference.
     */
    @Transactional
    public void deletePreference(Long preferenceId) {
        preferenceRepository.deleteById(preferenceId);
        log.info("Deleted digest preference {}", preferenceId);
    }

    /**
     * Delete all preferences for a user.
     */
    @Transactional
    public void deleteAllPreferencesForUser(String username) {
        preferenceRepository.deleteByUsername(username);
        log.info("Deleted all digest preferences for user {}", username);
    }

    /**
     * Get preference by ID.
     */
    public Optional<UserDigestPreference> getPreference(Long preferenceId) {
        return preferenceRepository.findById(preferenceId);
    }

    /**
     * Get count of enabled preferences.
     */
    public long countEnabled() {
        return preferenceRepository.countByEnabledTrue();
    }

    /**
     * Get all distinct users with enabled preferences.
     */
    public List<String> getUsersWithEnabledPreferences() {
        return preferenceRepository.findDistinctUsernamesWithEnabledPreferences();
    }

    /**
     * Resolve the effective cron expression for a preference.
     */
    public String resolveEffectiveCron(UserDigestPreference preference) {
        if (preference.getCronExpression() != null && !preference.getCronExpression().isBlank()) {
            return preference.getCronExpression();
        }
        return getGlobalCronExpression();
    }
}
