package com.dbaagent.controller;

import com.dbaagent.model.SchemaChange;
import com.dbaagent.model.SchemaDriftConfig;
import com.dbaagent.model.SchemaSnapshot;
import com.dbaagent.service.SchemaChangeTrackingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST API for schema change tracking and drift detection
 */
@RestController
@RequestMapping("/schema-changes")
@RequiredArgsConstructor
@Slf4j
public class SchemaChangeController {

    private final SchemaChangeTrackingService schemaChangeService;
    private final com.dbaagent.service.SchemaSnapshotService schemaSnapshotService;

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
        return ResponseEntity.ok(schemaChangeService.getSnapshots(connectionId));
    }

    /**
     * Get recent snapshots
     */
    @GetMapping("/{connectionId}/snapshots/recent")
    public ResponseEntity<List<SchemaSnapshot>> getRecentSnapshots(@PathVariable String connectionId) {
        return ResponseEntity.ok(schemaChangeService.getRecentSnapshots(connectionId));
    }

    /**
     * Set a snapshot as baseline
     */
    @PostMapping("/{connectionId}/snapshots/{snapshotId}/set-baseline")
    public ResponseEntity<Map<String, String>> setBaseline(
            @PathVariable String connectionId,
            @PathVariable String snapshotId) {

        schemaChangeService.setBaseline(connectionId, snapshotId);
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

        return ResponseEntity.ok(schemaChangeService.compareSnapshots(snapshotId1, snapshotId2));
    }

    // ==================== Change Endpoints ====================

    /**
     * Get all changes for a connection
     */
    @GetMapping("/{connectionId}/changes")
    public ResponseEntity<List<SchemaChange>> getChanges(@PathVariable String connectionId) {
        return ResponseEntity.ok(schemaChangeService.getChanges(connectionId));
    }

    /**
     * Get unacknowledged changes
     */
    @GetMapping("/{connectionId}/changes/unacknowledged")
    public ResponseEntity<List<SchemaChange>> getUnacknowledgedChanges(@PathVariable String connectionId) {
        return ResponseEntity.ok(schemaChangeService.getUnacknowledgedChanges(connectionId));
    }

    /**
     * Acknowledge specific changes
     */
    @PostMapping("/{connectionId}/changes/acknowledge")
    public ResponseEntity<Map<String, Object>> acknowledgeChanges(
            @PathVariable String connectionId,
            @RequestBody List<String> changeIds,
            @RequestParam(required = false, defaultValue = "user") String acknowledgedBy) {

        int count = schemaChangeService.acknowledgeChanges(changeIds, acknowledgedBy);
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
            @RequestParam(required = false, defaultValue = "user") String acknowledgedBy) {

        int count = schemaChangeService.acknowledgeAllChanges(connectionId, acknowledgedBy);
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
        return ResponseEntity.ok(schemaChangeService.getChangeStats(connectionId));
    }

    // ==================== Drift Detection Endpoints ====================

    /**
     * Get drift detection configuration
     */
    @GetMapping("/{connectionId}/drift-config")
    public ResponseEntity<SchemaDriftConfig> getDriftConfig(@PathVariable String connectionId) {
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

        return ResponseEntity.ok(schemaChangeService.configureDriftDetection(connectionId, config));
    }

    /**
     * Trigger manual drift check
     */
    @PostMapping("/{connectionId}/drift-check")
    public ResponseEntity<Map<String, Object>> triggerDriftCheck(@PathVariable String connectionId) {
        List<SchemaChange> changes = schemaChangeService.checkDrift(connectionId);
        return ResponseEntity.ok(Map.of(
                "status", "success",
                "changesDetected", changes.size(),
                "changes", changes
        ));
    }
}
