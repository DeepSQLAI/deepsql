package com.dbaagent.service.security;

import com.dbaagent.dto.AssignableConnectionOption;
import com.dbaagent.dto.ConnectionAccessGrantResponse;
import com.dbaagent.dto.UserConnectionAccessResponse;
import com.dbaagent.model.*;
import com.dbaagent.repository.ConnectionAccessGrantRepository;
import com.dbaagent.repository.CredentialRepository;
import com.dbaagent.repository.UserRepository;
import com.dbaagent.service.ConnectionChatAccessPolicyService;
import com.dbaagent.service.SecurityEventService;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ConnectionAccessService {

    private final CredentialRepository credentialRepository;
    private final ConnectionAccessGrantRepository grantRepository;
    private final UserRepository userRepository;
    private final SecurityEventService securityEventService;
    private final ConnectionChatAccessPolicyService connectionChatAccessPolicyService;

    @Transactional(readOnly = true)
    public ResolvedConnectionAccess resolveAccess(String connectionId, String username, boolean isAdmin) {
        DatabaseConnection connection = credentialRepository.findById(connectionId)
            .orElseThrow(() -> new RuntimeException("Connection not found: " + connectionId));
        return resolveAccess(connection, username, isAdmin);
    }

    @Transactional(readOnly = true)
    public ResolvedConnectionAccess resolveAccess(DatabaseConnection connection, String username, boolean isAdmin) {
        if (isAdmin) {
            return buildResolved(connection, ConnectionOwnershipType.ADMIN, EffectiveConnectionAccess.ADMIN);
        }

        if (username == null || username.isBlank()) {
            return buildResolved(connection, null, EffectiveConnectionAccess.NONE);
        }

        if (matchesUsername(connection.getOwnerUsername(), username)) {
            return buildResolved(connection, ConnectionOwnershipType.OWNED, EffectiveConnectionAccess.OWNER);
        }

        if (!isAssignableConnection(connection)) {
            return buildResolved(connection, null, EffectiveConnectionAccess.NONE);
        }

        return grantRepository.findByConnectionIdAndUsernameIgnoreCase(connection.getId(), username)
            .map(grant -> buildResolved(
                connection,
                ConnectionOwnershipType.ASSIGNED,
                grant.getAccessLevel() == ConnectionAccessLevel.FULL_CONTENT
                    ? EffectiveConnectionAccess.FULL_CONTENT
                    : EffectiveConnectionAccess.CHAT_EDITOR
            ))
            .orElseGet(() -> buildResolved(connection, null, EffectiveConnectionAccess.NONE));
    }

    @Transactional(readOnly = true)
    public List<DatabaseConnection> getVisibleConnections(String username, boolean isAdmin) {
        List<DatabaseConnection> allConnections = normalizeOwners(credentialRepository.findAllByOrderByCreatedAtDesc());
        if (username == null || username.isBlank() || isAdmin) {
            return allConnections;
        }
        return allConnections.stream()
            .filter(connection -> resolveAccess(connection, username, false).getEffectiveAccess().canUseConnection())
            .sorted(Comparator.comparing(DatabaseConnection::getCreatedAt, Comparator.nullsLast(Comparator.reverseOrder())))
            .toList();
    }

    @Transactional(readOnly = true)
    public List<AssignableConnectionOption> getAssignableConnections(String adminUsername) {
        return normalizeOwners(credentialRepository.findAllByOwnerUsernameOrderByCreatedAtDesc(adminUsername)).stream()
            .filter(this::isAssignableConnection)
            .map(connection -> AssignableConnectionOption.builder()
                .connectionId(connection.getId())
                .connectionName(connection.getConnectionName())
                .dbType(connection.getDbType())
                .build())
            .toList();
    }

    @Transactional(readOnly = true)
    public UserConnectionAccessResponse getUserAssignments(Long userId, String username) {
        Map<String, DatabaseConnection> connectionById = credentialRepository.findAllByOrderByCreatedAtDesc().stream()
            .collect(Collectors.toMap(DatabaseConnection::getId, Function.identity(), (left, right) -> left));

        List<ConnectionAccessGrantResponse> assignments = grantRepository
            .findAllByUsernameIgnoreCaseOrderByUpdatedAtDesc(username).stream()
            .map(grant -> toResponse(grant, connectionById.get(grant.getConnectionId())))
            .filter(response -> response != null)
            .toList();

        return UserConnectionAccessResponse.builder()
            .userId(userId)
            .username(username)
            .assignments(assignments)
            .assignableConnections(getAdminOwnedAssignableConnections())
            .build();
    }

    @Transactional(readOnly = true)
    public List<ConnectionAccessGrantResponse> getAssignmentsForConnection(String connectionId) {
        Map<String, DatabaseConnection> connections = credentialRepository.findAllByOrderByCreatedAtDesc().stream()
            .collect(Collectors.toMap(DatabaseConnection::getId, Function.identity(), (left, right) -> left));
        DatabaseConnection connection = connections.get(connectionId);
        if (connection == null) {
            throw new IllegalArgumentException("Connection not found");
        }
        return grantRepository.findAllByConnectionIdOrderByUsernameAsc(connectionId).stream()
            .map(grant -> toResponse(grant, connection))
            .toList();
    }

    @Transactional
    public ConnectionAccessGrant upsertGrant(String connectionId, String username, ConnectionAccessLevel accessLevel, String grantedBy) {
        DatabaseConnection connection = credentialRepository.findById(connectionId)
            .orElseThrow(() -> new IllegalArgumentException("Connection not found"));

        if (!isAssignableConnection(connection)) {
            throw new IllegalArgumentException("Only admin-owned connections can be assigned in V1");
        }
        if (matchesUsername(connection.getOwnerUsername(), username)) {
            throw new IllegalArgumentException("Connection owner already has full control");
        }
        userRepository.findByUsername(username)
            .orElseThrow(() -> new IllegalArgumentException("User not found"));

        ConnectionAccessGrant grant = grantRepository
            .findByConnectionIdAndUsernameIgnoreCase(connectionId, username)
            .orElseGet(ConnectionAccessGrant::new);
        grant.setConnectionId(connectionId);
        grant.setUsername(username);
        grant.setAccessLevel(accessLevel);
        grant.setGrantedBy(grantedBy);
        ConnectionAccessGrant saved = grantRepository.save(grant);
        securityEventService.log(SecurityEventService.EventRequest.builder()
            .eventType(SecurityEventType.CONNECTION_ACCESS_CHANGED)
            .outcome(SecurityEventOutcome.SUCCESS)
            .email(resolveEmailForUsername(username))
            .targetResource("connection:" + connectionId)
            .metadata(Map.of(
                "connectionName", connection.getConnectionName(),
                "username", username,
                "accessLevel", accessLevel.name(),
                "grantedBy", grantedBy,
                "action", "UPSERT"
            ))
            .build());
        return saved;
    }

    @Transactional
    public void revokeGrant(String connectionId, String username) {
        grantRepository.findByConnectionIdAndUsernameIgnoreCase(connectionId, username)
            .ifPresent(grant -> {
                grantRepository.delete(grant);
                securityEventService.log(SecurityEventService.EventRequest.builder()
                    .eventType(SecurityEventType.CONNECTION_ACCESS_CHANGED)
                    .outcome(SecurityEventOutcome.SUCCESS)
                    .email(resolveEmailForUsername(username))
                    .targetResource("connection:" + connectionId)
                    .metadata(Map.of(
                        "username", username,
                        "accessLevel", grant.getAccessLevel().name(),
                        "action", "REVOKE"
                    ))
                    .build());
            });
    }

    @Transactional
    public void deleteAllGrantsForConnection(String connectionId) {
        grantRepository.deleteByConnectionId(connectionId);
    }

    private ConnectionAccessGrantResponse toResponse(ConnectionAccessGrant grant, DatabaseConnection connection) {
        if (connection == null || !isAssignableConnection(connection)) {
            return null;
        }
        return ConnectionAccessGrantResponse.builder()
            .connectionId(connection.getId())
            .connectionName(connection.getConnectionName())
            .dbType(connection.getDbType())
            .accessLevel(grant.getAccessLevel().name())
            .chatAccessPolicy(connectionChatAccessPolicyService.getPolicyResponse(connection.getId(), grant.getUsername()).orElse(null))
            .grantedBy(grant.getGrantedBy())
            .createdAt(grant.getCreatedAt())
            .updatedAt(grant.getUpdatedAt())
            .build();
    }

    private List<AssignableConnectionOption> getAdminOwnedAssignableConnections() {
        Set<String> adminUsernames = userRepository.findAll().stream()
            .filter(User::isAdmin)
            .map(User::getUsername)
            .filter(username -> username != null && !username.isBlank())
            .map(username -> username.toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());

        return normalizeOwners(credentialRepository.findAllByOrderByCreatedAtDesc()).stream()
            .filter(connection -> adminUsernames.contains(normalizeUsername(connection.getOwnerUsername())))
            .map(connection -> AssignableConnectionOption.builder()
                .connectionId(connection.getId())
                .connectionName(connection.getConnectionName())
                .dbType(connection.getDbType())
                .build())
            .toList();
    }

    private boolean isAssignableConnection(DatabaseConnection connection) {
        String ownerUsername = connection.getOwnerUsername();
        if (ownerUsername == null || ownerUsername.isBlank()) {
            return false;
        }
        return userRepository.findByUsername(ownerUsername)
            .map(User::isAdmin)
            .orElse(false);
    }

    private List<DatabaseConnection> normalizeOwners(List<DatabaseConnection> connections) {
        connections.forEach(conn -> {
            if (conn.getOwnerUsername() == null) {
                conn.setOwnerUsername("admin");
            }
        });
        return connections;
    }

    private boolean matchesUsername(String left, String right) {
        return normalizeUsername(left).equals(normalizeUsername(right));
    }

    private String normalizeUsername(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    private String resolveEmailForUsername(String username) {
        return userRepository.findByUsername(username)
            .map(User::getEmail)
            .orElse(null);
    }

    private ResolvedConnectionAccess buildResolved(
        DatabaseConnection connection,
        ConnectionOwnershipType ownershipType,
        EffectiveConnectionAccess effectiveAccess
    ) {
        return ResolvedConnectionAccess.builder()
            .connection(connection)
            .ownershipType(ownershipType)
            .effectiveAccess(effectiveAccess)
            .build();
    }

    @Value
    @Builder
    public static class ResolvedConnectionAccess {
        DatabaseConnection connection;
        ConnectionOwnershipType ownershipType;
        EffectiveConnectionAccess effectiveAccess;

        public boolean canUseConnection() {
            return effectiveAccess.canUseConnection();
        }

        public boolean canUseChatEditor() {
            return effectiveAccess.canUseChatEditor();
        }

        public boolean canManageContent() {
            return effectiveAccess.canManageContent();
        }

        public boolean canManageConfig() {
            return effectiveAccess.canManageConfig();
        }
    }
}
