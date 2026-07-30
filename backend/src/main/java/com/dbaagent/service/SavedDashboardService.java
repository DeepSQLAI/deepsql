package com.dbaagent.service;

import com.dbaagent.model.SavedDashboard;
import com.dbaagent.repository.SavedDashboardRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
@Slf4j
public class SavedDashboardService {

    private static final SecureRandom RANDOM = new SecureRandom();

    @Autowired
    private SavedDashboardRepository savedDashboardRepository;

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
        if (updates.getDashboardConfig() != null) {
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
        if (updates.getFolder() != null) {
            existing.setFolder(updates.getFolder());
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

    /**
     * Delete all dashboards for a connection
     */
    @Transactional
    public void deleteDashboardsByConnection(String connectionId) {
        log.info("Deleting all dashboards for connection: {}", connectionId);
        List<SavedDashboard> dashboards = savedDashboardRepository.findByConnectionIdOrderByCreatedAtDesc(connectionId);
        savedDashboardRepository.deleteAll(dashboards);
    }
}
