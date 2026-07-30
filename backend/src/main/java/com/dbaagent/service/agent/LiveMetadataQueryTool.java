package com.dbaagent.service.agent;

import com.dbaagent.model.QueryResult;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.TableMetadata;
import com.dbaagent.service.QueryExecutorService;
import com.dbaagent.service.SchemaQuestionUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Agent tool that queries live database metadata catalogs (performance_schema,
 * information_schema, pg_catalog, pg_stat_*) when vault DB cached metadata
 * is insufficient to answer the user's question.
 *
 * Only runs when the vault_metadata_lookup_tool reports insufficient data.
 */
@Service
@Slf4j
public class LiveMetadataQueryTool extends AbstractSqlAgentTool {

    public LiveMetadataQueryTool(QueryExecutorService queryExecutorService) {
        super(queryExecutorService);
    }

    @Override
    public String name() {
        return "live_metadata_query_tool";
    }

    @Override
    public AgentToolResult execute(AgentPlanStep step, AgentExecutionContext context) {
        VerifiedAnswer verifiedMetadataAnswer = context.getMemory("metadataVerifiedAnswer");
        Boolean metadataNeedsLiveFallback = context.getMemory("metadataNeedsLiveFallback");
        Boolean vaultSufficient = context.getMemory("vaultDataSufficient");
        if (verifiedMetadataAnswer != null || Boolean.FALSE.equals(metadataNeedsLiveFallback) || Boolean.TRUE.equals(vaultSufficient)) {
            return new AgentToolResult(
                new AgentObservation(
                    "live_metadata_skipped",
                    "Skipped — verified cached metadata already satisfies the request",
                    Map.of(
                        "skipped", true,
                        "reason", verifiedMetadataAnswer != null ? "verified_cached_answer" : "cached_metadata_sufficient"
                    )
                ),
                null,
                null,
                1.0
            );
        }

        String brainTopic = (String) step.params().getOrDefault("brainTopic", "GENERAL");
        String dbType = context.dbType() != null ? context.dbType().toLowerCase(Locale.ROOT) : "";
        @SuppressWarnings("unchecked")
        List<String> mentionedTables = (List<String>) step.params().getOrDefault("mentionedTables", List.of());
        Map<String, Object> exactAnswerMetadata = resolveExactSchemaAnswerMetadata(brainTopic, context);

        String sql = buildMetadataQuery(brainTopic, dbType, mentionedTables, context);
        if (sql == null || sql.isBlank()) {
            return new AgentToolResult(
                new AgentObservation(
                    "live_metadata_unsupported",
                    "No live metadata query available for topic: " + brainTopic + " on " + dbType,
                    Map.of("supported", false)
                ),
                null,
                null,
                0.2
            );
        }

        try {
            QueryResult result = executeQuery(context.connectionId(), sql, 50);
            int rowCount = result != null && result.getRows() != null ? result.getRows().size() : 0;
            context.putMemory("liveMetadataResult", result);
            context.putMemory("liveMetadataSql", sql);
            if (!exactAnswerMetadata.isEmpty()) {
                context.putMemory("liveMetadataAnswerType", exactAnswerMetadata.get("answerType"));
                context.putMemory("liveMetadataTableName", exactAnswerMetadata.get("tableName"));
            }

            Map<String, Object> observationData = new java.util.LinkedHashMap<>();
            observationData.put("rowCount", rowCount);
            observationData.put("dbType", dbType);
            observationData.put("topic", brainTopic);
            observationData.putAll(exactAnswerMetadata);

            return new AgentToolResult(
                new AgentObservation(
                    "live_metadata_result",
                    "Queried live " + dbType + " metadata: " + rowCount + " rows returned",
                    observationData
                ),
                result,
                sql,
                rowCount > 0 ? 0.85 : 0.3
            );
        } catch (Exception e) {
            log.warn("Live metadata query failed for {}/{}: {}", dbType, brainTopic, e.getMessage());
            return new AgentToolResult(
                new AgentObservation(
                    "live_metadata_error",
                    "Live metadata query failed: " + e.getMessage(),
                    Map.of("error", e.getMessage())
                ),
                null,
                sql,
                0.1
            );
        }
    }

    private String buildMetadataQuery(
            String brainTopic,
            String dbType,
            List<String> mentionedTables,
            AgentExecutionContext context) {
        MetadataRequestScope requestScope = context.getMemory("metadataRequestScope");
        return switch (brainTopic.toUpperCase(Locale.ROOT)) {
            case "RELATIONSHIPS" -> buildRelationshipQuery(dbType, mentionedTables, requestScope);
            case "KEY_COLUMNS" -> buildKeyColumnQuery(dbType, resolveExactKeyColumnTables(mentionedTables, context));
            case "PERFORMANCE" -> buildPerformanceQuery(dbType, mentionedTables);
            case "GROWTH" -> buildGrowthQuery(dbType, mentionedTables);
            case "CLASSIFICATION" -> buildClassificationQuery(dbType, mentionedTables);
            case "SCHEMA", "GENERAL" -> buildSchemaQuery(dbType, mentionedTables, context);
            default -> null;
        };
    }

    private Map<String, Object> resolveExactSchemaAnswerMetadata(String brainTopic, AgentExecutionContext context) {
        if (context == null || context.question() == null) {
            return Map.of();
        }
        String upperTopic = brainTopic == null ? "" : brainTopic.toUpperCase(Locale.ROOT);
        MetadataRequestScope requestScope = context.getMemory("metadataRequestScope");
        if (requestScope != null && requestScope.pairScoped() && requestScope.requestedTables().size() >= 2) {
            return Map.of(
                "answerType", requestScope.factType() == MetadataRequestScope.FactType.JOIN_COLUMNS ? "pair_join_columns" : "pair_relationships",
                "tableName", String.join("::", requestScope.requestedTables())
            );
        }
        if ("KEY_COLUMNS".equals(upperTopic) && SchemaQuestionUtil.looksLikeExactTableKeyColumnQuestion(context.question())) {
            TableMetadata keyTable = resolveExactSchemaTable(context.schema(), context.question());
            if (keyTable != null) {
                return Map.of(
                    "answerType", "table_key_columns",
                    "tableName", keyTable.getName()
                );
            }
        }
        if (!"SCHEMA".equals(upperTopic) && !"GENERAL".equals(upperTopic)) {
            return Map.of();
        }

        TableMetadata table = resolveExactSchemaTable(context.schema(), context.question());
        if (table == null) {
            return Map.of();
        }

        if (SchemaQuestionUtil.looksLikeExactTableColumnQuestion(context.question())) {
            return Map.of(
                "answerType", "table_columns",
                "tableName", table.getName()
            );
        }
        if (SchemaQuestionUtil.looksLikeExactTableRowCountQuestion(context.question())) {
            return Map.of(
                "answerType", "table_row_count",
                "tableName", table.getName()
            );
        }
        if (SchemaQuestionUtil.looksLikeExactTableIndexQuestion(context.question())) {
            return Map.of(
                "answerType", "table_indexes",
                "tableName", table.getName()
            );
        }
        return Map.of();
    }

    private String buildSchemaQuery(String dbType, List<String> mentionedTables, AgentExecutionContext context) {
        String question = context != null ? context.question() : null;
        if (SchemaQuestionUtil.looksLikeExactTableColumnListQuestion(question)
            || SchemaQuestionUtil.looksLikeExactTableColumnCountQuestion(question)) {
            return buildSchemaColumnsQuery(dbType, mentionedTables, context);
        }
        if (SchemaQuestionUtil.looksLikeExactTableRowCountQuestion(question)) {
            return buildSchemaRowCountQuery(dbType, context);
        }
        if (SchemaQuestionUtil.looksLikeExactTableIndexQuestion(question)) {
            return buildSchemaIndexesQuery(dbType, context);
        }
        return buildClassificationQuery(dbType, mentionedTables);
    }

    private String buildSchemaColumnsQuery(String dbType, List<String> mentionedTables, AgentExecutionContext context) {
        TableMetadata table = resolveExactSchemaTable(context.schema(), context.question());
        if (table == null) {
            return null;
        }

        String schemaName = table.getSchema();
        String tableName = table.getName();

        if (dbType.contains("mysql")) {
            String schemaFilter = schemaName == null || schemaName.isBlank()
                ? "TABLE_SCHEMA = DATABASE()"
                : "TABLE_SCHEMA = " + quote(schemaName);
            return """
                SELECT
                    COLUMN_NAME,
                    DATA_TYPE,
                    IS_NULLABLE,
                    COLUMN_KEY,
                    COLUMN_DEFAULT,
                    ORDINAL_POSITION
                FROM information_schema.COLUMNS
                WHERE """
                + schemaFilter +
                """
                    AND TABLE_NAME = """
                + quote(tableName) +
                """
                ORDER BY ORDINAL_POSITION
                LIMIT 200
                """;
        }

        if (dbType.contains("postgres")) {
            String effectiveSchema = schemaName == null || schemaName.isBlank() ? "public" : schemaName;
            return """
                SELECT
                    column_name,
                    data_type,
                    is_nullable,
                    column_default,
                    ordinal_position
                FROM information_schema.columns
                WHERE table_schema = """
                + quote(effectiveSchema) +
                """
                    AND table_name = """
                + quote(tableName) +
                """
                ORDER BY ordinal_position
                LIMIT 200
                """;
        }

        return null;
    }

    private String buildSchemaRowCountQuery(String dbType, AgentExecutionContext context) {
        TableMetadata table = resolveExactSchemaTable(context.schema(), context.question());
        if (table == null) {
            return null;
        }

        String schemaName = table.getSchema();
        String tableName = table.getName();

        if (dbType.contains("mysql")) {
            String schemaFilter = schemaName == null || schemaName.isBlank()
                ? "TABLE_SCHEMA = DATABASE()"
                : "TABLE_SCHEMA = " + quote(schemaName);
            return """
                SELECT
                    TABLE_NAME,
                    TABLE_ROWS AS row_count
                FROM information_schema.TABLES
                WHERE """
                + schemaFilter +
                """
                    AND TABLE_NAME = """
                + quote(tableName) +
                """
                LIMIT 1
                """;
        }

        if (dbType.contains("postgres")) {
            String effectiveSchema = schemaName == null || schemaName.isBlank() ? "public" : schemaName;
            return """
                SELECT
                    c.relname AS table_name,
                    COALESCE(s.n_live_tup::bigint, c.reltuples::bigint) AS row_count
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                LEFT JOIN pg_stat_user_tables s ON s.relid = c.oid
                WHERE n.nspname = """
                + quote(effectiveSchema) +
                """
                    AND c.relname = """
                + quote(tableName) +
                """
                    AND c.relkind = 'r'
                LIMIT 1
                """;
        }

        return null;
    }

    private String buildSchemaIndexesQuery(String dbType, AgentExecutionContext context) {
        TableMetadata table = resolveExactSchemaTable(context.schema(), context.question());
        if (table == null) {
            return null;
        }

        String schemaName = table.getSchema();
        String tableName = table.getName();

        if (dbType.contains("mysql")) {
            String schemaFilter = schemaName == null || schemaName.isBlank()
                ? "TABLE_SCHEMA = DATABASE()"
                : "TABLE_SCHEMA = " + quote(schemaName);
            return """
                SELECT
                    INDEX_NAME,
                    GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX SEPARATOR ', ') AS columns,
                    CASE WHEN NON_UNIQUE = 0 THEN 'YES' ELSE 'NO' END AS is_unique,
                    INDEX_TYPE
                FROM information_schema.STATISTICS
                WHERE """
                + schemaFilter +
                """
                    AND TABLE_NAME = """
                + quote(tableName) +
                """
                GROUP BY INDEX_NAME, NON_UNIQUE, INDEX_TYPE
                ORDER BY INDEX_NAME
                LIMIT 100
                """;
        }

        if (dbType.contains("postgres")) {
            String effectiveSchema = schemaName == null || schemaName.isBlank() ? "public" : schemaName;
            return """
                SELECT
                    i.relname AS index_name,
                    string_agg(a.attname, ', ' ORDER BY a.attnum) AS columns,
                    CASE WHEN ix.indisunique THEN 'YES' ELSE 'NO' END AS is_unique,
                    am.amname AS index_type
                FROM pg_index ix
                JOIN pg_class t ON t.oid = ix.indrelid
                JOIN pg_class i ON i.oid = ix.indexrelid
                JOIN pg_am am ON am.oid = i.relam
                JOIN pg_namespace n ON n.oid = t.relnamespace
                JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(ix.indkey)
                WHERE n.nspname = """
                + quote(effectiveSchema) +
                """
                    AND t.relname = """
                + quote(tableName) +
                """
                GROUP BY i.relname, ix.indisunique, am.amname
                ORDER BY i.relname
                LIMIT 100
                """;
        }

        return null;
    }

    private List<String> resolveExactKeyColumnTables(List<String> mentionedTables, AgentExecutionContext context) {
        if (context == null || !SchemaQuestionUtil.looksLikeExactTableKeyColumnQuestion(context.question())) {
            return mentionedTables;
        }
        TableMetadata exactTable = resolveExactSchemaTable(context.schema(), context.question());
        return exactTable != null ? List.of(exactTable.getName()) : mentionedTables;
    }

    private String buildRelationshipQuery(String dbType, List<String> mentionedTables, MetadataRequestScope requestScope) {
        if (requestScope != null && requestScope.pairScoped() && requestScope.requestedTables().size() >= 2) {
            String firstTable = requestScope.requestedTables().getFirst();
            String secondTable = requestScope.requestedTables().get(1);
            if (dbType.contains("mysql")) {
                return "SELECT "
                    + "TABLE_NAME AS source_table, "
                    + "COLUMN_NAME AS source_column, "
                    + "REFERENCED_TABLE_NAME AS target_table, "
                    + "REFERENCED_COLUMN_NAME AS target_column, "
                    + "CONSTRAINT_NAME "
                    + "FROM information_schema.KEY_COLUMN_USAGE "
                    + "WHERE TABLE_SCHEMA = DATABASE() "
                    + "AND REFERENCED_TABLE_NAME IS NOT NULL "
                    + "AND ("
                    + "(TABLE_NAME = " + quote(firstTable) + " AND REFERENCED_TABLE_NAME = " + quote(secondTable) + ") "
                    + "OR "
                    + "(TABLE_NAME = " + quote(secondTable) + " AND REFERENCED_TABLE_NAME = " + quote(firstTable) + ")"
                    + ") "
                    + "ORDER BY TABLE_NAME, COLUMN_NAME "
                    + "LIMIT 50";
            }

            if (dbType.contains("postgres")) {
                return "SELECT "
                    + "kcu.table_name AS source_table, "
                    + "kcu.column_name AS source_column, "
                    + "ccu.table_name AS target_table, "
                    + "ccu.column_name AS target_column, "
                    + "tc.constraint_name "
                    + "FROM information_schema.table_constraints tc "
                    + "JOIN information_schema.key_column_usage kcu "
                    + "ON tc.constraint_name = kcu.constraint_name "
                    + "AND tc.table_schema = kcu.table_schema "
                    + "JOIN information_schema.constraint_column_usage ccu "
                    + "ON ccu.constraint_name = tc.constraint_name "
                    + "AND ccu.table_schema = tc.table_schema "
                    + "WHERE tc.constraint_type = 'FOREIGN KEY' "
                    + "AND ("
                    + "(kcu.table_name = " + quote(firstTable) + " AND ccu.table_name = " + quote(secondTable) + ") "
                    + "OR "
                    + "(kcu.table_name = " + quote(secondTable) + " AND ccu.table_name = " + quote(firstTable) + ")"
                    + ") "
                    + "ORDER BY kcu.table_name, kcu.column_name "
                    + "LIMIT 50";
            }
        }

        if (dbType.contains("mysql")) {
            String tableFilter = buildMysqlTableFilter(mentionedTables, "esd.DIGEST_TEXT");
            return """
                SELECT
                    LOWER(TRIM(SUBSTRING_INDEX(
                        SUBSTRING_INDEX(
                            REPLACE(REPLACE(REPLACE(LOWER(esd.DIGEST_TEXT), '`', ''), '\\n', ' '), '  ', ' '),
                            ' join ', -1),
                        ' ', 1)
                    )) AS joined_table,
                    SUM(esd.COUNT_STAR) AS join_count,
                    COUNT(DISTINCT esd.DIGEST) AS distinct_queries
                FROM performance_schema.events_statements_summary_by_digest esd
                WHERE esd.DIGEST_TEXT IS NOT NULL
                    AND LOWER(esd.DIGEST_TEXT) LIKE '%% join %%'
                """ + tableFilter + """
                GROUP BY joined_table
                ORDER BY join_count DESC
                LIMIT 20
                """;
        }

        if (dbType.contains("postgres")) {
            String tableFilter = buildPgTableFilter(mentionedTables);
            return """
                SELECT
                    schemaname,
                    relname AS table_name,
                    seq_scan,
                    idx_scan,
                    n_tup_ins,
                    n_tup_upd,
                    n_tup_del,
                    n_live_tup AS row_count
                FROM pg_stat_user_tables
                """ + tableFilter + """
                ORDER BY seq_scan + idx_scan DESC
                LIMIT 20
                """;
        }

        return null;
    }

    private String buildKeyColumnQuery(String dbType, List<String> mentionedTables) {
        if (dbType.contains("mysql")) {
            String tableFilter = mentionedTables.isEmpty() ? "" :
                "AND ist.TABLE_NAME IN (" + quotedList(mentionedTables) + ")";
            return """
                SELECT
                    ist.TABLE_NAME,
                    ist.COLUMN_NAME,
                    ist.INDEX_NAME,
                    ist.SEQ_IN_INDEX,
                    ist.NON_UNIQUE,
                    ist.CARDINALITY
                FROM information_schema.STATISTICS ist
                WHERE ist.TABLE_SCHEMA = DATABASE()
                """ + tableFilter + """
                ORDER BY ist.TABLE_NAME, ist.INDEX_NAME, ist.SEQ_IN_INDEX
                LIMIT 50
                """;
        }

        if (dbType.contains("postgres")) {
            String tableFilter = mentionedTables.isEmpty() ? "" :
                "AND t.relname IN (" + quotedList(mentionedTables) + ")";
            return """
                SELECT
                    t.relname AS table_name,
                    a.attname AS column_name,
                    i.relname AS index_name,
                    ix.indisunique AS is_unique,
                    ix.indisprimary AS is_primary
                FROM pg_index ix
                JOIN pg_class t ON t.oid = ix.indrelid
                JOIN pg_class i ON i.oid = ix.indexrelid
                JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(ix.indkey)
                JOIN pg_namespace n ON n.oid = t.relnamespace
                WHERE n.nspname = 'public'
                """ + tableFilter + """
                ORDER BY t.relname, i.relname
                LIMIT 50
                """;
        }

        return null;
    }

    private String buildPerformanceQuery(String dbType, List<String> mentionedTables) {
        if (dbType.contains("mysql")) {
            return """
                SELECT
                    DIGEST_TEXT,
                    COUNT_STAR AS exec_count,
                    ROUND(SUM_TIMER_WAIT / 1000000000000, 3) AS total_sec,
                    ROUND(AVG_TIMER_WAIT / 1000000000000, 3) AS avg_sec,
                    SUM_ROWS_EXAMINED AS rows_examined,
                    SUM_ROWS_SENT AS rows_sent
                FROM performance_schema.events_statements_summary_by_digest
                WHERE DIGEST_TEXT IS NOT NULL
                ORDER BY SUM_TIMER_WAIT DESC
                LIMIT 10
                """;
        }

        if (dbType.contains("postgres")) {
            return """
                SELECT
                    query,
                    calls,
                    ROUND(total_exec_time::numeric / 1000, 3) AS total_sec,
                    ROUND(mean_exec_time::numeric / 1000, 3) AS avg_sec,
                    rows
                FROM pg_stat_statements
                ORDER BY total_exec_time DESC
                LIMIT 10
                """;
        }

        return null;
    }

    private String buildGrowthQuery(String dbType, List<String> mentionedTables) {
        if (dbType.contains("mysql")) {
            String tableFilter = mentionedTables.isEmpty() ? "" :
                "AND TABLE_NAME IN (" + quotedList(mentionedTables) + ")";
            return """
                SELECT
                    TABLE_NAME,
                    TABLE_ROWS,
                    ROUND(DATA_LENGTH / 1024 / 1024, 2) AS data_mb,
                    ROUND(INDEX_LENGTH / 1024 / 1024, 2) AS index_mb,
                    ROUND((DATA_LENGTH + INDEX_LENGTH) / 1024 / 1024, 2) AS total_mb
                FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE()
                    AND TABLE_TYPE = 'BASE TABLE'
                """ + tableFilter + """
                ORDER BY DATA_LENGTH + INDEX_LENGTH DESC
                LIMIT 20
                """;
        }

        if (dbType.contains("postgres")) {
            String tableFilter = mentionedTables.isEmpty() ? "" :
                "AND relname IN (" + quotedList(mentionedTables) + ")";
            return """
                SELECT
                    relname AS table_name,
                    n_live_tup AS row_count,
                    pg_size_pretty(pg_total_relation_size(c.oid)) AS total_size,
                    pg_total_relation_size(c.oid) AS total_bytes
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public'
                    AND c.relkind = 'r'
                """ + tableFilter + """
                ORDER BY pg_total_relation_size(c.oid) DESC
                LIMIT 20
                """;
        }

        return null;
    }

    private String buildClassificationQuery(String dbType, List<String> mentionedTables) {
        if (dbType.contains("mysql")) {
            String tableFilter = mentionedTables.isEmpty() ? "" :
                "AND t.TABLE_NAME IN (" + quotedList(mentionedTables) + ")";
            return """
                SELECT
                    t.TABLE_NAME,
                    t.TABLE_ROWS,
                    t.ENGINE,
                    ROUND((t.DATA_LENGTH + t.INDEX_LENGTH) / 1024 / 1024, 2) AS size_mb,
                    (SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE kcu
                     WHERE kcu.TABLE_SCHEMA = t.TABLE_SCHEMA AND kcu.TABLE_NAME = t.TABLE_NAME
                       AND kcu.REFERENCED_TABLE_NAME IS NOT NULL) AS fk_count,
                    (SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE kcu
                     WHERE kcu.TABLE_SCHEMA = t.TABLE_SCHEMA AND kcu.REFERENCED_TABLE_NAME = t.TABLE_NAME) AS inbound_fk_count
                FROM information_schema.TABLES t
                WHERE t.TABLE_SCHEMA = DATABASE()
                    AND t.TABLE_TYPE = 'BASE TABLE'
                """ + tableFilter + """
                ORDER BY t.TABLE_ROWS DESC
                LIMIT 30
                """;
        }

        if (dbType.contains("postgres")) {
            String tableFilter = mentionedTables.isEmpty() ? "" :
                "AND c.relname IN (" + quotedList(mentionedTables) + ")";
            return """
                SELECT
                    c.relname AS table_name,
                    c.reltuples::bigint AS row_count,
                    pg_size_pretty(pg_total_relation_size(c.oid)) AS total_size,
                    (SELECT COUNT(*) FROM pg_constraint
                     WHERE conrelid = c.oid AND contype = 'f') AS fk_count,
                    (SELECT COUNT(*) FROM pg_constraint
                     WHERE confrelid = c.oid AND contype = 'f') AS inbound_fk_count
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname = 'public'
                    AND c.relkind = 'r'
                """ + tableFilter + """
                ORDER BY c.reltuples DESC
                LIMIT 30
                """;
        }

        return null;
    }

    private String buildMysqlTableFilter(List<String> mentionedTables, String column) {
        if (mentionedTables.isEmpty()) {
            return "";
        }
        StringBuilder filter = new StringBuilder();
        for (String table : mentionedTables) {
            filter.append("AND LOWER(").append(column).append(") LIKE ")
                .append(quote("%" + table.toLowerCase(Locale.ROOT) + "%")).append("\n");
        }
        return filter.toString();
    }

    private String buildPgTableFilter(List<String> mentionedTables) {
        if (mentionedTables.isEmpty()) {
            return "";
        }
        return "WHERE relname IN (" + quotedList(mentionedTables) + ")";
    }

    private String quotedList(List<String> values) {
        return values.stream()
            .map(this::quote)
            .reduce((a, b) -> a + ", " + b)
            .orElse("");
    }

    private TableMetadata resolveExactSchemaTable(SchemaMetadata schema, String question) {
        return SchemaQuestionUtil.resolveExactSchemaTable(schema, question);
    }
}
