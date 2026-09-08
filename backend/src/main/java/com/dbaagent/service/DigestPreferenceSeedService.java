package com.dbaagent.service;

import com.dbaagent.model.DigestDeliveryMethod;
import com.dbaagent.model.PersonaTag;
import com.dbaagent.model.Role;
import com.dbaagent.model.SlackDigestConfig;
import com.dbaagent.model.User;
import com.dbaagent.model.UserDigestPreference;
import com.dbaagent.repository.SlackDigestConfigRepository;
import com.dbaagent.repository.UserDigestPreferenceRepository;
import com.dbaagent.repository.UserRepository;
import com.dbaagent.service.security.ConnectionAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Service for seeding digest preferences from singleton config.
 *
 * <p>Provides migration helpers so existing deployments (like Stayflexi) don't stay
 * on legacy broadcast forever after the first per-user preferences appear.
 *
 * <h3>Seed Strategy</h3>
 * <ol>
 *   <li>Find all users with linked Slack accounts (candidates for DM delivery)</li>
 *   <li>For each user, create a SLACK_DM preference for each connection they can access</li>
 *   <li>Persona tag is inferred from the user's role (can be edited later in UI)</li>
 *   <li>Cron expression defaults to the global singleton config</li>
 * </ol>
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DigestPreferenceSeedService {

    private final UserDigestPreferenceRepository preferenceRepository;
    private final SlackDigestConfigRepository configRepository;
    private final SlackUserLinkService slackUserLinkService;
    private final ConnectionAccessService connectionAccessService;
    private final UserRepository userRepository;

    /**
     * Seed digest preferences for all Slack-linked users.
     *
     * <p>This creates one SLACK_DM preference per connection the user can access.
     * Existing preferences are not overwritten.
     *
     * @param dryRun if true, return what would be created without persisting
     * @return summary of seeded preferences
     */
    @Transactional
    public SeedResult seedPreferencesFromSingleton(boolean dryRun) {
        List<String> linkedUsernames = slackUserLinkService.getAllLinkedUsernames();
        if (linkedUsernames.isEmpty()) {
            log.info("No Slack-linked users found; nothing to seed");
            return new SeedResult(0, 0, List.of(), List.of());
        }

        String globalCron = getGlobalCronExpression();
        List<UserDigestPreference> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (String username : linkedUsernames) {
            Optional<User> userOpt = userRepository.findByUsernameIgnoreCase(username);
            if (userOpt.isEmpty()) {
                skipped.add(username + ": user not found");
                continue;
            }

            User user = userOpt.get();
            if (!user.isActiveAccount()) {
                skipped.add(username + ": user inactive");
                continue;
            }

            List<String> connectionIds = connectionAccessService
                .getVisibleConnections(username, user.isAdmin())
                .stream()
                .map(conn -> conn.getId())
                .toList();

            if (connectionIds.isEmpty()) {
                skipped.add(username + ": no accessible connections");
                continue;
            }

            PersonaTag inferredPersona = inferPersonaFromRole(user.getRoleEnum());

            for (String connectionId : connectionIds) {
                // Check if preference already exists
                Optional<UserDigestPreference> existing = preferenceRepository
                    .findByUsernameAndConnectionIdAndDeliveryMethod(
                        username, connectionId, DigestDeliveryMethod.SLACK_DM);

                if (existing.isPresent()) {
                    skipped.add(username + "/" + connectionId + ": preference exists");
                    continue;
                }

                UserDigestPreference pref = UserDigestPreference.builder()
                    .username(username)
                    .connectionId(connectionId)
                    .enabled(true)
                    .deliveryMethod(DigestDeliveryMethod.SLACK_DM)
                    .personaTag(inferredPersona)
                    .cronExpression(null)  // Use global default
                    .timezone(null)        // Use system default
                    .build();

                if (!dryRun) {
                    preferenceRepository.save(pref);
                }
                created.add(pref);
            }
        }

        log.info("Digest preference seed: {} users, {} created, {} skipped (dryRun={})",
            linkedUsernames.size(), created.size(), skipped.size(), dryRun);

        return new SeedResult(linkedUsernames.size(), created.size(), skipped, created);
    }

    /**
     * Seed preferences for a single user.
     */
    @Transactional
    public SeedResult seedPreferencesForUser(String username, boolean dryRun) {
        Optional<User> userOpt = userRepository.findByUsernameIgnoreCase(username);
        if (userOpt.isEmpty()) {
            return new SeedResult(0, 0, List.of(username + ": user not found"), List.of());
        }

        User user = userOpt.get();
        if (!user.isActiveAccount()) {
            return new SeedResult(0, 0, List.of(username + ": user inactive"), List.of());
        }

        // Check if user has Slack linked
        List<com.dbaagent.model.SlackUserLink> links = slackUserLinkService.getLinkedSlackAccounts(username);
        if (links.isEmpty()) {
            return new SeedResult(0, 0, List.of(username + ": not linked to Slack"), List.of());
        }

        List<String> connectionIds = connectionAccessService
            .getVisibleConnections(username, user.isAdmin())
            .stream()
            .map(conn -> conn.getId())
            .toList();

        if (connectionIds.isEmpty()) {
            return new SeedResult(0, 0, List.of(username + ": no accessible connections"), List.of());
        }

        PersonaTag inferredPersona = inferPersonaFromRole(user.getRoleEnum());
        List<UserDigestPreference> created = new ArrayList<>();
        List<String> skipped = new ArrayList<>();

        for (String connectionId : connectionIds) {
            Optional<UserDigestPreference> existing = preferenceRepository
                .findByUsernameAndConnectionIdAndDeliveryMethod(
                    username, connectionId, DigestDeliveryMethod.SLACK_DM);

            if (existing.isPresent()) {
                skipped.add(connectionId + ": preference exists");
                continue;
            }

            UserDigestPreference pref = UserDigestPreference.builder()
                .username(username)
                .connectionId(connectionId)
                .enabled(true)
                .deliveryMethod(DigestDeliveryMethod.SLACK_DM)
                .personaTag(inferredPersona)
                .build();

            if (!dryRun) {
                preferenceRepository.save(pref);
            }
            created.add(pref);
        }

        log.info("Seeded {} preferences for user {} (dryRun={})", created.size(), username, dryRun);

        return new SeedResult(1, created.size(), skipped, created);
    }

    /**
     * Infer a persona tag from the user's role.
     */
    private PersonaTag inferPersonaFromRole(Role role) {
        if (role == null) {
            return null;  // No persona; use role-based prioritization only
        }
        return switch (role) {
            case ADMIN -> null;  // Admins often wear multiple hats; let them pick
            case DBA -> PersonaTag.DBA;
            case DATA_ENGINEER -> PersonaTag.DATA_ENG;
            case DEVELOPER -> PersonaTag.APP_ENG;
        };
    }

    private String getGlobalCronExpression() {
        return configRepository.findById(1L)
            .map(SlackDigestConfig::getCronExpression)
            .orElse("0 0 9 * * *");
    }

    /**
     * Get seed preview: what would be created without actually seeding.
     */
    public SeedResult previewSeed() {
        return seedPreferencesFromSingleton(true);
    }

    /**
     * Execute seed: create preferences for all eligible users.
     */
    public SeedResult executeSeed() {
        return seedPreferencesFromSingleton(false);
    }

    public record SeedResult(
        int usersProcessed,
        int preferencesCreated,
        List<String> skipped,
        List<UserDigestPreference> preferences
    ) {
        public boolean hasCreations() {
            return preferencesCreated > 0;
        }
    }
}
