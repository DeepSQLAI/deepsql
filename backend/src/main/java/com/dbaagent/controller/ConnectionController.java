package com.dbaagent.controller;

import com.dbaagent.dto.ConnectionSummaryResponse;
import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.ConnectionTestResult;
import com.dbaagent.model.DatabaseConnection;
import com.dbaagent.model.DocumentationSource;
import com.dbaagent.model.SchemaDocumentation;
import com.dbaagent.repository.ConnectionInitHistoryRepository;
import com.dbaagent.repository.ConnectionInitStatusRepository;
import com.dbaagent.repository.SchemaDocumentationRepository;
import com.dbaagent.service.ConnectionService;
import com.dbaagent.service.scheduler.BrainInitSchedulerService;
import com.dbaagent.service.scheduler.BrainJobsService;
import com.dbaagent.service.CredentialService;
import com.dbaagent.service.SchemaScannerService;
import com.dbaagent.service.TrainingJobService;
import com.dbaagent.service.security.AccessControlService;
import com.dbaagent.service.security.ConnectionAccessService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/connections")
@RequiredArgsConstructor
@Slf4j
public class ConnectionController {
    private final ConnectionService connectionService;
    private final CredentialService credentialService;
    private final SchemaScannerService schemaScannerService;
    private final BrainInitSchedulerService brainInitSchedulerService;
    private final ConnectionInitStatusRepository connectionInitStatusRepository;
    private final ConnectionInitHistoryRepository connectionInitHistoryRepository;
    private final TrainingJobService trainingJobService;
    private final SchemaDocumentationRepository schemaDocumentationRepository;
    private final BrainJobsService brainJobsService;
    private final AccessControlService accessControlService;
    private final ConnectionAccessService connectionAccessService;
    private final com.dbaagent.repository.ConnectionAccessGrantRepository connectionAccessGrantRepository;

    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testConnection(@RequestBody ConnectionRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            ConnectionRequest testRequest = resolveConnectionTestRequest(request);
            ConnectionTestResult result = connectionService.testConnectionWithPrivileges(testRequest);

            response.put("success", result.isSuccess());
            response.put("connectionSuccessful", result.isConnectionSuccessful());
            response.put("sshTunnelSuccessful", result.isSshTunnelSuccessful());

            if (result.getErrorMessage() != null) {
                response.put("message", result.getErrorMessage());
            }

            // Build privilege check details
            List<Map<String, Object>> privilegeDetails = new ArrayList<>();
            for (ConnectionTestResult.PrivilegeCheck check : result.getPrivilegeChecks()) {
                Map<String, Object> checkMap = new HashMap<>();
                checkMap.put("name", check.getName());
                checkMap.put("scope", check.getScope());
                checkMap.put("reason", check.getReason());
                checkMap.put("granted", check.isGranted());
                if (!check.isGranted() && check.getErrorMessage() != null) {
                    checkMap.put("error", check.getErrorMessage());
                }
                privilegeDetails.add(checkMap);
            }
            response.put("privileges", privilegeDetails);

            // Build summary message
            if (!result.isConnectionSuccessful()) {
                if (Boolean.TRUE.equals(testRequest.getSshEnabled()) && !result.isSshTunnelSuccessful()) {
                    response.put("message", "SSH tunnel connection failed. Check SSH credentials and host.");
                } else {
                    response.put("message", result.getErrorMessage() != null
                        ? result.getErrorMessage()
                        : "Database connection failed. Please check your credentials and host.");
                }
            } else if (!result.allPrivilegesGranted()) {
                List<String> missing = result.getMissingPrivileges().stream()
                    .map(ConnectionTestResult.PrivilegeCheck::getName)
                    .toList();
                response.put("message", "Connection successful but missing privileges: " + String.join(", ", missing));
            } else {
                response.put("message", "Connection successful with all required privileges.");
            }

            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            response.put("success", false);
            response.put("connectionSuccessful", false);
            response.put("message", "Connection test failed: " + e.getMessage());
            response.put("privileges", new ArrayList<>());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    private ConnectionRequest resolveConnectionTestRequest(ConnectionRequest request) {
        if (request == null) {
            return new ConnectionRequest();
        }

        String connectionId = normalizeBlankToNull(request.getId());
        if (connectionId == null) {
            return request;
        }

        accessControlService.assertCanManageConnectionConfig(connectionId);

        ConnectionRequest saved;
        try {
            saved = credentialService.getDecryptedConnection(connectionId);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            throw new org.springframework.web.server.ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "Connection not found: " + connectionId
            );
        }

        if (isSavedConnectionReferenceOnly(request)) {
            return saved;
        }

        return mergeTestRequest(saved, request);
    }

    /**
     * `/connections/test` is used in two modes:
     *  - pre-save: callers send a complete unsaved config, including secrets;
     *  - saved-connection retest: callers send an id, or a masked summary row.
     *
     * Summary rows intentionally omit secrets. When an id is present, merge the
     * caller's explicit non-secret overrides with the decrypted persisted
     * secrets so saved SSH/SSL/password-backed connections can be retested
     * without leaking secrets through the list/show APIs.
     */
    private ConnectionRequest mergeTestRequest(ConnectionRequest saved, ConnectionRequest incoming) {
        ConnectionRequest merged = new ConnectionRequest();
        merged.setId(firstNonBlank(incoming.getId(), saved.getId()));
        merged.setConnectionName(firstNonBlank(incoming.getConnectionName(), saved.getConnectionName()));
        merged.setDbType(firstNonBlank(incoming.getDbType(), saved.getDbType()));
        merged.setHost(firstNonBlank(incoming.getHost(), saved.getHost()));
        merged.setPort(firstNonNull(incoming.getPort(), saved.getPort()));
        merged.setDatabase(firstNonBlank(incoming.getDatabase(), saved.getDatabase()));
        merged.setUsername(firstNonBlank(incoming.getUsername(), saved.getUsername()));
        merged.setPassword(firstNonBlank(incoming.getPassword(), saved.getPassword()));
        merged.setSsl(firstNonNull(incoming.getSsl(), saved.getSsl()));
        merged.setSslMode(firstNonBlank(incoming.getSslMode(), saved.getSslMode()));
        merged.setSslCaCertificate(firstNonBlank(incoming.getSslCaCertificate(), saved.getSslCaCertificate()));
        merged.setSslClientCertificate(firstNonBlank(incoming.getSslClientCertificate(), saved.getSslClientCertificate()));
        merged.setSslClientKey(firstNonBlank(incoming.getSslClientKey(), saved.getSslClientKey()));
        merged.setSslClientKeyPassphrase(firstNonBlank(incoming.getSslClientKeyPassphrase(), saved.getSslClientKeyPassphrase()));

        merged.setSshEnabled(firstNonNull(incoming.getSshEnabled(), saved.getSshEnabled()));
        merged.setSshAuthType(firstNonBlank(incoming.getSshAuthType(), saved.getSshAuthType()));
        merged.setSshHost(firstNonBlank(incoming.getSshHost(), saved.getSshHost()));
        merged.setSshPort(firstNonNull(incoming.getSshPort(), saved.getSshPort()));
        merged.setSshUsername(firstNonBlank(incoming.getSshUsername(), saved.getSshUsername()));
        merged.setSshPassword(firstNonBlank(incoming.getSshPassword(), saved.getSshPassword()));
        merged.setSshPrivateKey(firstNonBlank(incoming.getSshPrivateKey(), saved.getSshPrivateKey()));
        merged.setSshPassphrase(firstNonBlank(incoming.getSshPassphrase(), saved.getSshPassphrase()));

        merged.setCloudProvider(firstNonBlank(incoming.getCloudProvider(), saved.getCloudProvider()));
        merged.setManagedService(firstNonBlank(incoming.getManagedService(), saved.getManagedService()));
        merged.setInstanceClass(firstNonBlank(incoming.getInstanceClass(), saved.getInstanceClass()));
        merged.setInstanceVcpus(firstNonNull(incoming.getInstanceVcpus(), saved.getInstanceVcpus()));
        merged.setInstanceMemoryGb(firstNonNull(incoming.getInstanceMemoryGb(), saved.getInstanceMemoryGb()));
        merged.setStorageType(firstNonBlank(incoming.getStorageType(), saved.getStorageType()));
        merged.setStorageMaxIops(firstNonNull(incoming.getStorageMaxIops(), saved.getStorageMaxIops()));
        merged.setEnableDataSampling(firstNonNull(incoming.getEnableDataSampling(), saved.getEnableDataSampling()));

        return merged;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> saveConnection(@RequestBody ConnectionRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            // Creating a connection is not scoped to an existing connection id, so none of
            // the assertCanManage*Connection* checks apply here — this endpoint had no
            // authorization at all, and any authenticated user could add (then edit and
            // delete) their own connection. Hiding the Connections button did not stop it.
            accessControlService.assertCanManageConnections();

            // Test connection with privilege checks
            ConnectionTestResult result = connectionService.testConnectionWithPrivileges(request);

            // Only block save if connection itself fails (not just missing privileges)
            if (!result.isConnectionSuccessful()) {
                response.put("success", false);
                response.put("connectionSuccessful", false);
                response.put("sshTunnelSuccessful", result.isSshTunnelSuccessful());
                response.put("message", result.getErrorMessage() != null
                    ? result.getErrorMessage()
                    : "Connection test failed. Cannot save connection.");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }

            // Save the connection (even if some privileges are missing)
            String username = accessControlService.getCurrentUsername();
            String owner = username != null ? username : "admin";
            DatabaseConnection connection = credentialService.saveConnection(request, owner);

            // Schedule init pipeline via db-scheduler (survives VM crashes)
            brainInitSchedulerService.scheduleInit(connection.getId());

            // Build privilege details for response
            List<Map<String, Object>> privilegeDetails = new ArrayList<>();
            for (ConnectionTestResult.PrivilegeCheck check : result.getPrivilegeChecks()) {
                Map<String, Object> checkMap = new HashMap<>();
                checkMap.put("name", check.getName());
                checkMap.put("scope", check.getScope());
                checkMap.put("granted", check.isGranted());
                if (!check.isGranted() && check.getErrorMessage() != null) {
                    checkMap.put("error", check.getErrorMessage());
                }
                privilegeDetails.add(checkMap);
            }
            response.put("privileges", privilegeDetails);

            response.put("success", true);
            response.put("connectionId", connection.getId());

            // Warn about missing privileges but still save
            if (!result.allPrivilegesGranted()) {
                List<String> missing = result.getMissingPrivileges().stream()
                    .map(ConnectionTestResult.PrivilegeCheck::getName)
                    .toList();
                response.put("message", "Connection saved. Warning: missing privileges: " + String.join(", ", missing));
                response.put("missingPrivileges", missing);
            } else {
                response.put("message", "Connection saved successfully with all required privileges");
            }
            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to save connection: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Clone an existing connection — useful for adding the read-replica of an
     * already-configured master without re-entering credentials. The new
     * connection inherits every field from the source (db type, port, db name,
     * user, password, SSL config, SSH tunnel, instance sizing, etc.) with two
     * mandatory overrides:
     *
     * <ul>
     *   <li>{@code connectionName} — the new display name (must be different
     *       from the source's).
     *   <li>{@code host} — either a full replacement host, or a
     *       {@code hostReplace: {find, replace}} substring substitution
     *       applied to the source's host. Exactly one form must be supplied.
     * </ul>
     *
     * <p>Body example:
     * <pre>{
     *   "connectionName": "aws-rds-replica",
     *   "hostReplace": {"find": "sfprodrds.", "replace": "sfprodrds-rr."}
     * }</pre>
     *
     * <p>Admin only — same gate as deleteConnection. The cloned connection
     * runs through the same test+save path as a manual {@code POST /connections},
     * so credentials are validated before persistence; if the replica isn't
     * reachable the clone is rejected with the test failure message.
     */
    @PostMapping("/{id}/clone")
    @SuppressWarnings("unchecked")
    public ResponseEntity<Map<String, Object>> cloneConnection(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        Map<String, Object> response = new HashMap<>();
        try {
            accessControlService.assertCanManageConnectionConfig(id);

            String newName = body.get("connectionName") == null
                ? null : body.get("connectionName").toString();
            if (newName == null || newName.isBlank()) {
                response.put("success", false);
                response.put("message", "connectionName is required");
                return ResponseEntity.badRequest().body(response);
            }

            ConnectionRequest source;
            try {
                source = credentialService.getDecryptedConnection(id);
            } catch (org.springframework.web.server.ResponseStatusException e) {
                throw e;
            } catch (Exception e) {
                response.put("success", false);
                response.put("message", "Source connection not found: " + id);
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            // Apply host override — either explicit replacement or
            // substring substitution against the source host.
            String hostOverride = body.get("host") == null
                ? null : body.get("host").toString();
            Map<String, Object> hostReplace = body.get("hostReplace") instanceof Map
                ? (Map<String, Object>) body.get("hostReplace") : null;
            if (hostOverride != null && hostReplace != null) {
                response.put("success", false);
                response.put("message", "Provide either 'host' or 'hostReplace', not both");
                return ResponseEntity.badRequest().body(response);
            }
            if (hostOverride == null && hostReplace == null) {
                response.put("success", false);
                response.put("message", "One of 'host' or 'hostReplace' is required");
                return ResponseEntity.badRequest().body(response);
            }
            String resolvedHost;
            if (hostOverride != null) {
                resolvedHost = hostOverride;
            } else {
                String find = hostReplace.get("find") == null ? null : hostReplace.get("find").toString();
                String replace = hostReplace.get("replace") == null ? null : hostReplace.get("replace").toString();
                if (find == null || find.isBlank() || replace == null) {
                    response.put("success", false);
                    response.put("message", "hostReplace requires non-blank 'find' and 'replace' fields");
                    return ResponseEntity.badRequest().body(response);
                }
                String sourceHost = source.getHost() != null ? source.getHost() : "";
                if (!sourceHost.contains(find)) {
                    response.put("success", false);
                    response.put("message", "Source host does not contain '" + find + "' — nothing to replace");
                    return ResponseEntity.badRequest().body(response);
                }
                resolvedHost = sourceHost.replace(find, replace);
            }

            // Build the clone request — copy everything from source, override
            // name + host, clear the source's id so saveConnection allocates a fresh one.
            ConnectionRequest clone = source;
            clone.setId(null);
            clone.setConnectionName(newName);
            clone.setHost(resolvedHost);

            // Test + save through the standard path so credentials are validated.
            ConnectionTestResult test = connectionService.testConnectionWithPrivileges(clone);
            if (!test.isConnectionSuccessful()) {
                response.put("success", false);
                response.put("connectionSuccessful", false);
                response.put("message", "Replica connection test failed: "
                    + (test.getErrorMessage() != null ? test.getErrorMessage() : "unknown error"));
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }

            String username = accessControlService.getCurrentUsername();
            String owner = username != null ? username : "admin";
            DatabaseConnection saved = credentialService.saveConnection(clone, owner);

            // Copy the source's access grants to the clone. Without this, only
            // the cloning admin can access the new connection, and every other
            // team member that had access to the master gets 403s on the
            // replica — which we found out the hard way when the MCP
            // get_brain_context calls started 500'ing for users who could see
            // the master fine. The grant copy is best-effort: a failure here
            // shouldn't fail the whole clone since the user can re-grant
            // manually via the access UI.
            int grantsCopied = 0;
            try {
                var sourceGrants = connectionAccessGrantRepository
                    .findAllByConnectionIdOrderByUsernameAsc(id);
                for (var grant : sourceGrants) {
                    try {
                        connectionAccessService.upsertGrant(
                            saved.getId(),
                            grant.getUsername(),
                            grant.getAccessLevel(),
                            owner);
                        grantsCopied++;
                    } catch (Exception inner) {
                        log.warn("Failed to copy grant {} → {} on clone {}: {}",
                            grant.getUsername(), grant.getAccessLevel(),
                            saved.getId(), inner.getMessage());
                    }
                }
                log.info("Cloned {} access grant(s) from {} to {}",
                    grantsCopied, id, saved.getId());
            } catch (org.springframework.web.server.ResponseStatusException e) {
                throw e;
            } catch (Exception e) {
                log.warn("Could not copy access grants from {} to {}: {}",
                    id, saved.getId(), e.getMessage());
            }

            brainInitSchedulerService.scheduleInit(saved.getId());

            response.put("success", true);
            response.put("connectionId", saved.getId());
            response.put("sourceConnectionId", id);
            response.put("host", resolvedHost);
            response.put("grantsCopied", grantsCopied);
            response.put("message", "Connection cloned successfully. Brain init scheduled.");
            return ResponseEntity.ok(response);
        } catch (org.springframework.security.access.AccessDeniedException ade) {
            response.put("success", false);
            response.put("message", "Not authorized to clone this connection");
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to clone connection {}: {}", id, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Failed to clone connection: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @GetMapping
    public ResponseEntity<List<ConnectionSummaryResponse>> getAllConnections() {
        try {
            String username = accessControlService.getCurrentUsername();
            boolean isAdmin = accessControlService.isCurrentUserAdmin();
            List<DatabaseConnection> connections = credentialService.getConnectionsForUser(username, isAdmin);
            List<ConnectionSummaryResponse> decryptedConnections = connections.stream()
                .map(conn -> toSummary(conn, username, isAdmin))
                .toList();
            return ResponseEntity.ok(decryptedConnections);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteConnection(@PathVariable String id) {
        Map<String, Object> response = new HashMap<>();
        try {
            accessControlService.assertCanManageConnectionConfig(id);
            connectionService.closeConnectionPool(id);
            connectionAccessService.deleteAllGrantsForConnection(id);
            credentialService.deleteConnection(id);
            response.put("success", true);
            response.put("message", "Connection deleted successfully");
            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to delete connection: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateConnection(
            @PathVariable String id,
            @RequestBody ConnectionRequest request) {
        Map<String, Object> response = new HashMap<>();
        try {
            accessControlService.assertCanManageConnectionConfig(id);
            // For update, we need to merge with existing connection if secrets are not provided
            ConnectionRequest existingDecrypted = credentialService.getDecryptedConnection(id);

            // Merge secrets from existing if not provided in update request
            if (request.getPassword() == null || request.getPassword().trim().isEmpty()) {
                request.setPassword(existingDecrypted.getPassword());
            }
            if (Boolean.TRUE.equals(request.getSshEnabled())) {
                if (request.getSshPassword() == null || request.getSshPassword().trim().isEmpty()) {
                    request.setSshPassword(existingDecrypted.getSshPassword());
                }
                if (request.getSshPrivateKey() == null || request.getSshPrivateKey().trim().isEmpty()) {
                    request.setSshPrivateKey(existingDecrypted.getSshPrivateKey());
                }
                if (request.getSshPassphrase() == null || request.getSshPassphrase().trim().isEmpty()) {
                    request.setSshPassphrase(existingDecrypted.getSshPassphrase());
                }
            }
            // Merge SSL certificates if not provided
            String effectiveSslMode = request.getEffectiveSslMode();
            if (!"none".equals(effectiveSslMode)) {
                if (request.getSslCaCertificate() == null || request.getSslCaCertificate().trim().isEmpty()) {
                    request.setSslCaCertificate(existingDecrypted.getSslCaCertificate());
                }
                if ("server-client".equals(effectiveSslMode)) {
                    if (request.getSslClientCertificate() == null || request.getSslClientCertificate().trim().isEmpty()) {
                        request.setSslClientCertificate(existingDecrypted.getSslClientCertificate());
                    }
                    if (request.getSslClientKey() == null || request.getSslClientKey().trim().isEmpty()) {
                        request.setSslClientKey(existingDecrypted.getSslClientKey());
                    }
                    if (request.getSslClientKeyPassphrase() == null || request.getSslClientKeyPassphrase().trim().isEmpty()) {
                        request.setSslClientKeyPassphrase(existingDecrypted.getSslClientKeyPassphrase());
                    }
                }
            }

            // Always test connection with privilege checks
            ConnectionTestResult result = connectionService.testConnectionWithPrivileges(request);

            // Only block update if connection itself fails (not just missing privileges)
            if (!result.isConnectionSuccessful()) {
                response.put("success", false);
                response.put("connectionSuccessful", false);
                response.put("sshTunnelSuccessful", result.isSshTunnelSuccessful());
                response.put("message", result.getErrorMessage() != null
                    ? result.getErrorMessage()
                    : "Connection test failed with updated settings.");
                return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
            }

            // Close existing connection pool before updating
            connectionService.closeConnectionPool(id);

            // Update the connection (even if some privileges are missing)
            DatabaseConnection connection = credentialService.updateConnection(id, request);

            // Build privilege details for response
            List<Map<String, Object>> privilegeDetails = new ArrayList<>();
            for (ConnectionTestResult.PrivilegeCheck check : result.getPrivilegeChecks()) {
                Map<String, Object> checkMap = new HashMap<>();
                checkMap.put("name", check.getName());
                checkMap.put("scope", check.getScope());
                checkMap.put("granted", check.isGranted());
                if (!check.isGranted() && check.getErrorMessage() != null) {
                    checkMap.put("error", check.getErrorMessage());
                }
                privilegeDetails.add(checkMap);
            }
            response.put("privileges", privilegeDetails);

            response.put("success", true);
            response.put("connectionId", connection.getId());

            // Warn about missing privileges but still update
            if (!result.allPrivilegesGranted()) {
                List<String> missing = result.getMissingPrivileges().stream()
                    .map(ConnectionTestResult.PrivilegeCheck::getName)
                    .toList();
                response.put("message", "Connection updated. Warning: missing privileges: " + String.join(", ", missing));
                response.put("missingPrivileges", missing);
            } else {
                response.put("message", "Connection updated successfully with all required privileges");
            }
            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to update connection: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Pre-warm the schema cache for a connection.
     * Call this when selecting a connection to avoid cold start on first chat.
     * This is a fire-and-forget operation - returns immediately while warming happens in background.
     */
    @PostMapping("/{id}/warmup")
    public ResponseEntity<Map<String, Object>> warmupConnection(@PathVariable String id) {
        Map<String, Object> response = new HashMap<>();
        try {
            accessControlService.assertCanUseChatEditor(id);

            // Check if already cached
            boolean alreadyCached = schemaScannerService.isSchemaCached(id);
            if (alreadyCached) {
                response.put("success", true);
                response.put("cached", true);
                response.put("message", "Schema already cached");
                return ResponseEntity.ok(response);
            }

            // Start async warmup (fire-and-forget)
            schemaScannerService.warmupSchemaCache(id);

            response.put("success", true);
            response.put("cached", false);
            response.put("message", "Schema warmup started in background");
            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to start warmup: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @GetMapping("/{id}/init-status")
    public ResponseEntity<?> getInitStatus(@PathVariable String id) {
        try {
            accessControlService.assertCanReadConnectionContent(id);
            return connectionInitStatusRepository.findById(id)
                .map(s -> ResponseEntity.ok((Object) s))
                .orElse(ResponseEntity.notFound().build());
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "Failed to get init status: " + e.getMessage()));
        }
    }

    @GetMapping("/{id}/init-progress")
    public ResponseEntity<?> subscribeInitProgress(@PathVariable String id) {
        try {
            accessControlService.assertCanReadConnectionContent(id);
            return ResponseEntity.ok(trainingJobService.subscribeInitProgress(id));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "Failed to subscribe to init progress: " + e.getMessage()));
        }
    }

    @PostMapping("/{id}/reinit")
    public ResponseEntity<?> reinitialize(
        @PathVariable String id,
        @RequestParam(name = "force", defaultValue = "false") boolean force
    ) {
        try {
            accessControlService.assertCanManageConnectionContent(id);
            var plan = brainInitSchedulerService.planAndScheduleInit(id, force);

            Map<String, Object> payload = new HashMap<>();
            payload.put("mode", plan.mode().name());
            payload.put("skipped", plan.skipped());
            payload.put("reason", plan.reason());
            payload.put("dirtySources", plan.dirtySources());
            if (plan.startedFromStage() != null) {
                payload.put("startedFromStage", plan.startedFromStage().name());
            }

            String message = switch (plan.mode()) {
                case RESUME_FAILED -> "Initialization resumed from " + plan.startedFromStage().name();
                case QUICK_VERIFY -> plan.reason();
                case FULL_OR_PARTIAL_REFRESH -> "Re-initialization started from "
                    + plan.startedFromStage().name();
            };
            payload.put("message", message);
            return ResponseEntity.ok(payload);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "Failed to start re-initialization: " + e.getMessage()));
        }
    }

    @PostMapping("/{id}/cancel-init")
    public ResponseEntity<?> cancelInit(@PathVariable String id) {
        try {
            accessControlService.assertCanManageConnectionContent(id);
            var existing = connectionInitStatusRepository.findById(id);
            if (existing.isEmpty()) {
                return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(Map.of("message", "No initialization found for this connection"));
            }
            var stage = existing.get().getCurrentStage();
            if (stage == com.dbaagent.model.InitStage.COMPLETED
                    || stage == com.dbaagent.model.InitStage.FAILED) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                    .body(Map.of("message", "No active initialization to cancel"));
            }
            brainInitSchedulerService.cancelInit(id);
            return ResponseEntity.ok(Map.of("message", "Cancellation requested"));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "Failed to cancel initialization: " + e.getMessage()));
        }
    }

    @GetMapping("/{id}/init-history")
    public ResponseEntity<?> getInitHistory(@PathVariable String id) {
        try {
            accessControlService.assertCanReadConnectionContent(id);
            return ResponseEntity.ok(
                connectionInitHistoryRepository.findByConnectionIdOrderByCreatedAtDesc(id));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "Failed to get init history: " + e.getMessage()));
        }
    }

    @GetMapping("/{id}/init-summary")
    public ResponseEntity<?> getInitSummary(@PathVariable String id) {
        try {
            accessControlService.assertCanReadConnectionContent(id);

            long aiDocs = schemaDocumentationRepository.countByConnectionIdAndSource(id,
                DocumentationSource.AI_GENERATED);
            long userDocs = schemaDocumentationRepository.countByConnectionIdAndSource(id,
                DocumentationSource.USER);
            var allDocs = schemaDocumentationRepository.findByConnectionId(id);
            long tableDocs = allDocs.stream()
                .filter(d -> d.getObjectType() == SchemaDocumentation.DocumentationType.TABLE)
                .count();
            long columnDocs = allDocs.stream()
                .filter(d -> d.getObjectType() == SchemaDocumentation.DocumentationType.COLUMN)
                .count();

            // Use cached schema if available — avoid triggering a live DB scan
            int totalTables = 0;
            int totalColumns = 0;
            if (schemaScannerService.isSchemaCached(id)) {
                try {
                    var schema = schemaScannerService.scanSchema(id);
                    totalTables = schema.getTables().size();
                    totalColumns = schema.getTables().stream()
                        .mapToInt(t -> t.getColumns().size()).sum();
                } catch (org.springframework.web.server.ResponseStatusException e) {
                    throw e;
                } catch (Exception e) {
                    log.debug("Schema cache miss for {}: {}", id, e.getMessage());
                }
            }

            boolean dataSamplingEnabled = connectionService.isDataSamplingEnabled(id);

            Map<String, Object> summary = new HashMap<>();
            summary.put("totalTables", totalTables);
            summary.put("totalColumns", totalColumns);
            summary.put("aiDescriptions", aiDocs);
            summary.put("userDescriptions", userDocs);
            summary.put("tableDescriptions", tableDocs);
            summary.put("columnDescriptions", columnDocs);
            summary.put("dataSamplingEnabled", dataSamplingEnabled);
            summary.put("embeddingModel", "text-embedding-3-large");
            summary.put("aiModel", "gpt-5.4-pro");
            return ResponseEntity.ok(summary);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "Failed to get init summary: " + e.getMessage()));
        }
    }

    @GetMapping("/{id}/brain-jobs")
    public ResponseEntity<?> getBrainJobs(@PathVariable String id) {
        try {
            accessControlService.assertCanReadConnectionContent(id);
            return ResponseEntity.ok(brainJobsService.listJobs(id));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "Failed to get Brain jobs: " + e.getMessage()));
        }
    }

    @PostMapping("/{id}/brain-jobs/{jobKey}/run")
    public ResponseEntity<?> runBrainJob(@PathVariable String id, @PathVariable String jobKey) {
        try {
            accessControlService.assertCanManageConnectionContent(id);
            return ResponseEntity.ok(brainJobsService.runJob(id, jobKey));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("message", e.getMessage()));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("message", "Failed to run Brain job: " + e.getMessage()));
        }
    }

    private ConnectionSummaryResponse toSummary(DatabaseConnection conn, String username, boolean isAdmin) {
        ConnectionSummaryResponse summary = new ConnectionSummaryResponse();
        try {
            ConnectionRequest decrypted = credentialService.getDecryptedConnection(conn.getId());
            summary.setId(conn.getId());
            summary.setConnectionName(decrypted.getConnectionName());
            summary.setDbType(decrypted.getDbType());
            summary.setHost(decrypted.getHost());
            summary.setPort(decrypted.getPort());
            summary.setDatabase(decrypted.getDatabase());
            summary.setUsername(decrypted.getUsername());
            summary.setSsl(decrypted.getSsl());
            summary.setSslMode(decrypted.getSslMode());
            summary.setSshEnabled(decrypted.getSshEnabled());
            summary.setSshAuthType(decrypted.getSshAuthType());
            summary.setSshHost(decrypted.getSshHost());
            summary.setSshPort(decrypted.getSshPort());
            summary.setSshUsername(decrypted.getSshUsername());
            summary.setCloudProvider(decrypted.getCloudProvider());
            summary.setManagedService(decrypted.getManagedService());
            summary.setInstanceClass(decrypted.getInstanceClass());
            summary.setInstanceVcpus(decrypted.getInstanceVcpus());
            summary.setInstanceMemoryGb(decrypted.getInstanceMemoryGb());
            summary.setStorageType(decrypted.getStorageType());
            summary.setStorageMaxIops(decrypted.getStorageMaxIops());
            summary.setEnableDataSampling(decrypted.getEnableDataSampling());
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            summary.setId(conn.getId());
            summary.setConnectionName(conn.getConnectionName());
            summary.setDbType(conn.getDbType());
        }

        var resolved = connectionAccessService.resolveAccess(conn, username, isAdmin);
        summary.setOwnerUsername(conn.getOwnerUsername());
        summary.setOwnershipType(resolved.getOwnershipType() != null ? resolved.getOwnershipType().name() : null);
        summary.setAccessLevel(resolved.getEffectiveAccess().name());
        summary.setCanManageConfig(resolved.canManageConfig());
        summary.setCanManageContent(resolved.canManageContent());
        return summary;
    }

    private String normalizeBlankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String firstNonBlank(String preferred, String fallback) {
        String normalized = normalizeBlankToNull(preferred);
        return normalized != null ? normalized : fallback;
    }

    private <T> T firstNonNull(T preferred, T fallback) {
        return preferred != null ? preferred : fallback;
    }

    private boolean isSavedConnectionReferenceOnly(ConnectionRequest request) {
        return normalizeBlankToNull(request.getDbType()) == null
            && normalizeBlankToNull(request.getHost()) == null
            && normalizeBlankToNull(request.getDatabase()) == null
            && normalizeBlankToNull(request.getUsername()) == null
            && normalizeBlankToNull(request.getPassword()) == null
            && normalizeBlankToNull(request.getSshHost()) == null
            && normalizeBlankToNull(request.getSshUsername()) == null
            && normalizeBlankToNull(request.getSshPassword()) == null
            && normalizeBlankToNull(request.getSshPrivateKey()) == null;
    }
}
