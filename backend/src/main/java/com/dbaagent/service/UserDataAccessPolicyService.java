package com.dbaagent.service;

import com.dbaagent.model.DatabaseObject;
import com.dbaagent.model.InferredTableRelationship;
import com.dbaagent.model.QueryExecutionOrigin;
import com.dbaagent.model.QueryRequest;
import com.dbaagent.model.QueryResult;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.SecurityEventOutcome;
import com.dbaagent.model.SecurityEventType;
import com.dbaagent.model.TableMetadata;
import com.dbaagent.model.TrainingDataEmbedding;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.AnalyticExpression;
import net.sf.jsqlparser.expression.BinaryExpression;
import net.sf.jsqlparser.expression.CaseExpression;
import net.sf.jsqlparser.expression.CastExpression;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.LongValue;
import net.sf.jsqlparser.expression.NotExpression;
import net.sf.jsqlparser.expression.Parenthesis;
import net.sf.jsqlparser.expression.SignedExpression;
import net.sf.jsqlparser.expression.WhenClause;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.ExplainStatement;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.select.AllColumns;
import net.sf.jsqlparser.statement.select.AllTableColumns;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import net.sf.jsqlparser.util.TablesNamesFinder;
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
    private static final Set<String> SUMMARY_AGGREGATES = Set.of("sum", "avg");
    private static final ObjectMapper RAG_METADATA_MAPPER = new ObjectMapper();

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

        if (policy.blockMode() && requestsSensitiveDetails) {
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
        if (executionContext == null) {
            throw new UserDataAccessPolicyException(
                "DeepSQL could not determine who is running this query, so it was blocked.",
                "POLICY_ACTOR_REQUIRED"
            );
        }
        if (isActorExempt(executionContext.origin())) {
            return QueryGuardDecision.allow(ConnectionChatAccessPolicyService.EffectivePolicy.none());
        }
        if (executionContext.actorUsername() == null || executionContext.actorUsername().isBlank()) {
            throw new UserDataAccessPolicyException(
                "DeepSQL could not determine who is running this query, so it was blocked.",
                "POLICY_ACTOR_REQUIRED"
            );
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

        Statement parsed;
        try {
            parsed = CCJSqlParserUtil.parse(queryRequest.getQuery());
        } catch (Exception e) {
            logPolicyEvent(SecurityEventType.CHAT_ACCESS_POLICY_BLOCKED, executionContext.actorUsername(), connectionId, "sql_unparseable", Map.of(
                "query", truncate(queryRequest.getQuery())
            ));
            throw new UserDataAccessPolicyException(
                "This query could not be verified against your access policy, so DeepSQL blocked it before execution.",
                "POLICY_SQL_UNPARSEABLE"
            );
        }

        try {
            Statement inspectable = unwrapExplain(parsed);
            if (!(inspectable instanceof Select select)) {
                throw new UserDataAccessPolicyException(
                    "This statement shape cannot be verified against your access policy, so DeepSQL blocked it.",
                    "POLICY_SQL_UNHANDLED"
                );
            }
            enforceAllowedSchemas(select, policy.allowedSchemas());
            QueryInspection inspection = inspectSelect(select, protectedObjects, policy.allowAggregates());
            if (inspection.selectsWildcardFromProtectedTable
                || inspection.rawProtectedColumnsSelected
                || inspection.unresolvedProtectedReference) {
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
        } catch (UserDataAccessPolicyException e) {
            throw e;
        }

        return QueryGuardDecision.allow(policy);
    }

    public List<DatabaseObject> filterDatabaseObjects(
        String connectionId,
        String username,
        boolean actorIsAdmin,
        List<DatabaseObject> objects
    ) {
        if (objects == null || objects.isEmpty()) {
            return objects;
        }
        Set<String> allowedSchemas = allowedSchemasForActor(connectionId, username, actorIsAdmin);
        if (allowedSchemas.isEmpty()) {
            return objects;
        }
        return objects.stream()
            .filter(object -> ConnectionChatAccessPolicyService.isSchemaInScope(object.getSchema(), allowedSchemas))
            .toList();
    }

    public SchemaMetadata filterSchemaMetadata(
        String connectionId,
        String username,
        boolean actorIsAdmin,
        SchemaMetadata schema
    ) {
        if (schema == null) {
            return null;
        }
        Set<String> allowedSchemas = allowedSchemasForActor(connectionId, username, actorIsAdmin);
        if (allowedSchemas.isEmpty()) {
            return schema;
        }

        SchemaMetadata filtered = new SchemaMetadata();
        filtered.setDatabaseName(schema.getDatabaseName());
        filtered.setDbType(schema.getDbType());
        filtered.setTotalViews(schema.getTotalViews());
        filtered.setTotalSizeBytes(schema.getTotalSizeBytes());
        List<TableMetadata> tables = schema.getTables() == null ? List.of() : schema.getTables().stream()
            .filter(table -> ConnectionChatAccessPolicyService.isSchemaInScope(table.getSchema(), allowedSchemas))
            .toList();
        filtered.setTables(tables);
        filtered.setTotalTables((long) tables.size());
        if (schema.getRelationships() != null) {
            filtered.setRelationships(schema.getRelationships().stream()
                .filter(relationship ->
                    ConnectionChatAccessPolicyService.isSchemaInScope(schemaFromTableRef(relationship.getFromTable()), allowedSchemas)
                        && ConnectionChatAccessPolicyService.isSchemaInScope(schemaFromTableRef(relationship.getToTable()), allowedSchemas))
                .toList());
        }
        return filtered;
    }

    public List<TrainingDataEmbedding> filterRagEmbeddings(
        String connectionId,
        String username,
        boolean actorIsAdmin,
        List<TrainingDataEmbedding> embeddings
    ) {
        if (embeddings == null || embeddings.isEmpty()) {
            return embeddings == null ? List.of() : embeddings;
        }
        Set<String> allowedSchemas = allowedSchemasForActor(connectionId, username, actorIsAdmin);
        if (allowedSchemas.isEmpty()) {
            return embeddings;
        }
        return embeddings.stream()
            .filter(embedding -> isRagMetadataInScope(allowedSchemas, embedding == null ? null : embedding.getMetadata()))
            .toList();
    }

    public boolean isRagMetadataInScope(
        String connectionId,
        String username,
        boolean actorIsAdmin,
        String metadataJson
    ) {
        Set<String> allowedSchemas = allowedSchemasForActor(connectionId, username, actorIsAdmin);
        if (allowedSchemas.isEmpty()) {
            return true;
        }
        return isRagMetadataInScope(allowedSchemas, metadataJson);
    }

    public List<InferredTableRelationship> filterInferredRelationships(
        String connectionId,
        String username,
        boolean actorIsAdmin,
        List<InferredTableRelationship> relationships
    ) {
        if (relationships == null || relationships.isEmpty()) {
            return relationships;
        }
        Set<String> allowedSchemas = allowedSchemasForActor(connectionId, username, actorIsAdmin);
        if (allowedSchemas.isEmpty()) {
            return relationships;
        }
        return relationships.stream()
            .filter(relationship -> relationship != null
                && ConnectionChatAccessPolicyService.isSchemaInScope(schemaFromTableRef(relationship.getSourceTable()), allowedSchemas)
                && ConnectionChatAccessPolicyService.isSchemaInScope(schemaFromTableRef(relationship.getTargetTable()), allowedSchemas))
            .toList();
    }

    public void assertTableSchemaAllowed(
        String connectionId,
        String username,
        boolean actorIsAdmin,
        String tableRef
    ) {
        Set<String> allowedSchemas = allowedSchemasForActor(connectionId, username, actorIsAdmin);
        if (allowedSchemas.isEmpty()) {
            return;
        }
        String schema = schemaFromTableRef(tableRef);
        if (!ConnectionChatAccessPolicyService.isSchemaInScope(schema, allowedSchemas)) {
            throw new UserDataAccessPolicyException(
                "This object is in schema '" + (schema.isBlank() ? "public" : schema)
                    + "' which is outside your allowed schema scope.",
                "POLICY_SCHEMA_BLOCKED"
            );
        }
    }

    private Set<String> allowedSchemasForActor(String connectionId, String username, boolean actorIsAdmin) {
        ConnectionChatAccessPolicyService.EffectivePolicy policy =
            policyService.resolveEffectivePolicy(connectionId, username, actorIsAdmin);
        if (policy == null || policy.allowedSchemas() == null) {
            return Set.of();
        }
        return policy.allowedSchemas();
    }

    private String schemaFromTableRef(String tableRef) {
        if (tableRef == null || tableRef.isBlank()) {
            return "";
        }
        String normalized = normalizeName(tableRef);
        String[] parts = normalized.split("\\.");
        if (parts.length >= 2) {
            return parts[parts.length - 2];
        }
        return "";
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

        Set<Integer> redactIndexes = resolveRedactIndexes(result, descriptors);
        if (redactIndexes.isEmpty()) {
            return result;
        }

        List<List<Object>> redactedRows = new ArrayList<>();
        for (List<Object> row : result.getRows()) {
            List<Object> redactedRow = new ArrayList<>();
            for (int i = 0; i < row.size(); i++) {
                Object value = row.get(i);
                if (redactIndexes.contains(i)) {
                    redactedRow.add(redactValue(value));
                } else {
                    redactedRow.add(value);
                }
            }
            redactedRows.add(redactedRow);
        }

        QueryResult redactedResult = new QueryResult(
            result.getColumns(),
            redactedRows,
            result.getRowCount(),
            result.getTotalRowCount(),
            result.getIsLimited(),
            result.getExecutionTimeMs(),
            result.getQuery(),
            result.getSessionPid()
        );
        logPolicyEvent(SecurityEventType.CHAT_ACCESS_POLICY_REDACTED, executionContext.actorUsername(), connectionId, "result_redacted", Map.of(
            "query", truncate(result.getQuery()),
            "columns", result.getColumns()
        ));
        return redactedResult;
    }

    private Set<Integer> resolveRedactIndexes(
        QueryResult result,
        Map<String, ConnectionChatAccessPolicyService.ProtectionDescriptor> descriptors
    ) {
        Set<Integer> indexes = new LinkedHashSet<>();
        List<Set<ColumnReference>> provenance = resolveOutputProvenance(result.getQuery());
        if (provenance != null && provenance.size() == result.getColumns().size()) {
            for (int i = 0; i < provenance.size(); i++) {
                Set<ColumnReference> sources = provenance.get(i);
                if (sources.isEmpty()) {
                    continue;
                }
                for (ColumnReference reference : sources) {
                    if (isProtectedReference(descriptors, reference)) {
                        indexes.add(i);
                        break;
                    }
                }
            }
            return indexes;
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
        for (int i = 0; i < result.getColumns().size(); i++) {
            if (shouldRedactColumn(result.getColumns().get(i), protectedColumnNames)) {
                indexes.add(i);
            }
        }
        return indexes;
    }

    private List<Set<ColumnReference>> resolveOutputProvenance(String sql) {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        try {
            Statement parsed = unwrapExplain(CCJSqlParserUtil.parse(sql));
            if (!(parsed instanceof Select select)) {
                return null;
            }
            PlainSelect outermost = outermostPlainSelect(select);
            if (outermost == null || outermost.getSelectItems() == null) {
                return null;
            }
            Map<String, String> aliasToTable = buildAliasMap(outermost);
            String defaultTableName = resolveDefaultTableName(outermost, aliasToTable);
            List<Set<ColumnReference>> provenance = new ArrayList<>();
            outermost.getSelectItems().forEach(item -> {
                Set<ColumnReference> referenced = new LinkedHashSet<>();
                Expression expression = item.getExpression();
                if (expression instanceof AllColumns || expression instanceof AllTableColumns) {
                    provenance.clear();
                    return;
                }
                collectColumns(expression, referenced, aliasToTable, defaultTableName);
                provenance.add(referenced);
            });
            return provenance.size() == outermost.getSelectItems().size() ? provenance : null;
        } catch (Exception e) {
            return null;
        }
    }

    private PlainSelect outermostPlainSelect(Select select) {
        if (select == null) {
            return null;
        }
        PlainSelect plainSelect = asPlainSelect(select);
        if (plainSelect != null) {
            return plainSelect;
        }
        SetOperationList setOperationList = asSetOperationList(select);
        if (setOperationList != null && setOperationList.getSelects() != null && !setOperationList.getSelects().isEmpty()) {
            return outermostPlainSelect(setOperationList.getSelects().getFirst());
        }
        if (select instanceof ParenthesedSelect parenthesedSelect) {
            return outermostPlainSelect(parenthesedSelect.getSelect());
        }
        return null;
    }

    private PlainSelect asPlainSelect(Select select) {
        return select instanceof PlainSelect plainSelect ? plainSelect : null;
    }

    private SetOperationList asSetOperationList(Select select) {
        return select instanceof SetOperationList setOperationList ? setOperationList : null;
    }

    private boolean mentionsProtectedColumns(String normalized, ConnectionChatAccessPolicyService.EffectivePolicy policy) {
        return policy.impactedColumns().stream().anyMatch(column -> normalized.contains(column.toLowerCase(Locale.ROOT))
            || normalized.contains(column.substring(column.indexOf('.') + 1).toLowerCase(Locale.ROOT)));
    }

    private boolean mentionsProtectedTables(String normalized, ConnectionChatAccessPolicyService.EffectivePolicy policy) {
        return policy.impactedTables().stream().anyMatch(table -> normalized.contains(table.toLowerCase(Locale.ROOT)));
    }

    private Statement unwrapExplain(Statement parsed) {
        if (parsed instanceof ExplainStatement explain && explain.getStatement() != null) {
            return unwrapExplain(explain.getStatement());
        }
        return parsed;
    }

    private void enforceAllowedSchemas(Select select, Set<String> allowedSchemas) {
        if (allowedSchemas == null || allowedSchemas.isEmpty()) {
            return;
        }
        Set<String> cteNames = new LinkedHashSet<>();
        collectCteNames(select, cteNames);
        Set<String> tables = findReferencedTables(select);
        Set<String> referencedSchemas = new LinkedHashSet<>();
        for (String tableName : tables) {
            String normalized = normalizeName(tableName);
            String bare = bareTableName(normalized);
            if (cteNames.contains(bare) && !normalized.contains(".")) {
                continue;
            }
            String schema = schemaFromTableRef(normalized);
            if (schema.isBlank()) {
                throw new UserDataAccessPolicyException(
                    "This query references an unqualified table which is outside your allowed schema scope.",
                    "POLICY_SCHEMA_BLOCKED"
                );
            }
            referencedSchemas.add(schema);
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

    private Set<String> findReferencedTables(Select select) {
        TablesNamesFinder finder = new TablesNamesFinder();
        List<String> tableList = finder.getTableList((Statement) select);
        return tableList == null ? Set.of() : new LinkedHashSet<>(tableList);
    }

    private void collectCteNames(Select select, Set<String> names) {
        if (select == null) {
            return;
        }
        if (select.getWithItemsList() != null) {
            for (WithItem withItem : select.getWithItemsList()) {
                if (withItem.getAlias() != null && withItem.getAlias().getName() != null) {
                    names.add(normalizeName(withItem.getAlias().getName()));
                }
                if (withItem.getSelect() != null) {
                    collectCteNames(withItem.getSelect(), names);
                }
            }
        }
        SetOperationList setOperationList = asSetOperationList(select);
        if (setOperationList != null && setOperationList.getSelects() != null) {
            for (Select part : setOperationList.getSelects()) {
                collectCteNames(part, names);
            }
        }
    }

    private QueryInspection inspectSelect(
        Select select,
        Map<String, ConnectionChatAccessPolicyService.ProtectionDescriptor> protectedObjects,
        boolean allowAggregates
    ) {
        QueryInspection inspection = new QueryInspection();
        if (select == null) {
            inspection.unresolvedProtectedReference = true;
            inspection.reason = "Unhandled SELECT shape";
            return inspection;
        }
        if (select.getWithItemsList() != null) {
            for (WithItem withItem : select.getWithItemsList()) {
                if (withItem.getSelect() != null) {
                    inspection.merge(inspectSelect(withItem.getSelect(), protectedObjects, allowAggregates));
                }
            }
        }
        PlainSelect plainSelect = asPlainSelect(select);
        if (plainSelect != null) {
            inspection.merge(inspectPlainSelect(plainSelect, protectedObjects, allowAggregates));
            return inspection;
        }
        SetOperationList setOperationList = asSetOperationList(select);
        if (setOperationList != null && setOperationList.getSelects() != null) {
            for (Select part : setOperationList.getSelects()) {
                inspection.merge(inspectSelect(part, protectedObjects, allowAggregates));
            }
            return inspection;
        }
        if (select instanceof ParenthesedSelect parenthesedSelect && parenthesedSelect.getSelect() != null) {
            inspection.merge(inspectSelect(parenthesedSelect.getSelect(), protectedObjects, allowAggregates));
            return inspection;
        }
        inspection.unresolvedProtectedReference = true;
        inspection.reason = "Unhandled SELECT shape";
        return inspection;
    }

    private QueryInspection inspectPlainSelect(
        PlainSelect select,
        Map<String, ConnectionChatAccessPolicyService.ProtectionDescriptor> protectedObjects,
        boolean allowAggregates
    ) {
        Map<String, String> aliasToTable = buildAliasMap(select);
        String defaultTableName = resolveDefaultTableName(select, aliasToTable);
        QueryInspection inspection = new QueryInspection();
        boolean grouped = select.getGroupBy() != null;
        inspectFromItem(select.getFromItem(), protectedObjects, allowAggregates, inspection);
        if (select.getJoins() != null) {
            for (Join join : select.getJoins()) {
                inspectFromItem(join.getRightItem(), protectedObjects, allowAggregates, inspection);
            }
        }
        inspectExpressionTree(select.getWhere(), aliasToTable, defaultTableName, protectedObjects, allowAggregates, inspection);
        inspectExpressionTree(select.getHaving(), aliasToTable, defaultTableName, protectedObjects, allowAggregates, inspection);
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
                if (descriptor != null && (descriptor.protectWholeTable() || !descriptor.restrictedColumns().isEmpty())) {
                    inspection.selectsWildcardFromProtectedTable = true;
                    inspection.reason = "SELECT table.* touches protected table";
                    inspection.protectedTables.add(descriptor.qualifiedTableName());
                }
                return;
            }

            Set<ColumnReference> referencedColumns = new LinkedHashSet<>();
            collectColumns(expression, referencedColumns, aliasToTable, defaultTableName);
            if (containsSubselect(expression)) {
                inspectExpressionTree(expression, aliasToTable, defaultTableName, protectedObjects, allowAggregates, inspection);
            }
            boolean aggregateExemption = !grouped && isCountStarOnly(expression);
            if (allowAggregates && !grouped && isSummaryAggregate(expression)) {
                aggregateExemption = true;
            }
            if (referencedColumns.isEmpty() && !isLiteralOrCountStar(expression) && mentionsAnyProtectedName(expression, protectedObjects)) {
                inspection.unresolvedProtectedReference = true;
                inspection.reason = "Protected column provenance could not be established";
                return;
            }
            for (ColumnReference reference : referencedColumns) {
                if ((reference.tableName() == null || reference.tableName().isBlank()) && defaultTableName.isBlank()) {
                    if (!protectedObjects.isEmpty()) {
                        inspection.unresolvedProtectedReference = true;
                        inspection.reason = "Protected column provenance could not be established";
                    }
                    continue;
                }
                if (isProtectedReference(protectedObjects, reference)) {
                    ConnectionChatAccessPolicyService.ProtectionDescriptor descriptor = lookupDescriptor(protectedObjects, reference.tableName());
                    if (descriptor != null) {
                        inspection.protectedTables.add(descriptor.qualifiedTableName());
                        inspection.protectedColumns.add(descriptor.qualifiedTableName() + "." + reference.columnName());
                    }
                    if (!aggregateExemption) {
                        inspection.rawProtectedColumnsSelected = true;
                        inspection.reason = "Raw protected column selected";
                    }
                }
            }
        });
        return inspection;
    }

    private void inspectFromItem(
        FromItem fromItem,
        Map<String, ConnectionChatAccessPolicyService.ProtectionDescriptor> protectedObjects,
        boolean allowAggregates,
        QueryInspection inspection
    ) {
        if (fromItem instanceof ParenthesedSelect parenthesedSelect) {
            inspection.merge(inspectSelect(parenthesedSelect, protectedObjects, allowAggregates));
        } else if (fromItem instanceof Select select) {
            inspection.merge(inspectSelect(select, protectedObjects, allowAggregates));
        }
    }

    private void inspectExpressionTree(
        Expression expression,
        Map<String, String> aliasToTable,
        String defaultTableName,
        Map<String, ConnectionChatAccessPolicyService.ProtectionDescriptor> protectedObjects,
        boolean allowAggregates,
        QueryInspection inspection
    ) {
        if (expression instanceof ParenthesedSelect parenthesedSelect) {
            inspection.merge(inspectSelect(parenthesedSelect, protectedObjects, allowAggregates));
            return;
        }
        if (expression instanceof Select select) {
            inspection.merge(inspectSelect(select, protectedObjects, allowAggregates));
            return;
        }
        if (expression instanceof Function function && function.getParameters() != null) {
            for (Expression parameter : function.getParameters().getExpressions()) {
                inspectExpressionTree(parameter, aliasToTable, defaultTableName, protectedObjects, allowAggregates, inspection);
            }
            return;
        }
        if (expression instanceof BinaryExpression binaryExpression) {
            inspectExpressionTree(binaryExpression.getLeftExpression(), aliasToTable, defaultTableName, protectedObjects, allowAggregates, inspection);
            inspectExpressionTree(binaryExpression.getRightExpression(), aliasToTable, defaultTableName, protectedObjects, allowAggregates, inspection);
            return;
        }
        if (expression instanceof Parenthesis parenthesis) {
            inspectExpressionTree(parenthesis.getExpression(), aliasToTable, defaultTableName, protectedObjects, allowAggregates, inspection);
            return;
        }
        if (expression instanceof CastExpression castExpression) {
            inspectExpressionTree(castExpression.getLeftExpression(), aliasToTable, defaultTableName, protectedObjects, allowAggregates, inspection);
            return;
        }
        if (expression instanceof CaseExpression caseExpression) {
            inspectExpressionTree(caseExpression.getSwitchExpression(), aliasToTable, defaultTableName, protectedObjects, allowAggregates, inspection);
            if (caseExpression.getWhenClauses() != null) {
                for (WhenClause whenClause : caseExpression.getWhenClauses()) {
                    inspectExpressionTree(whenClause.getWhenExpression(), aliasToTable, defaultTableName, protectedObjects, allowAggregates, inspection);
                    inspectExpressionTree(whenClause.getThenExpression(), aliasToTable, defaultTableName, protectedObjects, allowAggregates, inspection);
                }
            }
            inspectExpressionTree(caseExpression.getElseExpression(), aliasToTable, defaultTableName, protectedObjects, allowAggregates, inspection);
        }
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
        if (expression instanceof AnalyticExpression analyticExpression) {
            collectColumns(analyticExpression.getExpression(), references, aliasToTable, defaultTableName);
            return;
        }
        if (expression instanceof BinaryExpression binaryExpression) {
            collectColumns(binaryExpression.getLeftExpression(), references, aliasToTable, defaultTableName);
            collectColumns(binaryExpression.getRightExpression(), references, aliasToTable, defaultTableName);
            return;
        }
        if (expression instanceof Parenthesis parenthesis) {
            collectColumns(parenthesis.getExpression(), references, aliasToTable, defaultTableName);
            return;
        }
        if (expression instanceof CastExpression castExpression) {
            collectColumns(castExpression.getLeftExpression(), references, aliasToTable, defaultTableName);
            return;
        }
        if (expression instanceof SignedExpression signedExpression) {
            collectColumns(signedExpression.getExpression(), references, aliasToTable, defaultTableName);
            return;
        }
        if (expression instanceof NotExpression notExpression) {
            collectColumns(notExpression.getExpression(), references, aliasToTable, defaultTableName);
            return;
        }
        if (expression instanceof CaseExpression caseExpression) {
            collectColumns(caseExpression.getSwitchExpression(), references, aliasToTable, defaultTableName);
            if (caseExpression.getWhenClauses() != null) {
                for (WhenClause whenClause : caseExpression.getWhenClauses()) {
                    collectColumns(whenClause.getWhenExpression(), references, aliasToTable, defaultTableName);
                    collectColumns(whenClause.getThenExpression(), references, aliasToTable, defaultTableName);
                }
            }
            collectColumns(caseExpression.getElseExpression(), references, aliasToTable, defaultTableName);
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

    private boolean isProtectedReference(
        Map<String, ConnectionChatAccessPolicyService.ProtectionDescriptor> protectedObjects,
        ColumnReference reference
    ) {
        ConnectionChatAccessPolicyService.ProtectionDescriptor descriptor = lookupDescriptor(protectedObjects, reference.tableName());
        if (descriptor == null) {
            return false;
        }
        return descriptor.protectWholeTable()
            || descriptor.restrictedColumns().stream().anyMatch(column -> normalizeName(column).equals(normalizeName(reference.columnName())));
    }

    private boolean isCountStarOnly(Expression expression) {
        if (!(expression instanceof Function function)) {
            return false;
        }
        if (function.getName() == null || !"count".equalsIgnoreCase(function.getName())) {
            return false;
        }
        if (function.isAllColumns()) {
            return true;
        }
        if (function.getParameters() == null || function.getParameters().getExpressions() == null
            || function.getParameters().getExpressions().isEmpty()) {
            return true;
        }
        if (function.getParameters().getExpressions().size() != 1) {
            return false;
        }
        Expression parameter = function.getParameters().getExpressions().getFirst();
        if (parameter instanceof AllColumns) {
            return true;
        }
        return parameter instanceof LongValue longValue && longValue.getValue() == 1;
    }

    private boolean isSummaryAggregate(Expression expression) {
        if (!(expression instanceof Function function) || function.getName() == null) {
            return false;
        }
        return SUMMARY_AGGREGATES.contains(function.getName().toLowerCase(Locale.ROOT));
    }

    private boolean isLiteralOrCountStar(Expression expression) {
        return isCountStarOnly(expression)
            || expression instanceof LongValue
            || (expression != null && expression.getClass().getSimpleName().contains("Value"));
    }

    private boolean containsSubselect(Expression expression) {
        return expression instanceof Select || expression instanceof ParenthesedSelect;
    }

    private boolean mentionsAnyProtectedName(
        Expression expression,
        Map<String, ConnectionChatAccessPolicyService.ProtectionDescriptor> protectedObjects
    ) {
        if (expression == null) {
            return false;
        }
        String rendered = normalizeName(expression.toString());
        for (ConnectionChatAccessPolicyService.ProtectionDescriptor descriptor : protectedObjects.values()) {
            for (String column : descriptor.restrictedColumns()) {
                if (rendered.contains(normalizeName(column))) {
                    return true;
                }
            }
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

    private boolean isRagMetadataInScope(Set<String> allowedSchemas, String metadataJson) {
        Set<String> tableRefs = extractTableRefsFromMetadata(metadataJson);
        if (tableRefs.isEmpty()) {
            return false;
        }
        for (String tableRef : tableRefs) {
            if (!ConnectionChatAccessPolicyService.isSchemaInScope(schemaFromTableRef(tableRef), allowedSchemas)) {
                return false;
            }
        }
        return true;
    }

    private Set<String> extractTableRefsFromMetadata(String metadataJson) {
        Set<String> tables = new LinkedHashSet<>();
        if (metadataJson == null || metadataJson.isBlank()) {
            return tables;
        }
        try {
            JsonNode node = RAG_METADATA_MAPPER.readTree(metadataJson);
            addTextualTable(tables, node, "tableName");
            addTextualTable(tables, node, "objectName");
            addTextualTable(tables, node, "schema");
            addTextualTable(tables, node, "schemaName");
            if (node.has("tablesUsed") && node.get("tablesUsed").isTextual()) {
                for (String table : node.get("tablesUsed").asText("").split(",")) {
                    addTableRef(tables, table);
                }
            }
            addArrayTables(tables, node.get("linkedTables"));
            if (node.has("linkedColumns") && node.get("linkedColumns").isArray()) {
                for (JsonNode linkedColumn : node.get("linkedColumns")) {
                    String columnReference = linkedColumn.asText("").trim();
                    int lastDot = columnReference.lastIndexOf('.');
                    if (lastDot > 0) {
                        addTableRef(tables, columnReference.substring(0, lastDot));
                    }
                }
            }
        } catch (Exception ignored) {
            return Set.of();
        }
        return tables;
    }

    private void addTextualTable(Set<String> tables, JsonNode node, String field) {
        if (node != null && node.has(field) && node.get(field).isTextual()) {
            addTableRef(tables, node.get(field).asText());
        }
    }

    private void addArrayTables(Set<String> tables, JsonNode array) {
        if (array == null || !array.isArray()) {
            return;
        }
        for (JsonNode item : array) {
            addTableRef(tables, item.asText());
        }
    }

    private void addTableRef(Set<String> tables, String raw) {
        if (raw == null) {
            return;
        }
        String trimmed = raw.trim();
        if (!trimmed.isBlank()) {
            tables.add(trimmed);
        }
    }

    private boolean isActorExempt(QueryExecutionOrigin origin) {
        return origin == QueryExecutionOrigin.INTERNAL || origin == QueryExecutionOrigin.SCHEDULED;
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

    private String bareTableName(String qualified) {
        int separator = qualified.lastIndexOf('.');
        return separator >= 0 ? qualified.substring(separator + 1) : qualified;
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
        private boolean unresolvedProtectedReference;
        private String reason;
        private final List<String> protectedTables = new ArrayList<>();
        private final List<String> protectedColumns = new ArrayList<>();

        private void merge(QueryInspection other) {
            this.selectsWildcardFromProtectedTable |= other.selectsWildcardFromProtectedTable;
            this.rawProtectedColumnsSelected |= other.rawProtectedColumnsSelected;
            this.unresolvedProtectedReference |= other.unresolvedProtectedReference;
            if (this.reason == null) {
                this.reason = other.reason;
            }
            this.protectedTables.addAll(other.protectedTables);
            this.protectedColumns.addAll(other.protectedColumns);
        }
    }

    private record ColumnReference(String tableName, String columnName) {
    }
}
