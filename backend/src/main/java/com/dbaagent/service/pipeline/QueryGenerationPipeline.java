package com.dbaagent.service.pipeline;

import com.dbaagent.model.ColumnMetadata;
import com.dbaagent.model.RelationshipMetadata;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.SemanticJoinModel;
import com.dbaagent.model.SemanticTableModel;
import com.dbaagent.model.TableMetadata;
import com.dbaagent.model.TrainingDataEmbedding;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.service.ConnectionService;
import com.dbaagent.service.SchemaTableMatchUtil;
import com.dbaagent.service.SemanticModelService;
import com.dbaagent.service.SqlExecutionPipeline;
import com.dbaagent.service.TrainingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

@Service
public class QueryGenerationPipeline {

    private static final Logger log = LoggerFactory.getLogger(QueryGenerationPipeline.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final java.util.regex.Pattern QUALIFIED_COLUMN_REFERENCE =
        java.util.regex.Pattern.compile("\\b([A-Za-z_][\\w$]*)\\.([A-Za-z_][\\w$]*)\\b");

    private final TrainingService trainingService;
    private final ConnectionService connectionService;
    private final SqlExecutionPipeline sqlExecutionPipeline;
    private final ChatClient chatClient;
    private final Resource sqlAdaptationPrompt;
    private final Resource tableResolutionPrompt;
    private final ColumnValueFetcher columnValueFetcher;
    private final SqlValidator sqlValidator;
    private final SemanticModelService semanticModelService;
    private final double historyMatchThreshold;

    @org.springframework.beans.factory.annotation.Autowired
    public QueryGenerationPipeline(
            TrainingService trainingService,
            ConnectionService connectionService,
            DatabaseProviderRegistry providerRegistry,
            SqlExecutionPipeline sqlExecutionPipeline,
            SemanticModelService semanticModelService,
            ChatModel chatModel,
            @Value("classpath:prompts/sql-adaptation-prompt.st") Resource sqlAdaptationPrompt,
            @Value("classpath:prompts/table-resolution-prompt.st") Resource tableResolutionPrompt,
            @Value("${app.pipeline.history-match-threshold:0.92}") double historyMatchThreshold,
            @Value("${app.pipeline.column-values.max-per-column:50}") int maxValuesPerColumn,
            @Value("${app.pipeline.column-values.max-columns:10}") int maxFilterColumns,
            @Value("${app.pipeline.column-values.timeout-ms:3000}") int queryTimeoutMs
    ) {
        this.trainingService = trainingService;
        this.connectionService = connectionService;
        this.sqlExecutionPipeline = sqlExecutionPipeline;
        this.chatClient = ChatClient.builder(chatModel).build();
        this.sqlAdaptationPrompt = sqlAdaptationPrompt;
        this.tableResolutionPrompt = tableResolutionPrompt;
        this.historyMatchThreshold = historyMatchThreshold;
        int queryTimeoutSeconds = Math.max(1, queryTimeoutMs / 1000);
        this.columnValueFetcher = new ColumnValueFetcher(connectionService, providerRegistry, maxValuesPerColumn, maxFilterColumns, queryTimeoutSeconds);
        this.sqlValidator = new SqlValidator(connectionService, providerRegistry, queryTimeoutSeconds);
        this.semanticModelService = semanticModelService;
    }

    // Constructor for testing (accepts pre-built helpers)
    QueryGenerationPipeline(
            TrainingService trainingService,
            ConnectionService connectionService,
            SqlExecutionPipeline sqlExecutionPipeline,
            ChatClient chatClient,
            Resource sqlAdaptationPrompt,
            Resource tableResolutionPrompt,
            double historyMatchThreshold,
            ColumnValueFetcher columnValueFetcher,
            SqlValidator sqlValidator,
            SemanticModelService semanticModelService
    ) {
        this.trainingService = trainingService;
        this.connectionService = connectionService;
        this.sqlExecutionPipeline = sqlExecutionPipeline;
        this.chatClient = chatClient;
        this.sqlAdaptationPrompt = sqlAdaptationPrompt;
        this.tableResolutionPrompt = tableResolutionPrompt;
        this.historyMatchThreshold = historyMatchThreshold;
        this.columnValueFetcher = columnValueFetcher;
        this.sqlValidator = sqlValidator;
        this.semanticModelService = semanticModelService;
    }

    @Value("${app.pipeline.enabled:true}")
    private boolean pipelineEnabled;

    /**
     * Execute the full multi-step pipeline.
     * When disabled, returns empty result — ChatService falls back to single-shot.
     */
    public PipelineResult execute(PipelineContext ctx) {
        if (!pipelineEnabled) {
            return new PipelineResult(null, null, false, ResolvedContext.empty(),
                ColumnValueContext.empty(), null, List.of("disabled"), 0L);
        }

        long start = System.currentTimeMillis();
        var stepsExecuted = new ArrayList<String>();

        // Step 1: Query history matching
        ctx.progressListener().onProgress("history_match", "Checking query history...", Map.of());
        var historyMatch = matchQueryHistory(ctx.connectionId(), ctx.userQuestion());
        stepsExecuted.add("history_match");

        if (historyMatch.isPresent()) {
            var adapted = historyMatch.get();
            // Step 1 EXPLAIN is intentionally always-on (not gated by explain-validation flag).
            // History-adapted SQL bypasses the main LLM — validation here is the only safety net.
            var validation = sqlValidator.validate(ctx.connectionId(), adapted.adaptedSql(), ctx.dbType());
            stepsExecuted.add("validation");

            if (validation.valid()) {
                return new PipelineResult(
                    adapted.adaptedSql(), adapted.syntheticResponse(),
                    true, ResolvedContext.empty(), ColumnValueContext.empty(),
                    validation, stepsExecuted, System.currentTimeMillis() - start
                );
            }
            log.info("History match SQL failed EXPLAIN validation, proceeding with full pipeline");
        }

        // Step 2: Table/column resolution (with fast-path skip)
        ResolvedContext resolved;
        var fastPathTable = detectSingleTableFastPath(ctx);
        if (fastPathTable.isPresent()) {
            String table = fastPathTable.get();
            resolved = new ResolvedContext(
                List.of(table), Map.of(), List.of(), List.of(),
                ResolvedContext.Confidence.HIGH
            );
            stepsExecuted.add("table_resolution_fastpath");
            log.debug("Step 2 fast-path: single table '{}' detected, skipping LLM resolution", table);
        } else {
            ctx.progressListener().onProgress("table_resolution", "Resolving tables and columns...", Map.of());
            resolved = resolveTablesAndColumns(ctx);
            stepsExecuted.add("table_resolution");
        }

        // Step 3: Column value fetch
        if (!resolved.filterColumns().isEmpty()) {
            String columnNames = resolved.filterColumns().stream()
                .map(FilterColumn::column).collect(Collectors.joining(", "));
            ctx.progressListener().onProgress("value_fetch",
                "Fetching column values for " + columnNames + "...", Map.of());
        }
        var columnValues = columnValueFetcher.fetch(ctx.connectionId(), ctx.dbType(), resolved.filterColumns());
        stepsExecuted.add("value_fetch");

        // Step 4: SQL generation (enriched) — returns context for ChatService to use
        // The actual LLM call happens in ChatService (it owns chatClient and memory)
        stepsExecuted.add("sql_generation");

        return new PipelineResult(
            null, null,
            false, resolved, columnValues,
            null, stepsExecuted, System.currentTimeMillis() - start
        );
    }

    /**
     * Execute deterministic schema resolution only, skipping embedding-based history match.
     * This is the preferred path for agentic workflows where correctness matters more than
     * fuzzy recall from prior examples.
     */
    public PipelineResult resolveContextOnly(PipelineContext ctx) {
        if (!pipelineEnabled) {
            return new PipelineResult(null, null, false, ResolvedContext.empty(),
                ColumnValueContext.empty(), null, List.of("disabled"), 0L);
        }

        long start = System.currentTimeMillis();
        var stepsExecuted = new ArrayList<String>();

        ResolvedContext resolved;
        var fastPathTable = detectSingleTableFastPath(ctx);
        if (fastPathTable.isPresent()) {
            String table = fastPathTable.get();
            resolved = new ResolvedContext(
                List.of(table), Map.of(), List.of(), List.of(),
                ResolvedContext.Confidence.HIGH
            );
            stepsExecuted.add("table_resolution_fastpath");
        } else {
            ctx.progressListener().onProgress("table_resolution", "Resolving tables and columns...", Map.of());
            resolved = resolveTablesAndColumns(ctx);
            stepsExecuted.add("table_resolution");
        }

        ColumnValueContext columnValues = ColumnValueContext.empty();
        if (!resolved.filterColumns().isEmpty()) {
            String columnNames = resolved.filterColumns().stream()
                .map(FilterColumn::column)
                .collect(Collectors.joining(", "));
            ctx.progressListener().onProgress("value_fetch",
                "Fetching column values for " + columnNames + "...", Map.of());
            columnValues = columnValueFetcher.fetch(ctx.connectionId(), ctx.dbType(), resolved.filterColumns());
            stepsExecuted.add("value_fetch");
        }

        stepsExecuted.add("sql_generation");
        return new PipelineResult(
            null,
            null,
            false,
            resolved,
            columnValues,
            null,
            stepsExecuted,
            System.currentTimeMillis() - start
        );
    }

    /**
     * Step 1: Check query history for a close match.
     * If match found, calls adaptation LLM prompt and wraps result as synthetic response.
     */
    public Optional<AdaptedSqlResult> matchQueryHistory(String connectionId, String question) {
        try {
            var results = trainingService.cachedRetrieveRelevant(connectionId, question, 3);

            return results.stream()
                .filter(doc -> doc.getType() == TrainingDataEmbedding.TrainingDataType.QUERY_EXAMPLE)
                .filter(doc -> doc.getScore() != null && doc.getScore() >= historyMatchThreshold)
                .findFirst()
                .flatMap(doc -> {
                    String origSql = extractSqlFromQueryExample(doc.getContent(), doc.getMetadata());
                    if (origSql == null || origSql.isBlank()) return Optional.empty();

                    String origQuestion = extractQuestionFromMetadata(doc.getMetadata());

                    String adaptedSql = adaptSql(origQuestion, origSql, question);
                    if (adaptedSql == null || adaptedSql.isBlank()) return Optional.empty();

                    String cleanSql = sqlExecutionPipeline.extractSqlFromResponse(adaptedSql);
                    if (cleanSql == null || cleanSql.isBlank()) cleanSql = adaptedSql.trim();

                    String syntheticResponse = "Based on a similar previous query:\n```sql\n"
                        + cleanSql + "\n```";

                    return Optional.of(new AdaptedSqlResult(
                        cleanSql, syntheticResponse, origQuestion, doc.getScore()
                    ));
                });
        } catch (Exception e) {
            log.warn("Query history matching failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Adapt a previously approved SQL example to a new but similar question.
     * This keeps approval-based reuse explicit and separate from embedding retrieval.
     */
    public Optional<AdaptedSqlResult> adaptApprovedExample(String originalQuestion, String originalSql, String newQuestion) {
        if (originalQuestion == null || originalQuestion.isBlank()
            || originalSql == null || originalSql.isBlank()
            || newQuestion == null || newQuestion.isBlank()) {
            return Optional.empty();
        }
        try {
            String adaptedSql = adaptSql(originalQuestion, originalSql, newQuestion);
            if (adaptedSql == null || adaptedSql.isBlank()) {
                return Optional.empty();
            }

            String cleanSql = sqlExecutionPipeline.extractSqlFromResponse(adaptedSql);
            if (cleanSql == null || cleanSql.isBlank()) {
                cleanSql = adaptedSql.trim();
            }
            if (cleanSql.isBlank()) {
                return Optional.empty();
            }

            String syntheticResponse = "Using a previously approved query pattern:\n```sql\n"
                + cleanSql + "\n```";
            return Optional.of(new AdaptedSqlResult(cleanSql, syntheticResponse, originalQuestion, 1.0d));
        } catch (Exception e) {
            log.warn("Approved workflow SQL adaptation failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Extract SQL from QUERY_EXAMPLE doc. Content format: naturalLanguage + "\n" + sql.
     * Falls back to metadata JSON {"sql": "..."} if content format unexpected.
     */
    public String extractSqlFromQueryExample(String content, String metadata) {
        if (content != null && content.contains("\n")) {
            String candidateSql = content.substring(content.indexOf('\n') + 1).trim();
            if (candidateSql.toUpperCase().startsWith("SELECT")
                || candidateSql.toUpperCase().startsWith("WITH")) {
                return candidateSql;
            }
        }
        try {
            return mapper.readTree(metadata).path("sql").asText(null);
        } catch (Exception e) {
            return null;
        }
    }

    private String adaptSql(String origQuestion, String origSql, String newQuestion) {
        try {
            String template = loadPromptTemplate(sqlAdaptationPrompt);
            return chatClient.prompt()
                .system(template
                    .replace("{originalQuestion}", origQuestion)
                    .replace("{originalSql}", origSql)
                    .replace("{newQuestion}", newQuestion)
                    .replace("{columnValueContext}", ""))
                .user(newQuestion)
                .call()
                .content();
        } catch (Exception e) {
            log.warn("SQL adaptation call failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Fast-path: detect if the question clearly references a single known table.
     * Checks if exactly one table name from SchemaMetadata appears in the question
     * (case-insensitive word boundary match).
     */
    Optional<String> detectSingleTableFastPath(PipelineContext ctx) {
        if (ctx.schemaMetadata() == null) return Optional.empty();

        String normalizedQuestion = SchemaTableMatchUtil.normalizeQuestion(ctx.userQuestion());
        Map<String, TableMetadata> matchedByCanonicalName = new LinkedHashMap<>();

        for (TableMetadata table : ctx.schemaMetadata().getTables()) {
            if (table == null || table.getName() == null || !tableMentionedInQuestion(normalizedQuestion, table.getName())) {
                continue;
            }
            String canonicalKey = table.getName().toLowerCase(Locale.ROOT);
            TableMetadata existing = matchedByCanonicalName.get(canonicalKey);
            if (existing == null || compareFastPathCandidates(table, existing) > 0) {
                matchedByCanonicalName.put(canonicalKey, table);
            }
        }

        return matchedByCanonicalName.size() == 1
            ? Optional.of(matchedByCanonicalName.values().iterator().next().getName())
            : Optional.empty();
    }

    private int compareFastPathCandidates(TableMetadata left, TableMetadata right) {
        long leftRows = left.getRowCount() != null ? left.getRowCount() : 0L;
        long rightRows = right.getRowCount() != null ? right.getRowCount() : 0L;
        int rowCompare = Long.compare(leftRows, rightRows);
        if (rowCompare != 0) {
            return rowCompare;
        }

        int leftColumns = left.getColumns() != null ? left.getColumns().size() : 0;
        int rightColumns = right.getColumns() != null ? right.getColumns().size() : 0;
        int columnCompare = Integer.compare(leftColumns, rightColumns);
        if (columnCompare != 0) {
            return columnCompare;
        }

        boolean leftUpper = left.getName().equals(left.getName().toUpperCase(Locale.ROOT));
        boolean rightUpper = right.getName().equals(right.getName().toUpperCase(Locale.ROOT));
        if (leftUpper != rightUpper) {
            return leftUpper ? 1 : -1;
        }

        return right.getName().compareToIgnoreCase(left.getName()) * -1;
    }

    private boolean tableMentionedInQuestion(String normalizedQuestion, String tableName) {
        return SchemaTableMatchUtil.mentionsTable(normalizedQuestion, tableName);
    }

    /**
     * Step 2: Resolve tables and columns using LLM.
     */
    public ResolvedContext resolveTablesAndColumns(PipelineContext ctx) {
        try {
            Set<String> focusTables = ctx.ragContext() != null && ctx.ragContext().ragTableNames() != null
                ? ctx.ragContext().ragTableNames()
                : Set.of();
            String semanticContext = semanticModelService != null
                ? semanticModelService.buildSemanticModelContext(ctx.connectionId(), ctx.userQuestion(), focusTables)
                : "";
            String companyKnowledgeContext = ctx.ragContext() != null ? safe(ctx.ragContext().companyKnowledgeContext()) : "";
            String trainingContext = ctx.ragContext() != null ? safe(ctx.ragContext().trainingContext()) : "";
            String ragFocusTables = focusTables.isEmpty()
                ? ""
                : focusTables.stream().limit(12).collect(Collectors.joining(", "));
            String prompt = String.format(
                "Schema:\n%s\n\nSemantic Model:\n%s\n\nCompany Knowledge Hints:\n%s\n\nRetrieved Business Context:\n%s\n\nRAG Focus Tables:\n%s\n\nQuestion: %s",
                ctx.schemaContext(),
                semanticContext,
                companyKnowledgeContext,
                trainingContext,
                ragFocusTables,
                ctx.userQuestion()
            );

            String response = chatClient.prompt()
                .system(loadPromptTemplate(tableResolutionPrompt))
                .user(prompt)
                .call()
                .content();

            String json = extractJsonFromResponse(response);
            ResolvedContext parsed = parseResolvedContext(json, ctx.schemaMetadata());
            return postProcessResolvedContext(ctx.connectionId(), ctx.userQuestion(), ctx.schemaMetadata(), parsed);
        } catch (Exception e) {
            log.warn("Table resolution failed, proceeding without: {}", e.getMessage());
            return ResolvedContext.empty();
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    /**
     * Step 5: Validate SQL with EXPLAIN.
     */
    public ValidationResult validateSql(String connectionId, String sql, String dbType) {
        return sqlValidator.validate(connectionId, sql, dbType);
    }

    /**
     * Parse LLM response JSON into ResolvedContext, removing hallucinated tables AND columns.
     */
    public ResolvedContext parseResolvedContext(String json, SchemaMetadata schemaMetadata) {
        try {
            JsonNode root = mapper.readTree(json);

            Set<String> validTables = schemaMetadata != null
                ? schemaMetadata.getTables().stream()
                    .map(t -> t.getName()).collect(Collectors.toSet())
                : Set.of();
            Map<String, Set<String>> validColumns = new HashMap<>();
            if (schemaMetadata != null) {
                schemaMetadata.getTables().forEach(t ->
                    validColumns.put(t.getName(),
                        t.getColumns().stream().map(c -> c.getName()).collect(Collectors.toSet())));
            }

            Set<String> tables = new LinkedHashSet<>();
            root.path("tables").forEach(n -> {
                String canonicalTable = canonicalizeTableName(n.asText(), schemaMetadata);
                if (validTables.contains(canonicalTable)) {
                    tables.add(canonicalTable);
                }
            });

            Map<String, List<String>> columns = new LinkedHashMap<>();
            root.path("columns").fields().forEachRemaining(entry -> {
                String tableName = canonicalizeTableName(entry.getKey(), schemaMetadata);
                if (validTables.contains(tableName)) {
                    Set<String> tableColumns = validColumns.getOrDefault(tableName, Set.of());
                    var cols = new LinkedHashSet<String>();
                    entry.getValue().forEach(n -> {
                        if (tableColumns.contains(n.asText())) cols.add(n.asText());
                    });
                    if (!cols.isEmpty()) columns.put(tableName, List.copyOf(cols));
                }
            });

            Set<FilterColumn> filterColumns = new LinkedHashSet<>();
            root.path("filterColumns").forEach(n -> {
                String table = canonicalizeTableName(n.path("table").asText(), schemaMetadata);
                String column = n.path("column").asText();
                Set<String> tableColumns = validColumns.getOrDefault(table, Set.of());
                if (validTables.contains(table) && tableColumns.contains(column)) {
                    filterColumns.add(new FilterColumn(table, column));
                }
            });

            Set<String> joinConditions = new LinkedHashSet<>();
            root.path("joinConditions").forEach(n -> {
                String joinCondition = n.asText();
                if (isValidJoinCondition(joinCondition, validTables, validColumns)) {
                    joinConditions.add(canonicalizeJoinCondition(joinCondition, schemaMetadata));
                }
            });

            String confidenceStr = root.path("confidence").asText("MEDIUM");
            ResolvedContext.Confidence confidence;
            try {
                confidence = ResolvedContext.Confidence.valueOf(confidenceStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                confidence = ResolvedContext.Confidence.MEDIUM;
            }

            return new ResolvedContext(
                List.copyOf(tables), Map.copyOf(columns),
                List.copyOf(filterColumns), List.copyOf(joinConditions), confidence
            );
        } catch (Exception e) {
            log.warn("Failed to parse resolution JSON: {}", e.getMessage());
            return ResolvedContext.empty();
        }
    }

    private String canonicalizeTableName(String requestedName, SchemaMetadata schemaMetadata) {
        if (requestedName == null || requestedName.isBlank() || schemaMetadata == null || schemaMetadata.getTables() == null) {
            return requestedName;
        }

        List<TableMetadata> matches = schemaMetadata.getTables().stream()
            .filter(Objects::nonNull)
            .filter(table -> table.getName() != null && table.getName().equalsIgnoreCase(requestedName))
            .toList();
        if (matches.isEmpty()) {
            return requestedName;
        }
        if (matches.size() == 1) {
            return matches.getFirst().getName();
        }

        return matches.stream()
            .max((left, right) -> compareCanonicalCandidates(left, right, requestedName))
            .map(TableMetadata::getName)
            .orElse(requestedName);
    }

    private int compareCanonicalCandidates(TableMetadata left, TableMetadata right, String requestedName) {
        long leftRows = left.getRowCount() != null ? left.getRowCount() : 0L;
        long rightRows = right.getRowCount() != null ? right.getRowCount() : 0L;
        int rowCompare = Long.compare(leftRows, rightRows);
        if (rowCompare != 0) {
            return rowCompare;
        }

        int leftColumns = left.getColumns() != null ? left.getColumns().size() : 0;
        int rightColumns = right.getColumns() != null ? right.getColumns().size() : 0;
        int columnCompare = Integer.compare(leftColumns, rightColumns);
        if (columnCompare != 0) {
            return columnCompare;
        }

        boolean leftExact = requestedName != null && requestedName.equals(left.getName());
        boolean rightExact = requestedName != null && requestedName.equals(right.getName());
        if (leftExact != rightExact) {
            return leftExact ? 1 : -1;
        }

        return String.CASE_INSENSITIVE_ORDER.compare(left.getName(), right.getName());
    }

    private String canonicalizeJoinCondition(String joinCondition, SchemaMetadata schemaMetadata) {
        if (joinCondition == null || joinCondition.isBlank() || schemaMetadata == null || schemaMetadata.getTables() == null) {
            return joinCondition;
        }

        Matcher matcher = QUALIFIED_COLUMN_REFERENCE.matcher(joinCondition);
        StringBuffer rewritten = new StringBuffer();
        while (matcher.find()) {
            String canonicalTable = canonicalizeTableName(matcher.group(1), schemaMetadata);
            matcher.appendReplacement(
                rewritten,
                Matcher.quoteReplacement(canonicalTable + "." + matcher.group(2))
            );
        }
        matcher.appendTail(rewritten);
        return rewritten.toString();
    }

    private boolean isValidJoinCondition(
            String joinCondition,
            Set<String> validTables,
            Map<String, Set<String>> validColumns) {
        if (joinCondition == null || joinCondition.isBlank()) {
            return false;
        }

        String normalized = joinCondition.replace("`", "").replace("\"", "");
        var matcher = QUALIFIED_COLUMN_REFERENCE.matcher(normalized);
        boolean foundQualifiedReference = false;

        while (matcher.find()) {
            foundQualifiedReference = true;
            String table = matcher.group(1);
            String column = matcher.group(2);
            if (!validTables.contains(table)) {
                return false;
            }
            if (!validColumns.getOrDefault(table, Set.of()).contains(column)) {
                return false;
            }
        }

        return foundQualifiedReference;
    }

    /**
     * Build resolution hints for injection into system prompt.
     */
    public String buildResolutionHints(ResolvedContext resolved) {
        if (resolved.isEmpty()) return "";

        var sb = new StringBuilder();
        sb.append("Tables identified as relevant: ")
            .append(String.join(", ", resolved.tables())).append("\n");

        if (resolved.columns() != null && !resolved.columns().isEmpty()) {
            String columnHints = resolved.columns().entrySet().stream()
                .filter(entry -> entry.getValue() != null && !entry.getValue().isEmpty())
                .map(entry -> entry.getKey() + "[" + String.join(", ", entry.getValue()) + "]")
                .collect(Collectors.joining("; "));
            if (!columnHints.isBlank()) {
                sb.append("Resolved columns by table: ")
                    .append(columnHints)
                    .append("\n");
            }
        }

        if (!resolved.joinConditions().isEmpty()) {
            sb.append("Suggested joins: ")
                .append(String.join("; ", resolved.joinConditions())).append("\n");
        }

        if (!resolved.filterColumns().isEmpty()) {
            sb.append("Filter columns with known values: ")
                .append(resolved.filterColumns().stream()
                    .map(FilterColumn::qualifiedName)
                    .collect(Collectors.joining(", ")))
                .append("\n");
        }

        return sb.toString();
    }

    public String buildResolutionHints(String connectionId, ResolvedContext resolved) {
        String base = buildResolutionHints(resolved);
        if (semanticModelService == null || resolved == null || resolved.isEmpty()) {
            return base;
        }
        String semanticHints = semanticModelService.buildSemanticHints(connectionId, resolved.tables());
        if (semanticHints == null || semanticHints.isBlank()) {
            return base;
        }
        return base.isBlank() ? semanticHints : base + "\n" + semanticHints;
    }

    private String extractQuestionFromMetadata(String metadata) {
        try {
            return mapper.readTree(metadata).path("question").asText("");
        } catch (Exception e) {
            return "";
        }
    }

    private ResolvedContext postProcessResolvedContext(
            String connectionId,
            String question,
            SchemaMetadata schemaMetadata,
            ResolvedContext resolvedContext) {
        if (resolvedContext == null || resolvedContext.isEmpty() || schemaMetadata == null || semanticModelService == null) {
            return resolvedContext == null ? ResolvedContext.empty() : resolvedContext;
        }

        ResolvedContext sourceOfTruthResolved = promoteSourceOfTruthTable(connectionId, question, schemaMetadata, resolvedContext);
        ResolvedContext joinCompleted = completeLikelyJoinPath(connectionId, question, schemaMetadata, sourceOfTruthResolved);
        return alignRequestedDetailColumns(question, schemaMetadata, joinCompleted);
    }

    private ResolvedContext alignRequestedDetailColumns(
            String question,
            SchemaMetadata schemaMetadata,
            ResolvedContext resolvedContext) {
        if (resolvedContext == null
            || resolvedContext.tables().size() < 2
            || schemaMetadata == null
            || schemaMetadata.getTables() == null) {
            return resolvedContext;
        }

        Set<String> requestedKinds = requestedDetailKinds(question);
        if (requestedKinds.isEmpty()) {
            return resolvedContext;
        }

        Map<String, TableMetadata> schemaTables = schemaMetadata.getTables().stream()
            .filter(Objects::nonNull)
            .filter(table -> table.getName() != null)
            .collect(Collectors.toMap(
                table -> table.getName().toLowerCase(Locale.ROOT),
                table -> table,
                (left, right) -> left,
                LinkedHashMap::new
            ));

        Map<String, List<String>> normalizedColumns = new LinkedHashMap<>();
        resolvedContext.columns().forEach((table, columns) ->
            normalizedColumns.put(table, columns == null ? new ArrayList<>() : new ArrayList<>(columns))
        );
        resolvedContext.tables().forEach(table -> normalizedColumns.computeIfAbsent(table, ignored -> new ArrayList<>()));

        boolean changed = false;
        for (String detailKind : requestedKinds) {
            DetailColumnChoice bestChoice = bestDetailColumnChoice(question, resolvedContext.tables(), schemaTables, detailKind);
            if (bestChoice == null) {
                continue;
            }

            List<String> preferredColumns = normalizedColumns.computeIfAbsent(bestChoice.tableName(), ignored -> new ArrayList<>());
            if (!preferredColumns.contains(bestChoice.columnName())) {
                preferredColumns.add(bestChoice.columnName());
                changed = true;
            }

            for (Map.Entry<String, List<String>> entry : normalizedColumns.entrySet()) {
                if (entry.getKey() == null || entry.getKey().equalsIgnoreCase(bestChoice.tableName()) || entry.getValue() == null) {
                    continue;
                }
                List<String> filtered = entry.getValue().stream()
                    .filter(column -> !detailKind.equals(detailColumnKind(column)))
                    .collect(Collectors.toCollection(ArrayList::new));
                if (filtered.size() != entry.getValue().size()) {
                    normalizedColumns.put(entry.getKey(), filtered);
                    changed = true;
                }
            }
        }

        if (!changed) {
            return resolvedContext;
        }

        Map<String, List<String>> immutableColumns = normalizedColumns.entrySet().stream()
            .collect(Collectors.toMap(
                Map.Entry::getKey,
                entry -> List.copyOf(entry.getValue()),
                (left, right) -> left,
                LinkedHashMap::new
            ));

        return new ResolvedContext(
            resolvedContext.tables(),
            immutableColumns,
            resolvedContext.filterColumns(),
            resolvedContext.joinConditions(),
            resolvedContext.confidence()
        );
    }

    private ResolvedContext promoteSourceOfTruthTable(
            String connectionId,
            String question,
            SchemaMetadata schemaMetadata,
            ResolvedContext resolvedContext) {
        if (resolvedContext.tables().size() != 1 || !isSourceOfTruthQuestion(question)) {
            return resolvedContext;
        }

        String currentTable = resolvedContext.tables().getFirst();
        List<SemanticTableModel> relevantTables = semanticModelService.findRelevantTables(connectionId, question, Set.of());
        TableMetadata currentMetadata = findTable(schemaMetadata, currentTable);
        boolean currentDerived = looksDerivedLike(currentTable) || hasAggregateLikeMeasureColumns(currentMetadata);
        for (SemanticTableModel model : relevantTables) {
            if (model != null && model.getTableName() != null && model.getTableName().equalsIgnoreCase(currentTable)) {
                currentDerived = currentDerived || isDerivedSemanticTable(model);
            }
        }
        if (!currentDerived) {
            return resolvedContext;
        }

        for (SemanticTableModel candidate : relevantTables) {
            if (candidate == null || candidate.getTableName() == null) {
                continue;
            }
            if (candidate.getTableName().equalsIgnoreCase(currentTable)) {
                continue;
            }
            if (looksDerivedLike(candidate.getTableName()) || isDerivedSemanticTable(candidate)) {
                continue;
            }
            TableMetadata candidateMetadata = findTable(schemaMetadata, candidate.getTableName());
            if (hasAggregateLikeMeasureColumns(candidateMetadata) && !hasRawBusinessMeasure(candidateMetadata)) {
                continue;
            }
            if (!hasMeaningfulTableOverlap(question, candidate.getTableName(), currentTable)) {
                continue;
            }
            return new ResolvedContext(
                List.of(candidate.getTableName()),
                Map.of(),
                resolvedContext.filterColumns(),
                resolvedContext.joinConditions(),
                resolvedContext.confidence()
            );
        }

        TableMetadata fallbackCandidate = findSourceOfTruthFallbackCandidate(question, currentTable, schemaMetadata);
        if (fallbackCandidate != null) {
            return new ResolvedContext(
                List.of(fallbackCandidate.getName()),
                Map.of(),
                resolvedContext.filterColumns(),
                resolvedContext.joinConditions(),
                resolvedContext.confidence()
            );
        }
        return resolvedContext;
    }

    private TableMetadata findTable(SchemaMetadata schemaMetadata, String tableName) {
        if (schemaMetadata == null || schemaMetadata.getTables() == null || tableName == null || tableName.isBlank()) {
            return null;
        }
        return schemaMetadata.getTables().stream()
            .filter(Objects::nonNull)
            .filter(table -> table.getName() != null && table.getName().equalsIgnoreCase(tableName))
            .findFirst()
            .orElse(null);
    }

    private boolean hasAggregateLikeMeasureColumns(TableMetadata table) {
        if (table == null || table.getColumns() == null) {
            return false;
        }
        return table.getColumns().stream()
            .filter(Objects::nonNull)
            .map(ColumnMetadata::getName)
            .filter(Objects::nonNull)
            .map(name -> name.toLowerCase(Locale.ROOT))
            .anyMatch(this::isAggregateLikeMeasureColumn);
    }

    private boolean hasRawBusinessMeasure(TableMetadata table) {
        if (table == null || table.getColumns() == null) {
            return false;
        }
        return table.getColumns().stream()
            .filter(Objects::nonNull)
            .map(ColumnMetadata::getName)
            .filter(Objects::nonNull)
            .map(name -> name.toLowerCase(Locale.ROOT))
            .anyMatch(name -> !isAggregateLikeMeasureColumn(name)
                && (name.contains("amount")
                || name.contains("revenue")
                || name.contains("fee")
                || name.contains("price")
                || name.contains("tax")
                || name.contains("commission")
                || name.contains("cost")
                || name.contains("value")));
    }

    private boolean isAggregateLikeMeasureColumn(String normalizedName) {
        if (normalizedName == null || normalizedName.isBlank()) {
            return false;
        }
        return normalizedName.startsWith("total_")
            || normalizedName.startsWith("sum_")
            || normalizedName.startsWith("avg_")
            || normalizedName.startsWith("average_")
            || normalizedName.startsWith("count_")
            || normalizedName.startsWith("cnt_")
            || normalizedName.startsWith("max_")
            || normalizedName.startsWith("min_")
            || normalizedName.startsWith("ratio_")
            || normalizedName.startsWith("rate_")
            || normalizedName.endsWith("_total")
            || normalizedName.endsWith("_sum")
            || normalizedName.endsWith("_avg")
            || normalizedName.endsWith("_average")
            || normalizedName.endsWith("_count")
            || normalizedName.endsWith("_cnt")
            || normalizedName.endsWith("_ratio")
            || normalizedName.endsWith("_rate");
    }

    private ResolvedContext completeLikelyJoinPath(
            String connectionId,
            String question,
            SchemaMetadata schemaMetadata,
            ResolvedContext resolvedContext) {
        if (resolvedContext.tables().isEmpty() || !questionNeedsJoinCompletion(question)) {
            return resolvedContext;
        }

        Set<String> currentTables = resolvedContext.tables().stream()
            .filter(Objects::nonNull)
            .map(String::toLowerCase)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, List<JoinEdge>> adjacency = buildJoinAdjacency(
            schemaMetadata,
            semanticModelService.getSemanticJoins(connectionId, resolvedContext.tables())
        );
        if (adjacency.isEmpty()) {
            return resolvedContext;
        }

        Map<String, TableMetadata> schemaTables = schemaMetadata.getTables() == null
            ? Map.of()
            : schemaMetadata.getTables().stream()
                .filter(Objects::nonNull)
                .filter(table -> table.getName() != null)
                .collect(Collectors.toMap(table -> table.getName().toLowerCase(Locale.ROOT), table -> table, (left, right) -> left, LinkedHashMap::new));

        List<JoinCandidate> candidates = new ArrayList<>();
        for (String currentTable : currentTables) {
            for (JoinEdge join : adjacency.getOrDefault(currentTable, List.of())) {
                String other = currentTable.equals(join.sourceTable()) ? join.targetTable() : join.sourceTable();
                if (currentTables.contains(other)) {
                    continue;
                }
                TableMetadata table = schemaTables.get(other);
                if (table == null) {
                    continue;
                }
                int score = scoreJoinCompanion(question, table);
                if (score > 0) {
                    candidates.add(new JoinCandidate(table, join.joinExpression(), score));
                }
            }
        }

        if (candidates.isEmpty()) {
            return resolvedContext;
        }

        candidates.sort(Comparator.comparingInt(JoinCandidate::score)
            .reversed()
            .thenComparing(candidate -> candidate.table().getName(), String.CASE_INSENSITIVE_ORDER));
        JoinCandidate best = candidates.getFirst();
        JoinCandidate second = candidates.size() > 1 ? candidates.get(1) : null;
        if (best.score() < 52 || (second != null && best.score() - second.score() < 8)) {
            return resolvedContext;
        }

        LinkedHashSet<String> tables = new LinkedHashSet<>(resolvedContext.tables());
        tables.add(best.table().getName());
        LinkedHashSet<String> joins = new LinkedHashSet<>(resolvedContext.joinConditions());
        joins.add(best.joinCondition());
        return new ResolvedContext(
            List.copyOf(tables),
            resolvedContext.columns(),
            resolvedContext.filterColumns(),
            List.copyOf(joins),
            resolvedContext.confidence()
        );
    }

    private boolean isSourceOfTruthQuestion(String question) {
        String normalized = SchemaTableMatchUtil.normalizeQuestion(question);
        return normalized.contains(" count ")
            || normalized.contains(" how many ")
            || normalized.contains(" total ")
            || normalized.contains(" revenue ")
            || normalized.contains(" amount ")
            || normalized.contains(" list ")
            || normalized.contains(" details ")
            || normalized.contains(" detail ")
            || normalized.contains(" email ")
            || normalized.contains(" country ");
    }

    private boolean questionNeedsJoinCompletion(String question) {
        String normalized = SchemaTableMatchUtil.normalizeQuestion(question);
        return normalized.contains(" with ")
            || normalized.contains(" along with ")
            || normalized.contains(" alongside ")
            || normalized.contains(" including ")
            || normalized.contains(" details ")
            || normalized.contains(" email ")
            || normalized.contains(" country ")
            || normalized.contains(" amount ")
            || normalized.contains(" tax ")
            || normalized.contains(" taxes ")
            || normalized.contains(" name ");
    }

    private int scoreJoinCompanion(String question, TableMetadata table) {
        Set<String> questionTokens = meaningfulTokens(question);
        if (questionTokens.isEmpty() || table == null || table.getName() == null) {
            return 0;
        }
        int score = 0;
        for (String token : identifierTokens(table.getName())) {
            if (questionTokens.contains(token)) {
                score += 20;
            }
        }
        if (table.getColumns() != null) {
            for (ColumnMetadata column : table.getColumns()) {
                if (column == null || column.getName() == null) {
                    continue;
                }
                for (String token : identifierTokens(column.getName())) {
                    if (questionTokens.contains(token)) {
                        score += 16;
                    }
                }
            }
        }
        if (looksDetailTable(table) && mentionsDetail(question)) {
            score += 12;
        }
        if (looksMeasureTable(table) && mentionsMetric(question)) {
            score += 12;
        }
        if (mentionsNamedEntity(question, table.getName())) {
            score += 24;
        }
        return score;
    }

    private boolean mentionsNamedEntity(String question, String tableName) {
        String normalizedQuestion = SchemaTableMatchUtil.normalizeQuestion(question);
        return identifierTokens(tableName).stream()
            .anyMatch(token -> normalizedQuestion.contains(" " + token + " "));
    }

    private Map<String, List<JoinEdge>> buildJoinAdjacency(
        SchemaMetadata schemaMetadata,
        List<SemanticJoinModel> semanticJoins
    ) {
        Map<String, List<JoinEdge>> adjacency = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        if (schemaMetadata != null && schemaMetadata.getRelationships() != null) {
            for (RelationshipMetadata relationship : schemaMetadata.getRelationships()) {
                if (relationship == null
                    || relationship.getFromTable() == null
                    || relationship.getToTable() == null
                    || relationship.getFromColumn() == null
                    || relationship.getToColumn() == null) {
                    continue;
                }
                addJoinEdge(
                    adjacency,
                    seen,
                    relationship.getFromTable(),
                    relationship.getToTable(),
                    relationship.getFromTable() + "." + relationship.getFromColumn()
                        + " = " + relationship.getToTable() + "." + relationship.getToColumn()
                );
            }
        }
        if (semanticJoins != null) {
            for (SemanticJoinModel join : semanticJoins) {
                if (join == null || join.getSourceTable() == null || join.getTargetTable() == null || join.getJoinExpression() == null) {
                    continue;
                }
                addJoinEdge(adjacency, seen, join.getSourceTable(), join.getTargetTable(), join.getJoinExpression());
            }
        }
        return adjacency;
    }

    private void addJoinEdge(
        Map<String, List<JoinEdge>> adjacency,
        Set<String> seen,
        String sourceTable,
        String targetTable,
        String joinExpression
    ) {
        String source = sourceTable.toLowerCase(Locale.ROOT);
        String target = targetTable.toLowerCase(Locale.ROOT);
        String key = source + "|" + target + "|" + joinExpression.toLowerCase(Locale.ROOT);
        if (!seen.add(key)) {
            return;
        }
        JoinEdge edge = new JoinEdge(source, target, joinExpression);
        adjacency.computeIfAbsent(source, ignored -> new ArrayList<>()).add(edge);
        adjacency.computeIfAbsent(target, ignored -> new ArrayList<>()).add(edge);
    }

    private boolean hasMeaningfulTableOverlap(String question, String candidateTable, String currentTable) {
        Set<String> questionTokens = meaningfulTokens(question);
        Set<String> candidateTokens = new LinkedHashSet<>(identifierTokens(candidateTable));
        Set<String> currentTokens = new LinkedHashSet<>(identifierTokens(currentTable));
        return candidateTokens.stream().anyMatch(questionTokens::contains)
            || candidateTokens.stream().anyMatch(currentTokens::contains);
    }

    private TableMetadata findSourceOfTruthFallbackCandidate(
            String question,
            String currentTable,
            SchemaMetadata schemaMetadata) {
        if (schemaMetadata == null || schemaMetadata.getTables() == null) {
            return null;
        }

        return schemaMetadata.getTables().stream()
            .filter(Objects::nonNull)
            .filter(table -> table.getName() != null)
            .filter(table -> !table.getName().equalsIgnoreCase(currentTable))
            .filter(table -> !looksDerivedLike(table.getName()))
            .filter(this::hasRawBusinessMeasure)
            .filter(table -> hasMeaningfulTableOverlap(question, table.getName(), currentTable))
            .max(Comparator.comparingInt(table -> sourceOfTruthFallbackScore(question, currentTable, table)))
            .orElse(null);
    }

    private int sourceOfTruthFallbackScore(String question, String currentTable, TableMetadata candidate) {
        Set<String> questionTokens = meaningfulTokens(question);
        int score = 0;
        score += (int) identifierTokens(candidate.getName()).stream()
            .filter(questionTokens::contains)
            .count() * 24;
        score += (int) identifierTokens(candidate.getName()).stream()
            .filter(identifierTokens(currentTable)::contains)
            .count() * 18;
        if (candidate.getColumns() != null) {
            for (ColumnMetadata column : candidate.getColumns()) {
                if (column == null || column.getName() == null) {
                    continue;
                }
                Set<String> columnTokens = new LinkedHashSet<>(identifierTokens(column.getName()));
                int matches = (int) columnTokens.stream().filter(questionTokens::contains).count();
                if (matches == 0) {
                    continue;
                }
                score += matches * 16;
                if (!isAggregateLikeMeasureColumn(column.getName().toLowerCase(Locale.ROOT))) {
                    score += 12;
                }
            }
        }
        return score;
    }

    private boolean mentionsDetail(String question) {
        String normalized = SchemaTableMatchUtil.normalizeQuestion(question);
        return normalized.contains(" detail ")
            || normalized.contains(" details ")
            || normalized.contains(" email ")
            || normalized.contains(" country ")
            || normalized.contains(" name ");
    }

    private boolean mentionsMetric(String question) {
        String normalized = SchemaTableMatchUtil.normalizeQuestion(question);
        return normalized.contains(" amount ")
            || normalized.contains(" revenue ")
            || normalized.contains(" total ")
            || normalized.contains(" count ")
            || normalized.contains(" mrr ")
            || normalized.contains(" booking ");
    }

    private boolean looksDetailTable(TableMetadata table) {
        return table.getColumns() != null && table.getColumns().stream()
            .filter(Objects::nonNull)
            .map(ColumnMetadata::getName)
            .filter(Objects::nonNull)
            .map(String::toLowerCase)
            .anyMatch(name -> name.contains("name") || name.contains("email") || name.contains("country"));
    }

    private boolean looksMeasureTable(TableMetadata table) {
        return table.getColumns() != null && table.getColumns().stream()
            .filter(Objects::nonNull)
            .map(ColumnMetadata::getName)
            .filter(Objects::nonNull)
            .map(String::toLowerCase)
            .anyMatch(name -> name.contains("amount") || name.contains("revenue") || name.contains("price") || name.contains("tax"));
    }

    private boolean looksDerivedLike(String tableName) {
        String normalized = SchemaTableMatchUtil.normalizeQuestion(tableName);
        return normalized.contains(" aggregate ")
            || normalized.contains(" aggregation ")
            || normalized.contains(" summary ")
            || normalized.contains(" trend ")
            || normalized.contains(" report ")
            || normalized.contains(" insight ")
            || normalized.contains(" analytics ")
            || normalized.contains(" snapshot ");
    }

    private Set<String> requestedDetailKinds(String question) {
        String normalized = SchemaTableMatchUtil.normalizeQuestion(question);
        Set<String> kinds = new LinkedHashSet<>();
        boolean emailRequested = normalized.contains(" email ") || normalized.contains(" emails ");
        if (emailRequested) {
            kinds.add("email");
        }
        if (normalized.contains(" name ") || normalized.contains(" names ")) {
            kinds.add("name");
        }
        if (normalized.contains(" country ") || normalized.contains(" countries ")) {
            kinds.add("country");
        }
        if (normalized.contains(" city ") || normalized.contains(" cities ")) {
            kinds.add("city");
        }
        if (normalized.contains(" state ") || normalized.contains(" states ")) {
            kinds.add("state");
        }
        if (normalized.contains(" address ") || normalized.contains(" addresses ")) {
            kinds.add("address");
        }
        if (normalized.contains(" contact ") || normalized.contains(" contacts ")) {
            kinds.add("contact");
        }
        if (emailRequested && mentionsPersonOrEntity(normalized)) {
            kinds.add("name");
        }
        return kinds;
    }

    private boolean mentionsPersonOrEntity(String normalizedQuestion) {
        return normalizedQuestion.contains(" guest ")
            || normalizedQuestion.contains(" guests ")
            || normalizedQuestion.contains(" customer ")
            || normalizedQuestion.contains(" customers ")
            || normalizedQuestion.contains(" user ")
            || normalizedQuestion.contains(" users ")
            || normalizedQuestion.contains(" member ")
            || normalizedQuestion.contains(" members ")
            || normalizedQuestion.contains(" hotel ")
            || normalizedQuestion.contains(" hotels ")
            || normalizedQuestion.contains(" account ")
            || normalizedQuestion.contains(" accounts ");
    }

    private DetailColumnChoice bestDetailColumnChoice(
            String question,
            List<String> resolvedTables,
            Map<String, TableMetadata> schemaTables,
            String detailKind) {
        Set<String> questionTokens = meaningfulTokens(question);
        DetailColumnChoice best = null;
        for (String tableName : resolvedTables) {
            if (tableName == null) {
                continue;
            }
            TableMetadata table = schemaTables.get(tableName.toLowerCase(Locale.ROOT));
            if (table == null || table.getColumns() == null) {
                continue;
            }
            for (ColumnMetadata column : table.getColumns()) {
                if (column == null || column.getName() == null || !detailKind.equals(detailColumnKind(column.getName()))) {
                    continue;
                }
                int score = scoreDetailColumnProvider(questionTokens, table, column);
                if (best == null || score > best.score()) {
                    best = new DetailColumnChoice(table.getName(), column.getName(), score);
                }
            }
        }
        return best;
    }

    private int scoreDetailColumnProvider(Set<String> questionTokens, TableMetadata table, ColumnMetadata column) {
        int score = 0;
        score += (int) identifierTokens(table.getName()).stream()
            .filter(questionTokens::contains)
            .count() * 32;
        score += (int) identifierTokens(column.getName()).stream()
            .filter(questionTokens::contains)
            .count() * 18;
        if (looksDetailTable(table)) {
            score += 24;
        }
        if (looksFactLikeTable(table)) {
            score -= 18;
        }
        if (table.getColumns() != null) {
            long distinctDetailKinds = table.getColumns().stream()
                .filter(Objects::nonNull)
                .map(ColumnMetadata::getName)
                .filter(Objects::nonNull)
                .map(this::detailColumnKind)
                .filter(Objects::nonNull)
                .distinct()
                .count();
            score += Math.min(18, (int) distinctDetailKinds * 6);
        }
        return score;
    }

    private boolean looksFactLikeTable(TableMetadata table) {
        if (table == null || table.getName() == null) {
            return false;
        }
        String normalized = SchemaTableMatchUtil.normalizeQuestion(table.getName());
        if (normalized.contains(" booking ")
            || normalized.contains(" payment ")
            || normalized.contains(" order ")
            || normalized.contains(" ledger ")
            || normalized.contains(" invoice ")
            || normalized.contains(" tax ")
            || normalized.contains(" fee ")
            || normalized.contains(" reservation ")
            || normalized.contains(" transaction ")) {
            return true;
        }
        return looksMeasureTable(table);
    }

    private String detailColumnKind(String columnName) {
        if (columnName == null) {
            return null;
        }
        String normalized = columnName.toLowerCase(Locale.ROOT);
        if (normalized.contains("email")) {
            return "email";
        }
        if (normalized.contains("name")) {
            return "name";
        }
        if (normalized.contains("country")) {
            return "country";
        }
        if (normalized.contains("city")) {
            return "city";
        }
        if (normalized.contains("state")) {
            return "state";
        }
        if (normalized.contains("address")) {
            return "address";
        }
        if (normalized.contains("contact")) {
            return "contact";
        }
        return null;
    }

    private boolean isDerivedSemanticTable(SemanticTableModel model) {
        if (model == null) {
            return false;
        }
        String role = model.getTableRole() == null ? "" : model.getTableRole().toUpperCase(Locale.ROOT);
        return "AGGREGATE".equals(role) || "SUMMARY".equals(role) || "ROLLUP".equals(role);
    }

    private Set<String> meaningfulTokens(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        Set<String> stopWords = Set.of(
            "the", "a", "an", "is", "are", "was", "were", "for", "from", "with", "and", "or", "to",
            "of", "in", "on", "by", "at", "this", "that", "these", "those", "what", "which", "how",
            "much", "many", "month", "months", "week", "weeks", "day", "days", "year", "years"
        );
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String normalized = normalizeToken(token);
            if (normalized.length() >= 3 && !stopWords.contains(normalized)) {
                tokens.add(normalized);
            }
        }
        return tokens;
    }

    private List<String> identifierTokens(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return List.of();
        }
        return Arrays.stream(identifier.split("[_\\s]+"))
            .map(this::normalizeToken)
            .filter(token -> token.length() >= 3)
            .toList();
    }

    private String normalizeToken(String token) {
        if (token == null) {
            return "";
        }
        String normalized = token.trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith("ies") && normalized.length() > 4) {
            return normalized.substring(0, normalized.length() - 3) + "y";
        }
        if (normalized.endsWith("s") && normalized.length() > 3 && !normalized.endsWith("ss")) {
            return normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private record JoinCandidate(TableMetadata table, String joinCondition, int score) {}
    private record JoinEdge(String sourceTable, String targetTable, String joinExpression) {}
    private record DetailColumnChoice(String tableName, String columnName, int score) {}

    private String extractJsonFromResponse(String response) {
        if (response.contains("```json")) {
            int start = response.indexOf("```json") + 7;
            int end = response.indexOf("```", start);
            if (end > start) return response.substring(start, end).trim();
        }
        if (response.contains("```")) {
            int start = response.indexOf("```") + 3;
            int end = response.indexOf("```", start);
            if (end > start) return response.substring(start, end).trim();
        }
        return response.trim();
    }

    private String loadPromptTemplate(Resource resource) {
        try {
            return new String(resource.getInputStream().readAllBytes());
        } catch (Exception e) {
            throw new RuntimeException("Failed to load prompt template", e);
        }
    }
}
