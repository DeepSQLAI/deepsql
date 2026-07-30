package com.dbaagent.service.brain.analysis;

import com.dbaagent.model.brain.BrainProfileResult;
import com.dbaagent.model.*;
import com.dbaagent.repository.ColumnProfileRepository;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.provider.api.SamplingProvider;
import com.dbaagent.service.ConnectionService;
import com.dbaagent.service.CredentialService;
import com.dbaagent.service.SchemaScannerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ColumnProfilingService {
    private static final int TOP_VALUES_LIMIT = 10;
    private static final int MAX_TOP_VALUE_LENGTH = 100;
    /** Low cardinality: always fetch top values (enum-like columns). */
    private static final int MAX_DISTINCT_FOR_TOP_VALUES = 50;
    /** Medium cardinality: still useful for AI to see dominant patterns. */
    private static final int MAX_MEDIUM_CARDINALITY = 200;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    /** Data types where GROUP BY / String.valueOf produce useless results. */
    private static final Set<String> BINARY_TYPES = Set.of(
        "bytea", "blob", "binary", "varbinary", "tinyblob", "mediumblob", "longblob",
        "image", "oid", "bit varying", "raw", "long raw"
    );

    private final ConnectionService connectionService;
    private final CredentialService credentialService;
    private final SchemaScannerService schemaScannerService;
    private final ColumnProfileRepository columnProfileRepository;
    private final DatabaseProviderRegistry providerRegistry;

    @Value("${brain.profile.max-columns-per-table:40}")
    private int maxColumnsPerTable;

    @Value("${brain.profile.max-tables:50}")
    private int maxTables;

    @Value("${brain.profile.max-rowcount:1000000}")
    private long maxRowCount;

    @Value("${brain.profile.query-timeout-seconds:10}")
    private int queryTimeoutSeconds;

    public interface ProfilingProgressCallback {
        void onProgress(int tablesProcessed, int totalEligible, String tableName);
    }

    public BrainProfileResult profileConnection(String connectionId) {
        return profileConnection(connectionId, null);
    }

    public BrainProfileResult profileConnection(String connectionId, ProfilingProgressCallback callback) {
        SchemaMetadata schema;
        try {
            schema = schemaScannerService.scanSchema(connectionId);
        } catch (Exception e) {
            throw new RuntimeException("Failed to load schema metadata", e);
        }

        ConnectionRequest request = credentialService.getDecryptedConnection(connectionId);
        String dbType = request.getDbType() != null ? request.getDbType().toLowerCase(Locale.ROOT) : "unknown";

        // Filter eligibility BEFORE applying cap — ensures large tables don't consume the limit
        int tableLimit = maxTables > 0 ? maxTables : Integer.MAX_VALUE;
        List<TableMetadata> eligibleTables = schema.getTables().stream()
            .filter(t -> "table".equalsIgnoreCase(t.getType()))
            .filter(t -> t.getRowCount() == null || t.getRowCount() <= maxRowCount)
            .limit(tableLimit)
            .toList();
        int totalEligible = eligibleTables.size();

        int tablesProfiled = 0;
        int columnsProfiled = 0;
        int tablesSkipped = 0;
        int columnsSkipped = 0;

        try (Connection connection = connectionService.getConnection(connectionId, request)) {
            int processed = 0;

            for (TableMetadata table : eligibleTables) {

                int profiledForTable = profileTable(connectionId, request, connection, table);
                if (profiledForTable > 0) {
                    tablesProfiled++;
                    columnsProfiled += profiledForTable;
                } else {
                    tablesSkipped++;
                }
                int totalColumns = table.getColumns() != null ? table.getColumns().size() : 0;
                int skipped = Math.max(0, totalColumns - profiledForTable);
                columnsSkipped += skipped;

                processed++;
                if (callback != null) {
                    callback.onProgress(processed, totalEligible, table.getName());
                }
            }
        } catch (Exception e) {
            log.error("Failed to profile connection {}", connectionId, e);
            throw new RuntimeException("Failed to profile connection", e);
        }

        return BrainProfileResult.builder()
            .connectionId(connectionId)
            .tablesProfiled(tablesProfiled)
            .columnsProfiled(columnsProfiled)
            .tablesSkipped(tablesSkipped)
            .columnsSkipped(columnsSkipped)
            .profiledAt(LocalDateTime.now())
            .build();
    }

    private int profileTable(
        String connectionId,
        ConnectionRequest request,
        Connection connection,
        TableMetadata table
    ) {
        if (table.getColumns() == null || table.getColumns().isEmpty()) {
            return 0;
        }

        String dbType = request.getDbType() != null ? request.getDbType().toLowerCase(Locale.ROOT) : "unknown";
        SamplingProvider sampling = providerRegistry.getDialect(dbType).sampling();
        String schemaName = table.getSchema() != null ? table.getSchema() : request.getDatabase();
        String tableRef = sampling.qualifyTable(schemaName, table.getName());

        int limit = maxColumnsPerTable > 0 ? Math.min(maxColumnsPerTable, table.getColumns().size()) : table.getColumns().size();
        int profiled = 0;

        for (int i = 0; i < limit; i++) {
            ColumnMetadata column = table.getColumns().get(i);
            if (column == null || column.getName() == null) {
                continue;
            }
            String columnRef = sampling.quoteIdentifier(column.getName());
            try (Statement stmt = connection.createStatement()) {
                stmt.setQueryTimeout(queryTimeoutSeconds);
                String aggregateSql = buildAggregateSql(tableRef, columnRef);
                ResultSet rs = stmt.executeQuery(aggregateSql);

                Long totalRows = null;
                Long nullCount = null;
                Long distinctCount = null;
                String minValue = null;
                String maxValue = null;
                if (rs.next()) {
                    totalRows = getLongSafely(rs, "total_rows");
                    nullCount = getLongSafely(rs, "null_count");
                    distinctCount = getLongSafely(rs, "distinct_count");
                    minValue = getStringSafely(rs, "min_val");
                    maxValue = getStringSafely(rs, "max_val");
                }
                rs.close();

                String sampleValue = fetchSampleValue(stmt, tableRef, columnRef);

                Optional<ColumnProfile> existing = columnProfileRepository
                    .findByConnectionIdAndTableNameAndColumnName(connectionId, table.getName(), column.getName());

                ColumnProfile profile = existing.orElseGet(() -> ColumnProfile.builder()
                    .connectionId(connectionId)
                    .tableName(table.getName())
                    .columnName(column.getName())
                    .build());

                profile.setDataType(column.getDataType());
                profile.setTotalRows(totalRows);
                profile.setNullCount(nullCount);
                profile.setDistinctCount(distinctCount);
                profile.setMinValue(minValue);
                profile.setMaxValue(maxValue);
                profile.setSampleValue(sampleValue);
                profile.setProfiledAt(LocalDateTime.now());

                // Refresh top-values for eligible columns; clear for ineligible ones.
                // Only clear AFTER deciding eligibility — preserves prior data on fetch failure.
                if (distinctCount != null && distinctCount > 0
                        && distinctCount <= MAX_MEDIUM_CARDINALITY
                        && !isBinaryType(column.getDataType())) {
                    // fetchTopValues sets topValues/skewCoefficient only on success
                    fetchTopValues(stmt, tableRef, columnRef, profile, totalRows, nullCount);
                } else {
                    // Column no longer eligible (cardinality changed, binary type, etc.)
                    profile.setTopValues(null);
                    profile.setSkewCoefficient(null);
                }

                columnProfileRepository.save(profile);
                profiled++;
            } catch (Exception e) {
                log.debug("Failed to profile {}.{}: {}", table.getName(), column.getName(), e.getMessage());
            }
        }

        return profiled;
    }

    private String fetchSampleValue(Statement stmt, String tableRef, String columnRef) {
        try {
            String sql = "SELECT " + columnRef + " AS sample_val FROM " + tableRef +
                " WHERE " + columnRef + " IS NOT NULL LIMIT 1";
            ResultSet rs = stmt.executeQuery(sql);
            String sample = null;
            if (rs.next()) {
                sample = getStringSafely(rs, "sample_val");
            }
            rs.close();
            return sample;
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Fetch top-N values for a low-cardinality column, populating topValues JSON and skewCoefficient.
     * Uses the non-null row count as denominator for percentages since the query excludes NULLs.
     */
    private void fetchTopValues(Statement stmt, String tableRef, String columnRef,
                                ColumnProfile profile, Long totalRows, Long nullCount) {
        try {
            String sql = "SELECT " + columnRef + " AS val, COUNT(*) AS cnt "
                + "FROM " + tableRef + " WHERE " + columnRef + " IS NOT NULL "
                + "GROUP BY " + columnRef + " ORDER BY cnt DESC LIMIT " + TOP_VALUES_LIMIT;
            ResultSet rs = stmt.executeQuery(sql);

            long nonNullRows = (totalRows != null && nullCount != null) ? totalRows - nullCount : 0;
            List<Map<String, Object>> topValues = new ArrayList<>();
            long topCount = 0;
            boolean first = true;

            while (rs.next()) {
                Object rawVal = rs.getObject("val");
                String value = rawVal != null ? rawVal.toString() : "NULL";
                if (value.length() > MAX_TOP_VALUE_LENGTH) {
                    value = value.substring(0, MAX_TOP_VALUE_LENGTH) + "...";
                }
                long count = rs.getLong("cnt");
                if (first) {
                    topCount = count;
                    first = false;
                }
                double pct = nonNullRows > 0
                    ? Math.round(count * 10000.0 / nonNullRows) / 100.0 : 0.0;

                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("value", value);
                entry.put("count", count);
                entry.put("percentage", pct);
                topValues.add(entry);
            }
            rs.close();

            if (!topValues.isEmpty()) {
                profile.setTopValues(OBJECT_MAPPER.writeValueAsString(topValues));
                profile.setSkewCoefficient(
                    nonNullRows > 0 ? (double) topCount / nonNullRows : 0.0);
            }
        } catch (Exception e) {
            log.debug("Failed to fetch top values for {}.{}: {}",
                profile.getTableName(), profile.getColumnName(), e.getMessage());
        }
    }

    private static boolean isBinaryType(String dataType) {
        if (dataType == null) return false;
        String lower = dataType.toLowerCase(Locale.ROOT).trim();
        return BINARY_TYPES.stream().anyMatch(lower::startsWith);
    }

    private String buildAggregateSql(String tableRef, String columnRef) {
        return "SELECT COUNT(*) AS total_rows, " +
            "SUM(CASE WHEN " + columnRef + " IS NULL THEN 1 ELSE 0 END) AS null_count, " +
            "COUNT(DISTINCT " + columnRef + ") AS distinct_count, " +
            "MIN(" + columnRef + ") AS min_val, " +
            "MAX(" + columnRef + ") AS max_val " +
            "FROM " + tableRef;
    }

    private Long getLongSafely(ResultSet rs, String column) {
        try {
            Object value = rs.getObject(column);
            if (value == null) {
                return null;
            }
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
            return Long.parseLong(value.toString());
        } catch (Exception e) {
            return null;
        }
    }

    private String getStringSafely(ResultSet rs, String column) {
        try {
            Object value = rs.getObject(column);
            return value != null ? value.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
