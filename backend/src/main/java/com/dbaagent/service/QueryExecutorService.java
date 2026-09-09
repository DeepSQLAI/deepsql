package com.dbaagent.service;

import com.dbaagent.model.*;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.provider.api.IntrospectionProvider;
import com.dbaagent.repository.KeyColumnAnalysisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class QueryExecutorService {
    private final ConnectionService connectionService;
    private final CredentialService credentialService;
    private final SchemaSnapshotService schemaSnapshotService;
    private final CacheManager cacheManager;
    private final CacheMetricsService cacheMetricsService;
    private final QueryLineageService queryLineageService;
    private final DatabaseProviderRegistry providerRegistry;
    private final QueryExecutionPolicyService queryExecutionPolicyService;
    private final UserDataAccessPolicyService userDataAccessPolicyService;
    private final com.dbaagent.service.telemetry.TelemetryCounters telemetryCounters;
    private final KeyColumnAnalysisRepository keyColumnAnalysisRepository;
    private final RunningQueryRegistry runningQueryRegistry;

    @Value("${db.query-timeout-seconds:30}")
    private int queryTimeoutSeconds;

    /**
     * Reads the connection's server-side session id via the dialect's own query.
     * Never fatal: without it the query simply cannot be cancelled early.
     */
    private String resolveSessionPid(Connection connection, String dbType) {
        String pidQuery;
        try {
            pidQuery = providerRegistry.getDialect(dbType).queryExecution().getSessionPidQuery();
        } catch (Exception e) {
            return null;
        }
        if (pidQuery == null) {
            return null;
        }
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(pidQuery)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            log.debug("Could not resolve session pid: {}", e.getMessage());
            return null;
        }
    }

    public List<DatabaseObject> getDatabaseObjects(String connectionId) throws SQLException {
        // Try to get from cache, but handle cache failures gracefully
        Cache cache = null;
        try {
            cache = cacheManager.getCache("databaseObjects");
            if (cache != null) {
                List<DatabaseObject> cached = cache.get(connectionId, List.class);
                if (cached != null) {
                    cacheMetricsService.recordGet("databaseObjects", true);
                    return cached;
                }
                cacheMetricsService.recordGet("databaseObjects", false);
            }
        } catch (Exception e) {
            // Cache unavailable (e.g., Redis connection issue), proceed without cache
            log.warn("Cache unavailable for databaseObjects, proceeding without cache: {}", e.getMessage());
            cache = null;
        }

        List<DatabaseObject> snapshotObjects = schemaSnapshotService.getLatestDatabaseObjects(connectionId);
        if (!snapshotObjects.isEmpty()) {
            log.debug(
                "Loaded {} database objects for connection {} from latest schema snapshot",
                snapshotObjects.size(),
                connectionId
            );
            enrichColumnsWithKeyAndIndexMetadata(connectionId, snapshotObjects, null, null, null);
            if (cache != null) {
                try {
                    cache.put(connectionId, snapshotObjects);
                    cacheMetricsService.recordPut("databaseObjects");
                } catch (Exception e) {
                    log.warn("Failed to cache snapshot-backed databaseObjects: {}", e.getMessage());
                }
            }
            return snapshotObjects;
        }

        ConnectionRequest connRequest = credentialService.getDecryptedConnection(connectionId);
        List<DatabaseObject> objects = new ArrayList<>();

        try (Connection connection = connectionService.getConnection(connectionId, connRequest)) {
            IntrospectionProvider provider = providerRegistry.getDialect(connRequest.getDbType()).introspection();
            if (provider == null) {
                throw new IllegalArgumentException("IntrospectionProvider not available for: " + connRequest.getDbType());
            }
            objects.addAll(provider.getDatabaseObjects(connection, connRequest.getDatabase()));
            // Enrich in-place while we still hold the live connection.
            enrichColumnsWithKeyAndIndexMetadata(connectionId, objects, connection, connRequest, provider);
        }

        // Try to cache the result (skip caching empty list to avoid serving stale "no tables")
        if (cache != null && !objects.isEmpty()) {
            try {
                cache.put(connectionId, objects);
                cacheMetricsService.recordPut("databaseObjects");
            } catch (Exception e) {
                log.warn("Failed to cache databaseObjects: {}", e.getMessage());
            }
        }
        return objects;
    }

    /**
     * Best-effort enrichment of column metadata with index and key flags:
     *   - hasSingleColumnIndex / hasCompositeIndex (from live DB indexes)
     *   - directKey (PK or FK as declared in the database)
     *   - inferredKey (from brain's KeyColumnAnalysis when the column isn't already a direct key)
     *
     * If a live connection is already open (provider path), it is reused. Otherwise (snapshot path)
     * a fresh connection is opened just for the index/FK scan. Failures here never break the response —
     * the editor simply shows fewer badges.
     */
    private void enrichColumnsWithKeyAndIndexMetadata(
        String connectionId,
        List<DatabaseObject> objects,
        Connection existingConnection,
        ConnectionRequest existingConnRequest,
        IntrospectionProvider existingProvider
    ) {
        if (objects == null || objects.isEmpty()) {
            return;
        }

        // 1) Inferred-key data from the vault DB — does not need a live target-DB connection.
        Set<String> inferredKeyColumns = loadInferredKeyColumns(connectionId);

        // 2) Index + FK data needs the live target DB. Open a connection if we don't have one.
        boolean ownsConnection = false;
        Connection connection = existingConnection;
        ConnectionRequest connRequest = existingConnRequest;
        IntrospectionProvider provider = existingProvider;
        try {
            if (connection == null) {
                try {
                    connRequest = credentialService.getDecryptedConnection(connectionId);
                    connection = connectionService.getConnection(connectionId, connRequest);
                    provider = providerRegistry.getDialect(connRequest.getDbType()).introspection();
                    ownsConnection = true;
                } catch (Exception e) {
                    log.warn(
                        "Skipping index/FK enrichment for connection {} (could not open live connection): {}",
                        connectionId,
                        e.getMessage()
                    );
                    connection = null;
                }
            }

            Set<String> fkColumns = (connection != null && provider != null)
                ? loadForeignKeyColumns(connection, connRequest.getDatabase(), provider, connectionId)
                : Collections.emptySet();

            // Indexes for every table up front, in one round trip. Fetching them
            // table-by-table inside the loop below meant one query per table — on a
            // 567-table schema behind a tunnel that was minutes, and it ran again for
            // every caller that missed the cache.
            //
            // Not every name is necessarily answered: a provider returns no entry for
            // one it cannot resolve precisely, and an object qualified with a database
            // other than this connection's is never offered in the first place. Either
            // way the loop below sees no entry and falls back to the per-table query,
            // which reads from the schema the name actually points at.
            Map<String, List<TableIndex>> indexesByTable = Collections.emptyMap();
            if (connection != null && provider != null) {
                List<String> bulkTables = new ArrayList<>();
                for (DatabaseObject obj : objects) {
                    if (isBulkIndexable(obj, connRequest.getDatabase())) {
                        bulkTables.add(obj.getName());
                    }
                }
                if (!bulkTables.isEmpty()) {
                    try {
                        indexesByTable = provider.getAllTableIndexes(
                            connection, connRequest.getDatabase(), bulkTables
                        );
                    } catch (Exception e) {
                        // Fall back to the per-table path rather than losing index flags.
                        log.warn(
                            "Bulk index fetch failed for connection {} ({}); falling back to per-table",
                            connectionId,
                            e.getMessage()
                        );
                        indexesByTable = Collections.emptyMap();
                    }
                }
            }

            for (DatabaseObject obj : objects) {
                if (obj.getColumns() == null || obj.getColumns().isEmpty()) {
                    continue;
                }

                // Indexes (table-by-table). Only attempt for table-like objects.
                if (connection != null
                    && provider != null
                    && obj.getType() != null
                    && "table".equalsIgnoreCase(obj.getType())) {
                    try {
                        String key = obj.getName() == null
                            ? null
                            : obj.getName().toLowerCase(Locale.ROOT);
                        List<TableIndex> indexes = key == null ? null : indexesByTable.get(key);
                        if (indexes == null) {
                            indexes = provider.getTableIndexes(
                                connection, connRequest.getDatabase(), obj.getName()
                            );
                        }
                        applyIndexFlags(obj, indexes);
                    } catch (Exception e) {
                        log.debug(
                            "Index fetch failed for {}.{}: {}",
                            connRequest != null ? connRequest.getDatabase() : "?",
                            obj.getName(),
                            e.getMessage()
                        );
                    }
                }

                // Direct + inferred keys.
                for (ColumnInfo col : obj.getColumns()) {
                    String tableCol = (obj.getName() + "." + col.getName()).toLowerCase(Locale.ROOT);
                    boolean isPk = Boolean.TRUE.equals(col.getPrimaryKey());
                    boolean isFk = fkColumns.contains(tableCol);
                    boolean direct = isPk || isFk;
                    col.setDirectKey(direct);
                    col.setInferredKey(!direct && inferredKeyColumns.contains(tableCol));
                }
            }
        } finally {
            if (ownsConnection && connection != null) {
                try {
                    connection.close();
                } catch (SQLException ignored) {
                    // best effort
                }
            }
        }
    }

    private Set<String> loadForeignKeyColumns(
        Connection connection,
        String database,
        IntrospectionProvider provider,
        String connectionId
    ) {
        Set<String> fkColumns = new HashSet<>();
        try {
            List<RelationshipMetadata> fks = provider.getForeignKeys(connection, database);
            if (fks != null) {
                for (RelationshipMetadata fk : fks) {
                    if (fk.getFromTable() != null && fk.getFromColumn() != null) {
                        fkColumns.add(
                            (fk.getFromTable() + "." + fk.getFromColumn()).toLowerCase(Locale.ROOT)
                        );
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Foreign-key fetch failed for connection {}: {}", connectionId, e.getMessage());
        }
        return fkColumns;
    }

    /**
     * True when the bulk (single-schema) index fetch can answer for this object.
     * A name qualified with a different schema must not be answered from the
     * connection database's index list.
     */
    private boolean isBulkIndexable(DatabaseObject obj, String database) {
        if (obj == null || obj.getName() == null || !"table".equalsIgnoreCase(obj.getType())) {
            return false;
        }
        int dot = obj.getName().lastIndexOf('.');
        if (dot <= 0) {
            return true;
        }
        return obj.getName().substring(0, dot).equalsIgnoreCase(database);
    }

    private void applyIndexFlags(DatabaseObject obj, List<TableIndex> indexes) {
        if (indexes == null || indexes.isEmpty()) {
            return;
        }
        Set<String> singleColumnIdx = new HashSet<>();
        Set<String> compositeIdx = new HashSet<>();
        for (TableIndex idx : indexes) {
            List<String> cols = idx.getColumns();
            if (cols == null || cols.isEmpty()) {
                continue;
            }
            if (cols.size() == 1) {
                String only = cols.get(0);
                if (only != null) {
                    singleColumnIdx.add(only.toLowerCase(Locale.ROOT));
                }
            } else {
                for (String c : cols) {
                    if (c != null) {
                        compositeIdx.add(c.toLowerCase(Locale.ROOT));
                    }
                }
            }
        }
        for (ColumnInfo col : obj.getColumns()) {
            if (col.getName() == null) {
                continue;
            }
            String n = col.getName().toLowerCase(Locale.ROOT);
            col.setHasSingleColumnIndex(singleColumnIdx.contains(n));
            col.setHasCompositeIndex(compositeIdx.contains(n));
        }
    }

    private Set<String> loadInferredKeyColumns(String connectionId) {
        Set<String> inferred = new HashSet<>();
        try {
            List<KeyColumnAnalysis> analyses =
                keyColumnAnalysisRepository.findByConnectionIdOrderByImportanceScoreDesc(connectionId);
            if (analyses == null || analyses.isEmpty()) {
                return inferred;
            }
            for (KeyColumnAnalysis a : analyses) {
                String kt = a.getKeyType();
                if (kt == null) {
                    continue;
                }
                // Treat brain-classified TRUE_KEY and SURROGATE_KEY as inferred keys.
                if ("TRUE_KEY".equals(kt) || "SURROGATE_KEY".equals(kt)) {
                    if (a.getTableName() != null && a.getColumnName() != null) {
                        inferred.add(
                            (a.getTableName() + "." + a.getColumnName()).toLowerCase(Locale.ROOT)
                        );
                    }
                }
            }
        } catch (Exception e) {
            log.debug("KeyColumnAnalysis fetch failed for connection {}: {}", connectionId, e.getMessage());
        }
        return inferred;
    }

    /**
     * Evict the databaseObjects cache for a connection so the next getDatabaseObjects()
     * will load fresh data from the database. Call this when the user refreshes schema
     * or when schema may have changed.
     */
    public void evictDatabaseObjectsCache(String connectionId) {
        try {
            Cache cache = cacheManager.getCache("databaseObjects");
            if (cache != null) {
                cache.evict(connectionId);
                cacheMetricsService.recordEvict("databaseObjects", 1);
                log.debug("Evicted databaseObjects cache for connection: {}", connectionId);
            }
        } catch (Exception e) {
            log.warn("Failed to evict databaseObjects cache for {}: {}", connectionId, e.getMessage());
        }
    }

    private List<DatabaseObject> getMySQLObjects(Connection connection, String database) throws SQLException {
        List<DatabaseObject> objects = new ArrayList<>();

        // Get tables
        String tablesQuery = "SELECT TABLE_NAME, TABLE_TYPE, TABLE_ROWS " +
            "FROM INFORMATION_SCHEMA.TABLES " +
            "WHERE TABLE_SCHEMA = ? " +
            "ORDER BY TABLE_TYPE, TABLE_NAME";

        try (PreparedStatement stmt = connection.prepareStatement(tablesQuery)) {
            stmt.setString(1, database);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    DatabaseObject obj = new DatabaseObject();
                    obj.setName(rs.getString("TABLE_NAME"));
                    obj.setSchema(database);
                    String tableType = rs.getString("TABLE_TYPE");
                    obj.setType(tableType.equals("BASE TABLE") ? "table" : "view");
                    obj.setRowCount(rs.getLong("TABLE_ROWS"));

                    // Get columns for this object
                    obj.setColumns(getMySQLColumns(connection, database, obj.getName()));
                    objects.add(obj);
                }
            }
        }

        // Get functions
        String functionsQuery = "SELECT ROUTINE_NAME, ROUTINE_DEFINITION " +
            "FROM INFORMATION_SCHEMA.ROUTINES " +
            "WHERE ROUTINE_SCHEMA = ? AND ROUTINE_TYPE = 'FUNCTION' " +
            "ORDER BY ROUTINE_NAME";

        try (PreparedStatement stmt = connection.prepareStatement(functionsQuery)) {
            stmt.setString(1, database);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    DatabaseObject obj = new DatabaseObject();
                    obj.setName(rs.getString("ROUTINE_NAME"));
                    obj.setSchema(database);
                    obj.setType("function");
                    obj.setDefinition(rs.getString("ROUTINE_DEFINITION"));
                    objects.add(obj);
                }
            }
        }

        // Get procedures
        String proceduresQuery = "SELECT ROUTINE_NAME, ROUTINE_DEFINITION " +
            "FROM INFORMATION_SCHEMA.ROUTINES " +
            "WHERE ROUTINE_SCHEMA = ? AND ROUTINE_TYPE = 'PROCEDURE' " +
            "ORDER BY ROUTINE_NAME";

        try (PreparedStatement stmt = connection.prepareStatement(proceduresQuery)) {
            stmt.setString(1, database);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    DatabaseObject obj = new DatabaseObject();
                    obj.setName(rs.getString("ROUTINE_NAME"));
                    obj.setSchema(database);
                    obj.setType("procedure");
                    obj.setDefinition(rs.getString("ROUTINE_DEFINITION"));
                    objects.add(obj);
                }
            }
        }

        return objects;
    }

    private List<ColumnInfo> getMySQLColumns(Connection connection, String database, String tableName) throws SQLException {
        List<ColumnInfo> columns = new ArrayList<>();
        String query = "SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_KEY, COLUMN_DEFAULT " +
            "FROM INFORMATION_SCHEMA.COLUMNS " +
            "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? " +
            "ORDER BY ORDINAL_POSITION";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, database);
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ColumnInfo col = new ColumnInfo();
                    col.setName(rs.getString("COLUMN_NAME"));
                    col.setDataType(rs.getString("DATA_TYPE"));
                    col.setNullable("YES".equals(rs.getString("IS_NULLABLE")));
                    col.setPrimaryKey("PRI".equals(rs.getString("COLUMN_KEY")));
                    col.setDefaultValue(rs.getString("COLUMN_DEFAULT"));
                    columns.add(col);
                }
            }
        }

        return columns;
    }

    private List<DatabaseObject> getPostgreSQLObjects(Connection connection) throws SQLException {
        List<DatabaseObject> objects = new ArrayList<>();
        // Keep in sync with PostgresIntrospectionProvider non-system schema filter (W2a).
        String nonSystem = "NOT IN ('pg_catalog','information_schema','pg_toast') "
            + "AND %1$s NOT LIKE 'pg_temp_%%' AND %1$s NOT LIKE 'pg_toast_temp_%%'";

        // Get tables and views
        String tablesQuery = "SELECT t.schemaname as schema_name, t.tablename as name, 'table' as type, "
            + "(SELECT reltuples::bigint FROM pg_class c "
            + " JOIN pg_namespace n ON c.relnamespace = n.oid "
            + " WHERE n.nspname = t.schemaname AND c.relname = t.tablename) as row_count "
            + "FROM pg_tables t WHERE t.schemaname " + String.format(nonSystem, "t.schemaname") + " "
            + "UNION ALL "
            + "SELECT v.schemaname as schema_name, v.viewname as name, 'view' as type, 0 as row_count "
            + "FROM pg_views v WHERE v.schemaname " + String.format(nonSystem, "v.schemaname") + " "
            + "ORDER BY schema_name, type, name";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(tablesQuery)) {
            while (rs.next()) {
                DatabaseObject obj = new DatabaseObject();
                String schemaName = rs.getString("schema_name");
                obj.setName(rs.getString("name"));
                obj.setSchema(schemaName);
                obj.setType(rs.getString("type"));
                obj.setRowCount(rs.getLong("row_count"));
                obj.setColumns(getPostgreSQLColumns(connection, schemaName, obj.getName()));
                objects.add(obj);
            }
        }

        // Get functions
        String functionsQuery = "SELECT n.nspname as schema_name, p.proname as name, pg_get_functiondef(p.oid) as definition "
            + "FROM pg_proc p "
            + "JOIN pg_namespace n ON p.pronamespace = n.oid "
            + "WHERE n.nspname " + String.format(nonSystem, "n.nspname") + " AND p.prokind = 'f' "
            + "ORDER BY n.nspname, p.proname";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(functionsQuery)) {
            while (rs.next()) {
                DatabaseObject obj = new DatabaseObject();
                obj.setName(rs.getString("name"));
                obj.setSchema(rs.getString("schema_name"));
                obj.setType("function");
                obj.setDefinition(rs.getString("definition"));
                objects.add(obj);
            }
        }

        // Get procedures
        String proceduresQuery = "SELECT n.nspname as schema_name, p.proname as name, pg_get_functiondef(p.oid) as definition "
            + "FROM pg_proc p "
            + "JOIN pg_namespace n ON p.pronamespace = n.oid "
            + "WHERE n.nspname " + String.format(nonSystem, "n.nspname") + " AND p.prokind = 'p' "
            + "ORDER BY n.nspname, p.proname";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(proceduresQuery)) {
            while (rs.next()) {
                DatabaseObject obj = new DatabaseObject();
                obj.setName(rs.getString("name"));
                obj.setSchema(rs.getString("schema_name"));
                obj.setType("procedure");
                obj.setDefinition(rs.getString("definition"));
                objects.add(obj);
            }
        }

        return objects;
    }

    private List<ColumnInfo> getPostgreSQLColumns(Connection connection, String schemaName, String tableName) throws SQLException {
        List<ColumnInfo> columns = new ArrayList<>();
        String query = "SELECT c.column_name, c.data_type, c.is_nullable, c.column_default, "
            + "CASE WHEN pk.column_name IS NOT NULL THEN true ELSE false END as is_primary_key "
            + "FROM information_schema.columns c "
            + "LEFT JOIN ( "
            + "    SELECT ku.column_name "
            + "    FROM information_schema.table_constraints tc "
            + "    JOIN information_schema.key_column_usage ku ON tc.constraint_name = ku.constraint_name "
            + "      AND tc.table_schema = ku.table_schema "
            + "    WHERE tc.constraint_type = 'PRIMARY KEY' AND ku.table_schema = ? AND ku.table_name = ? "
            + ") pk ON c.column_name = pk.column_name "
            + "WHERE c.table_schema = ? AND c.table_name = ? "
            + "ORDER BY c.ordinal_position";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, schemaName);
            stmt.setString(2, tableName);
            stmt.setString(3, schemaName);
            stmt.setString(4, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ColumnInfo col = new ColumnInfo();
                    col.setName(rs.getString("column_name"));
                    col.setDataType(rs.getString("data_type"));
                    col.setNullable("YES".equals(rs.getString("is_nullable")));
                    col.setPrimaryKey(rs.getBoolean("is_primary_key"));
                    col.setDefaultValue(rs.getString("column_default"));
                    columns.add(col);
                }
            }
        }

        return columns;
    }

    public QueryResult executeQuery(String connectionId, QueryRequest queryRequest) throws SQLException {
        return executeQuery(connectionId, queryRequest, QueryExecutionContext.internal());
    }

    public QueryResult executeQuery(
        String connectionId,
        QueryRequest queryRequest,
        QueryExecutionContext executionContext
    ) throws SQLException {
        ConnectionRequest connRequest = credentialService.getDecryptedConnection(connectionId);
        QueryResult result = new QueryResult();
        result.setQuery(queryRequest.getQuery());

        long startTime = System.currentTimeMillis();

        // Determine whether # is a comment character (MySQL yes, PostgreSQL no)
        String canonicalDbType = "";
        try {
            canonicalDbType = providerRegistry.getCanonicalName(connRequest.getDbType());
        } catch (Exception ignored) {}
        boolean hashIsComment = !"postgres".equalsIgnoreCase(canonicalDbType);

        // Phase-2.5 telemetry: count every query attempt that passes argument
        // resolution. Tagging by canonicalDbType ensures unknown dialects roll
        // up under "unknown" rather than leaking raw vendor strings.
        telemetryCounters.incrementQuery(executionContext.origin(), canonicalDbType);

        // Split multi-statement SQL (handles SET @var preambles, USE, etc.)
        // All statements must run on the SAME connection so session variables persist.
        List<String> statements = com.dbaagent.util.SqlStatementSplitter.split(
                queryRequest.getQuery(), hashIsComment);
        if (statements.isEmpty()) {
            statements = List.of(queryRequest.getQuery());
        }

        QueryExecutionPolicyService.PolicyDecision policyDecision = queryExecutionPolicyService.enforce(
            queryRequest,
            executionContext,
            connRequest.getDbType()
        );
        userDataAccessPolicyService.enforcePreExecution(
            connectionId,
            queryRequest,
            executionContext
        );

        try (Connection connection = connectionService.getConnection(connectionId, connRequest)) {
            // Defense in depth: when the caller may not mutate, ask the database
            // to enforce that too. Classification is a parser heuristic, so a
            // read-only session is what keeps a future gap from becoming data
            // loss — PostgreSQL then refuses the write itself.
            boolean readOnlySession =
                executionContext.mutationMode() == QueryExecutionContext.MutationMode.READ_ONLY_ONLY;
            if (readOnlySession) {
                try {
                    connection.setReadOnly(true);
                } catch (SQLException e) {
                    // A driver that refuses the hint must not silently downgrade
                    // to a writable session.
                    throw new SQLException(
                        "Could not open a read-only database session for this connection: " + e.getMessage(), e);
                }
            }

            // Per-query timeout override: use the request's timeout if provided,
            // otherwise fall back to the server default (db.query-timeout-seconds).
            int maxAllowedTimeoutSeconds = 600; // 10 minutes ceiling
            int effectiveTimeout = (queryRequest.getTimeoutSeconds() != null && queryRequest.getTimeoutSeconds() > 0)
                ? Math.min(queryRequest.getTimeoutSeconds(), maxAllowedTimeoutSeconds)
                : queryTimeoutSeconds;

            // Publish the server-side session id so a client that abandons this
            // request can terminate the query rather than leaving it to run out
            // its timeout holding a pooled connection.
            String sessionPid = resolveSessionPid(connection, connRequest.getDbType());
            result.setSessionPid(sessionPid);
            runningQueryRegistry.register(
                queryRequest.getExecutionId(), connectionId, sessionPid, executionContext.actorUsername());

            // Execute all preamble statements (SET @var, USE db, etc.) on the same connection
            // so that session variables are in scope for the final SELECT.
            for (int si = 0; si < statements.size() - 1; si++) {
                String preamble = statements.get(si);
                if (preamble == null || preamble.isBlank()) continue;
                try (Statement preambleStmt = connection.createStatement()) {
                    if (effectiveTimeout > 0) preambleStmt.setQueryTimeout(effectiveTimeout);
                    preambleStmt.execute(preamble);
                }
            }

            // The last (or only) statement is the query whose results we return
            String finalQuery = statements.get(statements.size() - 1);

            try (Statement stmt = connection.createStatement()) {
            if (effectiveTimeout > 0) {
                stmt.setQueryTimeout(effectiveTimeout);
            }

            // Row cap. setMaxRows is the enforcement: the driver stops handing
            // back rows regardless of the query's shape, so a LIMIT inside a CTE,
            // a comment, or a string literal can no longer defeat the cap. We ask
            // for one extra row purely to detect truncation.
            //
            // Appending " LIMIT n" is kept as an optimization for simple SELECTs
            // (it lets the database stop early rather than stream rows we drop),
            // but correctness no longer depends on that textual check.
            boolean limitRequested = queryRequest.getLimit() != null && queryRequest.getLimit() > 0;
            int rowLimit = limitRequested ? queryRequest.getLimit() : 0;
            boolean limitApplied = false;
            String baseQuery = null; // original query without limit, for COUNT
            if (limitRequested) {
                stmt.setMaxRows(rowLimit + 1);

                String queryTrimmed = finalQuery.trim();
                String queryLower = queryTrimmed.toLowerCase();
                boolean isSelect = queryLower.startsWith("select");
                boolean alreadyHasLimit = queryLower.matches("(?s).*\\blimit\\s+\\d+.*");
                if (isSelect && !alreadyHasLimit) {
                    baseQuery = queryTrimmed;
                    if (baseQuery.endsWith(";")) {
                        baseQuery = baseQuery.substring(0, baseQuery.length() - 1);
                    }
                    // Ask for one more than we will return, matching setMaxRows,
                    // so an exactly-at-the-cap result is still detectable as
                    // truncated rather than looking complete.
                    finalQuery = baseQuery + " LIMIT " + (rowLimit + 1);
                    limitApplied = true;
                }
            }

            // Execute query
                boolean hasResultSet;
                try {
                    hasResultSet = stmt.execute(finalQuery);
                } catch (SQLException executionError) {
                    if (policyDecision.mutating() && looksLikeWritePrivilegeDenied(executionError)) {
                        throw QueryExecutionPolicyException.dbWritePrivilegeDenied(
                            executionError.getMessage(),
                            policyDecision.primaryQueryType()
                        );
                    }
                    throw executionError;
                }

                if (hasResultSet) {
                    try (ResultSet rs = stmt.getResultSet()) {
                        ResultSetMetaData metaData = rs.getMetaData();
                        int columnCount = metaData.getColumnCount();

                        // Get column names
                        List<String> columns = new ArrayList<>();
                        for (int i = 1; i <= columnCount; i++) {
                            columns.add(metaData.getColumnName(i));
                        }
                        result.setColumns(columns);

                        // Get rows. setMaxRows let one extra row through so we can
                        // tell "exactly at the cap" from "there were more"; it is
                        // counted but never returned.
                        List<List<Object>> rows = new ArrayList<>();
                        boolean truncated = false;
                        while (rs.next()) {
                            if (limitRequested && rows.size() >= rowLimit) {
                                truncated = true;
                                break;
                            }
                            List<Object> row = new ArrayList<>();
                            for (int i = 1; i <= columnCount; i++) {
                                Object value = rs.getObject(i);
                                row.add(value);
                            }
                            rows.add(row);
                        }
                        result.setRows(rows);
                        result.setRowCount(rows.size());
                        if (truncated) {
                            result.setIsLimited(true);
                        }

                        // Fetch the true total only when we appended the LIMIT
                        // ourselves, since that is the one case where baseQuery is
                        // a valid standalone statement to wrap in COUNT(*).
                        if (truncated && limitApplied && baseQuery != null) {
                            try (Statement countStmt = connection.createStatement()) {
                                countStmt.setQueryTimeout(effectiveTimeout > 0 ? effectiveTimeout : 60);
                                // Strip trailing ORDER BY before wrapping in COUNT subquery,
                                // as ORDER BY in a subquery is invalid in some SQL dialects.
                                String queryForCount = stripTrailingOrderBy(baseQuery);
                                String countQuery = "SELECT COUNT(*) FROM (" + queryForCount + ") AS _total_count_q";
                                try (ResultSet countRs = countStmt.executeQuery(countQuery)) {
                                    if (countRs.next()) {
                                        result.setTotalRowCount(countRs.getLong(1));
                                    }
                                }
                            } catch (Exception e) {
                                log.warn("Could not fetch total row count: {}", e.getMessage());
                                // Non-fatal: totalRowCount stays null, isLimited is still set
                            }
                        }
                    }
                } else {
                    int updateCount = stmt.getUpdateCount();
                    result.setRowCount(updateCount);
                    result.setColumns(Arrays.asList("affected_rows"));
                    result.setRows(Arrays.asList(Arrays.asList(updateCount)));
                }
            }
        } finally {
            runningQueryRegistry.unregister(queryRequest.getExecutionId());
        }

        result.setExecutionTimeMs(System.currentTimeMillis() - startTime);

        try {
            queryLineageService.recordLineage(connectionId, queryRequest.getQuery(), "SQL_RUNNER");
        } catch (Exception e) {
            log.debug("Failed to record SQL Runner lineage: {}", e.getMessage());
        }
        return userDataAccessPolicyService.redactResult(connectionId, result, executionContext);
    }

    /**
     * Strips a trailing ORDER BY clause from a SQL query so it can be safely wrapped
     * in a COUNT(*) subquery. ORDER BY in a subquery without LIMIT is invalid in some
     * SQL dialects (e.g. older MySQL, strict PostgreSQL). Uses a simple heuristic:
     * finds the last ORDER BY token that is not inside parentheses.
     */
    private String stripTrailingOrderBy(String sql) {
        // Walk backwards through tokens to find the last ORDER BY not inside parens
        int depth = 0;
        int lastOrderByPos = -1;
        String upper = sql.toUpperCase();
        for (int i = upper.length() - 1; i >= 0; i--) {
            char c = upper.charAt(i);
            if (c == ')') depth++;
            else if (c == '(') depth--;
            if (depth == 0 && i + 8 <= upper.length()) {
                // Check for "ORDER BY" at position i
                if (i + 8 <= upper.length() && upper.substring(i, Math.min(i + 8, upper.length())).equals("ORDER BY")) {
                    lastOrderByPos = i;
                    break;
                }
            }
        }
        if (lastOrderByPos > 0) {
            return sql.substring(0, lastOrderByPos).trim();
        }
        return sql;
    }

    private boolean looksLikeWritePrivilegeDenied(SQLException exception) {
        String sqlState = exception.getSQLState();
        String message = exception.getMessage();
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return "42501".equals(sqlState)
            || "42000".equals(sqlState)
            || lower.contains("permission denied")
            || lower.contains("access denied")
            || lower.contains("not authorized")
            || lower.contains("command denied")
            || lower.contains("insufficient privilege")
            || lower.contains("must be owner");
    }

    public List<TableIndex> getTableIndexes(String connectionId, String tableName) throws SQLException {
        ConnectionRequest connRequest = credentialService.getDecryptedConnection(connectionId);
        List<TableIndex> indexes = new ArrayList<>();

        try (Connection connection = connectionService.getConnection(connectionId, connRequest)) {
            IntrospectionProvider provider = providerRegistry.getDialect(connRequest.getDbType()).introspection();
            if (provider == null) {
                throw new IllegalArgumentException("IntrospectionProvider not available for: " + connRequest.getDbType());
            }
            indexes.addAll(provider.getTableIndexes(connection, connRequest.getDatabase(), tableName));
        }

        return indexes;
    }

    private List<TableIndex> getMySQLIndexes(Connection connection, String database, String tableName) throws SQLException {
        List<TableIndex> indexes = new ArrayList<>();
        Map<String, TableIndex> indexMap = new HashMap<>();

        String query = "SELECT INDEX_NAME, COLUMN_NAME, NON_UNIQUE, INDEX_TYPE, SEQ_IN_INDEX " +
                      "FROM INFORMATION_SCHEMA.STATISTICS " +
                      "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ? " +
                      "ORDER BY INDEX_NAME, SEQ_IN_INDEX";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, database);
            stmt.setString(2, tableName);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String indexName = rs.getString("INDEX_NAME");
                    String columnName = rs.getString("COLUMN_NAME");
                    boolean nonUnique = rs.getBoolean("NON_UNIQUE");
                    String indexType = rs.getString("INDEX_TYPE");

                    TableIndex index = indexMap.get(indexName);
                    if (index == null) {
                        index = new TableIndex();
                        index.setName(indexName);
                        index.setType(indexType);
                        index.setUnique(!nonUnique);
                        index.setPrimary("PRIMARY".equals(indexName));
                        index.setColumns(new ArrayList<>());
                        indexMap.put(indexName, index);
                    }
                    index.getColumns().add(columnName);
                }
            }
        }

        indexes.addAll(indexMap.values());
        return indexes;
    }

    private List<TableIndex> getPostgreSQLIndexes(Connection connection, String tableName) throws SQLException {
        List<TableIndex> indexes = new ArrayList<>();

        String query = "SELECT " +
                      "    i.relname AS index_name, " +
                      "    a.attname AS column_name, " +
                      "    ix.indisunique AS is_unique, " +
                      "    ix.indisprimary AS is_primary, " +
                      "    am.amname AS index_type " +
                      "FROM pg_class t " +
                      "JOIN pg_index ix ON t.oid = ix.indrelid " +
                      "JOIN pg_class i ON i.oid = ix.indexrelid " +
                      "JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(ix.indkey) " +
                      "JOIN pg_am am ON i.relam = am.oid " +
                      "WHERE t.relname = ? " +
                      "ORDER BY i.relname, a.attnum";

        Map<String, TableIndex> indexMap = new HashMap<>();

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, tableName);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String indexName = rs.getString("index_name");
                    String columnName = rs.getString("column_name");
                    boolean isUnique = rs.getBoolean("is_unique");
                    boolean isPrimary = rs.getBoolean("is_primary");
                    String indexType = rs.getString("index_type");

                    TableIndex index = indexMap.get(indexName);
                    if (index == null) {
                        index = new TableIndex();
                        index.setName(indexName);
                        index.setType(indexType);
                        index.setUnique(isUnique);
                        index.setPrimary(isPrimary);
                        index.setColumns(new ArrayList<>());
                        indexMap.put(indexName, index);
                    }
                    index.getColumns().add(columnName);
                }
            }
        }

        indexes.addAll(indexMap.values());
        return indexes;
    }

    public TableStats getTableStats(String connectionId, String tableName) throws SQLException {
        ConnectionRequest connRequest = credentialService.getDecryptedConnection(connectionId);
        try (Connection connection = connectionService.getConnection(connectionId, connRequest)) {
            IntrospectionProvider provider = providerRegistry.getDialect(connRequest.getDbType()).introspection();
            if (provider == null) {
                throw new IllegalArgumentException("IntrospectionProvider not available for: " + connRequest.getDbType());
            }
            return provider.getTableStats(connection, connRequest.getDatabase(), tableName);
        }
    }

    private TableStats getMySQLTableStats(Connection connection, String database, String tableName) throws SQLException {
        TableStats stats = new TableStats();
        stats.setTableName(tableName);

        String query = "SELECT " +
            "ENGINE, TABLE_COLLATION, TABLE_COMMENT, TABLE_ROWS, " +
            "DATA_LENGTH, INDEX_LENGTH, DATA_FREE " +
            "FROM INFORMATION_SCHEMA.TABLES " +
            "WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            // Set query timeout to 10 seconds
            stmt.setQueryTimeout(10);
            stmt.setString(1, database);
            stmt.setString(2, tableName);

            long dataFree = 0;
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    stats.setEngine(rs.getString("ENGINE"));
                    stats.setCollation(rs.getString("TABLE_COLLATION"));
                    stats.setComment(rs.getString("TABLE_COMMENT"));
                    stats.setRowCount(rs.getLong("TABLE_ROWS"));
                    stats.setDataSize(rs.getLong("DATA_LENGTH"));
                    stats.setIndexSize(rs.getLong("INDEX_LENGTH"));
                    stats.setSizeBytes(rs.getLong("DATA_LENGTH") + rs.getLong("INDEX_LENGTH"));
                    stats.setIndexSizeBytes(rs.getLong("INDEX_LENGTH"));
                    dataFree = rs.getLong("DATA_FREE");
                }
            }
            // Calculate bloat (allocated space vs actual data size)
            // This query can be slow on large databases, so it has its own timeout
            // Pass dataFree as fallback when PROCESS privilege is not available
            calculateTableBloat(connection, database, tableName, stats, dataFree);
        }

        return stats;
    }

    private void calculateTableBloat(Connection connection, String database, String tableName, TableStats stats, long dataFree) {
        String bloatQuery = "SELECT " +
            "CAST(t.data_length AS DECIMAL(30,0)) + CAST(t.index_length AS DECIMAL(30,0)) AS logical_bytes, " +
            "SUM(CAST(f.total_extents AS DECIMAL(30,0)) * CAST(f.extent_size AS DECIMAL(30,0))) AS allocated_bytes, " +
            "GREATEST( " +
            "  SUM(CAST(f.total_extents AS DECIMAL(30,0)) * CAST(f.extent_size AS DECIMAL(30,0))) " +
            "  - (CAST(t.data_length AS DECIMAL(30,0)) + CAST(t.index_length AS DECIMAL(30,0))), " +
            "  0 " +
            ") AS bloat_bytes, " +
            "ROUND( " +
            "  100 * GREATEST( " +
            "    SUM(CAST(f.total_extents AS DECIMAL(30,0)) * CAST(f.extent_size AS DECIMAL(30,0))) " +
            "    - (CAST(t.data_length AS DECIMAL(30,0)) + CAST(t.index_length AS DECIMAL(30,0))), " +
            "    0 " +
            "  ) " +
            "  / NULLIF(SUM(CAST(f.total_extents AS DECIMAL(30,0)) * CAST(f.extent_size AS DECIMAL(30,0))), 0), " +
            "  2 " +
            ") AS bloat_pct " +
            "FROM information_schema.tables t " +
            "JOIN information_schema.files f " +
            "  ON f.file_type = 'TABLESPACE' " +
            " AND ( " +
            "      LOWER(f.tablespace_name) = LOWER(CONCAT(t.table_schema, '/', t.table_name)) " +
            "      OR LOWER(f.tablespace_name) LIKE LOWER(CONCAT(t.table_schema, '/', t.table_name, '#P#%')) " +
            "     ) " +
            "WHERE t.table_schema = ? " +
            "  AND t.table_name = ? " +
            "  AND t.table_type = 'BASE TABLE' " +
            "GROUP BY t.table_schema, t.table_name, t.data_length, t.index_length";

        try (PreparedStatement stmt = connection.prepareStatement(bloatQuery)) {
            // Set query timeout to 5 seconds to prevent hanging on large databases
            stmt.setQueryTimeout(5);
            stmt.setString(1, database);
            stmt.setString(2, tableName);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    stats.setAllocatedBytes(rs.getLong("allocated_bytes"));
                    stats.setBloatBytes(rs.getLong("bloat_bytes"));
                    stats.setBloatPercent(rs.getDouble("bloat_pct"));
                }
            }
        } catch (SQLException e) {
            // Bloat calculation may fail due to missing PROCESS privilege, table types, storage engines, or timeout
            // Use DATA_FREE as a fallback approximation (free space within InnoDB tablespace)
            if (dataFree > 0 && stats.getSizeBytes() != null && stats.getSizeBytes() > 0) {
                long logicalSize = stats.getSizeBytes();
                long allocatedSize = logicalSize + dataFree;
                stats.setAllocatedBytes(allocatedSize);
                stats.setBloatBytes(dataFree);
                double bloatPct = (dataFree * 100.0) / allocatedSize;
                stats.setBloatPercent(Math.round(bloatPct * 100.0) / 100.0);
                log.debug("Using DATA_FREE fallback for bloat calculation on {}.{}: {} bytes", database, tableName, dataFree);
            } else {
                log.debug("Could not calculate bloat for table {}.{}: {}", database, tableName, e.getMessage());
            }
        }
    }

    private TableStats getPostgreSQLTableStats(Connection connection, String tableName) throws SQLException {
        TableStats stats = new TableStats();
        stats.setTableName(tableName);

        String query = "SELECT " +
            "pg_size_pretty(pg_total_relation_size(?::regclass)) as total_size, " +
            "pg_total_relation_size(?::regclass) as total_bytes, " +
            "pg_size_pretty(pg_relation_size(?::regclass)) as data_size, " +
            "pg_relation_size(?::regclass) as data_bytes, " +
            "pg_size_pretty(pg_total_relation_size(?::regclass) - pg_relation_size(?::regclass)) as index_size, " +
            "(pg_total_relation_size(?::regclass) - pg_relation_size(?::regclass)) as index_bytes, " +
            "(SELECT reltuples::bigint FROM pg_class WHERE relname = ?) as row_count, " +
            "obj_description(?::regclass, 'pg_class') as comment";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            // Set all the table name parameters
            for (int i = 1; i <= 10; i++) {
                stmt.setString(i, tableName);
            }

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    stats.setEngine("PostgreSQL"); // PostgreSQL doesn't have storage engines like MySQL
                    stats.setCollation(null); // Can be added if needed
                    stats.setComment(rs.getString("comment"));
                    stats.setRowCount(rs.getLong("row_count"));
                    stats.setDataSize(rs.getLong("data_bytes"));
                    stats.setIndexSize(rs.getLong("index_bytes"));
                    stats.setSizeBytes(rs.getLong("total_bytes"));
                    stats.setIndexSizeBytes(rs.getLong("index_bytes"));
                }
            }
        }

        return stats;
    }
}
