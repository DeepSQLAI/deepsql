package com.dbaagent.service;

import com.dbaagent.model.DashboardVersion;
import com.dbaagent.model.SavedDashboard;
import com.dbaagent.repository.DashboardVersionRepository;
import com.dbaagent.repository.SavedDashboardRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class SavedDashboardService {

    private static final SecureRandom RANDOM = new SecureRandom();

    // A RUNNING status left behind by a crashed/killed backend must not block
    // a legitimate retry forever — only respected while still fresh.
    private static final Duration STALE_RUNNING_THRESHOLD = Duration.ofMinutes(20);

    // Bound history growth — a dashboard iterated on for months shouldn't carry an
    // unbounded snapshot table. Oldest beyond this count are pruned on each write.
    private static final int MAX_VERSIONS_PER_DASHBOARD = 50;

    @Autowired
    private SavedDashboardRepository savedDashboardRepository;

    @Autowired
    private DashboardVersionRepository dashboardVersionRepository;

    @Autowired
    private ObjectMapper objectMapper;

    /** Publish a dashboard to the web: mint a token if needed, mark it public. */
    @Transactional
    public SavedDashboard enablePublicShare(UUID id) {
        SavedDashboard d = savedDashboardRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Dashboard not found with id: " + id));
        if (d.getShareToken() == null || d.getShareToken().isBlank()) {
            d.setShareToken(newShareToken());
        }
        d.setIsPublic(true);
        return savedDashboardRepository.save(d);
    }

    /** Revoke the public link (keeps the token dormant; re-enabling reuses it). */
    @Transactional
    public SavedDashboard disablePublicShare(UUID id) {
        SavedDashboard d = savedDashboardRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Dashboard not found with id: " + id));
        d.setIsPublic(false);
        return savedDashboardRepository.save(d);
    }

    public Optional<SavedDashboard> findByShareToken(String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return savedDashboardRepository.findByShareToken(token);
    }

    private static final org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder SHARE_PW_ENCODER =
        new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder();

    /** Set (or, with a blank value, clear) the public link's password. */
    @Transactional
    public SavedDashboard setSharePassword(UUID id, String rawPassword) {
        SavedDashboard d = savedDashboardRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Dashboard not found with id: " + id));
        if (rawPassword == null || rawPassword.isBlank()) {
            d.setSharePasswordHash(null);
        } else {
            d.setSharePasswordHash(SHARE_PW_ENCODER.encode(rawPassword));
        }
        return savedDashboardRepository.save(d);
    }

    /** True if the dashboard has no password, or the supplied one matches. */
    public boolean sharePasswordOk(SavedDashboard d, String rawPassword) {
        String hash = d.getSharePasswordHash();
        if (hash == null || hash.isBlank()) return true;
        if (rawPassword == null || rawPassword.isEmpty()) return false;
        return SHARE_PW_ENCODER.matches(rawPassword, hash);
    }

    private String newShareToken() {
        byte[] b = new byte[24];
        RANDOM.nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /**
     * Save a new dashboard
     */
    @Transactional
    public SavedDashboard saveDashboard(SavedDashboard savedDashboard) {
        log.info("Saving dashboard: {} for connection: {}", savedDashboard.getName(), savedDashboard.getConnectionId());
        return savedDashboardRepository.save(savedDashboard);
    }

    /**
     * Update an existing dashboard
     */
    @Transactional
    public SavedDashboard updateDashboard(UUID id, SavedDashboard updates) {
        log.info("Updating dashboard: {}", id);

        Optional<SavedDashboard> existingOpt = savedDashboardRepository.findById(id);
        if (existingOpt.isEmpty()) {
            throw new IllegalArgumentException("Dashboard not found with id: " + id);
        }

        SavedDashboard existing = existingOpt.get();

        if (updates.getName() != null) {
            existing.setName(updates.getName());
        }
        if (updates.getDescription() != null) {
            existing.setDescription(updates.getDescription());
        }
        if (updates.getDashboardConfig() != null && !updates.getDashboardConfig().equals(existing.getDashboardConfig())) {
            if (hasRealBuild(existing)) {
                snapshotVersion(existing, "MANUAL_EDIT");
            }
            existing.setDashboardConfig(updates.getDashboardConfig());
        }
        if (updates.getChatMessages() != null) {
            existing.setChatMessages(updates.getChatMessages());
        }
        // Note: share_token / is_public are managed only via the dedicated share
        // endpoints, never through a general update, so they can't be spoofed here.
        if (updates.getTags() != null) {
            existing.setTags(updates.getTags());
        }
        if (updates.getIsFavorite() != null) {
            existing.setIsFavorite(updates.getIsFavorite());
        }
        // Blank clears the folder (moves back to "no folder") — distinguished from
        // null, which means the update omitted this field entirely.
        if (updates.getFolder() != null) {
            existing.setFolder(updates.getFolder().isBlank() ? null : updates.getFolder());
        }

        return savedDashboardRepository.save(existing);
    }

    /**
     * Get a dashboard by ID
     */
    public Optional<SavedDashboard> getDashboardById(UUID id) {
        return savedDashboardRepository.findById(id);
    }

    /**
     * Get all dashboards for a connection
     */
    public List<SavedDashboard> getDashboardsByConnection(String connectionId) {
        log.info("Fetching all dashboards for connection: {}", connectionId);
        return savedDashboardRepository.findByConnectionIdOrderByCreatedAtDesc(connectionId);
    }

    /**
     * Get favorite dashboards for a connection
     */
    public List<SavedDashboard> getFavoriteDashboards(String connectionId) {
        log.info("Fetching favorite dashboards for connection: {}", connectionId);
        return savedDashboardRepository.findByConnectionIdAndIsFavoriteTrueOrderByCreatedAtDesc(connectionId);
    }

    /**
     * Get dashboards by folder
     */
    public List<SavedDashboard> getDashboardsByFolder(String connectionId, String folder) {
        log.info("Fetching dashboards in folder: {} for connection: {}", folder, connectionId);
        return savedDashboardRepository.findByConnectionIdAndFolderOrderByCreatedAtDesc(connectionId, folder);
    }

    /**
     * Search dashboards
     */
    public List<SavedDashboard> searchDashboards(String connectionId, String searchTerm) {
        log.info("Searching dashboards with term: {} for connection: {}", searchTerm, connectionId);
        return savedDashboardRepository.searchDashboards(connectionId, searchTerm);
    }

    /**
     * Get dashboards by tag
     */
    public List<SavedDashboard> getDashboardsByTag(String connectionId, String tag) {
        log.info("Fetching dashboards with tag: {} for connection: {}", tag, connectionId);
        return savedDashboardRepository.findByTag(connectionId, tag);
    }

    /**
     * Get distinct folders for a connection
     */
    public List<String> getFolders(String connectionId) {
        log.info("Fetching folders for connection: {}", connectionId);
        return savedDashboardRepository.findDistinctFoldersByConnectionId(connectionId);
    }

    /**
     * Toggle favorite status
     */
    @Transactional
    public SavedDashboard toggleFavorite(UUID id) {
        log.info("Toggling favorite for dashboard: {}", id);

        Optional<SavedDashboard> existingOpt = savedDashboardRepository.findById(id);
        if (existingOpt.isEmpty()) {
            throw new IllegalArgumentException("Dashboard not found with id: " + id);
        }

        SavedDashboard existing = existingOpt.get();
        existing.setIsFavorite(!existing.getIsFavorite());

        return savedDashboardRepository.save(existing);
    }

    /**
     * Delete a dashboard
     */
    @Transactional
    public void deleteDashboard(UUID id) {
        log.info("Deleting dashboard: {}", id);
        savedDashboardRepository.deleteById(id);
    }

    /** Duplicate a dashboard (fresh id, not shared, not a favorite) as a starting point to iterate on. */
    @Transactional
    public SavedDashboard cloneDashboard(UUID id) {
        SavedDashboard source = requireDashboard(id);
        SavedDashboard copy = new SavedDashboard();
        copy.setConnectionId(source.getConnectionId());
        copy.setUserId(source.getUserId());
        copy.setName(source.getName() == null ? "Untitled copy" : source.getName() + " (copy)");
        copy.setDescription(source.getDescription());
        copy.setDashboardConfig(source.getDashboardConfig());
        copy.setChatMessages(source.getChatMessages());
        copy.setTags(source.getTags());
        copy.setFolder(source.getFolder());
        copy.setIsFavorite(false);
        copy.setIsPublic(false);
        return savedDashboardRepository.save(copy);
    }

    /** True once a dashboard has an actual rendered artifact, not just the initial placeholder. */
    private boolean hasRealBuild(SavedDashboard dashboard) {
        String cfg = dashboard.getDashboardConfig();
        return cfg != null && !cfg.isBlank() && !cfg.equals("{}") && cfg.contains("\"html\"");
    }

    private void snapshotVersion(SavedDashboard dashboard, String trigger) {
        DashboardVersion version = new DashboardVersion();
        version.setDashboardId(dashboard.getId());
        version.setDashboardConfig(dashboard.getDashboardConfig());
        version.setName(dashboard.getName());
        version.setTrigger(trigger);
        dashboardVersionRepository.save(version);
        pruneOldVersions(dashboard.getId());
    }

    private void pruneOldVersions(UUID dashboardId) {
        List<DashboardVersion> versions = dashboardVersionRepository.findByDashboardIdOrderByCreatedAtDesc(dashboardId);
        if (versions.size() > MAX_VERSIONS_PER_DASHBOARD) {
            dashboardVersionRepository.deleteAll(versions.subList(MAX_VERSIONS_PER_DASHBOARD, versions.size()));
        }
    }

    /** History for a dashboard, most recent first. */
    public List<DashboardVersion> getVersionHistory(UUID dashboardId) {
        return dashboardVersionRepository.findByDashboardIdOrderByCreatedAtDesc(dashboardId);
    }

    /**
     * Restore a prior snapshot as the current config — snapshots what's live now first, so
     * restoring is itself undoable.
     *
     * <p>The restored version's own row (and any other row with byte-identical content —
     * an earlier restore of the same snapshot) is then deleted: that content is now the
     * live config, not history, so leaving it in the list would show it twice — once as
     * "Current" and again as a stale history entry a user could re-restore into a no-op.
     * A flip-flop restore-edit-restore-edit session otherwise piles up an alternating
     * chain of identical snapshots, one pair per cycle.
     */
    @Transactional
    public SavedDashboard restoreVersion(UUID dashboardId, UUID versionId) {
        SavedDashboard dashboard = requireDashboard(dashboardId);
        DashboardVersion version = dashboardVersionRepository.findById(versionId)
            .orElseThrow(() -> new IllegalArgumentException("Version not found with id: " + versionId));
        if (!version.getDashboardId().equals(dashboardId)) {
            throw new IllegalArgumentException("Version does not belong to dashboard: " + dashboardId);
        }
        if (hasRealBuild(dashboard)) {
            snapshotVersion(dashboard, "RESTORE");
        }
        String restoredConfig = version.getDashboardConfig();
        dashboard.setDashboardConfig(restoredConfig);
        SavedDashboard saved = savedDashboardRepository.save(dashboard);
        List<DashboardVersion> duplicates = dashboardVersionRepository.findByDashboardIdOrderByCreatedAtDesc(dashboardId).stream()
            .filter(v -> v.getDashboardConfig().equals(restoredConfig))
            .toList();
        if (!duplicates.isEmpty()) {
            dashboardVersionRepository.deleteAll(duplicates);
        }
        return saved;
    }

    /**
     * Delete all dashboards for a connection
     */
    @Transactional
    public void deleteDashboardsByConnection(String connectionId) {
        log.info("Deleting all dashboards for connection: {}", connectionId);
        List<SavedDashboard> dashboards = savedDashboardRepository.findByConnectionIdOrderByCreatedAtDesc(connectionId);
        savedDashboardRepository.deleteAll(dashboards);
    }

    // ── Server-owned chat-turn persistence ──────────────────────────────────
    //
    // A dashboard generation turn used to be persisted only by the FRONTEND, in
    // response to receiving the SSE `done`/`chat` event — so closing or
    // reloading the tab before that event arrived silently discarded a turn the
    // backend had already finished computing. These four methods move
    // persistence into the backend code path itself (DashboardGenerationController),
    // which runs on a detached virtual thread that keeps going regardless of
    // whether the originating SSE client is still connected. Call order per
    // turn: beginGenerationTurn (before the slow agent work starts) → exactly
    // one of appendAgentReply / completeBuildTurn / appendErrorReply (when it
    // finishes).

    /**
     * Starts a chat turn: resolves (or creates) the target dashboard and
     * appends the user's message, synchronously and fast (before the caller
     * kicks off the slow agent work). Marking generationStatus=RUNNING here —
     * not after the agent finishes — is what lets a reload mid-generation see
     * "still working" instead of nothing at all.
     *
     * isFreshlyRunning below is check-then-act, but SavedDashboard.version
     * (@Version) is the real guard: save() is `UPDATE ... WHERE version=?`, so a
     * racing loser gets OptimisticLockingFailureException, not a double-append
     * (caught in DashboardGenerationController same as the IllegalStateException
     * below). Verified with concurrent requests: loser rejected, zero writes.
     */
    @Transactional
    public SavedDashboard beginGenerationTurn(UUID dashboardId, String connectionId, String prompt) {
        SavedDashboard dashboard;
        if (dashboardId != null) {
            dashboard = requireDashboard(dashboardId);
            if (!connectionId.equals(dashboard.getConnectionId())) {
                throw new IllegalArgumentException("Dashboard does not belong to connection: " + connectionId);
            }
            if (isFreshlyRunning(dashboard)) {
                throw new IllegalStateException("A generation is already running for this dashboard.");
            }
        } else {
            dashboard = new SavedDashboard();
            dashboard.setConnectionId(connectionId);
            dashboard.setName(deriveName(prompt));
            dashboard.setDescription("");
            dashboard.setDashboardConfig("{}");
            dashboard.setChatMessages("[]");
            dashboard.setIsFavorite(false);
        }
        List<Map<String, Object>> messages = parseMessages(dashboard.getChatMessages());
        messages.add(chatMessage("user", prompt));
        dashboard.setChatMessages(writeMessages(messages));
        dashboard.setGenerationStatus("RUNNING");
        dashboard.setGenerationStartedAt(LocalDateTime.now());
        return savedDashboardRepository.save(dashboard);
    }

    /** Turn finished as a plain chat reply (e.g. "hi") — no dashboard change. */
    @Transactional
    public SavedDashboard appendAgentReply(UUID dashboardId, String replyText) {
        SavedDashboard dashboard = requireDashboard(dashboardId);
        List<Map<String, Object>> messages = parseMessages(dashboard.getChatMessages());
        messages.add(chatMessage("agent", replyText));
        dashboard.setChatMessages(writeMessages(messages));
        return finishRunning(dashboard);
    }

    /** Turn finished as a real build — persists the artifact + its derived title. */
    @Transactional
    public SavedDashboard completeBuildTurn(UUID dashboardId, Map<String, Object> config) {
        SavedDashboard dashboard = requireDashboard(dashboardId);
        if (hasRealBuild(dashboard)) {
            snapshotVersion(dashboard, "AGENT_BUILD");
        }
        try {
            dashboard.setDashboardConfig(objectMapper.writeValueAsString(config));
        } catch (Exception e) {
            log.error("Failed to serialize dashboard config for {}", dashboardId, e);
        }
        Object title = config.get("title");
        if (title != null && !String.valueOf(title).isBlank()) {
            dashboard.setName(String.valueOf(title));
        }
        List<Map<String, Object>> messages = parseMessages(dashboard.getChatMessages());
        messages.add(chatMessage("agent", "Done — built and verified against your data. Saved as a draft — tell me what to change."));
        dashboard.setChatMessages(writeMessages(messages));
        return finishRunning(dashboard);
    }

    /** Turn finished as a real generation failure (not a client disconnect — see controller). */
    @Transactional
    public SavedDashboard appendErrorReply(UUID dashboardId, String errorText) {
        SavedDashboard dashboard = requireDashboard(dashboardId);
        List<Map<String, Object>> messages = parseMessages(dashboard.getChatMessages());
        Map<String, Object> msg = chatMessage("agent", "⚠ " + errorText);
        msg.put("error", true);
        messages.add(msg);
        dashboard.setChatMessages(writeMessages(messages));
        return finishRunning(dashboard);
    }

    private SavedDashboard finishRunning(SavedDashboard dashboard) {
        dashboard.setGenerationStatus("IDLE");
        dashboard.setGenerationStartedAt(null);
        return savedDashboardRepository.save(dashboard);
    }

    private boolean isFreshlyRunning(SavedDashboard dashboard) {
        return "RUNNING".equals(dashboard.getGenerationStatus())
            && dashboard.getGenerationStartedAt() != null
            && dashboard.getGenerationStartedAt().isAfter(LocalDateTime.now().minus(STALE_RUNNING_THRESHOLD));
    }

    private SavedDashboard requireDashboard(UUID id) {
        return savedDashboardRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Dashboard not found with id: " + id));
    }

    private static Map<String, Object> chatMessage(String role, String text) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("role", role);
        m.put("text", text);
        return m;
    }

    private static String deriveName(String prompt) {
        if (prompt == null || prompt.isBlank()) return "New dashboard";
        String trimmed = prompt.trim();
        return trimmed.length() > 80 ? trimmed.substring(0, 80) : trimmed;
    }

    private List<Map<String, Object>> parseMessages(String json) {
        if (json == null || json.isBlank()) return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() { });
        } catch (Exception e) {
            log.warn("Failed to parse chatMessages, starting fresh: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    private String writeMessages(List<Map<String, Object>> messages) {
        try {
            return objectMapper.writeValueAsString(messages);
        } catch (Exception e) {
            log.error("Failed to serialize chatMessages", e);
            return "[]";
        }
    }
}
