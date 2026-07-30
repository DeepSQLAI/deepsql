package com.dbaagent.service.brain.classification;

import com.dbaagent.service.ConnectionService;
import com.dbaagent.service.CredentialService;

import com.dbaagent.model.ConnectionRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Service for classifying temporal patterns in tables.
 *
 * Temporal Types:
 * - TIME_SERIES: Has timestamp + monotonically increasing data, high insert rate
 * - SCD_TYPE_1: Has updated_at but no history columns (overwrites)
 * - SCD_TYPE_2: Has effective_from, effective_to, is_current columns (versioned)
 * - AUDIT_LOG: Naming pattern *_audit, *_log, *_history + timestamp + action column
 * - EVENT_SOURCING: Immutable (no updates), has event_type, sequential IDs
 * - SNAPSHOT: Point-in-time snapshot data (has snapshot_date/as_of_date)
 * - NONE: No temporal pattern detected
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TemporalClassificationService {

    private final ConnectionService connectionService;
    private final CredentialService credentialService;

    // Patterns for detecting temporal columns
    private static final Pattern CREATED_AT_PATTERN = Pattern.compile(
        "(?i)(created_at|createdat|created_date|creation_date|inserted_at|date_created|create_time)");
    private static final Pattern UPDATED_AT_PATTERN = Pattern.compile(
        "(?i)(updated_at|updatedat|modified_at|modifiedat|last_modified|modification_date|update_time|changed_at)");
    private static final Pattern EFFECTIVE_FROM_PATTERN = Pattern.compile(
        "(?i)(effective_from|valid_from|start_date|begin_date|from_date|effective_date)");
    private static final Pattern EFFECTIVE_TO_PATTERN = Pattern.compile(
        "(?i)(effective_to|valid_to|end_date|expiry_date|to_date|termination_date)");
    private static final Pattern IS_CURRENT_PATTERN = Pattern.compile(
        "(?i)(is_current|is_active|current_flag|active_flag|is_latest|is_valid)");
    private static final Pattern SNAPSHOT_DATE_PATTERN = Pattern.compile(
        "(?i)(snapshot_date|as_of_date|point_in_time|snapshot_at|data_date|report_date)");
    private static final Pattern EVENT_TYPE_PATTERN = Pattern.compile(
        "(?i)(event_type|event_name|action_type|operation_type|activity_type)");
    private static final Pattern ACTION_PATTERN = Pattern.compile(
        "(?i)(action|operation|event|activity|change_type)");

    // Patterns for audit/log table names
    private static final Pattern AUDIT_TABLE_PATTERN = Pattern.compile(
        "(?i).*(_audit|_log|_history|_changelog|_trail|audit_|log_|history_).*");
    private static final Pattern EVENT_TABLE_PATTERN = Pattern.compile(
        "(?i).*(events?|event_store|event_log|domain_events).*");

    /**
     * Classify temporal patterns for all tables in a connection.
     */
    public Map<String, TemporalClassification> classifyTemporalPatterns(String connectionId) {
        Map<String, TemporalClassification> results = new HashMap<>();

        ConnectionRequest connRequest = credentialService.getDecryptedConnection(connectionId);
        if (connRequest == null) {
            log.warn("Connection not found: {}", connectionId);
            return results;
        }

        String dbType = connRequest.getDbType();

        try (Connection conn = connectionService.getConnection(connectionId, connRequest)) {
            // Get all tables with their columns and types
            Map<String, List<ColumnInfo>> tableColumns = getTableColumns(conn, dbType);

            // Get table statistics for additional context
            Map<String, TableStats> tableStats = getTableStats(conn, dbType);

            // Classify each table
            for (Map.Entry<String, List<ColumnInfo>> entry : tableColumns.entrySet()) {
                String tableName = entry.getKey();
                List<ColumnInfo> columns = entry.getValue();
                TableStats stats = tableStats.get(tableName.toLowerCase());

                TemporalClassification classification = classifyTable(tableName, columns, stats);
                results.put(tableName.toLowerCase(), classification);
            }

        } catch (Exception e) {
            log.error("Error classifying temporal patterns for connection {}: {}", connectionId, e.getMessage(), e);
        }

        return results;
    }

    /**
     * Classify a single table's temporal pattern.
     */
    private TemporalClassification classifyTable(String tableName, List<ColumnInfo> columns, TableStats stats) {
        List<TimestampColumnInfo> timestampColumns = new ArrayList<>();
        boolean hasCreatedAt = false;
        boolean hasUpdatedAt = false;
        boolean hasEffectiveFrom = false;
        boolean hasEffectiveTo = false;
        boolean hasIsCurrent = false;
        boolean hasSnapshotDate = false;
        boolean hasEventType = false;
        boolean hasAction = false;

        // Analyze each column
        for (ColumnInfo col : columns) {
            String colName = col.getColumnName().toLowerCase();
            String colType = col.getDataType().toLowerCase();

            // Check if it's a timestamp/date column
            boolean isTimestampType = colType.contains("timestamp") || colType.contains("datetime") ||
                colType.contains("date") || colType.contains("time");

            if (CREATED_AT_PATTERN.matcher(colName).matches()) {
                hasCreatedAt = true;
                if (isTimestampType) {
                    timestampColumns.add(TimestampColumnInfo.builder()
                        .column(colName)
                        .type("CREATION")
                        .indexed(col.isIndexed())
                        .build());
                }
            }
            if (UPDATED_AT_PATTERN.matcher(colName).matches()) {
                hasUpdatedAt = true;
                if (isTimestampType) {
                    timestampColumns.add(TimestampColumnInfo.builder()
                        .column(colName)
                        .type("MODIFICATION")
                        .indexed(col.isIndexed())
                        .build());
                }
            }
            if (EFFECTIVE_FROM_PATTERN.matcher(colName).matches()) {
                hasEffectiveFrom = true;
                if (isTimestampType) {
                    timestampColumns.add(TimestampColumnInfo.builder()
                        .column(colName)
                        .type("EFFECTIVE_FROM")
                        .indexed(col.isIndexed())
                        .build());
                }
            }
            if (EFFECTIVE_TO_PATTERN.matcher(colName).matches()) {
                hasEffectiveTo = true;
                if (isTimestampType) {
                    timestampColumns.add(TimestampColumnInfo.builder()
                        .column(colName)
                        .type("EFFECTIVE_TO")
                        .indexed(col.isIndexed())
                        .build());
                }
            }
            if (IS_CURRENT_PATTERN.matcher(colName).matches()) {
                hasIsCurrent = true;
            }
            if (SNAPSHOT_DATE_PATTERN.matcher(colName).matches()) {
                hasSnapshotDate = true;
                if (isTimestampType) {
                    timestampColumns.add(TimestampColumnInfo.builder()
                        .column(colName)
                        .type("SNAPSHOT")
                        .indexed(col.isIndexed())
                        .build());
                }
            }
            if (EVENT_TYPE_PATTERN.matcher(colName).matches()) {
                hasEventType = true;
            }
            if (ACTION_PATTERN.matcher(colName).matches()) {
                hasAction = true;
            }

            // Also capture any timestamp columns not matched above
            if (isTimestampType && timestampColumns.stream()
                    .noneMatch(tc -> tc.getColumn().equals(colName))) {
                timestampColumns.add(TimestampColumnInfo.builder()
                    .column(colName)
                    .type("OTHER")
                    .indexed(col.isIndexed())
                    .build());
            }
        }

        // Determine temporal type based on detected patterns
        String temporalType;
        double confidence;
        String reasoning;

        boolean isAuditTableName = AUDIT_TABLE_PATTERN.matcher(tableName).matches();
        boolean isEventTableName = EVENT_TABLE_PATTERN.matcher(tableName).matches();

        // Check for SCD Type 2 (highest priority - most specific)
        if (hasEffectiveFrom && (hasEffectiveTo || hasIsCurrent)) {
            temporalType = "SCD_TYPE_2";
            confidence = 90.0;
            if (hasEffectiveFrom && hasEffectiveTo && hasIsCurrent) {
                confidence = 95.0;
            }
            reasoning = String.format("SCD Type 2 pattern: effective_from=%b, effective_to=%b, is_current=%b",
                hasEffectiveFrom, hasEffectiveTo, hasIsCurrent);
        }
        // Check for Audit Log
        else if (isAuditTableName && hasCreatedAt && (hasAction || hasEventType)) {
            temporalType = "AUDIT_LOG";
            confidence = 90.0;
            reasoning = String.format("Audit log pattern: table name matches audit pattern, has timestamp and action column");
        }
        // Check for Event Sourcing
        else if ((isEventTableName || hasEventType) && hasCreatedAt && !hasUpdatedAt) {
            temporalType = "EVENT_SOURCING";
            confidence = 85.0;
            // Additional check: low/zero updates suggest immutability
            if (stats != null && stats.getUpdateCount() == 0 && stats.getInsertCount() > 0) {
                confidence = 92.0;
                reasoning = "Event sourcing pattern: immutable (0 updates), has event type and creation timestamp";
            } else {
                reasoning = "Event sourcing pattern: has event type and creation timestamp";
            }
        }
        // Check for Snapshot
        else if (hasSnapshotDate) {
            temporalType = "SNAPSHOT";
            confidence = 85.0;
            reasoning = "Snapshot pattern: has snapshot/as_of date column";
        }
        // Check for Time Series (high insert rate, timestamp column, append-only pattern)
        else if (hasCreatedAt && !hasUpdatedAt && stats != null &&
                 stats.getInsertCount() > 1000 && stats.getUpdateCount() < stats.getInsertCount() * 0.01) {
            temporalType = "TIME_SERIES";
            confidence = 80.0;
            reasoning = String.format("Time series pattern: %,d inserts, %,d updates (append-heavy)",
                stats.getInsertCount(), stats.getUpdateCount());
        }
        // Check for SCD Type 1 (has updated_at but no versioning)
        else if (hasUpdatedAt && !hasEffectiveFrom && !hasEffectiveTo && !hasIsCurrent) {
            temporalType = "SCD_TYPE_1";
            confidence = 75.0;
            reasoning = "SCD Type 1 pattern: has updated_at but no versioning columns";
        }
        // Default: no temporal pattern
        else {
            temporalType = "NONE";
            confidence = hasCreatedAt || hasUpdatedAt ? 60.0 : 90.0;
            reasoning = timestampColumns.isEmpty() ?
                "No temporal columns detected" :
                "Has timestamp columns but no clear temporal pattern";
        }

        return TemporalClassification.builder()
            .tableName(tableName)
            .temporalType(temporalType)
            .hasTimestampColumns(!timestampColumns.isEmpty())
            .timestampColumns(timestampColumns)
            .confidence(BigDecimal.valueOf(confidence).setScale(2, RoundingMode.HALF_UP))
            .reasoning(reasoning)
            .indicators(Map.of(
                "has_created_at", hasCreatedAt,
                "has_updated_at", hasUpdatedAt,
                "has_effective_from", hasEffectiveFrom,
                "has_effective_to", hasEffectiveTo,
                "has_is_current", hasIsCurrent,
                "has_snapshot_date", hasSnapshotDate,
                "has_event_type", hasEventType,
                "is_audit_table_name", isAuditTableName,
                "is_event_table_name", isEventTableName
            ))
            .build();
    }

    /**
     * Get columns for all tables.
     */
    private Map<String, List<ColumnInfo>> getTableColumns(Connection conn, String dbType) {
        Map<String, List<ColumnInfo>> tableColumns = new HashMap<>();

        String sql;
        if ("postgresql".equalsIgnoreCase(dbType)) {
            sql = """
                SELECT
                    c.table_name,
                    c.column_name,
                    c.data_type,
                    EXISTS (
                        SELECT 1 FROM pg_indexes pi
                        WHERE pi.tablename = c.table_name
                        AND pi.indexdef LIKE '%' || c.column_name || '%'
                    ) as is_indexed
                FROM information_schema.columns c
                WHERE c.table_schema NOT IN ('pg_catalog', 'information_schema')
                ORDER BY c.table_name, c.ordinal_position
                """;
        } else {
            sql = """
                SELECT
                    c.TABLE_NAME as table_name,
                    c.COLUMN_NAME as column_name,
                    c.DATA_TYPE as data_type,
                    EXISTS (
                        SELECT 1 FROM information_schema.STATISTICS s
                        WHERE s.TABLE_SCHEMA = c.TABLE_SCHEMA
                        AND s.TABLE_NAME = c.TABLE_NAME
                        AND s.COLUMN_NAME = c.COLUMN_NAME
                    ) as is_indexed
                FROM information_schema.COLUMNS c
                WHERE c.TABLE_SCHEMA = DATABASE()
                ORDER BY c.TABLE_NAME, c.ORDINAL_POSITION
                """;
        }

        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                String tableName = rs.getString("table_name").toLowerCase();
                ColumnInfo col = ColumnInfo.builder()
                    .columnName(rs.getString("column_name"))
                    .dataType(rs.getString("data_type"))
                    .indexed(rs.getBoolean("is_indexed"))
                    .build();

                tableColumns.computeIfAbsent(tableName, k -> new ArrayList<>()).add(col);
            }
        } catch (Exception e) {
            log.error("Error getting table columns: {}", e.getMessage(), e);
        }

        return tableColumns;
    }

    /**
     * Get table statistics (insert/update counts).
     */
    private Map<String, TableStats> getTableStats(Connection conn, String dbType) {
        Map<String, TableStats> stats = new HashMap<>();

        if (!"postgresql".equalsIgnoreCase(dbType)) {
            // MySQL doesn't have easy access to these stats without performance_schema
            return stats;
        }

        String sql = """
            SELECT
                relname as table_name,
                COALESCE(n_tup_ins, 0) as inserts,
                COALESCE(n_tup_upd, 0) as updates,
                COALESCE(n_tup_del, 0) as deletes
            FROM pg_stat_user_tables
            """;

        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                String tableName = rs.getString("table_name").toLowerCase();
                stats.put(tableName, TableStats.builder()
                    .insertCount(rs.getLong("inserts"))
                    .updateCount(rs.getLong("updates"))
                    .deleteCount(rs.getLong("deletes"))
                    .build());
            }
        } catch (Exception e) {
            log.error("Error getting table stats: {}", e.getMessage(), e);
        }

        return stats;
    }

    // ==================== Data Classes ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TemporalClassification {
        private String tableName;
        private String temporalType;  // TIME_SERIES, SCD_TYPE_1, SCD_TYPE_2, AUDIT_LOG, EVENT_SOURCING, SNAPSHOT, NONE
        private boolean hasTimestampColumns;
        private List<TimestampColumnInfo> timestampColumns;
        private BigDecimal confidence;
        private String reasoning;
        private Map<String, Object> indicators;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TimestampColumnInfo {
        private String column;
        private String type;  // CREATION, MODIFICATION, EFFECTIVE_FROM, EFFECTIVE_TO, SNAPSHOT, OTHER
        private boolean indexed;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class ColumnInfo {
        private String columnName;
        private String dataType;
        private boolean indexed;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class TableStats {
        private long insertCount;
        private long updateCount;
        private long deleteCount;
    }
}
