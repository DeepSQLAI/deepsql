package com.dbaagent.repository;

import com.dbaagent.model.DashboardWorkspaceMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DashboardWorkspaceMemberRepository extends JpaRepository<DashboardWorkspaceMember, UUID> {

    List<DashboardWorkspaceMember> findByWorkspaceIdOrderByUsernameAsc(UUID workspaceId);

    Optional<DashboardWorkspaceMember> findByWorkspaceIdAndUsernameIgnoreCase(UUID workspaceId, String username);

    List<DashboardWorkspaceMember> findByUsernameIgnoreCase(String username);

    /**
     * Memberships this user holds among the given workspaces. Used to resolve visibility
     * for a whole dashboard list in one query rather than one per dashboard.
     */
    List<DashboardWorkspaceMember> findByUsernameIgnoreCaseAndWorkspaceIdIn(String username, Collection<UUID> workspaceIds);

    // Derived deletes need their own transaction; a self-invoked @Transactional caller
    // does not supply one (Spring proxies are bypassed by this::), which is the same
    // trap McpTokenRepository.deleteByUserId documents.
    @Transactional
    void deleteByWorkspaceId(UUID workspaceId);

    @Transactional
    void deleteByWorkspaceIdAndUsernameIgnoreCase(UUID workspaceId, String username);
}
