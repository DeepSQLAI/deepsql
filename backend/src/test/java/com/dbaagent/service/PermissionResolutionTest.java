package com.dbaagent.service;

import com.dbaagent.model.CustomRole;
import com.dbaagent.model.Permission;
import com.dbaagent.model.Role;
import com.dbaagent.model.RolePermissionOverride;
import com.dbaagent.repository.CustomRoleRepository;
import com.dbaagent.repository.RolePermissionOverrideRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Permission resolution across built-in and custom roles.
 *
 * <p>These assert the *product* rules the roles were specified with — which menus each
 * role can open — rather than restating the enum. A regression here is a user seeing a
 * section they should not, so each role's section set is pinned explicitly.
 */
class PermissionResolutionTest {

    private RolePermissionOverrideRepository overrideRepository;
    private CustomRoleRepository customRoleRepository;
    private PermissionService permissionService;

    @BeforeEach
    void setUp() {
        overrideRepository = mock(RolePermissionOverrideRepository.class);
        customRoleRepository = mock(CustomRoleRepository.class);
        when(overrideRepository.findByRoleIgnoreCase(anyString())).thenReturn(List.of());
        when(customRoleRepository.findByCodeIgnoreCase(anyString())).thenReturn(Optional.empty());
        when(customRoleRepository.findAllByOrderByNameAsc()).thenReturn(List.of());
        permissionService = new PermissionService(overrideRepository, customRoleRepository);
    }

    @Test
    @DisplayName("DBA sees every section and can manage connections, but not users")
    void dbaHasEverythingExceptUserManagement() {
        Set<Permission> permissions = permissionService.getEffectivePermissions(Role.DBA.name());

        assertThat(permissions).contains(
            Permission.VIEW_AGENT, Permission.VIEW_DASHBOARDS, Permission.VIEW_DIGEST,
            Permission.VIEW_BRAIN, Permission.VIEW_PERFORMANCE, Permission.VIEW_EDITOR,
            Permission.MANAGE_CONNECTIONS, Permission.MANAGE_SETTINGS);

        assertThat(permissions).doesNotContain(
            Permission.MANAGE_USERS, Permission.MANAGE_INVITE_CODES, Permission.MANAGE_PERMISSIONS);
    }

    @Test
    @DisplayName("Data Engineer gets Agent, Dashboards and Editor only")
    void dataEngineerSections() {
        Set<Permission> permissions = permissionService.getEffectivePermissions(Role.DATA_ENGINEER.name());

        assertThat(permissions).contains(
            Permission.VIEW_AGENT, Permission.VIEW_DASHBOARDS, Permission.VIEW_EDITOR);
        assertThat(permissions).doesNotContain(
            Permission.VIEW_DIGEST, Permission.VIEW_PERFORMANCE, Permission.VIEW_BRAIN,
            Permission.MANAGE_CONNECTIONS, Permission.MANAGE_USERS);
    }

    @Test
    @DisplayName("Developer gets Agent, Digest, Dashboards, Performance and Editor, but no connection settings")
    void developerSections() {
        Set<Permission> permissions = permissionService.getEffectivePermissions(Role.DEVELOPER.name());

        assertThat(permissions).contains(
            Permission.VIEW_AGENT, Permission.VIEW_DIGEST, Permission.VIEW_DASHBOARDS,
            Permission.VIEW_PERFORMANCE, Permission.VIEW_EDITOR);
        assertThat(permissions).doesNotContain(
            Permission.MANAGE_CONNECTIONS, Permission.MANAGE_SETTINGS, Permission.MANAGE_USERS);
    }

    @Test
    @DisplayName("Roles do not nest: each of Data Engineer and Developer has something the other lacks")
    void rolesAreNotAHierarchy() {
        Set<Permission> dataEngineer = permissionService.getEffectivePermissions(Role.DATA_ENGINEER.name());
        Set<Permission> developer = permissionService.getEffectivePermissions(Role.DEVELOPER.name());

        // Developer has Digest and Performance that Data Engineer lacks...
        assertThat(developer).contains(Permission.VIEW_DIGEST, Permission.VIEW_PERFORMANCE);
        assertThat(dataEngineer).doesNotContain(Permission.VIEW_DIGEST, Permission.VIEW_PERFORMANCE);
        // ...so neither is a superset of the other, which is why ordinal comparison had
        // to go. If this ever passes trivially, the role definitions drifted.
        assertThat(developer).isNotEqualTo(dataEngineer);
    }

    @Test
    @DisplayName("Admin holds every permission and ignores overrides")
    void adminIsAFixedPoint() {
        when(overrideRepository.findByRoleIgnoreCase("ADMIN")).thenReturn(List.of(
            new RolePermissionOverride("ADMIN", Permission.MANAGE_USERS, false, "tester")));

        Set<Permission> permissions = permissionService.getEffectivePermissions("ADMIN");

        assertThat(permissions).containsExactlyInAnyOrder(Permission.values());
        assertThat(permissions).contains(Permission.MANAGE_USERS);
    }

    @Test
    @DisplayName("A custom role grants exactly its ticked permissions")
    void customRoleGrantsItsOwnSet() {
        CustomRole analyst = new CustomRole();
        analyst.setCode("ANALYST");
        analyst.setName("Analyst");
        analyst.setPermissions(Set.of(Permission.VIEW_DASHBOARDS, Permission.USE_CHAT));
        when(customRoleRepository.findByCodeIgnoreCase("ANALYST")).thenReturn(Optional.of(analyst));

        Set<Permission> permissions = permissionService.getEffectivePermissions("ANALYST");

        assertThat(permissions).containsExactlyInAnyOrder(Permission.VIEW_DASHBOARDS, Permission.USE_CHAT);
        assertThat(permissions).doesNotContain(Permission.VIEW_EDITOR, Permission.MANAGE_USERS);
    }

    @Test
    @DisplayName("An unknown role code grants nothing rather than falling back to Developer")
    void unknownRoleGrantsNothing() {
        // The old Role.fromString collapsed anything unrecognised to DEVELOPER. Keeping
        // that behaviour here would turn a deleted custom role into silent Developer
        // access for everyone who held it.
        assertThat(permissionService.getEffectivePermissions("NO_SUCH_ROLE")).isEmpty();
    }

    @Test
    @DisplayName("Overrides add and remove permissions for a non-admin role")
    void overridesApply() {
        when(overrideRepository.findByRoleIgnoreCase(Role.DATA_ENGINEER.name())).thenReturn(List.of(
            new RolePermissionOverride(Role.DATA_ENGINEER.name(), Permission.VIEW_DIGEST, true, "tester"),
            new RolePermissionOverride(Role.DATA_ENGINEER.name(), Permission.VIEW_EDITOR, false, "tester")));

        Set<Permission> permissions = permissionService.getEffectivePermissions(Role.DATA_ENGINEER.name());

        assertThat(permissions).contains(Permission.VIEW_DIGEST);
        assertThat(permissions).doesNotContain(Permission.VIEW_EDITOR);
    }

    @Test
    @DisplayName("An override cannot be recorded against Admin")
    void adminOverrideRejected() {
        when(customRoleRepository.existsByCodeIgnoreCase(anyString())).thenReturn(false);
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
            () -> permissionService.setOverride("ADMIN", Permission.MANAGE_USERS, false, null, "tester"));
    }

    @Test
    @DisplayName("Legacy role names still resolve to Developer")
    void legacyRoleNamesMigrate() {
        // Installs upgrading from the two-role model carry EDITOR/VIEWER/USER values.
        assertThat(Role.fromString("EDITOR")).isEqualTo(Role.DEVELOPER);
        assertThat(Role.fromString("VIEWER")).isEqualTo(Role.DEVELOPER);
        assertThat(Role.fromString("developer")).isEqualTo(Role.DEVELOPER);
        assertThat(Role.fromString("DBA")).isEqualTo(Role.DBA);
        assertThat(Role.fromString("ANALYST")).isNull();
    }
}
