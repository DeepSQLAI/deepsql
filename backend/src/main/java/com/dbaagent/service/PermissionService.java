package com.dbaagent.service;

import com.dbaagent.model.Permission;
import com.dbaagent.model.Role;
import com.dbaagent.model.RolePermissionOverride;
import com.dbaagent.repository.RolePermissionOverrideRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for computing effective permissions based on:
 * 1. Default permissions from Permission.defaultMinRole (hierarchy-based)
 * 2. Custom overrides from RolePermissionOverride table
 *
 * Effective permission = (default permissions) + (granted overrides) - (revoked overrides)
 */
@Service
public class PermissionService {

    private static final Logger log = LoggerFactory.getLogger(PermissionService.class);

    private final RolePermissionOverrideRepository overrideRepository;

    public PermissionService(RolePermissionOverrideRepository overrideRepository) {
        this.overrideRepository = overrideRepository;
    }

    /**
     * Get the effective permissions for a role.
     * Combines default hierarchy-based permissions with any overrides.
     */
    public Set<Permission> getEffectivePermissions(Role role) {
        // Start with default permissions based on hierarchy
        Set<Permission> effective = new HashSet<>(role.getDefaultPermissions());

        // Apply overrides
        List<RolePermissionOverride> overrides = overrideRepository.findByRole(role);
        for (RolePermissionOverride override : overrides) {
            if (override.isGranted()) {
                // Add permission (even if not in defaults)
                effective.add(override.getPermissionCode());
            } else {
                // Remove permission (even if in defaults)
                effective.remove(override.getPermissionCode());
            }
        }

        return effective;
    }

    /**
     * Get effective permissions as a Set of permission code strings.
     * This is what gets returned to the frontend.
     */
    public Set<String> getEffectivePermissionCodes(Role role) {
        return getEffectivePermissions(role).stream()
            .map(Enum::name)
            .collect(Collectors.toSet());
    }

    /**
     * Check if a role has a specific permission (considering overrides).
     */
    public boolean hasPermission(Role role, Permission permission) {
        // Check for override first
        Optional<RolePermissionOverride> override =
            overrideRepository.findByRoleAndPermissionCode(role, permission);

        if (override.isPresent()) {
            return override.get().isGranted();
        }

        // Fall back to default
        return permission.isGrantedByDefaultTo(role);
    }

    /**
     * Get the full permission registry with role assignments.
     * Returns all permissions with their default min role and any overrides.
     */
    public List<PermissionInfo> getPermissionRegistry() {
        List<RolePermissionOverride> allOverrides = overrideRepository.findAll();
        Map<Permission, List<RolePermissionOverride>> overridesByPermission = allOverrides.stream()
            .collect(Collectors.groupingBy(RolePermissionOverride::getPermissionCode));

        return Arrays.stream(Permission.values())
            .map(p -> {
                PermissionInfo info = new PermissionInfo();
                info.code = p.name();
                info.description = p.getDescription();
                info.defaultMinRole = p.getDefaultMinRole().name();

                // Compute effective roles for this permission
                Set<String> effectiveRoles = new HashSet<>();
                for (Role role : Role.values()) {
                    if (hasPermission(role, p)) {
                        effectiveRoles.add(role.name());
                    }
                }
                info.effectiveRoles = effectiveRoles;

                // Include overrides for this permission
                info.overrides = overridesByPermission.getOrDefault(p, Collections.emptyList())
                    .stream()
                    .map(o -> {
                        OverrideInfo oi = new OverrideInfo();
                        oi.role = o.getRole().name();
                        oi.granted = o.isGranted();
                        oi.reason = o.getReason();
                        return oi;
                    })
                    .collect(Collectors.toList());

                return info;
            })
            .collect(Collectors.toList());
    }

    /**
     * Get all overrides (for admin view).
     */
    public List<RolePermissionOverride> getAllOverrides() {
        return overrideRepository.findAll();
    }

    /**
     * Create or update an override.
     */
    @Transactional
    public RolePermissionOverride setOverride(Role role, Permission permission, boolean granted, String reason, String updatedBy) {
        Optional<RolePermissionOverride> existing =
            overrideRepository.findByRoleAndPermissionCode(role, permission);

        RolePermissionOverride override;
        if (existing.isPresent()) {
            override = existing.get();
            override.setGranted(granted);
            override.setReason(reason);
            override.setUpdatedBy(updatedBy);
        } else {
            override = new RolePermissionOverride(role, permission, granted, reason, updatedBy);
        }

        RolePermissionOverride saved = overrideRepository.save(override);
        log.info("Permission override set: role={}, permission={}, granted={}, by={}",
            role, permission, granted, updatedBy);
        return saved;
    }

    /**
     * Remove an override (revert to default behavior).
     */
    @Transactional
    public void removeOverride(Role role, Permission permission) {
        overrideRepository.deleteByRoleAndPermissionCode(role, permission);
        log.info("Permission override removed: role={}, permission={}", role, permission);
    }

    /**
     * Get role info with effective permissions.
     */
    public List<RoleInfo> getRoleRegistry() {
        return Arrays.stream(Role.values())
            .map(role -> {
                RoleInfo info = new RoleInfo();
                info.code = role.name();
                info.description = role.getDescription();
                info.level = role.ordinal();
                info.defaultPermissions = role.getDefaultPermissions().stream()
                    .map(Enum::name)
                    .collect(Collectors.toSet());
                info.effectivePermissions = getEffectivePermissionCodes(role);
                info.overrideCount = overrideRepository.findByRole(role).size();
                return info;
            })
            .collect(Collectors.toList());
    }

    // DTO classes for API responses
    public static class PermissionInfo {
        public String code;
        public String description;
        public String defaultMinRole;
        public Set<String> effectiveRoles;
        public List<OverrideInfo> overrides;
    }

    public static class OverrideInfo {
        public String role;
        public boolean granted;
        public String reason;
    }

    public static class RoleInfo {
        public String code;
        public String description;
        public int level;
        public Set<String> defaultPermissions;
        public Set<String> effectivePermissions;
        public int overrideCount;
    }
}
