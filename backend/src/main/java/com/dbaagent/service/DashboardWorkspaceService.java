package com.dbaagent.service;

import com.dbaagent.model.DashboardWorkspace;
import com.dbaagent.model.DashboardWorkspaceMember;
import com.dbaagent.model.DashboardWorkspaceRole;
import com.dbaagent.model.SavedDashboard;
import com.dbaagent.repository.DashboardWorkspaceMemberRepository;
import com.dbaagent.repository.DashboardWorkspaceRepository;
import com.dbaagent.repository.SavedDashboardRepository;
import com.dbaagent.service.security.AccessControlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.util.*;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Dashboard workspaces: named groups of dashboards with their own member lists.
 *
 * <p>The access rule is an AND, deliberately. Connection access is checked first and
 * unchanged — {@code AccessControlService.assertCanReadConnectionContent} — and workspace
 * membership is an <em>additional</em> gate on top. Membership therefore can only ever
 * narrow what a user sees, never widen it, so introducing a workspace cannot hand anyone
 * access to a connection they were not already granted.
 *
 * <p>Admins bypass the membership half, matching how they already bypass connection
 * grants ({@code isCurrentUserAdmin}). Under "View as", {@code ImpersonationContext} has
 * already replaced the principal, so membership resolves as the target user and an admin
 * viewing as someone else correctly sees only that user's workspaces.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardWorkspaceService {

    private final DashboardWorkspaceRepository workspaceRepository;
    private final DashboardWorkspaceMemberRepository memberRepository;
    private final SavedDashboardRepository savedDashboardRepository;
    private final AccessControlService accessControlService;

    // ==================== Queries ====================

    /** Workspaces on this connection that the caller can see. */
    public List<DashboardWorkspace> listVisibleWorkspaces(String connectionId) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        List<DashboardWorkspace> all = workspaceRepository.findByConnectionIdOrderByNameAsc(connectionId);
        if (accessControlService.isCurrentUserAdmin()) {
            return all;
        }
        Set<UUID> memberOf = memberWorkspaceIds(currentUsername(), all);
        return all.stream()
            .filter(ws -> memberOf.contains(ws.getId()))
            .collect(Collectors.toList());
    }

    /**
     * The workspace ids the caller may read on this connection, plus whether the caller
     * sees everything. Callers filtering a dashboard list use this to avoid a membership
     * query per dashboard.
     */
    public WorkspaceVisibility resolveVisibility(String connectionId) {
        if (accessControlService.isCurrentUserAdmin()) {
            return new WorkspaceVisibility(true, Set.of());
        }
        List<DashboardWorkspace> all = workspaceRepository.findByConnectionIdOrderByNameAsc(connectionId);
        return new WorkspaceVisibility(false, memberWorkspaceIds(currentUsername(), all));
    }

    public DashboardWorkspace getWorkspace(UUID workspaceId) {
        DashboardWorkspace workspace = workspaceRepository.findById(workspaceId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Workspace not found"));
        assertCanView(workspace);
        return workspace;
    }

    public List<DashboardWorkspaceMember> listMembers(UUID workspaceId) {
        DashboardWorkspace workspace = workspaceRepository.findById(workspaceId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Workspace not found"));
        assertCanView(workspace);
        return memberRepository.findByWorkspaceIdOrderByUsernameAsc(workspaceId);
    }

    /** Dashboards in a workspace the caller can see. */
    public List<SavedDashboard> listDashboards(UUID workspaceId) {
        DashboardWorkspace workspace = getWorkspace(workspaceId);
        return savedDashboardRepository.findByWorkspaceIdOrderByUpdatedAtDesc(workspace.getId());
    }

    // ==================== Mutations ====================

    @Transactional
    public DashboardWorkspace createWorkspace(String connectionId, String name, String description, String color) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        String cleanName = requireName(name);

        workspaceRepository.findByConnectionIdAndNameIgnoreCase(connectionId, cleanName).ifPresent(existing -> {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                "A workspace named \"" + cleanName + "\" already exists on this connection");
        });

        String creator = accessControlService.requireCurrentUsername();

        DashboardWorkspace workspace = new DashboardWorkspace();
        workspace.setConnectionId(connectionId);
        workspace.setName(cleanName);
        workspace.setDescription(trimToNull(description));
        workspace.setColor(trimToNull(color));
        workspace.setCreatedBy(creator);
        DashboardWorkspace saved = workspaceRepository.save(workspace);

        // The creator is a MANAGER member outright rather than relying on a
        // createdBy check at read time, so ownership survives if the workspace is
        // later handed to someone else.
        DashboardWorkspaceMember owner = new DashboardWorkspaceMember();
        owner.setWorkspaceId(saved.getId());
        owner.setUsername(creator);
        owner.setWorkspaceRole(DashboardWorkspaceRole.MANAGER);
        owner.setAddedBy(creator);
        memberRepository.save(owner);

        log.info("Dashboard workspace created: id={}, connection={}, by={}", saved.getId(), connectionId, creator);
        return saved;
    }

    @Transactional
    public DashboardWorkspace updateWorkspace(UUID workspaceId, String name, String description, String color) {
        DashboardWorkspace workspace = workspaceRepository.findById(workspaceId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Workspace not found"));
        assertCanManage(workspace);

        if (name != null && !name.isBlank()) {
            String cleanName = requireName(name);
            workspaceRepository.findByConnectionIdAndNameIgnoreCase(workspace.getConnectionId(), cleanName)
                .filter(other -> !other.getId().equals(workspaceId))
                .ifPresent(other -> {
                    throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                        "A workspace named \"" + cleanName + "\" already exists on this connection");
                });
            workspace.setName(cleanName);
        }
        // Null means "field omitted"; blank is the explicit clear signal, matching the
        // convention updateDashboard/setSharePassword already use.
        if (description != null) {
            workspace.setDescription(trimToNull(description));
        }
        if (color != null) {
            workspace.setColor(trimToNull(color));
        }
        return workspaceRepository.save(workspace);
    }

    @Transactional
    public void deleteWorkspace(UUID workspaceId) {
        DashboardWorkspace workspace = workspaceRepository.findById(workspaceId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Workspace not found"));
        assertCanManage(workspace);

        // Dashboards outlive their workspace: detach them rather than cascading a delete.
        // Deleting a grouping must never destroy the things grouped.
        List<SavedDashboard> dashboards = savedDashboardRepository.findByWorkspaceIdOrderByUpdatedAtDesc(workspaceId);
        for (SavedDashboard dashboard : dashboards) {
            dashboard.setWorkspaceId(null);
        }
        if (!dashboards.isEmpty()) {
            savedDashboardRepository.saveAll(dashboards);
        }

        memberRepository.deleteByWorkspaceId(workspaceId);
        workspaceRepository.delete(workspace);
        log.info("Dashboard workspace deleted: id={}, detached {} dashboard(s)", workspaceId, dashboards.size());
    }

    @Transactional
    public DashboardWorkspaceMember addMember(UUID workspaceId, String username, String workspaceRole) {
        DashboardWorkspace workspace = workspaceRepository.findById(workspaceId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Workspace not found"));
        assertCanManage(workspace);

        String cleanUsername = username == null ? "" : username.trim();
        if (cleanUsername.isEmpty()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "Username is required");
        }

        DashboardWorkspaceMember member = memberRepository
            .findByWorkspaceIdAndUsernameIgnoreCase(workspaceId, cleanUsername)
            .orElseGet(() -> {
                DashboardWorkspaceMember fresh = new DashboardWorkspaceMember();
                fresh.setWorkspaceId(workspaceId);
                fresh.setUsername(cleanUsername);
                return fresh;
            });
        member.setWorkspaceRole(DashboardWorkspaceRole.fromString(workspaceRole));
        member.setAddedBy(accessControlService.requireCurrentUsername());
        return memberRepository.save(member);
    }

    @Transactional
    public void removeMember(UUID workspaceId, String username) {
        DashboardWorkspace workspace = workspaceRepository.findById(workspaceId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Workspace not found"));
        assertCanManage(workspace);

        List<DashboardWorkspaceMember> members = memberRepository.findByWorkspaceIdOrderByUsernameAsc(workspaceId);
        boolean removingLastManager = members.stream()
            .filter(m -> m.getWorkspaceRole() == DashboardWorkspaceRole.MANAGER)
            .allMatch(m -> m.getUsername().equalsIgnoreCase(username));
        boolean anyManager = members.stream().anyMatch(m -> m.getWorkspaceRole() == DashboardWorkspaceRole.MANAGER);
        if (anyManager && removingLastManager) {
            // A workspace with no manager can never be changed again by anyone but an
            // admin — refuse rather than create that dead end.
            throw new ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                "Cannot remove the last manager. Promote another member first.");
        }
        memberRepository.deleteByWorkspaceIdAndUsernameIgnoreCase(workspaceId, username);
    }

    /**
     * Move a dashboard into a workspace, or out of one when {@code workspaceId} is null.
     *
     * <p>Both ends are checked: the caller must be able to manage the dashboard's
     * connection content, and must be able to manage the destination workspace. Without
     * the second check, anyone could push a dashboard into a workspace they cannot see.
     */
    @Transactional
    public SavedDashboard moveDashboard(UUID dashboardId, UUID workspaceId) {
        SavedDashboard dashboard = savedDashboardRepository.findById(dashboardId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Dashboard not found"));
        accessControlService.assertCanManageConnectionContent(dashboard.getConnectionId());
        assertCanReadDashboard(dashboard);

        if (workspaceId == null) {
            dashboard.setWorkspaceId(null);
            return savedDashboardRepository.save(dashboard);
        }

        DashboardWorkspace target = workspaceRepository.findById(workspaceId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Workspace not found"));
        if (!target.getConnectionId().equals(dashboard.getConnectionId())) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                "Workspace belongs to a different connection");
        }
        assertCanManage(target);

        dashboard.setWorkspaceId(workspaceId);
        return savedDashboardRepository.save(dashboard);
    }

    // ==================== Access checks ====================

    /** True when the caller may read a dashboard given its workspace, if any. */
    public boolean canReadDashboard(SavedDashboard dashboard) {
        if (dashboard == null) {
            return false;
        }
        UUID workspaceId = dashboard.getWorkspaceId();
        if (workspaceId == null) {
            return true;
        }
        if (accessControlService.isCurrentUserAdmin()) {
            return true;
        }
        return memberRepository
            .findByWorkspaceIdAndUsernameIgnoreCase(workspaceId, currentUsername())
            .isPresent();
    }

    /**
     * Assert workspace membership for a dashboard the caller already passed the
     * connection check on. Reports 404, not 403: a user outside the workspace should not
     * learn that a dashboard with that id exists.
     */
    public void assertCanReadDashboard(SavedDashboard dashboard) {
        if (!canReadDashboard(dashboard)) {
            throw new ResponseStatusException(NOT_FOUND, "Dashboard not found");
        }
    }

    /** Filter a dashboard list to what the caller may see, in one membership query. */
    public List<SavedDashboard> filterReadable(List<SavedDashboard> dashboards) {
        if (dashboards == null || dashboards.isEmpty()) {
            return List.of();
        }
        if (accessControlService.isCurrentUserAdmin()) {
            return dashboards;
        }
        Set<UUID> workspaceIds = dashboards.stream()
            .map(SavedDashboard::getWorkspaceId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        if (workspaceIds.isEmpty()) {
            return dashboards;
        }
        Set<UUID> memberOf = memberRepository
            .findByUsernameIgnoreCaseAndWorkspaceIdIn(currentUsername(), workspaceIds)
            .stream()
            .map(DashboardWorkspaceMember::getWorkspaceId)
            .collect(Collectors.toSet());
        return dashboards.stream()
            .filter(d -> d.getWorkspaceId() == null || memberOf.contains(d.getWorkspaceId()))
            .collect(Collectors.toList());
    }

    private void assertCanView(DashboardWorkspace workspace) {
        accessControlService.assertCanReadConnectionContent(workspace.getConnectionId());
        if (accessControlService.isCurrentUserAdmin()) {
            return;
        }
        memberRepository.findByWorkspaceIdAndUsernameIgnoreCase(workspace.getId(), currentUsername())
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Workspace not found"));
    }

    private void assertCanManage(DashboardWorkspace workspace) {
        accessControlService.assertCanReadConnectionContent(workspace.getConnectionId());
        if (accessControlService.isCurrentUserAdmin()) {
            return;
        }
        DashboardWorkspaceMember member = memberRepository
            .findByWorkspaceIdAndUsernameIgnoreCase(workspace.getId(), currentUsername())
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "Workspace not found"));
        if (!member.getWorkspaceRole().canManage()) {
            throw new ResponseStatusException(FORBIDDEN, "Only a workspace manager can change this workspace");
        }
    }

    private Set<UUID> memberWorkspaceIds(String username, List<DashboardWorkspace> candidates) {
        if (candidates.isEmpty()) {
            return Set.of();
        }
        Set<UUID> ids = candidates.stream().map(DashboardWorkspace::getId).collect(Collectors.toSet());
        return memberRepository.findByUsernameIgnoreCaseAndWorkspaceIdIn(username, ids).stream()
            .map(DashboardWorkspaceMember::getWorkspaceId)
            .collect(Collectors.toSet());
    }

    private String currentUsername() {
        return accessControlService.requireCurrentUsername();
    }

    private static String requireName(String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                "Workspace name is required");
        }
        if (clean.length() > 128) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST,
                "Workspace name must be 128 characters or fewer");
        }
        return clean;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    /** Whether the caller sees every workspace, and if not, which ones they belong to. */
    public record WorkspaceVisibility(boolean seesAll, Set<UUID> memberWorkspaceIds) {
        public boolean canSee(UUID workspaceId) {
            return workspaceId == null || seesAll || memberWorkspaceIds.contains(workspaceId);
        }
    }
}
