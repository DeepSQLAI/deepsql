package com.dbaagent.service;

import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.TableMetadata;
import com.dbaagent.model.brain.BrainRule;
import com.dbaagent.repository.brain.BrainRuleRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Stores and applies connection-scoped SQL business rules learned from user feedback.
 *
 * Rules are persisted as BrainRule rows with SQL_* rule types and used as deterministic
 * guardrails when generating and repairing SQL.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BusinessRuleMemoryService {

    public static final String RULE_REQUIRED_TABLE = "SQL_REQUIRED_TABLE";
    public static final String RULE_PROHIBITED_TABLE = "SQL_PROHIBITED_TABLE";
    public static final String RULE_REQUIRED_PREDICATE = "SQL_REQUIRED_PREDICATE";
    public static final String RULE_REQUIRED_JOIN_KEY = "SQL_REQUIRED_JOIN_KEY";

    private static final String TEXT_PREFIX_VALUE = "value:";
    private static final String TEXT_PREFIX_SOURCE = "source:";

    private static final Pattern TABLE_REF_PATTERN =
        Pattern.compile("(?i)\\b(from|join)\\s+([`\"\\w\\.]+)(?:\\s+(?:as\\s+)?([`\"\\w]+))?");

    private static final Pattern EQUALITY_PATTERN =
        Pattern.compile("(?i)\\b([a-zA-Z_][a-zA-Z0-9_]*)\\b\\s*=\\s*['\"]?([a-zA-Z0-9_\\-]{1,80})['\"]?");

    private static final Pattern JOIN_COLUMN_PATTERN =
        Pattern.compile("(?i)\\bjoin\\s+on\\s+([a-zA-Z_][a-zA-Z0-9_]*)\\b");

    private static final Pattern JOIN_EQUALS_PATTERN =
        Pattern.compile("(?i)\\bon\\s+([a-zA-Z_][a-zA-Z0-9_\\.]*)\\s*=\\s*([a-zA-Z_][a-zA-Z0-9_\\.]*)");

    private static final Pattern RULE_SIGNAL_PATTERN = Pattern.compile(
        "(?i)(should\\s+join|must\\s+join|join\\s+on|instead\\s+of|business\\s+rule" +
        "|always\\s+filter|never\\s+use|must\\s+include|must\\s+filter|always\\s+join|never\\s+join" +
        "|valid\\s+values|allowed\\s+values|required\\s+predicate|required\\s+join)"
    );

    private static final Pattern SQL_CONTEXT_PATTERN = Pattern.compile(
        "(?i)\\b(sql|table|column|join|where|filter|predicate|from|on|values?)\\b"
    );

    private static final int MIN_GUARDRAIL_CONFIDENCE = 60;

    private static final Set<String> STOP_WORDS = Set.of(
        "the", "a", "an", "for", "with", "from", "into", "that", "this", "these", "those",
        "what", "when", "where", "which", "who", "how", "much", "many", "all", "use", "using",
        "join", "table", "tables", "query", "sql", "and", "or", "not", "should", "must", "need",
        "in", "on", "by", "to", "is", "are", "be", "of", "as", "it", "we", "you"
    );

    private final BrainRuleRepository brainRuleRepository;

    public enum GuardrailType {
        REQUIRED_TABLE,
        PROHIBITED_TABLE,
        REQUIRED_PREDICATE,
        REQUIRED_JOIN_KEY
    }

    public record SqlGuardrail(
        GuardrailType type,
        String tableName,
        String columnName,
        String value,
        String sourceText,
        Integer confidence
    ) {
    }

    public record SqlGuardrailEvaluation(
        boolean passed,
        List<String> violations,
        int evaluatedRules
    ) {
        public String summary() {
            if (violations == null || violations.isEmpty()) {
                return "No violations";
            }
            return String.join("; ", violations);
        }
    }

    /**
     * Learns guardrails from user feedback and upserts them into BrainRule storage.
     *
     * This path is connection-scoped and intentionally schema-aware. If an equivalent
     * rule already exists, we keep a single canonical rule and only raise confidence.
     */
    @Transactional
    public int learnFromFeedback(
        String connectionId,
        String feedbackText,
        @Nullable String tableNameHint,
        @Nullable String columnNameHint,
        @Nullable String createdBy,
        @Nullable SchemaMetadata schema
    ) {
        if (connectionId == null || connectionId.isBlank() || feedbackText == null || feedbackText.isBlank()) {
            return 0;
        }

        if (!looksLikeSqlRule(feedbackText, tableNameHint, columnNameHint, schema)) {
            return 0;
        }

        List<SqlGuardrail> extracted = extractGuardrailsFromText(feedbackText, tableNameHint, columnNameHint, schema);
        if (extracted.isEmpty()) {
            return 0;
        }

        List<BrainRule> existingRules = brainRuleRepository
            .findByConnectionIdAndIsActiveTrueOrderByCreatedAtDesc(connectionId);

        int learnedCount = 0;
        for (SqlGuardrail guardrail : extracted) {
            BrainRule match = findEquivalentRule(existingRules, guardrail);
            if (match != null) {
                Integer oldConfidence = match.getConfidence() != null ? match.getConfidence() : 50;
                Integer newConfidence = guardrail.confidence() != null ? guardrail.confidence() : 85;
                if (newConfidence > oldConfidence) {
                    match.setConfidence(newConfidence);
                    if (guardrail.sourceText() != null && !guardrail.sourceText().isBlank()) {
                        match.setRuleText(serializeRuleText(guardrail));
                    }
                    if (createdBy != null && !createdBy.isBlank()) {
                        match.setUpdatedBy(createdBy);
                    }
                    brainRuleRepository.save(match);
                }
                continue;
            }

            BrainRule newRule = BrainRule.builder()
                .connectionId(connectionId)
                .ruleType(toRuleType(guardrail.type()))
                .tableName(guardrail.tableName())
                .columnName(guardrail.columnName())
                .ruleText(serializeRuleText(guardrail))
                .confidence(guardrail.confidence() != null ? guardrail.confidence() : 85)
                .isActive(true)
                .createdBy(createdBy)
                .build();
            brainRuleRepository.save(newRule);
            existingRules.add(newRule);
            learnedCount++;
        }

        if (learnedCount > 0) {
            log.info("Learned {} SQL guardrail rules for connection {}", learnedCount, connectionId);
        }

        return learnedCount;
    }

    /**
     * Heuristic text-to-rule extraction.
     *
     * The parser is intentionally generic: it does not hardcode specific table names so
     * the same logic can work across different customer schemas.
     */
    public List<SqlGuardrail> extractGuardrailsFromText(
        String text,
        @Nullable String tableNameHint,
        @Nullable String columnNameHint,
        @Nullable SchemaMetadata schema
    ) {
        if (text == null || text.isBlank()) {
            return List.of();
        }

        String normalized = text.trim().replaceAll("\\s+", " ");
        String lower = normalized.toLowerCase(Locale.ROOT);

        Set<String> schemaTables = extractSchemaTableNames(schema);
        Set<String> schemaColumns = extractSchemaColumnNames(schema);
        Set<String> requiredTables = new LinkedHashSet<>();
        Set<String> prohibitedTables = new LinkedHashSet<>();
        Set<String> requiredJoinKeys = new LinkedHashSet<>();
        List<ColumnValueRule> requiredPredicates = new ArrayList<>();

        int insteadOfIdx = lower.indexOf("instead of");
        if (insteadOfIdx >= 0) {
            // "Use A instead of B" means A becomes required and B becomes prohibited.
            String preferredSegment = normalized.substring(0, insteadOfIdx);
            String avoidSegment = normalized.substring(insteadOfIdx + "instead of".length());
            requiredTables.addAll(extractMentionedTables(preferredSegment, schemaTables));
            prohibitedTables.addAll(extractMentionedTables(avoidSegment, schemaTables));
        } else {
            requiredTables.addAll(extractMentionedTables(normalized, schemaTables));
        }

        if (tableNameHint != null && !tableNameHint.isBlank() && requiredTables.isEmpty()
            && hasSqlContext(normalized)) {
            requiredTables.add(tableNameHint.toLowerCase(Locale.ROOT));
        }

        Matcher eqMatcher = EQUALITY_PATTERN.matcher(normalized);
        while (eqMatcher.find()) {
            String column = normalizeIdentifier(eqMatcher.group(1));
            String value = normalizeValue(eqMatcher.group(2));
            if (column.isBlank() || value.isBlank()) {
                continue;
            }
            if (!isKnownSchemaColumn(column, columnNameHint, schemaColumns)) {
                continue;
            }
            requiredPredicates.add(new ColumnValueRule(column, value));
        }

        Matcher joinOnMatcher = JOIN_COLUMN_PATTERN.matcher(normalized);
        while (joinOnMatcher.find()) {
            String key = normalizeIdentifier(joinOnMatcher.group(1));
            if (!key.isBlank() && isKnownSchemaColumn(key, columnNameHint, schemaColumns)) {
                requiredJoinKeys.add(key);
            }
        }

        Matcher joinEqMatcher = JOIN_EQUALS_PATTERN.matcher(normalized);
        while (joinEqMatcher.find()) {
            String left = normalizeIdentifier(lastSegment(joinEqMatcher.group(1)));
            String right = normalizeIdentifier(lastSegment(joinEqMatcher.group(2)));
            if (!left.isBlank() && isKnownSchemaColumn(left, columnNameHint, schemaColumns)) {
                requiredJoinKeys.add(left);
            }
            if (!right.isBlank() && isKnownSchemaColumn(right, columnNameHint, schemaColumns)) {
                requiredJoinKeys.add(right);
            }
        }

        if (columnNameHint != null && !columnNameHint.isBlank() && requiredPredicates.isEmpty() && lower.contains("values")) {
            // Column values teaching is useful for prompt context but should not force a single SQL literal.
            requiredJoinKeys.add(normalizeIdentifier(columnNameHint));
        }

        String scopedTable = null;
        if (tableNameHint != null && !tableNameHint.isBlank()) {
            scopedTable = normalizeIdentifier(tableNameHint);
        } else if (requiredTables.size() == 1) {
            scopedTable = requiredTables.iterator().next();
        }

        List<SqlGuardrail> guardrails = new ArrayList<>();

        for (String table : requiredTables) {
            guardrails.add(new SqlGuardrail(
                GuardrailType.REQUIRED_TABLE,
                table,
                null,
                null,
                normalized,
                90
            ));
        }

        for (String table : prohibitedTables) {
            guardrails.add(new SqlGuardrail(
                GuardrailType.PROHIBITED_TABLE,
                table,
                null,
                null,
                normalized,
                90
            ));
        }

        for (ColumnValueRule predicate : requiredPredicates) {
            guardrails.add(new SqlGuardrail(
                GuardrailType.REQUIRED_PREDICATE,
                scopedTable,
                predicate.column(),
                predicate.value(),
                normalized,
                70
            ));
        }

        for (String joinKey : requiredJoinKeys) {
            guardrails.add(new SqlGuardrail(
                GuardrailType.REQUIRED_JOIN_KEY,
                scopedTable,
                joinKey,
                null,
                normalized,
                80
            ));
        }

        return guardrails;
    }

    /**
     * Returns only the subset of rules relevant to the current question and connection.
     *
     * Selection is based on:
     * 1) active rules for the connection
     * 2) schema compatibility (when schema is available)
     * 3) token overlap between question and rule metadata/source text
     */
    public List<SqlGuardrail> resolveApplicableGuardrails(
        String connectionId,
        String userQuestion,
        @Nullable SchemaMetadata schema
    ) {
        if (connectionId == null || connectionId.isBlank()) {
            return List.of();
        }

        List<BrainRule> rules = brainRuleRepository.findByConnectionIdAndIsActiveTrueOrderByCreatedAtDesc(connectionId);
        if (rules.isEmpty()) {
            return List.of();
        }

        Set<String> schemaTables = extractSchemaTableNames(schema);
        Set<String> questionTokens = tokenize(userQuestion);

        List<SqlGuardrail> allGuardrails = rules.stream()
            .map(this::toGuardrail)
            .filter(Objects::nonNull)
            .filter(g -> g.confidence() != null && g.confidence() >= MIN_GUARDRAIL_CONFIDENCE)
            .filter(g -> isCompatibleWithSchema(g, schemaTables))
            .collect(Collectors.toList());

        if (questionTokens.isEmpty()) {
            return allGuardrails.stream().limit(20).collect(Collectors.toList());
        }

        return allGuardrails.stream()
            .filter(g -> isApplicableToQuestion(g, questionTokens))
            .sorted(Comparator.comparingInt((SqlGuardrail g) -> g.confidence() != null ? g.confidence() : 50).reversed())
            .limit(20)
            .collect(Collectors.toList());
    }

    public String buildGuardrailContext(List<SqlGuardrail> guardrails) {
        if (guardrails == null || guardrails.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("\n\n=== LEARNED SQL GUARDRAILS (connection-scoped) ===\n");
        sb.append("Apply these when generating SQL for matching business questions:\n");

        for (SqlGuardrail guardrail : guardrails) {
            switch (guardrail.type()) {
                case REQUIRED_TABLE -> sb.append("- Must include table `").append(guardrail.tableName()).append("`\n");
                case PROHIBITED_TABLE -> sb.append("- Must avoid table `").append(guardrail.tableName()).append("`\n");
                case REQUIRED_PREDICATE -> sb.append("- Must include predicate `")
                    .append(guardrail.columnName())
                    .append(" = '")
                    .append(guardrail.value())
                    .append("'`\n");
                case REQUIRED_JOIN_KEY -> sb.append("- Must join/filter on key `")
                    .append(guardrail.columnName())
                    .append("`\n");
            }
        }

        return sb.toString();
    }

    /**
     * Deterministic SQL validation pass before query execution.
     *
     * This is intentionally lightweight string-pattern validation so it can run
     * on every generated/repaired candidate SQL in the chat loop.
     */
    public SqlGuardrailEvaluation evaluateSql(String sql, List<SqlGuardrail> guardrails) {
        if (guardrails == null || guardrails.isEmpty() || sql == null || sql.isBlank()) {
            return new SqlGuardrailEvaluation(true, List.of(), 0);
        }

        String normalizedSql = sql.toLowerCase(Locale.ROOT);
        Set<String> tablesInSql = extractTablesFromSql(normalizedSql);
        List<String> violations = new ArrayList<>();

        for (SqlGuardrail guardrail : guardrails) {
            switch (guardrail.type()) {
                case REQUIRED_TABLE -> {
                    String table = lower(guardrail.tableName());
                    if (table != null && !tablesInSql.contains(table)) {
                        violations.add("Missing required table '" + guardrail.tableName() + "'");
                    }
                }
                case PROHIBITED_TABLE -> {
                    String table = lower(guardrail.tableName());
                    if (table != null && tablesInSql.contains(table)) {
                        violations.add("Uses prohibited table '" + guardrail.tableName() + "'");
                    }
                }
                case REQUIRED_PREDICATE -> {
                    String scopedTable = lower(guardrail.tableName());
                    if (scopedTable != null && !tablesInSql.contains(scopedTable)) {
                        continue;
                    }

                    String column = lower(guardrail.columnName());
                    String value = lower(guardrail.value());
                    if (column == null || value == null) {
                        continue;
                    }

                    Pattern predicatePattern = Pattern.compile(
                        "(?i)\\b" + Pattern.quote(column) + "\\b\\s*=\\s*['\"]?" + Pattern.quote(value) + "['\"]?"
                    );
                    if (!predicatePattern.matcher(normalizedSql).find()) {
                        violations.add("Missing required predicate '" + guardrail.columnName() + "=" + guardrail.value() + "'");
                    }
                }
                case REQUIRED_JOIN_KEY -> {
                    String scopedTable = lower(guardrail.tableName());
                    if (scopedTable != null && !tablesInSql.contains(scopedTable)) {
                        continue;
                    }

                    String joinKey = lower(guardrail.columnName());
                    if (joinKey == null) {
                        continue;
                    }

                    Pattern joinPattern = Pattern.compile(
                        "(?i)\\b(on|where)\\b[^;\\n]*\\b" + Pattern.quote(joinKey) + "\\b"
                    );
                    if (!joinPattern.matcher(normalizedSql).find()) {
                        violations.add("Missing required join key '" + guardrail.columnName() + "'");
                    }
                }
            }
        }

        return new SqlGuardrailEvaluation(violations.isEmpty(), violations, guardrails.size());
    }

    public List<BrainRule> getActiveRules(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return List.of();
        }
        return brainRuleRepository.findByConnectionIdAndIsActiveTrueOrderByCreatedAtDesc(connectionId);
    }

    @Transactional
    public boolean deactivateRule(String ruleId) {
        if (ruleId == null || ruleId.isBlank()) {
            return false;
        }
        return brainRuleRepository.findById(ruleId).map(rule -> {
            rule.setIsActive(false);
            brainRuleRepository.save(rule);
            log.info("Deactivated brain rule {} (type={}, table={}, column={})",
                ruleId, rule.getRuleType(), rule.getTableName(), rule.getColumnName());
            return true;
        }).orElse(false);
    }

    private boolean looksLikeSqlRule(String text, @Nullable String tableHint, @Nullable String columnHint) {
        return looksLikeSqlRule(text, tableHint, columnHint, null);
    }

    /**
     * Schema-aware variant used by learnFromFeedback when no table/column hints are provided.
     * Requires either explicit signal phrases OR a known schema table name in the text.
     */
    private boolean looksLikeSqlRule(String text, @Nullable String tableHint, @Nullable String columnHint,
                                     @Nullable SchemaMetadata schema) {
        if (text == null || text.isBlank()) {
            return false;
        }

        String normalized = text.trim().toLowerCase(Locale.ROOT);
        boolean hasHints = (tableHint != null && !tableHint.isBlank()) || (columnHint != null && !columnHint.isBlank());
        boolean hasSqlContext = hasSqlContext(text);

        Set<String> schemaTables = extractSchemaTableNames(schema);
        Set<String> schemaColumns = extractSchemaColumnNames(schema);
        boolean mentionsSchemaTable = containsAnyToken(normalized, schemaTables);
        boolean mentionsSchemaColumn = containsAnyToken(normalized, schemaColumns);
        boolean hasExplicitSignal = RULE_SIGNAL_PATTERN.matcher(text).find();

        if (hasHints && (hasSqlContext || normalized.contains("values"))) {
            return true;
        }
        if (hasExplicitSignal && (hasSqlContext || mentionsSchemaTable || mentionsSchemaColumn)) {
            return true;
        }
        if (mentionsSchemaTable && hasSqlContext) {
            return true;
        }
        if (mentionsSchemaColumn && EQUALITY_PATTERN.matcher(text).find()) {
            return true;
        }
        return false;
    }

    private Set<String> extractMentionedTables(String segment, Set<String> schemaTables) {
        if (segment == null || segment.isBlank()) {
            return Set.of();
        }

        String lowerSegment = segment.toLowerCase(Locale.ROOT);
        Set<String> mentioned = new LinkedHashSet<>();

        for (String table : schemaTables) {
            Pattern p = Pattern.compile("(?i)(^|[^a-z0-9_])" + Pattern.quote(table) + "([^a-z0-9_]|$)");
            if (p.matcher(lowerSegment).find()) {
                mentioned.add(table);
            }
        }

        return mentioned;
    }

    private Set<String> extractSchemaTableNames(@Nullable SchemaMetadata schema) {
        if (schema == null || schema.getTables() == null || schema.getTables().isEmpty()) {
            return Set.of();
        }

        return schema.getTables().stream()
            .map(TableMetadata::getName)
            .filter(Objects::nonNull)
            .map(name -> name.toLowerCase(Locale.ROOT))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private Set<String> extractSchemaColumnNames(@Nullable SchemaMetadata schema) {
        if (schema == null || schema.getTables() == null || schema.getTables().isEmpty()) {
            return Set.of();
        }

        Set<String> columns = new LinkedHashSet<>();
        for (TableMetadata table : schema.getTables()) {
            if (table == null || table.getColumns() == null) {
                continue;
            }
            for (var column : table.getColumns()) {
                if (column == null || column.getName() == null) {
                    continue;
                }
                String normalized = normalizeIdentifier(column.getName());
                if (!normalized.isBlank()) {
                    columns.add(normalized);
                }
            }
        }
        return columns;
    }

    private boolean containsAnyToken(String text, Set<String> candidates) {
        if (text == null || text.isBlank() || candidates == null || candidates.isEmpty()) {
            return false;
        }
        for (String candidate : candidates) {
            if (candidate == null || candidate.isBlank()) {
                continue;
            }
            Pattern tokenPattern = Pattern.compile(
                "(?i)(^|[^a-z0-9_])" + Pattern.quote(candidate) + "([^a-z0-9_]|$)"
            );
            if (tokenPattern.matcher(text).find()) {
                return true;
            }
        }
        return false;
    }

    private boolean hasSqlContext(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        return SQL_CONTEXT_PATTERN.matcher(text).find()
            || EQUALITY_PATTERN.matcher(text).find()
            || JOIN_EQUALS_PATTERN.matcher(text).find();
    }

    private boolean isKnownSchemaColumn(String column, @Nullable String columnHint, Set<String> schemaColumns) {
        if (column == null || column.isBlank()) {
            return false;
        }

        String normalizedColumn = normalizeIdentifier(column);
        if (columnHint != null && !columnHint.isBlank()) {
            String normalizedHint = normalizeIdentifier(columnHint);
            if (normalizedColumn.equalsIgnoreCase(normalizedHint)) {
                return true;
            }
        }
        if (schemaColumns == null || schemaColumns.isEmpty()) {
            return true;
        }
        return schemaColumns.contains(normalizedColumn.toLowerCase(Locale.ROOT));
    }

    private Set<String> extractTablesFromSql(String sql) {
        if (sql == null || sql.isBlank()) {
            return Set.of();
        }

        Set<String> tables = new HashSet<>();
        Matcher matcher = TABLE_REF_PATTERN.matcher(sql);
        while (matcher.find()) {
            String tableToken = normalizeIdentifier(lastSegment(matcher.group(2)));
            if (!tableToken.isBlank()) {
                tables.add(tableToken.toLowerCase(Locale.ROOT));
            }
        }
        return tables;
    }

    private boolean isCompatibleWithSchema(SqlGuardrail guardrail, Set<String> schemaTables) {
        if (guardrail == null) {
            return false;
        }
        if (guardrail.tableName() == null || guardrail.tableName().isBlank()) {
            // Rules without a concrete table (e.g. join-key or predicate hints) remain valid.
            return true;
        }
        if (schemaTables == null || schemaTables.isEmpty()) {
            // If schema is unavailable, keep rule and let question matching decide applicability.
            return true;
        }
        return schemaTables.contains(guardrail.tableName().toLowerCase(Locale.ROOT));
    }

    private boolean isApplicableToQuestion(SqlGuardrail guardrail, Set<String> questionTokens) {
        if (guardrail == null || questionTokens == null || questionTokens.isEmpty()) {
            return false;
        }

        Set<String> ruleTokens = new HashSet<>();
        addToken(ruleTokens, guardrail.tableName());
        addToken(ruleTokens, guardrail.columnName());
        addToken(ruleTokens, guardrail.value());

        if (guardrail.sourceText() != null) {
            ruleTokens.addAll(tokenize(guardrail.sourceText()));
        }

        if (ruleTokens.isEmpty()) {
            return false;
        }

        int overlap = overlapCount(questionTokens, ruleTokens);
        if (overlap == 0) {
            return false;
        }

        Set<String> tableTokens = tokenize(guardrail.tableName());
        Set<String> columnTokens = tokenize(guardrail.columnName());
        Set<String> valueTokens = tokenize(guardrail.value());

        boolean mentionsTable = !tableTokens.isEmpty() && overlapCount(questionTokens, tableTokens) > 0;
        boolean mentionsColumn = !columnTokens.isEmpty() && overlapCount(questionTokens, columnTokens) > 0;
        boolean mentionsValue = !valueTokens.isEmpty() && overlapCount(questionTokens, valueTokens) > 0;

        return switch (guardrail.type()) {
            case REQUIRED_TABLE, PROHIBITED_TABLE -> mentionsTable;
            case REQUIRED_PREDICATE, REQUIRED_JOIN_KEY -> {
                if (!tableTokens.isEmpty()) {
                    // Scoped guardrails require the table plus another related signal.
                    yield mentionsTable && (mentionsColumn || mentionsValue || overlap >= 2);
                }
                // Unscoped guardrails must strongly match to avoid cross-domain leakage.
                yield mentionsColumn && (mentionsValue || overlap >= 2);
            }
        };
    }

    private int overlapCount(Set<String> left, Set<String> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        int overlap = 0;
        for (String token : left) {
            if (right.contains(token)) {
                overlap++;
            }
        }
        return overlap;
    }

    private void addToken(Set<String> target, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        target.addAll(tokenize(value));
    }

    private Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }

        String[] parts = text.toLowerCase(Locale.ROOT).split("[^a-z0-9_]+");
        Set<String> tokens = new HashSet<>();
        for (String part : parts) {
            if (part == null || part.length() < 2 || STOP_WORDS.contains(part)) {
                continue;
            }
            tokens.add(part);
        }
        return tokens;
    }

    private BrainRule findEquivalentRule(List<BrainRule> existingRules, SqlGuardrail guardrail) {
        if (existingRules == null || existingRules.isEmpty() || guardrail == null) {
            return null;
        }

        String targetRuleType = toRuleType(guardrail.type());
        String targetTable = lower(guardrail.tableName());
        String targetColumn = lower(guardrail.columnName());
        String targetValue = lower(guardrail.value());

        for (BrainRule rule : existingRules) {
            if (!Objects.equals(rule.getRuleType(), targetRuleType)) {
                continue;
            }
            if (!Objects.equals(lower(rule.getTableName()), targetTable)) {
                continue;
            }
            if (!Objects.equals(lower(rule.getColumnName()), targetColumn)) {
                continue;
            }

            if (guardrail.type() == GuardrailType.REQUIRED_PREDICATE) {
                String existingValue = lower(extractStoredValue(rule.getRuleText()));
                if (!Objects.equals(existingValue, targetValue)) {
                    continue;
                }
            }

            return rule;
        }

        return null;
    }

    private SqlGuardrail toGuardrail(BrainRule rule) {
        if (rule == null || rule.getRuleType() == null) {
            return null;
        }

        GuardrailType type = switch (rule.getRuleType()) {
            case RULE_REQUIRED_TABLE -> GuardrailType.REQUIRED_TABLE;
            case RULE_PROHIBITED_TABLE -> GuardrailType.PROHIBITED_TABLE;
            case RULE_REQUIRED_PREDICATE -> GuardrailType.REQUIRED_PREDICATE;
            case RULE_REQUIRED_JOIN_KEY -> GuardrailType.REQUIRED_JOIN_KEY;
            default -> null;
        };

        if (type == null) {
            return null;
        }

        String sourceText = extractStoredSource(rule.getRuleText());
        String value = type == GuardrailType.REQUIRED_PREDICATE
            ? extractStoredValue(rule.getRuleText())
            : null;

        return new SqlGuardrail(
            type,
            lower(rule.getTableName()),
            lower(rule.getColumnName()),
            lower(value),
            sourceText,
            rule.getConfidence()
        );
    }

    private String serializeRuleText(SqlGuardrail guardrail) {
        String source = normalizeSource(guardrail.sourceText());

        if (guardrail.type() == GuardrailType.REQUIRED_PREDICATE) {
            String value = guardrail.value() != null ? guardrail.value().trim() : "";
            return TEXT_PREFIX_VALUE + value + "\n" + TEXT_PREFIX_SOURCE + source;
        }

        return TEXT_PREFIX_SOURCE + source;
    }

    private String extractStoredValue(String ruleText) {
        if (ruleText == null || ruleText.isBlank()) {
            return null;
        }

        for (String line : ruleText.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith(TEXT_PREFIX_VALUE)) {
                return trimmed.substring(TEXT_PREFIX_VALUE.length()).trim();
            }
        }

        return null;
    }

    private String extractStoredSource(String ruleText) {
        if (ruleText == null || ruleText.isBlank()) {
            return "";
        }

        for (String line : ruleText.split("\\n")) {
            String trimmed = line.trim();
            if (trimmed.toLowerCase(Locale.ROOT).startsWith(TEXT_PREFIX_SOURCE)) {
                return trimmed.substring(TEXT_PREFIX_SOURCE.length()).trim();
            }
        }

        return ruleText.trim();
    }

    private String normalizeSource(String source) {
        if (source == null || source.isBlank()) {
            return "user feedback";
        }

        String normalized = source.trim().replaceAll("\\s+", " ");
        int maxChars = 400;
        return normalized.length() <= maxChars ? normalized : normalized.substring(0, maxChars);
    }

    private String normalizeIdentifier(String identifier) {
        if (identifier == null) {
            return "";
        }
        String trimmed = identifier.trim().replaceAll("[`\"]", "");
        return trimmed.toLowerCase(Locale.ROOT);
    }

    private String normalizeValue(String value) {
        if (value == null) {
            return "";
        }
        return value.trim().replaceAll("[`\"]", "").toLowerCase(Locale.ROOT);
    }

    private String lastSegment(String identifier) {
        if (identifier == null) {
            return "";
        }
        String cleaned = identifier.trim().replaceAll("[`\"]", "");
        int idx = cleaned.lastIndexOf('.');
        if (idx >= 0 && idx < cleaned.length() - 1) {
            return cleaned.substring(idx + 1);
        }
        return cleaned;
    }

    private String lower(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.toLowerCase(Locale.ROOT);
    }

    private String toRuleType(GuardrailType type) {
        return switch (type) {
            case REQUIRED_TABLE -> RULE_REQUIRED_TABLE;
            case PROHIBITED_TABLE -> RULE_PROHIBITED_TABLE;
            case REQUIRED_PREDICATE -> RULE_REQUIRED_PREDICATE;
            case REQUIRED_JOIN_KEY -> RULE_REQUIRED_JOIN_KEY;
        };
    }

    private record ColumnValueRule(String column, String value) {
    }
}
