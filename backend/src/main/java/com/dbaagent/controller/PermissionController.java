package com.dbaagent.controller;

import com.dbaagent.model.Permission;
import com.dbaagent.model.Role;
import com.dbaagent.model.RolePermissionOverride;
import com.dbaagent.service.PermissionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Controller for permission-related endpoints.
 *
 * Public endpoints:
 * - GET /api/permissions/me - Get current user's effective permissions
 *
 * Admin endpoints:
 * - GET /api/permissions/registry - Get full permission registry
 * - GET /api/permissions/roles - Get all roles with permissions
 * - GET /api/permissions/overrides - Get all overrides
 * - POST /api/permissions/overrides - Create/update an override
 * - DELETE /api/permissions/overrides - Remove an override
 */
@RestController
@RequestMapping("/permissions")
public class PermissionController {

    private final PermissionService permissionService;

    public PermissionController(PermissionService permissionService) {
        this.permissionService = permissionService;
    }

    /**
     * Get the current user's effective permissions.
     * This is called by frontend after login to know what the user can do.
     */
    @GetMapping("/me")
    public ResponseEntity<?> getMyPermissions() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }

        // Extract role from authorities
        Role role = extractRoleFromAuthentication(auth);
        Set<String> permissions = permissionService.getEffectivePermissionCodes(role);

        Map<String, Object> response = new HashMap<>();
        response.put("role", role.name());
        response.put("permissions", permissions);
        response.put("permissionCount", permissions.size());

        return ResponseEntity.ok(response);
    }

    /**
     * Get the full permission registry.
     * Shows all permissions with their default roles and any overrides.
     */
    @GetMapping("/registry")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getPermissionRegistry() {
        List<PermissionService.PermissionInfo> registry = permissionService.getPermissionRegistry();
        return ResponseEntity.ok(Map.of(
            "permissions", registry,
            "totalPermissions", Permission.values().length,
            "totalRoles", Role.values().length
        ));
    }

    /**
     * Get all roles with their effective permissions.
     */
    @GetMapping("/roles")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getRoles() {
        List<PermissionService.RoleInfo> roles = permissionService.getRoleRegistry();
        return ResponseEntity.ok(Map.of("roles", roles));
    }

    /**
     * Get all permission overrides.
     */
    @GetMapping("/overrides")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getOverrides() {
        List<RolePermissionOverride> overrides = permissionService.getAllOverrides();
        List<Map<String, Object>> overrideList = overrides.stream()
            .map(o -> {
                Map<String, Object> map = new HashMap<>();
                map.put("id", o.getId());
                map.put("role", o.getRole().name());
                map.put("permission", o.getPermissionCode().name());
                map.put("granted", o.isGranted());
                map.put("reason", o.getReason());
                map.put("updatedBy", o.getUpdatedBy());
                map.put("updatedAt", o.getUpdatedAt());
                return map;
            })
            .toList();

        return ResponseEntity.ok(Map.of(
            "overrides", overrideList,
            "totalOverrides", overrideList.size()
        ));
    }

    /**
     * Create or update a permission override.
     */
    @PostMapping("/overrides")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> setOverride(@RequestBody Map<String, Object> request) {
        try {
            String roleStr = (String) request.get("role");
            String permissionStr = (String) request.get("permission");
            Boolean granted = (Boolean) request.get("granted");
            String reason = (String) request.get("reason");

            if (roleStr == null || permissionStr == null || granted == null) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Missing required fields: role, permission, granted"
                ));
            }

            Role role = Role.valueOf(roleStr.toUpperCase());
            Permission permission = Permission.valueOf(permissionStr.toUpperCase());

            // Get current user for audit
            Authentication auth = SecurityContextHolder.getContext().getAuthentication();
            String updatedBy = auth != null ? auth.getName() : "system";

            RolePermissionOverride override = permissionService.setOverride(
                role, permission, granted, reason, updatedBy
            );

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", granted ?
                    "Permission " + permission + " granted to " + role :
                    "Permission " + permission + " revoked from " + role,
                "override", Map.of(
                    "id", override.getId(),
                    "role", override.getRole().name(),
                    "permission", override.getPermissionCode().name(),
                    "granted", override.isGranted(),
                    "reason", override.getReason()
                )
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid role or permission: " + e.getMessage()
            ));
        }
    }

    /**
     * Remove a permission override (revert to default).
     */
    @DeleteMapping("/overrides")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> removeOverride(@RequestBody Map<String, String> request) {
        try {
            String roleStr = request.get("role");
            String permissionStr = request.get("permission");

            if (roleStr == null || permissionStr == null) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Missing required fields: role, permission"
                ));
            }

            Role role = Role.valueOf(roleStr.toUpperCase());
            Permission permission = Permission.valueOf(permissionStr.toUpperCase());

            permissionService.removeOverride(role, permission);

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Override removed. Permission " + permission +
                    " for " + role + " reverted to default behavior."
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid role or permission: " + e.getMessage()
            ));
        }
    }

    /**
     * Check if a specific role has a specific permission.
     */
    @GetMapping("/check")
    public ResponseEntity<?> checkPermission(
            @RequestParam String role,
            @RequestParam String permission) {
        try {
            Role r = Role.valueOf(role.toUpperCase());
            Permission p = Permission.valueOf(permission.toUpperCase());

            boolean hasPermission = permissionService.hasPermission(r, p);
            boolean isDefault = p.isGrantedByDefaultTo(r);

            return ResponseEntity.ok(Map.of(
                "role", r.name(),
                "permission", p.name(),
                "granted", hasPermission,
                "isDefault", isDefault,
                "hasOverride", hasPermission != isDefault
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Invalid role or permission: " + e.getMessage()
            ));
        }
    }

    private Role extractRoleFromAuthentication(Authentication auth) {
        if (auth.getAuthorities() != null) {
            for (var authority : auth.getAuthorities()) {
                String authorityStr = authority.getAuthority();
                if (authorityStr.startsWith("ROLE_")) {
                    String roleName = authorityStr.substring(5);
                    try {
                        return Role.valueOf(roleName.toUpperCase());
                    } catch (IllegalArgumentException ignored) {
                        // Continue to next authority
                    }
                }
            }
        }
        return Role.DEVELOPER; // Default fallback
    }
}
