package com.dbaagent.controller;

import com.dbaagent.model.DashboardWorkspace;
import com.dbaagent.model.DashboardWorkspaceMember;
import com.dbaagent.model.SavedDashboard;
import com.dbaagent.repository.SavedDashboardRepository;
import com.dbaagent.service.DashboardWorkspaceService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;

/**
 * Dashboard workspaces: grouping dashboards with their own member list.
 *
 * <pre>
 *   GET    /dashboard-workspaces/connection/{connectionId}   workspaces I can see
 *   POST   /dashboard-workspaces                             create
 *   GET    /dashboard-workspaces/{id}                        one workspace
 *   PUT    /dashboard-workspaces/{id}                        rename / recolour
 *   DELETE /dashboard-workspaces/{id}                        delete (detaches dashboards)
 *   GET    /dashboard-workspaces/{id}/dashboards             dashboards inside it
 *   GET    /dashboard-workspaces/{id}/members                member list
 *   POST   /dashboard-workspaces/{id}/members                add or change a member
 *   DELETE /dashboard-workspaces/{id}/members/{username}     remove a member
 *   PUT    /dashboard-workspaces/dashboards/{dashboardId}    move a dashboard in/out
 * </pre>
 *
 * <p>Every method delegates its access check to {@link DashboardWorkspaceService}, which
 * asserts connection access first and workspace membership second. As elsewhere in this
 * codebase there is no filter doing this for you — a new endpoint here must call the
 * service, never the repositories directly.
 */
@RestController
@RequestMapping("/dashboard-workspaces")
@RequiredArgsConstructor
@Slf4j
public class DashboardWorkspaceController {

    private final DashboardWorkspaceService workspaceService;
    private final SavedDashboardRepository savedDashboardRepository;

    @GetMapping("/connection/{connectionId}")
    public ResponseEntity<?> listWorkspaces(@PathVariable String connectionId) {
        try {
            List<DashboardWorkspace> workspaces = workspaceService.listVisibleWorkspaces(connectionId);
            return ResponseEntity.ok(workspaces.stream().map(this::describe).toList());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error listing dashboard workspaces", e);
            return failure("Failed to load workspaces");
        }
    }

    @PostMapping
    public ResponseEntity<?> createWorkspace(@RequestBody Map<String, Object> body) {
        try {
            String connectionId = asString(body.get("connectionId"));
            if (connectionId == null) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "connectionId is required"));
            }
            DashboardWorkspace workspace = workspaceService.createWorkspace(
                connectionId,
                asString(body.get("name")),
                asString(body.get("description")),
                asString(body.get("color"))
            );
            return ResponseEntity.ok(describe(workspace));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error creating dashboard workspace", e);
            return failure("Failed to create workspace");
        }
    }

    @GetMapping("/{id}")
    public ResponseEntity<?> getWorkspace(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(describe(workspaceService.getWorkspace(id)));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error loading dashboard workspace", e);
            return failure("Failed to load workspace");
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateWorkspace(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        try {
            DashboardWorkspace workspace = workspaceService.updateWorkspace(
                id,
                asString(body.get("name")),
                // Distinguish "omitted" from "cleared": a present-but-blank value clears
                // the field, matching updateDashboard's convention.
                body.containsKey("description") ? String.valueOf(Objects.toString(body.get("description"), "")) : null,
                body.containsKey("color") ? String.valueOf(Objects.toString(body.get("color"), "")) : null
            );
            return ResponseEntity.ok(describe(workspace));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error updating dashboard workspace", e);
            return failure("Failed to update workspace");
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteWorkspace(@PathVariable UUID id) {
        try {
            workspaceService.deleteWorkspace(id);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error deleting dashboard workspace", e);
            return failure("Failed to delete workspace");
        }
    }

    @GetMapping("/{id}/dashboards")
    public ResponseEntity<?> listDashboards(@PathVariable UUID id) {
        try {
            return ResponseEntity.ok(workspaceService.listDashboards(id));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error listing workspace dashboards", e);
            return failure("Failed to load dashboards");
        }
    }

    @GetMapping("/{id}/members")
    public ResponseEntity<?> listMembers(@PathVariable UUID id) {
        try {
            List<DashboardWorkspaceMember> members = workspaceService.listMembers(id);
            return ResponseEntity.ok(members.stream().map(this::describeMember).toList());
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error listing workspace members", e);
            return failure("Failed to load members");
        }
    }

    @PostMapping("/{id}/members")
    public ResponseEntity<?> addMember(@PathVariable UUID id, @RequestBody Map<String, Object> body) {
        try {
            DashboardWorkspaceMember member = workspaceService.addMember(
                id, asString(body.get("username")), asString(body.get("workspaceRole")));
            return ResponseEntity.ok(describeMember(member));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error adding workspace member", e);
            return failure("Failed to add member");
        }
    }

    @DeleteMapping("/{id}/members/{username}")
    public ResponseEntity<?> removeMember(@PathVariable UUID id, @PathVariable String username) {
        try {
            workspaceService.removeMember(id, username);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error removing workspace member", e);
            return failure("Failed to remove member");
        }
    }

    /** Move a dashboard into a workspace, or out of one with a null/blank workspaceId. */
    @PutMapping("/dashboards/{dashboardId}")
    public ResponseEntity<?> moveDashboard(@PathVariable UUID dashboardId, @RequestBody Map<String, Object> body) {
        try {
            String raw = asString(body.get("workspaceId"));
            UUID target = raw == null ? null : UUID.fromString(raw);
            SavedDashboard dashboard = workspaceService.moveDashboard(dashboardId, target);
            return ResponseEntity.ok(Map.of(
                "success", true,
                "dashboardId", dashboard.getId().toString(),
                "workspaceId", dashboard.getWorkspaceId() == null ? "" : dashboard.getWorkspaceId().toString()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Invalid workspace id"));
        } catch (ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error moving dashboard between workspaces", e);
            return failure("Failed to move dashboard");
        }
    }

    private Map<String, Object> describe(DashboardWorkspace workspace) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", workspace.getId().toString());
        body.put("connectionId", workspace.getConnectionId());
        body.put("name", workspace.getName());
        body.put("description", workspace.getDescription());
        body.put("color", workspace.getColor());
        body.put("createdBy", workspace.getCreatedBy());
        body.put("createdAt", workspace.getCreatedAt());
        body.put("updatedAt", workspace.getUpdatedAt());
        body.put("dashboardCount", savedDashboardRepository.countByWorkspaceId(workspace.getId()));
        return body;
    }

    private Map<String, Object> describeMember(DashboardWorkspaceMember member) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", member.getId().toString());
        body.put("username", member.getUsername());
        body.put("workspaceRole", member.getWorkspaceRole().name());
        body.put("addedBy", member.getAddedBy());
        body.put("createdAt", member.getCreatedAt());
        return body;
    }

    private static ResponseEntity<Map<String, Object>> failure(String message) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(Map.of("success", false, "message", message));
    }

    private static String asString(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }
}
