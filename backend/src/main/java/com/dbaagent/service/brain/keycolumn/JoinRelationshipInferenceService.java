package com.dbaagent.service.brain.keycolumn;

import com.dbaagent.dto.JoinRelationshipDetail;
import com.dbaagent.dto.RelationshipInferenceResult;
import com.dbaagent.model.InferredTableRelationship;
import com.dbaagent.model.KeyColumnAnalysis;
import com.dbaagent.model.QueryLineage;
import com.dbaagent.model.QueryRequest;
import com.dbaagent.model.SlowQuery;
import com.dbaagent.model.SlowQueryAnalysis;
import com.dbaagent.model.SlowQueryHistory;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.TableMetadata;
import com.dbaagent.model.IndexMetadata;
import com.dbaagent.repository.InferredTableRelationshipRepository;
import com.dbaagent.repository.KeyColumnAnalysisRepository;
import com.dbaagent.repository.QueryLineageRepository;
import com.dbaagent.repository.SlowQueryHistoryRepository;
import com.dbaagent.service.QueryExecutorService;
import com.dbaagent.service.SchemaScannerService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.update.Update;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Service for inferring table relationships from JOIN patterns in SQL queries.
 * Analyzes QueryLineage and slow query logs to detect implicit foreign key relationships.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class JoinRelationshipInferenceService {

    private final InferredTableRelationshipRepository inferredRelationshipRepository;
    private final QueryLineageRepository queryLineageRepository;
    private final KeyColumnAnalysisRepository keyColumnAnalysisRepository;
    private final SlowQueryHistoryRepository slowQueryHistoryRepository;
    private final ObjectMapper objectMapper;
    private final QueryExecutorService queryExecutorService;
    private final SchemaScannerService schemaScannerService;

    // Patterns for FK column detection
    // Matches: user_id, hotel_key, order_fk, pm_hotel_id, bookingid, customerid
    private static final Pattern FK_SUFFIX_PATTERN = Pattern.compile(".*(_id|_key|_fk|_ref|[a-z]id)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern PK_PATTERN = Pattern.compile("^(id|pk|uuid)$", Pattern.CASE_INSENSITIVE);

    /**
     * Main entry point: analyze all queries for a connection and infer relationships.
     */
    @Transactional
    public RelationshipInferenceResult inferRelationships(String connectionId) {
        log.info("Starting relationship inference for connection: {}", connectionId);
        LocalDateTime startTime = LocalDateTime.now();

        int totalQueries = 0;
        int lineageQueries = 0;
        int parseFailures = 0;

        // Map to aggregate join patterns: key -> (count, distinctQueries, sampleQuery)
        Map<String, JoinAggregation> joinAggregations = new HashMap<>();

        // 1. Process queries from QueryLineage
        List<QueryLineage> lineageEntries = queryLineageRepository.findByConnectionIdOrderByCreatedAtDesc(connectionId);
        log.info("Found {} query lineage entries to analyze", lineageEntries.size());

        for (QueryLineage lineage : lineageEntries) {
            String queryText = lineage.getQueryText();
            if (queryText == null || queryText.trim().isEmpty()) {
                continue;
            }

            totalQueries++;
            lineageQueries++;

            try {
                List<JoinRelationshipDetail> joins = extractJoinRelationships(queryText);
                for (JoinRelationshipDetail join : joins) {
                    if (join.isValid()) {
                        aggregateJoin(joinAggregations, join, queryText);
                    }
                }
            } catch (Exception e) {
                parseFailures++;
                log.debug("Failed to parse query: {}", truncateQuery(queryText), e);
            }
        }

        log.info("Processed {} QueryLineage entries, found {} unique join patterns, {} parse failures",
            lineageQueries, joinAggregations.size(), parseFailures);

        // 1b. Process queries from SlowQueryHistory (contains actual slow query SQL)
        int slowLogQueries = processSlowQueryHistory(connectionId, joinAggregations);
        totalQueries += slowLogQueries;
        log.info("Processed {} queries from SlowQueryHistory", slowLogQueries);

        // 2. Infer relationships from column naming patterns (fallback when few JOINs found)
        int columnBasedRelationships = inferRelationshipsFromColumnPatterns(connectionId, joinAggregations);
        log.info("Inferred {} additional relationships from column naming patterns", columnBasedRelationships);

        // 2b. Infer relationships from index metadata (indexes on FK columns suggest relationships)
        int indexBasedRelationships = inferRelationshipsFromIndexes(connectionId, joinAggregations);
        log.info("Inferred {} additional relationships from index metadata", indexBasedRelationships);

        // 3. Validate relationships using data value correlation (sample actual data)
        int dataCorrelationValidated = validateRelationshipsWithDataCorrelation(connectionId, joinAggregations);
        log.info("Validated {} relationships using data value correlation", dataCorrelationValidated);

        // 4. Save/update relationships
        int newCount = 0;
        int updatedCount = 0;
        List<InferredTableRelationship> allRelationships = new ArrayList<>();

        for (Map.Entry<String, JoinAggregation> entry : joinAggregations.entrySet()) {
            JoinAggregation agg = entry.getValue();
            JoinRelationshipDetail detail = agg.detail;

            // Check if relationship already exists
            Optional<InferredTableRelationship> existing = inferredRelationshipRepository
                .findByConnectionIdAndSourceTableAndSourceColumnAndTargetTableAndTargetColumn(
                    connectionId,
                    detail.getSourceTable(),
                    detail.getSourceColumn(),
                    detail.getTargetTable(),
                    detail.getTargetColumn()
                );

            InferredTableRelationship relationship;
            if (existing.isPresent()) {
                // Update existing
                relationship = existing.get();
                relationship.setJoinCount(relationship.getJoinCount() + agg.joinCount);
                relationship.setDistinctQueryCount(relationship.getDistinctQueryCount() + agg.distinctQueries.size());
                relationship.setLastSeenAt(LocalDateTime.now());
                relationship.setConfidenceScore(calculateConfidenceScore(
                    relationship.getJoinCount(), relationship.getDistinctQueryCount()));
                updatedCount++;
            } else {
                // Create new
                relationship = InferredTableRelationship.builder()
                    .connectionId(connectionId)
                    .sourceTable(detail.getSourceTable())
                    .sourceColumn(detail.getSourceColumn())
                    .targetTable(detail.getTargetTable())
                    .targetColumn(detail.getTargetColumn())
                    .joinCount(agg.joinCount)
                    .distinctQueryCount(agg.distinctQueries.size())
                    .confidenceScore(calculateConfidenceScore(agg.joinCount, agg.distinctQueries.size()))
                    .isSelfJoin(detail.isSelfJoin())
                    .cardinality("N:1")
                    .status("INFERRED")
                    .sampleQuery(agg.sampleQuery)
                    .build();
                newCount++;
            }

            relationship = inferredRelationshipRepository.save(relationship);
            allRelationships.add(relationship);
        }

        // 3. Build result
        long highConfidenceCount = allRelationships.stream()
            .filter(r -> r.getConfidenceScore().compareTo(BigDecimal.valueOf(50)) >= 0)
            .count();

        RelationshipInferenceResult result = RelationshipInferenceResult.builder()
            .connectionId(connectionId)
            .totalRelationshipsInferred(allRelationships.size())
            .highConfidenceCount((int) highConfidenceCount)
            .newRelationshipsFound(newCount)
            .existingRelationshipsUpdated(updatedCount)
            .totalQueriesAnalyzed(totalQueries)
            .queriesFromLineage(lineageQueries)
            .queriesFromSlowLogs(slowLogQueries)
            .parseFailures(parseFailures)
            .analyzedAt(startTime)
            .relationships(allRelationships)
            .message(String.format("Analysis complete: %d relationships inferred (%d new, %d updated), %d high-confidence",
                allRelationships.size(), newCount, updatedCount, highConfidenceCount))
            .build();

        log.info("Relationship inference complete: {} relationships ({} new, {} updated)",
            allRelationships.size(), newCount, updatedCount);

        return result;
    }

    /**
     * Extract JOIN relationships from a SQL query using JSQLParser.
     */
    public List<JoinRelationshipDetail> extractJoinRelationships(String sql) {
        List<JoinRelationshipDetail> relationships = new ArrayList<>();

        if (sql == null || sql.trim().isEmpty()) {
            return relationships;
        }

        try {
            Statement statement = CCJSqlParserUtil.parse(sql);

            if (statement instanceof Select select) {
                processSelect(select, relationships, sql);
            } else if (statement instanceof Update update) {
                processUpdate(update, relationships, sql);
            } else if (statement instanceof Delete delete) {
                processDelete(delete, relationships, sql);
            }

        } catch (JSQLParserException e) {
            log.debug("Failed to parse SQL: {}", truncateQuery(sql));
            throw new RuntimeException("SQL parse error", e);
        }

        return relationships;
    }

    private void processSelect(Select select, List<JoinRelationshipDetail> relationships, String originalSql) {
        PlainSelect plainSelect = select.getPlainSelect();
        if (plainSelect == null) {
            return;
        }

        // Build alias to table mapping
        Map<String, String> aliasToTable = buildAliasMap(plainSelect);

        // Process JOINs
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                processJoin(join, aliasToTable, relationships, originalSql);
            }
        }

        // Process WHERE clause for implicit joins
        if (plainSelect.getWhere() != null) {
            processWhereClause(plainSelect.getWhere(), aliasToTable, relationships, originalSql);
        }
    }

    private void processUpdate(Update update, List<JoinRelationshipDetail> relationships, String originalSql) {
        // Updates can have JOINs in some SQL dialects
        if (update.getJoins() != null) {
            Map<String, String> aliasToTable = new HashMap<>();
            if (update.getTable() != null) {
                Table t = update.getTable();
                String tableName = t.getName().toLowerCase();
                aliasToTable.put(tableName, tableName);
                if (t.getAlias() != null) {
                    aliasToTable.put(t.getAlias().getName().toLowerCase(), tableName);
                }
            }
            for (Join join : update.getJoins()) {
                if (join.getFromItem() instanceof Table joinTable) {
                    String tableName = joinTable.getName().toLowerCase();
                    aliasToTable.put(tableName, tableName);
                    if (joinTable.getAlias() != null) {
                        aliasToTable.put(joinTable.getAlias().getName().toLowerCase(), tableName);
                    }
                }
                processJoin(join, aliasToTable, relationships, originalSql);
            }
        }
    }

    private void processDelete(Delete delete, List<JoinRelationshipDetail> relationships, String originalSql) {
        // Deletes can have JOINs in some SQL dialects
        if (delete.getJoins() != null) {
            Map<String, String> aliasToTable = new HashMap<>();
            if (delete.getTable() != null) {
                Table t = delete.getTable();
                String tableName = t.getName().toLowerCase();
                aliasToTable.put(tableName, tableName);
                if (t.getAlias() != null) {
                    aliasToTable.put(t.getAlias().getName().toLowerCase(), tableName);
                }
            }
            for (Join join : delete.getJoins()) {
                if (join.getFromItem() instanceof Table joinTable) {
                    String tableName = joinTable.getName().toLowerCase();
                    aliasToTable.put(tableName, tableName);
                    if (joinTable.getAlias() != null) {
                        aliasToTable.put(joinTable.getAlias().getName().toLowerCase(), tableName);
                    }
                }
                processJoin(join, aliasToTable, relationships, originalSql);
            }
        }
    }

    private Map<String, String> buildAliasMap(PlainSelect plainSelect) {
        Map<String, String> aliasToTable = new HashMap<>();

        // FROM clause
        if (plainSelect.getFromItem() instanceof Table fromTable) {
            String tableName = fromTable.getName().toLowerCase();
            aliasToTable.put(tableName, tableName);
            if (fromTable.getAlias() != null) {
                aliasToTable.put(fromTable.getAlias().getName().toLowerCase(), tableName);
            }
        }

        // JOIN tables
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                if (join.getFromItem() instanceof Table joinTable) {
                    String tableName = joinTable.getName().toLowerCase();
                    aliasToTable.put(tableName, tableName);
                    if (joinTable.getAlias() != null) {
                        aliasToTable.put(joinTable.getAlias().getName().toLowerCase(), tableName);
                    }
                }
            }
        }

        return aliasToTable;
    }

    private void processJoin(Join join, Map<String, String> aliasToTable,
                            List<JoinRelationshipDetail> relationships, String originalSql) {
        if (join.getOnExpressions() == null) {
            return;
        }

        String joinType = getJoinType(join);

        for (Expression onExpr : join.getOnExpressions()) {
            processJoinCondition(onExpr, aliasToTable, relationships, joinType, originalSql);
        }
    }

    private void processJoinCondition(Expression expr, Map<String, String> aliasToTable,
                                     List<JoinRelationshipDetail> relationships,
                                     String joinType, String originalSql) {
        if (expr instanceof EqualsTo equalsTo) {
            Expression left = equalsTo.getLeftExpression();
            Expression right = equalsTo.getRightExpression();

            if (left instanceof Column leftCol && right instanceof Column rightCol) {
                JoinRelationshipDetail detail = createJoinDetail(leftCol, rightCol, aliasToTable, joinType, originalSql);
                if (detail != null && detail.isValid()) {
                    relationships.add(detail);
                }
            }
        } else if (expr instanceof BinaryExpression binExpr) {
            // Handle AND conditions
            processJoinCondition(binExpr.getLeftExpression(), aliasToTable, relationships, joinType, originalSql);
            processJoinCondition(binExpr.getRightExpression(), aliasToTable, relationships, joinType, originalSql);
        }
    }

    private void processWhereClause(Expression where, Map<String, String> aliasToTable,
                                   List<JoinRelationshipDetail> relationships, String originalSql) {
        // Look for implicit joins: WHERE a.id = b.a_id
        if (where instanceof EqualsTo equalsTo) {
            Expression left = equalsTo.getLeftExpression();
            Expression right = equalsTo.getRightExpression();

            if (left instanceof Column leftCol && right instanceof Column rightCol) {
                // Only consider as join if columns are from different tables
                String leftTable = resolveTable(leftCol, aliasToTable);
                String rightTable = resolveTable(rightCol, aliasToTable);

                if (leftTable != null && rightTable != null && !leftTable.equals(rightTable)) {
                    JoinRelationshipDetail detail = createJoinDetail(leftCol, rightCol, aliasToTable, "IMPLICIT", originalSql);
                    if (detail != null && detail.isValid()) {
                        relationships.add(detail);
                    }
                }
            }
        } else if (where instanceof BinaryExpression binExpr) {
            processWhereClause(binExpr.getLeftExpression(), aliasToTable, relationships, originalSql);
            processWhereClause(binExpr.getRightExpression(), aliasToTable, relationships, originalSql);
        }
    }

    private JoinRelationshipDetail createJoinDetail(Column leftCol, Column rightCol,
                                                   Map<String, String> aliasToTable,
                                                   String joinType, String originalSql) {
        String leftTable = resolveTable(leftCol, aliasToTable);
        String rightTable = resolveTable(rightCol, aliasToTable);
        String leftColumn = leftCol.getColumnName().toLowerCase();
        String rightColumn = rightCol.getColumnName().toLowerCase();

        if (leftTable == null || rightTable == null) {
            return null;
        }

        // Determine FK direction
        String[] fkDirection = determineFKDirection(leftTable, leftColumn, rightTable, rightColumn);
        String sourceTable = fkDirection[0];
        String sourceColumn = fkDirection[1];
        String targetTable = fkDirection[2];
        String targetColumn = fkDirection[3];

        boolean isSelfJoin = leftTable.equals(rightTable);

        return JoinRelationshipDetail.builder()
            .leftTable(leftTable)
            .leftColumn(leftColumn)
            .rightTable(rightTable)
            .rightColumn(rightColumn)
            .joinType(joinType)
            .sourceTable(sourceTable)
            .sourceColumn(sourceColumn)
            .targetTable(targetTable)
            .targetColumn(targetColumn)
            .selfJoin(isSelfJoin)
            .originalQuery(originalSql)
            .build();
    }

    private String resolveTable(Column col, Map<String, String> aliasToTable) {
        if (col.getTable() != null) {
            String tableRef = col.getTable().getName().toLowerCase();
            return aliasToTable.getOrDefault(tableRef, tableRef);
        }
        return null;
    }

    /**
     * Determine FK direction based on column naming patterns.
     * Returns [sourceTable, sourceColumn, targetTable, targetColumn]
     */
    public String[] determineFKDirection(String table1, String col1, String table2, String col2) {
        // Rule 1: Column ending in _id, _key, _fk, _ref is likely FK (source)
        boolean col1IsFKPattern = FK_SUFFIX_PATTERN.matcher(col1).matches();
        boolean col2IsFKPattern = FK_SUFFIX_PATTERN.matcher(col2).matches();

        // Rule 2: Column named 'id', 'pk', 'uuid' is likely PK (target)
        boolean col1IsPKPattern = PK_PATTERN.matcher(col1).matches();
        boolean col2IsPKPattern = PK_PATTERN.matcher(col2).matches();

        // Rule 3: Column name contains target table name (e.g., user_id -> users)
        boolean col1ContainsTable2 = columnRefersToTable(col1, table2);
        boolean col2ContainsTable1 = columnRefersToTable(col2, table1);

        // Decision logic
        if (col1IsFKPattern && col2IsPKPattern) {
            // col1 is FK pointing to col2 (PK)
            return new String[]{table1, col1, table2, col2};
        } else if (col2IsFKPattern && col1IsPKPattern) {
            // col2 is FK pointing to col1 (PK)
            return new String[]{table2, col2, table1, col1};
        } else if (col1ContainsTable2 && !col2ContainsTable1) {
            // col1 references table2
            return new String[]{table1, col1, table2, col2};
        } else if (col2ContainsTable1 && !col1ContainsTable2) {
            // col2 references table1
            return new String[]{table2, col2, table1, col1};
        } else if (col1IsFKPattern && !col2IsFKPattern) {
            // col1 looks more like FK
            return new String[]{table1, col1, table2, col2};
        } else if (col2IsFKPattern && !col1IsFKPattern) {
            // col2 looks more like FK
            return new String[]{table2, col2, table1, col1};
        } else {
            // Fallback: alphabetical order
            if (table1.compareTo(table2) <= 0) {
                return new String[]{table1, col1, table2, col2};
            } else {
                return new String[]{table2, col2, table1, col1};
            }
        }
    }

    private boolean columnRefersToTable(String column, String table) {
        // Remove common suffixes and check if column contains table name
        String colBase = column.toLowerCase()
            .replaceAll("_(id|key|fk|ref)$", "")
            .replaceAll("^(fk_|ref_)", "");

        String tableBase = table.toLowerCase();

        // Handle pluralization
        String tableSingular = tableBase.endsWith("s") ?
            tableBase.substring(0, tableBase.length() - 1) : tableBase;
        String tablePlural = tableBase + "s";

        // Direct matches
        if (colBase.equals(tableBase)
            || colBase.equals(tableSingular)
            || colBase.equals(tablePlural)
            || colBase.startsWith(tableSingular)) {
            return true;
        }

        // Handle prefixed columns like "pm_hotel_id" -> "customer"
        // Check if column contains the table name after any prefix
        if (colBase.contains("_")) {
            String[] parts = colBase.split("_");
            for (String part : parts) {
                if (part.equals(tableBase)
                    || part.equals(tableSingular)
                    || part.equals(tablePlural)
                    || (part.length() > 2 && tableSingular.startsWith(part))) {
                    return true;
                }
            }
            // Also check the suffix part (everything after first underscore)
            int firstUnderscore = colBase.indexOf('_');
            if (firstUnderscore > 0) {
                String suffix = colBase.substring(firstUnderscore + 1);
                if (suffix.equals(tableBase)
                    || suffix.equals(tableSingular)
                    || suffix.equals(tablePlural)) {
                    return true;
                }
            }
        }

        // Handle columns like "bookingid" (no underscore) -> "booking"
        if (colBase.endsWith(tableSingular) || colBase.endsWith(tableBase)) {
            return true;
        }

        return false;
    }

    /**
     * Calculate confidence score based on join frequency and query diversity.
     * - Base: joinCount * 5 (max 50 points)
     * - Distinct queries: distinctQueryCount * 10 (max 30 points)
     * - Consistency bonus: 20 points if >= 3 distinct queries
     */
    public BigDecimal calculateConfidenceScore(int joinCount, int distinctQueryCount) {
        double score = 0;

        // Join frequency component (max 50)
        score += Math.min(50, joinCount * 5);

        // Query diversity component (max 30)
        score += Math.min(30, distinctQueryCount * 10);

        // Consistency bonus
        if (distinctQueryCount >= 3) {
            score += 20;
        }

        // Cap at 100
        score = Math.min(100, score);

        return BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP);
    }

    private String getJoinType(Join join) {
        if (join.isLeft()) return "LEFT";
        if (join.isRight()) return "RIGHT";
        if (join.isFull()) return "FULL";
        if (join.isInner()) return "INNER";
        if (join.isCross()) return "CROSS";
        return "INNER";
    }

    private void aggregateJoin(Map<String, JoinAggregation> aggregations,
                              JoinRelationshipDetail detail, String queryText) {
        String key = detail.getKey();
        String queryHash = String.valueOf(queryText.hashCode());

        JoinAggregation agg = aggregations.computeIfAbsent(key, k -> new JoinAggregation(detail));
        agg.joinCount++;
        agg.distinctQueries.add(queryHash);
        if (agg.sampleQuery == null) {
            agg.sampleQuery = truncateQuery(queryText);
        }
    }

    private String truncateQuery(String query) {
        if (query == null) return null;
        return query.length() > 500 ? query.substring(0, 500) + "..." : query;
    }

    /**
     * Process SlowQueryHistory to extract queries and find JOIN patterns.
     * SlowQueryHistory stores analysisData as JSON containing SlowQueryAnalysis with topSlowQueries.
     */
    private int processSlowQueryHistory(String connectionId, Map<String, JoinAggregation> joinAggregations) {
        int processedQueries = 0;

        try {
            // Get recent slow query history entries
            Optional<SlowQueryHistory> latestHistory = slowQueryHistoryRepository
                .findFirstByConnectionIdOrderByCreatedAtDesc(connectionId);

            if (latestHistory.isEmpty()) {
                log.debug("No SlowQueryHistory found for connection: {}", connectionId);
                return 0;
            }

            // Parse the analysisData JSON
            SlowQueryHistory history = latestHistory.get();
            String analysisDataJson = history.getAnalysisData();

            if (analysisDataJson == null || analysisDataJson.isEmpty()) {
                return 0;
            }

            SlowQueryAnalysis analysis = objectMapper.readValue(analysisDataJson, SlowQueryAnalysis.class);

            if (analysis.getTopSlowQueries() == null || analysis.getTopSlowQueries().isEmpty()) {
                return 0;
            }

            // Process each slow query
            for (SlowQuery slowQuery : analysis.getTopSlowQueries()) {
                String queryText = slowQuery.getQueryText();

                // Also try normalizedQuery if queryText is parameterized
                if (queryText == null || queryText.trim().isEmpty()) {
                    queryText = slowQuery.getNormalizedQuery();
                }

                if (queryText == null || queryText.trim().isEmpty()) {
                    continue;
                }

                processedQueries++;

                try {
                    List<JoinRelationshipDetail> joins = extractJoinRelationships(queryText);
                    for (JoinRelationshipDetail join : joins) {
                        if (join.isValid()) {
                            aggregateJoin(joinAggregations, join, queryText);
                        }
                    }
                } catch (Exception e) {
                    log.debug("Failed to parse slow query: {}", truncateQuery(queryText));
                }
            }

        } catch (Exception e) {
            log.warn("Failed to process SlowQueryHistory for connection {}: {}", connectionId, e.getMessage());
        }

        return processedQueries;
    }

    /**
     * Get all inferred relationships for a connection.
     */
    public List<InferredTableRelationship> getRelationships(String connectionId) {
        return inferredRelationshipRepository.findByConnectionIdOrderByConfidenceScoreDesc(connectionId);
    }

    /**
     * Get high-confidence relationships for schema classification.
     */
    public List<InferredTableRelationship> getHighConfidenceRelationships(String connectionId, BigDecimal minConfidence) {
        return inferredRelationshipRepository.findHighConfidenceRelationships(connectionId, minConfidence);
    }

    /**
     * Validate or reject a relationship.
     */
    @Transactional
    public InferredTableRelationship updateRelationshipStatus(String relationshipId, String status) {
        InferredTableRelationship relationship = inferredRelationshipRepository.findById(relationshipId)
            .orElseThrow(() -> new IllegalArgumentException("Relationship not found: " + relationshipId));

        relationship.setStatus(status);
        return inferredRelationshipRepository.save(relationship);
    }

    public String getConnectionId(String relationshipId) {
        return inferredRelationshipRepository.findById(relationshipId)
            .map(InferredTableRelationship::getConnectionId)
            .orElseThrow(() -> new IllegalArgumentException("Relationship not found: " + relationshipId));
    }

    /**
     * Infer relationships from column naming patterns across tables.
     * This is a fallback mechanism when JOINs are not found in queries.
     *
     * Looks for patterns like:
     * - table_a.user_id -> users.id
     * - orders.customer_id -> customers.id
     * - product_id column in any table -> products.id
     */
    private int inferRelationshipsFromColumnPatterns(String connectionId,
                                                      Map<String, JoinAggregation> existingJoins) {
        int addedCount = 0;

        // Get all key column analyses for this connection
        List<KeyColumnAnalysis> allColumns = keyColumnAnalysisRepository
            .findByConnectionIdOrderByImportanceScoreDesc(connectionId);

        if (allColumns.isEmpty()) {
            log.debug("No KeyColumnAnalysis data found for column pattern inference");
            return 0;
        }

        // Build map of table names (case-insensitive)
        Map<String, String> tableNames = new HashMap<>(); // lowercase -> actual name
        for (KeyColumnAnalysis col : allColumns) {
            String tableName = col.getTableName();
            tableNames.put(tableName.toLowerCase(), tableName);
        }

        // Find columns with FK-like names that match other table names
        for (KeyColumnAnalysis col : allColumns) {
            String columnName = col.getColumnName().toLowerCase();
            String sourceTable = col.getTableName();

            // Check if column looks like a FK (ends with _id, _key, _fk, _ref)
            if (!FK_SUFFIX_PATTERN.matcher(columnName).matches()) {
                continue;
            }

            // Extract the base name (e.g., "user" from "user_id", "booking" from "bookingid")
            String baseName = columnName
                .replaceAll("_(id|key|fk|ref)$", "")  // user_id -> user
                .replaceAll("id$", "")                 // bookingid -> booking (but not "id" alone)
                .replaceAll("^(fk_|ref_)", "");

            // Skip if we ended up with empty or very short base name
            if (baseName.length() < 2) {
                continue;
            }

            // Try to find a matching table
            String targetTable = findTargetTable(baseName, tableNames, sourceTable);

            // If not found, try stripping common prefixes (e.g., "pm_hotel" -> "customer")
            if (targetTable == null && baseName.contains("_")) {
                // Try the part after the prefix (e.g., "pm_hotel" -> "customer")
                int lastUnderscore = baseName.lastIndexOf('_');
                if (lastUnderscore > 0) {
                    String suffix = baseName.substring(lastUnderscore + 1);
                    targetTable = findTargetTable(suffix, tableNames, sourceTable);
                }
                // Also try each segment
                if (targetTable == null) {
                    String[] parts = baseName.split("_");
                    for (String part : parts) {
                        if (part.length() > 2) { // Skip very short parts like "pm"
                            targetTable = findTargetTable(part, tableNames, sourceTable);
                            if (targetTable != null) break;
                        }
                    }
                }
            }

            // Handle columns like "bookingid" (no underscore before id suffix)
            if (targetTable == null && !baseName.contains("_")) {
                // Try common entity names within the column
                for (String tableLower : tableNames.keySet()) {
                    String tableSingular = tableLower.endsWith("s") ?
                        tableLower.substring(0, tableLower.length() - 1) : tableLower;
                    if (baseName.endsWith(tableSingular) || baseName.endsWith(tableLower)) {
                        String candidate = tableNames.get(tableLower);
                        if (!candidate.equalsIgnoreCase(sourceTable)) {
                            targetTable = candidate;
                            break;
                        }
                    }
                }
            }

            // Skip if no target table found or it's a self-reference
            if (targetTable == null || targetTable.equalsIgnoreCase(sourceTable)) {
                continue;
            }

            // Create a join relationship detail
            String sourceColumn = col.getColumnName();
            String targetColumn = "id"; // Assume PK is named 'id'

            // Check if this relationship already exists from JOIN analysis
            String key = sourceTable.toLowerCase() + "." + sourceColumn.toLowerCase() + "->" +
                        targetTable.toLowerCase() + "." + targetColumn.toLowerCase();

            if (existingJoins.containsKey(key)) {
                continue; // Already have this from JOIN patterns
            }

            // Create the join detail
            JoinRelationshipDetail detail = JoinRelationshipDetail.builder()
                .leftTable(sourceTable)
                .leftColumn(sourceColumn)
                .rightTable(targetTable)
                .rightColumn(targetColumn)
                .joinType("INFERRED_FK")
                .sourceTable(sourceTable)
                .sourceColumn(sourceColumn)
                .targetTable(targetTable)
                .targetColumn(targetColumn)
                .selfJoin(false)
                .originalQuery("Inferred from column naming pattern: " + sourceTable + "." + sourceColumn)
                .build();

            // Add to aggregations with lower confidence (no actual JOIN observed)
            JoinAggregation agg = new JoinAggregation(detail);
            agg.joinCount = 1;
            agg.distinctQueries.add("column_pattern_inference");
            agg.sampleQuery = "Inferred from column naming pattern: " + sourceTable + "." + sourceColumn +
                             " matches table " + targetTable;

            existingJoins.put(key, agg);
            addedCount++;

            log.debug("Inferred relationship from column pattern: {}.{} -> {}.{}",
                     sourceTable, sourceColumn, targetTable, targetColumn);
        }

        return addedCount;
    }

    /**
     * Infer relationships from database index metadata.
     *
     * Indexes often reveal relationships:
     * 1. An index on (user_id) suggests user_id is a FK that's frequently queried
     * 2. Composite indexes like (user_id, created_at) suggest user_id is a FK with date filters
     * 3. Non-unique indexes on FK-like columns are strong indicators of relationships
     *
     * This is a powerful inference technique because DBAs create indexes to optimize
     * JOIN performance, so indexes often point to actual relationships.
     */
    private int inferRelationshipsFromIndexes(String connectionId,
                                               Map<String, JoinAggregation> existingJoins) {
        int addedCount = 0;

        try {
            // Get schema metadata with index information
            SchemaMetadata schema = schemaScannerService.scanSchema(connectionId);
            if (schema == null || schema.getTables() == null || schema.getTables().isEmpty()) {
                log.debug("No schema metadata available for index-based inference");
                return 0;
            }

            // Build table name lookup (case-insensitive)
            Map<String, String> tableNames = new HashMap<>();
            for (TableMetadata table : schema.getTables()) {
                tableNames.put(table.getName().toLowerCase(), table.getName());
            }

            // Process each table's indexes
            for (TableMetadata table : schema.getTables()) {
                if (table.getIndexes() == null || table.getIndexes().isEmpty()) {
                    continue;
                }

                String sourceTable = table.getName();

                for (IndexMetadata index : table.getIndexes()) {
                    if (index.getColumns() == null || index.getColumns().isEmpty()) {
                        continue;
                    }

                    // Look at the first column in the index (leading column)
                    String leadingColumn = index.getColumns().get(0).toLowerCase();

                    // Skip if it's likely a PK index or doesn't look like FK
                    if (index.getUnique() != null && index.getUnique()) {
                        // Unique indexes are usually PKs, not FKs
                        // But check if column looks like FK anyway
                        if (PK_PATTERN.matcher(leadingColumn).matches()) {
                            continue;
                        }
                    }

                    // Check if column looks like a FK
                    if (!FK_SUFFIX_PATTERN.matcher(leadingColumn).matches()) {
                        continue;
                    }

                    // Extract base name and find target table
                    String baseName = leadingColumn
                        .replaceAll("_(id|key|fk|ref)$", "")
                        .replaceAll("^(fk_|ref_)", "");

                    String targetTable = findTargetTable(baseName, tableNames, sourceTable);
                    if (targetTable == null) {
                        continue;
                    }

                    // Create relationship key
                    String sourceColumn = index.getColumns().get(0);
                    String targetColumn = "id";
                    String key = sourceTable.toLowerCase() + "." + sourceColumn.toLowerCase() + "->" +
                                targetTable.toLowerCase() + "." + targetColumn.toLowerCase();

                    if (existingJoins.containsKey(key)) {
                        // Boost confidence if we already have this relationship
                        JoinAggregation existing = existingJoins.get(key);
                        existing.joinCount += 2; // Index presence adds confidence
                        existing.distinctQueries.add("index_inference_" + index.getName());
                        continue;
                    }

                    // Create new relationship from index
                    JoinRelationshipDetail detail = JoinRelationshipDetail.builder()
                        .leftTable(sourceTable)
                        .leftColumn(sourceColumn)
                        .rightTable(targetTable)
                        .rightColumn(targetColumn)
                        .joinType("INFERRED_INDEX")
                        .sourceTable(sourceTable)
                        .sourceColumn(sourceColumn)
                        .targetTable(targetTable)
                        .targetColumn(targetColumn)
                        .selfJoin(false)
                        .originalQuery("Inferred from index: " + index.getName() + " on " +
                                      sourceTable + "(" + String.join(", ", index.getColumns()) + ")")
                        .build();

                    JoinAggregation agg = new JoinAggregation(detail);
                    agg.joinCount = 2; // Higher base confidence for indexed FK
                    agg.distinctQueries.add("index_inference_" + index.getName());
                    agg.sampleQuery = "Inferred from index " + index.getName() + ": " +
                                     sourceTable + "." + sourceColumn + " -> " + targetTable + "." + targetColumn;

                    existingJoins.put(key, agg);
                    addedCount++;

                    log.debug("Inferred relationship from index {}: {}.{} -> {}.{}",
                             index.getName(), sourceTable, sourceColumn, targetTable, targetColumn);
                }
            }

        } catch (Exception e) {
            log.warn("Failed to infer relationships from indexes for connection {}: {}", connectionId, e.getMessage());
        }

        return addedCount;
    }

    /**
     * Helper to find target table name from FK column base name.
     */
    private String findTargetTable(String baseName, Map<String, String> tableNames, String sourceTable) {
        // Try exact match
        if (tableNames.containsKey(baseName)) {
            String target = tableNames.get(baseName);
            if (!target.equalsIgnoreCase(sourceTable)) {
                return target;
            }
        }
        // Try plural form
        if (tableNames.containsKey(baseName + "s")) {
            String target = tableNames.get(baseName + "s");
            if (!target.equalsIgnoreCase(sourceTable)) {
                return target;
            }
        }
        // Try singular form
        if (baseName.endsWith("s") && tableNames.containsKey(baseName.substring(0, baseName.length() - 1))) {
            String target = tableNames.get(baseName.substring(0, baseName.length() - 1));
            if (!target.equalsIgnoreCase(sourceTable)) {
                return target;
            }
        }
        // Try with common prefix/suffix patterns
        for (String tableLower : tableNames.keySet()) {
            if ((tableLower.startsWith(baseName) || tableLower.endsWith(baseName) ||
                 baseName.startsWith(tableLower.replace("_", "")) ||
                 tableLower.replace("_", "").equals(baseName)) &&
                !tableNames.get(tableLower).equalsIgnoreCase(sourceTable)) {
                return tableNames.get(tableLower);
            }
        }
        return null;
    }

    /**
     * Validate inferred relationships by sampling actual data values.
     * For each potential FK column, checks if its values exist in the target table's PK.
     * High overlap (>70%) increases confidence score.
     *
     * This is a powerful technique because it uses actual data, not just naming conventions.
     */
    private int validateRelationshipsWithDataCorrelation(String connectionId,
                                                          Map<String, JoinAggregation> joinAggregations) {
        int validatedCount = 0;

        // Only validate low-confidence relationships (from column patterns)
        List<Map.Entry<String, JoinAggregation>> lowConfidenceJoins = joinAggregations.entrySet().stream()
            .filter(e -> e.getValue().distinctQueries.contains("column_pattern_inference"))
            .limit(20) // Limit to avoid too many queries
            .collect(Collectors.toList());

        if (lowConfidenceJoins.isEmpty()) {
            return 0;
        }

        log.info("Validating {} low-confidence relationships with data correlation", lowConfidenceJoins.size());

        for (Map.Entry<String, JoinAggregation> entry : lowConfidenceJoins) {
            JoinAggregation agg = entry.getValue();
            JoinRelationshipDetail detail = agg.detail;

            try {
                // Sample distinct values from source column
                String sampleQuery = String.format(
                    "SELECT DISTINCT %s FROM %s WHERE %s IS NOT NULL LIMIT 100",
                    detail.getSourceColumn(),
                    detail.getSourceTable(),
                    detail.getSourceColumn()
                );

                var sourceResult = queryExecutorService.executeQuery(connectionId, new QueryRequest(sampleQuery, 100, null));
                if (sourceResult == null || sourceResult.getRows() == null || sourceResult.getRows().isEmpty()) {
                    continue;
                }

                Set<String> sourceValues = sourceResult.getRows().stream()
                    .map(row -> row.isEmpty() ? null : String.valueOf(row.get(0)))
                    .filter(v -> v != null && !v.equals("null"))
                    .collect(Collectors.toSet());

                if (sourceValues.isEmpty()) {
                    continue;
                }

                // Check how many exist in target table
                String targetColumn = detail.getTargetColumn();
                String targetTable = detail.getTargetTable();

                // Build IN clause (limit to 50 values to avoid huge queries)
                List<String> valuesToCheck = sourceValues.stream().limit(50).collect(Collectors.toList());
                String inClause = valuesToCheck.stream()
                    .map(v -> "'" + v.replace("'", "''") + "'")
                    .collect(Collectors.joining(","));

                String checkQuery = String.format(
                    "SELECT COUNT(DISTINCT %s) as cnt FROM %s WHERE %s IN (%s)",
                    targetColumn, targetTable, targetColumn, inClause
                );

                var targetResult = queryExecutorService.executeQuery(connectionId, new QueryRequest(checkQuery, 1, null));
                if (targetResult == null || targetResult.getRows() == null || targetResult.getRows().isEmpty()) {
                    continue;
                }

                int matchCount = 0;
                try {
                    Object countVal = targetResult.getRows().get(0).get(0);
                    matchCount = countVal instanceof Number ? ((Number) countVal).intValue() :
                                Integer.parseInt(String.valueOf(countVal));
                } catch (Exception e) {
                    continue;
                }

                // Calculate match percentage
                double matchPercentage = (double) matchCount / valuesToCheck.size() * 100;

                log.debug("Data correlation for {}.{} -> {}.{}: {}% match ({}/{})",
                         detail.getSourceTable(), detail.getSourceColumn(),
                         detail.getTargetTable(), detail.getTargetColumn(),
                         String.format("%.1f", matchPercentage), matchCount, valuesToCheck.size());

                // If high match, boost confidence
                if (matchPercentage >= 70) {
                    // Add bonus to join count based on match percentage
                    int bonus = (int) (matchPercentage / 10); // 70% = +7, 100% = +10
                    agg.joinCount += bonus;
                    agg.distinctQueries.add("data_correlation_validated");
                    agg.sampleQuery = String.format(
                        "Data correlation: %.0f%% of %s.%s values exist in %s.%s",
                        matchPercentage, detail.getSourceTable(), detail.getSourceColumn(),
                        detail.getTargetTable(), detail.getTargetColumn()
                    );
                    validatedCount++;

                    log.info("Validated relationship via data correlation: {}.{} -> {}.{} ({}% match)",
                            detail.getSourceTable(), detail.getSourceColumn(),
                            detail.getTargetTable(), detail.getTargetColumn(),
                            String.format("%.0f", matchPercentage));
                }

            } catch (Exception e) {
                log.debug("Failed to validate relationship {}: {}", entry.getKey(), e.getMessage());
            }
        }

        return validatedCount;
    }

    /**
     * Helper class for aggregating join patterns.
     */
    private static class JoinAggregation {
        JoinRelationshipDetail detail;
        int joinCount = 0;
        Set<String> distinctQueries = new HashSet<>();
        String sampleQuery;

        JoinAggregation(JoinAggregation detail) {
            this.detail = detail.detail;
        }

        JoinAggregation(JoinRelationshipDetail detail) {
            this.detail = detail;
        }
    }
}
