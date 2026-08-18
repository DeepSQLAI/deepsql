package com.dbaagent.service;

import com.dbaagent.model.QueryRequest;
import com.dbaagent.model.QueryResult;
import com.dbaagent.model.SecurityEventOutcome;
import com.dbaagent.model.SecurityEventType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.operators.relational.ExpressionList;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserDataAccessPolicyService {

    private static final Pattern DIRECT_SENSITIVE_REQUEST_PATTERN = Pattern.compile(
        ".*\\b(show|list|export|get|give|find|fetch|retrieve|display|tell me|which)\\b.*\\b(email|emails|phone|phones|mobile|bank|credit card|card number|salary|income|ssn|passport|password|token|address)\\b.*",
        Pattern.CASE_INSENSITIVE
    );

    private final ConnectionChatAccessPolicyService policyService;
    private final SecurityEventService securityEventService;

    public PromptDecision evaluatePrompt(
        String connectionId,
        String username,
        boolean actorIsAdmin,
        String message
    ) {
        ConnectionChatAccessPolicyService.EffectivePolicy policy = policyService.resolveEffectivePolicy(connectionId, username, actorIsAdmin);
        if (!policy.protectsAnything()) {
            return PromptDecision.allow(policy);
        }
        String normalized = message == null ? "" : message.toLowerCase(Locale.ROOT);
        boolean requestsSensitiveDetails = DIRECT_SENSITIVE_REQUEST_PATTERN.matcher(normalized).matches()
            || mentionsProtectedColumns(normalized, policy)
            || mentionsProtectedTables(normalized, policy);

        boolean requestsAggregateOnly = normalized.contains("how many")
            || normalized.contains("count")
            || normalized.contains("trend")
            || normalized.contains("summary")
            || normalized.contains("breakdown");

        if (policy.blockMode() && requestsSensitiveDetails && !requestsAggregateOnly) {
            logPolicyEvent(SecurityEventType.CHAT_ACCESS_POLICY_BLOCKED, username, connectionId, "prompt_blocked", Map.of(
                "message", truncate(message),
                "blockedSensitivityCategories", policy.blockedSensitivityCategories(),
                "impactedColumns", policy.impactedColumns()
            ));
            return PromptDecision.block(
                policy,
                "I can’t provide that information for your account because this connection blocks access to protected data such as PII, financial, health, regulated, or secret fields."
            );
        }
        return PromptDecision.allow(policy);
    }

    public String decorateQuestionWithPolicy(ConnectionChatAccessPolicyService.EffectivePolicy policy, String question) {
        if (policy == null || !policy.protectsAnything()) {
            return question;
        }
        return "DeepSQL access policy for this user: never return restricted data categories "
            + String.join(", ", policy.blockedSensitivityCategories())
            + "; do not expose protected tables/columns; prefer safe aggregates and summaries; redact any restricted values that still appear.\n\nUser request: "
            + question;
    }

    public QueryGuardDecision enforcePreExecution(
        String connectionId,
        QueryRequest queryRequest,
        QueryExecutionContext executionContext
    ) {
        if (executionContext == null || executionContext.actorUsername() == null || executionContext.actorUsername().isBlank()) {
            return QueryGuardDecision.allow(ConnectionChatAccessPolicyService.EffectivePolicy.none());
        }

        ConnectionChatAccessPolicyService.EffectivePolicy policy = policyService.resolveEffectivePolicy(
            connectionId,
            executionContext.actorUsername(),
            executionContext.actorIsAdmin()
        );
        if (!policy.protectsAnything()) {
            return QueryGuardDecision.allow(policy);
        }

        Map<String, ConnectionChatAccessPolicyService.ProtectionDescriptor> protectedObjects =
            policyService.buildProtectionDescriptors(policy);

        try {
            Statement parsed = CCJSqlParserUtil.parse(queryRequest.getQuery());
            if (parsed instanceof Select select && select.getPlainSelect() != null) {
                enforceAllowedSchemas(select.getPlainSelect(), policy.allowedSchemas());
                QueryInspection inspection = inspectPlainSelect(select.getPlainSelect(), protectedObjects);
                if (inspection.selectsWildcardFromProtectedTable || inspection.rawProtectedColumnsSelected) {
                    logPolicyEvent(SecurityEventType.CHAT_ACCESS_POLICY_BLOCKED, executionContext.actorUsername(), connectionId, "sql_blocked", Map.of(
                        "query", truncate(queryRequest.getQuery()),
                        "reason", inspection.reason,
                        "protectedTables", inspection.protectedTables,
                        "protectedColumns", inspection.protectedColumns
                    ));
                    throw new UserDataAccessPolicyException(
                        "This query would return restricted data for your account, so DeepSQL blocked it before execution.",
                        "POLICY_SQL_BLOCKED"
                    );
                }
            }
        } catch (UserDataAccessPolicyException e) {
            throw e;
        } catch (Exception e) {
            String normalized = queryRequest.getQuery() == null ? "" : queryRequest.getQuery().toLowerCase(Locale.ROOT);
            if (containsDangerousProtectedReference(normalized, protectedObjects)) {
                logPolicyEvent(SecurityEventType.CHAT_ACCESS_POLICY_BLOCKED, executionContext.actorUsername(), connectionId, "sql_blocked_fallback", Map.of(
                    "query", truncate(queryRequest.getQuery())
                ));
                throw new UserDataAccessPolicyException(
                    "This query appears to target restricted data for your account, so DeepSQL blocked it before execution.",
                    "POLICY_SQL_BLOCKED"
                );
            }
        }

        return QueryGuardDecision.allow(policy);
    }

    public QueryResult redactResult(
        String connectionId,
        QueryResult result,
        QueryExecutionContext executionContext
    ) {
        if (result == null || executionContext == null || executionContext.actorUsername() == null || executionContext.actorUsername().isBlank()) {
            return result;
        }

        ConnectionChatAccessPolicyService.EffectivePolicy policy = policyService.resolveEffectivePolicy(
            connectionId,
            executionContext.actorUsername(),
            executionContext.actorIsAdmin()
        );
        if (!policy.protectsAnything() || !policy.redactMode()) {
            return result;
        }

        Map<String, ConnectionChatAccessPolicyService.ProtectionDescriptor> descriptors =
            policyService.buildProtectionDescriptors(policy);

        if (result.getColumns() == null || result.getRows() == null) {
            return result;
        }

        Set<String> protectedColumnNames = new LinkedHashSet<>();
        descriptors.values().forEach(descriptor -> {
            String qualifiedTable = descriptor.qualifiedTableName();
            descriptor.restrictedColumns().forEach(column ->
                protectedColumnNames.add(normalizeName(qualifiedTable + "." + column))
            );
            if (descriptor.protectWholeTable()) {
                protectedColumnNames.add(normalizeName(qualifiedTable));
            }
        });

        List<List<Object>> redactedRows = new ArrayList<>();
        boolean redacted = false;
        for (List<Object> row : result.getRows()) {
            List<Object> redactedRow = new ArrayList<>();
            for (int i = 0; i < row.size(); i++) {
                String column = i < result.getColumns().size() ? result.getColumns().get(i) : "";
                Object value = row.get(i);
                if (shouldRedactColumn(column, protectedColumnNames)) {
                    redactedRow.add(redactValue(value));
                    redacted = true;
                } else {
                    redactedRow.add(value);
                }
            }
            redactedRows.add(redactedRow);
        }

        if (!redacted) {
            return result;
        }

        QueryResult redactedResult = new QueryResult(
            result.getColumns(),
            redactedRows,
            result.getRowCount(),
            result.getTotalRowCount(),
            result.getIsLimited(),
            result.getExecutionTimeMs(),
            result.getQuery()
        );
        logPolicyEvent(SecurityEventType.CHAT_ACCESS_POLICY_REDACTED, executionContext.actorUsername(), connectionId, "result_redacted", Map.of(
            "query", truncate(result.getQuery()),
            "columns", result.getColumns()
        ));
        return redactedResult;
    }

    private boolean mentionsProtectedColumns(String normalized, ConnectionChatAccessPolicyService.EffectivePolicy policy) {
        return policy.impactedColumns().stream().anyMatch(column -> normalized.contains(column.toLowerCase(Locale.ROOT))
            || normalized.contains(column.substring(column.indexOf('.') + 1).toLowerCase(Locale.ROOT)));
    }

    private boolean mentionsProtectedTables(String normalized, ConnectionChatAccessPolicyService.EffectivePolicy policy) {
        return policy.impactedTables().stream().anyMatch(table -> normalized.contains(table.toLowerCase(Locale.ROOT)));
    }

    private void enforceAllowedSchemas(PlainSelect select, Set<String> allowedSchemas) {
        if (allowedSchemas == null || allowedSchemas.isEmpty()) {
            return;
        }
        Map<String, String> aliasToTable = buildAliasMap(select);
        Set<String> referencedSchemas = new LinkedHashSet<>();
        collectReferencedSchemas(select.getFromItem(), aliasToTable, referencedSchemas);
        if (select.getJoins() != null) {
            for (Join join : select.getJoins()) {
                collectReferencedSchemas(join.getRightItem(), aliasToTable, referencedSchemas);
            }
        }
        for (String schema : referencedSchemas) {
            if (!allowedSchemas.contains(normalizeName(schema))) {
                throw new UserDataAccessPolicyException(
                    "This query references schema '" + schema + "' which is outside your allowed schema scope.",
                    "POLICY_SCHEMA_BLOCKED"
                );
            }
        }
    }

    private void collectReferencedSchemas(FromItem fromItem, Map<String, String> aliasToTable, Set<String> schemas) {
        if (fromItem instanceof Table table) {
            String schema = table.getSchemaName();
            if (schema != null && !schema.isBlank()) {
                schemas.add(schema);
                return;
            }
            String qualified = aliasToTable.get(normalizeName(table.getName()));
            if (qualified != null && qualified.contains(".")) {
                schemas.add(qualified.substring(0, qualified.indexOf('.')));
            }
        }
    }

    private boolean containsDangerousProtectedReference(
        String normalizedSql,
        Map<String, ConnectionChatAccessPolicyService.ProtectionDescriptor> protectedObjects
    ) {
        for (ConnectionChatAccessPolicyService.ProtectionDescriptor descriptor : protectedObjects.values()) {
            String qualified = normalizeName(descriptor.qualifiedTableName());
            if (descriptor.protectWholeTable() && normalizedSql.contains(qualified)) {
                return true;
            }
            for (String column : descriptor.restrictedColumns()) {
                if (normalizedSql.contains(qualified + "." + normalizeName(column))) {
                    return true;
                }
            }
        }
        return false;
    }

    private QueryInspection inspectPlainSelect(
        PlainSelect select,
        Map<String, ConnectionChatAccessPolicyService.ProtectionDescriptor> protectedObjects
    ) {
        Map<String, String> aliasToTable = buildAliasMap(select);
        String defaultTableName = resolveDefaultTableName(select, aliasToTable);
        QueryInspection inspection = new QueryInspection();
        if (select.getSelectItems() == null) {
            return inspection;
        }

        select.getSelectItems().forEach(item -> {
            Expression expression = item.getExpression();
            if (expression instanceof AllColumns) {
                String fromTable = resolveDefaultTableName(select, aliasToTable);
                ConnectionChatAccessPolicyService.ProtectionDescriptor descriptor = lookupDescriptor(protectedObjects, fromTable);
                if (descriptor != null && (descriptor.protectWholeTable() || !descriptor.restrictedColumns().isEmpty())) {
                    inspection.selectsWildcardFromProtectedTable = true;
                    inspection.reason = "SELECT * touches protected objects";
                    inspection.protectedTables.add(descriptor.qualifiedTableName());
                }
                return;
            }
            if (expression instanceof AllTableColumns allTableColumns) {
                String tableName = resolveQualifiedTableName(allTableColumns.getTable(), aliasToTable);
                ConnectionChatAccessPolicyService.ProtectionDescriptor descriptor = lookupDescriptor(protectedObjects, tableName);
                if (descriptor != null) {
                    inspection.selectsWildcardFromProtectedTable = true;
                    inspection.reason = "SELECT table.* touches protected table";
                    inspection.protectedTables.add(descriptor.qualifiedTableName());
                }
                return;
            }

            Set<ColumnReference> referencedColumns = new LinkedHashSet<>();
            collectColumns(expression, referencedColumns, aliasToTable, defaultTableName);
            boolean aggregateExpression = containsAggregate(expression);
            for (ColumnReference reference : referencedColumns) {
                ConnectionChatAccessPolicyService.ProtectionDescriptor descriptor = lookupDescriptor(protectedObjects, reference.tableName());
                if (descriptor == null) {
                    continue;
                }
                boolean protectedColumn = descriptor.protectWholeTable()
                    || descriptor.restrictedColumns().stream().anyMatch(column -> normalizeName(column).equals(normalizeName(reference.columnName())));
                if (protectedColumn) {
                    inspection.protectedTables.add(descriptor.qualifiedTableName());
                    inspection.protectedColumns.add(descriptor.qualifiedTableName() + "." + reference.columnName());
                    if (!aggregateExpression) {
                        inspection.rawProtectedColumnsSelected = true;
                        inspection.reason = "Raw protected column selected";
                    }
                }
            }
        });
        return inspection;
    }

    private String resolveDefaultTableName(PlainSelect select, Map<String, String> aliasToTable) {
        if (select == null) {
            return "";
        }
        Set<String> concreteTables = new LinkedHashSet<>();
        if (select.getFromItem() instanceof Table table) {
            concreteTables.add(resolveQualifiedTableName(table, aliasToTable));
        }
        if (select.getJoins() != null) {
            for (Join join : select.getJoins()) {
                if (join.getRightItem() instanceof Table table) {
                    concreteTables.add(resolveQualifiedTableName(table, aliasToTable));
                }
            }
        }
        return concreteTables.size() == 1 ? concreteTables.iterator().next() : "";
    }

    private Map<String, String> buildAliasMap(PlainSelect select) {
        Map<String, String> aliasMap = new LinkedHashMap<>();
        extractAlias(select.getFromItem(), aliasMap);
        if (select.getJoins() != null) {
            for (Join join : select.getJoins()) {
                extractAlias(join.getRightItem(), aliasMap);
            }
        }
        return aliasMap;
    }

    private void extractAlias(FromItem fromItem, Map<String, String> aliasMap) {
        if (fromItem instanceof Table table) {
            String qualified;
            if (table.getSchemaName() != null && !table.getSchemaName().isBlank()) {
                qualified = ConnectionChatAccessPolicyService.qualifyTable(table.getSchemaName(), table.getName());
            } else {
                qualified = table.getName();
            }
            aliasMap.put(normalizeName(table.getName()), qualified);
            aliasMap.put(normalizeName(qualified), qualified);
            if (table.getAlias() != null) {
                aliasMap.put(normalizeName(table.getAlias().getName()), qualified);
            }
        } else if (fromItem instanceof ParenthesedSelect parenthesedSelect
            && parenthesedSelect.getAlias() != null) {
            aliasMap.put(normalizeName(parenthesedSelect.getAlias().getName()), parenthesedSelect.getAlias().getName());
        }
    }

    private void collectColumns(
        Expression expression,
        Set<ColumnReference> references,
        Map<String, String> aliasToTable,
        String defaultTableName
    ) {
        if (expression == null) {
            return;
        }
        if (expression instanceof Column column) {
            String resolvedTableName = resolveQualifiedTableName(column.getTable(), aliasToTable);
            if (resolvedTableName.isBlank()) {
                resolvedTableName = defaultTableName;
            }
            references.add(new ColumnReference(resolvedTableName, column.getColumnName()));
            return;
        }
        if (expression instanceof Function function) {
            if (function.getParameters() != null) {
                for (Expression parameter : function.getParameters().getExpressions()) {
                    collectColumns(parameter, references, aliasToTable, defaultTableName);
                }
            }
            return;
        }
        if (expression instanceof BinaryExpression binaryExpression) {
            collectColumns(binaryExpression.getLeftExpression(), references, aliasToTable, defaultTableName);
            collectColumns(binaryExpression.getRightExpression(), references, aliasToTable, defaultTableName);
            return;
        }
        if (expression instanceof Parenthesis parenthesis) {
            collectColumns(parenthesis.getExpression(), references, aliasToTable, defaultTableName);
        }
    }

    private String resolveQualifiedTableName(Table table, Map<String, String> aliasToTable) {
        if (table == null || table.getName() == null) {
            return "";
        }
        if (table.getSchemaName() != null && !table.getSchemaName().isBlank()) {
            return ConnectionChatAccessPolicyService.qualifyTable(table.getSchemaName(), table.getName());
        }
        return aliasToTable.getOrDefault(normalizeName(table.getName()), table.getName());
    }

    private ConnectionChatAccessPolicyService.ProtectionDescriptor lookupDescriptor(
        Map<String, ConnectionChatAccessPolicyService.ProtectionDescriptor> protectedObjects,
        String tableRef
    ) {
        if (tableRef == null || tableRef.isBlank()) {
            return null;
        }
        ConnectionChatAccessPolicyService.ProtectionDescriptor direct = protectedObjects.get(normalizeName(tableRef));
        if (direct != null) {
            return direct;
        }
        if (!tableRef.contains(".")) {
            List<ConnectionChatAccessPolicyService.ProtectionDescriptor> matches = protectedObjects.values().stream()
                .filter(descriptor -> normalizeName(descriptor.tableName()).equals(normalizeName(tableRef)))
                .toList();
            if (matches.size() == 1) {
                return matches.getFirst();
            }
        }
        return null;
    }

    private boolean containsAggregate(Expression expression) {
        if (expression instanceof Function function) {
            String name = function.getName();
            return name != null && List.of("count", "sum", "avg", "min", "max", "array_agg", "string_agg").contains(name.toLowerCase(Locale.ROOT));
        }
        if (expression instanceof BinaryExpression binaryExpression) {
            return containsAggregate(binaryExpression.getLeftExpression()) || containsAggregate(binaryExpression.getRightExpression());
        }
        if (expression instanceof Parenthesis parenthesis) {
            return containsAggregate(parenthesis.getExpression());
        }
        return false;
    }

    private boolean shouldRedactColumn(String column, Set<String> protectedColumnNames) {
        String normalized = normalizeName(column);
        if (normalized.isBlank()) {
            return false;
        }
        if (protectedColumnNames.contains(normalized)) {
            return true;
        }
        return protectedColumnNames.stream().anyMatch(protectedName ->
            protectedName.endsWith("." + normalized) || protectedName.equals(normalized)
        );
    }

    private Object redactValue(Object value) {
        if (value == null) {
            return null;
        }
        String rendered = String.valueOf(value);
        return "[redacted:" + rendered.length() + "]";
    }

    private void logPolicyEvent(SecurityEventType eventType, String username, String connectionId, String reason, Map<String, Object> metadata) {
        securityEventService.log(SecurityEventService.EventRequest.builder()
            .eventType(eventType)
            .outcome(SecurityEventOutcome.SUCCESS)
            .email(username)
            .targetResource("connection:" + connectionId)
            .reason(reason)
            .metadata(metadata)
            .build());
    }

    private String truncate(String value) {
        if (value == null) {
            return null;
        }
        return value.length() > 512 ? value.substring(0, 512) + "..." : value;
    }

    private String normalizeName(String value) {
        return value == null ? "" : value.trim().replace("\"", "").replace("`", "").toLowerCase(Locale.ROOT);
    }

    public record PromptDecision(
        boolean allowed,
        String responseMessage,
        ConnectionChatAccessPolicyService.EffectivePolicy policy
    ) {
        public static PromptDecision allow(ConnectionChatAccessPolicyService.EffectivePolicy policy) {
            return new PromptDecision(true, null, policy);
        }

        public static PromptDecision block(
            ConnectionChatAccessPolicyService.EffectivePolicy policy,
            String responseMessage
        ) {
            return new PromptDecision(false, responseMessage, policy);
        }
    }

    public record QueryGuardDecision(ConnectionChatAccessPolicyService.EffectivePolicy policy) {
        public static QueryGuardDecision allow(ConnectionChatAccessPolicyService.EffectivePolicy policy) {
            return new QueryGuardDecision(policy);
        }
    }

    private static final class QueryInspection {
        private boolean selectsWildcardFromProtectedTable;
        private boolean rawProtectedColumnsSelected;
        private String reason;
        private final List<String> protectedTables = new ArrayList<>();
        private final List<String> protectedColumns = new ArrayList<>();
    }

    private record ColumnReference(String tableName, String columnName) {
    }
}
