package com.dbaagent.service;

import com.dbaagent.model.CustomRole;
import com.dbaagent.model.Permission;
import com.dbaagent.model.Role;
import com.dbaagent.model.RolePermissionOverride;
import com.dbaagent.repository.CustomRoleRepository;
import com.dbaagent.repository.RolePermissionOverrideRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Resolves the effective permissions of a role code.
 *
 * <p>A role code is either a built-in {@link Role} name or a {@link CustomRole} code.
 * The effective set is:
 *
 * <pre>
 *   built-in : Role default permissions + granted overrides - revoked overrides
 *   custom   : the role's own explicit permission set + granted - revoked
 * </pre>
 *
 * <p>ADMIN is a fixed point: it holds every permission and overrides are ignored for it,
 * so no configuration change can lock the last administrator out of user management.
 */
@Service
public class PermissionService {

    private static final Logger log = LoggerFactory.getLogger(PermissionService.class);

    private final RolePermissionOverrideRepository overrideRepository;
    private final CustomRoleRepository customRoleRepository;

    public PermissionService(RolePermissionOverrideRepository overrideRepository,
                             CustomRoleRepository customRoleRepository) {
        this.overrideRepository = overrideRepository;
        this.customRoleRepository = customRoleRepository;
    }

    /** Effective permissions for a role code (built-in name or custom role code). */
    public Set<Permission> getEffectivePermissions(String roleCode) {
        String normalized = normalize(roleCode);
        if (normalized == null) {
            return EnumSet.noneOf(Permission.class);
        }

        Role builtIn = Role.fromString(normalized);
        if (builtIn == Role.ADMIN) {
            return EnumSet.allOf(Permission.class);
        }

        Set<Permission> effective = EnumSet.noneOf(Permission.class);
        if (builtIn != null) {
            effective.addAll(builtIn.getDefaultPermissions());
        } else {
            Optional<CustomRole> custom = customRoleRepository.findByCodeIgnoreCase(normalized);
            if (custom.isEmpty()) {
                // An unknown role code grants nothing. Falling back to a real role here
                // would turn a typo, or a custom role someone deleted, into silent access.
                log.warn("Unknown role code '{}' resolved to no permissions", roleCode);
                return effective;
            }
            effective.addAll(custom.get().getPermissions());
        }

        for (RolePermissionOverride override : overrideRepository.findByRoleIgnoreCase(normalized)) {
            if (override.isGranted()) {
                effective.add(override.getPermissionCode());
            } else {
                effective.remove(override.getPermissionCode());
            }
        }
        return effective;
    }

    /** Convenience overload for a built-in role. */
    public Set<Permission> getEffectivePermissions(Role role) {
        return role == null ? EnumSet.noneOf(Permission.class) : getEffectivePermissions(role.name());
    }

    public Set<String> getEffectivePermissionCodes(String roleCode) {
        return getEffectivePermissions(roleCode).stream()
            .map(Enum::name)
            .collect(Collectors.toCollection(TreeSet::new));
    }

    public Set<String> getEffectivePermissionCodes(Role role) {
        return getEffectivePermissionCodes(role == null ? null : role.name());
    }

    public boolean hasPermission(String roleCode, Permission permission) {
        return permission != null && getEffectivePermissions(roleCode).contains(permission);
    }

    public boolean hasPermission(Role role, Permission permission) {
        return hasPermission(role == null ? null : role.name(), permission);
    }

    /** True when the role code names a built-in role or an existing custom role. */
    public boolean roleExists(String roleCode) {
        String normalized = normalize(roleCode);
        if (normalized == null) {
            return false;
        }
        return Role.isBuiltIn(normalized) || customRoleRepository.existsByCodeIgnoreCase(normalized);
    }

    /** Display name for a role code, for UI and audit messages. */
    public String describeRole(String roleCode) {
        String normalized = normalize(roleCode);
        if (normalized == null) {
            return "Unknown";
        }
        Role builtIn = Role.fromString(normalized);
        if (builtIn != null) {
            return builtIn.getDisplayName();
        }
        return customRoleRepository.findByCodeIgnoreCase(normalized)
            .map(CustomRole::getName)
            .orElse(normalized);
    }

    /**
     * The full permission registry: every permission, which roles hold it, and any
     * overrides recorded against it.
     */
    public List<PermissionInfo> getPermissionRegistry() {
        List<String> allRoleCodes = getAllRoleCodes();
        Map<Permission, List<RolePermissionOverride>> overridesByPermission =
            overrideRepository.findAll().stream()
                .collect(Collectors.groupingBy(RolePermissionOverride::getPermissionCode));

        Map<String, Set<Permission>> effectiveByRole = allRoleCodes.stream()
            .collect(Collectors.toMap(code -> code, this::getEffectivePermissions));

        return Arrays.stream(Permission.values())
            .map(p -> {
                PermissionInfo info = new PermissionInfo();
                info.code = p.name();
                info.description = p.getDescription();
                info.defaultRoles = p.getDefaultRoles().stream().map(Enum::name)
                    .collect(Collectors.toCollection(TreeSet::new));
                info.effectiveRoles = effectiveByRole.entrySet().stream()
                    .filter(entry -> entry.getValue().contains(p))
                    .map(Map.Entry::getKey)
                    .collect(Collectors.toCollection(TreeSet::new));
                info.overrides = overridesByPermission.getOrDefault(p, List.of()).stream()
                    .map(o -> {
                        OverrideInfo oi = new OverrideInfo();
                        oi.role = o.getRole();
                        oi.granted = o.isGranted();
                        oi.reason = o.getReason();
                        return oi;
                    })
                    .collect(Collectors.toList());
                return info;
            })
            .collect(Collectors.toList());
    }

    public List<RolePermissionOverride> getAllOverrides() {
        return overrideRepository.findAll();
    }

    @Transactional
    public RolePermissionOverride setOverride(String roleCode, Permission permission, boolean granted,
                                              String reason, String updatedBy) {
        String normalized = normalize(roleCode);
        if (normalized == null || !roleExists(normalized)) {
            throw new IllegalArgumentException("Unknown role: " + roleCode);
        }
        if (Role.ADMIN.name().equals(normalized)) {
            // Admin holds every permission unconditionally; storing an override here
            // would record a rule getEffectivePermissions deliberately ignores.
            throw new IllegalArgumentException("The Admin role always holds every permission and cannot be overridden");
        }

        RolePermissionOverride override = overrideRepository
            .findByRoleIgnoreCaseAndPermissionCode(normalized, permission)
            .orElseGet(() -> new RolePermissionOverride(normalized, permission, granted, reason, updatedBy));
        override.setGranted(granted);
        override.setReason(reason);
        override.setUpdatedBy(updatedBy);

        RolePermissionOverride saved = overrideRepository.save(override);
        log.info("Permission override set: role={}, permission={}, granted={}, by={}",
            normalized, permission, granted, updatedBy);
        return saved;
    }

    @Transactional
    public void removeOverride(String roleCode, Permission permission) {
        String normalized = normalize(roleCode);
        if (normalized == null) {
            return;
        }
        overrideRepository.deleteByRoleIgnoreCaseAndPermissionCode(normalized, permission);
        log.info("Permission override removed: role={}, permission={}", normalized, permission);
    }

    /** Every role code in the system: built-ins first, then custom roles by name. */
    public List<String> getAllRoleCodes() {
        List<String> codes = Arrays.stream(Role.values()).map(Enum::name)
            .collect(Collectors.toCollection(ArrayList::new));
        customRoleRepository.findAllByOrderByNameAsc().stream()
            .map(CustomRole::getCode)
            .filter(code -> !Role.isBuiltIn(code))
            .forEach(codes::add);
        return codes;
    }

    /** Role registry for the admin UI: built-in and custom roles with their permissions. */
    public List<RoleInfo> getRoleRegistry() {
        List<RoleInfo> roles = new ArrayList<>();

        for (Role role : Role.values()) {
            RoleInfo info = new RoleInfo();
            info.code = role.name();
            info.name = role.getDisplayName();
            info.description = role.getDescription();
            info.builtIn = true;
            info.editable = role != Role.ADMIN;
            info.defaultPermissions = role.getDefaultPermissions().stream().map(Enum::name)
                .collect(Collectors.toCollection(TreeSet::new));
            info.effectivePermissions = getEffectivePermissionCodes(role.name());
            info.overrideCount = role == Role.ADMIN ? 0 : overrideRepository.findByRoleIgnoreCase(role.name()).size();
            roles.add(info);
        }

        for (CustomRole custom : customRoleRepository.findAllByOrderByNameAsc()) {
            RoleInfo info = new RoleInfo();
            info.code = custom.getCode();
            info.name = custom.getName();
            info.description = custom.getDescription();
            info.builtIn = false;
            info.editable = true;
            info.defaultPermissions = custom.getPermissionCodeSet().stream()
                .collect(Collectors.toCollection(TreeSet::new));
            info.effectivePermissions = getEffectivePermissionCodes(custom.getCode());
            info.overrideCount = overrideRepository.findByRoleIgnoreCase(custom.getCode()).size();
            roles.add(info);
        }

        return roles;
    }

    private static String normalize(String roleCode) {
        if (roleCode == null || roleCode.isBlank()) {
            return null;
        }
        return roleCode.trim().toUpperCase();
    }

    // DTO classes for API responses
    public static class PermissionInfo {
        public String code;
        public String description;
        public Set<String> defaultRoles;
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
        public String name;
        public String description;
        public boolean builtIn;
        public boolean editable;
        public Set<String> defaultPermissions;
        public Set<String> effectivePermissions;
        public int overrideCount;
        /** Filled in by the controller; how many users currently hold this role. */
        public long userCount;
    }
}
