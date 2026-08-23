package com.dbaagent.service;

import com.dbaagent.model.Permission;
import com.dbaagent.model.Role;
import com.dbaagent.model.InviteType;
import com.dbaagent.model.SecurityEventOutcome;
import com.dbaagent.model.SecurityEventType;
import com.dbaagent.model.User;
import com.dbaagent.model.UserAccountStatus;
import com.dbaagent.repository.UserRepository;
import com.dbaagent.repository.UserSessionRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Service for user management operations.
 * Handles user listing, role updates, and deletion.
 */
@Slf4j
@Service
public class UserManagementService {

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserInviteService userInviteService;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private SecurityEventService securityEventService;

    @Autowired
    private UserSessionRepository userSessionRepository;

    @Autowired
    private PermissionService permissionService;

    /**
     * Create a new user (admin only).
     */
    @Transactional
    public Map<String, Object> createUser(String username, String password, String email, String roleName) {
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email is required");
        }
        if (password == null || password.isBlank()) {
            throw new IllegalArgumentException("Password is required");
        }
        if (password.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }
        String roleCode = resolveRoleCode(roleName);
        String normalizedEmail = email.trim().toLowerCase();
        if (userRepository.existsByEmailIgnoreCase(normalizedEmail)) {
            throw new IllegalArgumentException("A user with this email already exists");
        }

        String resolvedUsername = resolveUsername(username, normalizedEmail);
        if (userRepository.findByUsername(resolvedUsername).isPresent()) {
            throw new IllegalArgumentException("A user with this display name already exists");
        }

        User user = new User();
        user.setUsername(resolvedUsername);
        user.setEmail(normalizedEmail);
        user.setPassword(passwordEncoder.encode(password));
        user.setRole(roleCode);
        user.setEmailVerifiedAt(LocalDateTime.now());
        user.setAccountStatusEnum(UserAccountStatus.ACTIVE);
        user.setInvitedAt(LocalDateTime.now());
        userRepository.save(user);

        securityEventService.log(SecurityEventService.EventRequest.builder()
            .eventType(SecurityEventType.PASSWORD_SET)
            .outcome(SecurityEventOutcome.SUCCESS)
            .userId(user.getId())
            .email(user.getEmail())
            .targetResource("user:" + user.getId())
            .metadata(Map.of("role", roleCode, "action", "user_created"))
            .build());

        log.info("Admin created user: {} with role {}", normalizedEmail, roleCode);
        return toUserDTO(user);
    }

    /**
     * Get all users with their roles.
     */
    public List<Map<String, Object>> listAllUsers() {
        return userRepository.findAll().stream()
                .map(this::toUserDTO)
                .collect(Collectors.toList());
    }

    /**
     * Get a user by ID.
     */
    public Optional<Map<String, Object>> getUserById(Long userId) {
        return userRepository.findById(userId)
                .map(this::toUserDTO);
    }

    /**
     * Get a user by username.
     */
    public Optional<Map<String, Object>> getUserByUsername(String username) {
        return userRepository.findByUsername(username)
                .map(this::toUserDTO);
    }

    /**
     * Update a user's role.
     */
    @Transactional
    public Map<String, Object> updateUserRole(Long userId, String roleName) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found with ID: " + userId));

        // Prevent changing the admin user's role
        if ("admin".equalsIgnoreCase(user.getUsername())) {
            throw new IllegalArgumentException("Cannot change the admin user's role");
        }

        String roleCode = resolveRoleCode(roleName);
        user.setRole(roleCode);
        userRepository.save(user);
        securityEventService.log(SecurityEventService.EventRequest.builder()
            .eventType(SecurityEventType.ROLE_CHANGED)
            .outcome(SecurityEventOutcome.SUCCESS)
            .userId(user.getId())
            .email(user.getEmail())
            .targetResource("user:" + user.getId())
            .metadata(Map.of("role", roleCode))
            .build());

        log.info("Updated user {} role to {}", user.getUsername(), roleCode);

        return toUserDTO(user);
    }

    /**
     * Reset a user's password (admin only).
     */
    @Transactional
    public void resetPassword(Long userId, String newPassword) {
        if (newPassword == null || newPassword.isBlank()) {
            throw new IllegalArgumentException("Password is required");
        }
        if (newPassword.length() < 8) {
            throw new IllegalArgumentException("Password must be at least 8 characters");
        }
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found with ID: " + userId));
        user.setPassword(passwordEncoder.encode(newPassword));
        userRepository.save(user);
        userSessionRepository.findAllByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(user.getId()).forEach(session -> {
            session.setRevokedAt(LocalDateTime.now());
            session.setRevokeReason("password_changed_by_admin");
            userSessionRepository.save(session);
        });
        securityEventService.log(SecurityEventService.EventRequest.builder()
            .eventType(SecurityEventType.PASSWORD_CHANGED)
            .outcome(SecurityEventOutcome.SUCCESS)
            .userId(user.getId())
            .email(user.getEmail())
            .targetResource("user:" + user.getId())
            .metadata(Map.of("action", "admin_password_change"))
            .build());
    }

    /**
     * Delete a user by ID.
     */
    @Transactional
    public void deleteUser(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("User not found with ID: " + userId));

        // Prevent deleting the admin user
        if ("admin".equalsIgnoreCase(user.getUsername())) {
            throw new IllegalArgumentException("Cannot delete the admin user");
        }

        userRepository.delete(user);
        log.info("Deleted user: {}", user.getUsername());
    }

    @Transactional
    public Map<String, Object> resendInvite(Long userId) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found with ID: " + userId));
        if (user.getAccountStatusEnum() == UserAccountStatus.ACTIVE) {
            throw new IllegalArgumentException("Direct account creation is enabled. Active users do not need invite emails.");
        }
        // UserInviteService is typed to the built-in enum; a custom-role invite carries
        // the closest built-in and the real role is already stored on the user row.
        Role role = Role.fromStringOrDefault(user.getRole());
        var invite = userInviteService.createInvite(user.getEmail(), user.getUsername(), role, "admin", InviteType.STANDARD, null, null);
        return toUserDTO(invite.user());
    }

    @Transactional
    public Map<String, Object> lockUser(Long userId) {
        return updateStatus(userId, UserAccountStatus.LOCKED);
    }

    @Transactional
    public Map<String, Object> unlockUser(Long userId) {
        return updateStatus(userId, UserAccountStatus.ACTIVE);
    }

    @Transactional
    public Map<String, Object> disableUser(Long userId) {
        return updateStatus(userId, UserAccountStatus.DISABLED);
    }

    /**
     * Get all roles with their permissions.
     */
    public List<Map<String, Object>> getRolesWithPermissions() {
        // Every assignable role, built-in and custom, with the permissions actually in
        // effect (overrides applied) rather than the enum's raw defaults — this list is
        // what the admin UI offers when changing someone's role.
        return permissionService.getRoleRegistry().stream()
                .map(info -> {
                    Map<String, Object> roleDTO = new HashMap<>();
                    roleDTO.put("name", info.code);
                    roleDTO.put("code", info.code);
                    roleDTO.put("displayName", info.name);
                    roleDTO.put("description", info.description);
                    roleDTO.put("builtIn", info.builtIn);

                    List<Map<String, String>> permissions = info.effectivePermissions.stream()
                            .map(code -> {
                                Permission p = Permission.fromCode(code);
                                Map<String, String> permDTO = new HashMap<>();
                                permDTO.put("name", code);
                                permDTO.put("description", p != null ? p.getDescription() : code);
                                return permDTO;
                            })
                            .collect(Collectors.toList());

                    roleDTO.put("permissions", permissions);
                    return roleDTO;
                })
                .collect(Collectors.toList());
    }

    /**
     * Normalise a requested role name to a role code that actually exists.
     *
     * <p>Blank means DEVELOPER. Anything else must name a built-in role or an existing
     * custom role: an unknown code is rejected rather than silently downgraded, because a
     * user whose role does not resolve gets no permissions at all at their next login.
     */
    private String resolveRoleCode(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return Role.DEVELOPER.name();
        }
        String code = roleName.trim().toUpperCase();
        Role builtIn = Role.fromString(code);
        if (builtIn != null) {
            return builtIn.name();
        }
        if (!permissionService.roleExists(code)) {
            throw new IllegalArgumentException("Unknown role: " + roleName);
        }
        return code;
    }

    /**
     * Get all available permissions.
     */
    public List<Map<String, String>> getAllPermissions() {
        return Arrays.stream(Permission.values())
                .map(p -> {
                    Map<String, String> permDTO = new HashMap<>();
                    permDTO.put("name", p.name());
                    permDTO.put("description", p.getDescription());
                    return permDTO;
                })
                .collect(Collectors.toList());
    }

    /**
     * Convert User entity to DTO map (excludes password).
     */
    private Map<String, Object> toUserDTO(User user) {
        Map<String, Object> dto = new HashMap<>();
        dto.put("id", user.getId());
        dto.put("username", user.getUsername());
        dto.put("email", user.getEmail());
        dto.put("role", user.getRole());
        dto.put("inviteCodeId", user.getInviteCodeId());
        dto.put("invitedAt", user.getInvitedAt());
        dto.put("accountStatus", user.getAccountStatus());
        dto.put("emailVerifiedAt", user.getEmailVerifiedAt());
        dto.put("emailVerified", user.isEmailVerified());
        dto.put("mfaEnrolledAt", user.getMfaEnrolledAt());
        dto.put("mfaEnrolled", user.getMfaEnrolledAt() != null);

        // Effective permissions, resolved by role code so a custom role reports what it
        // actually grants; user.getPermissions() only knows the built-in enum.
        dto.put("roleName", permissionService.describeRole(user.getRoleCode()));
        dto.put("permissions", new java.util.ArrayList<>(
                permissionService.getEffectivePermissionCodes(user.getRoleCode())));

        return dto;
    }

    private Map<String, Object> updateStatus(Long userId, UserAccountStatus status) {
        User user = userRepository.findById(userId)
            .orElseThrow(() -> new IllegalArgumentException("User not found with ID: " + userId));
        if ("admin".equalsIgnoreCase(user.getUsername()) && status == UserAccountStatus.DISABLED) {
            throw new IllegalArgumentException("Cannot disable the bootstrap admin user");
        }
        user.setAccountStatusEnum(status);
        userRepository.save(user);
        if (status == UserAccountStatus.LOCKED || status == UserAccountStatus.DISABLED) {
            userSessionRepository.findAllByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(user.getId()).forEach(session -> {
                session.setRevokedAt(java.time.LocalDateTime.now());
                session.setRevokeReason("account_" + status.name().toLowerCase());
                userSessionRepository.save(session);
            });
        }
        securityEventService.log(SecurityEventService.EventRequest.builder()
            .eventType(status == UserAccountStatus.LOCKED
                ? SecurityEventType.ACCOUNT_LOCKED
                : status == UserAccountStatus.DISABLED
                    ? SecurityEventType.ACCOUNT_DISABLED
                    : SecurityEventType.ACCOUNT_UNLOCKED)
            .outcome(SecurityEventOutcome.SUCCESS)
            .userId(user.getId())
            .email(user.getEmail())
            .targetResource("user:" + user.getId())
            .metadata(Map.of("accountStatus", status.name()))
            .build());
        return toUserDTO(user);
    }

    private String resolveUsername(String requestedUsername, String email) {
        String base = requestedUsername != null && !requestedUsername.isBlank()
            ? requestedUsername.trim()
            : email.substring(0, email.indexOf('@'));
        return base;
    }
}
