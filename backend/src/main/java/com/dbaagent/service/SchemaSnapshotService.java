package com.dbaagent.service;

import com.dbaagent.model.ColumnInfo;
import com.dbaagent.model.ColumnMetadata;
import com.dbaagent.model.DatabaseObject;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.SchemaSnapshot;
import com.dbaagent.model.TableDetails;
import com.dbaagent.model.TableMetadata;
import com.dbaagent.repository.SchemaSnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Single owner of {@code schema_snapshot} writes.
 *
 * <p>Two callers used to write this table:
 * <ul>
 *   <li>This service, via {@link SchemaScannerService#scanSchema} → {@link SchemaMetadata}
 *       → JSON with {@code tables: [...]} (array). Used by brain init.
 *   <li>{@code SchemaChangeTrackingService.captureSnapshot} (deleted), via
 *       {@link SchemaIntrospectionService} → raw {@code Map<String, Object>}
 *       → JSON with {@code tables: {...}} (object keyed by name). Used by
 *       drift detection.
 * </ul>
 *
 * <p>Both shapes lived in the same column, so a brain init that picked up a
 * drift-captured "latest" snapshot deserialization-failed and looped forever.
 * Merged: this service now exposes {@link #captureSnapshot(String, String, SchemaSnapshot.SnapshotType, String, String)}
 * which produces the rich drift-compatible JSON in array shape, replacing
 * the duplicate writer. Drift detection's comparison code consumes the array
 * shape via its existing {@code normalizeTablesByName} helper, so detection
 * fidelity is preserved (constraints + foreign keys still serialized).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SchemaSnapshotService {
    private final SchemaSnapshotRepository schemaSnapshotRepository;
    private final SchemaScannerService schemaScannerService;
    private final SchemaIntrospectionService introspectionService;
    private final ObjectMapper objectMapper;

    @Transactional
    public SchemaSnapshot captureSnapshot(String connectionId) {
        return captureSnapshot(connectionId, false);
    }

    @Transactional
    public SchemaSnapshot captureSnapshot(String connectionId, boolean forceRefresh) {
        try {
            if (forceRefresh) {
                schemaScannerService.evictSchemaCache(connectionId);
            }
            SchemaMetadata schema = schemaScannerService.scanSchema(connectionId);
            String schemaJson = objectMapper.writeValueAsString(schema);
            int tableCount = schema.getTables() != null ? schema.getTables().size() : 0;
            int columnCount = schema.getTables() != null
                ? schema.getTables().stream().mapToInt(t -> t.getColumns() != null ? t.getColumns().size() : 0).sum()
                : 0;

            SchemaSnapshot snapshot = SchemaSnapshot.builder()
                .connectionId(connectionId)
                .schemaJson(schemaJson)
                .schemaHash(sha256(schemaJson))
                .tableCount(tableCount)
                .columnCount(columnCount)
                .build();

            return schemaSnapshotRepository.save(snapshot);
        } catch (Exception e) {
            log.warn("Failed to capture schema snapshot for connection {}", connectionId, e);
            return null;
        }
    }

    /**
     * Rich snapshot capture for drift detection and manual snapshots —
     * includes constraints and foreign keys per table (which the simpler
     * {@link #captureSnapshot(String, boolean)} overload doesn't capture,
     * since {@link SchemaMetadata} doesn't model them).
     *
     * <p>Replaces the deleted {@code SchemaChangeTrackingService.captureSnapshot}.
     * Same fields, but the {@code tables} key is serialized as a JSON
     * <b>array</b> (not an object keyed by table name) so the brain init's
     * {@link SchemaSnapshotService#deserializeSchema} path that consumes
     * {@code SchemaMetadata} can read it cleanly.
     *
     * <p>Drift detection's {@code detectChanges} method consumes either
     * shape via its existing {@code normalizeTablesByName} helper, so the
     * shape switch is transparent to comparison.
     *
     * <p>If the schema is unchanged from the most recent prior snapshot for
     * this connection (same SHA-256 of the JSON), returns that prior row
     * instead of writing a duplicate — matches the deduplication behavior
     * of the old drift-tracker writer.
     *
     * @throws RuntimeException on introspection / serialization failure so
     *     the caller (drift baseline, controller) gets a real error instead
     *     of a silent null.
     */
    @Transactional
    public SchemaSnapshot captureSnapshot(
        String connectionId,
        String snapshotName,
        SchemaSnapshot.SnapshotType type,
        String createdBy,
        String notes
    ) {
        log.info("Capturing rich schema snapshot for connection {} (type={})", connectionId, type);
        try {
            // 1. Pull the table list, then per-table details (columns +
            // indexes + constraints + foreign keys). Reusing the introspection
            // service the old drift writer used so detection fidelity is
            // identical — just emitted in a different outer shape.
            List<Map<String, Object>> tableInfos = introspectionService.getAllTables(connectionId);
            List<Map<String, Object>> tables = new ArrayList<>(tableInfos.size());
            int totalColumns = 0;
            int totalIndexes = 0;
            int totalConstraints = 0;
            for (Map<String, Object> tableInfo : tableInfos) {
                String tableName = (String) tableInfo.get("tableName");
                if (tableName == null) continue;
                Map<String, Object> tableMap = buildRichTableMap(connectionId, tableName, tableInfo);
                if (tableMap == null) continue;
                tables.add(tableMap);

                @SuppressWarnings("unchecked")
                List<Object> cols = (List<Object>) tableMap.get("columns");
                @SuppressWarnings("unchecked")
                List<Object> idx = (List<Object>) tableMap.get("indexes");
                @SuppressWarnings("unchecked")
                List<Object> cons = (List<Object>) tableMap.get("constraints");
                if (cols != null) totalColumns += cols.size();
                if (idx != null) totalIndexes += idx.size();
                if (cons != null) totalConstraints += cons.size();
            }

            // 2. Outer envelope — array shape so SchemaMetadata can bind it.
            Map<String, Object> envelope = new LinkedHashMap<>();
            envelope.put("tables", tables);
            envelope.put("capturedAt", LocalDateTime.now().toString());
            String schemaJson = objectMapper.writeValueAsString(envelope);

            // 3. Dedup against the most recent snapshot — matches the old
            // drift writer's behavior (avoids writing identical rows on
            // every drift-check tick when the schema hasn't changed).
            String hash = sha256(schemaJson);
            if (schemaSnapshotRepository.existsByConnectionIdAndSchemaHash(connectionId, hash)) {
                log.info("Schema unchanged for connection {} — returning existing snapshot", connectionId);
                return schemaSnapshotRepository
                    .findByConnectionIdAndSchemaHash(connectionId, hash)
                    .orElse(null);
            }

            SchemaSnapshot snapshot = SchemaSnapshot.builder()
                .connectionId(connectionId)
                .snapshotName(snapshotName != null
                    ? snapshotName
                    : "Snapshot " + LocalDateTime.now())
                .snapshotType(type)
                .schemaJson(schemaJson)
                .schemaHash(hash)
                .tableCount(tables.size())
                .columnCount(totalColumns)
                .indexCount(totalIndexes)
                .constraintCount(totalConstraints)
                .createdBy(createdBy)
                .notes(notes)
                .build();
            return schemaSnapshotRepository.save(snapshot);
        } catch (Exception e) {
            log.error("Failed to capture rich schema snapshot for {}: {}", connectionId, e.getMessage(), e);
            throw new RuntimeException("Schema snapshot capture failed: " + e.getMessage(), e);
        }
    }

    /**
     * Build the per-table Map that goes into the snapshot's {@code tables}
     * array. Same shape the old drift writer's {@code buildSchemaMap}
     * produced per table: name + rowCount/sizeBytes + columns/indexes/
     * constraints/foreignKeys lists. The outer envelope decides whether to
     * key by name (old shape) or push into an array (new shape) — this
     * helper is shape-agnostic.
     *
     * <p>Returns null when introspection fails for the table so the caller
     * can skip without poisoning the whole capture.
     */
    private Map<String, Object> buildRichTableMap(
        String connectionId,
        String tableName,
        Map<String, Object> tableInfo
    ) {
        try {
            TableDetails details = introspectionService.getTableDetails(connectionId, tableName);
            if (details == null) return null;

            Map<String, Object> tableMap = new LinkedHashMap<>();
            tableMap.put("name", tableName);
            tableMap.put("rowCount", tableInfo.get("rowCount"));
            tableMap.put("sizeBytes", tableInfo.get("sizeBytes"));

            List<Map<String, Object>> columnsData = new ArrayList<>();
            if (details.getColumns() != null) {
                for (var col : details.getColumns()) {
                    Map<String, Object> colMap = new LinkedHashMap<>();
                    colMap.put("name", col.getColumnName());
                    colMap.put("dataType", col.getDataType());
                    colMap.put("columnType", col.getColumnType());
                    colMap.put("isNullable", col.getIsNullable());
                    colMap.put("isPrimaryKey", col.getIsPrimaryKey());
                    colMap.put("isForeignKey", col.getIsForeignKey());
                    colMap.put("defaultValue", col.getColumnDefault());
                    columnsData.add(colMap);
                }
            }
            tableMap.put("columns", columnsData);

            List<Map<String, Object>> indexesData = new ArrayList<>();
            if (details.getIndexes() != null) {
                for (var idx : details.getIndexes()) {
                    Map<String, Object> idxMap = new LinkedHashMap<>();
                    idxMap.put("name", idx.getIndexName());
                    idxMap.put("columns", idx.getColumns());
                    idxMap.put("isUnique", idx.getIsUnique());
                    idxMap.put("isPrimary", idx.getIsPrimary());
                    idxMap.put("indexType", idx.getIndexType());
                    indexesData.add(idxMap);
                }
            }
            tableMap.put("indexes", indexesData);

            List<Map<String, Object>> constraintsData = new ArrayList<>();
            if (details.getConstraints() != null) {
                for (var con : details.getConstraints()) {
                    Map<String, Object> conMap = new LinkedHashMap<>();
                    conMap.put("name", con.getConstraintName());
                    conMap.put("type", con.getConstraintType());
                    conMap.put("columns", con.getColumns());
                    constraintsData.add(conMap);
                }
            }
            tableMap.put("constraints", constraintsData);

            List<Map<String, Object>> fksData = new ArrayList<>();
            if (details.getForeignKeys() != null) {
                for (var fk : details.getForeignKeys()) {
                    Map<String, Object> fkMap = new LinkedHashMap<>();
                    fkMap.put("name", fk.getConstraintName());
                    fkMap.put("column", fk.getColumnName());
                    fkMap.put("referencedTable", fk.getReferencedTable());
                    fkMap.put("referencedColumn", fk.getReferencedColumn());
                    fkMap.put("onDelete", fk.getOnDelete());
                    fkMap.put("onUpdate", fk.getOnUpdate());
                    fksData.add(fkMap);
                }
            }
            tableMap.put("foreignKeys", fksData);

            return tableMap;
        } catch (Exception e) {
            log.warn("Could not get details for table {}: {}", tableName, e.getMessage());
            return null;
        }
    }

    @Transactional(readOnly = true)
    public Optional<SchemaMetadata> getLatestSchemaMetadata(String connectionId) {
        return schemaSnapshotRepository.findTopByConnectionIdOrderByCapturedAtDesc(connectionId)
            .flatMap(snapshot -> deserializeSchema(connectionId, snapshot));
    }

    @Transactional(readOnly = true)
    public List<DatabaseObject> getLatestDatabaseObjects(String connectionId) {
        return getLatestSchemaMetadata(connectionId)
            .map(this::toDatabaseObjects)
            .orElseGet(Collections::emptyList);
    }

    private Optional<SchemaMetadata> deserializeSchema(String connectionId, SchemaSnapshot snapshot) {
        try {
            // The schema_snapshot table is written by TWO services with
            // incompatible shapes for the top-level `tables` key:
            //
            //   * SchemaSnapshotService (this class) writes via the
            //     SchemaMetadata model, producing tables as a JSON ARRAY:
            //       {"tables": [{"name": "users", "columns": [...]}, ...]}
            //
            //   * SchemaChangeTrackingService.buildSchemaMap (used by the
            //     schema-drift baseline auto-capture) builds a raw
            //     Map<String, Object> keyed by table name, producing tables
            //     as a JSON OBJECT:
            //       {"tables": {"users": {"name": "users", "columns": [...]}, ...}}
            //
            // Both write to schema_snapshot, but only the array form
            // deserializes cleanly into SchemaMetadata.tables (List<...>).
            // When the brain init's SCHEMA_CLASSIFICATION stage picks up a
            // drift-tracker-written snapshot as the "latest", deserialization
            // throws MismatchedInputException and the stage loops forever on
            // db-scheduler retries.
            //
            // Normalize via the JSON tree: if tables is an object, lift the
            // values into an array before binding. The per-table shape is
            // already a superset of TableMetadata so the values bind directly.
            com.fasterxml.jackson.databind.JsonNode root =
                objectMapper.readTree(snapshot.getSchemaJson());
            com.fasterxml.jackson.databind.JsonNode tables = root.get("tables");
            if (tables != null && tables.isObject()) {
                com.fasterxml.jackson.databind.node.ArrayNode arr =
                    objectMapper.createArrayNode();
                tables.fields().forEachRemaining(entry -> arr.add(entry.getValue()));
                ((com.fasterxml.jackson.databind.node.ObjectNode) root).set("tables", arr);
            }
            // Use a reader that ignores unknown fields — drift snapshots add
            // extras (constraints, foreignKeys, columnType, etc.) that the
            // SchemaMetadata/TableMetadata models don't know about.
            return Optional.ofNullable(
                objectMapper
                    .readerFor(SchemaMetadata.class)
                    .without(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                    .readValue(root)
            );
        } catch (Exception e) {
            log.warn(
                "Failed to deserialize latest schema snapshot {} for connection {}",
                snapshot.getId(),
                connectionId,
                e
            );
            return Optional.empty();
        }
    }

    private List<DatabaseObject> toDatabaseObjects(SchemaMetadata schema) {
        if (schema == null || schema.getTables() == null || schema.getTables().isEmpty()) {
            return Collections.emptyList();
        }

        List<DatabaseObject> objects = new ArrayList<>(schema.getTables().size());
        for (TableMetadata table : schema.getTables()) {
            DatabaseObject object = new DatabaseObject();
            object.setName(table.getName());
            object.setSchema(table.getSchema() != null ? table.getSchema() : schema.getDatabaseName());
            object.setType(table.getType());
            object.setRowCount(table.getRowCount());
            object.setColumns(toColumnInfos(table.getColumns()));
            objects.add(object);
        }
        return objects;
    }

    private List<ColumnInfo> toColumnInfos(List<ColumnMetadata> columns) {
        if (columns == null || columns.isEmpty()) {
            return Collections.emptyList();
        }

        List<ColumnInfo> infos = new ArrayList<>(columns.size());
        for (ColumnMetadata column : columns) {
            ColumnInfo info = new ColumnInfo();
            info.setName(column.getName());
            info.setDataType(column.getDataType());
            info.setNullable(column.getNullable());
            info.setPrimaryKey(column.getPrimaryKey());
            info.setDefaultValue(column.getDefaultValue());
            infos.add(info);
        }
        return infos;
    }

    private String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash schema snapshot", e);
        }
    }
}
