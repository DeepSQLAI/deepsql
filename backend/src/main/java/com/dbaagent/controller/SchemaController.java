package com.dbaagent.controller;

import com.dbaagent.model.*;
import com.dbaagent.model.TableStats;
import com.dbaagent.service.ClientContext;
import com.dbaagent.service.CredentialService;
import com.dbaagent.service.QueryExecutionContext;
import com.dbaagent.service.QueryExecutionPolicyException;
import com.dbaagent.service.ActiveQueryService;
import com.dbaagent.service.QueryExecutorService;
import com.dbaagent.service.RunningQueryRegistry;
import com.dbaagent.service.SqlExecutionAuditService;
import com.dbaagent.service.UserDataAccessPolicyException;
import com.dbaagent.service.UserDataAccessPolicyService;
import com.dbaagent.service.SchemaScannerService;
import com.dbaagent.service.VisualizationService;
import com.dbaagent.service.security.AccessControlService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/connections/{connectionId}")
@RequiredArgsConstructor
@Slf4j
public class SchemaController {
    private final SchemaScannerService schemaScannerService;
    private final VisualizationService visualizationService;
    private final CredentialService credentialService;
    private final QueryExecutorService queryExecutorService;
    private final AccessControlService accessControlService;
    private final SqlExecutionAuditService sqlExecutionAuditService;
    private final UserDataAccessPolicyService userDataAccessPolicyService;
    private final RunningQueryRegistry runningQueryRegistry;
    private final ActiveQueryService activeQueryService;

    @PostMapping("/scan")
    public ResponseEntity<Map<String, Object>> scanSchema(@PathVariable String connectionId) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (!credentialService.connectionExists(connectionId)) {
                response.put("success", false);
                response.put("message", "Connection not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
            accessControlService.assertCanUseChatEditor(connectionId);

            SchemaMetadata schema = scopedSchema(connectionId, schemaScannerService.scanSchema(connectionId));
            response.put("success", true);
            response.put("schema", schema);
            return ResponseEntity.ok(response);
        } catch (SQLException e) {
            response.put("success", false);
            response.put("message", "Schema scan failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        } catch (ResponseStatusException e) {
            response.put("success", false);
            response.put("message", e.getReason());
            return ResponseEntity.status(e.getStatusCode()).body(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Schema scan failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @GetMapping("/schema")
    public ResponseEntity<Map<String, Object>> getSchema(@PathVariable String connectionId) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (!credentialService.connectionExists(connectionId)) {
                response.put("success", false);
                response.put("message", "Connection not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
            accessControlService.assertCanUseChatEditor(connectionId);

            SchemaMetadata schema = scopedSchema(connectionId, schemaScannerService.scanSchema(connectionId));
            response.put("success", true);
            response.put("schema", schema);
            return ResponseEntity.ok(response);
        } catch (SQLException e) {
            response.put("success", false);
            response.put("message", "Failed to get schema: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        } catch (ResponseStatusException e) {
            response.put("success", false);
            response.put("message", e.getReason());
            return ResponseEntity.status(e.getStatusCode()).body(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to get schema: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @GetMapping("/visualization")
    public ResponseEntity<Map<String, Object>> getVisualization(@PathVariable String connectionId) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (!credentialService.connectionExists(connectionId)) {
                response.put("success", false);
                response.put("message", "Connection not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
            accessControlService.assertCanUseChatEditor(connectionId);

            SchemaMetadata schema = scopedSchema(connectionId, schemaScannerService.scanSchema(connectionId));
            ErDiagramData erDiagram = visualizationService.generateErDiagram(schema, connectionId);
            DependencyGraphData dependencyGraph = visualizationService.generateDependencyGraph(schema, connectionId);

            response.put("success", true);
            response.put("erDiagram", erDiagram);
            response.put("dependencyGraph", dependencyGraph);
            return ResponseEntity.ok(response);
        } catch (SQLException e) {
            response.put("success", false);
            response.put("message", "Failed to generate visualization: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        } catch (ResponseStatusException e) {
            response.put("success", false);
            response.put("message", e.getReason());
            return ResponseEntity.status(e.getStatusCode()).body(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to generate visualization: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @GetMapping("/objects")
    public ResponseEntity<Map<String, Object>> getDatabaseObjects(@PathVariable String connectionId) {
        Map<String, Object> response = new HashMap<>();
        try {
            log.info("Fetching database objects for connection: {}", connectionId);
            if (!credentialService.connectionExists(connectionId)) {
                response.put("success", false);
                response.put("message", "Connection not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
            accessControlService.assertCanUseChatEditor(connectionId);

            List<DatabaseObject> objects = scopedObjects(
                connectionId,
                queryExecutorService.getDatabaseObjects(connectionId)
            );
            log.info("Successfully fetched {} database objects for connection: {}", objects.size(), connectionId);
            response.put("success", true);
            response.put("objects", objects);
            return ResponseEntity.ok(response);
        } catch (SQLException e) {
            log.error("SQLException fetching database objects for connection {}: {}", connectionId, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Failed to fetch database objects: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        } catch (ResponseStatusException e) {
            response.put("success", false);
            response.put("message", e.getReason());
            return ResponseEntity.status(e.getStatusCode()).body(response);
        } catch (Exception e) {
            log.error("Exception fetching database objects for connection {}: {}", connectionId, e.getMessage(), e);
            response.put("success", false);
            response.put("message", "Failed to fetch database objects: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @PostMapping("/query")
    public ResponseEntity<Map<String, Object>> executeQuery(
            @PathVariable String connectionId,
            @RequestBody QueryRequest queryRequest,
            HttpServletRequest httpRequest) {
        Map<String, Object> response = new HashMap<>();
        ConnectionRequest connectionRequest = null;
        ClientContext client = ClientContext.fromRequest(httpRequest);
        try {
            if (!credentialService.connectionExists(connectionId)) {
                response.put("success", false);
                response.put("message", "Connection not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
            accessControlService.assertCanUseChatEditor(connectionId);
            queryRequest.setExecutionOrigin(QueryExecutionOrigin.EDITOR);
            connectionRequest = credentialService.getDecryptedConnection(connectionId);

            QueryResult result = queryExecutorService.executeQuery(
                connectionId,
                queryRequest,
                QueryExecutionContext.editor(
                    accessControlService.getCurrentUsername(),
                    accessControlService.isCurrentUserAdmin(),
                    Boolean.TRUE.equals(queryRequest.getMutationConfirmed())
                )
            );
            sqlExecutionAuditService.record(SqlExecutionAuditService.AuditRecord.executed()
                .connectionId(connectionId)
                .connectionRequest(connectionRequest)
                .queryRequest(queryRequest)
                .queryResult(result)
                .httpRequest(httpRequest)
                .client(client));
            response.put("success", true);
            response.put("result", result);
            return ResponseEntity.ok(response);
        } catch (QueryExecutionPolicyException e) {
            sqlExecutionAuditService.record(SqlExecutionAuditService.AuditRecord.blocked(e.getMessage())
                .connectionId(connectionId)
                .connectionRequest(connectionRequest)
                .queryRequest(queryRequest)
                .httpRequest(httpRequest)
                .client(client));
            response.put("success", false);
            response.put("message", e.getMessage());
            response.put("errorCode", e.getErrorCode());
            response.put("queryType", e.getQueryType());
            response.put("requiresConfirmation", e.isRequiresConfirmation());
            response.put("warnings", e.getWarnings());
            return ResponseEntity.status(e.getHttpStatus()).body(response);
        } catch (UserDataAccessPolicyException e) {
            sqlExecutionAuditService.record(SqlExecutionAuditService.AuditRecord.blocked(e.getMessage())
                .connectionId(connectionId)
                .connectionRequest(connectionRequest)
                .queryRequest(queryRequest)
                .httpRequest(httpRequest)
                .client(client));
            response.put("success", false);
            response.put("message", e.getMessage());
            response.put("errorCode", e.getErrorCode());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
        } catch (SQLException e) {
            sqlExecutionAuditService.record(SqlExecutionAuditService.AuditRecord.failed(e.getMessage())
                .connectionId(connectionId)
                .connectionRequest(connectionRequest)
                .queryRequest(queryRequest)
                .httpRequest(httpRequest)
                .client(client));
            response.put("success", false);
            response.put("message", "Query execution failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        } catch (ResponseStatusException e) {
            sqlExecutionAuditService.record(SqlExecutionAuditService.AuditRecord.failed(e.getReason())
                .connectionId(connectionId)
                .connectionRequest(connectionRequest)
                .queryRequest(queryRequest)
                .httpRequest(httpRequest)
                .client(client));
            response.put("success", false);
            response.put("message", e.getReason());
            return ResponseEntity.status(e.getStatusCode()).body(response);
        } catch (Exception e) {
            sqlExecutionAuditService.record(SqlExecutionAuditService.AuditRecord.failed(e.getMessage())
                .connectionId(connectionId)
                .connectionRequest(connectionRequest)
                .queryRequest(queryRequest)
                .httpRequest(httpRequest)
                .client(client));
            response.put("success", false);
            response.put("message", "Query execution failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    /**
     * Terminates a query this caller started but abandoned. Aborting the HTTP
     * request only closes the socket — the statement keeps running and holds a
     * pooled connection until it finishes, so the client must ask for it to stop.
     */
    @PostMapping("/query/{executionId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelQuery(
            @PathVariable String connectionId,
            @PathVariable String executionId) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (!credentialService.connectionExists(connectionId)) {
                response.put("success", false);
                response.put("message", "Connection not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
            accessControlService.assertCanUseChatEditor(connectionId);

            var running = runningQueryRegistry.find(executionId);
            if (running.isEmpty()) {
                // Already finished, or never started. Nothing to cancel.
                response.put("success", true);
                response.put("cancelled", false);
                response.put("message", "Query is no longer running");
                return ResponseEntity.ok(response);
            }

            RunningQueryRegistry.RunningQuery target = running.get();
            // The execution id is a bearer token for a kill: scope it to this
            // connection and to the user who started it, so one caller cannot
            // terminate another's query by guessing an id.
            String currentUser = accessControlService.getCurrentUsername();
            if (!connectionId.equals(target.connectionId())
                || (target.username() != null && currentUser != null && !target.username().equals(currentUser))) {
                response.put("success", false);
                response.put("message", "Query not found for this connection");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            activeQueryService.killQuery(connectionId, target.sessionPid());
            runningQueryRegistry.unregister(executionId);
            response.put("success", true);
            response.put("cancelled", true);
            response.put("message", "Query cancelled");
            return ResponseEntity.ok(response);
        } catch (ResponseStatusException e) {
            response.put("success", false);
            response.put("message", e.getReason());
            return ResponseEntity.status(e.getStatusCode()).body(response);
        } catch (Exception e) {
            log.warn("Failed to cancel query {} on connection {}: {}", executionId, connectionId, e.getMessage());
            response.put("success", false);
            response.put("message", "Failed to cancel query: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    // `{tableName:.+}` keeps schema-qualified ids (`crm.orders`) as one segment.
    @GetMapping("/tables/{tableName:.+}/indexes")
    public ResponseEntity<Map<String, Object>> getTableIndexes(
            @PathVariable String connectionId,
            @PathVariable String tableName) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (!credentialService.connectionExists(connectionId)) {
                response.put("success", false);
                response.put("message", "Connection not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
            accessControlService.assertCanUseChatEditor(connectionId);
            userDataAccessPolicyService.assertTableSchemaAllowed(
                connectionId,
                accessControlService.getCurrentUsername(),
                accessControlService.isCurrentUserAdmin(),
                tableName
            );

            List<TableIndex> indexes = queryExecutorService.getTableIndexes(connectionId, tableName);
            response.put("success", true);
            response.put("indexes", indexes);
            return ResponseEntity.ok(response);
        } catch (SQLException e) {
            response.put("success", false);
            response.put("message", "Failed to fetch table indexes: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        } catch (UserDataAccessPolicyException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            response.put("errorCode", e.getErrorCode());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
        } catch (ResponseStatusException e) {
            response.put("success", false);
            response.put("message", e.getReason());
            return ResponseEntity.status(e.getStatusCode()).body(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to fetch table indexes: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @GetMapping("/tables/{tableName:.+}/stats")
    public ResponseEntity<Map<String, Object>> getTableStats(
            @PathVariable String connectionId,
            @PathVariable String tableName) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (!credentialService.connectionExists(connectionId)) {
                response.put("success", false);
                response.put("message", "Connection not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }
            accessControlService.assertCanUseChatEditor(connectionId);
            userDataAccessPolicyService.assertTableSchemaAllowed(
                connectionId,
                accessControlService.getCurrentUsername(),
                accessControlService.isCurrentUserAdmin(),
                tableName
            );

            TableStats stats = queryExecutorService.getTableStats(connectionId, tableName);
            response.put("success", true);
            response.put("stats", stats);
            return ResponseEntity.ok(response);
        } catch (SQLException e) {
            response.put("success", false);
            response.put("message", "Failed to fetch table stats: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        } catch (UserDataAccessPolicyException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            response.put("errorCode", e.getErrorCode());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
        } catch (ResponseStatusException e) {
            response.put("success", false);
            response.put("message", e.getReason());
            return ResponseEntity.status(e.getStatusCode()).body(response);
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to fetch table stats: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    private SchemaMetadata scopedSchema(String connectionId, SchemaMetadata schema) {
        return userDataAccessPolicyService.filterSchemaMetadata(
            connectionId,
            accessControlService.getCurrentUsername(),
            accessControlService.isCurrentUserAdmin(),
            schema
        );
    }

    private List<DatabaseObject> scopedObjects(String connectionId, List<DatabaseObject> objects) {
        return userDataAccessPolicyService.filterDatabaseObjects(
            connectionId,
            accessControlService.getCurrentUsername(),
            accessControlService.isCurrentUserAdmin(),
            objects
        );
    }
}
