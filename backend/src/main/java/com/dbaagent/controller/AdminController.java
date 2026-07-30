package com.dbaagent.controller;

import com.dbaagent.dto.ConnectionAccessUpsertRequest;
import com.dbaagent.dto.ConnectionChatAccessPolicyUpsertRequest;
import com.dbaagent.dto.PolicyPreviewRequest;
import com.dbaagent.model.ConnectionAccessLevel;
import com.dbaagent.model.Role;
import com.dbaagent.model.User;
import com.dbaagent.repository.*;
import com.dbaagent.service.security.AccessControlService;
import com.dbaagent.service.security.ConnectionAccessService;
import com.dbaagent.service.ConnectionChatAccessPolicyService;
import com.dbaagent.service.SecurityEventService;
import com.dbaagent.service.UserManagementService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Controller for admin operations.
 * All endpoints require ADMIN role.
 */
@Slf4j
@RestController
@RequestMapping("/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminController {

    @Autowired
    private UserManagementService userManagementService;

    @Autowired
    private SlowQueryHistoryRepository slowQueryHistoryRepository;

    @Autowired
    private SlowLogSourceConfigRepository slowLogSourceConfigRepository;

    @Autowired
    private QueryFingerprintRepository queryFingerprintRepository;

    @Autowired
    private QueryPerformanceHistoryRepository queryPerformanceHistoryRepository;

    @Autowired
    private QueryPerformanceRegressionRepository queryPerformanceRegressionRepository;

    @Autowired
    private QueryPerformanceBaselineRepository queryPerformanceBaselineRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ConnectionAccessService connectionAccessService;

    @Autowired
    private AccessControlService accessControlService;

    @Autowired
    private SecurityEventRepository securityEventRepository;

    @Autowired
    private UserSessionRepository userSessionRepository;

    @Autowired
    private SecurityEventService securityEventService;

    @Autowired
    private ConnectionChatAccessPolicyService connectionChatAccessPolicyService;


    /**
     * List all users.
     */
    @GetMapping("/users")
    public ResponseEntity<List<Map<String, Object>>> listUsers() {
        log.info("Admin requesting user list");
        List<Map<String, Object>> users = userManagementService.listAllUsers();
        return ResponseEntity.ok(users);
    }

    /**
     * Create a new user.
     */
    @PostMapping("/users")
    public ResponseEntity<?> createUser(@RequestBody Map<String, String> request) {
        String username = request.get("username");
        String email = request.get("email");
        String role = request.get("role");
        String password = request.get("password");

        try {
            log.info("Admin creating new user: {}", email);
            Map<String, Object> newUser = userManagementService.createUser(username, password, email, role);
            return ResponseEntity.ok(newUser);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/users/invite")
    public ResponseEntity<?> inviteUser(@RequestBody Map<String, String> request) {
        return createUser(request);
    }

    /**
     * Get a specific user by ID.
     */
    @GetMapping("/users/{id}")
    public ResponseEntity<?> getUser(@PathVariable Long id) {
        log.info("Admin requesting user details for ID: {}", id);
        return userManagementService.getUserById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Update a user's role.
     */
    @PutMapping("/users/{id}/role")
    public ResponseEntity<?> updateUserRole(
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {
        String role = request.get("role");
        if (role == null || role.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", "Role is required"));
        }

        try {
            log.info("Admin updating user {} role to {}", id, role);
            Map<String, Object> updatedUser = userManagementService.updateUserRole(id, role);
            return ResponseEntity.ok(updatedUser);
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Reset a user's password.
     */
    @PutMapping("/users/{id}/password")
    public ResponseEntity<?> resetUserPassword(
            @PathVariable Long id,
            @RequestBody Map<String, String> request) {
        String password = request.get("password");
        try {
            userManagementService.resetPassword(id, password);
            return ResponseEntity.ok(Map.of("message", "Password updated successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/users/{id}/resend-invite")
    public ResponseEntity<?> resendInvite(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(userManagementService.resendInvite(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/users/{id}/lock")
    public ResponseEntity<?> lockUser(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(userManagementService.lockUser(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/users/{id}/unlock")
    public ResponseEntity<?> unlockUser(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(userManagementService.unlockUser(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/users/{id}/disable")
    public ResponseEntity<?> disableUser(@PathVariable Long id) {
        try {
            return ResponseEntity.ok(userManagementService.disableUser(id));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Delete a user.
     */
    @DeleteMapping("/users/{id}")
    public ResponseEntity<?> deleteUser(@PathVariable Long id) {
        try {
            log.info("Admin deleting user {}", id);
            userManagementService.deleteUser(id);
            return ResponseEntity.ok(Map.of("message", "User deleted successfully"));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest()
                    .body(Map.of("message", e.getMessage()));
        }
    }

    /**
     * Get all roles with their permissions.
     */
    @GetMapping("/roles")
    public ResponseEntity<List<Map<String, Object>>> getRoles() {
        log.info("Admin requesting roles list");
        List<Map<String, Object>> roles = userManagementService.getRolesWithPermissions();
        return ResponseEntity.ok(roles);
    }

    /**
     * Get all available permissions.
     */
    @GetMapping("/permissions")
    public ResponseEntity<List<Map<String, String>>> getPermissions() {
        log.info("Admin requesting permissions list");
        List<Map<String, String>> permissions = userManagementService.getAllPermissions();
        return ResponseEntity.ok(permissions);
    }

    @GetMapping("/users/{id}/connection-access")
    public ResponseEntity<?> getUserConnectionAccess(@PathVariable Long id) {
        return userRepository.findById(id)
            .map(user -> ResponseEntity.ok(connectionAccessService.getUserAssignments(user.getId(), user.getUsername())))
            .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/users/{id}/connection-access/{connectionId}")
    public ResponseEntity<?> upsertUserConnectionAccess(
        @PathVariable Long id,
        @PathVariable String connectionId,
        @RequestBody ConnectionAccessUpsertRequest request
    ) {
        if (request == null || request.getAccessLevel() == null || request.getAccessLevel().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Access level is required"));
        }

        try {
            User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
            ConnectionAccessLevel accessLevel = ConnectionAccessLevel.fromString(request.getAccessLevel());
            String grantedBy = accessControlService.getCurrentUsername();
            connectionAccessService.upsertGrant(connectionId, user.getUsername(), accessLevel, grantedBy);
            return ResponseEntity.ok(connectionAccessService.getUserAssignments(user.getId(), user.getUsername()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @DeleteMapping("/users/{id}/connection-access/{connectionId}")
    public ResponseEntity<?> revokeUserConnectionAccess(
        @PathVariable Long id,
        @PathVariable String connectionId
    ) {
        try {
            User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
            connectionAccessService.revokeGrant(connectionId, user.getUsername());
            return ResponseEntity.ok(connectionAccessService.getUserAssignments(user.getId(), user.getUsername()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/users/{id}/connection-access/{connectionId}/chat-policy")
    public ResponseEntity<?> getUserConnectionChatPolicy(
        @PathVariable Long id,
        @PathVariable String connectionId
    ) {
        return userRepository.findById(id)
            .map(user -> connectionChatAccessPolicyService.getPolicyResponse(connectionId, user.getUsername())
                .<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElse(ResponseEntity.ok(Map.of())))
            .orElse(ResponseEntity.notFound().build());
    }

    @PutMapping("/users/{id}/connection-access/{connectionId}/chat-policy")
    public ResponseEntity<?> upsertUserConnectionChatPolicy(
        @PathVariable Long id,
        @PathVariable String connectionId,
        @RequestBody ConnectionChatAccessPolicyUpsertRequest request
    ) {
        if (request == null || request.getPlainEnglishPolicy() == null || request.getPlainEnglishPolicy().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Policy text is required"));
        }
        try {
            User user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found"));
            return ResponseEntity.ok(connectionChatAccessPolicyService.savePolicy(
                connectionId,
                user.getUsername(),
                request.getPlainEnglishPolicy(),
                request.getActive(),
                accessControlService.getCurrentUsername()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @PostMapping("/connection-chat-policies/preview")
    public ResponseEntity<?> previewConnectionChatPolicy(@RequestBody PolicyPreviewRequest request) {
        if (request == null || request.getConnectionId() == null || request.getConnectionId().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Connection ID is required"));
        }
        if (request.getPlainEnglishPolicy() == null || request.getPlainEnglishPolicy().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Policy text is required"));
        }
        return ResponseEntity.ok(connectionChatAccessPolicyService.previewPolicy(
            request.getConnectionId(),
            request.getPlainEnglishPolicy()
        ));
    }

    @GetMapping("/connections/{connectionId}/connection-access")
    public ResponseEntity<?> getConnectionAssignments(@PathVariable String connectionId) {
        try {
            return ResponseEntity.ok(connectionAccessService.getAssignmentsForConnection(connectionId));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    @GetMapping("/security/events")
    public ResponseEntity<?> listSecurityEvents(
        @RequestParam(required = false) Long userId,
        @RequestParam(required = false) String userIds,
        @RequestParam(required = false) String email,
        @RequestParam(required = false) String eventType,
        @RequestParam(required = false) String outcome,
        @RequestParam(required = false) String clientIp,
        @RequestParam(required = false) String fromTime,
        @RequestParam(required = false) String toTime,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "50") int size
    ) {
        try {
            java.util.List<Long> parsedUserIds = parseUserIds(userIds);
            return ResponseEntity.ok(securityEventService.search(
                userId,
                parsedUserIds,
                email,
                eventType,
                outcome,
                clientIp,
                fromTime != null && !fromTime.isBlank() ? LocalDateTime.parse(fromTime) : null,
                toTime != null && !toTime.isBlank() ? LocalDateTime.parse(toTime) : null,
                PageRequest.of(page, size)
            ));
        } catch (RuntimeException e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid security log filters"));
        }
    }

    private java.util.List<Long> parseUserIds(String rawUserIds) {
        if (rawUserIds == null || rawUserIds.isBlank()) {
            return java.util.List.of();
        }
        return java.util.Arrays.stream(rawUserIds.split(","))
            .map(String::trim)
            .filter(value -> !value.isEmpty())
            .map(Long::valueOf)
            .toList();
    }

    @GetMapping("/security/sessions")
    public ResponseEntity<?> listUserSessions(@RequestParam(required = false) Long userId) {
        if (userId == null) {
            return ResponseEntity.ok(List.of());
        }
        return ResponseEntity.ok(userSessionRepository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
            .map(session -> {
                Map<String, Object> row = new java.util.LinkedHashMap<>();
                row.put("id", session.getId());
                row.put("userId", session.getUserId());
                row.put("clientIp", session.getClientIp());
                row.put("userAgent", session.getUserAgent());
                row.put("deviceLabel", session.getDeviceLabel());
                row.put("createdAt", session.getCreatedAt());
                row.put("lastSeenAt", session.getLastSeenAt());
                row.put("accessExpiresAt", session.getAccessExpiresAt());
                row.put("refreshExpiresAt", session.getRefreshExpiresAt());
                row.put("revokedAt", session.getRevokedAt());
                row.put("revokeReason", session.getRevokeReason());
                row.put("mfaVerifiedAt", session.getMfaVerifiedAt());
                return row;
            })
            .toList());
    }

    /**
     * Clear all slow query ingestion data for testing.
     * This removes: slow query history, source configs, fingerprints, performance history, regressions, baselines.
     */
    @DeleteMapping("/slow-query-data")
    @Transactional
    public ResponseEntity<?> clearSlowQueryData(@RequestParam(required = false) String connectionId) {
        log.warn("Admin clearing slow query data. connectionId={}", connectionId);

        try {
            int deletedCount = 0;

            if (connectionId != null && !connectionId.isEmpty()) {
                slowQueryHistoryRepository.deleteByConnectionId(connectionId);
                slowLogSourceConfigRepository.deleteByConnectionId(connectionId);
                queryFingerprintRepository.deleteByConnectionId(connectionId);
                queryPerformanceHistoryRepository.deleteByConnectionId(connectionId);
                queryPerformanceRegressionRepository.deleteByConnectionId(connectionId);
                queryPerformanceBaselineRepository.deleteByConnectionId(connectionId);
                log.info("Cleared slow query data for connection: {}", connectionId);
            } else {
                deletedCount += slowQueryHistoryRepository.findAll().size();
                slowQueryHistoryRepository.deleteAll();

                deletedCount += slowLogSourceConfigRepository.findAll().size();
                slowLogSourceConfigRepository.deleteAll();

                deletedCount += queryFingerprintRepository.findAll().size();
                queryFingerprintRepository.deleteAll();

                deletedCount += queryPerformanceHistoryRepository.findAll().size();
                queryPerformanceHistoryRepository.deleteAll();

                deletedCount += queryPerformanceRegressionRepository.findAll().size();
                queryPerformanceRegressionRepository.deleteAll();

                deletedCount += queryPerformanceBaselineRepository.findAll().size();
                queryPerformanceBaselineRepository.deleteAll();

                log.info("Cleared all slow query data. Total records deleted: {}", deletedCount);
            }

            return ResponseEntity.ok(Map.of(
                "message", "Slow query data cleared successfully",
                "connectionId", connectionId != null ? connectionId : "all",
                "tablesCleared", List.of(
                    "slow_query_history",
                    "slow_log_source_config",
                    "query_fingerprints",
                    "query_performance_history",
                    "query_performance_regression",
                    "query_performance_baseline"
                )
            ));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to clear slow query data", e);
            return ResponseEntity.internalServerError()
                .body(Map.of("message", "Failed to clear data: " + e.getMessage()));
        }
    }
}
