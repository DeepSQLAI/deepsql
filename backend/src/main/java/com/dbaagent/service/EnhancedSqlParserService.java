package com.dbaagent.service;

import com.dbaagent.dto.ColumnUsageDetail;
import com.dbaagent.dto.ColumnUsageExtraction;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.*;
import net.sf.jsqlparser.expression.operators.conditional.AndExpression;
import net.sf.jsqlparser.expression.operators.conditional.OrExpression;
import net.sf.jsqlparser.expression.operators.relational.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.statement.update.Update;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enhanced SQL Parser using JSQLParser library to extract column usage patterns.
 * Identifies columns used in JOINs, WHERE clauses, GROUP BY, and ORDER BY operations.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class EnhancedSqlParserService {

    /**
     * Extract column usage from SQL query
     */
    public ColumnUsageExtraction extractColumnUsage(String sql) {
        ColumnUsageExtraction extraction = ColumnUsageExtraction.builder().build();

        if (sql == null || sql.trim().isEmpty()) {
            return extraction;
        }

        // Preprocess to strip MySQL slow query prefixes
        String cleanedSql = stripMySqlSlowQueryPrefixes(sql);

        try {
            // Try JSQLParser first
            Statement stmt = CCJSqlParserUtil.parse(cleanedSql);
            if (stmt instanceof Select) {
                parseSelectStatement((Select) stmt, extraction);
            }
        } catch (JSQLParserException e) {
            log.debug("JSQLParser failed, falling back to regex: {}", e.getMessage());
            // Fall back to regex parsing
            regexFallbackParsing(cleanedSql, extraction);
        } catch (Exception e) {
            log.warn("Error parsing SQL: {}", e.getMessage());
            regexFallbackParsing(cleanedSql, extraction);
        }

        return extraction;
    }

    /**
     * Strip MySQL slow query log prefixes like:
     * - SET timestamp=1234567890;
     * - use database_name;
     * - USE database_name;
     */
    private String stripMySqlSlowQueryPrefixes(String sql) {
        if (sql == null) return null;

        String cleaned = sql.trim();

        // Remove "SET timestamp=...;" prefix (case-insensitive)
        cleaned = cleaned.replaceAll("(?i)^\\s*SET\\s+timestamp\\s*=\\s*\\d+\\s*;\\s*", "");

        // Remove "use database;" prefix (case-insensitive)
        cleaned = cleaned.replaceAll("(?i)^\\s*USE\\s+[\\w_]+\\s*;\\s*", "");

        // Remove multiple SET statements that might appear
        cleaned = cleaned.replaceAll("(?i)^\\s*SET\\s+[^;]+;\\s*", "");

        return cleaned.trim();
    }

    private void parseSelectStatement(Select select, ColumnUsageExtraction extraction) {
        if (select.getPlainSelect() != null) {
            PlainSelect plainSelect = select.getPlainSelect();

            // Build alias map
            Map<String, String> aliasMap = buildAliasMap(plainSelect);

            // Extract JOIN columns
            extractJoinColumns(plainSelect, aliasMap, extraction);

            // Extract WHERE columns
            if (plainSelect.getWhere() != null) {
                extractWhereColumns(plainSelect.getWhere(), aliasMap, extraction);
            }

            // Extract GROUP BY columns
            if (plainSelect.getGroupBy() != null) {
                extractGroupByColumns(plainSelect.getGroupBy(), aliasMap, extraction);
            }

            // Extract ORDER BY columns
            if (plainSelect.getOrderByElements() != null) {
                extractOrderByColumns(plainSelect.getOrderByElements(), aliasMap, extraction);
            }
        }
    }

    private Map<String, String> buildAliasMap(PlainSelect plainSelect) {
        // Use case-insensitive map for alias lookups
        Map<String, String> aliasMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        extractAliasesFromSelect(plainSelect, aliasMap);
        return aliasMap;
    }

    /**
     * Recursively extract table aliases from a SELECT statement including subqueries
     */
    private void extractAliasesFromSelect(PlainSelect plainSelect, Map<String, String> aliasMap) {
        // Main table from FROM clause
        FromItem fromItem = plainSelect.getFromItem();
        extractAliasFromFromItem(fromItem, aliasMap);

        // Joined tables (includes comma-separated tables as implicit joins)
        if (plainSelect.getJoins() != null) {
            for (Join join : plainSelect.getJoins()) {
                extractAliasFromFromItem(join.getRightItem(), aliasMap);

                // Check ON expressions for subqueries
                if (join.getOnExpression() != null) {
                    extractAliasesFromExpression(join.getOnExpression(), aliasMap);
                }
            }
        }

        // Check WHERE clause for subqueries
        if (plainSelect.getWhere() != null) {
            extractAliasesFromExpression(plainSelect.getWhere(), aliasMap);
        }

        // Check SELECT items for subqueries
        if (plainSelect.getSelectItems() != null) {
            for (SelectItem item : plainSelect.getSelectItems()) {
                if (item.getExpression() != null) {
                    extractAliasesFromExpression(item.getExpression(), aliasMap);
                }
            }
        }
    }

    /**
     * Extract alias from a FROM item (table, subquery, etc.)
     */
    private void extractAliasFromFromItem(FromItem fromItem, Map<String, String> aliasMap) {
        if (fromItem == null) return;

        if (fromItem instanceof Table) {
            Table table = (Table) fromItem;
            String tableName = table.getName();
            if (table.getAlias() != null) {
                aliasMap.put(table.getAlias().getName(), tableName);
            }
            aliasMap.put(tableName, tableName); // Self-reference
        } else if (fromItem instanceof ParenthesedSelect) {
            // Subquery in FROM clause - recurse into it
            ParenthesedSelect subSelect = (ParenthesedSelect) fromItem;
            if (subSelect.getSelect() != null && subSelect.getSelect().getPlainSelect() != null) {
                extractAliasesFromSelect(subSelect.getSelect().getPlainSelect(), aliasMap);
            }
        }
    }

    /**
     * Extract aliases from expressions (handles subqueries in WHERE, etc.)
     */
    private void extractAliasesFromExpression(Expression expr, Map<String, String> aliasMap) {
        if (expr == null) return;

        if (expr instanceof ParenthesedSelect) {
            ParenthesedSelect subSelect = (ParenthesedSelect) expr;
            if (subSelect.getSelect() != null && subSelect.getSelect().getPlainSelect() != null) {
                extractAliasesFromSelect(subSelect.getSelect().getPlainSelect(), aliasMap);
            }
        } else if (expr instanceof InExpression) {
            InExpression inExpr = (InExpression) expr;
            if (inExpr.getRightExpression() instanceof ParenthesedSelect) {
                ParenthesedSelect subSelect = (ParenthesedSelect) inExpr.getRightExpression();
                if (subSelect.getSelect() != null && subSelect.getSelect().getPlainSelect() != null) {
                    extractAliasesFromSelect(subSelect.getSelect().getPlainSelect(), aliasMap);
                }
            }
        } else if (expr instanceof ExistsExpression) {
            ExistsExpression existsExpr = (ExistsExpression) expr;
            if (existsExpr.getRightExpression() instanceof ParenthesedSelect) {
                ParenthesedSelect subSelect = (ParenthesedSelect) existsExpr.getRightExpression();
                if (subSelect.getSelect() != null && subSelect.getSelect().getPlainSelect() != null) {
                    extractAliasesFromSelect(subSelect.getSelect().getPlainSelect(), aliasMap);
                }
            }
        } else if (expr instanceof BinaryExpression) {
            BinaryExpression binExpr = (BinaryExpression) expr;
            extractAliasesFromExpression(binExpr.getLeftExpression(), aliasMap);
            extractAliasesFromExpression(binExpr.getRightExpression(), aliasMap);
        }
    }

    private void extractJoinColumns(PlainSelect plainSelect, Map<String, String> aliasMap,
                                     ColumnUsageExtraction extraction) {
        if (plainSelect.getJoins() == null) return;

        for (Join join : plainSelect.getJoins()) {
            if (join.getOnExpression() != null) {
                Expression onExpr = join.getOnExpression();
                extractColumnsFromExpression(onExpr, aliasMap, extraction.getJoinColumns(), "=");
            }
        }
    }

    private void extractWhereColumns(Expression where, Map<String, String> aliasMap,
                                      ColumnUsageExtraction extraction) {
        extractColumnsFromExpression(where, aliasMap, extraction.getWhereColumns(), null);
    }

    private void extractGroupByColumns(GroupByElement groupBy, Map<String, String> aliasMap,
                                        ColumnUsageExtraction extraction) {
        if (groupBy != null && groupBy.getGroupByExpressionList() != null) {
            for (Object exprObj : groupBy.getGroupByExpressionList().getExpressions()) {
                if (exprObj instanceof Expression) {
                    Expression expr = (Expression) exprObj;
                    if (expr instanceof Column) {
                        Column col = (Column) expr;
                        ColumnUsageDetail detail = createColumnDetail(col, aliasMap, "GROUP_BY", null);
                        if (detail != null) {
                            extraction.getGroupByColumns().add(detail);
                        }
                    }
                }
            }
        }
    }

    private void extractOrderByColumns(List<OrderByElement> orderByElements, Map<String, String> aliasMap,
                                        ColumnUsageExtraction extraction) {
        for (OrderByElement element : orderByElements) {
            Expression expr = element.getExpression();
            if (expr instanceof Column) {
                Column col = (Column) expr;
                ColumnUsageDetail detail = createColumnDetail(col, aliasMap, "ORDER_BY", null);
                if (detail != null) {
                    extraction.getOrderByColumns().add(detail);
                }
            }
        }
    }

    private void extractColumnsFromExpression(Expression expr, Map<String, String> aliasMap,
                                               List<ColumnUsageDetail> targetList, String defaultOp) {
        if (expr == null) return;

        if (expr instanceof BinaryExpression) {
            BinaryExpression binExpr = (BinaryExpression) expr;
            String operation = defaultOp != null ? defaultOp : binExpr.getStringExpression();

            // Left side
            if (binExpr.getLeftExpression() instanceof Column) {
                Column col = (Column) binExpr.getLeftExpression();
                ColumnUsageDetail detail = createColumnDetail(col, aliasMap,
                        defaultOp != null ? "JOIN" : "WHERE", operation);
                if (detail != null) {
                    targetList.add(detail);
                }
            }

            // Right side
            if (binExpr.getRightExpression() instanceof Column) {
                Column col = (Column) binExpr.getRightExpression();
                ColumnUsageDetail detail = createColumnDetail(col, aliasMap,
                        defaultOp != null ? "JOIN" : "WHERE", operation);
                if (detail != null) {
                    targetList.add(detail);
                }
            }

            // Recurse for nested expressions
            extractColumnsFromExpression(binExpr.getLeftExpression(), aliasMap, targetList, defaultOp);
            extractColumnsFromExpression(binExpr.getRightExpression(), aliasMap, targetList, defaultOp);

        } else if (expr instanceof InExpression) {
            InExpression inExpr = (InExpression) expr;
            if (inExpr.getLeftExpression() instanceof Column) {
                Column col = (Column) inExpr.getLeftExpression();
                ColumnUsageDetail detail = createColumnDetail(col, aliasMap, "WHERE", "IN");
                if (detail != null) {
                    targetList.add(detail);
                }
            }
        } else if (expr instanceof LikeExpression) {
            LikeExpression likeExpr = (LikeExpression) expr;
            if (likeExpr.getLeftExpression() instanceof Column) {
                Column col = (Column) likeExpr.getLeftExpression();
                ColumnUsageDetail detail = createColumnDetail(col, aliasMap, "WHERE", "LIKE");
                if (detail != null) {
                    targetList.add(detail);
                }
            }
        } else if (expr instanceof Between) {
            Between between = (Between) expr;
            if (between.getLeftExpression() instanceof Column) {
                Column col = (Column) between.getLeftExpression();
                ColumnUsageDetail detail = createColumnDetail(col, aliasMap, "WHERE", "BETWEEN");
                if (detail != null) {
                    targetList.add(detail);
                }
            }
        }
    }

    private ColumnUsageDetail createColumnDetail(Column col, Map<String, String> aliasMap,
                                                  String usageType, String operation) {
        String columnName = col.getColumnName();
        String tableName = null;

        if (col.getTable() != null) {
            String tableRef = col.getTable().getName();
            // Try to resolve the alias to actual table name
            String resolved = aliasMap.get(tableRef);
            if (resolved != null) {
                tableName = resolved;
            } else {
                // Check if this looks like a subquery/derived table alias (short, not in alias map)
                // Subquery aliases are typically short (1-4 chars) like dt, bt, t1, etc.
                if (isLikelySubqueryAlias(tableRef)) {
                    // Skip columns from subquery aliases - return null to filter them out
                    log.debug("Skipping likely subquery alias: {}", tableRef);
                    return null;
                }
                // Use as-is (might be actual table name without alias)
                tableName = tableRef;
            }
        }

        return ColumnUsageDetail.builder()
                .tableName(tableName)
                .columnName(columnName)
                .usageType(usageType)
                .operation(operation)
                .build();
    }

    /**
     * Check if a table reference looks like a subquery/derived table alias
     * These are typically short (1-4 chars) and follow patterns like: t, t1, dt, bt, sp4, rms, tmp, etc.
     */
    private boolean isLikelySubqueryAlias(String ref) {
        if (ref == null || ref.length() > 4) return false;

        String lower = ref.toLowerCase();

        // Single letter alias (t, u, p, etc.)
        if (lower.length() == 1 && Character.isLetter(lower.charAt(0))) {
            return true;
        }

        // Two characters: letter+letter or letter+number (dt, bt, p1, t2, etc.)
        if (lower.length() == 2) {
            char c0 = lower.charAt(0);
            char c1 = lower.charAt(1);
            if (Character.isLetter(c0) && (Character.isLetter(c1) || Character.isDigit(c1))) {
                return true;
            }
        }

        // Three characters: all letters or ending with number (rms, tmp, sp4, tb1, etc.)
        if (lower.length() == 3) {
            boolean allLetters = lower.chars().allMatch(Character::isLetter);
            boolean endsWithDigit = Character.isDigit(lower.charAt(2)) &&
                                    Character.isLetter(lower.charAt(0)) &&
                                    Character.isLetter(lower.charAt(1));
            if (allLetters || endsWithDigit) {
                return true;
            }
        }

        // Four characters: common alias patterns (cbin, bids, etc.)
        // Only if all lowercase letters (real tables usually have underscores or mixed case)
        if (lower.length() == 4) {
            boolean allLowerLetters = lower.chars().allMatch(c -> Character.isLetter(c) && Character.isLowerCase(c));
            boolean endsWithDigit = Character.isDigit(lower.charAt(3));
            if (allLowerLetters || endsWithDigit) {
                return true;
            }
        }

        return false;
    }

    /**
     * Fallback regex-based parsing when JSQLParser fails
     */
    private void regexFallbackParsing(String sql, ColumnUsageExtraction extraction) {
        // Build alias map from SQL using regex
        Map<String, String> aliasMap = buildAliasMapFromRegex(sql);

        // Extract JOIN columns (basic)
        Pattern joinPattern = Pattern.compile("JOIN\\s+\\w+.*?ON\\s+([\\w.]+)\\s*=\\s*([\\w.]+)", Pattern.CASE_INSENSITIVE);
        Matcher joinMatcher = joinPattern.matcher(sql);
        while (joinMatcher.find()) {
            String col1 = joinMatcher.group(1);
            String col2 = joinMatcher.group(2);
            ColumnUsageDetail detail1 = parseColumnRef(col1, "JOIN", "=", aliasMap);
            ColumnUsageDetail detail2 = parseColumnRef(col2, "JOIN", "=", aliasMap);
            if (detail1 != null) extraction.getJoinColumns().add(detail1);
            if (detail2 != null) extraction.getJoinColumns().add(detail2);
        }

        // Extract WHERE columns (basic)
        Pattern wherePattern = Pattern.compile("WHERE.*?([\\w.]+)\\s*[=<>]", Pattern.CASE_INSENSITIVE);
        Matcher whereMatcher = wherePattern.matcher(sql);
        while (whereMatcher.find()) {
            String col = whereMatcher.group(1);
            ColumnUsageDetail detail = parseColumnRef(col, "WHERE", "=", aliasMap);
            if (detail != null) extraction.getWhereColumns().add(detail);
        }

        // Extract GROUP BY columns
        Pattern groupByPattern = Pattern.compile("GROUP\\s+BY\\s+([\\w.,\\s]+)", Pattern.CASE_INSENSITIVE);
        Matcher groupByMatcher = groupByPattern.matcher(sql);
        if (groupByMatcher.find()) {
            String cols = groupByMatcher.group(1);
            for (String col : cols.split(",")) {
                ColumnUsageDetail detail = parseColumnRef(col.trim(), "GROUP_BY", null, aliasMap);
                if (detail != null) extraction.getGroupByColumns().add(detail);
            }
        }

        // Extract ORDER BY columns
        Pattern orderByPattern = Pattern.compile("ORDER\\s+BY\\s+([\\w.,\\s]+)", Pattern.CASE_INSENSITIVE);
        Matcher orderByMatcher = orderByPattern.matcher(sql);
        if (orderByMatcher.find()) {
            String cols = orderByMatcher.group(1);
            for (String col : cols.split(",")) {
                String cleanCol = col.trim().replaceAll("\\s+(ASC|DESC).*", "");
                ColumnUsageDetail detail = parseColumnRef(cleanCol, "ORDER_BY", null, aliasMap);
                if (detail != null) extraction.getOrderByColumns().add(detail);
            }
        }
    }

    /**
     * Build alias map from SQL using regex patterns
     * Matches patterns like: FROM table_name alias, JOIN table_name AS alias, etc.
     */
    private Map<String, String> buildAliasMapFromRegex(String sql) {
        Map<String, String> aliasMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);

        // Pattern 1: table_name AS alias (anywhere in SQL)
        Pattern asPattern = Pattern.compile(
            "\\b(\\w+)\\s+AS\\s+(\\w+)\\b",
            Pattern.CASE_INSENSITIVE
        );
        Matcher asMatcher = asPattern.matcher(sql);
        while (asMatcher.find()) {
            String tableName = asMatcher.group(1);
            String alias = asMatcher.group(2);
            if (!isKeyword(alias) && !isKeyword(tableName)) {
                aliasMap.put(alias, tableName);
            }
        }

        // Pattern 2: FROM table_name alias (without AS)
        // Matches: FROM users u, FROM ORDER_ITEMS oi
        Pattern fromPattern = Pattern.compile(
            "FROM\\s+(\\w+)\\s+(\\w+)(?=\\s*(?:,|WHERE|JOIN|LEFT|RIGHT|INNER|OUTER|CROSS|NATURAL|GROUP|ORDER|HAVING|LIMIT|UNION|\\)|$))",
            Pattern.CASE_INSENSITIVE
        );
        Matcher fromMatcher = fromPattern.matcher(sql);
        while (fromMatcher.find()) {
            String tableName = fromMatcher.group(1);
            String alias = fromMatcher.group(2);
            if (!isKeyword(alias) && !isKeyword(tableName) && !aliasMap.containsKey(alias)) {
                aliasMap.put(alias, tableName);
            }
        }

        // Pattern 3: JOIN table_name alias (without AS)
        // Matches: JOIN rate_plans r ON, LEFT JOIN customers h ON
        Pattern joinPattern = Pattern.compile(
            "JOIN\\s+(\\w+)\\s+(\\w+)\\s+ON\\b",
            Pattern.CASE_INSENSITIVE
        );
        Matcher joinMatcher = joinPattern.matcher(sql);
        while (joinMatcher.find()) {
            String tableName = joinMatcher.group(1);
            String alias = joinMatcher.group(2);
            if (!isKeyword(alias) && !isKeyword(tableName) && !aliasMap.containsKey(alias)) {
                aliasMap.put(alias, tableName);
            }
        }

        // Pattern 4: Comma-separated tables in FROM: FROM t1 a, t2 b, t3 c
        Pattern commaPattern = Pattern.compile(
            ",\\s*(\\w+)\\s+(\\w+)(?=\\s*(?:,|WHERE|JOIN|LEFT|RIGHT|INNER|OUTER|GROUP|ORDER|HAVING|LIMIT|\\)|$))",
            Pattern.CASE_INSENSITIVE
        );
        Matcher commaMatcher = commaPattern.matcher(sql);
        while (commaMatcher.find()) {
            String tableName = commaMatcher.group(1);
            String alias = commaMatcher.group(2);
            if (!isKeyword(alias) && !isKeyword(tableName) && !aliasMap.containsKey(alias)) {
                aliasMap.put(alias, tableName);
            }
        }

        // Pattern 5: Subquery alias - (SELECT ...) alias or (SELECT ...) AS alias
        // We can't resolve subquery aliases but we can note them

        log.debug("Built alias map from regex: {}", aliasMap);
        return aliasMap;
    }

    /**
     * Check if a string is a SQL keyword (to avoid treating keywords as aliases)
     */
    private boolean isKeyword(String word) {
        Set<String> keywords = Set.of(
            "ON", "WHERE", "AND", "OR", "LEFT", "RIGHT", "INNER", "OUTER", "JOIN",
            "GROUP", "ORDER", "BY", "HAVING", "LIMIT", "OFFSET", "AS", "SELECT",
            "FROM", "SET", "UPDATE", "DELETE", "INSERT", "INTO", "VALUES"
        );
        return keywords.contains(word.toUpperCase());
    }

    private ColumnUsageDetail parseColumnRef(String ref, String usageType, String operation, Map<String, String> aliasMap) {
        String[] parts = ref.split("\\.");
        String tableRef = parts.length > 1 ? parts[0] : null;
        String columnName = parts.length > 1 ? parts[1] : parts[0];

        String tableName = null;
        if (tableRef != null) {
            // Try to resolve the alias to actual table name
            String resolved = aliasMap.get(tableRef);
            if (resolved != null) {
                tableName = resolved;
            } else {
                // Check if this looks like a subquery/derived table alias
                if (isLikelySubqueryAlias(tableRef)) {
                    log.debug("Skipping likely subquery alias in regex parsing: {}", tableRef);
                    return null;
                }
                tableName = tableRef;
            }
        }

        return ColumnUsageDetail.builder()
                .tableName(tableName)
                .columnName(columnName)
                .usageType(usageType)
                .operation(operation)
                .build();
    }

    // ==================== WHERE Clause Literal Extraction ====================

    /**
     * Inner class representing a column-literal pair extracted from a WHERE clause.
     */
    @lombok.Data
    @lombok.Builder
    @lombok.NoArgsConstructor
    @lombok.AllArgsConstructor
    public static class ColumnLiteralPair {
        private String tableName;
        private String columnName;
        private String value;
        private String operation;
    }

    private static final Pattern PARAMETERIZED_PATTERN = Pattern.compile("\\?|\\$\\d+");

    /**
     * Extract WHERE clause literals from a SQL query.
     * Returns column-value pairs for = , IN(...), and BETWEEN operations.
     * Returns empty list if the query is parameterized (contains ? or $1 placeholders).
     */
    public List<ColumnLiteralPair> extractWhereClauseLiterals(String sql) {
        if (sql == null || sql.trim().isEmpty()) {
            return List.of();
        }

        // Skip parameterized queries
        if (PARAMETERIZED_PATTERN.matcher(sql).find()) {
            return List.of();
        }

        String cleanedSql = stripMySqlSlowQueryPrefixes(sql);
        List<ColumnLiteralPair> results = new ArrayList<>();

        try {
            Statement stmt = CCJSqlParserUtil.parse(cleanedSql);
            if (stmt instanceof Select select && select.getPlainSelect() != null) {
                PlainSelect plainSelect = select.getPlainSelect();
                Map<String, String> aliasMap = buildAliasMap(plainSelect);
                if (plainSelect.getWhere() != null) {
                    collectLiteralsFromExpression(plainSelect.getWhere(), aliasMap, results);
                }
            } else if (stmt instanceof Update update) {
                Map<String, String> aliasMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                if (update.getTable() != null) {
                    Table t = update.getTable();
                    if (t.getAlias() != null) {
                        aliasMap.put(t.getAlias().getName(), t.getName());
                    }
                }
                if (update.getWhere() != null) {
                    collectLiteralsFromExpression(update.getWhere(), aliasMap, results);
                }
            } else if (stmt instanceof Delete delete) {
                Map<String, String> aliasMap = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                if (delete.getTable() != null) {
                    Table t = delete.getTable();
                    if (t.getAlias() != null) {
                        aliasMap.put(t.getAlias().getName(), t.getName());
                    }
                }
                if (delete.getWhere() != null) {
                    collectLiteralsFromExpression(delete.getWhere(), aliasMap, results);
                }
            }
        } catch (JSQLParserException e) {
            log.debug("JSQLParser failed for literal extraction, falling back to regex: {}", e.getMessage());
            collectLiteralsViaRegex(cleanedSql, results);
        } catch (Exception e) {
            log.warn("Error extracting WHERE literals: {}", e.getMessage());
            collectLiteralsViaRegex(cleanedSql, results);
        }

        return results;
    }

    /**
     * Recursively traverse the WHERE expression tree to collect Column = Literal pairs.
     */
    private void collectLiteralsFromExpression(Expression expr, Map<String, String> aliasMap,
                                                List<ColumnLiteralPair> results) {
        if (expr == null) return;

        if (expr instanceof AndExpression andExpr) {
            collectLiteralsFromExpression(andExpr.getLeftExpression(), aliasMap, results);
            collectLiteralsFromExpression(andExpr.getRightExpression(), aliasMap, results);
        } else if (expr instanceof OrExpression orExpr) {
            collectLiteralsFromExpression(orExpr.getLeftExpression(), aliasMap, results);
            collectLiteralsFromExpression(orExpr.getRightExpression(), aliasMap, results);
        } else if (expr instanceof Parenthesis paren) {
            collectLiteralsFromExpression(paren.getExpression(), aliasMap, results);
        } else if (expr instanceof ComparisonOperator compOp) {
            // Handle: column = literal, column > literal, etc.
            String op = compOp.getStringExpression();
            if ("=".equals(op)) {
                tryExtractPair(compOp.getLeftExpression(), compOp.getRightExpression(), aliasMap, "=", results);
                tryExtractPair(compOp.getRightExpression(), compOp.getLeftExpression(), aliasMap, "=", results);
            }
        } else if (expr instanceof InExpression inExpr) {
            // Handle: column IN (val1, val2, ...)
            if (inExpr.getLeftExpression() instanceof Column col) {
                String tableName = resolveColumnTable(col, aliasMap);
                Expression rightExpr = inExpr.getRightExpression();
                if (rightExpr instanceof ExpressionList<?> exprList) {
                    for (Object item : exprList) {
                        String val = extractLiteralValue((Expression) item);
                        if (val != null) {
                            results.add(ColumnLiteralPair.builder()
                                .tableName(tableName)
                                .columnName(col.getColumnName())
                                .value(val)
                                .operation("IN")
                                .build());
                        }
                    }
                }
            }
        } else if (expr instanceof Between between) {
            // Handle: column BETWEEN val1 AND val2
            if (between.getLeftExpression() instanceof Column col) {
                String tableName = resolveColumnTable(col, aliasMap);
                String start = extractLiteralValue(between.getBetweenExpressionStart());
                String end = extractLiteralValue(between.getBetweenExpressionEnd());
                if (start != null) {
                    results.add(ColumnLiteralPair.builder()
                        .tableName(tableName)
                        .columnName(col.getColumnName())
                        .value(start)
                        .operation("BETWEEN")
                        .build());
                }
                if (end != null) {
                    results.add(ColumnLiteralPair.builder()
                        .tableName(tableName)
                        .columnName(col.getColumnName())
                        .value(end)
                        .operation("BETWEEN")
                        .build());
                }
            }
        }
    }

    /**
     * Try to extract a column-literal pair where one side is a Column and the other is a literal.
     */
    private void tryExtractPair(Expression columnSide, Expression literalSide,
                                 Map<String, String> aliasMap, String operation,
                                 List<ColumnLiteralPair> results) {
        if (!(columnSide instanceof Column col)) return;
        String val = extractLiteralValue(literalSide);
        if (val == null) return;

        String tableName = resolveColumnTable(col, aliasMap);
        results.add(ColumnLiteralPair.builder()
            .tableName(tableName)
            .columnName(col.getColumnName())
            .value(val)
            .operation(operation)
            .build());
    }

    /**
     * Resolve a Column's table name through the alias map.
     */
    private String resolveColumnTable(Column col, Map<String, String> aliasMap) {
        if (col.getTable() == null) return null;
        String tableRef = col.getTable().getName();
        String resolved = aliasMap.get(tableRef);
        return resolved != null ? resolved : tableRef;
    }

    /**
     * Extract the string representation of a literal value expression.
     */
    private String extractLiteralValue(Expression expr) {
        if (expr instanceof StringValue sv) return sv.getValue();
        if (expr instanceof LongValue lv) return String.valueOf(lv.getValue());
        if (expr instanceof DoubleValue dv) return String.valueOf(dv.getValue());
        if (expr instanceof NullValue) return null;
        return null;
    }

    private static final Pattern REGEX_WHERE_LITERAL = Pattern.compile(
        "(\\w+\\.)??(\\w+)\\s*=\\s*(?:'([^']*)'|(\\d+(?:\\.\\d+)?))",
        Pattern.CASE_INSENSITIVE
    );

    /**
     * Regex fallback for extracting WHERE clause literals from unparseable SQL.
     */
    private void collectLiteralsViaRegex(String sql, List<ColumnLiteralPair> results) {
        // Find the WHERE clause
        int whereIdx = sql.toUpperCase().indexOf("WHERE");
        if (whereIdx < 0) return;

        String whereClause = sql.substring(whereIdx);
        // Trim at GROUP BY, ORDER BY, LIMIT, HAVING
        String trimmed = whereClause.replaceAll("(?i)(GROUP\\s+BY|ORDER\\s+BY|LIMIT|HAVING).*", "");

        // Build alias map from regex
        Map<String, String> aliasMap = buildAliasMapFromRegex(sql);

        Matcher m = REGEX_WHERE_LITERAL.matcher(trimmed);
        while (m.find()) {
            String tableRef = m.group(1) != null ? m.group(1).replace(".", "") : null;
            String columnName = m.group(2);
            String strValue = m.group(3);
            String numValue = m.group(4);
            String value = strValue != null ? strValue : numValue;

            if (value != null && !isKeyword(columnName)) {
                String tableName = tableRef != null ? aliasMap.getOrDefault(tableRef, tableRef) : null;
                results.add(ColumnLiteralPair.builder()
                    .tableName(tableName)
                    .columnName(columnName)
                    .value(value)
                    .operation("=")
                    .build());
            }
        }
    }
}
