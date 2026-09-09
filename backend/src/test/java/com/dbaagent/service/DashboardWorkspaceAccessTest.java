package com.dbaagent.service;

import com.dbaagent.model.DashboardWorkspace;
import com.dbaagent.model.DashboardWorkspaceMember;
import com.dbaagent.model.DashboardWorkspaceRole;
import com.dbaagent.model.SavedDashboard;
import com.dbaagent.repository.DashboardWorkspaceMemberRepository;
import com.dbaagent.repository.DashboardWorkspaceRepository;
import com.dbaagent.repository.SavedDashboardRepository;
import com.dbaagent.service.security.AccessControlService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The workspace access rule: connection access AND workspace membership.
 *
 * <p>The load-bearing property is that membership can only ever <em>narrow</em> access.
 * A dashboard with no workspace must stay visible to everyone who could already see it,
 * and a dashboard in a workspace must be invisible to a non-member even though that user
 * still has full read access to the connection.
 */
class DashboardWorkspaceAccessTest {

    private static final String CONNECTION = "conn-1";
    private static final UUID WORKSPACE = UUID.randomUUID();

    private DashboardWorkspaceRepository workspaceRepository;
    private DashboardWorkspaceMemberRepository memberRepository;
    private SavedDashboardRepository savedDashboardRepository;
    private AccessControlService accessControlService;
    private DashboardWorkspaceService service;

    @BeforeEach
    void setUp() {
        workspaceRepository = mock(DashboardWorkspaceRepository.class);
        memberRepository = mock(DashboardWorkspaceMemberRepository.class);
        savedDashboardRepository = mock(SavedDashboardRepository.class);
        accessControlService = mock(AccessControlService.class);

        when(accessControlService.requireCurrentUsername()).thenReturn("analyst");
        when(accessControlService.isCurrentUserAdmin()).thenReturn(false);

        service = new DashboardWorkspaceService(
            workspaceRepository, memberRepository, savedDashboardRepository, accessControlService);
    }

    private static SavedDashboard dashboard(UUID workspaceId) {
        SavedDashboard d = new SavedDashboard();
        d.setId(UUID.randomUUID());
        d.setConnectionId(CONNECTION);
        d.setWorkspaceId(workspaceId);
        return d;
    }

    @Test
    @DisplayName("A dashboard with no workspace stays readable — workspaces never widen or narrow the ungrouped case")
    void ungroupedDashboardIsReadable() {
        assertThat(service.canReadDashboard(dashboard(null))).isTrue();
    }

    @Test
    @DisplayName("A non-member cannot read a dashboard inside a workspace, despite connection access")
    void nonMemberCannotReadGroupedDashboard() {
        when(memberRepository.findByWorkspaceIdAndUsernameIgnoreCase(WORKSPACE, "analyst"))
            .thenReturn(Optional.empty());

        SavedDashboard grouped = dashboard(WORKSPACE);

        assertThat(service.canReadDashboard(grouped)).isFalse();
        // 404, not 403: a non-member must not learn the dashboard exists.
        assertThatThrownBy(() -> service.assertCanReadDashboard(grouped))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("404");
    }

    @Test
    @DisplayName("A member can read a dashboard inside their workspace")
    void memberCanReadGroupedDashboard() {
        DashboardWorkspaceMember member = new DashboardWorkspaceMember();
        member.setWorkspaceId(WORKSPACE);
        member.setUsername("analyst");
        member.setWorkspaceRole(DashboardWorkspaceRole.VIEWER);
        when(memberRepository.findByWorkspaceIdAndUsernameIgnoreCase(WORKSPACE, "analyst"))
            .thenReturn(Optional.of(member));

        assertThat(service.canReadDashboard(dashboard(WORKSPACE))).isTrue();
    }

    @Test
    @DisplayName("An admin reads every workspace without a membership row")
    void adminBypassesMembership() {
        when(accessControlService.isCurrentUserAdmin()).thenReturn(true);

        assertThat(service.canReadDashboard(dashboard(WORKSPACE))).isTrue();
        verify(memberRepository, never()).findByWorkspaceIdAndUsernameIgnoreCase(any(), anyString());
    }

    @Test
    @DisplayName("filterReadable keeps ungrouped dashboards and drops non-member workspaces")
    void filterReadableSplitsCorrectly() {
        UUID otherWorkspace = UUID.randomUUID();
        SavedDashboard ungrouped = dashboard(null);
        SavedDashboard mine = dashboard(WORKSPACE);
        SavedDashboard theirs = dashboard(otherWorkspace);

        DashboardWorkspaceMember member = new DashboardWorkspaceMember();
        member.setWorkspaceId(WORKSPACE);
        member.setUsername("analyst");
        when(memberRepository.findByUsernameIgnoreCaseAndWorkspaceIdIn(eq("analyst"), any()))
            .thenReturn(List.of(member));

        List<SavedDashboard> visible = service.filterReadable(List.of(ungrouped, mine, theirs));

        assertThat(visible).containsExactly(ungrouped, mine);
        assertThat(visible).doesNotContain(theirs);
    }

    @Test
    @DisplayName("A viewer cannot rename the workspace; only a manager can")
    void viewerCannotManageWorkspace() {
        DashboardWorkspace workspace = new DashboardWorkspace();
        workspace.setId(WORKSPACE);
        workspace.setConnectionId(CONNECTION);
        workspace.setName("Finance");
        when(workspaceRepository.findById(WORKSPACE)).thenReturn(Optional.of(workspace));

        DashboardWorkspaceMember viewer = new DashboardWorkspaceMember();
        viewer.setWorkspaceId(WORKSPACE);
        viewer.setUsername("analyst");
        viewer.setWorkspaceRole(DashboardWorkspaceRole.VIEWER);
        when(memberRepository.findByWorkspaceIdAndUsernameIgnoreCase(WORKSPACE, "analyst"))
            .thenReturn(Optional.of(viewer));

        assertThatThrownBy(() -> service.updateWorkspace(WORKSPACE, "Renamed", null, null))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("403");
    }

    @Test
    @DisplayName("Deleting a workspace detaches its dashboards instead of deleting them")
    void deleteDetachesDashboards() {
        when(accessControlService.isCurrentUserAdmin()).thenReturn(true);
        DashboardWorkspace workspace = new DashboardWorkspace();
        workspace.setId(WORKSPACE);
        workspace.setConnectionId(CONNECTION);
        when(workspaceRepository.findById(WORKSPACE)).thenReturn(Optional.of(workspace));

        SavedDashboard grouped = dashboard(WORKSPACE);
        when(savedDashboardRepository.findByWorkspaceIdOrderByUpdatedAtDesc(WORKSPACE))
            .thenReturn(List.of(grouped));

        service.deleteWorkspace(WORKSPACE);

        // The dashboard survives, merely ungrouped. Deleting a grouping must never
        // destroy the things grouped.
        assertThat(grouped.getWorkspaceId()).isNull();
        verify(savedDashboardRepository).saveAll(any());
        verify(savedDashboardRepository, never()).delete(any());
        verify(workspaceRepository).delete(workspace);
    }

    @Test
    @DisplayName("Removing the last manager is refused so the workspace cannot be orphaned")
    void cannotRemoveLastManager() {
        when(accessControlService.isCurrentUserAdmin()).thenReturn(true);
        DashboardWorkspace workspace = new DashboardWorkspace();
        workspace.setId(WORKSPACE);
        workspace.setConnectionId(CONNECTION);
        when(workspaceRepository.findById(WORKSPACE)).thenReturn(Optional.of(workspace));

        DashboardWorkspaceMember onlyManager = new DashboardWorkspaceMember();
        onlyManager.setWorkspaceId(WORKSPACE);
        onlyManager.setUsername("owner");
        onlyManager.setWorkspaceRole(DashboardWorkspaceRole.MANAGER);
        when(memberRepository.findByWorkspaceIdOrderByUsernameAsc(WORKSPACE))
            .thenReturn(List.of(onlyManager));

        assertThatThrownBy(() -> service.removeMember(WORKSPACE, "owner"))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("409");
    }

    @Test
    @DisplayName("assertCanAssignInto requires MANAGER, not mere membership")
    void assignIntoRequiresManager() {
        // Creating a dashboard into a workspace used to call getWorkspace(), which asserts
        // only visibility — so a VIEWER could push dashboards into a workspace while
        // moveDashboard() required MANAGER for the same effect.
        DashboardWorkspace workspace = new DashboardWorkspace();
        workspace.setId(WORKSPACE);
        workspace.setConnectionId(CONNECTION);
        when(workspaceRepository.findById(WORKSPACE)).thenReturn(Optional.of(workspace));

        DashboardWorkspaceMember viewer = new DashboardWorkspaceMember();
        viewer.setWorkspaceId(WORKSPACE);
        viewer.setUsername("analyst");
        viewer.setWorkspaceRole(DashboardWorkspaceRole.VIEWER);
        when(memberRepository.findByWorkspaceIdAndUsernameIgnoreCase(WORKSPACE, "analyst"))
            .thenReturn(Optional.of(viewer));

        assertThatThrownBy(() -> service.assertCanAssignInto(WORKSPACE))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("403");

        // A manager passes.
        viewer.setWorkspaceRole(DashboardWorkspaceRole.MANAGER);
        org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> service.assertCanAssignInto(WORKSPACE));
    }

    @Test
    @DisplayName("assertCanReadDashboard is what stops the favorite-toggle IDOR")
    void favoriteTogglePathIsGated() {
        // POST /saved-dashboards/{id}/favorite had no authorization at all: a non-member
        // could flip the flag on a workspace-restricted dashboard AND read the whole row
        // back from the 200 response, bypassing the 404 that hides it. The controller now
        // runs this same gate before toggling.
        when(memberRepository.findByWorkspaceIdAndUsernameIgnoreCase(WORKSPACE, "analyst"))
            .thenReturn(Optional.empty());

        SavedDashboard restricted = dashboard(WORKSPACE);

        assertThatThrownBy(() -> service.assertCanReadDashboard(restricted))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("404");
    }

    @Test
    @DisplayName("A dashboard cannot be moved into a workspace on a different connection")
    void cannotMoveAcrossConnections() {
        when(accessControlService.isCurrentUserAdmin()).thenReturn(true);
        SavedDashboard d = dashboard(null);
        when(savedDashboardRepository.findById(d.getId())).thenReturn(Optional.of(d));

        DashboardWorkspace foreign = new DashboardWorkspace();
        foreign.setId(WORKSPACE);
        foreign.setConnectionId("some-other-connection");
        when(workspaceRepository.findById(WORKSPACE)).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.moveDashboard(d.getId(), WORKSPACE))
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("400");
    }
}
