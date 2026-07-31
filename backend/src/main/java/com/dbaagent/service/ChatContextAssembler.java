package com.dbaagent.service;

import com.dbaagent.model.DocumentationSource;
import com.dbaagent.model.SchemaDocumentation;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.SchemaClassification;
import com.dbaagent.model.TableClassification;
import com.dbaagent.model.TableMetadata;
import com.dbaagent.model.SlowQueryHistory;
import com.dbaagent.model.SlowQueryAnalysis;
import com.dbaagent.model.SlowQuery;
import com.dbaagent.model.QueryPerformanceRegression;
import com.dbaagent.model.KeyColumnAnalysis;
import com.dbaagent.model.InferredTableRelationship;
import com.dbaagent.model.GrowthAnomaly;
import com.dbaagent.model.IndexRecommendationEntity;
import com.dbaagent.model.TableStatsHistory;
import com.dbaagent.model.ColumnValueCache;
import com.dbaagent.model.brain.WorkloadProfile;
import com.dbaagent.model.brain.KnobRanking;
import com.dbaagent.model.brain.ColumnStatistics;
import com.dbaagent.model.brain.PlanPattern;
import com.dbaagent.repository.TableClassificationRepository;
import com.dbaagent.repository.brain.WorkloadProfileRepository;
import com.dbaagent.repository.brain.KnobRankingRepository;
import com.dbaagent.repository.brain.ColumnStatisticsRepository;
import com.dbaagent.repository.brain.PlanPatternRepository;
import com.dbaagent.repository.TableStatsHistoryRepository;
import com.dbaagent.repository.SlowQueryHistoryRepository;
import com.dbaagent.repository.QueryPerformanceRegressionRepository;
import com.dbaagent.repository.KeyColumnAnalysisRepository;
import com.dbaagent.repository.InferredTableRelationshipRepository;
import com.dbaagent.repository.GrowthAnomalyRepository;
import com.dbaagent.repository.IndexRecommendationRepository;
import com.dbaagent.repository.ColumnValueCacheRepository;
import com.dbaagent.repository.SchemaDocumentationRepository;
import com.dbaagent.service.brain.classification.SchemaClassificationService;
import com.dbaagent.util.TokenEstimator;
import com.dbaagent.service.SchemaObjectNameUtil;
import com.dbaagent.service.SchemaTableMatchUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Assembles various context sections for the chat system prompt.
 * Extracted from ChatService to separate context-building concerns.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatContextAssembler {

    /**
     * Types of context that can be loaded for a chat request.
     * Used for lazy loading - only load what's needed based on question type.
     */
    public enum ContextType {
        CLASSIFICATION,      // Schema classification (table roles, domains)
        SEMANTIC_MODEL,      // Vault-backed semantic model (grain, preferred joins, filter semantics)
        SLOW_QUERIES,        // Slow query history
        REGRESSIONS,         // Performance regressions
        INDEX_RECOMMENDATIONS, // Index suggestions
        KEY_COLUMNS,         // Key column analysis
        RELATIONSHIPS,       // Inferred table relationships
        GROWTH,              // Growth anomalies and table sizes
        BRAIN_INSIGHTS,      // Brain ML insights (workload, knobs, column statistics, plan patterns)
    }

    private final SchemaClassificationService schemaClassificationService;
    private final CredentialService credentialService;
    private final TableClassificationRepository tableClassificationRepository;
    private final SlowQueryHistoryRepository slowQueryHistoryRepository;
    private final QueryPerformanceRegressionRepository queryPerformanceRegressionRepository;
    private final KeyColumnAnalysisRepository keyColumnAnalysisRepository;
    private final InferredTableRelationshipRepository inferredTableRelationshipRepository;
    private final GrowthAnomalyRepository growthAnomalyRepository;
    private final IndexRecommendationRepository indexRecommendationRepository;
    private final TableStatsHistoryRepository tableStatsHistoryRepository;
    private final ColumnValueCacheRepository columnValueCacheRepository;
    private final WorkloadProfileRepository workloadProfileRepository;
    private final KnobRankingRepository knobRankingRepository;
    private final ColumnStatisticsRepository columnStatisticsRepository;
    private final PlanPatternRepository planPatternRepository;
    private final ObjectMapper objectMapper;
    private final SchemaDocumentationRepository schemaDocumentationRepository;
    private final SemanticModelService semanticModelService;

    @Value("${app.chat.schema-context.full-max-tables:100}")
    private int schemaContextFullMaxTables;

    @Value("${app.chat.schema-context.preview-max-tables:100}")
    private int schemaContextPreviewMaxTables;

    @Value("${app.chat.schema-context.preview-max-columns-per-table:8}")
    private int schemaContextPreviewMaxColumnsPerTable;

    @Value("${app.chat.max-system-prompt-tokens:64000}")
    private int maxSystemPromptTokens;

    @Value("${app.chat.schema-context.max-schema-tokens:20000}")
    private int maxSchemaTokens;

    /**
     * Enrichment levels for schema context, ordered from richest to most compact.
     * Used for graceful degradation when schema exceeds token budget.
     */
    enum EnrichmentLevel {
        FULL,            // PK + FK + NOT NULL + descriptions
        NO_DESCRIPTIONS, // PK + FK + NOT NULL (drop descriptions)
        PK_FK_ONLY,      // PK + FK (drop NOT NULL)
        SKELETON          // name + dataType only (current format)
    }

    /**
     * Analyze the question to determine which context types are needed.
     * Simple questions don't need heavy performance context.
     */
    public Set<ContextType> determineNeededContext(String message) {
        if (message == null || message.isBlank()) {
            return EnumSet.noneOf(ContextType.class);
        }

        String lowerMessage = message.toLowerCase();
        if (lowerMessage.isBlank()) {
            return EnumSet.noneOf(ContextType.class);
        }
        Set<ContextType> needed = EnumSet.noneOf(ContextType.class);

        // Relationships always needed — multi-table queries need JOIN paths even without explicit keywords.
        // Inferred relationships are lightweight (~200 tokens, capped at 10) so always include them.
        needed.add(ContextType.RELATIONSHIPS);

        // Simple schema/structure questions - minimal context needed
        boolean isSimpleSchemaQuestion = lowerMessage.matches(".*(show|list|what).*(tables?|columns?|schema|views?).*") ||
            lowerMessage.matches(".*(how many|count).*(tables?|rows?|records?).*") ||
            lowerMessage.matches(".*(describe|structure|definition).*") ||
            lowerMessage.matches(".*(largest|biggest|smallest|size).*table.*");

        if (isSimpleSchemaQuestion) {
            // For simple questions, only add relationships (minimal context)
            log.debug("Simple schema question detected - using minimal context with relationships");
            return needed;
        }

        needed.add(ContextType.SEMANTIC_MODEL);

        // Performance-related questions (tight patterns to avoid false positives on data queries)
        if (lowerMessage.matches(".*(slow quer|performance|optimize|speed up|latency|execution time|response time).*") ||
            lowerMessage.matches(".*(why.{0,20}(slow|taking|long)|taking too long|how long.{0,10}(quer|execut)).*") ||
            lowerMessage.matches(".*(query.{0,10}(slow|fast|quick|seconds|minutes)|timeout|timed? out).*")) {
            needed.add(ContextType.SLOW_QUERIES);
            needed.add(ContextType.REGRESSIONS);
            needed.add(ContextType.INDEX_RECOMMENDATIONS);
            needed.add(ContextType.KEY_COLUMNS);
            needed.add(ContextType.BRAIN_INSIGHTS);
        }

        // Tuning/configuration questions - Brain ML insights
        if (lowerMessage.matches(".*(tun(e|ing)|config|parameter|knob|setting|memory|buffer|cache).*") ||
            lowerMessage.matches(".*(workload|oltp|olap|batch|throughput|qps).*") ||
            lowerMessage.matches(".*(cardinality|selectivity|statistic|estimate|plan|cost).*") ||
            lowerMessage.matches(".*(recommend|suggestion|improve|better).*")) {
            needed.add(ContextType.BRAIN_INSIGHTS);
        }

        // Index-related questions
        if (lowerMessage.matches(".*(index|indexes|indexed|indexing).*")) {
            needed.add(ContextType.INDEX_RECOMMENDATIONS);
            needed.add(ContextType.KEY_COLUMNS);
        }

        // Value dictionary / enum / filter-value questions
        if (lowerMessage.matches(".*(valid values|allowed values|possible values|status values|enum|picklist|dropdown).*") ||
            lowerMessage.matches(".*(what values|which values|acceptable values).*")) {
            needed.add(ContextType.KEY_COLUMNS);
            needed.add(ContextType.CLASSIFICATION);
        }

        // Join-specific questions also get classification context
        if (lowerMessage.matches(".*(join|relationship|foreign key|fk|reference|connect|link).*")) {
            needed.add(ContextType.CLASSIFICATION);
        }

        // Growth/scaling questions
        if (lowerMessage.matches(".*(grow|growth|scale|scaling|storage|disk|bloat|archive).*") ||
            lowerMessage.matches(".*(partition|shard).*")) {
            needed.add(ContextType.GROWTH);
            needed.add(ContextType.CLASSIFICATION);
        }

        // Analysis/review/audit questions - full context
        if (lowerMessage.matches(".*(analyze|analysis|review|audit|health|diagnose|assessment).*") ||
            lowerMessage.matches(".*(what.*wrong|issue|problem|bottleneck).*")) {
            needed.addAll(EnumSet.allOf(ContextType.class));
        }

        // Complex SQL generation - add helpful context
        if (lowerMessage.matches(".*(select|insert|update|delete|query).*") && lowerMessage.length() > 50) {
            needed.add(ContextType.KEY_COLUMNS);
            needed.add(ContextType.RELATIONSHIPS);
            needed.add(ContextType.CLASSIFICATION);
        }

        log.debug("Context types needed for question: {}", needed);
        return needed;
    }

    public String buildSchemaContext(SchemaMetadata schema, String userQuestion) {
        return buildSchemaContext(null, schema, userQuestion, Set.of());
    }

    public String buildSchemaContext(SchemaMetadata schema, String userQuestion, Set<String> ragTableNames) {
        return buildSchemaContext(null, schema, userQuestion, ragTableNames);
    }

    public String buildSchemaContext(String connectionId, SchemaMetadata schema,
                                     String userQuestion, Set<String> ragTableNames) {
        // Build header (always included)
        StringBuilder header = new StringBuilder();
        header.append("Database: ").append(schema.getDatabaseName()).append("\n");
        header.append("Database Type: ").append(schema.getDbType().toUpperCase()).append("\n\n");
        List<TableMetadata> tables = schema.getTables() != null ? schema.getTables() : List.of();
        header.append("Total Tables: ").append(tables.size()).append("\n\n");

        if (tables.isEmpty()) {
            header.append("Tables: none\n");
            return header.toString();
        }

        // Build FK lookup and description maps
        Map<String, Map<String, String>> fkLookup = buildFkLookup(schema);
        Map<String, String> tableDescriptions = loadTableDescriptions(connectionId);

        // Select which tables to render
        List<TableMetadata> tablesToRender;
        String tablesSectionPrefix;
        String tablesSectionSuffix = "";

        if (tables.size() <= schemaContextFullMaxTables) {
            tablesToRender = tables;
            tablesSectionPrefix = "Tables:\n";
        } else {
            tablesToRender = selectTablesForCompactView(tables, userQuestion, ragTableNames);
            tablesSectionPrefix = "Tables (showing " + tablesToRender.size() + " of " + tables.size() + "):\n";
            int omitted = tables.size() - tablesToRender.size();
            if (omitted > 0) {
                tablesSectionSuffix = "... " + omitted + " more tables omitted for brevity.\n"
                    + "Focus table names in your question for table-specific details.\n";
            }
        }

        // Build FK relationships section (filtered to selected tables for compact view)
        Set<String> selectedTableNames = tablesToRender.stream()
            .map(TableMetadata::getName)
            .collect(Collectors.toSet());
        String fkSection = buildFkRelationshipsSection(schema, selectedTableNames);

        // Try each enrichment level (richest first), degrade if over budget
        for (EnrichmentLevel level : EnrichmentLevel.values()) {
            StringBuilder sb = new StringBuilder(header);
            sb.append(tablesSectionPrefix);
            for (TableMetadata table : tablesToRender) {
                appendTableLine(sb, table, fkLookup, tableDescriptions, level);
            }
            sb.append(tablesSectionSuffix);
            sb.append(fkSection);

            if (TokenEstimator.estimate(sb.toString()) <= maxSchemaTokens) {
                return sb.toString();
            }
        }

        // If even skeleton exceeds budget, return skeleton anyway (best effort, no FK section)
        StringBuilder sb = new StringBuilder(header);
        sb.append(tablesSectionPrefix);
        for (TableMetadata table : tablesToRender) {
            appendTableLine(sb, table, fkLookup, tableDescriptions, EnrichmentLevel.SKELETON);
        }
        sb.append(tablesSectionSuffix);
        return sb.toString();
    }

    public String buildSemanticModelContext(String connectionId, String userQuestion, Set<String> ragTableNames) {
        if (connectionId == null || semanticModelService == null) {
            return "";
        }
        try {
            return semanticModelService.buildSemanticModelContext(
                connectionId,
                userQuestion,
                ragTableNames != null ? ragTableNames : Set.of()
            );
        } catch (Exception e) {
            log.warn("Could not build semantic model context: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Select tables for compact view (> schemaContextFullMaxTables).
     * Priority: question-mentioned tables first, then RAG-found, then fill remaining.
     */
    private List<TableMetadata> selectTablesForCompactView(List<TableMetadata> tables,
                                                            String userQuestion,
                                                            Set<String> ragTableNames) {
        String normalizedQuestion = normalizeQuestionForTableMatch(userQuestion);
        Set<String> mentionedTables = new LinkedHashSet<>();
        for (TableMetadata table : tables) {
            if (mentionsTable(normalizedQuestion, table.getName())) {
                mentionedTables.add(table.getName());
            }
        }

        if (ragTableNames != null) {
            for (TableMetadata table : tables) {
                if (ragTableNames.stream().anyMatch(rt -> rt.equalsIgnoreCase(table.getName()))) {
                    mentionedTables.add(table.getName());
                }
            }
        }

        List<TableMetadata> selectedTables = new ArrayList<>();
        for (String tableName : mentionedTables) {
            if (selectedTables.size() >= schemaContextPreviewMaxTables) break;
            tables.stream()
                .filter(t -> t.getName().equalsIgnoreCase(tableName))
                .findFirst()
                .ifPresent(selectedTables::add);
        }

        if (selectedTables.size() < schemaContextPreviewMaxTables) {
            int remaining = schemaContextPreviewMaxTables - selectedTables.size();
            tables.stream()
                .filter(t -> mentionedTables.stream().noneMatch(mt -> mt.equalsIgnoreCase(t.getName())))
                .limit(remaining)
                .forEach(selectedTables::add);
        }

        return selectedTables;
    }

    private String normalizeQuestionForTableMatch(String question) {
        return SchemaTableMatchUtil.normalizeQuestion(question);
    }

    private boolean mentionsTable(String normalizedQuestion, String tableName) {
        return SchemaTableMatchUtil.mentionsTable(normalizedQuestion, tableName);
    }

    /**
     * Build FK lookup map from schema relationships.
     * Returns: table_name → (column_name → "target_table.target_column")
     */
    private Map<String, Map<String, String>> buildFkLookup(SchemaMetadata schema) {
        var relationships = schema.getRelationships();
        if (relationships == null || relationships.isEmpty()) {
            return Map.of();
        }
        Map<String, Map<String, String>> fkLookup = new HashMap<>();
        for (var rel : relationships) {
            fkLookup
                .computeIfAbsent(rel.getFromTable(), k -> new HashMap<>())
                .put(rel.getFromColumn(), rel.getToTable() + "." + rel.getToColumn());
        }
        return fkLookup;
    }

    private static final int FK_RELATIONSHIPS_CAP = 50;

    /**
     * Build a "Key Relationships" section from explicit FK relationships in schema metadata.
     * For compact views, only includes relationships where at least one table is in selectedTableNames.
     * Capped at {@link #FK_RELATIONSHIPS_CAP} entries.
     */
    private String buildFkRelationshipsSection(SchemaMetadata schema, Set<String> selectedTableNames) {
        var relationships = schema.getRelationships();
        if (relationships == null || relationships.isEmpty()) {
            return "";
        }

        boolean isCompactView = schema.getTables() != null
            && schema.getTables().size() > schemaContextFullMaxTables;

        List<String> fkLines = relationships.stream()
            .filter(rel -> !isCompactView
                || selectedTableNames.contains(rel.getFromTable())
                || selectedTableNames.contains(rel.getToTable()))
            .limit(FK_RELATIONSHIPS_CAP)
            .map(rel -> "  - " + rel.getFromTable() + "." + rel.getFromColumn()
                + " → " + rel.getToTable() + "." + rel.getToColumn())
            .toList();

        if (fkLines.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\nKey Relationships (from database foreign keys):\n");
        for (String line : fkLines) {
            sb.append(line).append("\n");
        }
        return sb.toString();
    }

    /**
     * Load table descriptions from SchemaDocumentation.
     * Returns: table_name → description (only USER or high-confidence AI docs).
     */
    private Map<String, String> loadTableDescriptions(String connectionId) {
        if (connectionId == null || schemaDocumentationRepository == null) {
            return Map.of();
        }
        List<SchemaDocumentation> docs = schemaDocumentationRepository.findByConnectionId(connectionId);
        Map<String, String> descriptions = new HashMap<>();
        for (SchemaDocumentation doc : docs) {
            if (doc.getObjectType() != SchemaDocumentation.DocumentationType.TABLE) {
                continue;
            }
            if (doc.getDescription() == null || doc.getDescription().isBlank()) {
                continue;
            }
            boolean isUser = doc.getSource() == DocumentationSource.USER
                || doc.getSource() == DocumentationSource.CSV_IMPORT;
            boolean isHighConfidenceAi = doc.getSource() == DocumentationSource.AI_GENERATED
                && doc.getConfidence() != null && doc.getConfidence() >= 0.7;
            if (isUser || isHighConfidenceAi) {
                for (String alias : SchemaObjectNameUtil.tableLookupAliases(doc.getObjectName())) {
                    descriptions.putIfAbsent(alias, doc.getDescription());
                }
            }
        }
        return descriptions;
    }

    /**
     * Append a single table line with enriched column markers and optional description,
     * respecting the given enrichment level.
     */
    private void appendTableLine(StringBuilder sb, TableMetadata table,
                                  Map<String, Map<String, String>> fkLookup,
                                  Map<String, String> tableDescriptions,
                                  EnrichmentLevel level) {
        Map<String, String> tableFks = fkLookup.getOrDefault(table.getName(), Map.of());

        sb.append("- ").append(table.getName()).append(" (");
        String columns = table.getColumns().stream()
            .map(c -> formatColumn(c, tableFks, level))
            .collect(Collectors.joining(", "));
        sb.append(columns).append(")\n");

        if (level == EnrichmentLevel.FULL) {
            String description = resolveTableDescription(tableDescriptions, table);
            if (description != null) {
                sb.append("  \"").append(description).append("\"\n");
            }
        }
    }

    private String resolveTableDescription(Map<String, String> tableDescriptions, TableMetadata table) {
        if (tableDescriptions == null || tableDescriptions.isEmpty() || table == null) {
            return null;
        }
        for (String alias : SchemaObjectNameUtil.tableLookupAliases(table)) {
            String description = tableDescriptions.get(alias);
            if (description != null && !description.isBlank()) {
                return description;
            }
        }
        return null;
    }

    /**
     * Format a single column respecting the enrichment level.
     * FULL:            "name dataType PK FK→target.col NOT NULL"
     * NO_DESCRIPTIONS: "name dataType PK FK→target.col NOT NULL"  (same, descriptions handled by caller)
     * PK_FK_ONLY:      "name dataType PK FK→target.col"
     * SKELETON:        "name dataType"
     */
    private String formatColumn(com.dbaagent.model.ColumnMetadata column,
                                 Map<String, String> tableFks, EnrichmentLevel level) {
        var col = new StringBuilder(column.getName()).append(" ").append(column.getDataType());
        if (level == EnrichmentLevel.SKELETON) {
            return col.toString();
        }
        if (Boolean.TRUE.equals(column.getPrimaryKey())) {
            col.append(" PK");
        }
        String fkTarget = tableFks.get(column.getName());
        if (fkTarget != null) {
            col.append(" FK→").append(fkTarget);
        }
        if (level != EnrichmentLevel.PK_FK_ONLY && Boolean.FALSE.equals(column.getNullable())) {
            col.append(" NOT NULL");
        }
        return col.toString();
    }

    /**
     * Build database-specific SQL syntax rules and guidelines
     */
    public String buildDatabaseSpecificRules(String dbType) {
        if (dbType == null) {
            dbType = "mysql"; // Default fallback
        }

        String normalizedDbType = dbType.toLowerCase();

        if ("postgres".equals(normalizedDbType) || "postgresql".equals(normalizedDbType)) {
            return buildPostgreSQLRules();
        } else if ("mysql".equals(normalizedDbType)) {
            return buildMySQLRules();
        } else {
            // Generic rules for unknown database types
            return buildGenericSQLRules();
        }
    }

    /**
     * PostgreSQL-specific SQL syntax rules
     */
    private String buildPostgreSQLRules() {
        return "CRITICAL PostgreSQL SQL RULES:\n" +
            "1. ALWAYS use table-qualified column names (e.g., schema.table.column or table.column) especially when joining multiple tables.\n" +
            "2. Use double quotes for identifiers only when they contain special characters or are case-sensitive (e.g., \"TableName\").\n" +
            "3. Use single quotes for string literals (e.g., 'value').\n" +
            "4. PostgreSQL uses information_schema and pg_catalog system schemas:\n" +
            "   - Use information_schema.tables, information_schema.columns for metadata\n" +
            "   - Use pg_class, pg_attribute, pg_index for PostgreSQL-specific metadata\n" +
            "   - Use pg_stat_* views for statistics (e.g., pg_stat_user_tables, pg_stat_user_indexes)\n" +
            "   - Use pg_database_size(), pg_relation_size() for size functions\n" +
            "5. For table size queries, use: pg_relation_size(schemaname||'.'||tablename) or pg_total_relation_size()\n" +
            "6. Use pg_class.reltuples for row count estimates (approximate)\n" +
            "7. Use EXPLAIN ANALYZE for query execution plans (executes the query)\n" +
            "8. Use EXPLAIN (without ANALYZE) for query plans without execution\n" +
            "9. Table aliases are mandatory when referencing the same table multiple times\n" +
            "10. Use LIMIT clause (not TOP) for row limiting\n" +
            "11. Use OFFSET for pagination: LIMIT 10 OFFSET 20\n" +
            "12. Boolean values: true/false (lowercase)\n" +
            "13. String concatenation: Use || operator or CONCAT() function\n" +
            "14. Date functions: NOW(), CURRENT_DATE, CURRENT_TIMESTAMP, EXTRACT(), TO_TIMESTAMP()\n" +
            "15. Use COALESCE() or NULLIF() for null handling\n" +
            "16. Use array types and array operators when applicable\n" +
            "17. Use DISTINCT ON (columns) for PostgreSQL-specific distinct behavior\n" +
            "18. Window functions are fully supported: ROW_NUMBER(), RANK(), DENSE_RANK(), etc.\n" +
            "19. Common table expressions (CTEs) are supported: WITH clause\n" +
            "20. Use :: for type casting (e.g., column::text, value::integer)\n\n" +
            "EXAMPLE PostgreSQL QUERIES (ALL use table-qualified columns):\n" +
            "- Show all tables (SIMPLE - USE THIS PATTERN): SELECT t.tablename FROM pg_tables t WHERE t.schemaname = 'public' ORDER BY t.tablename;\n" +
            "- Show all tables and views: SELECT t.tablename as name, 'table' as type FROM pg_tables t WHERE t.schemaname = 'public' UNION ALL SELECT v.viewname as name, 'view' as type FROM pg_views v WHERE v.schemaname = 'public' ORDER BY name;\n" +
            "- Large tables: SELECT t.schemaname, t.tablename, pg_size_pretty(pg_total_relation_size(t.schemaname||'.'||t.tablename)) as size FROM pg_tables t WHERE t.schemaname = 'public' ORDER BY pg_total_relation_size(t.schemaname||'.'||t.tablename) DESC LIMIT 10;\n" +
            "- Table with alias: SELECT t1.id, t1.name, t2.category FROM products t1 JOIN categories t2 ON t1.category_id = t2.id WHERE t1.price > 100;\n" +
            "- Metadata: SELECT c.column_name, c.data_type FROM information_schema.columns c WHERE c.table_schema = 'public' AND c.table_name = 'users';\n\n" +
            "CRITICAL: When user asks 'show all tables' or 'list tables', ALWAYS use: SELECT t.tablename FROM pg_tables t WHERE t.schemaname = 'public' ORDER BY t.tablename;";
    }

    /**
     * MySQL-specific SQL syntax rules
     */
    private String buildMySQLRules() {
        return "CRITICAL MySQL SQL RULES:\n" +
            "1. ALWAYS use table-qualified column names (e.g., database.table.column or table.column) especially when joining multiple tables.\n" +
            "2. Use backticks for identifiers only when they contain special characters or are reserved words (e.g., `table`, `select`).\n" +
            "3. Use single quotes for string literals (e.g., 'value').\n" +
            "4. MySQL uses INFORMATION_SCHEMA database for metadata:\n" +
            "   - Use INFORMATION_SCHEMA.TABLES for table information\n" +
            "   - Use INFORMATION_SCHEMA.COLUMNS for column information\n" +
            "   - Use INFORMATION_SCHEMA.KEY_COLUMN_USAGE for indexes and foreign keys\n" +
            "   - Use INFORMATION_SCHEMA.STATISTICS for index statistics\n" +
            "5. For table size queries, use: DATA_LENGTH + INDEX_LENGTH from INFORMATION_SCHEMA.TABLES\n" +
            "6. Use TABLE_ROWS from INFORMATION_SCHEMA.TABLES for row count (approximate for InnoDB)\n" +
            "7. Use EXPLAIN or EXPLAIN FORMAT=JSON for query execution plans\n" +
            "8. Table aliases are mandatory when referencing the same table multiple times\n" +
            "9. Use LIMIT clause for row limiting (not TOP)\n" +
            "10. Use LIMIT offset, count for pagination: LIMIT 20, 10 (skip 20, take 10)\n" +
            "11. Boolean values: TRUE/FALSE (case-insensitive) or 1/0\n" +
            "12. String concatenation: Use CONCAT() function (not || operator)\n" +
            "13. Date functions: NOW(), CURDATE(), CURTIME(), DATE_FORMAT(), STR_TO_DATE()\n" +
            "14. Use IFNULL() or COALESCE() for null handling\n" +
            "15. Use GROUP_CONCAT() for string aggregation\n" +
            "16. Window functions are supported in MySQL 8.0+: ROW_NUMBER(), RANK(), DENSE_RANK(), etc.\n" +
            "17. Common table expressions (CTEs) are supported in MySQL 8.0+: WITH clause\n" +
            "18. Use CAST() or CONVERT() for type casting\n" +
            "19. AUTO_INCREMENT columns for primary keys\n" +
            "20. Engine-specific features: InnoDB (transactions, foreign keys), MyISAM (full-text search)\n\n" +
            "EXAMPLE MySQL QUERIES:\n" +
            "- Large tables: SELECT TABLE_SCHEMA, TABLE_NAME, ROUND((DATA_LENGTH + INDEX_LENGTH) / 1024 / 1024, 2) AS size_mb, TABLE_ROWS FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_SCHEMA = DATABASE() ORDER BY (DATA_LENGTH + INDEX_LENGTH) DESC LIMIT 10;\n" +
            "- Table with alias: SELECT p.id, p.name, c.category FROM products p JOIN categories c ON p.category_id = c.id WHERE p.price > 100;\n" +
            "- Metadata: SELECT COLUMN_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users';";
    }

    /**
     * Generic SQL rules for unknown database types
     */
    private String buildGenericSQLRules() {
        return "CRITICAL SQL RULES (Generic):\n" +
            "1. ALWAYS use table-qualified column names (e.g., table.column) especially when joining multiple tables.\n" +
            "2. Never use unqualified column names in SELECT, WHERE, ORDER BY, or any other clause when multiple tables are involved.\n" +
            "3. This prevents 'column reference is ambiguous' errors.\n" +
            "4. Use table aliases consistently (e.g., SELECT t1.id, t2.name FROM table1 t1 JOIN table2 t2 ON t1.id = t2.id).\n" +
            "5. Use single quotes for string literals.\n" +
            "6. Be careful with identifier quoting rules (check database-specific documentation).\n" +
            "7. Use appropriate system tables/views for metadata queries.\n" +
            "8. Use appropriate functions for date/time, string manipulation, and type conversion.";
    }

    /**
     * Applies a token budget to system prompt sections.
     * Protected (never truncated): schema, dbRules, feedback/guardrails.
     * High-priority (cut last): company knowledge, then generic training/RAG.
     *
     * @return array: [schema, classification, performance, brain, feedback, companyKnowledge, training, columnValues, resolutionHints]
     */
    public String[] applyTokenBudget(
        String schema, String classification, String performance,
        String brain, String feedback, String companyKnowledge, String training, String dbRules,
        String columnValueContext, String resolutionHints
    ) {
        int schemaTokens = TokenEstimator.estimate(schema);
        int classificationTokens = TokenEstimator.estimate(classification);
        int performanceTokens = TokenEstimator.estimate(performance);
        int brainTokens = TokenEstimator.estimate(brain);
        int feedbackTokens = TokenEstimator.estimate(feedback);
        int companyKnowledgeTokens = TokenEstimator.estimate(companyKnowledge);
        int trainingTokens = TokenEstimator.estimate(training);
        int dbRulesTokens = TokenEstimator.estimate(dbRules);
        int columnValueTokens = TokenEstimator.estimate(columnValueContext);
        int resolutionHintsTokens = TokenEstimator.estimate(resolutionHints);

        int total = schemaTokens + classificationTokens + performanceTokens
            + brainTokens + feedbackTokens + companyKnowledgeTokens + trainingTokens + dbRulesTokens
            + columnValueTokens + resolutionHintsTokens;

        log.info("Token budget: schema={}, classification={}, performance={}, brain={}, feedback={}, companyKnowledge={}, training={}, dbRules={}, colValues={}, hints={}, total={}, limit={}",
            schemaTokens, classificationTokens, performanceTokens, brainTokens,
            feedbackTokens, companyKnowledgeTokens, trainingTokens, dbRulesTokens,
            columnValueTokens, resolutionHintsTokens, total, maxSystemPromptTokens);

        if (total <= maxSystemPromptTokens) {
            return new String[]{schema, classification, performance, brain, feedback, companyKnowledge, training,
                columnValueContext, resolutionHints};
        }

        // Protected (never truncated): schema, dbRules, feedback/guardrails.
        // Feedback contains learned SQL guardrails (tenant filters, status predicates) — cutting them
        // worsens failure mode #3 (missing WHERE filters).
        int protected_ = schemaTokens + dbRulesTokens + feedbackTokens;
        int budget = maxSystemPromptTokens - protected_;
        if (budget < 0) {
            budget = 0;
        }

        // Truncation order (first to cut → last to cut):
        // columnValues → resolutionHints → brain → classification → performance → training → companyKnowledge
        String[][] sections = {
            {"columnValues", columnValueContext},
            {"resolutionHints", resolutionHints},
            {"brain", brain},
            {"classification", classification},
            {"performance", performance},
            {"training", training},
            {"companyKnowledge", companyKnowledge}
        };
        int[] tokenCounts = {columnValueTokens, resolutionHintsTokens,
            brainTokens, classificationTokens, performanceTokens, trainingTokens, companyKnowledgeTokens};

        int usedByOthers = 0;
        for (int t : tokenCounts) {
            usedByOthers += t;
        }

        // Truncate from lowest priority until within budget
        for (int i = 0; i < sections.length && usedByOthers > budget; i++) {
            int excess = usedByOthers - budget;
            if (tokenCounts[i] <= excess) {
                usedByOthers -= tokenCounts[i];
                sections[i][1] = "";
                log.info("Token budget: fully truncated {} section ({} tokens)", sections[i][0], tokenCounts[i]);
                tokenCounts[i] = 0;
            } else {
                int allowedTokens = tokenCounts[i] - excess;
                int allowedChars = allowedTokens * 4;
                if (allowedChars < sections[i][1].length()) {
                    sections[i][1] = sections[i][1].substring(0, allowedChars) + "\n... (truncated for token budget)";
                }
                usedByOthers -= excess;
                log.info("Token budget: truncated {} section from {} to ~{} tokens", sections[i][0], tokenCounts[i], allowedTokens);
            }
        }

        // Map back: colValues=0, hints=1, brain=2, classification=3, performance=4, training=5, companyKnowledge=6
        // Return: [schema, classification, performance, brain, feedback, companyKnowledge, training, colValues, hints]
        return new String[]{schema, sections[3][1], sections[4][1], sections[2][1],
            feedback, sections[6][1], sections[5][1],
            sections[0][1], sections[1][1]};
    }

    /**
     * Build classification context from schema analysis for enhanced LLM understanding.
     * Includes table roles, access patterns, anti-patterns, and optimization hints.
     */
    public String buildClassificationContext(String connectionId) {
        try {
            var classificationOpt = schemaClassificationService.getLatestClassification(connectionId);
            if (classificationOpt.isEmpty()) {
                return ""; // No classification available yet
            }

            SchemaClassification classification = classificationOpt.get();
            List<TableClassification> tables = tableClassificationRepository.findLatestByConnectionIdOrderByTableNameAsc(connectionId);

            if (tables.isEmpty()) {
                return ""; // No table classifications available
            }

            StringBuilder sb = new StringBuilder();
            sb.append("\n\n=== SCHEMA INTELLIGENCE (from Brain Analysis) ===\n");

            // Global Schema Pattern
            sb.append("\nSchema Design Pattern: ").append(classification.getGlobalPattern());
            if (classification.getConfidenceScore() != null) {
                sb.append(" (").append(classification.getConfidenceScore()).append("% confidence)");
            }
            sb.append("\n");

            // High-level stats
            if (classification.getAvgHealthScore() != null) {
                sb.append("Average Table Health: ").append(classification.getAvgHealthScore()).append("%\n");
            }
            if (classification.getTablesWithAntiPatterns() != null && classification.getTablesWithAntiPatterns() > 0) {
                sb.append("WARNING: Tables with Anti-Patterns: ").append(classification.getTablesWithAntiPatterns()).append("\n");
            }
            if (classification.getHasCycles() != null && classification.getHasCycles()) {
                sb.append("WARNING: Circular dependencies detected in schema\n");
            }

            // Table Roles Summary
            sb.append("\n--- Table Classifications ---\n");

            // Group tables by role for better context
            var factTables = tables.stream().filter(t -> "FACT".equals(t.getTableRole())).toList();
            var dimensionTables = tables.stream().filter(t -> "DIMENSION".equals(t.getTableRole())).toList();
            var bridgeTables = tables.stream().filter(t -> "BRIDGE".equals(t.getTableRole())).toList();
            var lookupTables = tables.stream().filter(t -> "LOOKUP".equals(t.getTableRole())).toList();

            if (!factTables.isEmpty()) {
                sb.append("FACT Tables (central transaction/metric data): ");
                sb.append(factTables.stream().map(TableClassification::getTableName).collect(Collectors.joining(", ")));
                sb.append("\n");
            }
            if (!dimensionTables.isEmpty()) {
                sb.append("DIMENSION Tables (descriptive attributes): ");
                sb.append(dimensionTables.stream().map(TableClassification::getTableName).collect(Collectors.joining(", ")));
                sb.append("\n");
            }
            if (!bridgeTables.isEmpty()) {
                sb.append("BRIDGE Tables (many-to-many relationships): ");
                sb.append(bridgeTables.stream().map(TableClassification::getTableName).collect(Collectors.joining(", ")));
                sb.append("\n");
            }
            if (!lookupTables.isEmpty()) {
                sb.append("LOOKUP Tables (reference data): ");
                sb.append(lookupTables.stream().map(TableClassification::getTableName).collect(Collectors.joining(", ")));
                sb.append("\n");
            }

            // Important Table Details (health issues, anti-patterns, access patterns)
            sb.append("\n--- Key Table Insights ---\n");
            for (TableClassification table : tables) {
                List<String> insights = new ArrayList<>();

                // Add role
                if (table.getTableRole() != null) {
                    insights.add("Role: " + table.getTableRole());
                }

                // Add access pattern if known
                if (table.getAccessPattern() != null && !"UNKNOWN".equals(table.getAccessPattern())) {
                    insights.add("Access: " + table.getAccessPattern());
                }

                // Add health concern if poor
                if (table.getHealthScore() != null && table.getHealthScore().doubleValue() < 60) {
                    insights.add("Low Health: " + table.getHealthScore() + "%");
                }

                // Add anti-pattern warning
                if (table.getAntiPatternCount() != null && table.getAntiPatternCount() > 0) {
                    String severity = table.getAntiPatternSeverity() != null ? table.getAntiPatternSeverity() : "UNKNOWN";
                    insights.add("Anti-patterns: " + table.getAntiPatternCount() + " (" + severity + ")");
                }

                // Add sensitivity warning
                if (table.getSensitivityLevel() != null && !table.getSensitivityLevel().equals("PUBLIC")) {
                    insights.add("Sensitivity: " + table.getSensitivityLevel());
                }

                // Add business domain if known
                if (table.getBusinessDomain() != null && !"UNKNOWN".equals(table.getBusinessDomain())) {
                    insights.add("Domain: " + table.getBusinessDomain());
                }

                // Only output if we have meaningful insights beyond just the role
                if (insights.size() > 1) {
                    sb.append("\u2022 ").append(table.getTableName()).append(": ");
                    sb.append(String.join(" | ", insights));
                    sb.append("\n");
                }
            }

            // Optimization Hints
            sb.append("\n--- Query Optimization Hints ---\n");

            // Read-heavy tables (good for caching)
            var readHeavyTables = tables.stream()
                .filter(t -> "READ_HEAVY".equals(t.getAccessPattern()))
                .map(TableClassification::getTableName)
                .toList();
            if (!readHeavyTables.isEmpty()) {
                sb.append("Read-heavy tables (good for caching/replicas): ");
                sb.append(String.join(", ", readHeavyTables)).append("\n");
            }

            // Write-heavy tables (be careful with locks)
            var writeHeavyTables = tables.stream()
                .filter(t -> "WRITE_HEAVY".equals(t.getAccessPattern()) || "UPDATE_INTENSIVE".equals(t.getAccessPattern()))
                .map(TableClassification::getTableName)
                .toList();
            if (!writeHeavyTables.isEmpty()) {
                sb.append("Write-heavy tables (watch for lock contention): ");
                sb.append(String.join(", ", writeHeavyTables)).append("\n");
            }

            // Large tables that might need pagination
            var largeTables = tables.stream()
                .filter(t -> t.getRowCount() != null && t.getRowCount() > 1000000)
                .map(t -> t.getTableName() + " (" + formatRowCount(t.getRowCount()) + " rows)")
                .toList();
            if (!largeTables.isEmpty()) {
                sb.append("Large tables (consider LIMIT/pagination): ");
                sb.append(String.join(", ", largeTables)).append("\n");
            }

            // Critical anti-patterns to warn about
            var criticalAntiPatternTables = tables.stream()
                .filter(t -> "CRITICAL".equals(t.getAntiPatternSeverity()) || "HIGH".equals(t.getAntiPatternSeverity()))
                .map(TableClassification::getTableName)
                .toList();
            if (!criticalAntiPatternTables.isEmpty()) {
                sb.append("WARNING: Tables with critical issues (use with caution): ");
                sb.append(String.join(", ", criticalAntiPatternTables)).append("\n");
            }

            sb.append("\n");

            log.debug("Built classification context with {} tables", tables.size());
            return sb.toString();

        } catch (Exception e) {
            log.warn("Failed to build classification context: {}", e.getMessage());
            return ""; // Return empty string on error - don't break chat functionality
        }
    }

    /**
     * Format row count for display (e.g., 1500000 -> "1.5M")
     */
    public String formatRowCount(Long rowCount) {
        if (rowCount == null) return "?";
        if (rowCount >= 1_000_000_000) {
            return String.format("%.1fB", rowCount / 1_000_000_000.0);
        } else if (rowCount >= 1_000_000) {
            return String.format("%.1fM", rowCount / 1_000_000.0);
        } else if (rowCount >= 1_000) {
            return String.format("%.1fK", rowCount / 1_000.0);
        }
        return rowCount.toString();
    }

    /**
     * Build comprehensive performance insights context from multiple data sources.
     * Uses lazy loading - only builds context that's needed based on question type.
     */
    public String buildPerformanceInsightsContext(String connectionId, Set<ContextType> neededContext, String question) {
        // If no specific context is requested (simple question), skip heavy performance context
        if (neededContext.isEmpty()) {
            log.debug("Skipping performance insights context (simple question)");
            return "";
        }

        StringBuilder sb = new StringBuilder();

        try {
            // 1. Slow Query Patterns (only if needed)
            if (neededContext.contains(ContextType.SLOW_QUERIES)) {
                String slowQueryContext = buildSlowQueryContext(connectionId);
                if (!slowQueryContext.isEmpty()) {
                    sb.append(slowQueryContext);
                }
            }

            // 2. Performance Regressions (only if needed)
            if (neededContext.contains(ContextType.REGRESSIONS)) {
                String regressionContext = buildRegressionContext(connectionId);
                if (!regressionContext.isEmpty()) {
                    sb.append(regressionContext);
                }
            }

            // 3. Index Recommendations (only if needed)
            if (neededContext.contains(ContextType.INDEX_RECOMMENDATIONS)) {
                String indexContext = buildIndexRecommendationContext(connectionId);
                if (!indexContext.isEmpty()) {
                    sb.append(indexContext);
                }
            }

            // 4. Key Column Intelligence (only if needed)
            if (neededContext.contains(ContextType.KEY_COLUMNS)) {
                String keyColumnContext = buildKeyColumnContext(connectionId);
                if (!keyColumnContext.isEmpty()) {
                    sb.append(keyColumnContext);
                }
            }

            // 5. Inferred Relationships (only if needed)
            if (neededContext.contains(ContextType.RELATIONSHIPS)) {
                String relationshipContext = buildInferredRelationshipsContext(connectionId, question);
                if (!relationshipContext.isEmpty()) {
                    sb.append(relationshipContext);
                }
            }

            // 6. Growth Anomalies (only if needed)
            if (neededContext.contains(ContextType.GROWTH)) {
                String growthContext = buildGrowthAnomalyContext(connectionId);
                if (!growthContext.isEmpty()) {
                    sb.append(growthContext);
                }
            }

            if (sb.length() > 0) {
                log.debug("Built performance insights context ({} chars) for connection {}", sb.length(), connectionId);
            }

        } catch (Exception e) {
            log.warn("Failed to build performance insights context: {}", e.getMessage());
        }

        return sb.toString();
    }

    /**
     * Build context from Brain ML insights - workload characterization, knob rankings,
     * column statistics (if Key Column Analysis not available), and query plan patterns.
     */
    public String buildBrainContext(String connectionId) {
        StringBuilder sb = new StringBuilder();

        try {
            // 0. Instance Specs (hardware context for sizing recommendations)
            String instanceContext = buildInstanceSpecsContext(connectionId);
            if (!instanceContext.isEmpty()) {
                sb.append(instanceContext);
            }

            // 1. Workload Characterization
            String workloadContext = buildWorkloadContext(connectionId);
            if (!workloadContext.isEmpty()) {
                sb.append(workloadContext);
            }

            // 2. Knob Rankings (configuration parameter importance)
            String knobContext = buildKnobRankingContext(connectionId);
            if (!knobContext.isEmpty()) {
                sb.append(knobContext);
            }

            // 3. Column Statistics (cardinality and selectivity)
            // Skip if Key Column Analysis data exists - it provides richer context
            // with importance scores, column values, and anti-patterns
            boolean hasKeyColumnData = !keyColumnAnalysisRepository
                .findByConnectionIdOrderByImportanceScoreDesc(connectionId).isEmpty();

            if (!hasKeyColumnData) {
                String statisticsContext = buildColumnStatisticsContext(connectionId);
                if (!statisticsContext.isEmpty()) {
                    sb.append(statisticsContext);
                }
            } else {
                log.debug("Skipping Column Statistics - Key Column Analysis provides richer context");
            }

            // 4. Plan Patterns (query optimization suggestions)
            String patternContext = buildPlanPatternContext(connectionId);
            if (!patternContext.isEmpty()) {
                sb.append(patternContext);
            }

            if (sb.length() > 0) {
                log.debug("Built Brain context ({} chars) for connection {}", sb.length(), connectionId);
            }

        } catch (Exception e) {
            log.warn("Failed to build Brain context: {}", e.getMessage());
        }

        return sb.toString();
    }

    /**
     * Build instance specs context for hardware-aware recommendations.
     * Includes vCPUs, RAM, storage type, and IOPS for accurate sizing suggestions.
     */
    private String buildInstanceSpecsContext(String connectionId) {
        try {
            var connection = credentialService.getConnectionEntity(connectionId);

            // Check if any instance specs are configured
            boolean hasSpecs = connection.getInstanceClass() != null ||
                               connection.getInstanceVcpus() != null ||
                               connection.getInstanceMemoryGb() != null ||
                               connection.getStorageType() != null ||
                               connection.getStorageMaxIops() != null;

            if (!hasSpecs) {
                return "";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("\n=== INSTANCE SPECIFICATIONS ===\n");

            if (connection.getInstanceClass() != null) {
                sb.append("Instance Class: ").append(connection.getInstanceClass()).append("\n");
            }
            if (connection.getInstanceVcpus() != null) {
                sb.append("vCPUs: ").append(connection.getInstanceVcpus()).append("\n");
            }
            if (connection.getInstanceMemoryGb() != null) {
                sb.append("Memory: ").append(connection.getInstanceMemoryGb()).append(" GB\n");
            }
            if (connection.getStorageType() != null) {
                sb.append("Storage Type: ").append(connection.getStorageType()).append("\n");
            }
            if (connection.getStorageMaxIops() != null) {
                sb.append("Max IOPS: ").append(connection.getStorageMaxIops()).append("\n");
            }

            // Add cloud provider context if available
            if (connection.getCloudProvider() != null) {
                sb.append("Cloud Provider: ").append(connection.getCloudProvider());
                if (connection.getManagedService() != null) {
                    sb.append(" (").append(connection.getManagedService()).append(")");
                }
                sb.append("\n");
            }

            sb.append("-> Use these specs to size buffer pools, connection pools, and memory settings accurately.\n");

            return sb.toString();

        } catch (Exception e) {
            log.debug("Could not build instance specs context: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Build workload characterization context.
     */
    private String buildWorkloadContext(String connectionId) {
        try {
            var profile = workloadProfileRepository.findByConnectionId(connectionId);

            if (profile.isEmpty()) {
                return "";
            }

            WorkloadProfile wp = profile.get();
            StringBuilder sb = new StringBuilder();
            sb.append("\n=== WORKLOAD CHARACTERIZATION ===\n");
            sb.append("Workload Type: ").append(wp.getWorkloadType());

            if (wp.getClassificationConfidence() != null) {
                sb.append(" (confidence: ").append(String.format("%.0f%%", wp.getClassificationConfidence())).append(")");
            }
            sb.append("\n");

            // Performance metrics
            if (wp.getThroughputQps() != null && wp.getThroughputQps() > 0) {
                sb.append("Throughput: ").append(String.format("%.1f QPS", wp.getThroughputQps()));
            }
            if (wp.getLatencyP50Ms() != null && wp.getLatencyP50Ms() > 0) {
                sb.append(" | P50 Latency: ").append(String.format("%.1fms", wp.getLatencyP50Ms()));
            }
            if (wp.getLatencyP99Ms() != null && wp.getLatencyP99Ms() > 0) {
                sb.append(" | P99 Latency: ").append(String.format("%.1fms", wp.getLatencyP99Ms()));
            }
            sb.append("\n");

            // Workload-specific recommendations
            WorkloadProfile.WorkloadType workloadType = wp.getWorkloadType();
            if (workloadType == WorkloadProfile.WorkloadType.OLTP) {
                sb.append("-> Optimize for low latency, high concurrency, short transactions\n");
            } else if (workloadType == WorkloadProfile.WorkloadType.OLAP) {
                sb.append("-> Optimize for throughput, large scans, complex aggregations\n");
            } else if (workloadType == WorkloadProfile.WorkloadType.MIXED) {
                sb.append("-> Balance between transactional and analytical workloads\n");
            } else if (workloadType == WorkloadProfile.WorkloadType.WRITE_HEAVY) {
                sb.append("-> Optimize for write throughput, consider batch inserts, minimize indexes\n");
            } else if (workloadType == WorkloadProfile.WorkloadType.READ_HEAVY) {
                sb.append("-> Optimize for read performance, indexing, caching strategies\n");
            }

            return sb.toString();

        } catch (Exception e) {
            log.debug("Could not build workload context: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Build knob ranking context - which config parameters have most impact.
     */
    private String buildKnobRankingContext(String connectionId) {
        try {
            // Get all knobs ordered by target metric and rank
            var knobs = knobRankingRepository.findByConnectionIdOrderByTargetMetricAscRankAsc(connectionId);

            if (knobs == null || knobs.isEmpty()) {
                return "";
            }

            // Show top 5 most impactful knobs (take first 5 which are usually LATENCY metric)
            var topKnobs = knobs.stream().limit(5).toList();

            StringBuilder sb = new StringBuilder();
            sb.append("\n=== TOP TUNABLE PARAMETERS (by impact) ===\n");

            for (KnobRanking knob : topKnobs) {
                sb.append(knob.getRank()).append(". ").append(knob.getKnobName());

                if (knob.getImpactScore() != null) {
                    sb.append(" (impact: ").append(String.format("%.2f", knob.getImpactScore())).append(")");
                }

                if (knob.getCurrentValue() != null && knob.getDefaultValue() != null) {
                    sb.append(" [current: ").append(knob.getCurrentValue())
                      .append(", default: ").append(knob.getDefaultValue()).append("]");
                }

                if (knob.getRequiresRestart() != null && knob.getRequiresRestart()) {
                    sb.append(" (requires restart)");
                }

                sb.append("\n");
            }

            sb.append("-> Use Brain > Tuning Advisor for ML-powered recommendations\n");

            return sb.toString();

        } catch (Exception e) {
            log.debug("Could not build knob ranking context: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Build column statistics context - cardinality and selectivity insights.
     */
    private String buildColumnStatisticsContext(String connectionId) {
        try {
            var stats = columnStatisticsRepository.findByConnectionId(connectionId);

            if (stats == null || stats.isEmpty()) {
                return "";
            }

            // Group by table and show key statistics
            Map<String, List<ColumnStatistics>> byTable = stats.stream()
                .collect(Collectors.groupingBy(ColumnStatistics::getTableName));

            // Only include if we have meaningful stats
            long significantColumns = stats.stream()
                .filter(s -> s.getDistinctCount() != null && s.getDistinctCount() > 0)
                .count();

            if (significantColumns < 3) {
                return ""; // Not enough data to be useful
            }

            StringBuilder sb = new StringBuilder();
            sb.append("\n=== COLUMN STATISTICS (ML-learned) ===\n");

            // Show tables with interesting selectivity patterns
            for (var entry : byTable.entrySet()) {
                String tableName = entry.getKey();
                List<ColumnStatistics> tableCols = entry.getValue();

                // Find highly selective columns (good for filtering)
                var selectiveCols = tableCols.stream()
                    .filter(c -> c.getDistinctCount() != null && c.getDistinctCount() > 100)
                    .limit(3)
                    .toList();

                // Find low-cardinality columns (good for indexing, enums)
                var lowCardCols = tableCols.stream()
                    .filter(c -> c.getDistinctCount() != null && c.getDistinctCount() > 0 && c.getDistinctCount() <= 20)
                    .limit(3)
                    .toList();

                if (!selectiveCols.isEmpty() || !lowCardCols.isEmpty()) {
                    sb.append(tableName).append(":\n");

                    for (ColumnStatistics col : selectiveCols) {
                        sb.append("  \u2022 ").append(col.getColumnName())
                          .append(" - ").append(col.getDistinctCount()).append(" distinct (highly selective)\n");
                    }

                    for (ColumnStatistics col : lowCardCols) {
                        sb.append("  \u2022 ").append(col.getColumnName())
                          .append(" - ").append(col.getDistinctCount()).append(" distinct (enum-like");
                        if (col.getMcvValues() != null && !col.getMcvValues().isEmpty()) {
                            sb.append(": ").append(col.getMcvValues());
                        }
                        sb.append(")\n");
                    }
                }
            }

            return sb.toString();

        } catch (Exception e) {
            log.debug("Could not build column statistics context: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Build plan pattern context - known query optimization patterns.
     */
    private String buildPlanPatternContext(String connectionId) {
        try {
            // Get reliable plan patterns (minScore=70%, minUsage=5)
            var patterns = planPatternRepository.findReliablePatterns(connectionId, 70.0, 5);

            if (patterns == null || patterns.isEmpty()) {
                return "";
            }

            // Show top 3 most effective patterns
            var topPatterns = patterns.stream().limit(3).toList();

            StringBuilder sb = new StringBuilder();
            sb.append("\n=== QUERY PLAN PATTERNS (ML-learned optimizations) ===\n");

            for (PlanPattern pattern : topPatterns) {
                sb.append("Pattern: ").append(pattern.getPatternType());
                if (pattern.getTablesInvolved() != null) {
                    sb.append(" on ").append(pattern.getTablesInvolved());
                }
                sb.append(" (").append(String.format("%.0f%% effective", pattern.getEffectivenessScore()))
                  .append(", used ").append(pattern.getUsageCount()).append("x)\n");

                if (pattern.getSuggestions() != null && !pattern.getSuggestions().isEmpty()) {
                    sb.append("  Suggestions: ").append(pattern.getSuggestions()).append("\n");
                }
            }

            return sb.toString();

        } catch (Exception e) {
            log.debug("Could not build plan pattern context: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Build context from slow query history - includes actual slow queries from ingested logs.
     */
    private String buildSlowQueryContext(String connectionId) {
        try {
            List<SlowQueryHistory> slowQueryHistories = slowQueryHistoryRepository
                .findTop10ByConnectionIdOrderByAnalyzedAtDesc(connectionId);

            if (slowQueryHistories == null || slowQueryHistories.isEmpty()) {
                return "";
            }

            // Get the most recent analysis
            SlowQueryHistory latest = slowQueryHistories.get(0);
            log.info("Slow query context: Found {} history records for connection {}",
                slowQueryHistories.size(), connectionId);
            log.info("Slow query context: Latest record - health={}, total={}, analysisData={}",
                latest.getOverallHealth(), latest.getTotalSlowQueries(),
                latest.getAnalysisData() != null ? latest.getAnalysisData().length() + " chars" : "NULL");

            StringBuilder sb = new StringBuilder();
            sb.append("\n");
            sb.append("================================================================================\n");
            sb.append("  SLOW QUERY DATA FROM PRODUCTION LOGS - USE THIS DATA TO ANSWER QUESTIONS\n");
            sb.append("  DO NOT generate SQL to query pg_stat_statements or performance_schema\n");
            sb.append("  The data below comes from ACTUAL ingested slow query logs\n");
            sb.append("================================================================================\n");
            sb.append("\n");
            sb.append("Health: ").append(latest.getOverallHealth() != null ? latest.getOverallHealth() : "UNKNOWN");
            sb.append(" | Total: ").append(latest.getTotalSlowQueries() != null ? latest.getTotalSlowQueries() : 0);

            if (latest.getCriticalCount() != null && latest.getCriticalCount() > 0) {
                sb.append(" | CRITICAL: ").append(latest.getCriticalCount());
            }
            if (latest.getHighCount() != null && latest.getHighCount() > 0) {
                sb.append(" | HIGH: ").append(latest.getHighCount());
            }
            sb.append("\n");

            // Parse the full analysis data to get actual slow queries
            boolean hasQueryDetails = false;
            if (latest.getAnalysisData() != null && !latest.getAnalysisData().isEmpty()) {
                try {
                    SlowQueryAnalysis analysis = objectMapper.readValue(
                        latest.getAnalysisData(), SlowQueryAnalysis.class);

                    if (analysis.getTopSlowQueries() != null && !analysis.getTopSlowQueries().isEmpty()) {
                        sb.append("\n--- TOP SLOW QUERIES - RANKED BY EXECUTION TIME (slowest first) ---\n");

                        // Sort by best available metric: avg > max > total execution time
                        // This ensures we always get results even if some metrics are null
                        var topQueries = analysis.getTopSlowQueries().stream()
                            .sorted((a, b) -> {
                                // Use best available metric for comparison
                                double aTime = getBestExecutionTime(a);
                                double bTime = getBestExecutionTime(b);
                                return Double.compare(bTime, aTime); // Descending
                            })
                            .limit(5)
                            .toList();

                        if (!topQueries.isEmpty()) {
                            hasQueryDetails = true;
                            int rank = 1;
                            for (SlowQuery sq : topQueries) {
                                sb.append("\n").append(rank++).append(". ");
                                sb.append("[").append(sq.getSeverity() != null ? sq.getSeverity() : "LOW").append("] ");

                                // Show best available time metric
                                String timeInfo = formatBestExecutionTime(sq);
                                sb.append(timeInfo);

                                if (sq.getCallCount() != null) {
                                    sb.append(" | Calls: ").append(sq.getCallCount());
                                }
                                if (sq.getRowsExamined() != null && sq.getRowsExamined() > 0) {
                                    sb.append(" | Rows examined: ").append(sq.getRowsExamined());
                                }

                                // Show the query (truncated for context window)
                                String queryText = sq.getNormalizedQuery() != null ? sq.getNormalizedQuery() : sq.getQueryText();
                                if (queryText != null) {
                                    String truncatedQuery = queryText.length() > 200
                                        ? queryText.substring(0, 200) + "..."
                                        : queryText;
                                    sb.append("\n   Query: ").append(truncatedQuery);
                                }

                                // Show affected tables
                                if (sq.getAffectedTables() != null && !sq.getAffectedTables().isEmpty()) {
                                    sb.append("\n   Tables: ").append(String.join(", ", sq.getAffectedTables()));
                                }

                                // Show suggestions if available
                                if (sq.getSuggestions() != null && !sq.getSuggestions().isEmpty()) {
                                    sb.append("\n   Suggestions: ").append(sq.getSuggestions().get(0));
                                }
                            }

                            sb.append("\n");
                            sb.append("\n-> When asked about slow queries, slowest query, or performance issues:\n");
                            sb.append("  - The #1 ranked query above is THE SLOWEST QUERY\n");
                            sb.append("  - Reference the query text, execution time, and tables shown above\n");
                            sb.append("  - DO NOT query pg_stat_statements, performance_schema, or INFORMATION_SCHEMA\n");
                        }
                    }
                } catch (Exception parseEx) {
                    log.debug("Could not parse slow query analysis data: {}", parseEx.getMessage());
                }
            }

            // Fallback hint if no query details were shown
            if (!hasQueryDetails) {
                sb.append("\n-> View full slow query details in: Performance > Slow Query Analysis\n");
            }

            sb.append("\n================================ END OF SLOW QUERY DATA ================================\n");

            return sb.toString();

        } catch (Exception e) {
            log.debug("Could not build slow query context: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Get the best available execution time metric from a SlowQuery.
     * Prioritizes: avg > max > total (useful when some metrics are null).
     */
    public double getBestExecutionTime(SlowQuery sq) {
        if (sq.getAvgExecutionTimeMs() != null && sq.getAvgExecutionTimeMs() > 0) {
            return sq.getAvgExecutionTimeMs();
        }
        if (sq.getMaxExecutionTimeMs() != null && sq.getMaxExecutionTimeMs() > 0) {
            return sq.getMaxExecutionTimeMs();
        }
        if (sq.getTotalExecutionTimeMs() != null && sq.getTotalExecutionTimeMs() > 0) {
            // Estimate avg from total if call count is available
            if (sq.getCallCount() != null && sq.getCallCount() > 0) {
                return sq.getTotalExecutionTimeMs() / sq.getCallCount();
            }
            return sq.getTotalExecutionTimeMs();
        }
        return 0.0;
    }

    /**
     * Format the best available execution time for display.
     */
    public String formatBestExecutionTime(SlowQuery sq) {
        if (sq.getAvgExecutionTimeMs() != null && sq.getAvgExecutionTimeMs() > 0) {
            return String.format("Avg: %.0fms", sq.getAvgExecutionTimeMs());
        }
        if (sq.getMaxExecutionTimeMs() != null && sq.getMaxExecutionTimeMs() > 0) {
            return String.format("Max: %.0fms", sq.getMaxExecutionTimeMs());
        }
        if (sq.getTotalExecutionTimeMs() != null && sq.getTotalExecutionTimeMs() > 0) {
            return String.format("Total: %.0fms", sq.getTotalExecutionTimeMs());
        }
        return "Time: unknown";
    }

    /**
     * Build context from query performance regressions - queries that got slower.
     */
    private String buildRegressionContext(String connectionId) {
        try {
            List<QueryPerformanceRegression> regressions = queryPerformanceRegressionRepository
                .findByConnectionIdAndResolvedFalseOrderByDetectedAtDesc(connectionId);

            if (regressions == null || regressions.isEmpty()) {
                return "";
            }

            // Focus on critical and severe regressions
            var significantRegressions = regressions.stream()
                .filter(r -> r.getSeverity() != null &&
                    (r.getSeverity() == QueryPerformanceRegression.Severity.CRITICAL ||
                     r.getSeverity() == QueryPerformanceRegression.Severity.SEVERE))
                .limit(3)
                .toList();

            if (significantRegressions.isEmpty()) {
                return "";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("\n=== PERFORMANCE REGRESSIONS (queries that degraded) ===\n");

            for (QueryPerformanceRegression reg : significantRegressions) {
                sb.append("[").append(reg.getSeverity()).append("] ");

                if (reg.getSlowdownPercent() != null) {
                    sb.append(String.format("+%.0f%% slower", reg.getSlowdownPercent()));
                }

                if (reg.getBaselineAvgMs() != null && reg.getCurrentAvgMs() != null) {
                    sb.append(" (").append(String.format("%.0fms", reg.getBaselineAvgMs()))
                      .append(" -> ").append(String.format("%.0fms", reg.getCurrentAvgMs())).append(")");
                }

                // Show truncated query pattern if available
                if (reg.getNormalizedQuery() != null) {
                    String query = reg.getNormalizedQuery();
                    if (query.length() > 80) {
                        query = query.substring(0, 77) + "...";
                    }
                    sb.append("\n  Query pattern: ").append(query);
                }

                sb.append("\n");
            }

            return sb.toString();

        } catch (Exception e) {
            log.debug("Could not build regression context: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Build context from index recommendations - columns that need indexes.
     */
    private String buildIndexRecommendationContext(String connectionId) {
        try {
            List<IndexRecommendationEntity> recommendations = indexRecommendationRepository
                .findByConnectionIdAndStatusOrderByPriorityAscCreatedAtDesc(connectionId, IndexRecommendationEntity.Status.PENDING);

            if (recommendations == null || recommendations.isEmpty()) {
                return "";
            }

            // Focus on high priority recommendations
            var highPriority = recommendations.stream()
                .filter(r -> "HIGH".equals(r.getPriority()))
                .limit(5)
                .toList();

            if (highPriority.isEmpty()) {
                return "";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("\n=== INDEX RECOMMENDATIONS (use these columns in WHERE/JOIN) ===\n");

            for (IndexRecommendationEntity rec : highPriority) {
                sb.append("- ").append(rec.getTableName()).append(".");
                sb.append(rec.getColumnNames() != null ? rec.getColumnNames() : "?");

                if (rec.getEstimatedImpact() != null && rec.getEstimatedImpact() > 0) {
                    sb.append(" (est. ").append(rec.getEstimatedImpact()).append("% improvement)");
                }

                if (rec.getReason() != null && !rec.getReason().isEmpty()) {
                    String reason = rec.getReason();
                    if (reason.length() > 60) {
                        reason = reason.substring(0, 57) + "...";
                    }
                    sb.append("\n  Reason: ").append(reason);
                }

                sb.append("\n");
            }

            return sb.toString();

        } catch (Exception e) {
            log.debug("Could not build index recommendation context: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Build context from key column analysis - important columns for filtering/joining.
     */
    private String buildKeyColumnContext(String connectionId) {
        try {
            List<KeyColumnAnalysis> keyColumns = keyColumnAnalysisRepository
                .findByConnectionIdOrderByImportanceScoreDesc(connectionId);

            if (keyColumns == null || keyColumns.isEmpty()) {
                return "";
            }

            // Get top key columns by importance
            var topColumns = keyColumns.stream()
                .filter(k -> k.getImportanceScore() != null && k.getImportanceScore().doubleValue() >= 50)
                .limit(10)
                .toList();

            if (topColumns.isEmpty()) {
                return "";
            }

            // Pre-load column values for low-cardinality columns
            Map<String, List<String>> columnValuesMap = loadColumnValuesMap(connectionId);

            StringBuilder sb = new StringBuilder();
            sb.append("\n=== KEY COLUMNS (best for filtering/joining) ===\n");

            for (KeyColumnAnalysis kc : topColumns) {
                sb.append("\u2022 ").append(kc.getTableName()).append(".").append(kc.getColumnName());
                sb.append(" (importance: ").append(kc.getImportanceScore().intValue()).append(")");

                // Show cardinality info
                if (kc.getDistinctCount() != null && kc.getDistinctCount() > 0) {
                    sb.append(" | ").append(formatRowCount(kc.getDistinctCount())).append(" distinct");
                }

                // Show selectivity
                if (kc.getSelectivity() != null && kc.getSelectivity().doubleValue() > 0) {
                    double sel = kc.getSelectivity().doubleValue();
                    if (sel > 0.9) {
                        sb.append(" | HIGH selectivity");
                    } else if (sel < 0.1) {
                        sb.append(" | LOW selectivity (consider alternatives)");
                    }
                }

                // Warn about data skew
                if (kc.getIsHeavilySkewed() != null && kc.getIsHeavilySkewed()) {
                    sb.append(" | SKEWED data (consider alternatives)");
                }

                // Show key type
                if (kc.getKeyType() != null && !"NON_KEY".equals(kc.getKeyType())) {
                    sb.append(" | ").append(kc.getKeyType());
                }

                sb.append("\n");

                // Include actual values for low-cardinality columns
                String columnKey = kc.getTableName().toLowerCase() + "." + kc.getColumnName().toLowerCase();
                List<String> values = columnValuesMap.get(columnKey);
                if (values != null && !values.isEmpty()) {
                    sb.append("  VALUES: ");
                    // Show up to 10 values inline
                    if (values.size() <= 10) {
                        sb.append(values.stream().map(v -> "'" + v + "'").collect(Collectors.joining(", ")));
                    } else {
                        sb.append(values.stream().limit(10).map(v -> "'" + v + "'").collect(Collectors.joining(", ")));
                        sb.append(", ... (").append(values.size() - 10).append(" more)");
                    }
                    sb.append("\n");
                }
            }

            return sb.toString();

        } catch (Exception e) {
            log.debug("Could not build key column context: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Load column values for low-cardinality columns from the cache.
     * Returns a map of "table.column" -> list of values.
     */
    private Map<String, List<String>> loadColumnValuesMap(String connectionId) {
        Map<String, List<String>> result = new HashMap<>();
        try {
            List<ColumnValueCache> cachedColumns = columnValueCacheRepository
                .findByConnectionIdAndIsLowCardinalityTrue(connectionId);

            for (ColumnValueCache cache : cachedColumns) {
                if (cache.getAllValues() != null && !cache.getAllValues().isEmpty()) {
                    try {
                        List<String> values = objectMapper.readValue(
                            cache.getAllValues(),
                            new TypeReference<List<String>>() {}
                        );
                        String key = cache.getTableName().toLowerCase() + "." + cache.getColumnName().toLowerCase();
                        result.put(key, values);
                    } catch (Exception e) {
                        log.debug("Could not parse values for {}.{}: {}",
                            cache.getTableName(), cache.getColumnName(), e.getMessage());
                    }
                }
            }

            log.debug("Loaded {} column value sets for connection {}", result.size(), connectionId);
        } catch (Exception e) {
            log.debug("Could not load column values: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Build context from inferred table relationships - best JOIN paths.
     * When a question is provided, relationships involving tables mentioned in the question
     * are always included first so they are never dropped by the cap.
     */
    private String buildInferredRelationshipsContext(String connectionId, String question) {
        try {
            List<InferredTableRelationship> relationships = inferredTableRelationshipRepository
                .findByConnectionIdOrderByConfidenceScoreDesc(connectionId);

            if (relationships == null || relationships.isEmpty()) {
                return "";
            }

            // Extract bare table names mentioned in the question by tokenizing on spaces.
            // Exact token matching avoids false positives from suffix variants (e.g. "booking"
            // from THREAD_BOOKING matching inside "customer_orders") and short alias names.
            String normalizedQuestion = question == null ? "" :
                question.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]", " ").replaceAll("\\s+", " ").trim();
            Set<String> questionTokens = java.util.Arrays.stream(normalizedQuestion.split(" "))
                .filter(t -> t.length() >= 3)
                .collect(Collectors.toSet());
            Set<String> questionTables = relationships.stream()
                .flatMap(r -> Stream.of(r.getSourceTable(), r.getTargetTable()))
                .filter(t -> t != null && !t.isBlank())
                .map(t -> SchemaObjectNameUtil.normalizedBareTableName(t))
                .filter(t -> t.length() >= 3 && questionTokens.contains(t))
                .collect(Collectors.toSet());

            // Partition: relationships touching a mentioned table come first, rest fill remaining slots
            var eligible = relationships.stream()
                .filter(r -> r.getConfidenceScore() != null && r.getConfidenceScore().doubleValue() >= 70)
                .filter(r -> !"REJECTED".equals(r.getStatus()))
                .toList();

            List<InferredTableRelationship> prioritized = new ArrayList<>();
            List<InferredTableRelationship> general = new ArrayList<>();
            for (InferredTableRelationship r : eligible) {
                String src = SchemaObjectNameUtil.normalizedBareTableName(r.getSourceTable());
                String tgt = SchemaObjectNameUtil.normalizedBareTableName(r.getTargetTable());
                if (!questionTables.isEmpty() && (questionTables.contains(src) || questionTables.contains(tgt))) {
                    prioritized.add(r);
                } else {
                    general.add(r);
                }
            }

            // Combine: all prioritized first, then fill up to 10 from general
            List<InferredTableRelationship> highConfidence = new ArrayList<>(prioritized);
            int remaining = Math.max(0, 10 - prioritized.size());
            highConfidence.addAll(general.stream().limit(remaining).toList());

            if (highConfidence.isEmpty()) {
                return "";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("\n=== INFERRED JOIN PATHS (high confidence) ===\n");

            for (InferredTableRelationship rel : highConfidence) {
                sb.append("- ").append(rel.getSourceTable()).append(".").append(rel.getSourceColumn());
                sb.append(" -> ").append(rel.getTargetTable()).append(".").append(rel.getTargetColumn());
                sb.append(" (").append(rel.getConfidenceScore().intValue()).append("% confidence");

                if (rel.getJoinCount() != null && rel.getJoinCount() > 0) {
                    sb.append(", ").append(rel.getJoinCount()).append(" queries");
                }

                sb.append(")");

                // Show cardinality
                if (rel.getCardinality() != null) {
                    sb.append(" [").append(rel.getCardinality()).append("]");
                }

                // Mark if validated
                if ("VALIDATED".equals(rel.getStatus())) {
                    sb.append(" \u2713");
                }

                sb.append("\n");
            }

            return sb.toString();

        } catch (Exception e) {
            log.debug("Could not build inferred relationships context: {}", e.getMessage());
            return "";
        }
    }

    /**
     * Build context from growth anomalies - tables with size/growth issues.
     */
    private String buildGrowthAnomalyContext(String connectionId) {
        StringBuilder sb = new StringBuilder();

        try {
            // Get latest snapshots for each table (last 7 days)
            java.time.LocalDateTime since = java.time.LocalDateTime.now().minusDays(7);
            List<TableStatsHistory> allHistory = tableStatsHistoryRepository
                .findByConnectionIdAndSnapshotTimestampBetweenOrderBySnapshotTimestampAsc(
                    connectionId, since, java.time.LocalDateTime.now());

            if (allHistory != null && !allHistory.isEmpty()) {
                // Group by table and get latest snapshot for each
                java.util.Map<String, TableStatsHistory> latestByTable = new java.util.HashMap<>();
                java.util.Map<String, TableStatsHistory> oldestByTable = new java.util.HashMap<>();

                for (TableStatsHistory h : allHistory) {
                    String tableName = h.getTableName();
                    TableStatsHistory existing = latestByTable.get(tableName);
                    if (existing == null || h.getSnapshotTimestamp().isAfter(existing.getSnapshotTimestamp())) {
                        latestByTable.put(tableName, h);
                    }
                    TableStatsHistory oldestExisting = oldestByTable.get(tableName);
                    if (oldestExisting == null || h.getSnapshotTimestamp().isBefore(oldestExisting.getSnapshotTimestamp())) {
                        oldestByTable.put(tableName, h);
                    }
                }

                // Calculate growth and find interesting tables
                List<TableGrowthInfo> tableInfos = new java.util.ArrayList<>();
                for (java.util.Map.Entry<String, TableStatsHistory> entry : latestByTable.entrySet()) {
                    String tableName = entry.getKey();
                    TableStatsHistory latest = entry.getValue();
                    TableStatsHistory oldest = oldestByTable.get(tableName);

                    long size = (latest.getDataSizeBytes() != null ? latest.getDataSizeBytes() : 0) +
                                (latest.getIndexSizeBytes() != null ? latest.getIndexSizeBytes() : 0);
                    long rowCount = latest.getRowCount() != null ? latest.getRowCount() : 0;
                    double bloatPct = latest.getBloatPercent() != null ? latest.getBloatPercent() : 0;
                    long bloatBytes = latest.getBloatBytes() != null ? latest.getBloatBytes() : 0;

                    double growthRate = 0;
                    long growthBytes = 0;
                    if (oldest != null && oldest != latest) {
                        long oldSize = (oldest.getDataSizeBytes() != null ? oldest.getDataSizeBytes() : 0) +
                                       (oldest.getIndexSizeBytes() != null ? oldest.getIndexSizeBytes() : 0);
                        if (oldSize > 0) {
                            growthBytes = size - oldSize;
                            growthRate = (growthBytes * 100.0) / oldSize;
                        }
                    }

                    tableInfos.add(new TableGrowthInfo(tableName, size, rowCount, growthRate, growthBytes, bloatPct, bloatBytes));
                }

                // Build context sections
                boolean hasContent = false;

                // 1. Largest tables (>1GB)
                List<TableGrowthInfo> largeTables = tableInfos.stream()
                    .filter(t -> t.size > 1024L * 1024 * 1024)
                    .sorted((a, b) -> Long.compare(b.size, a.size))
                    .limit(10)
                    .toList();

                if (!largeTables.isEmpty()) {
                    sb.append("\n=== LARGE TABLES (>1GB) ===\n");
                    for (TableGrowthInfo t : largeTables) {
                        sb.append("- ").append(t.name).append(": ").append(formatBytes(t.size));
                        sb.append(" (").append(formatRowCount(t.rowCount)).append(" rows)");
                        if (t.bloatPct > 10) {
                            sb.append(" [").append(String.format("%.0f%%", t.bloatPct)).append(" bloat]");
                        }
                        sb.append("\n");
                    }
                    hasContent = true;
                }

                // 2. Fast growing tables (>10% growth)
                List<TableGrowthInfo> fastGrowing = tableInfos.stream()
                    .filter(t -> t.growthRate > 10 && t.size > 100L * 1024 * 1024) // >10% growth and >100MB
                    .sorted((a, b) -> Double.compare(b.growthRate, a.growthRate))
                    .limit(5)
                    .toList();

                if (!fastGrowing.isEmpty()) {
                    sb.append("\n=== FAST GROWING TABLES (last 7 days) ===\n");
                    for (TableGrowthInfo t : fastGrowing) {
                        String level = t.growthRate > 50 ? "[CRITICAL]" : "[WARNING]";
                        sb.append(level).append(" ").append(t.name);
                        sb.append(": +").append(String.format("%.1f%%", t.growthRate));
                        sb.append(" (+").append(formatBytes(t.growthBytes)).append(")");
                        sb.append("\n");
                    }
                    sb.append("-> Consider pagination/archival for these tables\n");
                    hasContent = true;
                }

                // 3. High bloat tables (>10% and >1GB)
                List<TableGrowthInfo> highBloat = tableInfos.stream()
                    .filter(t -> t.bloatPct > 10 && t.size > 1024L * 1024 * 1024)
                    .sorted((a, b) -> Long.compare(b.bloatBytes, a.bloatBytes))
                    .limit(5)
                    .toList();

                if (!highBloat.isEmpty()) {
                    sb.append("\n=== HIGH BLOAT TABLES (wasted space) ===\n");
                    for (TableGrowthInfo t : highBloat) {
                        sb.append("[WARNING] ").append(t.name);
                        sb.append(": ").append(String.format("%.0f%%", t.bloatPct)).append(" bloat");
                        sb.append(" (").append(formatBytes(t.bloatBytes)).append(" wasted)");
                        sb.append("\n");
                    }
                    sb.append("-> Consider OPTIMIZE TABLE or VACUUM to reclaim space\n");
                    hasContent = true;
                }

                // 4. Tables likely needing retention policy (log/audit/history patterns)
                java.util.regex.Pattern retentionPattern = java.util.regex.Pattern.compile(
                    "(_log|_logs|_audit|_history|_archive|_event|_events|_tracking|_activity|_changes|log_|audit_|history_)",
                    java.util.regex.Pattern.CASE_INSENSITIVE);

                List<TableGrowthInfo> retentionCandidates = tableInfos.stream()
                    .filter(t -> retentionPattern.matcher(t.name).find())
                    .filter(t -> t.size > 500L * 1024 * 1024 || t.growthRate > 10) // >500MB or >10% growth
                    .sorted((a, b) -> Long.compare(b.size, a.size))
                    .limit(5)
                    .toList();

                if (!retentionCandidates.isEmpty()) {
                    sb.append("\n=== TABLES NEEDING RETENTION POLICY ===\n");
                    for (TableGrowthInfo t : retentionCandidates) {
                        sb.append("- ").append(t.name);
                        sb.append(": ").append(formatBytes(t.size));
                        if (t.growthRate > 10) {
                            sb.append(" (+").append(String.format("%.0f%%", t.growthRate)).append(" growth)");
                        }
                        sb.append("\n");
                    }
                    sb.append("-> Suggest: DELETE FROM table WHERE created_at < NOW() - INTERVAL X DAY\n");
                    hasContent = true;
                }

                if (!hasContent) {
                    // No significant growth issues
                    return "";
                }
            }

            // Also include growth anomalies if any
            List<GrowthAnomaly> anomalies = growthAnomalyRepository
                .findByConnectionIdAndAcknowledgedFalseOrderByDetectionTimestampDesc(connectionId);

            if (anomalies != null && !anomalies.isEmpty()) {
                var significantAnomalies = anomalies.stream()
                    .filter(a -> a.getSeverity() != null &&
                        (a.getSeverity() == GrowthAnomaly.Severity.CRITICAL ||
                         a.getSeverity() == GrowthAnomaly.Severity.WARNING))
                    .limit(5)
                    .toList();

                if (!significantAnomalies.isEmpty()) {
                    sb.append("\n=== ACTIVE GROWTH ALERTS ===\n");
                    for (GrowthAnomaly anomaly : significantAnomalies) {
                        String level = anomaly.getSeverity() == GrowthAnomaly.Severity.CRITICAL ? "[CRITICAL]" : "[WARNING]";
                        sb.append(level).append(" ").append(anomaly.getTableName());
                        sb.append(": ").append(anomaly.getAnomalyType());
                        if (anomaly.getSizeGrowthPercent() != null && anomaly.getSizeGrowthPercent() > 0) {
                            sb.append(" (+").append(String.format("%.0f%%", anomaly.getSizeGrowthPercent())).append(")");
                        }
                        sb.append("\n");
                    }
                }
            }

            return sb.toString();

        } catch (Exception e) {
            log.debug("Could not build growth context: {}", e.getMessage());
            return "";
        }
    }

    // Helper class for growth analysis
    private static class TableGrowthInfo {
        String name;
        long size;
        long rowCount;
        double growthRate;
        long growthBytes;
        double bloatPct;
        long bloatBytes;

        TableGrowthInfo(String name, long size, long rowCount, double growthRate, long growthBytes, double bloatPct, long bloatBytes) {
            this.name = name;
            this.size = size;
            this.rowCount = rowCount;
            this.growthRate = growthRate;
            this.growthBytes = growthBytes;
            this.bloatPct = bloatPct;
            this.bloatBytes = bloatBytes;
        }
    }

    public String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
