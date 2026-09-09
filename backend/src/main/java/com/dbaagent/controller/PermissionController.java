package com.dbaagent.controller;

import com.dbaagent.model.CustomRole;
import com.dbaagent.model.Permission;
import com.dbaagent.model.Role;
import com.dbaagent.model.RolePermissionOverride;
import com.dbaagent.service.CustomRoleService;
import com.dbaagent.service.PermissionService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.util.*;

/**
 * Permission and role endpoints.
 *
 * <pre>
 *   GET    /permissions/me            current user's effective permissions
 *   GET    /permissions/registry      every permission and who holds it        (admin)
 *   GET    /permissions/roles         built-in + custom roles                  (admin)
 *   POST   /permissions/roles         create a custom role                     (admin)
 *   PUT    /permissions/roles/{code}  update a custom role                     (admin)
 *   DELETE /permissions/roles/{code}  delete an unused custom role             (admin)
 *   GET    /permissions/overrides     all role-permission overrides            (admin)
 *   POST   /permissions/overrides     create or update an override             (admin)
 *   DELETE /permissions/overrides     remove an override                       (admin)
 * </pre>
 */
@RestController
@RequestMapping("/permissions")
public class PermissionController {

    private final PermissionService permissionService;
    private final CustomRoleService customRoleService;

    public PermissionController(PermissionService permissionService, CustomRoleService customRoleService) {
        this.permissionService = permissionService;
        this.customRoleService = customRoleService;
    }

    /**
     * The current user's effective permissions — what the frontend uses to decide which
     * menus and actions to show.
     */
    @GetMapping("/me")
    public ResponseEntity<?> getMyPermissions() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) {
            return ResponseEntity.status(401).body(Map.of("error", "Not authenticated"));
        }

        String roleCode = extractRoleCode(auth);
        Set<String> permissions = permissionService.getEffectivePermissionCodes(roleCode);

        Map<String, Object> response = new HashMap<>();
        response.put("role", roleCode);
        response.put("roleName", permissionService.describeRole(roleCode));
        response.put("permissions", permissions);
        response.put("permissionCount", permissions.size());
        return ResponseEntity.ok(response);
    }

    @GetMapping("/registry")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getPermissionRegistry() {
        List<PermissionService.PermissionInfo> registry = permissionService.getPermissionRegistry();
        List<Map<String, String>> catalog = Arrays.stream(Permission.values())
            .map(p -> Map.of("code", p.name(), "description", p.getDescription()))
            .toList();
        return ResponseEntity.ok(Map.of(
            "permissions", registry,
            "catalog", catalog,
            "totalPermissions", Permission.values().length,
            "totalRoles", permissionService.getAllRoleCodes().size()
        ));
    }

    @GetMapping("/roles")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getRoles() {
        List<PermissionService.RoleInfo> roles = permissionService.getRoleRegistry();
        for (PermissionService.RoleInfo role : roles) {
            role.userCount = customRoleService.countUsersWithRole(role.code);
        }
        return ResponseEntity.ok(Map.of(
            "roles", roles,
            "catalog", Arrays.stream(Permission.values())
                .map(p -> Map.of("code", p.name(), "description", p.getDescription()))
                .toList()
        ));
    }

    /** Create a custom role from a name plus an explicit permission list. */
    @PostMapping("/roles")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> createCustomRole(@RequestBody Map<String, Object> request) {
        try {
            CustomRole role = customRoleService.createRole(
                asString(request.get("name")),
                asString(request.get("description")),
                asStringList(request.get("permissions")),
                currentActor()
            );
            return ResponseEntity.ok(Map.of("success", true, "role", describe(role)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @PutMapping("/roles/{code}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> updateCustomRole(@PathVariable String code, @RequestBody Map<String, Object> request) {
        try {
            if (Role.isBuiltIn(code)) {
                // Built-in role permission sets are code, not data. Changing one is what
                // overrides are for, so the admin's change survives an upgrade.
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Built-in roles cannot be edited directly. Use a permission override, "
                        + "or create a custom role."
                ));
            }
            CustomRole role = customRoleService.updateRole(
                code,
                asString(request.get("name")),
                request.containsKey("description") ? asString(request.get("description")) : null,
                request.containsKey("permissions") ? asStringList(request.get("permissions")) : null,
                currentActor()
            );
            return ResponseEntity.ok(Map.of("success", true, "role", describe(role)));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @DeleteMapping("/roles/{code}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> deleteCustomRole(@PathVariable String code) {
        try {
            if (Role.isBuiltIn(code)) {
                return ResponseEntity.badRequest().body(Map.of(
                    "success", false, "message", "Built-in roles cannot be deleted"));
            }
            customRoleService.deleteRole(code, currentActor());
            return ResponseEntity.ok(Map.of("success", true, "message", "Role deleted"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", e.getMessage()));
        }
    }

    @GetMapping("/overrides")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> getOverrides() {
        List<Map<String, Object>> overrideList = permissionService.getAllOverrides().stream()
            .map(o -> {
                Map<String, Object> map = new HashMap<>();
                map.put("id", o.getId());
                map.put("role", o.getRole());
                map.put("permission", o.getPermissionCode().name());
                map.put("granted", o.isGranted());
                map.put("reason", o.getReason());
                map.put("updatedBy", o.getUpdatedBy());
                map.put("updatedAt", o.getUpdatedAt());
                return map;
            })
            .toList();
        return ResponseEntity.ok(Map.of("overrides", overrideList, "totalOverrides", overrideList.size()));
    }

    @PostMapping("/overrides")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> setOverride(@RequestBody Map<String, Object> request) {
        try {
            String roleCode = asString(request.get("role"));
            String permissionStr = asString(request.get("permission"));
            Boolean granted = (Boolean) request.get("granted");
            String reason = asString(request.get("reason"));

            if (roleCode == null || permissionStr == null || granted == null) {
                return ResponseEntity.badRequest().body(Map.of(
                    "error", "Missing required fields: role, permission, granted"));
            }

            Permission permission = Permission.fromCode(permissionStr);
            if (permission == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "Unknown permission: " + permissionStr));
            }

            RolePermissionOverride override = permissionService.setOverride(
                roleCode, permission, granted, reason, currentActor());

            Map<String, Object> overrideBody = new HashMap<>();
            overrideBody.put("id", override.getId());
            overrideBody.put("role", override.getRole());
            overrideBody.put("permission", override.getPermissionCode().name());
            overrideBody.put("granted", override.isGranted());
            overrideBody.put("reason", override.getReason());

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", (granted ? "Permission granted to " : "Permission revoked from ")
                    + permissionService.describeRole(roleCode),
                "override", overrideBody
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/overrides")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> removeOverride(@RequestBody Map<String, String> request) {
        String roleCode = request.get("role");
        String permissionStr = request.get("permission");
        if (roleCode == null || permissionStr == null) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", "Missing required fields: role, permission"));
        }
        Permission permission = Permission.fromCode(permissionStr);
        if (permission == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown permission: " + permissionStr));
        }
        permissionService.removeOverride(roleCode, permission);
        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "Override removed; " + permission + " reverted to the role's default."
        ));
    }

    /** Whether a role holds a permission, and whether that differs from its default. */
    @GetMapping("/check")
    public ResponseEntity<?> checkPermission(@RequestParam String role, @RequestParam String permission) {
        Permission p = Permission.fromCode(permission);
        if (p == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown permission: " + permission));
        }
        if (!permissionService.roleExists(role)) {
            return ResponseEntity.badRequest().body(Map.of("error", "Unknown role: " + role));
        }
        boolean granted = permissionService.hasPermission(role, p);
        Role builtIn = Role.fromString(role);
        boolean isDefault = builtIn != null && p.isGrantedByDefaultTo(builtIn);
        return ResponseEntity.ok(Map.of(
            "role", role.toUpperCase(),
            "permission", p.name(),
            "granted", granted,
            "isDefault", isDefault,
            "hasOverride", granted != isDefault
        ));
    }

    private static Map<String, Object> describe(CustomRole role) {
        Map<String, Object> body = new HashMap<>();
        body.put("code", role.getCode());
        body.put("name", role.getName());
        body.put("description", role.getDescription());
        body.put("permissions", role.getPermissionCodeSet());
        body.put("builtIn", false);
        return body;
    }

    private static String currentActor() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        return auth != null ? auth.getName() : "system";
    }

    private static String asString(Object value) {
        if (value == null) return null;
        String s = String.valueOf(value).trim();
        return s.isEmpty() ? null : s;
    }

    @SuppressWarnings("unchecked")
    private static List<String> asStringList(Object value) {
        if (value == null) return List.of();
        if (value instanceof Collection<?> collection) {
            return collection.stream().filter(Objects::nonNull).map(String::valueOf).toList();
        }
        return List.of(String.valueOf(value));
    }

    /**
     * The caller's role code from their granted authorities.
     *
     * <p>Custom roles carry a {@code ROLE_<CODE>} authority just like built-in ones (see
     * {@code CustomUserDetailsService}), so this returns the code as stored without
     * needing to know whether it is built-in.
     */
    private static String extractRoleCode(Authentication auth) {
        if (auth.getAuthorities() != null) {
            for (var authority : auth.getAuthorities()) {
                String value = authority.getAuthority();
                if (value != null && value.startsWith("ROLE_")) {
                    return value.substring(5);
                }
            }
        }
        return Role.DEVELOPER.name();
    }
}
