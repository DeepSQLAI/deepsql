package com.dbaagent.controller;

import com.dbaagent.model.DashboardVersion;
import com.dbaagent.model.DashboardWorkspace;
import com.dbaagent.model.SavedDashboard;
import com.dbaagent.service.ConnectionChatAccessPolicyService;
import com.dbaagent.service.DashboardWorkspaceService;
import com.dbaagent.service.SavedDashboardService;
import com.dbaagent.service.security.AccessControlService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

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

    @Autowired
    private ConnectionChatAccessPolicyService connectionChatAccessPolicyService;

    @Autowired
    private DashboardWorkspaceService dashboardWorkspaceService;

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

    /**
     * A dashboard may only be created into a workspace the caller can actually manage.
     *
     * <p>This previously called {@code getWorkspace}, which asserts only *visibility* —
     * so a VIEWER could create dashboards into a workspace they merely belonged to,
     * despite {@code moveDashboard} requiring MANAGER for the same effect. The two paths
     * now agree.
     */
    private void assertWorkspaceAssignable(String connectionId, UUID workspaceId) {
        if (workspaceId == null) {
            return;
        }
        DashboardWorkspace workspace = dashboardWorkspaceService.getWorkspace(workspaceId);
        if (!workspace.getConnectionId().equals(connectionId)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "Workspace belongs to a different connection");
        }
        dashboardWorkspaceService.assertCanAssignInto(workspaceId);
    }

    /** Publish this dashboard to the web (opt-in, revocable public link). */
    @PostMapping("/{id}/share")
    public ResponseEntity<Map<String, Object>> enableShare(@PathVariable UUID id) {
        try {
            SavedDashboard existing = savedDashboardService.getDashboardById(id)
                .orElseThrow(() -> new IllegalArgumentException("Dashboard not found"));
            accessControlService.assertCanReadConnectionContent(existing.getConnectionId());
            dashboardWorkspaceService.assertCanReadDashboard(existing);
            if (connectionChatAccessPolicyService.hasActivePolicy(existing.getConnectionId())) {
                return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                    "success", false,
                    "errorCode", "POLICY_PUBLIC_SHARE_FORBIDDEN",
                    "message", "This connection has an active chat access policy, so the dashboard cannot be shared publicly."
                ));
            }
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
            dashboardWorkspaceService.assertCanReadDashboard(existing);
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
            dashboardWorkspaceService.assertCanReadDashboard(existing);
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
            accessControlService.assertCanManageConnectionContent(savedDashboard.getConnectionId());
            assertWorkspaceAssignable(savedDashboard.getConnectionId(), savedDashboard.getWorkspaceId());

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
            accessControlService.assertCanReadConnectionContent(connectionId);

            List<SavedDashboard> dashboards = dashboardWorkspaceService.filterReadable(
                savedDashboardService.getDashboardsByConnection(connectionId));

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
                        accessControlService.assertCanReadConnectionContent(dashboard.getConnectionId());
                        dashboardWorkspaceService.assertCanReadDashboard(dashboard);
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
            SavedDashboard existing = savedDashboardService.getDashboardById(id)
                .orElseThrow(() -> new IllegalArgumentException("Dashboard not found"));
            accessControlService.assertCanManageConnectionContent(existing.getConnectionId());
            dashboardWorkspaceService.assertCanReadDashboard(existing);

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
     * Duplicate a dashboard as a new, independent draft.
     */
    @PostMapping("/{id}/clone")
    public ResponseEntity<Map<String, Object>> cloneDashboard(@PathVariable UUID id) {
        try {
            SavedDashboard existing = savedDashboardService.getDashboardById(id)
                .orElseThrow(() -> new IllegalArgumentException("Dashboard not found"));
            accessControlService.assertCanManageConnectionContent(existing.getConnectionId());
            dashboardWorkspaceService.assertCanReadDashboard(existing);
            SavedDashboard clone = savedDashboardService.cloneDashboard(id);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("savedDashboard", clone);
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("success", false, "message", e.getMessage()));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error cloning dashboard", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("success", false, "message", "Failed to clone dashboard"));
        }
    }

    /**
     * List version history for a dashboard, most recent first.
     */
    @GetMapping("/{id}/versions")
    public ResponseEntity<Map<String, Object>> getVersionHistory(@PathVariable UUID id) {
        try {
            SavedDashboard existing = savedDashboardService.getDashboardById(id)
                .orElseThrow(() -> new IllegalArgumentException("Dashboard not found"));
            accessControlService.assertCanReadConnectionContent(existing.getConnectionId());
            dashboardWorkspaceService.assertCanReadDashboard(existing);
            List<DashboardVersion> versions = savedDashboardService.getVersionHistory(id);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("versions", versions);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("success", false, "message", e.getMessage()));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching dashboard version history", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("success", false, "message", "Failed to fetch version history"));
        }
    }

    /**
     * Restore a prior version as the dashboard's current config.
     */
    @PostMapping("/{id}/versions/{versionId}/restore")
    public ResponseEntity<Map<String, Object>> restoreVersion(@PathVariable UUID id, @PathVariable UUID versionId) {
        try {
            SavedDashboard existing = savedDashboardService.getDashboardById(id)
                .orElseThrow(() -> new IllegalArgumentException("Dashboard not found"));
            accessControlService.assertCanManageConnectionContent(existing.getConnectionId());
            dashboardWorkspaceService.assertCanReadDashboard(existing);
            SavedDashboard restored = savedDashboardService.restoreVersion(id, versionId);
            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("savedDashboard", restored);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("success", false, "message", e.getMessage()));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (OptimisticLockingFailureException e) {
            return conflict(e);
        } catch (Exception e) {
            log.error("Error restoring dashboard version", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("success", false, "message", "Failed to restore version"));
        }
    }

    /**
     * Delete a saved dashboard
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteDashboard(@PathVariable UUID id) {
        try {
            log.info("Deleting saved dashboard: {}", id);
            SavedDashboard existing = savedDashboardService.getDashboardById(id)
                .orElseThrow(() -> new IllegalArgumentException("Dashboard not found"));
            accessControlService.assertCanManageConnectionContent(existing.getConnectionId());
            dashboardWorkspaceService.assertCanReadDashboard(existing);

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
            // This handler had NO authorization at all, so a non-member could toggle the
            // favorite flag on a workspace-restricted dashboard and — worse — read the
            // whole row back from the 200 response (dashboardConfig and chatMessages
            // included), bypassing the very 404 that hides it. Load first, then apply the
            // same connection + workspace gate every other handler here uses.
            SavedDashboard existing = savedDashboardService.getDashboardById(id)
                .orElseThrow(() -> new IllegalArgumentException("Dashboard not found"));
            accessControlService.assertCanReadConnectionContent(existing.getConnectionId());
            dashboardWorkspaceService.assertCanReadDashboard(existing);

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
            accessControlService.assertCanReadConnectionContent(connectionId);

            List<SavedDashboard> dashboards = dashboardWorkspaceService.filterReadable(
                savedDashboardService.getFavoriteDashboards(connectionId));

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
            accessControlService.assertCanReadConnectionContent(connectionId);

            List<SavedDashboard> dashboards = dashboardWorkspaceService.filterReadable(
                savedDashboardService.getDashboardsByFolder(connectionId, folder));

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
            accessControlService.assertCanReadConnectionContent(connectionId);

            List<SavedDashboard> dashboards = dashboardWorkspaceService.filterReadable(
                savedDashboardService.searchDashboards(connectionId, q));

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
            accessControlService.assertCanReadConnectionContent(connectionId);

            // Derive folders from the dashboards this caller can actually see. The
            // repository query is a DISTINCT over every dashboard on the connection, so a
            // non-member learned the folder names of workspace-restricted dashboards —
            // a small leak, but through the same list the 404s are meant to hide.
            List<String> folders = dashboardWorkspaceService
                .filterReadable(savedDashboardService.getDashboardsByConnection(connectionId))
                .stream()
                .map(SavedDashboard::getFolder)
                .filter(f -> f != null && !f.isBlank())
                .distinct()
                .sorted()
                .toList();

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
