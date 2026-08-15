package com.dbaagent.controller;

import com.dbaagent.model.SavedDashboard;
import com.dbaagent.service.SavedDashboardService;
import com.dbaagent.service.security.AccessControlService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/saved-dashboards")
@Slf4j
public class SavedDashboardController {

    @Autowired
    private SavedDashboardService savedDashboardService;

    @Autowired
    private AccessControlService accessControlService;

    // Every write method below is load-then-save on a row a background generation
    // turn (SavedDashboardService.beginGenerationTurn etc.) may be writing at the
    // same time. Without this helper, the loser's raw Hibernate message
    // ("Unexpected row count... where id=? and version=?") leaked straight into
    // the API response as a 500 instead of a clean, retryable conflict.
    private static ResponseEntity<Map<String, Object>> conflict(OptimisticLockingFailureException e) {
        log.warn("Dashboard update lost a concurrent-write race: {}", e.getMessage());
        Map<String, Object> body = new HashMap<>();
        body.put("success", false);
        body.put("message", "This dashboard changed elsewhere just now — please retry.");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(body);
    }

    /** Publish this dashboard to the web (opt-in, revocable public link). */
    @PostMapping("/{id}/share")
    public ResponseEntity<Map<String, Object>> enableShare(@PathVariable UUID id) {
        try {
            SavedDashboard existing = savedDashboardService.getDashboardById(id)
                .orElseThrow(() -> new IllegalArgumentException("Dashboard not found"));
            accessControlService.assertCanReadConnectionContent(existing.getConnectionId());
            SavedDashboard d = savedDashboardService.enablePublicShare(id);
            return ResponseEntity.ok(Map.of("success", true,
                "shareToken", d.getShareToken(), "isPublic", true));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("success", false, "message", e.getMessage()));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (OptimisticLockingFailureException e) {
            return conflict(e);
        } catch (Exception e) {
            log.error("Error enabling dashboard share", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("success", false, "message", "Failed to enable sharing"));
        }
    }

    /** Set or clear the public link's password (blank/empty clears it). */
    @PutMapping("/{id}/share/password")
    public ResponseEntity<Map<String, Object>> setSharePassword(@PathVariable UUID id, @RequestBody Map<String, String> body) {
        try {
            SavedDashboard existing = savedDashboardService.getDashboardById(id)
                .orElseThrow(() -> new IllegalArgumentException("Dashboard not found"));
            accessControlService.assertCanReadConnectionContent(existing.getConnectionId());
            SavedDashboard d = savedDashboardService.setSharePassword(id, body == null ? null : body.get("password"));
            return ResponseEntity.ok(Map.of("success", true, "sharePasswordSet", d.isSharePasswordSet()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("success", false, "message", e.getMessage()));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (OptimisticLockingFailureException e) {
            return conflict(e);
        } catch (Exception e) {
            log.error("Error setting dashboard share password", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("success", false, "message", "Failed to update password"));
        }
    }

    /** Revoke the public link. */
    @DeleteMapping("/{id}/share")
    public ResponseEntity<Map<String, Object>> disableShare(@PathVariable UUID id) {
        try {
            SavedDashboard existing = savedDashboardService.getDashboardById(id)
                .orElseThrow(() -> new IllegalArgumentException("Dashboard not found"));
            accessControlService.assertCanReadConnectionContent(existing.getConnectionId());
            savedDashboardService.disablePublicShare(id);
            return ResponseEntity.ok(Map.of("success", true, "isPublic", false));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("success", false, "message", e.getMessage()));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (OptimisticLockingFailureException e) {
            return conflict(e);
        } catch (Exception e) {
            log.error("Error disabling dashboard share", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("success", false, "message", "Failed to disable sharing"));
        }
    }

    /**
     * Create a new saved dashboard
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createDashboard(@RequestBody SavedDashboard savedDashboard) {
        try {
            log.info("Creating saved dashboard: {} for connection: {}", savedDashboard.getName(), savedDashboard.getConnectionId());

            SavedDashboard created = savedDashboardService.saveDashboard(savedDashboard);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("savedDashboard", created);
            response.put("message", "Dashboard saved successfully");

            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error creating saved dashboard", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to save dashboard: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Get all saved dashboards for a connection
     */
    @GetMapping("/connection/{connectionId}")
    public ResponseEntity<Map<String, Object>> getDashboardsByConnection(@PathVariable String connectionId) {
        try {
            log.info("Fetching saved dashboards for connection: {}", connectionId);

            List<SavedDashboard> dashboards = savedDashboardService.getDashboardsByConnection(connectionId);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("dashboards", dashboards);
            response.put("count", dashboards.size());

            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching saved dashboards", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to fetch dashboards: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Get a specific saved dashboard by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getDashboardById(@PathVariable UUID id) {
        try {
            log.info("Fetching saved dashboard: {}", id);

            return savedDashboardService.getDashboardById(id)
                    .map(dashboard -> {
                        Map<String, Object> response = new HashMap<>();
                        response.put("success", true);
                        response.put("savedDashboard", dashboard);
                        return ResponseEntity.ok(response);
                    })
                    .orElseGet(() -> {
                        Map<String, Object> errorResponse = new HashMap<>();
                        errorResponse.put("success", false);
                        errorResponse.put("message", "Dashboard not found");
                        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
                    });
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching saved dashboard", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to fetch dashboard: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Update a saved dashboard
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateDashboard(@PathVariable UUID id, @RequestBody SavedDashboard updates) {
        try {
            log.info("Updating saved dashboard: {}", id);

            SavedDashboard updated = savedDashboardService.updateDashboard(id, updates);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("savedDashboard", updated);
            response.put("message", "Dashboard updated successfully");

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.error("Dashboard not found: {}", id);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (OptimisticLockingFailureException e) {
            return conflict(e);
        } catch (Exception e) {
            log.error("Error updating saved dashboard", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to update dashboard: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Delete a saved dashboard
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteDashboard(@PathVariable UUID id) {
        try {
            log.info("Deleting saved dashboard: {}", id);

            savedDashboardService.deleteDashboard(id);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Dashboard deleted successfully");

            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error deleting saved dashboard", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to delete dashboard: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Toggle favorite status
     */
    @PostMapping("/{id}/favorite")
    public ResponseEntity<Map<String, Object>> toggleFavorite(@PathVariable UUID id) {
        try {
            log.info("Toggling favorite for dashboard: {}", id);

            SavedDashboard updated = savedDashboardService.toggleFavorite(id);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("savedDashboard", updated);
            response.put("message", "Favorite status updated");

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.error("Dashboard not found: {}", id);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (OptimisticLockingFailureException e) {
            return conflict(e);
        } catch (Exception e) {
            log.error("Error toggling favorite", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to update favorite status: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Get favorite dashboards for a connection
     */
    @GetMapping("/connection/{connectionId}/favorites")
    public ResponseEntity<Map<String, Object>> getFavoriteDashboards(@PathVariable String connectionId) {
        try {
            log.info("Fetching favorite dashboards for connection: {}", connectionId);

            List<SavedDashboard> dashboards = savedDashboardService.getFavoriteDashboards(connectionId);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("dashboards", dashboards);
            response.put("count", dashboards.size());

            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching favorite dashboards", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to fetch favorite dashboards: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Get dashboards by folder
     */
    @GetMapping("/connection/{connectionId}/folder/{folder}")
    public ResponseEntity<Map<String, Object>> getDashboardsByFolder(
            @PathVariable String connectionId,
            @PathVariable String folder) {
        try {
            log.info("Fetching dashboards in folder: {} for connection: {}", folder, connectionId);

            List<SavedDashboard> dashboards = savedDashboardService.getDashboardsByFolder(connectionId, folder);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("dashboards", dashboards);
            response.put("count", dashboards.size());

            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching dashboards by folder", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to fetch dashboards: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Search dashboards
     */
    @GetMapping("/connection/{connectionId}/search")
    public ResponseEntity<Map<String, Object>> searchDashboards(
            @PathVariable String connectionId,
            @RequestParam String q) {
        try {
            log.info("Searching dashboards for connection: {} with term: {}", connectionId, q);

            List<SavedDashboard> dashboards = savedDashboardService.searchDashboards(connectionId, q);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("dashboards", dashboards);
            response.put("count", dashboards.size());

            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error searching dashboards", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to search dashboards: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Get distinct folders for a connection
     */
    @GetMapping("/connection/{connectionId}/folders")
    public ResponseEntity<Map<String, Object>> getFolders(@PathVariable String connectionId) {
        try {
            log.info("Fetching folders for connection: {}", connectionId);

            List<String> folders = savedDashboardService.getFolders(connectionId);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("folders", folders);
            response.put("count", folders.size());

            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching folders", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to fetch folders: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
}
