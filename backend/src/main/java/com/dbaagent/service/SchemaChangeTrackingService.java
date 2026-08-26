package com.dbaagent.service;

import com.dbaagent.model.SchemaChange;
import com.dbaagent.model.SchemaChange.ChangeType;
import com.dbaagent.model.SchemaChange.ObjectType;
import com.dbaagent.model.SchemaChange.Severity;
import com.dbaagent.model.SchemaDriftConfig;
import com.dbaagent.model.SchemaSnapshot;
import com.dbaagent.repository.SchemaChangeRepository;
import com.dbaagent.repository.SchemaDriftConfigRepository;
import com.dbaagent.repository.SchemaSnapshotRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Service for tracking schema changes and detecting drift.
 * Captures schema snapshots, compares them, and records detected changes.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SchemaChangeTrackingService {

    private final SchemaSnapshotRepository snapshotRepository;
    private final SchemaChangeRepository changeRepository;
    private final SchemaDriftConfigRepository driftConfigRepository;
    private final SchemaIntrospectionService introspectionService;
    private final ObjectMapper objectMapper;
    private final SchemaDriftListener schemaDriftListener;
    private final SchemaSnapshotService schemaSnapshotService;

    // Snapshot capture moved to SchemaSnapshotService — see
    // SchemaSnapshotService.captureSnapshot(connectionId, name, type, createdBy, notes).
    // Single owner of schema_snapshot writes; this service is now purely
    // about diff/comparison/drift-config. Callers that used the old
    // SchemaChangeTrackingService.captureSnapshot:
    //   - SchemaChangeController.captureSnapshot (controller endpoint)
    //   - this.checkDrift (baseline + per-cycle capture)
    // all now go through SchemaSnapshotService.

    /**
     * Detect changes between two snapshots
     */
    @Transactional
    public List<SchemaChange> detectChanges(SchemaSnapshot fromSnapshot, SchemaSnapshot toSnapshot) {
        List<SchemaChange> changes = new ArrayList<>();

        try {
            Map<String, Object> fromSchema = parseSchemaJson(fromSnapshot.getSchemaJson());
            Map<String, Object> toSchema = parseSchemaJson(toSnapshot.getSchemaJson());

            Map<String, Map<String, Object>> fromTables = normalizeTablesByName(fromSchema.get("tables"));
            Map<String, Map<String, Object>> toTables = normalizeTablesByName(toSchema.get("tables"));

            String connectionId = toSnapshot.getConnectionId();

            // Check for added tables
            for (String tableName : toTables.keySet()) {
                if (!fromTables.containsKey(tableName)) {
                    changes.add(createChange(connectionId, fromSnapshot.getId(), toSnapshot.getId(),
                            ChangeType.TABLE_ADDED, ObjectType.TABLE, tableName, null,
                            Map.of("tableName", tableName), Severity.INFO, false));
                }
            }

            // Check for removed tables
            for (String tableName : fromTables.keySet()) {
                if (!toTables.containsKey(tableName)) {
                    changes.add(createChange(connectionId, fromSnapshot.getId(), toSnapshot.getId(),
                            ChangeType.TABLE_REMOVED, ObjectType.TABLE, tableName, null,
                            Map.of("tableName", tableName), Severity.CRITICAL, true));
                }
            }

            // Check for column, index, and constraint changes in existing tables
            for (String tableName : toTables.keySet()) {
                if (fromTables.containsKey(tableName)) {
                    Map<String, Object> fromTable = fromTables.get(tableName);
                    Map<String, Object> toTable = toTables.get(tableName);

                    // Compare columns
                    changes.addAll(compareColumns(connectionId, fromSnapshot.getId(), toSnapshot.getId(),
                            tableName, fromTable, toTable));

                    // Compare indexes
                    changes.addAll(compareIndexes(connectionId, fromSnapshot.getId(), toSnapshot.getId(),
                            tableName, fromTable, toTable));

                    // Compare constraints
                    changes.addAll(compareConstraints(connectionId, fromSnapshot.getId(), toSnapshot.getId(),
                            tableName, fromTable, toTable));

                    // Compare foreign keys
                    changes.addAll(compareForeignKeys(connectionId, fromSnapshot.getId(), toSnapshot.getId(),
                            tableName, fromTable, toTable));
                }
            }

        } catch (Exception e) {
            log.error("Failed to detect schema changes: {}", e.getMessage(), e);
        }

        return changes;
    }

    /**
     * After persisting schema changes, fire the drift listener for column-level
     * deltas. Tables are intentionally NOT fired here — those are handled by
     * BrainService's own drift path via {@link SchemaDriftListener#onTablesAdded}
     * / {@link SchemaDriftListener#onTablesRemoved} so we don't double-notify.
     */
    private void notifyColumnDrift(String connectionId, List<SchemaChange> changes) {
        List<SchemaDriftListener.ColumnRef> added = new ArrayList<>();
        List<SchemaDriftListener.ColumnRef> removed = new ArrayList<>();
        List<SchemaDriftListener.ColumnRef> modified = new ArrayList<>();
        for (SchemaChange c : changes) {
            if (c.getObjectType() != ObjectType.COLUMN) continue;
            var ref = new SchemaDriftListener.ColumnRef(c.getTableName(), c.getObjectName());
            switch (c.getChangeType()) {
                case COLUMN_ADDED    -> added.add(ref);
                case COLUMN_REMOVED  -> removed.add(ref);
                case COLUMN_MODIFIED -> modified.add(ref);
                default -> { /* not a column-level event */ }
            }
        }
        try {
            if (!added.isEmpty())    schemaDriftListener.onColumnsAdded(connectionId, added);
            if (!removed.isEmpty())  schemaDriftListener.onColumnsRemoved(connectionId, removed);
            if (!modified.isEmpty()) schemaDriftListener.onColumnsModified(connectionId, modified);
        } catch (Exception e) {
            // Listener errors must never break the snapshot path.
            log.warn("schema drift listener notify failed: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> normalizeTablesByName(Object rawTables) {
        if (rawTables == null) {
            return Collections.emptyMap();
        }

        Map<String, Map<String, Object>> normalized = new LinkedHashMap<>();

        if (rawTables instanceof Map<?, ?> tableMap) {
            for (Map.Entry<?, ?> entry : tableMap.entrySet()) {
                if (entry.getKey() == null || !(entry.getValue() instanceof Map<?, ?> valueMap)) {
                    continue;
                }
                normalized.put(String.valueOf(entry.getKey()), new LinkedHashMap<>((Map<String, Object>) valueMap));
            }
            return normalized;
        }

        if (rawTables instanceof List<?> tableList) {
            for (Object tableObj : tableList) {
                if (!(tableObj instanceof Map<?, ?> tableEntry)) {
                    continue;
                }

                Object name = tableEntry.get("name");
                if (name == null) {
                    continue;
                }

                normalized.put(String.valueOf(name), new LinkedHashMap<>((Map<String, Object>) tableEntry));
            }
        }

        return normalized;
    }

    private List<SchemaChange> compareColumns(String connectionId, String fromSnapshotId, String toSnapshotId,
                                               String tableName, Map<String, Object> fromTable, Map<String, Object> toTable) {
        List<SchemaChange> changes = new ArrayList<>();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fromColumns = (List<Map<String, Object>>) fromTable.getOrDefault("columns", Collections.emptyList());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toColumns = (List<Map<String, Object>>) toTable.getOrDefault("columns", Collections.emptyList());

        Map<String, Map<String, Object>> fromColMap = new HashMap<>();
        Map<String, Map<String, Object>> toColMap = new HashMap<>();

        for (Map<String, Object> col : fromColumns) {
            String name = (String) col.get("name");
            if (name != null) fromColMap.put(name, col);
        }
        for (Map<String, Object> col : toColumns) {
            String name = (String) col.get("name");
            if (name != null) toColMap.put(name, col);
        }

        // Added columns
        for (String colName : toColMap.keySet()) {
            if (!fromColMap.containsKey(colName)) {
                Map<String, Object> col = toColMap.get(colName);
                changes.add(createChange(connectionId, fromSnapshotId, toSnapshotId,
                        ChangeType.COLUMN_ADDED, ObjectType.COLUMN, colName, tableName,
                        Map.of("column", col), Severity.INFO, false));
            }
        }

        // Removed columns
        for (String colName : fromColMap.keySet()) {
            if (!toColMap.containsKey(colName)) {
                Map<String, Object> col = fromColMap.get(colName);
                boolean isBreaking = Boolean.TRUE.equals(col.get("isPrimaryKey")) ||
                        Boolean.TRUE.equals(col.get("isForeignKey"));
                changes.add(createChange(connectionId, fromSnapshotId, toSnapshotId,
                        ChangeType.COLUMN_REMOVED, ObjectType.COLUMN, colName, tableName,
                        Map.of("column", col), isBreaking ? Severity.CRITICAL : Severity.WARNING, isBreaking));
            }
        }

        // Modified columns
        for (String colName : toColMap.keySet()) {
            if (fromColMap.containsKey(colName)) {
                Map<String, Object> fromCol = fromColMap.get(colName);
                Map<String, Object> toCol = toColMap.get(colName);

                if (!columnsEqual(fromCol, toCol)) {
                    Map<String, Object> details = new LinkedHashMap<>();
                    details.put("before", fromCol);
                    details.put("after", toCol);
                    details.put("changes", findColumnDifferences(fromCol, toCol));

                    // Type changes are potentially breaking
                    boolean isBreaking = !Objects.equals(fromCol.get("dataType"), toCol.get("dataType"));

                    changes.add(createChange(connectionId, fromSnapshotId, toSnapshotId,
                            ChangeType.COLUMN_MODIFIED, ObjectType.COLUMN, colName, tableName,
                            details, isBreaking ? Severity.WARNING : Severity.INFO, isBreaking));
                }
            }
        }

        return changes;
    }

    private boolean columnsEqual(Map<String, Object> col1, Map<String, Object> col2) {
        return Objects.equals(col1.get("dataType"), col2.get("dataType")) &&
                Objects.equals(col1.get("columnType"), col2.get("columnType")) &&
                Objects.equals(col1.get("isNullable"), col2.get("isNullable")) &&
                Objects.equals(col1.get("defaultValue"), col2.get("defaultValue"));
    }

    private List<String> findColumnDifferences(Map<String, Object> fromCol, Map<String, Object> toCol) {
        List<String> diffs = new ArrayList<>();
        if (!Objects.equals(fromCol.get("dataType"), toCol.get("dataType"))) {
            diffs.add("dataType: " + fromCol.get("dataType") + " -> " + toCol.get("dataType"));
        }
        if (!Objects.equals(fromCol.get("columnType"), toCol.get("columnType"))) {
            diffs.add("columnType: " + fromCol.get("columnType") + " -> " + toCol.get("columnType"));
        }
        if (!Objects.equals(fromCol.get("isNullable"), toCol.get("isNullable"))) {
            diffs.add("isNullable: " + fromCol.get("isNullable") + " -> " + toCol.get("isNullable"));
        }
        if (!Objects.equals(fromCol.get("defaultValue"), toCol.get("defaultValue"))) {
            diffs.add("defaultValue: " + fromCol.get("defaultValue") + " -> " + toCol.get("defaultValue"));
        }
        return diffs;
    }

    private List<SchemaChange> compareIndexes(String connectionId, String fromSnapshotId, String toSnapshotId,
                                               String tableName, Map<String, Object> fromTable, Map<String, Object> toTable) {
        List<SchemaChange> changes = new ArrayList<>();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fromIndexes = (List<Map<String, Object>>) fromTable.getOrDefault("indexes", Collections.emptyList());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toIndexes = (List<Map<String, Object>>) toTable.getOrDefault("indexes", Collections.emptyList());

        Map<String, Map<String, Object>> fromIdxMap = new HashMap<>();
        Map<String, Map<String, Object>> toIdxMap = new HashMap<>();

        for (Map<String, Object> idx : fromIndexes) {
            String name = (String) idx.get("name");
            if (name != null) fromIdxMap.put(name, idx);
        }
        for (Map<String, Object> idx : toIndexes) {
            String name = (String) idx.get("name");
            if (name != null) toIdxMap.put(name, idx);
        }

        // Added indexes
        for (String idxName : toIdxMap.keySet()) {
            if (!fromIdxMap.containsKey(idxName)) {
                changes.add(createChange(connectionId, fromSnapshotId, toSnapshotId,
                        ChangeType.INDEX_ADDED, ObjectType.INDEX, idxName, tableName,
                        Map.of("index", toIdxMap.get(idxName)), Severity.INFO, false));
            }
        }

        // Removed indexes
        for (String idxName : fromIdxMap.keySet()) {
            if (!toIdxMap.containsKey(idxName)) {
                Map<String, Object> idx = fromIdxMap.get(idxName);
                boolean isPrimary = Boolean.TRUE.equals(idx.get("isPrimary"));
                changes.add(createChange(connectionId, fromSnapshotId, toSnapshotId,
                        ChangeType.INDEX_REMOVED, ObjectType.INDEX, idxName, tableName,
                        Map.of("index", idx), isPrimary ? Severity.CRITICAL : Severity.WARNING, isPrimary));
            }
        }

        return changes;
    }

    private List<SchemaChange> compareConstraints(String connectionId, String fromSnapshotId, String toSnapshotId,
                                                   String tableName, Map<String, Object> fromTable, Map<String, Object> toTable) {
        List<SchemaChange> changes = new ArrayList<>();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fromConstraints = (List<Map<String, Object>>) fromTable.getOrDefault("constraints", Collections.emptyList());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toConstraints = (List<Map<String, Object>>) toTable.getOrDefault("constraints", Collections.emptyList());

        Map<String, Map<String, Object>> fromConMap = new HashMap<>();
        Map<String, Map<String, Object>> toConMap = new HashMap<>();

        for (Map<String, Object> con : fromConstraints) {
            String name = (String) con.get("name");
            if (name != null) fromConMap.put(name, con);
        }
        for (Map<String, Object> con : toConstraints) {
            String name = (String) con.get("name");
            if (name != null) toConMap.put(name, con);
        }

        // Added constraints
        for (String conName : toConMap.keySet()) {
            if (!fromConMap.containsKey(conName)) {
                changes.add(createChange(connectionId, fromSnapshotId, toSnapshotId,
                        ChangeType.CONSTRAINT_ADDED, ObjectType.CONSTRAINT, conName, tableName,
                        Map.of("constraint", toConMap.get(conName)), Severity.INFO, false));
            }
        }

        // Removed constraints
        for (String conName : fromConMap.keySet()) {
            if (!toConMap.containsKey(conName)) {
                Map<String, Object> con = fromConMap.get(conName);
                String type = (String) con.get("type");
                boolean isBreaking = "PRIMARY KEY".equalsIgnoreCase(type) || "UNIQUE".equalsIgnoreCase(type);
                changes.add(createChange(connectionId, fromSnapshotId, toSnapshotId,
                        ChangeType.CONSTRAINT_REMOVED, ObjectType.CONSTRAINT, conName, tableName,
                        Map.of("constraint", con), isBreaking ? Severity.CRITICAL : Severity.WARNING, isBreaking));
            }
        }

        return changes;
    }

    private List<SchemaChange> compareForeignKeys(String connectionId, String fromSnapshotId, String toSnapshotId,
                                                   String tableName, Map<String, Object> fromTable, Map<String, Object> toTable) {
        List<SchemaChange> changes = new ArrayList<>();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> fromFKs = (List<Map<String, Object>>) fromTable.getOrDefault("foreignKeys", Collections.emptyList());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> toFKs = (List<Map<String, Object>>) toTable.getOrDefault("foreignKeys", Collections.emptyList());

        Map<String, Map<String, Object>> fromFKMap = new HashMap<>();
        Map<String, Map<String, Object>> toFKMap = new HashMap<>();

        for (Map<String, Object> fk : fromFKs) {
            String name = (String) fk.get("name");
            if (name != null) fromFKMap.put(name, fk);
        }
        for (Map<String, Object> fk : toFKs) {
            String name = (String) fk.get("name");
            if (name != null) toFKMap.put(name, fk);
        }

        // Added FKs
        for (String fkName : toFKMap.keySet()) {
            if (!fromFKMap.containsKey(fkName)) {
                changes.add(createChange(connectionId, fromSnapshotId, toSnapshotId,
                        ChangeType.FOREIGN_KEY_ADDED, ObjectType.FOREIGN_KEY, fkName, tableName,
                        Map.of("foreignKey", toFKMap.get(fkName)), Severity.INFO, false));
            }
        }

        // Removed FKs
        for (String fkName : fromFKMap.keySet()) {
            if (!toFKMap.containsKey(fkName)) {
                changes.add(createChange(connectionId, fromSnapshotId, toSnapshotId,
                        ChangeType.FOREIGN_KEY_REMOVED, ObjectType.FOREIGN_KEY, fkName, tableName,
                        Map.of("foreignKey", fromFKMap.get(fkName)), Severity.WARNING, false));
            }
        }

        return changes;
    }

    private SchemaChange createChange(String connectionId, String fromSnapshotId, String toSnapshotId,
                                       ChangeType changeType, ObjectType objectType, String objectName,
                                       String tableName, Map<String, Object> details,
                                       Severity severity, boolean isBreaking) {
        return SchemaChange.builder()
                .id(UUID.randomUUID().toString())
                .connectionId(connectionId)
                .fromSnapshotId(fromSnapshotId)
                .toSnapshotId(toSnapshotId)
                .changeType(changeType)
                .objectType(objectType)
                .objectName(objectName)
                .tableName(tableName)
                .changeDetails(details)
                .severity(severity)
                .isBreakingChange(isBreaking)
                .isAcknowledged(false)
                .detectedAt(LocalDateTime.now())
                .build();
    }

    private Map<String, Object> parseSchemaJson(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            log.error("Failed to parse schema JSON: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    private String calculateHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // ==================== Drift Detection Methods ====================

    /**
     * Configure drift detection for a connection
     */
    @Transactional
    public SchemaDriftConfig configureDriftDetection(String connectionId, SchemaDriftConfig config) {
        config.setId(UUID.randomUUID().toString());
        config.setConnectionId(connectionId);
        return driftConfigRepository.save(config);
    }

    /**
     * Get drift configuration for a connection
     */
    public Optional<SchemaDriftConfig> getDriftConfig(String connectionId) {
        return driftConfigRepository.findByConnectionId(connectionId);
    }

    /**
     * Ensure a default drift config exists for the connection.
     *
     * Called automatically before the first scheduled drift check so the job
     * is active out of the box without requiring manual configuration.
     * Alerts are enabled for table and column changes; index/constraint alerts
     * default off to avoid noise on busy schemas. The check runs daily.
     * If a config already exists it is left untouched.
     */
    @Transactional
    public SchemaDriftConfig ensureDefaultDriftConfig(String connectionId) {
        return driftConfigRepository.findByConnectionId(connectionId).orElseGet(() -> {
            SchemaDriftConfig cfg = new SchemaDriftConfig();
            cfg.setId(UUID.randomUUID().toString());
            cfg.setConnectionId(connectionId);
            cfg.setEnabled(true);
            cfg.setCheckFrequencyMinutes(1440); // daily
            cfg.setAlertOnTableChanges(true);
            cfg.setAlertOnColumnChanges(true);
            cfg.setAlertOnIndexChanges(false);
            cfg.setAlertOnConstraintChanges(false);
            log.info("Auto-created default drift config for connection {}", connectionId);
            return driftConfigRepository.save(cfg);
        });
    }

    /**
     * Set a snapshot as the baseline for drift detection
     */
    @Transactional
    public void setBaseline(String connectionId, String snapshotId) {
        // Update the snapshot type to BASELINE
        snapshotRepository.findById(snapshotId).ifPresent(snapshot -> {
            snapshot.setSnapshotType(SchemaSnapshot.SnapshotType.BASELINE);
            snapshotRepository.save(snapshot);
        });

        // Update drift config
        driftConfigRepository.findByConnectionId(connectionId).ifPresent(config -> {
            config.setBaselineSnapshotId(snapshotId);
            driftConfigRepository.save(config);
        });
    }

    /**
     * Check for schema drift against baseline
     */
    @Transactional
    public List<SchemaChange> checkDrift(String connectionId) {
        Optional<SchemaDriftConfig> configOpt = driftConfigRepository.findByConnectionId(connectionId);
        if (configOpt.isEmpty() || !configOpt.get().getEnabled()) {
            return Collections.emptyList();
        }

        SchemaDriftConfig config = configOpt.get();

        // Get baseline snapshot
        Optional<SchemaSnapshot> baselineOpt = config.getBaselineSnapshotId() != null ?
                snapshotRepository.findById(config.getBaselineSnapshotId()) :
                snapshotRepository.findFirstByConnectionIdAndSnapshotTypeOrderByCapturedAtDesc(
                        connectionId, SchemaSnapshot.SnapshotType.BASELINE);

        if (baselineOpt.isEmpty()) {
            // No baseline yet — capture the current schema as the initial baseline.
            // This first run establishes the reference point; subsequent runs will
            // diff against it. Return empty (no drift on first run by definition).
            log.info("No baseline snapshot for connection {} — capturing initial baseline", connectionId);
            SchemaSnapshot baseline = schemaSnapshotService.captureSnapshot(
                    connectionId, "Initial baseline",
                    SchemaSnapshot.SnapshotType.BASELINE, "system",
                    "Auto-captured on first drift check");
            config.setBaselineSnapshotId(baseline.getId());
            config.scheduleNextCheck();
            driftConfigRepository.save(config);
            return Collections.emptyList();
        }

        // Capture current snapshot
        SchemaSnapshot currentSnapshot = schemaSnapshotService.captureSnapshot(
                connectionId, "Drift Check",
                SchemaSnapshot.SnapshotType.SCHEDULED, "system", "Automatic drift check");

        // Detect changes from baseline
        List<SchemaChange> changes = detectChanges(baselineOpt.get(), currentSnapshot);

        // Update config
        config.scheduleNextCheck();
        driftConfigRepository.save(config);

        // Filter changes based on config
        return filterChangesByConfig(changes, config);
    }

    private List<SchemaChange> filterChangesByConfig(List<SchemaChange> changes, SchemaDriftConfig config) {
        return changes.stream()
                .filter(change -> {
                    // Check if table should be ignored
                    if (change.getTableName() != null && config.shouldIgnoreTable(change.getTableName())) {
                        return false;
                    }

                    // Check change type against config
                    return switch (change.getObjectType()) {
                        case TABLE -> config.getAlertOnTableChanges();
                        case COLUMN -> config.getAlertOnColumnChanges();
                        case INDEX -> config.getAlertOnIndexChanges();
                        case CONSTRAINT, FOREIGN_KEY -> config.getAlertOnConstraintChanges();
                    };
                })
                .toList();
    }

    /**
     * Scheduled task to check for drift on all configured connections
     */
    @Transactional
    public void scheduledDriftCheck() {
        List<SchemaDriftConfig> configsDue = driftConfigRepository
                .findConfigsDueForCheck(LocalDateTime.now());

        for (SchemaDriftConfig config : configsDue) {
            try {
                log.info("Running scheduled drift check for connection {}", config.getConnectionId());
                checkDrift(config.getConnectionId());
            } catch (Exception e) {
                log.error("Failed drift check for connection {}: {}", config.getConnectionId(), e.getMessage());
            }
        }
    }

    // ==================== Query Methods ====================

    /**
     * Get all snapshots for a connection
     */
    public List<SchemaSnapshot> getSnapshots(String connectionId) {
        return snapshotRepository.findByConnectionIdOrderByCapturedAtDesc(connectionId);
    }

    /**
     * Get recent snapshots
     */
    public List<SchemaSnapshot> getRecentSnapshots(String connectionId) {
        return snapshotRepository.findTop10ByConnectionIdOrderByCapturedAtDesc(connectionId);
    }

    /**
     * Get all changes for a connection
     */
    public List<SchemaChange> getChanges(String connectionId) {
        return changeRepository.findTop50ByConnectionIdOrderByDetectedAtDesc(connectionId);
    }

    /**
     * Get unacknowledged changes
     */
    public List<SchemaChange> getUnacknowledgedChanges(String connectionId) {
        return changeRepository.findByConnectionIdAndIsAcknowledgedFalseOrderByDetectedAtDesc(connectionId);
    }

    /**
     * Acknowledge changes
     */
    @Transactional
    public int acknowledgeChanges(List<String> changeIds, String acknowledgedBy) {
        return changeRepository.acknowledgeByIds(changeIds, acknowledgedBy, LocalDateTime.now());
    }

    /**
     * Acknowledge all changes for a connection
     */
    @Transactional
    public int acknowledgeAllChanges(String connectionId, String acknowledgedBy) {
        return changeRepository.acknowledgeAll(connectionId, acknowledgedBy, LocalDateTime.now());
    }

    /**
     * Compare two specific snapshots
     */
    public List<SchemaChange> compareSnapshots(String snapshotId1, String snapshotId2) {
        Optional<SchemaSnapshot> snap1 = snapshotRepository.findById(snapshotId1);
        Optional<SchemaSnapshot> snap2 = snapshotRepository.findById(snapshotId2);

        if (snap1.isEmpty() || snap2.isEmpty()) {
            throw new IllegalArgumentException("One or both snapshots not found");
        }

        // Both snapshots must belong to the same connection. Without this a caller
        // authorized on connection A could diff A's schema against connection B's
        // and read B's table and column names out of the resulting change list.
        if (!Objects.equals(snap1.get().getConnectionId(), snap2.get().getConnectionId())) {
            throw new IllegalArgumentException("Snapshots belong to different connections");
        }

        return detectChanges(snap1.get(), snap2.get());
    }

    /**
     * The connection a snapshot belongs to, for authorizing an endpoint keyed
     * only on a snapshot id. Empty when the id does not exist.
     */
    public Optional<String> findConnectionIdForSnapshot(String snapshotId) {
        return snapshotRepository.findById(snapshotId).map(SchemaSnapshot::getConnectionId);
    }

    /**
     * True when every given change id belongs to {@code connectionId}. Guards the
     * acknowledge endpoints, whose ids arrive in the body and are therefore not
     * covered by a path-variable authorization check.
     */
    public boolean allChangesBelongTo(String connectionId, List<String> changeIds) {
        if (changeIds == null || changeIds.isEmpty()) {
            return true;
        }
        List<SchemaChange> found = changeRepository.findAllById(changeIds);
        // An id that resolves to nothing must fail too, otherwise a caller can mix
        // unknown ids in and still have the batch accepted.
        return found.size() == changeIds.stream().distinct().count()
            && found.stream().allMatch(c -> Objects.equals(connectionId, c.getConnectionId()));
    }

    /**
     * Get change statistics for a connection
     */
    public Map<String, Object> getChangeStats(String connectionId) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalSnapshots", snapshotRepository.countByConnectionId(connectionId));
        stats.put("unacknowledgedChanges", changeRepository.countByConnectionIdAndIsAcknowledgedFalse(connectionId));
        stats.put("criticalChanges", changeRepository.countByConnectionIdAndSeverity(connectionId, Severity.CRITICAL));
        stats.put("warningChanges", changeRepository.countByConnectionIdAndSeverity(connectionId, Severity.WARNING));
        stats.put("infoChanges", changeRepository.countByConnectionIdAndSeverity(connectionId, Severity.INFO));
        return stats;
    }
}
