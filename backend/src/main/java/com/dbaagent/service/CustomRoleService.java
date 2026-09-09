package com.dbaagent.service;

import com.dbaagent.model.CustomRole;
import com.dbaagent.model.Permission;
import com.dbaagent.model.Role;
import com.dbaagent.repository.CustomRoleRepository;
import com.dbaagent.repository.RolePermissionOverrideRepository;
import com.dbaagent.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * CRUD for admin-defined roles.
 *
 * <p>A custom role's {@code code} shares a namespace with the built-in {@link Role} names
 * because both are written to {@code users.role}, so creation refuses a code that
 * collides with a built-in one. Deletion refuses while any user still holds the role —
 * silently reassigning people is not a decision this service should make on its own, and
 * an orphaned code would resolve to no permissions at their next login.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CustomRoleService {

    private final CustomRoleRepository customRoleRepository;
    private final RolePermissionOverrideRepository overrideRepository;
    private final UserRepository userRepository;

    public List<CustomRole> listRoles() {
        return customRoleRepository.findAllByOrderByNameAsc();
    }

    public CustomRole getByCode(String code) {
        return customRoleRepository.findByCodeIgnoreCase(normalizeCode(code))
            .orElseThrow(() -> new IllegalArgumentException("Custom role not found: " + code));
    }

    @Transactional
    public CustomRole createRole(String name, String description, Collection<String> permissionCodes, String actor) {
        String cleanName = requireName(name);
        String code = deriveCode(cleanName);

        if (Role.isBuiltIn(code)) {
            throw new IllegalArgumentException(
                "\"" + cleanName + "\" collides with the built-in " + code + " role. Choose a different name.");
        }
        if (customRoleRepository.existsByCodeIgnoreCase(code)) {
            throw new IllegalArgumentException("A role named \"" + cleanName + "\" already exists");
        }

        CustomRole role = new CustomRole();
        role.setCode(code);
        role.setName(cleanName);
        role.setDescription(trimToNull(description));
        role.setPermissions(resolvePermissions(permissionCodes));
        role.setCreatedBy(actor);
        role.setUpdatedBy(actor);

        CustomRole saved = customRoleRepository.save(role);
        log.info("Custom role created: code={}, permissions={}, by={}",
            saved.getCode(), saved.getPermissions().size(), actor);
        return saved;
    }

    @Transactional
    public CustomRole updateRole(String code, String name, String description,
                                 Collection<String> permissionCodes, String actor) {
        CustomRole role = getByCode(code);

        if (name != null && !name.isBlank()) {
            // The code is the identity written to users.role, so renaming changes only the
            // label. Re-deriving the code would orphan every user holding the old one.
            role.setName(requireName(name));
        }
        if (description != null) {
            role.setDescription(trimToNull(description));
        }
        if (permissionCodes != null) {
            role.setPermissions(resolvePermissions(permissionCodes));
        }
        role.setUpdatedBy(actor);

        CustomRole saved = customRoleRepository.save(role);
        log.info("Custom role updated: code={}, by={}", saved.getCode(), actor);
        return saved;
    }

    @Transactional
    public void deleteRole(String code, String actor) {
        CustomRole role = getByCode(code);
        long holders = userRepository.countByRoleIgnoreCase(role.getCode());
        if (holders > 0) {
            throw new IllegalArgumentException(
                "Cannot delete \"" + role.getName() + "\": " + holders + " user(s) still have this role. "
                    + "Reassign them first.");
        }
        // Overrides are keyed by role code with no FK, so they would dangle silently.
        overrideRepository.deleteAll(overrideRepository.findByRoleIgnoreCase(role.getCode()));
        customRoleRepository.delete(role);
        log.info("Custom role deleted: code={}, by={}", role.getCode(), actor);
    }

    /** How many users hold this role code (built-in or custom). */
    public long countUsersWithRole(String roleCode) {
        return userRepository.countByRoleIgnoreCase(normalizeCode(roleCode));
    }

    private static Set<Permission> resolvePermissions(Collection<String> codes) {
        Set<Permission> resolved = EnumSet.noneOf(Permission.class);
        if (codes == null) {
            return resolved;
        }
        for (String raw : codes) {
            Permission permission = Permission.fromCode(raw);
            if (permission == null) {
                throw new IllegalArgumentException("Unknown permission: " + raw);
            }
            resolved.add(permission);
        }
        return resolved;
    }

    private static String requireName(String name) {
        String clean = name == null ? "" : name.trim();
        if (clean.isEmpty()) {
            throw new IllegalArgumentException("Role name is required");
        }
        if (clean.length() > 128) {
            throw new IllegalArgumentException("Role name must be 128 characters or fewer");
        }
        return clean;
    }

    /** "Data Analyst" -> "DATA_ANALYST". */
    private static String deriveCode(String name) {
        String code = name.trim().toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9]+", "_")
            .replaceAll("^_+|_+$", "");
        if (code.isEmpty()) {
            throw new IllegalArgumentException("Role name must contain at least one letter or number");
        }
        return code.length() > 64 ? code.substring(0, 64) : code;
    }

    private static String normalizeCode(String code) {
        return code == null ? "" : code.trim().toUpperCase(Locale.ROOT);
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
