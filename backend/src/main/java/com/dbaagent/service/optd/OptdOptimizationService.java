package com.dbaagent.service.optd;

import com.dbaagent.model.ColumnMetadata;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.TableMetadata;
import com.dbaagent.service.SchemaScannerService;
import com.dbaagent.util.QueryNormalizer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.JSQLParserException;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.*;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

import static com.dbaagent.service.optd.OptdModels.OptdColumn;
import static com.dbaagent.service.optd.OptdModels.OptdOptimizeRequest;
import static com.dbaagent.service.optd.OptdModels.OptdOptimizeResponse;
import static com.dbaagent.service.optd.OptdModels.OptdSchema;
import static com.dbaagent.service.optd.OptdModels.OptdTable;

@Service
@RequiredArgsConstructor
@Slf4j
public class OptdOptimizationService {

    private final OptdClient optdClient;
    private final SchemaScannerService schemaScannerService;
    private static final Set<String> AGG_FUNCTIONS = Set.of(
        "SUM",
        "COUNT",
        "MIN",
        "MAX",
        "AVG",
        "STRING_AGG",
        "GROUP_CONCAT"
    );

    public Optional<OptdOptimizeResponse> optimizeQuery(String connectionId, String dbType, String query) {
        if (query == null || query.isBlank()) {
            return Optional.empty();
        }

        try {
            SchemaMetadata schema = schemaScannerService.scanSchema(connectionId);
            Map<String, Set<String>> tableColumns = buildTableColumnMap(schema);
            String normalizedQuery = normalizeForOptd(
                dbType,
                query,
                schema != null ? schema.getDatabaseName() : null,
                tableColumns
            );
            OptdSchema optdSchema = mapSchemaForQuery(schema, query, dbType);

            OptdOptimizeRequest request = OptdOptimizeRequest.builder()
                .dbType(dbType)
                .query(normalizedQuery)
                .schema(optdSchema)
                .build();

            log.debug("optd normalized query (first 300): {}",
                normalizedQuery.substring(0, Math.min(300, normalizedQuery.length())));

            Optional<OptdOptimizeResponse> response = optdClient.optimize(request);

            if (response.isEmpty() && dbType != null && "mysql".equalsIgnoreCase(dbType)) {
                log.info("optd primary request failed, attempting aggressive fallback for connection: {}", connectionId);
                String aggressive = normalizeForOptd(
                    dbType,
                    query,
                    schema != null ? schema.getDatabaseName() : null,
                    tableColumns,
                    true
                );
                if (aggressive != null && !aggressive.equals(normalizedQuery)) {
                    log.debug("optd aggressive fallback query (first 300): {}",
                        aggressive.substring(0, Math.min(300, aggressive.length())));
                    OptdOptimizeRequest retry = OptdOptimizeRequest.builder()
                        .dbType(dbType)
                        .query(aggressive)
                        .schema(optdSchema)
                        .build();
                    response = optdClient.optimize(retry);
                    if (response.isPresent()) {
                        log.info("optd aggressive fallback succeeded for connection: {}", connectionId);
                    } else {
                        log.warn("optd aggressive fallback also failed for connection: {}", connectionId);
                    }
                } else {
                    log.debug("optd aggressive fallback produced same query, skipping retry");
                }
            }

            return response;
        } catch (Exception e) {
            log.debug("optd optimize failed for connection {}: {}", connectionId, e.getMessage());
            return Optional.empty();
        }
    }

    private OptdSchema mapSchema(SchemaMetadata schemaMetadata) {
        List<OptdTable> tables = schemaMetadata.getTables().stream()
            .filter(table -> "table".equalsIgnoreCase(table.getType()) || "view".equalsIgnoreCase(table.getType()))
            .map(this::mapTable)
            .collect(Collectors.toList());

        return OptdSchema.builder()
            .tables(tables)
            .build();
    }

    private OptdSchema mapSchemaForQuery(SchemaMetadata schemaMetadata, String query, String dbType) {
        if (schemaMetadata == null || schemaMetadata.getTables() == null) {
            return OptdSchema.builder().tables(List.of()).build();
        }

        Set<String> referenced = extractReferencedTables(query);
        boolean isMysql = dbType != null && "mysql".equalsIgnoreCase(dbType);
        if (referenced.isEmpty() || (isMysql && hasCommaJoin(query))) {
            List<OptdTable> tables = mapTablesForOptd(schemaMetadata, isMysql, Set.of());
            return OptdSchema.builder()
                .tables(tables)
                .build();
        }

        List<OptdTable> tables = mapTablesForOptd(schemaMetadata, isMysql, referenced);

        if (tables.isEmpty()) {
            return mapSchema(schemaMetadata);
        }

        return OptdSchema.builder()
            .tables(tables)
            .build();
    }

    private List<OptdTable> mapTablesForOptd(
        SchemaMetadata schemaMetadata,
        boolean isMysql,
        Set<String> referenced
    ) {
        java.util.Map<String, OptdTable> unique = new java.util.LinkedHashMap<>();
        for (TableMetadata table : schemaMetadata.getTables()) {
            if (!"table".equalsIgnoreCase(table.getType()) && !"view".equalsIgnoreCase(table.getType())) {
                continue;
            }
            if (referenced != null && !referenced.isEmpty() && !isReferenced(table, referenced)) {
                continue;
            }

            String key = table.getName() != null ? table.getName().toLowerCase(Locale.ROOT) : "";
            if (isMysql && unique.containsKey(key)) {
                continue;
            }

            OptdTable mapped = mapTable(table, isMysql);
            unique.put(isMysql ? key : mapped.getName(), mapped);
        }

        return new java.util.ArrayList<>(unique.values());
    }

    private Map<String, Set<String>> buildTableColumnMap(SchemaMetadata schemaMetadata) {
        Map<String, Set<String>> tableColumns = new HashMap<>();
        if (schemaMetadata == null || schemaMetadata.getTables() == null) {
            return tableColumns;
        }
        for (TableMetadata table : schemaMetadata.getTables()) {
            if (table == null) {
                continue;
            }
            if (!"table".equalsIgnoreCase(table.getType()) && !"view".equalsIgnoreCase(table.getType())) {
                continue;
            }
            Set<String> columns = new HashSet<>();
            if (table.getColumns() != null) {
                for (ColumnMetadata column : table.getColumns()) {
                    if (column == null || column.getName() == null || column.getName().isBlank()) {
                        continue;
                    }
                    columns.add(column.getName().toLowerCase(Locale.ROOT));
                }
            }
            String baseName = normalizeIdentifier(table.getName());
            addTableColumns(tableColumns, baseName, columns);
            if (table.getSchema() != null && !table.getSchema().isBlank()) {
                String qualified = normalizeIdentifier(table.getSchema() + "." + table.getName());
                addTableColumns(tableColumns, qualified, columns);
            }
        }
        return tableColumns;
    }

    private void addTableColumns(Map<String, Set<String>> tableColumns, String key, Set<String> columns) {
        if (key == null || key.isBlank() || columns == null || columns.isEmpty()) {
            return;
        }
        tableColumns.computeIfAbsent(key, k -> new HashSet<>()).addAll(columns);
    }

    private Set<String> extractReferencedTables(String query) {
        if (query == null || query.isBlank()) {
            return Set.of();
        }

        String[] tables = QueryNormalizer.extractTableNames(query);
        if (tables == null || tables.length == 0) {
            return Set.of();
        }

        return Arrays.stream(tables)
            .filter(t -> t != null && !t.isBlank())
            .flatMap(t -> {
                String normalized = normalizeIdentifier(t);
                if (normalized.contains(".")) {
                    String unqualified = normalized.substring(normalized.lastIndexOf('.') + 1);
                    return Arrays.stream(new String[] { normalized, unqualified });
                }
                return Arrays.stream(new String[] { normalized });
            })
            .filter(t -> !t.isBlank())
            .collect(Collectors.toSet());
    }

    private boolean isReferenced(TableMetadata table, Set<String> referenced) {
        String tableName = normalizeIdentifier(table.getName());
        if (referenced.contains(tableName)) {
            return true;
        }
        if (table.getSchema() != null && !table.getSchema().isBlank()) {
            String qualified = normalizeIdentifier(table.getSchema() + "." + table.getName());
            return referenced.contains(qualified);
        }
        return false;
    }

    private boolean hasCommaJoin(String query) {
        if (query == null) {
            return false;
        }
        String upper = query.toUpperCase(Locale.ROOT);
        int fromIdx = upper.indexOf(" FROM ");
        if (fromIdx < 0) {
            return false;
        }
        int searchStart = fromIdx + 6;
        int nextIdx = findNextClauseIndex(upper, searchStart);
        String segment = nextIdx < 0 ? query.substring(searchStart) : query.substring(searchStart, nextIdx);
        return segment.contains(",");
    }

    private String normalizeIdentifier(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.trim();
        if (cleaned.startsWith("`") && cleaned.endsWith("`") && cleaned.length() > 1) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        if (cleaned.startsWith("\"") && cleaned.endsWith("\"") && cleaned.length() > 1) {
            cleaned = cleaned.substring(1, cleaned.length() - 1);
        }
        return cleaned.toLowerCase(Locale.ROOT);
    }

    private String normalizeForOptd(
        String dbType,
        String query,
        String schemaName,
        Map<String, Set<String>> tableColumns
    ) {
        return normalizeForOptd(dbType, query, schemaName, tableColumns, false);
    }

    private String normalizeForOptd(
        String dbType,
        String query,
        String schemaName,
        Map<String, Set<String>> tableColumns,
        boolean aggressive
    ) {
        if (query == null) {
            return null;
        }
        if (dbType == null || !"mysql".equalsIgnoreCase(dbType)) {
            return query;
        }

        String sanitized = QueryNormalizer.sanitize(query);
        sanitized = stripMySqlHints(sanitized);
        sanitized = replaceDerivedTablePlaceholders(sanitized);
        sanitized = replaceParamPlaceholders(sanitized);
        sanitized = replaceDerivedTablePlaceholders(sanitized);
        String rewritten = normalizeMySqlWithParser(sanitized, schemaName, tableColumns, aggressive);
        if (rewritten != null) {
            return rewritten;
        }

        String fallback = sanitized;
        if (schemaName != null && !schemaName.isBlank()) {
            String schemaPrefix = java.util.regex.Pattern.quote(schemaName);
            fallback = fallback.replaceAll("(?i)\\b" + schemaPrefix + "\\.", "");
        }

        // Replace non-standard MySQL functions with more generic forms to help DataFusion planning.
        fallback = fallback.replaceAll("(?i)IFNULL\\s*\\(", "COALESCE(");
        fallback = stripMySqlHints(fallback);
        fallback = fallback.replaceAll("\\s+", " ").trim();

        return fallback;
    }

    private int findNextClauseIndex(String upperSql, int startIdx) {
        int groupIdx = upperSql.indexOf(" GROUP BY ", startIdx);
        int orderIdx = upperSql.indexOf(" ORDER BY ", startIdx);
        int havingIdx = upperSql.indexOf(" HAVING ", startIdx);
        int limitIdx = upperSql.indexOf(" LIMIT ", startIdx);

        int next = -1;
        for (int idx : new int[] { groupIdx, orderIdx, havingIdx, limitIdx }) {
            if (idx >= 0 && (next < 0 || idx < next)) {
                next = idx;
            }
        }
        return next;
    }

    private String replaceDerivedTablePlaceholders(String sql) {
        if (sql == null || sql.isBlank()) {
            return sql;
        }
        String placeholder = "optd_placeholder";
        String replaced = sql;
        replaced = replaced.replaceAll("(?i)\\b(from|join)\\s*\\(\\s*\\?\\s*\\)", "$1 " + placeholder);
        replaced = replaced.replaceAll("(?i)\\b(from|join)\\s+\\?", "$1 " + placeholder);
        replaced = replaced.replaceAll("(?i)\\b(from|join)\\s*\\(\\s*\\$\\d+\\s*\\)", "$1 " + placeholder);
        replaced = replaced.replaceAll("(?i)\\b(from|join)\\s+\\$\\d+", "$1 " + placeholder);
        replaced = replaced.replaceAll("(?i)\\b(from|join)\\s*\\(\\s*\\d+\\s*\\)", "$1 " + placeholder);
        replaced = replaced.replaceAll("(?i)\\b(from|join)\\s+\\d+", "$1 " + placeholder);
        java.util.regex.Pattern paren = java.util.regex.Pattern.compile("(?i)\\b(from|join)\\s*+\\(([^)]*+)\\)");
        java.util.regex.Matcher matcher = paren.matcher(replaced);
        StringBuffer buffer = new StringBuffer();
        while (matcher.find()) {
            String inner = matcher.group(2);
            if (inner != null && inner.matches("[\\?\\$\\d\\s]++")) {
                matcher.appendReplacement(buffer, matcher.group(1) + " " + placeholder);
            }
        }
        matcher.appendTail(buffer);
        replaced = buffer.toString();
        return replaced;
    }

    private String replaceParamPlaceholders(String sql) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        StringBuilder out = new StringBuilder(sql.length());
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inBacktick = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);

            if (!inDouble && !inBacktick && c == '\'') {
                out.append(c);
                if (inSingle && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    out.append('\'');
                    i++;
                    continue;
                }
                inSingle = !inSingle;
                continue;
            }
            if (!inSingle && !inBacktick && c == '"') {
                inDouble = !inDouble;
                out.append(c);
                continue;
            }
            if (!inSingle && !inDouble && c == '`') {
                inBacktick = !inBacktick;
                out.append(c);
                continue;
            }

            if (!inSingle && !inDouble && !inBacktick) {
                if (c == '?') {
                    out.append('1');
                    continue;
                }
                if (c == '$' && i + 1 < sql.length() && Character.isDigit(sql.charAt(i + 1))) {
                    int j = i + 1;
                    while (j < sql.length() && Character.isDigit(sql.charAt(j))) {
                        j++;
                    }
                    out.append('1');
                    i = j - 1;
                    continue;
                }
            }

            out.append(c);
        }

        return out.toString();
    }

    /**
     * Strip MySQL-specific optimizer hints that DataFusion cannot parse:
     * STRAIGHT_JOIN, FORCE/USE/IGNORE INDEX/KEY hints.
     */
    private String stripMySqlHints(String sql) {
        if (sql == null || sql.isEmpty()) {
            return sql;
        }
        // SELECT STRAIGHT_JOIN ... → SELECT ...
        String result = sql.replaceAll("(?i)\\bSELECT\\s+STRAIGHT_JOIN\\b", "SELECT");
        // STRAIGHT_JOIN as join type → INNER JOIN
        result = result.replaceAll("(?i)\\bSTRAIGHT_JOIN\\b", "INNER JOIN");
        // FORCE/USE/IGNORE INDEX/KEY [FOR JOIN|ORDER BY|GROUP BY] (index_list)
        result = result.replaceAll(
            "(?i)\\b(FORCE|USE|IGNORE)\\s++(INDEX|KEY)\\s++(FOR\\s++(JOIN|ORDER\\s++BY|GROUP\\s++BY)\\s++)?\\([^)]*+\\)",
            ""
        );
        return result;
    }

    private String normalizeMySqlWithParser(
        String sql,
        String schemaName,
        Map<String, Set<String>> tableColumns,
        boolean aggressive
    ) {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            if (!(stmt instanceof Select)) {
                return sql;
            }
            Select select = (Select) stmt;
            if (select.getWithItemsList() != null) {
                java.util.Map<String, Set<String>> requiredWith =
                    aggressive ? collectRequiredOutputsForSelect(select) : java.util.Collections.emptyMap();
                for (WithItem<?> withItem : select.getWithItemsList()) {
                    Object parenthesed = withItem.getParenthesedStatement();
                    if (parenthesed instanceof ParenthesedSelect) {
                        Select withSelect = ((ParenthesedSelect) parenthesed).getSelect();
                        if (withSelect != null) {
                            rewriteSelect(withSelect, schemaName);
                            if (tableColumns != null && !tableColumns.isEmpty()) {
                                qualifySelectColumns(withSelect, tableColumns);
                            }
                            if (aggressive) {
                                Set<String> requiredOutputs = null;
                                String aliasName = withItem.getAliasName();
                                if (aliasName != null) {
                                    requiredOutputs = requiredWith.get(aliasName.toLowerCase(Locale.ROOT));
                                }
                                simplifySelectForPlanner(withSelect, requiredOutputs);
                            }
                        }
                    }
                }
            }
            rewriteSelect(select, schemaName);
            String beforeQualify = select.toString();
            if (tableColumns != null && !tableColumns.isEmpty()) {
                qualifySelectColumns(select, tableColumns);
            }
            String afterQualify = select.toString();
            if (!beforeQualify.equals(afterQualify)) {
                log.info("qualifySelectColumns changed query");
                // Check if be_booking_refund was introduced
                if (!beforeQualify.toLowerCase().contains("be_booking_refund")
                    && afterQualify.toLowerCase().contains("be_booking_refund")) {
                    log.warn("PHANTOM TABLE: be_booking_refund was introduced during column qualification!");
                    log.info("Before qualification (first 500): {}",
                        beforeQualify.substring(0, Math.min(500, beforeQualify.length())));
                    log.info("After qualification (first 500): {}",
                        afterQualify.substring(0, Math.min(500, afterQualify.length())));
                }
            }
            if (aggressive) {
                simplifySelectForPlanner(select);
            }
            return select.toString();
        } catch (JSQLParserException e) {
            log.debug("optd SQL parse failed, falling back to regex normalization: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.debug("optd SQL rewrite failed, falling back to regex normalization: {}", e.getMessage());
            return null;
        }
    }

    private void rewriteSelect(Select select, String schemaName) {
        if (select == null) {
            return;
        }
        if (select instanceof PlainSelect) {
            rewritePlainSelect((PlainSelect) select, schemaName);
            return;
        }
        if (select instanceof ParenthesedSelect) {
            ParenthesedSelect parenthesedSelect = (ParenthesedSelect) select;
            if (parenthesedSelect.getSelect() != null) {
                rewriteSelect(parenthesedSelect.getSelect(), schemaName);
            }
            return;
        }
        if (select instanceof SetOperationList) {
            SetOperationList list = (SetOperationList) select;
            if (list.getSelects() != null) {
                for (Select nested : list.getSelects()) {
                    rewriteSelect(nested, schemaName);
                }
            }
        }
    }

    private void qualifySelectColumns(Select select, Map<String, Set<String>> tableColumns) {
        if (select == null || tableColumns == null || tableColumns.isEmpty()) {
            return;
        }
        if (select instanceof PlainSelect) {
            qualifyPlainSelect((PlainSelect) select, tableColumns);
            return;
        }
        if (select instanceof ParenthesedSelect) {
            ParenthesedSelect parenthesedSelect = (ParenthesedSelect) select;
            if (parenthesedSelect.getSelect() != null) {
                qualifySelectColumns(parenthesedSelect.getSelect(), tableColumns);
            }
            return;
        }
        if (select instanceof SetOperationList) {
            SetOperationList list = (SetOperationList) select;
            if (list.getSelects() != null) {
                for (Select nested : list.getSelects()) {
                    qualifySelectColumns(nested, tableColumns);
                }
            }
        }
    }

    private void qualifyPlainSelect(PlainSelect select, Map<String, Set<String>> tableColumns) {
        if (select == null) {
            return;
        }
        Map<String, String> aliasToTable = collectAliasTables(select);
        log.info("qualifyPlainSelect - aliasToTable: {}", aliasToTable);
        Map<String, Set<String>> aliasColumns = buildAliasColumnMap(aliasToTable, tableColumns);
        log.info("qualifyPlainSelect - aliasColumns keys: {}", aliasColumns.keySet());
        if (aliasColumns.isEmpty()) {
            return;
        }

        ExpressionVisitorAdapter<Void> visitor = new ExpressionVisitorAdapter<>() {
            @Override
            public <S> Void visit(net.sf.jsqlparser.schema.Column column, S context) {
                if (column == null) {
                    return null;
                }
                if (column.getTable() != null && column.getTable().getName() != null
                    && !column.getTable().getName().isBlank()) {
                    return null;
                }
                String name = column.getColumnName();
                if (name == null || name.isBlank()) {
                    return null;
                }
                String resolved = resolveAliasForColumn(name, aliasColumns);
                if (resolved != null) {
                    Table table = new Table();
                    table.setName(resolved);
                    column.setTable(table);
                }
                return null;
            }

            @Override
            public <S> Void visit(ParenthesedSelect parenthesedSelect, S context) {
                if (parenthesedSelect.getSelect() != null) {
                    qualifySelectColumns(parenthesedSelect.getSelect(), tableColumns);
                }
                return null;
            }
        };

        if (select.getSelectItems() != null) {
            for (SelectItem<?> item : select.getSelectItems()) {
                if (item != null && item.getExpression() != null) {
                    item.getExpression().accept(visitor, null);
                }
            }
        }
        if (select.getWhere() != null) {
            select.getWhere().accept(visitor, null);
        }
        if (select.getHaving() != null) {
            select.getHaving().accept(visitor, null);
        }
        if (select.getGroupBy() != null && select.getGroupBy().getGroupByExpressionList() != null) {
            List<Expression> groupByExprs = select.getGroupBy().getGroupByExpressionList().getExpressions();
            if (groupByExprs != null) {
                for (Expression expr : groupByExprs) {
                    if (expr != null) {
                        expr.accept(visitor, null);
                    }
                }
            }
        }
        if (select.getOrderByElements() != null) {
            for (OrderByElement element : select.getOrderByElements()) {
                if (element.getExpression() != null) {
                    element.getExpression().accept(visitor, null);
                }
            }
        }
        if (select.getJoins() != null) {
            for (Join join : select.getJoins()) {
                if (join.getOnExpression() != null) {
                    join.getOnExpression().accept(visitor, null);
                }
                if (join.getOnExpressions() != null) {
                    for (Expression expr : join.getOnExpressions()) {
                        if (expr != null) {
                            expr.accept(visitor, null);
                        }
                    }
                }
            }
        }
    }

    private Map<String, String> collectAliasTables(PlainSelect select) {
        Map<String, String> aliasToTable = new HashMap<>();
        if (select == null) {
            return aliasToTable;
        }
        collectAliasFromItem(select.getFromItem(), aliasToTable);
        if (select.getJoins() != null) {
            for (Join join : select.getJoins()) {
                collectAliasFromItem(join.getRightItem(), aliasToTable);
            }
        }
        return aliasToTable;
    }

    private void collectAliasFromItem(FromItem item, Map<String, String> aliasToTable) {
        if (item == null || aliasToTable == null) {
            return;
        }
        if (item instanceof Table) {
            Table table = (Table) item;
            String baseKey = normalizeIdentifier(table.getName());
            String qualifiedKey = null;
            if (table.getSchemaName() != null && !table.getSchemaName().isBlank()) {
                qualifiedKey = normalizeIdentifier(table.getSchemaName() + "." + table.getName());
            }
            String tableKey = qualifiedKey != null && !qualifiedKey.isBlank() ? qualifiedKey : baseKey;
            Alias alias = table.getAlias();
            String aliasName = alias != null ? alias.getName() : table.getName();
            if (aliasName != null && !aliasName.isBlank()) {
                aliasToTable.put(normalizeIdentifier(aliasName), tableKey);
            }
            if (qualifiedKey != null && baseKey != null && !baseKey.isBlank()) {
                aliasToTable.putIfAbsent(baseKey, tableKey);
            }
            return;
        }
        Alias alias = item.getAlias();
        if (alias != null && alias.getName() != null && !alias.getName().isBlank()) {
            aliasToTable.put(normalizeIdentifier(alias.getName()), null);
        }
    }

    private Map<String, Set<String>> buildAliasColumnMap(
        Map<String, String> aliasToTable,
        Map<String, Set<String>> tableColumns
    ) {
        Map<String, Set<String>> aliasColumns = new HashMap<>();
        if (aliasToTable == null || tableColumns == null) {
            return aliasColumns;
        }
        for (Map.Entry<String, String> entry : aliasToTable.entrySet()) {
            String tableKey = entry.getValue();
            if (tableKey == null) {
                continue;
            }
            Set<String> columns = tableColumns.get(tableKey);
            if (columns == null || columns.isEmpty()) {
                continue;
            }
            aliasColumns.put(entry.getKey(), columns);
        }
        return aliasColumns;
    }

    private String resolveAliasForColumn(String columnName, Map<String, Set<String>> aliasColumns) {
        if (columnName == null || aliasColumns == null || aliasColumns.isEmpty()) {
            return null;
        }
        String key = columnName.toLowerCase(Locale.ROOT);
        List<String> matches = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : aliasColumns.entrySet()) {
            if (entry.getValue().contains(key)) {
                matches.add(entry.getKey());
            }
        }
        if (matches.size() == 1) {
            log.info("resolveAliasForColumn: {} -> {} (single match)", columnName, matches.get(0));
            return matches.get(0);
        }
        if (matches.isEmpty() && aliasColumns.size() == 1) {
            String fallback = aliasColumns.keySet().iterator().next();
            log.info("resolveAliasForColumn: {} -> {} (single-table fallback)", columnName, fallback);
            return fallback;
        }
        if (matches.size() > 1) {
            log.info("resolveAliasForColumn: {} -> null (ambiguous, matches: {})", columnName, matches);
        } else {
            log.info("resolveAliasForColumn: {} -> null (no match)", columnName);
        }
        return null;
    }

    private void simplifySelectForPlanner(Select select) {
        simplifySelectForPlanner(select, null);
    }

    private void simplifySelectForPlanner(Select select, Set<String> requiredOutputs) {
        if (select == null) {
            return;
        }
        if (select instanceof PlainSelect) {
            simplifyPlainSelect((PlainSelect) select, requiredOutputs);
            return;
        }
        if (select instanceof ParenthesedSelect) {
            ParenthesedSelect parenthesedSelect = (ParenthesedSelect) select;
            if (parenthesedSelect.getSelect() != null) {
                simplifySelectForPlanner(parenthesedSelect.getSelect(), requiredOutputs);
            }
            return;
        }
        if (select instanceof SetOperationList) {
            SetOperationList list = (SetOperationList) select;
            if (list.getSelects() != null) {
                for (Select nested : list.getSelects()) {
                    simplifySelectForPlanner(nested, requiredOutputs);
                }
            }
        }
    }

    private void simplifyPlainSelect(PlainSelect select) {
        simplifyPlainSelect(select, null);
    }

    private void simplifyPlainSelect(PlainSelect select, Set<String> requiredOutputs) {
        if (select == null) {
            return;
        }
        select.setOrderByElements(null);
        select.setLimit(null);
        select.setOffset(null);
        select.setFetch(null);

        java.util.Map<String, Set<String>> requiredByAlias = collectRequiredOutputs(select);
        simplifyFromItem(select.getFromItem(), requiredByAlias);
        if (select.getJoins() != null) {
            for (Join join : select.getJoins()) {
                simplifyFromItem(join.getRightItem(), requiredByAlias);
            }
        }

        if (select.getGroupBy() != null && select.getGroupBy().getGroupByExpressionList() != null) {
            List<Expression> groupByExprs = select.getGroupBy().getGroupByExpressionList().getExpressions();
            List<SelectItem<?>> items = new java.util.ArrayList<>();
            // Get primary table alias for qualifying unqualified columns
            String primaryTableAlias = getPrimaryTableAlias(select);
            if (groupByExprs != null && !groupByExprs.isEmpty()) {
                for (Expression expr : groupByExprs) {
                    if (expr != null) {
                        // Qualify unqualified column references to avoid ambiguity
                        Expression qualified = qualifyIfNeeded(expr, primaryTableAlias);
                        items.add(SelectItem.from(qualified));
                    }
                }
            }
            if (items.isEmpty()) {
                items.add(SelectItem.from(new LongValue(1)));
            }
            addRequiredSelectItems(items, requiredOutputs);
            select.setSelectItems(items);
        } else {
            List<SelectItem<?>> items = new java.util.ArrayList<>();
            items.add(SelectItem.from(new LongValue(1)));
            addRequiredSelectItems(items, requiredOutputs);
            select.setSelectItems(items);
        }
    }

    /**
     * Get the primary table alias from a SELECT's FROM clause.
     * Returns the alias (or table name if no alias) of the first simple table.
     * Returns null for subqueries or complex FROM items.
     */
    private String getPrimaryTableAlias(PlainSelect select) {
        if (select == null || select.getFromItem() == null) {
            return null;
        }
        FromItem fromItem = select.getFromItem();
        if (fromItem instanceof Table table) {
            Alias alias = table.getAlias();
            if (alias != null && alias.getName() != null && !alias.getName().isBlank()) {
                return alias.getName();
            }
            return table.getName();
        }
        return null;
    }

    /**
     * Qualify an unqualified column expression with the given table alias.
     * Returns the original expression if it's already qualified or not a column.
     */
    private Expression qualifyIfNeeded(Expression expr, String tableAlias) {
        if (expr == null || tableAlias == null || tableAlias.isBlank()) {
            return expr;
        }
        if (expr instanceof Column column) {
            // Check if already qualified (has a table)
            if (column.getTable() != null && column.getTable().getName() != null
                && !column.getTable().getName().isBlank()) {
                return expr;  // Already qualified
            }
            // Create a new qualified column
            Table table = new Table(tableAlias);
            Column qualified = new Column(table, column.getColumnName());
            log.debug("qualifyIfNeeded: {} -> {}.{}", column.getColumnName(), tableAlias, column.getColumnName());
            return qualified;
        }
        return expr;
    }

    private void simplifyFromItem(FromItem item, java.util.Map<String, Set<String>> requiredByAlias) {
        if (item == null) {
            return;
        }
        Set<String> requiredOutputs = null;
        Alias alias = item.getAlias();
        if (alias != null && alias.getName() != null && requiredByAlias != null) {
            requiredOutputs = requiredByAlias.get(alias.getName().toLowerCase(Locale.ROOT));
        }
        if (item instanceof ParenthesedSelect parenthesedSelect) {
            if (parenthesedSelect.getSelect() != null) {
                simplifySelectForPlanner(parenthesedSelect.getSelect(), requiredOutputs);
            }
            return;
        }
        if (item instanceof ParenthesedFromItem parenthesedFromItem) {
            if (parenthesedFromItem.getFromItem() != null) {
                simplifyFromItem(parenthesedFromItem.getFromItem(), requiredByAlias);
            }
            if (parenthesedFromItem.getJoins() != null) {
                for (Join join : parenthesedFromItem.getJoins()) {
                    simplifyFromItem(join.getRightItem(), requiredByAlias);
                }
            }
        }
    }

    private java.util.Map<String, Set<String>> collectRequiredOutputs(PlainSelect select) {
        java.util.Map<String, Set<String>> required = new java.util.HashMap<>();
        if (select == null) {
            return required;
        }

        ExpressionVisitorAdapter<Void> visitor = new ExpressionVisitorAdapter<>() {
            @Override
            public <S> Void visit(net.sf.jsqlparser.schema.Column column, S context) {
                if (column != null && column.getTable() != null && column.getTable().getName() != null) {
                    String alias = column.getTable().getName().toLowerCase(Locale.ROOT);
                    String name = column.getColumnName();
                    if (name != null && !name.isBlank()) {
                        required.computeIfAbsent(alias, k -> new HashSet<>())
                            .add(name.toLowerCase(Locale.ROOT));
                    }
                }
                return null;
            }
        };

        if (select.getSelectItems() != null) {
            for (SelectItem<?> item : select.getSelectItems()) {
                if (item != null && item.getExpression() != null) {
                    item.getExpression().accept(visitor, null);
                }
            }
        }
        if (select.getWhere() != null) {
            select.getWhere().accept(visitor, null);
        }
        if (select.getHaving() != null) {
            select.getHaving().accept(visitor, null);
        }
        if (select.getGroupBy() != null && select.getGroupBy().getGroupByExpressionList() != null) {
            List<Expression> groupByExprs = select.getGroupBy().getGroupByExpressionList().getExpressions();
            if (groupByExprs != null) {
                for (Expression expr : groupByExprs) {
                    if (expr != null) {
                        expr.accept(visitor, null);
                    }
                }
            }
        }
        if (select.getOrderByElements() != null) {
            for (OrderByElement element : select.getOrderByElements()) {
                if (element.getExpression() != null) {
                    element.getExpression().accept(visitor, null);
                }
            }
        }
        if (select.getJoins() != null) {
            for (Join join : select.getJoins()) {
                if (join.getOnExpression() != null) {
                    join.getOnExpression().accept(visitor, null);
                }
                if (join.getOnExpressions() != null) {
                    for (Expression expr : join.getOnExpressions()) {
                        if (expr != null) {
                            expr.accept(visitor, null);
                        }
                    }
                }
            }
        }

        return required;
    }

    private java.util.Map<String, Set<String>> collectRequiredOutputsForSelect(Select select) {
        if (select == null) {
            return new java.util.HashMap<>();
        }
        if (select instanceof PlainSelect) {
            return collectRequiredOutputs((PlainSelect) select);
        }
        if (select instanceof ParenthesedSelect) {
            ParenthesedSelect parenthesedSelect = (ParenthesedSelect) select;
            if (parenthesedSelect.getSelect() != null) {
                return collectRequiredOutputsForSelect(parenthesedSelect.getSelect());
            }
        }
        if (select instanceof SetOperationList) {
            java.util.Map<String, Set<String>> merged = new java.util.HashMap<>();
            SetOperationList list = (SetOperationList) select;
            if (list.getSelects() != null) {
                for (Select nested : list.getSelects()) {
                    java.util.Map<String, Set<String>> next = collectRequiredOutputsForSelect(nested);
                    for (java.util.Map.Entry<String, Set<String>> entry : next.entrySet()) {
                        merged.computeIfAbsent(entry.getKey(), k -> new HashSet<>())
                            .addAll(entry.getValue());
                    }
                }
            }
            return merged;
        }
        return new java.util.HashMap<>();
    }

    private void addRequiredSelectItems(List<SelectItem<?>> items, Set<String> requiredOutputs) {
        if (requiredOutputs == null || requiredOutputs.isEmpty()) {
            return;
        }
        Set<String> existing = new HashSet<>();
        for (SelectItem<?> item : items) {
            String name = getSelectItemName(item);
            if (name != null) {
                existing.add(name.toLowerCase(Locale.ROOT));
            }
        }
        for (String required : requiredOutputs) {
            if (required == null || required.isBlank()) {
                continue;
            }
            String key = required.toLowerCase(Locale.ROOT);
            if (existing.contains(key)) {
                continue;
            }
            SelectItem<?> item = SelectItem.from(new LongValue(1));
            Alias alias = new Alias(required, true);
            item.setAlias(alias);
            items.add(item);
            existing.add(key);
        }
    }

    private String getSelectItemName(SelectItem<?> item) {
        if (item == null) {
            return null;
        }
        Alias alias = item.getAlias();
        if (alias != null && alias.getName() != null && !alias.getName().isBlank()) {
            return alias.getName();
        }
        Expression expr = item.getExpression();
        if (expr instanceof net.sf.jsqlparser.schema.Column column) {
            return column.getColumnName();
        }
        return null;
    }

    private void rewritePlainSelect(PlainSelect select, String schemaName) {
        rewriteFromItem(select.getFromItem(), schemaName);
        if (select.getJoins() != null) {
            for (Join join : select.getJoins()) {
                rewriteFromItem(join.getRightItem(), schemaName);
                if (join.getOnExpression() != null) {
                    rewriteExpression(join.getOnExpression(), schemaName);
                }
                if (join.getOnExpressions() != null) {
                    for (Expression expr : join.getOnExpressions()) {
                        rewriteExpression(expr, schemaName);
                    }
                }
            }
        }
        if (select.getWhere() != null) {
            rewriteExpression(select.getWhere(), schemaName);
        }
        if (select.getHaving() != null) {
            rewriteExpression(select.getHaving(), schemaName);
        }
        if (select.getSelectItems() != null) {
            for (SelectItem<?> item : select.getSelectItems()) {
                if (item != null && item.getExpression() != null) {
                    rewriteExpression(item.getExpression(), schemaName);
                }
            }
        }
        if (select.getGroupBy() != null && select.getGroupBy().getGroupByExpressionList() != null) {
            List<Expression> groupByExprs = select.getGroupBy().getGroupByExpressionList().getExpressions();
            if (groupByExprs != null) {
                for (Expression expr : groupByExprs) {
                    rewriteExpression(expr, schemaName);
                }
            }
        }
        if (select.getOrderByElements() != null) {
            for (OrderByElement element : select.getOrderByElements()) {
                if (element.getExpression() != null) {
                    rewriteExpression(element.getExpression(), schemaName);
                }
            }
        }
        coerceSelectItemsForGroupBy(select);
        forceAliasWithAs(select);
    }

    private void coerceSelectItemsForGroupBy(PlainSelect select) {
        if (select == null || select.getGroupBy() == null || select.getSelectItems() == null) {
            return;
        }
        if (select.getGroupBy().getGroupByExpressionList() == null) {
            return;
        }
        List<Expression> groupByExprs = select.getGroupBy().getGroupByExpressionList().getExpressions();
        if (groupByExprs == null || groupByExprs.isEmpty()) {
            return;
        }

        Set<String> groupBySql = new HashSet<>();
        for (Expression expr : groupByExprs) {
            if (expr != null) {
                groupBySql.add(expr.toString().toLowerCase(Locale.ROOT));
            }
        }

        for (SelectItem<?> item : select.getSelectItems()) {
            if (item == null || item.getExpression() == null) {
                continue;
            }
            Expression expr = item.getExpression();
            if (expr instanceof Function && isAggregateFunction((Function) expr)) {
                continue;
            }
            String exprSql = expr.toString().toLowerCase(Locale.ROOT);
            if (groupBySql.contains(exprSql)) {
                continue;
            }
            String aliasName = null;
            if (item.getAlias() == null && expr instanceof net.sf.jsqlparser.schema.Column column) {
                aliasName = column.getColumnName();
            }
            Function wrapper = new Function();
            wrapper.setName("MIN");
            wrapper.setParameters(new ExpressionList<>(List.of(expr)));
            @SuppressWarnings("unchecked")
            SelectItem<Expression> typed = (SelectItem<Expression>) item;
            typed.setExpression(wrapper);
            if (item.getAlias() == null && aliasName != null && !aliasName.isBlank()) {
                Alias alias = new Alias(aliasName, true);
                item.setAlias(alias);
            }
        }
    }

    private boolean isAggregateFunction(Function function) {
        if (function == null || function.getName() == null) {
            return false;
        }
        if (function.isAllColumns()) {
            return true;
        }
        String name = function.getName().toUpperCase(Locale.ROOT);
        return AGG_FUNCTIONS.contains(name);
    }

    private void rewriteFromItem(FromItem item, String schemaName) {
        if (item == null) {
            return;
        }
        if (item instanceof Table) {
            stripSchemaFromTable((Table) item, schemaName);
            return;
        }
        if (item instanceof ParenthesedSelect) {
            ParenthesedSelect subSelect = (ParenthesedSelect) item;
            if (subSelect.getSelect() != null) {
                rewriteSelect(subSelect.getSelect(), schemaName);
            }
            return;
        }
        if (item instanceof ParenthesedFromItem) {
            ParenthesedFromItem parenthesed = (ParenthesedFromItem) item;
            if (parenthesed.getFromItem() != null) {
                rewriteFromItem(parenthesed.getFromItem(), schemaName);
            }
            if (parenthesed.getJoins() != null) {
                for (Join join : parenthesed.getJoins()) {
                    rewriteFromItem(join.getRightItem(), schemaName);
                    if (join.getOnExpression() != null) {
                        rewriteExpression(join.getOnExpression(), schemaName);
                    }
                    if (join.getOnExpressions() != null) {
                        for (Expression expr : join.getOnExpressions()) {
                            rewriteExpression(expr, schemaName);
                        }
                    }
                }
            }
        }
    }

    private void rewriteExpression(Expression expr, String schemaName) {
        if (expr == null) {
            return;
        }
        expr.accept(new ExpressionVisitorAdapter<Void>() {
            @Override
            public <S> Void visit(Function function, S context) {
                super.visit(function, context);
                rewriteFunction(function);
                return null;
            }

            @Override
            public <S> Void visit(net.sf.jsqlparser.schema.Column column, S context) {
                stripSchemaFromColumn(column, schemaName);
                return null;
            }

            @Override
            public <S> Void visit(ParenthesedSelect parenthesedSelect, S context) {
                if (parenthesedSelect.getSelect() != null) {
                    rewriteSelect(parenthesedSelect.getSelect(), schemaName);
                }
                return null;
            }
        }, null);
    }

    private void rewriteFunction(Function function) {
        if (function == null || function.getName() == null) {
            return;
        }
        String name = function.getName().toUpperCase(Locale.ROOT);
        switch (name) {
            case "IFNULL" -> function.setName("COALESCE");
            default -> {
                // Keep as-is
            }
        }
    }

    private void forceAliasWithAs(PlainSelect select) {
        if (select == null) {
            return;
        }
        if (select.getSelectItems() != null) {
            for (SelectItem<?> item : select.getSelectItems()) {
                Alias alias = item.getAlias();
                if (alias != null && !alias.isUseAs()) {
                    alias.setUseAs(true);
                }
            }
        }
        forceAliasWithAs(select.getFromItem());
        if (select.getJoins() != null) {
            for (Join join : select.getJoins()) {
                forceAliasWithAs(join.getRightItem());
            }
        }
    }

    private void forceAliasWithAs(FromItem item) {
        if (item == null) {
            return;
        }
        Alias alias = item.getAlias();
        if (alias != null && !alias.isUseAs()) {
            alias.setUseAs(true);
        }
        if (item instanceof ParenthesedSelect parenthesedSelect) {
            if (parenthesedSelect.getSelect() != null) {
                forceAliasWithAs(parenthesedSelect.getSelect());
            }
        }
        if (item instanceof ParenthesedFromItem parenthesedFromItem) {
            if (parenthesedFromItem.getFromItem() != null) {
                forceAliasWithAs(parenthesedFromItem.getFromItem());
            }
            if (parenthesedFromItem.getJoins() != null) {
                for (Join join : parenthesedFromItem.getJoins()) {
                    forceAliasWithAs(join.getRightItem());
                }
            }
        }
    }

    private void stripSchemaFromTable(Table table, String schemaName) {
        if (table == null) {
            return;
        }
        if (schemaName != null && !schemaName.isBlank()) {
            if (schemaName.equalsIgnoreCase(table.getSchemaName())) {
                table.setSchemaName(null);
            }
        }
    }

    private void stripSchemaFromColumn(net.sf.jsqlparser.schema.Column column, String schemaName) {
        if (column == null || column.getTable() == null) {
            return;
        }
        Table table = column.getTable();
        if (schemaName != null && !schemaName.isBlank()) {
            if (schemaName.equalsIgnoreCase(table.getSchemaName())) {
                table.setSchemaName(null);
            }
        }
    }

    private OptdTable mapTable(TableMetadata table) {
        return mapTable(table, false);
    }

    private OptdTable mapTable(TableMetadata table, boolean stripSchemaPrefix) {
        String tableName = table.getName();
        if (!stripSchemaPrefix && table.getSchema() != null && !table.getSchema().isBlank()) {
            tableName = table.getSchema() + "." + table.getName();
        }

        List<OptdColumn> columns = new java.util.ArrayList<>();
        Set<String> seen = new HashSet<>();
        if (table.getColumns() != null) {
            for (ColumnMetadata column : table.getColumns()) {
                if (column == null || column.getName() == null) {
                    continue;
                }
                String key = column.getName().toLowerCase(Locale.ROOT);
                if (seen.add(key)) {
                    columns.add(mapColumn(column));
                }
            }
        }

        return OptdTable.builder()
            .name(tableName)
            .rowCount(table.getRowCount())
            .columns(columns)
            .build();
    }

    private OptdColumn mapColumn(ColumnMetadata column) {
        return OptdColumn.builder()
            .name(column.getName())
            .dataType(column.getDataType())
            .nullable(column.getNullable())
            .build();
    }
}
