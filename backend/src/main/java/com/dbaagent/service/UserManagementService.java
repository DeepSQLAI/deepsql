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
        Role role = Role.DEVELOPER;
        if (roleName != null && !roleName.isBlank()) {
            role = Role.fromString(roleName);
        }
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
        user.setRoleEnum(role);
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
            .metadata(Map.of("role", role.name(), "action", "user_created"))
            .build());

        log.info("Admin created user: {} with role {}", normalizedEmail, role.name());
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

        Role role = Role.fromString(roleName);
        user.setRole(role.name());
        userRepository.save(user);
        securityEventService.log(SecurityEventService.EventRequest.builder()
            .eventType(SecurityEventType.ROLE_CHANGED)
            .outcome(SecurityEventOutcome.SUCCESS)
            .userId(user.getId())
            .email(user.getEmail())
            .targetResource("user:" + user.getId())
            .metadata(Map.of("role", role.name()))
            .build());

        log.info("Updated user {} role to {}", user.getUsername(), role.name());

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
        Role role = user.getRoleEnum();
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
        return Arrays.stream(Role.values())
                .map(role -> {
                    Map<String, Object> roleDTO = new HashMap<>();
                    roleDTO.put("name", role.name());
                    roleDTO.put("description", role.getDescription());

                    List<Map<String, String>> permissions = role.getPermissions().stream()
                            .map(p -> {
                                Map<String, String> permDTO = new HashMap<>();
                                permDTO.put("name", p.name());
                                permDTO.put("description", p.getDescription());
                                return permDTO;
                            })
                            .collect(Collectors.toList());

                    roleDTO.put("permissions", permissions);
                    return roleDTO;
                })
                .collect(Collectors.toList());
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

        // Include permissions for convenience
        Set<Permission> permissions = user.getPermissions();
        dto.put("permissions", permissions.stream()
                .map(Permission::name)
                .collect(Collectors.toList()));

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
