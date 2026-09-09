package com.dbaagent.controller;

import com.dbaagent.model.SchemaChange;
import com.dbaagent.model.SchemaDriftConfig;
import com.dbaagent.model.SchemaSnapshot;
import com.dbaagent.service.SchemaChangeTrackingService;
import com.dbaagent.service.security.AccessControlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for schema change tracking and drift detection
 *
 * <p><b>Authorization:</b> every endpoint here takes a caller-supplied connection id, so
 * each one asserts access itself ({@code assertCanReadConnectionContent} for reads,
 * {@code assertCanManageConnectionContent} for writes). {@code SecurityConfig} only
 * requires an authenticated principal — nothing upstream inspects a connection id. See
 * {@code ConnectionScopedAuthorizationSafetyTest}.
 */
@RestController
@RequestMapping("/schema-changes")
@RequiredArgsConstructor
@Slf4j
public class SchemaChangeController {

    private final SchemaChangeTrackingService schemaChangeService;
    private final com.dbaagent.service.SchemaSnapshotService schemaSnapshotService;
    private final AccessControlService accessControlService;

    // ==================== Snapshot Endpoints ====================

    /**
     * Capture a new schema snapshot
     */
    @PostMapping("/{connectionId}/snapshots")
    public ResponseEntity<SchemaSnapshot> captureSnapshot(
            @PathVariable String connectionId,
            @RequestParam(required = false) String name,
            @RequestParam(required = false, defaultValue = "MANUAL") String type,
            @RequestParam(required = false) String notes) {
        accessControlService.assertCanManageConnectionContent(connectionId);

        SchemaSnapshot.SnapshotType snapshotType = SchemaSnapshot.SnapshotType.valueOf(type.toUpperCase());
        // Was: schemaChangeService.captureSnapshot — moved into
        // SchemaSnapshotService so there's a single owner of schema_snapshot
        // writes (and a single, consistent JSON shape on the row).
        SchemaSnapshot snapshot = schemaSnapshotService.captureSnapshot(
                connectionId, name, snapshotType, "user", notes);
        return ResponseEntity.ok(snapshot);
    }

    /**
     * Get all snapshots for a connection
     */
    @GetMapping("/{connectionId}/snapshots")
    public ResponseEntity<List<SchemaSnapshot>> getSnapshots(@PathVariable String connectionId) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        return ResponseEntity.ok(schemaChangeService.getSnapshots(connectionId));
    }

    /**
     * Get recent snapshots
     */
    @GetMapping("/{connectionId}/snapshots/recent")
    public ResponseEntity<List<SchemaSnapshot>> getRecentSnapshots(@PathVariable String connectionId) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        return ResponseEntity.ok(schemaChangeService.getRecentSnapshots(connectionId));
    }

    /**
     * Set a snapshot as baseline
     */
    @PostMapping("/{connectionId}/snapshots/{snapshotId}/set-baseline")
    public ResponseEntity<Map<String, String>> setBaseline(
            @PathVariable String connectionId,
            @PathVariable String snapshotId) {
        accessControlService.assertCanManageConnectionContent(connectionId);
        assertSnapshotBelongsTo(connectionId, snapshotId);

        try {
            schemaChangeService.setBaseline(connectionId, snapshotId);
        } catch (IllegalArgumentException e) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, e.getMessage());
        }
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "message", "Snapshot set as baseline"
        ));
    }

    /**
     * Compare two snapshots
     */
    @GetMapping("/snapshots/compare")
    public ResponseEntity<List<SchemaChange>> compareSnapshots(
            @RequestParam String snapshotId1,
            @RequestParam String snapshotId2) {

        assertCanReadSnapshot(snapshotId1);
        assertCanReadSnapshot(snapshotId2);
        try {
            return ResponseEntity.ok(schemaChangeService.compareSnapshots(snapshotId1, snapshotId2));
        } catch (IllegalArgumentException e) {
            // Missing snapshot, or two snapshots from different connections. Both are
            // "not something you can compare", not a server fault — a 500 here would read
            // as a broken feature and hide the real reason.
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    // ==================== Change Endpoints ====================

    /**
     * Get all changes for a connection
     */
    @GetMapping("/{connectionId}/changes")
    public ResponseEntity<List<SchemaChange>> getChanges(@PathVariable String connectionId) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        return ResponseEntity.ok(schemaChangeService.getChanges(connectionId));
    }

    /**
     * Get unacknowledged changes
     */
    @GetMapping("/{connectionId}/changes/unacknowledged")
    public ResponseEntity<List<SchemaChange>> getUnacknowledgedChanges(@PathVariable String connectionId) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        return ResponseEntity.ok(schemaChangeService.getUnacknowledgedChanges(connectionId));
    }

    /**
     * Acknowledge specific changes
     */
    @PostMapping("/{connectionId}/changes/acknowledge")
    public ResponseEntity<Map<String, Object>> acknowledgeChanges(
            @PathVariable String connectionId,
            @RequestBody List<String> changeIds,
            @RequestParam(required = false) String acknowledgedBy) {
            // Accepted for wire compatibility and deliberately ignored: the actor is
            // taken from the security context below. It previously defaulted to the
            // literal string "user", so the trail named nobody.
        accessControlService.assertCanManageConnectionContent(connectionId);

        assertChangesBelongTo(connectionId, changeIds);
        int count = schemaChangeService.acknowledgeChanges(changeIds, accessControlService.requireCurrentUsername());
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "acknowledgedCount", count
        ));
    }

    /**
     * Acknowledge all changes for a connection
     */
    @PostMapping("/{connectionId}/changes/acknowledge-all")
    public ResponseEntity<Map<String, Object>> acknowledgeAllChanges(
            @PathVariable String connectionId,
            @RequestParam(required = false) String acknowledgedBy) {
            // Accepted for wire compatibility and deliberately ignored: the actor is
            // taken from the security context below. It previously defaulted to the
            // literal string "user", so the trail named nobody.
        accessControlService.assertCanManageConnectionContent(connectionId);

        int count = schemaChangeService.acknowledgeAllChanges(
                connectionId, accessControlService.requireCurrentUsername());
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "acknowledgedCount", count
        ));
    }

    /**
     * Get change statistics
     */
    @GetMapping("/{connectionId}/changes/stats")
    public ResponseEntity<Map<String, Object>> getChangeStats(@PathVariable String connectionId) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        return ResponseEntity.ok(schemaChangeService.getChangeStats(connectionId));
    }

    // ==================== Drift Detection Endpoints ====================

    /**
     * Get drift detection configuration
     */
    @GetMapping("/{connectionId}/drift-config")
    public ResponseEntity<SchemaDriftConfig> getDriftConfig(@PathVariable String connectionId) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        return schemaChangeService.getDriftConfig(connectionId)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Configure drift detection
     */
    @PostMapping("/{connectionId}/drift-config")
    public ResponseEntity<SchemaDriftConfig> configureDriftDetection(
            @PathVariable String connectionId,
            @RequestBody SchemaDriftConfig config) {
        accessControlService.assertCanManageConnectionContent(connectionId);

        return ResponseEntity.ok(schemaChangeService.configureDriftDetection(connectionId, config));
    }

    /**
     * Trigger manual drift check
     */
    @PostMapping("/{connectionId}/drift-check")
    public ResponseEntity<Map<String, Object>> triggerDriftCheck(@PathVariable String connectionId) {
        accessControlService.assertCanManageConnectionContent(connectionId);
        List<SchemaChange> changes = schemaChangeService.checkDrift(connectionId);
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "changesDetected", changes.size(),
                "changes", changes
        ));
    }

    /**
     * Authorize a read keyed only on a snapshot id. The snapshot carries its own
     * connectionId, so resolve that and assert against it. An unknown id reports
     * 404 — as does a snapshot on a connection the caller cannot read, so the endpoint
     * cannot be used to probe which snapshots exist.
     */
    private void assertCanReadSnapshot(String snapshotId) {
        String connectionId = schemaChangeService.findConnectionIdForSnapshot(snapshotId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Snapshot not found"));
        accessControlService.assertCanReadConnectionContentOrNotFound(connectionId, "Snapshot");
    }

    /**
     * The change ids arrive in the request body, so the path-variable check alone
     * does not constrain them — a caller authorized on their own connection could
     * otherwise acknowledge another connection's changes.
     */
    private void assertChangesBelongTo(String connectionId, List<String> changeIds) {
        if (!schemaChangeService.allChangesBelongTo(connectionId, changeIds)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Change not found for this connection");
        }
    }

    /**
     * The snapshot id is a separate path variable from the connection id, so authorizing
     * the connection says nothing about the snapshot. Without this a caller with manage
     * access on connection A could flip connection B's snapshot to BASELINE and point A's
     * drift config at it — the same body/path id-mismatch class as
     * {@code changes/acknowledge}, just split across two path variables instead.
     */
    private void assertSnapshotBelongsTo(String connectionId, String snapshotId) {
        String owner = schemaChangeService.findConnectionIdForSnapshot(snapshotId).orElse(null);
        if (!connectionId.equals(owner)) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.NOT_FOUND, "Snapshot not found");
        }
    }
}
