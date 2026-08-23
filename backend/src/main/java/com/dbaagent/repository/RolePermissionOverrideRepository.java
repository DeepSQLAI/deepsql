package com.dbaagent.repository;

import com.dbaagent.model.Permission;
import com.dbaagent.model.RolePermissionOverride;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface RolePermissionOverrideRepository extends JpaRepository<RolePermissionOverride, Long> {

    /**
     * Find all overrides for a specific role.
     */
    List<RolePermissionOverride> findByRoleIgnoreCase(String role);

    /**
     * Find a specific override by role and permission.
     */
    Optional<RolePermissionOverride> findByRoleIgnoreCaseAndPermissionCode(String role, Permission permissionCode);

    /**
     * Find all overrides that grant permissions (used for adding permissions to roles).
     */
    List<RolePermissionOverride> findByRoleIgnoreCaseAndGrantedTrue(String role);

    /**
     * Find all overrides that revoke permissions (used for removing permissions from roles).
     */
    List<RolePermissionOverride> findByRoleIgnoreCaseAndGrantedFalse(String role);

    /**
     * Delete an override by role and permission.
     */
    void deleteByRoleIgnoreCaseAndPermissionCode(String role, Permission permissionCode);

    /**
     * Check if an override exists for a role-permission pair.
     */
    boolean existsByRoleIgnoreCaseAndPermissionCode(String role, Permission permissionCode);

    /**
     * Count overrides (for admin dashboard).
     */
    @Query("SELECT COUNT(o) FROM RolePermissionOverride o")
    long countOverrides();
}
