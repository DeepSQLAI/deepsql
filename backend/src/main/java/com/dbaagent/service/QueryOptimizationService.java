package com.dbaagent.service;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.ExplainPlanAnalysis;
import com.dbaagent.model.ExplainPlanNode;
import com.dbaagent.model.QueryOptimizationCache;
import com.dbaagent.model.QueryFingerprint;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.TableMetadata;
import com.dbaagent.model.SlowQuery;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.repository.QueryOptimizationCacheRepository;
import com.dbaagent.repository.QueryFingerprintRepository;
import com.dbaagent.repository.KeyColumnAnalysisRepository;
import com.dbaagent.repository.brain.ColumnStatisticsRepository;
import com.dbaagent.model.brain.ColumnStatistics;
import com.dbaagent.model.KeyColumnAnalysis;
import com.dbaagent.service.optd.OptdOptimizationService;
import com.dbaagent.util.QueryNormalizer;
import static com.dbaagent.service.QueryFingerprintService.computeCanonicalFingerprint;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;

/**
 * AI-powered query optimization service.
 * Analyzes slow queries and provides detailed optimization recommendations
 * using Azure OpenAI.
 */
@Service
@Slf4j
public class QueryOptimizationService {

    private final ChatClient chatClient;
    private final CredentialService credentialService;
    private final ConnectionService connectionService;
    private final ExplainPlanService explainPlanService;
    private final SchemaScannerService schemaScannerService;
    private final DatabaseProviderRegistry providerRegistry;
    private final QueryOptimizationCacheRepository cacheRepository;
    private final QueryFingerprintRepository fingerprintRepository;
    private final ColumnStatisticsRepository columnStatisticsRepository;
    private final KeyColumnAnalysisRepository keyColumnAnalysisRepository;
    private final OptdOptimizationService optdOptimizationService;
    private final OptimizationCandidateService candidateService;
    private final ObjectMapper objectMapper;
    private final org.springframework.cache.CacheManager cacheManager;
    private final RewritePlanScorer rewritePlanScorer;

    /**
     * Bump this whenever the rewrite engine changes in a way that should
     * invalidate previously-cached rewrites (prompt/strategy/selection changes).
     * Cached entries stamped with an older version are treated as a cache miss
     * and regenerated, so improvements reach already-optimized queries.
     */
    private static final int REWRITE_ENGINE_VERSION = 2;

    @Autowired
    public QueryOptimizationService(
            ChatClient.Builder chatClientBuilder,
            CredentialService credentialService,
            ConnectionService connectionService,
            ExplainPlanService explainPlanService,
            SchemaScannerService schemaScannerService,
            DatabaseProviderRegistry providerRegistry,
            QueryOptimizationCacheRepository cacheRepository,
            QueryFingerprintRepository fingerprintRepository,
            ColumnStatisticsRepository columnStatisticsRepository,
            KeyColumnAnalysisRepository keyColumnAnalysisRepository,
            OptdOptimizationService optdOptimizationService,
            OptimizationCandidateService candidateService,
            ObjectMapper objectMapper,
            org.springframework.cache.CacheManager cacheManager,
            RewritePlanScorer rewritePlanScorer) {
        this.rewritePlanScorer = rewritePlanScorer;
        this.chatClient = chatClientBuilder.build();
        this.credentialService = credentialService;
        this.connectionService = connectionService;
        this.explainPlanService = explainPlanService;
        this.schemaScannerService = schemaScannerService;
        this.providerRegistry = providerRegistry;
        this.cacheRepository = cacheRepository;
        this.fingerprintRepository = fingerprintRepository;
        this.columnStatisticsRepository = columnStatisticsRepository;
        this.keyColumnAnalysisRepository = keyColumnAnalysisRepository;
        this.optdOptimizationService = optdOptimizationService;
        this.candidateService = candidateService;
        this.objectMapper = objectMapper;
        this.cacheManager = cacheManager;
        log.info("QueryOptimizationService initialized with Spring AI ChatClient and caching");
    }

    /**
     * Response model for query optimization suggestions
     */
    @Data
    @Builder
    public static class OptimizationResult {
        private String queryId;
        /** Canonical 16-char SHA-256 fingerprint matching query_fingerprints table.
         *  Informational — maps to the query_fingerprints table key for cross-table
         *  lookups (tiered timeout, trend data). API calls still use queryId as the key. */
        private String canonicalFingerprint;
        private String originalQuery;
        private String optimizedQuery;
        private List<OptimizationSuggestion> suggestions;
        private List<String> indexRecommendations;
        private String explanation;
        private Double estimatedImprovement;
        private ExplainPlanAnalysis explainAnalysis;
        private LocalDateTime generatedAt;
        private Boolean cached;  // True if this result was retrieved from cache
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OptimizationSuggestion {
        private String category;  // QUERY_REWRITE, INDEX, SCHEMA, CONFIG
        private String title;
        private String description;
        private String implementationSQL;
        private String priority;  // HIGH, MEDIUM, LOW
        private Double estimatedImpact;  // 0-100
    }

    /**
     * Sanitize query text by removing common prefixes like "use database;", "set timestamp=?;", etc.
     * Returns only the actual DML/DDL statement (SELECT, INSERT, UPDATE, DELETE, etc.)
     *
     * Delegates to centralized QueryNormalizer.sanitize() for consistent behavior.
     */
    public static String sanitizeQueryText(String queryText) {
        return com.dbaagent.util.QueryNormalizer.sanitize(queryText);
    }

    /**
     * Generate AI-powered optimization suggestions for a slow query
     *
     * @param connectionId The database connection ID
     * @param queryText The normalized query text (may contain ? placeholders)
     * @param sampleQuery The actual query with real values (used for EXPLAIN), can be null
     * @param slowQueryContext Additional context about the slow query
     */
    public OptimizationResult optimizeQuery(String connectionId, String queryText, String sampleQuery, SlowQuery slowQueryContext) {
        return optimizeQuery(connectionId, queryText, sampleQuery, slowQueryContext, false);
    }

    public OptimizationResult optimizeQuery(String connectionId, String queryText, String sampleQuery, SlowQuery slowQueryContext, boolean forceRefresh) {
        log.info("Generating AI optimization for query in connection: {} (forceRefresh={})", connectionId, forceRefresh);

        try {
            // Sanitize the query text to remove prefixes like "use db; set timestamp=?;"
            String sanitizedQuery = sanitizeQueryText(queryText);
            log.debug("Original query: {}", queryText.substring(0, Math.min(100, queryText.length())));
            log.debug("Sanitized query: {}", sanitizedQuery.substring(0, Math.min(100, sanitizedQuery.length())));

            // Sanitize the sample query too if available
            String sanitizedSampleQuery = sampleQuery != null ? sanitizeQueryText(sampleQuery) : null;
            if (sanitizedSampleQuery != null) {
                log.debug("Sanitized sample query: {}", sanitizedSampleQuery.substring(0, Math.min(100, sanitizedSampleQuery.length())));
            }

            // Use the incoming queryId for cache/candidate storage key — this is what the
            // frontend sends for subsequent lookups (candidates, benchmarks, cached results).
            // For Performance Schema queries this is a 64-char SHA-256 digest.
            String queryFingerprint = null;
            if (slowQueryContext != null && slowQueryContext.getQueryId() != null && !slowQueryContext.getQueryId().isBlank()) {
                queryFingerprint = slowQueryContext.getQueryId();
            } else if (sanitizedQuery != null && !sanitizedQuery.isBlank()) {
                queryFingerprint = QueryNormalizer.generateFingerprint(sanitizedQuery);
            }

            // Compute canonical 16-char fingerprint matching query_fingerprints table format.
            // Used for cross-table lookups (tiered timeout, trend data) where the storage
            // format differs from the API fingerprint format.
            String canonicalFp = null;
            if (sanitizedQuery != null && !sanitizedQuery.isBlank()) {
                String normalized = QueryNormalizer.normalize(sanitizedQuery);
                canonicalFp = computeCanonicalFingerprint(normalized);
            }

            // Return cached result if available (avoids non-deterministic LLM output)
            if (!forceRefresh && queryFingerprint != null) {
                OptimizationResult cached = getCachedOptimization(connectionId, queryFingerprint);
                if (cached != null) {
                    log.info("Returning cached optimization for query {}", queryFingerprint);
                    cached.setCached(true);
                    return cached;
                }
            }

            // Get database context
            ConnectionRequest connection = credentialService.getDecryptedConnection(connectionId);
            String dbType = providerRegistry.getCanonicalName(connection.getDbType());

            // Get schema context for affected tables (use sanitized query for better table extraction)
            String schemaContext = getSchemaContext(connectionId, sanitizedQuery);

            // Run EXPLAIN if possible (skip AI analysis to avoid double AI call and timeout)
            // Prefer sampleQuery (actual query with values) over queryText (normalized with ? placeholders)
            ExplainPlanAnalysis explainAnalysis = null;
            String queryForExplain = null;
            boolean usedPlaceholderSubstitution = false;
            String explainSkipReason = null;

            // Determine which query to use for EXPLAIN
            String explainCandidate = null;
            if (isExplainCandidate(sanitizedSampleQuery)) {
                explainCandidate = sanitizedSampleQuery;
                log.debug("Using sample query for EXPLAIN candidate");
            } else if (isExplainCandidate(sanitizedQuery)) {
                explainCandidate = sanitizedQuery;
                log.debug("Using normalized query for EXPLAIN candidate");
            }

            if (explainCandidate != null) {
                boolean hasDerivedPlaceholders = hasDerivedTablePlaceholders(explainCandidate);
                if (hasDerivedPlaceholders && "mysql".equals(dbType)) {
                    String queryId = slowQueryContext != null ? slowQueryContext.getQueryId() : null;
                    String sample = fetchMySqlSampleQuery(connectionId, queryId);
                    if (sample != null && !sample.isBlank()) {
                        explainCandidate = sanitizeQueryText(sample);
                        log.debug("Resolved sample query from performance_schema for EXPLAIN");
                        hasDerivedPlaceholders = hasDerivedTablePlaceholders(explainCandidate);
                    }
                }

                if (!hasDerivedPlaceholders) {
                    PlaceholderSubstitutionResult substituted =
                        substitutePlaceholdersForExplain(explainCandidate, dbType);
                    queryForExplain = substituted.query();
                    usedPlaceholderSubstitution = substituted.substituted();
                    if (usedPlaceholderSubstitution) {
                        log.debug("Substituted parameter placeholders for EXPLAIN execution");
                    }
                } else {
                    explainSkipReason =
                        "EXPLAIN skipped: query text contains derived-table placeholders (e.g., JOIN (?)). " +
                        "Provide a sample query with real values or enable query samples in performance_schema.";
                    log.debug("Skipping EXPLAIN - query contains derived table placeholders");
                }
            }

            if (queryForExplain != null) {
                try {
                    // Only use EXPLAIN ANALYZE when we have real values (avoid executing with placeholders)
                    boolean useAnalyze = !usedPlaceholderSubstitution;
                    explainAnalysis = explainPlanService.analyzeQuery(connectionId, queryForExplain, useAnalyze);
                } catch (Exception e) {
                    log.warn("Could not run EXPLAIN for optimization: {}", e.getMessage());
                }
            } else {
                log.debug("Skipping EXPLAIN - no executable query available (not SELECT/CTE)");
            }

            if (explainAnalysis == null && explainSkipReason != null) {
                explainAnalysis = ExplainPlanAnalysis.builder()
                    .connectionId(connectionId)
                    .query(sanitizedQuery)
                    .normalizedQuery(sanitizedQuery)
                    .dbType(dbType)
                    .aiSummary(explainSkipReason)
                    .planParseError(explainSkipReason)
                    .analyzedAt(LocalDateTime.now())
                    .wasExecuted(false)
                    .build();
            }

            // Extract affected tables from the query for workload-aware index recommendations
            List<String> affectedTables = new java.util.ArrayList<>(extractTableNames(sanitizedQuery));

            // Build prompt for AI optimization (use sanitized query)
            String prompt = buildOptimizationPrompt(
                sanitizedQuery,
                sanitizedSampleQuery,
                dbType,
                schemaContext,
                slowQueryContext,
                explainAnalysis,
                connectionId,
                affectedTables
            );

            // Call AI via Spring AI ChatClient
            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(getSystemPrompt(dbType)));
            messages.add(new UserMessage(prompt));

            // Low temperature: rewrites must be deterministic and consistent —
            // the same slow query should yield the same (best) rewrite, not a
            // different one each run. The default chat temperature (1.0) caused
            // run-to-run variance and occasional regressions.
            String aiResponse = chatClient.prompt()
                .messages(messages)
                .options(org.springframework.ai.chat.prompt.ChatOptions.builder()
                    .temperature(0.15))
                .call()
                .content();

            // Log the raw AI response for debugging
            log.info("AI optimization response length: {}", aiResponse != null ? aiResponse.length() : 0);
            if (aiResponse != null && aiResponse.length() > 0) {
                log.debug("AI optimization response (first 500 chars): {}",
                    aiResponse.substring(0, Math.min(500, aiResponse.length())));
            } else {
                log.warn("AI optimization returned empty or null response");
                return OptimizationResult.builder()
                    .queryId(slowQueryContext != null ? slowQueryContext.getQueryId() : null)
                    .canonicalFingerprint(canonicalFp)
                    .originalQuery(sanitizedQuery)
                    .suggestions(Collections.emptyList())
                    .explanation("AI returned empty response. Please try again.")
                    .generatedAt(LocalDateTime.now())
                    .build();
            }

            // Parse AI response into structured format (use sanitized query as originalQuery)
            OptimizationResult result = parseAIResponse(aiResponse, sanitizedQuery, slowQueryContext, dbType);
            result.setCanonicalFingerprint(canonicalFp);
            result.setExplainAnalysis(explainAnalysis);
            result.setGeneratedAt(LocalDateTime.now());
            result.setCached(false);  // Fresh result, not from cache

            if (result.getOptimizedQuery() != null && !result.getOptimizedQuery().isBlank()) {
                String aligned = alignOptimizedQueryWithSchema(connectionId, dbType, result.getOptimizedQuery());
                if (aligned != null && !aligned.isBlank() && !aligned.equals(result.getOptimizedQuery())) {
                    result.setOptimizedQuery(aligned);
                }

                // Substitute literals from sampleQuery into the rewrite if it still has placeholders
                if (sanitizedSampleQuery != null && !sanitizedSampleQuery.isBlank()) {
                    String literalized = applySampleLiterals(result.getOptimizedQuery(), sanitizedSampleQuery);
                    if (literalized != null && !literalized.isBlank()) {
                        result.setOptimizedQuery(literalized);
                    }
                }

                // Ensure all @variables have SET declarations
                String optimizedSql = result.getOptimizedQuery();
                Set<String> unresolvedVars = findUnresolvedUserVariables(optimizedSql);

                if (!unresolvedVars.isEmpty()) {
                    // Try 1: Get SET preamble from original/sample query
                    String samplePreamble = extractSetPreamble(sampleQuery);
                    if (samplePreamble == null) {
                        samplePreamble = extractSetPreamble(queryText);
                    }
                    if (samplePreamble != null) {
                        optimizedSql = samplePreamble + "\n" + optimizedSql;
                        unresolvedVars = findUnresolvedUserVariables(optimizedSql);
                    }
                }

                if (!unresolvedVars.isEmpty()) {
                    // Try 2: Scan the full AI response for inline variable definitions
                    // (patterns like "@var = expression" even without SET keyword)
                    String generatedDecls = generateMissingSetDeclarations(
                        unresolvedVars, aiResponse, sampleQuery != null ? sampleQuery : queryText);
                    if (generatedDecls != null) {
                        optimizedSql = generatedDecls + "\n" + optimizedSql;
                    }
                }

                // Post-process: extract inlined epoch values into SET @variables
                optimizedSql = extractInlinedEpochsToSetVariables(optimizedSql, sanitizedSampleQuery);

                result.setOptimizedQuery(optimizedSql);

                // Validate AI rewrite by executing with LIMIT 0 to catch all runtime errors
                String validationError = validateRewriteViaDryRun(connectionId, dbType, optimizedSql);
                if (validationError != null) {
                    log.warn("AI rewrite validation failed: {}", validationError);
                    List<OptimizationSuggestion> updatedSuggestions = new ArrayList<>(
                        result.getSuggestions() != null ? result.getSuggestions() : Collections.emptyList());
                    OptimizationSuggestion warningSuggestion = new OptimizationSuggestion();
                    warningSuggestion.setCategory("QUERY_REWRITE");
                    warningSuggestion.setTitle("AI rewrite has errors");
                    warningSuggestion.setDescription(validationError);
                    warningSuggestion.setPriority("HIGH");
                    updatedSuggestions.add(0, warningSuggestion);
                    result.setSuggestions(updatedSuggestions);
                    // Mark result so it is NOT cached — a broken rewrite should never be served
                    // from cache; next request will trigger a fresh AI generation attempt.
                    result.setOptimizedQuery(null);
                }
            }

            // Candidate evaluation: generate alternative-strategy rewrites, then
            // EXPLAIN-score them (NO execution) alongside the original and keep
            // the plan-cheapest one. This turns "the model's single guess" into
            // "the measured-best of several flavors" — choosing the structure
            // whose estimated plan has the lowest cost / fewest full scans.
            if (result.getOptimizedQuery() != null && !result.getOptimizedQuery().isBlank()) {
                try {
                    String originalForExplain = (sanitizedSampleQuery != null && !sanitizedSampleQuery.isBlank())
                        ? sanitizedSampleQuery : sanitizedQuery;
                    selectBestCandidateByPlan(connectionId, dbType, originalForExplain, schemaContext, explainAnalysis, result);
                } catch (Exception e) {
                    log.debug("Candidate plan-evaluation skipped: {}", e.getMessage());
                }
            }

            try {
                recordOptimizationCandidates(
                    connectionId,
                    dbType,
                    queryFingerprint,
                    sanitizedQuery,
                    sanitizedSampleQuery,
                    result.getOptimizedQuery()
                );
            } catch (Exception e) {
                log.debug("Failed to record optimization candidates: {}", e.getMessage());
            }

            log.info("Generated {} optimization suggestions for query",
                result.getSuggestions() != null ? result.getSuggestions().size() : 0);

            // Cache the result only when the rewrite is valid (or there is no rewrite)
            boolean hasInvalidRewrite = result.getSuggestions() != null &&
                result.getSuggestions().stream()
                    .anyMatch(s -> "AI rewrite has errors".equals(s.getTitle()));
            if (queryFingerprint != null && !queryFingerprint.isBlank() && !hasInvalidRewrite) {
                try {
                    cacheOptimization(connectionId, queryFingerprint, result);
                } catch (Exception e) {
                    log.warn("Failed to cache optimization result: {}", e.getMessage());
                }
            } else if (hasInvalidRewrite) {
                log.info("Skipping cache for query {} — rewrite validation failed, fresh AI generation will be attempted next time", queryFingerprint);
            }

            return result;

        } catch (Exception e) {
            log.error("Error generating optimization suggestions", e);
            // Return basic result with error (use sanitized query if available)
            String displayQuery = queryText != null ? sanitizeQueryText(queryText) : queryText;
            return OptimizationResult.builder()
                .queryId(slowQueryContext != null ? slowQueryContext.getQueryId() : null)
                .originalQuery(displayQuery)
                .suggestions(Collections.emptyList())
                .explanation("Failed to generate AI optimization: " + e.getMessage())
                .generatedAt(LocalDateTime.now())
                .build();
        }
    }

    private String getSystemPrompt(String dbType) {
        return String.format("""
            You are an expert %s Database Administrator and query optimization specialist.
            Your role is to analyze ONE slow query and provide single-query-scoped
            optimizations: query rewrites and a plan diagnosis.

            DO NOT recommend indexes. Index decisions require the whole workload —
            an index that helps this query may hurt write throughput or be redundant
            with one another query already needs. Those recommendations come from
            DeepSQL's holistic Workload Analysis, which weighs every query's calls,
            execution time, and write cost together. If this query would clearly
            benefit from an index, say so in one sentence in the EXPLANATION and
            point the user to "Analyze workload" — but never emit CREATE INDEX here.

            For this query, you should:
            1. Identify performance bottlenecks from its execution plan
            2. Suggest a query rewrite if one helps (this is the main deliverable)
            3. Identify single-query schema or configuration issues (NOT indexes)

            REWRITE STRATEGY — prefer STRUCTURAL rewrites that cut rows early over
            cosmetic edits. A rewrite that only reorders columns, renames things,
            or extracts SET @variables WITHOUT reducing how many rows are scanned
            is NOT acceptable — if that is all you can do, say there is no
            beneficial rewrite.
            When the plan shows a large nested loop, late filtering, or a
            sub/derived query that scans far more rows than the query ultimately
            returns, RESTRUCTURE the query. The highest-leverage techniques, in
            order:
            1. DECOMPOSE WITH CTEs. First build ONE fully-narrowed "core" set that
               applies ALL the most-selective predicates together (combine them
               via joins if they live on different tables) so it is as SMALL as
               possible. Then drive EVERY dependent sub-query / lookup (invoice
               numbers, latest status, etc.) by JOINING IT TO THAT CORE SET — so
               the dependent lookup touches only the keys that survive ALL the
               filters.

               ROOT THE CORE AT THE TABLE WITH THE SMALLEST MEASURED POST-FILTER
               ROW COUNT. Do not guess selectivity from column names — READ the
               "Measured Cardinalities" block: it gives the ACTUAL rows each
               filtered access produced when the query ran. Whichever access has
               the fewest actual rows is your driver; start the core FROM that
               table — EVEN IF it is the table you later aggregate — and JOIN the
               larger filtered tables INTO it. Never start the core from the
               table with the larger measured count and leave the more selective
               filter for a later join. The core should already contain the
               columns the final SELECT aggregates, so the outer query reads from
               the core, not from a raw table again.

               COMMON MISTAKE TO AVOID (this is the #1 way these rewrites go
               wrong): do NOT create a broad intermediate CTE — e.g. one filtered
               by date alone while still spanning every tenant/customer/type — and
               then join other large tables to it. That intermediate is not
               narrow, so the dependent joins stay huge. Always drive dependent
               lookups from the SMALLEST already-computed set (the one with the
               MOST predicates applied), never from a partially-filtered one.
               If two tables must both be filtered to get a small set, JOIN them
               inside the core CTE rather than making one broad CTE per table.
               Use the FEWEST CTEs that achieve this; never add a CTE that is not
               materially narrower than one you already have.
            2. ELIMINATE work, don't just relocate it. Before adding a CTE, ask
               whether a derived table / sub-query is needed at ALL — a
               GROUP-BY-then-join derived table can often be replaced by a plain
               JOIN + aggregate in the outer query once the driving set is small.
               Fewer moving parts often beats more CTEs; the best rewrite is
               sometimes a single well-ordered JOIN with no CTE.
            3. Push every predicate as early as possible; a LEFT JOIN whose table
               is filtered in the WHERE clause is effectively an INNER JOIN — make
               it one so the optimizer can reorder.
            4. Make predicates sargable: no functions/arithmetic on indexed
               columns; match column types exactly (no INT-vs-quoted-string);
               filter on the LEADING column of a composite index; rewrite
               date-range BETWEEN into half-open >= / < on the raw column.

            DECISION PROCESS — do this reasoning FIRST, grounded in the Measured
            Cardinalities and Statistical Enrichment provided, then write the SQL:
            (a) list each table's actual post-filter row count from the plan;
            (b) pick the smallest as the driver and an explicit join order that
                keeps each intermediate result as small as possible;
            (c) decide which sub-queries can be eliminated or folded into joins;
            (d) only then emit the rewrite. Put this reasoning in the EXPLANATION.
            Think about the join ORDER the rewrite implies — the goal is to make
            the database touch the fewest rows, as early as possible.

            Worked example of the preferred shape — ONE small core with ALL
            filters applied, dependent lookup joins that core (NOT a broad set):
            ```sql
            WITH core AS (                       -- every selective predicate applied → smallest set
              SELECT hs.id, hs.fk, hs.amount, d.ts
              FROM big_table hs
              JOIN driver d ON d.id = hs.fk
              WHERE hs.tenant_id = 123 AND hs.type = 'x'   -- predicates on big_table
                AND d.ts >= @from AND d.ts < @to           -- predicate on the driver
            ),
            lookup AS (                          -- joins the NARROW core, not a date-only set
              SELECT s.fk, MAX(s.val) AS val
              FROM side_table s
              JOIN core c ON c.fk = s.fk          -- only keys that survived ALL filters
              WHERE s.tenant_id = 123
              GROUP BY s.fk
            )
            SELECT c.fk, SUM(c.amount) AS total, MAX(lookup.val) AS val
            FROM core c LEFT JOIN lookup ON lookup.fk = c.fk
            GROUP BY c.fk;
            ```
            (Anti-pattern: a "by_date" CTE filtered only on d.ts, then joining
            side_table to by_date — by_date still spans every tenant, so the
            lookup stays huge. Don't do that.)

            Always provide:
            - Clear, implementable solutions
            - SQL code for any recommended changes (rewrites only — no DDL)
            - Estimated performance improvement (as a percentage)
            - Priority ranking (HIGH, MEDIUM, LOW)

            Format your response as follows:

            ## OPTIMIZED QUERY
            Provide the COMPLETE rewritten query in a SINGLE code block.

            CRITICAL RULES FOR THE OPTIMIZED QUERY:
            1. ONLY use column names, table names, and aliases that appear in the original query
               or are explicitly listed in the Schema Context section. Do NOT invent or assume
               any column or table name — if it is not in the schema, do not use it.
            2. When the original query computes date/time values from literals
               (e.g. STR_TO_DATE, UNIX_TIMESTAMP, CONVERT_TZ with string dates),
               you MUST extract the computed values into SET @variable declarations
               at the TOP of the code block. Never inline raw epoch numbers or
               computed date values directly in the WHERE clause.
            3. Every user-defined @variable must have a SET declaration.
            4. SET declarations and the SELECT must be in the SAME code block.
            5. The rewritten query MUST be executable as-is — it will be validated
               against the live database. Any column or function that does not exist
               will cause the rewrite to be rejected.

            Example:
            ```sql
            SET @target_date = STR_TO_DATE('11-01-2026', '%%d-%%m-%%Y');
            SET @day_start = UNIX_TIMESTAMP(CONVERT_TZ(@target_date, 'Asia/Calcutta', @@session.time_zone));
            SET @day_end = @day_start + 86399;
            SELECT ... WHERE col BETWEEN @day_start AND @day_end;
            ```
            Never inline raw epoch timestamps like 1768089600 in the query body.
            Never use @variables without their SET declarations.
            Never split SET and SELECT into separate code blocks.

            ## SUGGESTIONS
            ### [CATEGORY] Title
            Description of the issue and solution. CATEGORY must be one of
            QUERY_REWRITE, SCHEMA, or CONFIG. Do NOT use INDEX — index
            recommendations are out of scope for single-query analysis.
            ```sql
            Implementation SQL if applicable (rewrites only — no CREATE INDEX)
            ```
            Priority: [HIGH/MEDIUM/LOW]
            Estimated Impact: [percentage]%%

            ## EXPLANATION
            Write the explanation in two labelled parts:

            **Why the indexes aren't kicking in** — Look at the EXPLAIN plan and
            the indexes listed in Schema Context. For each table scanned WITHOUT
            using an available index (or using a worse one than expected),
            explain WHY in plain language. Common causes to check for:
            - a function wraps an indexed column (e.g. DATE(col), UPPER(col),
              STR_TO_DATE(col,…)) making the predicate non-sargable;
            - an implicit type or collation mismatch (e.g. a numeric column
              compared to a quoted '123', or VARCHAR vs INT), which silently
              disables the index;
            - the predicate only filters a NON-leading column of a composite
              index, so the index can't be used;
            - OR / negation / leading-wildcard LIKE that defeats the index.
            If every scan already uses the right index, say "Indexes are being
            used effectively." Only discuss EXISTING indexes here — never
            propose new ones.

            **What the rewrite changes** — Exactly what you changed and how it
            lets the existing indexes be used (or otherwise speeds the query).
            If a brand-new index would help but does not exist, note it in ONE
            sentence and refer the user to Analyze workload — do not give DDL.

            ## ESTIMATED IMPROVEMENT
            [Overall estimated performance improvement percentage]
            """, dbType.toUpperCase());
    }

    private static boolean isExplainCandidate(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String type = com.dbaagent.util.QueryNormalizer.detectQueryType(query);
        return "SELECT".equals(type);
    }

    /**
     * Extract SET variable declarations that precede the main DML statement.
     * Returns the SET preamble (e.g. "SET @tz := 'UTC'; SET @d := ...;") or null if none.
     */
    private static final Pattern SET_PREAMBLE_PATTERN =
        Pattern.compile("^((?:\\s*SET\\s+@[^;]+;\\s*)+)", Pattern.CASE_INSENSITIVE);

    static String extractSetPreamble(String query) {
        if (query == null || query.isBlank()) {
            return null;
        }
        java.util.regex.Matcher m = SET_PREAMBLE_PATTERN.matcher(query.trim());
        if (m.find()) {
            return m.group(1).trim();
        }
        return null;
    }

    /**
     * Find user-defined @variable references in a SQL query.
     * Returns variable names (without @) in lowercase.
     * Excludes @@system variables and @@ session variables.
     */
    private static final Pattern USER_VAR_REFERENCE_PATTERN =
        Pattern.compile("(?<!@)@([a-zA-Z_]\\w*)", Pattern.CASE_INSENSITIVE);

    static Set<String> findUserVariableReferences(String query) {
        Set<String> vars = new LinkedHashSet<>();
        if (query == null || query.isBlank()) return vars;
        java.util.regex.Matcher m = USER_VAR_REFERENCE_PATTERN.matcher(query);
        while (m.find()) {
            vars.add(m.group(1).toLowerCase());
        }
        return vars;
    }

    /**
     * Find SET @variable = ... declarations in a SQL string.
     * Returns map of lowercase variable name -> full SET statement.
     */
    private static final Pattern SET_DECLARATION_PATTERN =
        Pattern.compile("(SET\\s+@(\\w+)\\s*(?::=|=)\\s*[^;]+;)", Pattern.CASE_INSENSITIVE);

    static Map<String, String> findSetDeclarations(String sql) {
        Map<String, String> decls = new LinkedHashMap<>();
        if (sql == null || sql.isBlank()) return decls;
        java.util.regex.Matcher m = SET_DECLARATION_PATTERN.matcher(sql);
        while (m.find()) {
            decls.put(m.group(2).toLowerCase(), m.group(1).trim());
        }
        return decls;
    }

    /**
     * Ensure a selected query includes SET declarations for all referenced @variables.
     * Scans other SQL blocks and the raw response text for missing declarations.
     */
    static String resolveSetPreamble(String selectedQuery, List<String> allSqlBlocks, String rawResponse) {
        if (selectedQuery == null || selectedQuery.isBlank()) return selectedQuery;

        Set<String> referencedVars = findUserVariableReferences(selectedQuery);
        if (referencedVars.isEmpty()) return selectedQuery;

        Map<String, String> existingDecls = findSetDeclarations(selectedQuery);
        Set<String> missingVars = new LinkedHashSet<>(referencedVars);
        missingVars.removeAll(existingDecls.keySet());

        if (missingVars.isEmpty()) return selectedQuery;

        // Scan all other SQL blocks for SET declarations of missing variables
        Map<String, String> foundDecls = new LinkedHashMap<>();
        if (allSqlBlocks != null) {
            for (String block : allSqlBlocks) {
                if (block.equals(selectedQuery)) continue;
                Map<String, String> blockDecls = findSetDeclarations(block);
                for (String var : missingVars) {
                    if (blockDecls.containsKey(var) && !foundDecls.containsKey(var)) {
                        foundDecls.put(var, blockDecls.get(var));
                    }
                }
            }
        }

        // If still missing, scan the raw response text (outside code blocks) for SET declarations
        if (foundDecls.size() < missingVars.size() && rawResponse != null) {
            Map<String, String> responseDecls = findSetDeclarations(rawResponse);
            for (String var : missingVars) {
                if (!foundDecls.containsKey(var) && responseDecls.containsKey(var)) {
                    foundDecls.put(var, responseDecls.get(var));
                }
            }
        }

        if (foundDecls.isEmpty()) return selectedQuery;

        StringBuilder preamble = new StringBuilder();
        for (String decl : foundDecls.values()) {
            preamble.append(decl).append("\n");
        }

        log.debug("Resolved {} missing SET declarations for variables: {}",
            foundDecls.size(), foundDecls.keySet());

        return preamble.toString().trim() + "\n\n" + selectedQuery;
    }

    /**
     * Find @variables that are referenced but not declared with SET in the query.
     */
    static Set<String> findUnresolvedUserVariables(String query) {
        Set<String> referenced = findUserVariableReferences(query);
        Map<String, String> declared = findSetDeclarations(query);
        referenced.removeAll(declared.keySet());
        return referenced;
    }

    /**
     * Pattern to find inline variable definitions in AI prose like "@start_ts = UNIX_TIMESTAMP(...)"
     */
    private static final Pattern INLINE_VAR_DEF_PATTERN =
        Pattern.compile("@(\\w+)\\s*(?::=|=)\\s*([^,;\\n]+)", Pattern.CASE_INSENSITIVE);

    /**
     * Generate SET declarations for undefined @variables by:
     * 1. Scanning the AI response text for inline definitions (e.g. "@start_ts = UNIX_TIMESTAMP(...)")
     * 2. Inferring from date literals in the original query (for common epoch patterns)
     */
    static String generateMissingSetDeclarations(Set<String> missingVars, String aiResponse, String originalQuery) {
        Map<String, String> generated = new LinkedHashMap<>();

        // Scan AI response for inline definitions like "@var = expression"
        if (aiResponse != null) {
            java.util.regex.Matcher m = INLINE_VAR_DEF_PATTERN.matcher(aiResponse);
            while (m.find()) {
                String varName = m.group(1).toLowerCase();
                String expression = m.group(2).trim();
                if (missingVars.contains(varName) && !generated.containsKey(varName)) {
                    // Clean up the expression (remove trailing prose)
                    expression = cleanExpression(expression);
                    if (expression != null && !expression.isBlank()) {
                        generated.put(varName, "SET @" + m.group(1) + " = " + expression + ";");
                    }
                }
            }
        }

        // For remaining variables, try to infer from original query date literals
        Set<String> stillMissing = new LinkedHashSet<>(missingVars);
        stillMissing.removeAll(generated.keySet());

        if (!stillMissing.isEmpty() && originalQuery != null) {
            String firstDateLiteral = extractFirstDateLiteral(originalQuery);
            if (firstDateLiteral != null) {
                // Common patterns: @start_ts/@start_date, @end_ts/@end_date, @target_date
                for (String var : stillMissing) {
                    String lowerVar = var.toLowerCase();
                    if (lowerVar.contains("start") || lowerVar.contains("target") || lowerVar.contains("date")) {
                        generated.put(var, "SET @" + var + " = UNIX_TIMESTAMP(STR_TO_DATE('"
                            + firstDateLiteral + "','%d-%m-%Y'));");
                    } else if (lowerVar.contains("end")) {
                        generated.put(var, "SET @" + var + " = UNIX_TIMESTAMP(STR_TO_DATE('"
                            + firstDateLiteral + "','%d-%m-%Y')) + 86400;");
                    }
                }
            }
        }

        if (generated.isEmpty()) return null;

        log.info("Generated {} SET declarations for undefined variables: {}", generated.size(), generated.keySet());

        StringBuilder sb = new StringBuilder();
        for (String decl : generated.values()) {
            sb.append(decl).append("\n");
        }
        return sb.toString().trim();
    }

    /**
     * Clean up an expression extracted from AI prose.
     * Removes trailing natural language text.
     */
    private static String cleanExpression(String expr) {
        if (expr == null || expr.isBlank()) return null;
        // If expression contains SQL-like content, keep up to the first non-SQL part
        // Remove trailing prose words (the, for, which, etc.)
        String cleaned = expr.replaceAll("\\s+(the|for|which|where|this|that|so|and|or)\\b.*$", "");
        // Remove trailing parentheses imbalance
        int opens = 0;
        int end = cleaned.length();
        for (int i = 0; i < cleaned.length(); i++) {
            if (cleaned.charAt(i) == '(') opens++;
            else if (cleaned.charAt(i) == ')') {
                opens--;
                if (opens < 0) { end = i; break; }
            }
        }
        cleaned = cleaned.substring(0, end).trim();
        // Must contain at least some SQL-like content
        if (cleaned.length() < 3) return null;
        return cleaned;
    }

    /**
     * Extract the first date literal from a query (patterns like '11-01-2026' or '2026-01-11').
     */
    private static final Pattern DATE_LITERAL_PATTERN =
        Pattern.compile("'(\\d{1,2}-\\d{1,2}-\\d{4}(?:\\s+\\d{2}:\\d{2}:\\d{2})?)'");

    private static String extractFirstDateLiteral(String query) {
        if (query == null) return null;
        java.util.regex.Matcher m = DATE_LITERAL_PATTERN.matcher(query);
        if (m.find()) {
            String datePart = m.group(1);
            // Return just the date portion (strip time if present)
            int spaceIdx = datePart.indexOf(' ');
            return spaceIdx > 0 ? datePart.substring(0, spaceIdx) : datePart;
        }
        return null;
    }

    /**
     * Post-process: detect bare epoch/unix-timestamp integers in the optimized query
     * that correspond to date literals in the original sample query, and extract them
     * into SET @variable declarations for readability.
     *
     * Example transform:
     *   WHERE col BETWEEN 1768089600 AND 1768175999
     * becomes:
     *   SET @day_start = UNIX_TIMESTAMP(CONVERT_TZ(STR_TO_DATE('11-01-2026','%d-%m-%Y'), 'Asia/Calcutta', @@session.time_zone));
     *   SET @day_end = @day_start + 86399;
     *   ... WHERE col BETWEEN @day_start AND @day_end
     */
    private static final Pattern EPOCH_LITERAL_PATTERN =
        Pattern.compile("\\b(1[4-9]\\d{8})\\b");

    static String extractInlinedEpochsToSetVariables(String optimizedSql, String sampleQuery) {
        if (optimizedSql == null || optimizedSql.isBlank()) return optimizedSql;
        // Only process if query has no SET declarations already
        if (extractSetPreamble(optimizedSql) != null) return optimizedSql;

        // Find all epoch-like integers (10-digit numbers starting with 1[4-9], i.e. 2009-2033 range)
        java.util.regex.Matcher m = EPOCH_LITERAL_PATTERN.matcher(optimizedSql);
        List<Long> epochs = new ArrayList<>();
        while (m.find()) {
            try {
                long val = Long.parseLong(m.group(1));
                // Sanity check: must be in a reasonable unix timestamp range (2010-2040)
                if (val >= 1262304000L && val <= 2208988800L) {
                    if (!epochs.contains(val)) {
                        epochs.add(val);
                    }
                }
            } catch (NumberFormatException ignored) {}
        }

        if (epochs.isEmpty()) return optimizedSql;

        // Sort epochs to pair them (start/end)
        epochs.sort(Long::compareTo);

        // Extract timezone from sample query if available (e.g. 'Asia/Calcutta')
        String tz = extractTimezone(sampleQuery);

        // Build SET declarations and replace inline values
        StringBuilder preamble = new StringBuilder();
        String result = optimizedSql;
        int varIdx = 0;
        List<long[]> pairs = new ArrayList<>();

        // Try to pair epochs that are ~86399 apart (same day start/end)
        Set<Integer> paired = new HashSet<>();
        for (int i = 0; i < epochs.size(); i++) {
            for (int j = i + 1; j < epochs.size(); j++) {
                long diff = epochs.get(j) - epochs.get(i);
                if (diff >= 86399 && diff <= 86400) {
                    pairs.add(new long[]{epochs.get(i), epochs.get(j)});
                    paired.add(i);
                    paired.add(j);
                    break;
                }
            }
        }

        // Generate SET declarations for paired epochs
        for (long[] pair : pairs) {
            varIdx++;
            String startVar = pairs.size() == 1 ? "@day_start" : "@day_start_" + varIdx;
            String endVar = pairs.size() == 1 ? "@day_end" : "@day_end_" + varIdx;

            // Convert epoch to date for the SET declaration
            java.time.LocalDate date = java.time.Instant.ofEpochSecond(pair[0])
                .atZone(java.time.ZoneId.of(tz != null ? tz : "UTC"))
                .toLocalDate();
            String dateStr = String.format("%02d-%02d-%04d", date.getDayOfMonth(), date.getMonthValue(), date.getYear());
            String dateFormat = "%d-%m-%Y";

            if (tz != null) {
                preamble.append(String.format("SET %s = UNIX_TIMESTAMP(CONVERT_TZ(STR_TO_DATE('%s','%s'), '%s', @@session.time_zone));\n",
                    startVar, dateStr, dateFormat, tz));
            } else {
                preamble.append(String.format("SET %s = UNIX_TIMESTAMP(STR_TO_DATE('%s','%s'));\n",
                    startVar, dateStr, dateFormat));
            }
            long diff = pair[1] - pair[0];
            preamble.append(String.format("SET %s = %s + %d;\n", endVar, startVar, diff));

            result = result.replace(String.valueOf(pair[0]), startVar);
            result = result.replace(String.valueOf(pair[1]), endVar);
        }

        // Handle unpaired epochs
        for (int i = 0; i < epochs.size(); i++) {
            if (paired.contains(i)) continue;
            varIdx++;
            String var = "@epoch_val_" + varIdx;
            long epoch = epochs.get(i);

            java.time.LocalDateTime dt = java.time.Instant.ofEpochSecond(epoch)
                .atZone(java.time.ZoneId.of(tz != null ? tz : "UTC"))
                .toLocalDateTime();
            String dtStr = String.format("%02d-%02d-%04d %02d:%02d:%02d",
                dt.getDayOfMonth(), dt.getMonthValue(), dt.getYear(),
                dt.getHour(), dt.getMinute(), dt.getSecond());
            String dtFormat = "%d-%m-%Y %H:%i:%s";

            if (tz != null) {
                preamble.append(String.format("SET %s = UNIX_TIMESTAMP(CONVERT_TZ(STR_TO_DATE('%s','%s'), '%s', @@session.time_zone));\n",
                    var, dtStr, dtFormat, tz));
            } else {
                preamble.append(String.format("SET %s = UNIX_TIMESTAMP(STR_TO_DATE('%s','%s'));\n",
                    var, dtStr, dtFormat));
            }

            result = result.replace(String.valueOf(epoch), var);
        }

        if (preamble.length() == 0) return optimizedSql;

        log.info("Extracted {} inlined epoch values into SET @variable declarations", epochs.size());
        return preamble.toString().trim() + "\n\n" + result;
    }

    /**
     * Extract timezone string from a query (e.g. 'Asia/Calcutta' from CONVERT_TZ(..., 'Asia/Calcutta')).
     */
    private static final Pattern TZ_PATTERN =
        Pattern.compile("'((?:Africa|America|Asia|Atlantic|Australia|Europe|Indian|Pacific)/[A-Za-z_/]+)'");

    static String extractTimezone(String query) {
        if (query == null || query.isBlank()) return null;
        java.util.regex.Matcher m = TZ_PATTERN.matcher(query);
        if (m.find()) return m.group(1);
        return null;
    }

    private static final Pattern DERIVED_TABLE_PLACEHOLDER_PATTERN =
        Pattern.compile("\\b(from|join)\\s*\\(\\s*\\?\\s*\\)", Pattern.CASE_INSENSITIVE);

    private static boolean hasDerivedTablePlaceholders(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        return DERIVED_TABLE_PLACEHOLDER_PATTERN.matcher(query).find();
    }

    private String fetchMySqlSampleQuery(String connectionId, String queryId) {
        if (connectionId == null || connectionId.isBlank() || queryId == null || queryId.isBlank()) {
            return null;
        }
        try {
            ConnectionRequest connection = credentialService.getDecryptedConnection(connectionId);
            JdbcTemplate jdbc = connectionService.getJdbcTemplate(connectionId, connection);
            String sampleFromSummary = queryForDigest(jdbc,
                "SELECT QUERY_SAMPLE_TEXT FROM performance_schema.events_statements_summary_by_digest " +
                "WHERE DIGEST = ? AND QUERY_SAMPLE_TEXT IS NOT NULL LIMIT 1",
                queryId);
            if (sampleFromSummary != null && !sampleFromSummary.isBlank()) {
                return sampleFromSummary;
            }
            String sampleFromHistoryLong = queryForDigest(jdbc,
                "SELECT SQL_TEXT FROM performance_schema.events_statements_history_long " +
                "WHERE DIGEST = ? AND SQL_TEXT IS NOT NULL ORDER BY TIMER_START DESC LIMIT 1",
                queryId);
            if (sampleFromHistoryLong != null && !sampleFromHistoryLong.isBlank()) {
                return sampleFromHistoryLong;
            }
            return queryForDigest(jdbc,
                "SELECT SQL_TEXT FROM performance_schema.events_statements_history " +
                "WHERE DIGEST = ? AND SQL_TEXT IS NOT NULL ORDER BY TIMER_START DESC LIMIT 1",
                queryId);
        } catch (Exception e) {
            log.debug("Could not resolve MySQL sample query for digest {}: {}", queryId, e.getMessage());
            return null;
        }
    }

    private String queryForDigest(JdbcTemplate jdbc, String sql, String digest) {
        try {
            List<String> results = jdbc.query(sql, ps -> ps.setString(1, digest),
                (rs, rowNum) -> rs.getString(1));
            return results.isEmpty() ? null : results.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private record PlaceholderSubstitutionResult(String query, boolean substituted) {}

    private static PlaceholderSubstitutionResult substitutePlaceholdersForExplain(String query, String dbType) {
        if (query == null || query.isEmpty()) {
            return new PlaceholderSubstitutionResult(query, false);
        }

        StringBuilder out = new StringBuilder(query.length());
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inBacktick = false;
        boolean substituted = false;

        for (int i = 0; i < query.length(); i++) {
            char c = query.charAt(i);

            if (inSingle) {
                out.append(c);
                if (c == '\\' && i + 1 < query.length()) {
                    out.append(query.charAt(++i));
                    continue;
                }
                if (c == '\'') {
                    if (i + 1 < query.length() && query.charAt(i + 1) == '\'') {
                        out.append(query.charAt(++i));
                    } else {
                        inSingle = false;
                    }
                }
                continue;
            }

            if (inDouble) {
                out.append(c);
                if (c == '\\' && i + 1 < query.length()) {
                    out.append(query.charAt(++i));
                    continue;
                }
                if (c == '"') {
                    if (i + 1 < query.length() && query.charAt(i + 1) == '"') {
                        out.append(query.charAt(++i));
                    } else {
                        inDouble = false;
                    }
                }
                continue;
            }

            if (inBacktick) {
                out.append(c);
                if (c == '`') {
                    if (i + 1 < query.length() && query.charAt(i + 1) == '`') {
                        out.append(query.charAt(++i));
                    } else {
                        inBacktick = false;
                    }
                }
                continue;
            }

            if (c == '\'') {
                inSingle = true;
                out.append(c);
                continue;
            }
            if (c == '"') {
                inDouble = true;
                out.append(c);
                continue;
            }
            if (c == '`') {
                inBacktick = true;
                out.append(c);
                continue;
            }

            if (c == '?') {
                out.append('0');
                substituted = true;
                continue;
            }

            if ("postgres".equals(dbType) && c == '$') {
                int j = i + 1;
                while (j < query.length() && Character.isDigit(query.charAt(j))) {
                    j++;
                }
                if (j > i + 1) {
                    out.append('0');
                    substituted = true;
                    i = j - 1;
                    continue;
                }
            }

            out.append(c);
        }

        return new PlaceholderSubstitutionResult(out.toString(), substituted);
    }

    /**
     * Build a workload summary showing how heavily each affected table is used across all
     * known slow queries for this connection. Used to make index recommendations aware of
     * the broader query workload — an index that benefits 50 queries is more valuable
     * than one that only helps 1.
     */
    private String buildWorkloadContext(String connectionId, List<String> affectedTables) {
        if (affectedTables == null || affectedTables.isEmpty()) return null;
        try {
            StringBuilder wb = new StringBuilder();
            wb.append("## Query Workload Context (across all slow queries on this connection)\n");
            wb.append("Use this to assess index impact — prefer indexes that benefit many queries.\n\n");
            boolean anyData = false;
            for (String table : affectedTables) {
                try {
                    List<com.dbaagent.model.QueryFingerprint> peers =
                        fingerprintRepository.findByAffectedTable(connectionId, table);
                    if (peers == null || peers.isEmpty()) continue;
                    anyData = true;
                    long totalCalls = peers.stream()
                        .mapToLong(fp -> fp.getCurrentCallCount() != null ? fp.getCurrentCallCount() : 0)
                        .sum();
                    double avgTimeMs = peers.stream()
                        .mapToDouble(fp -> fp.getCurrentAvgTimeMs() != null ? fp.getCurrentAvgTimeMs() : 0)
                        .average().orElse(0);
                    wb.append(String.format("Table `%s`: %d queries reference it | %,d total calls/day | %.0f ms avg\n",
                        table, peers.size(), totalCalls, avgTimeMs));
                } catch (Exception ignored) {/* don't block optimization on workload lookup failure */}
            }
            return anyData ? wb.toString() : null;
        } catch (Exception e) {
            log.debug("Workload context lookup failed: {}", e.getMessage());
            return null;
        }
    }

    /** Mutable per-column accumulator for {@link #buildEnrichmentContext}. */
    private static final class EnrichedColumn {
        final String name;
        Long distinctCount;
        Double selectivity;
        Double nullFraction;
        boolean skewed;
        List<String> mcvValues;
        EnrichedColumn(String name) { this.name = name; }
    }

    /**
     * Build a statistical enrichment block from precomputed Brain data — per-column
     * cardinality, selectivity, null fraction and skew for the tables this query
     * touches. Grounds the LLM's index/rewrite recommendations in real statistics
     * instead of guesses, mirroring {@code ChatContextAssembler.buildColumnStatisticsContext}.
     * Returns {@code null} when no enrichment exists (e.g. a connection whose Brain
     * has not been initialized) so query optimization is never blocked.
     */
    private String buildEnrichmentContext(String connectionId, List<String> affectedTables) {
        if (affectedTables == null || affectedTables.isEmpty()) return null;
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("## Statistical Enrichment (precomputed — cardinality & selectivity)\n");
            sb.append("Use these statistics to ground index and rewrite recommendations.\n\n");
            boolean anyData = false;

            for (String table : affectedTables) {
                try {
                    List<ColumnStatistics> stats =
                        columnStatisticsRepository.findByConnectionIdAndTableName(connectionId, table);
                    List<KeyColumnAnalysis> keyCols =
                        keyColumnAnalysisRepository.findByConnectionIdAndTableNameOrderByImportanceScoreDesc(
                            connectionId, table);
                    boolean hasStats = stats != null && !stats.isEmpty();
                    boolean hasKeyCols = keyCols != null && !keyCols.isEmpty();
                    if (!hasStats && !hasKeyCols) {
                        continue;
                    }

                    // Merge both sources per column (case-insensitive key). KeyColumnAnalysis
                    // is inserted first in importance order; stats-only columns follow.
                    Map<String, EnrichedColumn> merged = new LinkedHashMap<>();
                    if (hasKeyCols) {
                        for (KeyColumnAnalysis kc : keyCols) {
                            if (kc.getColumnName() == null) continue;
                            EnrichedColumn ec = merged.computeIfAbsent(
                                kc.getColumnName().toLowerCase(Locale.ROOT),
                                k -> new EnrichedColumn(kc.getColumnName()));
                            if (kc.getSelectivity() != null) ec.selectivity = kc.getSelectivity().doubleValue();
                            if (kc.getDistinctCount() != null) ec.distinctCount = kc.getDistinctCount();
                            if (Boolean.TRUE.equals(kc.getIsHeavilySkewed())) ec.skewed = true;
                        }
                    }
                    if (hasStats) {
                        for (ColumnStatistics cs : stats) {
                            if (cs.getColumnName() == null) continue;
                            EnrichedColumn ec = merged.computeIfAbsent(
                                cs.getColumnName().toLowerCase(Locale.ROOT),
                                k -> new EnrichedColumn(cs.getColumnName()));
                            if (cs.getDistinctCount() != null) ec.distinctCount = cs.getDistinctCount();
                            if (cs.getMcvValues() != null && !cs.getMcvValues().isEmpty()) {
                                ec.mcvValues = cs.getMcvValues();
                            }
                            if (cs.getNullFraction() != null) {
                                ec.nullFraction = cs.getNullFraction();
                            } else if (cs.getNullCount() != null && cs.getRowCount() != null
                                    && cs.getRowCount() > 0) {
                                ec.nullFraction = (double) cs.getNullCount() / cs.getRowCount();
                            }
                        }
                    }
                    if (merged.isEmpty()) continue;

                    // KeyColumnAnalysis columns are already importance-ordered; for a
                    // stats-only table fall back to distinct-count descending.
                    List<EnrichedColumn> columns = new ArrayList<>(merged.values());
                    if (!hasKeyCols) {
                        columns.sort(Comparator.comparingLong(
                            (EnrichedColumn c) -> c.distinctCount == null ? -1L : c.distinctCount).reversed());
                    }

                    StringBuilder tableBlock = new StringBuilder();
                    boolean tableHasData = false;
                    for (EnrichedColumn c : columns.stream().limit(5).toList()) {
                        boolean enumLike = c.distinctCount != null
                            && c.distinctCount > 0 && c.distinctCount <= 20;
                        boolean highlySelective = (c.selectivity != null && c.selectivity >= 0.1)
                            || (c.distinctCount != null && c.distinctCount > 100);
                        String leadingEdge = "high selectivity — strong index leading-edge candidate";

                        List<String> facts = new ArrayList<>();
                        if (c.distinctCount != null) {
                            StringBuilder d = new StringBuilder(String.format("%,d distinct", c.distinctCount));
                            if (enumLike && c.mcvValues != null && !c.mcvValues.isEmpty()) {
                                d.append(" (enum-like: ").append(c.mcvValues).append(")");
                            } else if (highlySelective && c.selectivity == null) {
                                d.append(" (").append(leadingEdge).append(")");
                            }
                            facts.add(d.toString());
                        }
                        if (c.selectivity != null) {
                            StringBuilder s = new StringBuilder(String.format("selectivity %.2f", c.selectivity));
                            if (highlySelective) {
                                s.append(" (").append(leadingEdge).append(")");
                            }
                            facts.add(s.toString());
                        }
                        if (c.nullFraction != null) {
                            facts.add(String.format("null fraction %.2f", c.nullFraction));
                        }
                        if (c.skewed) {
                            facts.add("skewed distribution — consider partial index");
                        }
                        if (facts.isEmpty()) continue;
                        tableBlock.append("  - ").append(c.name).append(": ")
                            .append(String.join(", ", facts)).append("\n");
                        tableHasData = true;
                    }

                    if (tableHasData) {
                        sb.append("Table `").append(table).append("`:\n").append(tableBlock).append("\n");
                        anyData = true;
                    }
                } catch (Exception ignored) {
                    // never block optimization on a single table's enrichment lookup
                }
            }

            return anyData ? sb.toString() : null;
        } catch (Exception e) {
            log.debug("Enrichment context lookup failed: {}", e.getMessage());
            return null;
        }
    }

    private String buildOptimizationPrompt(
        String queryText,
        String sampleQuery,
        String dbType,
        String schemaContext,
        SlowQuery slowQueryContext,
        ExplainPlanAnalysis explainAnalysis
    ) {
        return buildOptimizationPrompt(queryText, sampleQuery, dbType, schemaContext,
            slowQueryContext, explainAnalysis, null, null);
    }

    private String buildOptimizationPrompt(
        String queryText,
        String sampleQuery,
        String dbType,
        String schemaContext,
        SlowQuery slowQueryContext,
        ExplainPlanAnalysis explainAnalysis,
        String connectionId,
        List<String> affectedTables
    ) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("Analyze and optimize this slow query:\n\n");
        prompt.append("```sql\n").append(queryText).append("\n```\n\n");

        if (sampleQuery != null && !sampleQuery.isBlank() && !sampleQuery.equals(queryText)) {
            prompt.append("## Sample Query (with literals)\n");
            prompt.append("```sql\n").append(sampleQuery).append("\n```\n\n");
            prompt.append("Preserve literal values from the sample query in any rewrite so it can be benchmarked.\n\n");
        }

        if (slowQueryContext != null) {
            prompt.append("## Query Statistics\n");
            prompt.append(String.format("- Average Execution Time: %.2f ms\n",
                slowQueryContext.getAvgExecutionTimeMs() != null ? slowQueryContext.getAvgExecutionTimeMs() : 0));
            prompt.append(String.format("- Total Execution Time: %.2f ms\n",
                slowQueryContext.getTotalExecutionTimeMs() != null ? slowQueryContext.getTotalExecutionTimeMs() : 0));
            prompt.append(String.format("- Call Count: %,d\n",
                slowQueryContext.getCallCount() != null ? slowQueryContext.getCallCount() : 0));
            prompt.append(String.format("- Rows Examined: %,d\n",
                slowQueryContext.getRowsExamined() != null ? slowQueryContext.getRowsExamined() : 0));
            prompt.append(String.format("- Rows Sent: %,d\n",
                slowQueryContext.getRowsSent() != null ? slowQueryContext.getRowsSent() : 0));
            prompt.append(String.format("- Severity: %s\n\n",
                slowQueryContext.getSeverity() != null ? slowQueryContext.getSeverity() : "UNKNOWN"));
        }

        if (schemaContext != null && !schemaContext.isEmpty()) {
            prompt.append("## Schema Context\n");
            prompt.append(schemaContext).append("\n\n");
        }

        // Statistical enrichment (precomputed cardinality / selectivity). Kept
        // because it grounds REWRITES too — e.g. "filter on the high-selectivity
        // column first", "this predicate is on a skewed column". Index-specific
        // column-ordering guidance was removed: indexes are out of scope here.
        if (connectionId != null && affectedTables != null && !affectedTables.isEmpty()) {
            String enrichmentCtx = buildEnrichmentContext(connectionId, affectedTables);
            if (enrichmentCtx != null) {
                prompt.append(enrichmentCtx).append("\n");
                prompt.append("Use these statistics to ground the REWRITE — e.g. push the most "
                    + "selective predicate first, avoid functions on indexed columns, prefer "
                    + "sargable forms. Do not propose new indexes.\n\n");
            }
        }

        if (explainAnalysis != null) {
            prompt.append("## EXPLAIN Plan Summary\n");
            prompt.append(explainAnalysis.getSummaryStats()).append("\n");
            if (explainAnalysis.getIssues() != null && !explainAnalysis.getIssues().isEmpty()) {
                prompt.append("\nIdentified Issues:\n");
                for (var issue : explainAnalysis.getIssues()) {
                    prompt.append(String.format("- [%s] %s\n", issue.getSeverity(), issue.getMessage()));
                }
            }
            prompt.append("\n");

            // GROUND TRUTH: the measured per-step row counts from the plan. This
            // is what the rewrite decision must be based on — which filtered set
            // is actually smallest, where rows blow up — instead of guessing
            // selectivity from column names.
            String cardinality = buildPlanCardinalityContext(explainAnalysis);
            if (cardinality != null) {
                prompt.append(cardinality).append("\n");
            }
        }

        // NOTE: the cross-query workload-context block that used to live here
        // (buildWorkloadContext) was removed. It existed only to inform index
        // recommendations, which are now the exclusive job of the holistic
        // Workload Analysis. buildWorkloadContext() is retained and reused there.

        prompt.append("Provide a query rewrite and a plan diagnosis. No index DDL.\n");

        return prompt.toString();
    }

    /**
     * Emit the measured per-step cardinalities from the (EXPLAIN ANALYZE) plan
     * tree as ground truth for the rewrite decision. For each access / join /
     * aggregate node we list its table, filter/condition, estimated rows and —
     * crucially — ACTUAL rows × loops. The model uses these real numbers to pick
     * the smallest filtered set as the driver and order joins to minimise
     * intermediate rows, instead of guessing selectivity from column names.
     */
    private String buildPlanCardinalityContext(ExplainPlanAnalysis explainAnalysis) {
        if (explainAnalysis == null || explainAnalysis.getPlanTree() == null) return null;
        if (!Boolean.TRUE.equals(explainAnalysis.getWasExecuted())) return null; // only real actuals are trustworthy
        List<String> lines = new ArrayList<>();
        collectCardinalityLines(explainAnalysis.getPlanTree(), 0, lines);
        if (lines.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("## Measured Cardinalities (from EXPLAIN ANALYZE — GROUND TRUTH)\n");
        sb.append("These are the REAL row counts each step produced when the query ran. "
            + "Base every structural decision on these numbers — the smallest post-filter "
            + "set is your driver; never drive a dependent lookup from a larger set when a "
            + "smaller already-filtered one exists.\n");
        for (String l : lines) sb.append(l).append("\n");
        return sb.toString();
    }

    // ── candidate generation + plan-based selection (no execution) ──────────

    /**
     * Generate alternative rewrites, EXPLAIN-score the original + the primary +
     * the alternatives (no execution), and replace the primary with the
     * plan-cheapest valid candidate when one is meaningfully better. The chosen
     * alternative is dry-run validated before swapping. Best-effort: never throws.
     */
    private void selectBestCandidateByPlan(String connectionId, String dbType, String originalForExplain,
                                           String schemaContext, ExplainPlanAnalysis explainAnalysis,
                                           OptimizationResult result) {
        String primary = result.getOptimizedQuery();
        // Dedup candidates by normalized text; preserve insertion order.
        Map<String, String> candidates = new LinkedHashMap<>();
        candidates.put(normalizeForCompare(primary), primary);
        for (String alt : generateAlternativeCandidates(dbType, originalForExplain, schemaContext, explainAnalysis)) {
            if (alt != null && !alt.isBlank()) candidates.putIfAbsent(normalizeForCompare(alt), alt);
        }
        if (candidates.size() <= 1) return; // nothing to compare against

        RewritePlanScorer.PlanScore originalScore = rewritePlanScorer.score(connectionId, originalForExplain);

        String bestSql = null;
        RewritePlanScorer.PlanScore bestScore = null;
        int scored = 0;
        for (String sql : candidates.values()) {
            RewritePlanScorer.PlanScore s = rewritePlanScorer.score(connectionId, sql);
            if (!s.valid()) continue;
            scored++;
            if (bestScore == null || isBetterPlan(s, bestScore)) {
                bestScore = s;
                bestSql = sql;
            }
        }
        if (bestSql == null || scored <= 1) return;

        boolean swapped = false;
        if (!bestSql.equals(primary)) {
            // Only swap to an alternative that also executes cleanly.
            String validationError = validateRewriteViaDryRun(connectionId, dbType, bestSql);
            if (validationError == null) {
                result.setOptimizedQuery(bestSql);
                swapped = true;
            } else {
                log.debug("Plan-best candidate failed dry-run, keeping primary: {}", validationError);
                bestSql = primary;
                bestScore = rewritePlanScorer.score(connectionId, primary);
            }
        }

        // Ground the improvement estimate in plan cost when we can measure both.
        if (originalScore.valid() && bestScore != null && bestScore.valid()
            && originalScore.estimatedCost() > 0 && bestScore.estimatedCost() < originalScore.estimatedCost()) {
            int pct = (int) Math.round((1.0 - bestScore.estimatedCost() / originalScore.estimatedCost()) * 100);
            if (pct > 0) result.setEstimatedImprovement((double) Math.min(99, pct));
        }

        String note = String.format(
            "%n%n— Candidate evaluation (EXPLAIN, no execution): scored %d rewrite flavor(s) against the original. "
            + "Selected the plan with the lowest estimated cost%s%s.",
            scored,
            bestScore != null && bestScore.valid() ? String.format(" (cost %.0f, %d full scan%s)",
                bestScore.estimatedCost(), bestScore.fullScans(), bestScore.fullScans() == 1 ? "" : "s") : "",
            swapped ? " — a different structure than the first draft won on estimated cost" : "");
        result.setExplanation((result.getExplanation() == null ? "" : result.getExplanation()) + note);
    }

    /** Strictly-better plan: lower cost, then fewer full scans, then fewer rows. */
    private boolean isBetterPlan(RewritePlanScorer.PlanScore a, RewritePlanScorer.PlanScore b) {
        if (a.estimatedCost() != b.estimatedCost()) return a.estimatedCost() < b.estimatedCost();
        if (a.fullScans() != b.fullScans()) return a.fullScans() < b.fullScans();
        return a.estimatedRows() < b.estimatedRows();
    }

    /**
     * One focused, low-temperature call asking for up to 3 DISTINCT-strategy
     * rewrites, returned as bare ```sql code blocks. Grounded in the schema and
     * the measured cardinalities so each flavor is plausible.
     */
    private List<String> generateAlternativeCandidates(String dbType, String originalQuery,
                                                       String schemaContext, ExplainPlanAnalysis explainAnalysis) {
        try {
            StringBuilder p = new StringBuilder();
            p.append("Produce UP TO 3 alternative rewrites of the SQL below, each using a DISTINCT structural "
                + "strategy so they can be compared by their execution plans:\n")
                .append("  A) a CTE/WITH decomposition rooted at the table with the smallest measured post-filter row count;\n")
                .append("  B) a single flat JOIN query with NO derived tables/sub-queries (fold aggregates into the outer query);\n")
                .append("  C) a derived-table / semi-join pushdown variant.\n")
                .append("Preserve EXACT semantics and all literal values. Use only columns/tables that exist.\n")
                .append("Return ONLY SQL — each rewrite in its own ```sql code block, no prose, no commentary.\n\n");
            if (schemaContext != null && !schemaContext.isBlank()) {
                p.append("## Schema\n").append(schemaContext).append("\n");
            }
            String card = buildPlanCardinalityContext(explainAnalysis);
            if (card != null) p.append(card).append("\n");
            p.append("## Query\n```sql\n").append(originalQuery).append("\n```\n");

            String resp = chatClient.prompt()
                .messages(List.of(
                    new SystemMessage("You are a query rewrite generator. Output only SQL code blocks, nothing else."),
                    new UserMessage(p.toString())))
                .options(org.springframework.ai.chat.prompt.ChatOptions.builder().temperature(0.2))
                .call()
                .content();
            return extractSqlBlocks(resp);
        } catch (Exception e) {
            log.debug("Alternative candidate generation failed: {}", e.getMessage());
            return List.of();
        }
    }

    private static final java.util.regex.Pattern SQL_BLOCK =
        java.util.regex.Pattern.compile("```sql\\s*(.*?)```", java.util.regex.Pattern.DOTALL | java.util.regex.Pattern.CASE_INSENSITIVE);

    private List<String> extractSqlBlocks(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        java.util.regex.Matcher m = SQL_BLOCK.matcher(text);
        while (m.find()) {
            String sql = m.group(1).trim();
            if (!sql.isBlank()) out.add(sql);
        }
        return out;
    }

    private String normalizeForCompare(String sql) {
        return sql == null ? "" : sql.replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
    }

    private void collectCardinalityLines(ExplainPlanNode node, int depth, List<String> out) {
        if (node == null) return;
        Long actual = node.getActualRows();
        Integer est = node.getPlanRows();
        Integer loops = node.getActualLoops();
        // Only emit nodes that carry a meaningful measurement.
        if (node.getNodeType() != null && (actual != null || est != null)) {
            StringBuilder l = new StringBuilder("  ".repeat(depth)).append("- ").append(node.getNodeType());
            if (node.getTableName() != null) l.append(" on ").append(node.getTableName());
            if (node.getKey() != null) l.append(" [index ").append(node.getKey()).append("]");
            String cond = node.getIndexCondition() != null ? node.getIndexCondition()
                : (node.getFilter() != null ? node.getFilter() : null);
            if (cond != null) l.append(" {").append(cond.length() > 100 ? cond.substring(0, 100) + "…" : cond).append("}");
            if (est != null) l.append(" est=").append(est);
            if (actual != null) {
                long total = (loops != null && loops > 1) ? actual * loops : actual;
                l.append(" actual=").append(actual);
                if (loops != null && loops > 1) l.append("×").append(loops).append("loops=").append(total);
            }
            out.add(l.toString());
        }
        if (node.getChildren() != null) {
            for (ExplainPlanNode child : node.getChildren()) {
                collectCardinalityLines(child, depth + 1, out);
            }
        }
    }

    private String getSchemaContext(String connectionId, String queryText) {
        try {
            Set<String> tables = extractTableNames(queryText);
            if (tables.isEmpty()) {
                return "";
            }

            StringBuilder context = new StringBuilder();
            try {
                var schemaMetadata = schemaScannerService.scanSchema(connectionId);
                if (schemaMetadata != null && schemaMetadata.getTables() != null) {
                    for (String table : tables) {
                        for (var tableInfo : schemaMetadata.getTables()) {
                            if (tableInfo.getName() != null && tableInfo.getName().equalsIgnoreCase(table)) {
                                context.append("Table: ").append(tableInfo.getName()).append("\n");
                                context.append("Columns (use ONLY these exact names in any rewrite):\n");
                                if (tableInfo.getColumns() != null) {
                                    for (var col : tableInfo.getColumns()) {
                                        StringBuilder colDef = new StringBuilder("  - ")
                                            .append(col.getName())
                                            .append(" ").append(col.getDataType() != null ? col.getDataType() : "");
                                        if (Boolean.TRUE.equals(col.getPrimaryKey())) colDef.append(" PK");
                                        if (Boolean.FALSE.equals(col.getNullable())) colDef.append(" NOT NULL");
                                        context.append(colDef).append("\n");
                                    }
                                }
                                if (tableInfo.getIndexes() != null && !tableInfo.getIndexes().isEmpty()) {
                                    context.append("Indexes:\n");
                                    for (var idx : tableInfo.getIndexes()) {
                                        context.append("  - ").append(idx).append("\n");
                                    }
                                }
                                context.append("\n");
                                break;
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Could not get schema for tables: {}", e.getMessage());
            }
            return context.toString();
        } catch (Exception e) {
            log.warn("Error getting schema context: {}", e.getMessage());
            return "";
        }
    }

    private Set<String> extractTableNames(String queryText) {
        Set<String> tables = new HashSet<>();
        String upperQuery = queryText.toUpperCase();

        // Simple extraction - find words after FROM, JOIN, INTO, UPDATE
        String[] keywords = {"FROM", "JOIN", "INTO", "UPDATE"};
        for (String keyword : keywords) {
            int idx = 0;
            while ((idx = upperQuery.indexOf(keyword, idx)) != -1) {
                int start = idx + keyword.length();
                while (start < queryText.length() && Character.isWhitespace(queryText.charAt(start))) {
                    start++;
                }
                int end = start;
                while (end < queryText.length() &&
                       (Character.isLetterOrDigit(queryText.charAt(end)) ||
                        queryText.charAt(end) == '_' ||
                        queryText.charAt(end) == '.')) {
                    end++;
                }
                if (end > start) {
                    String table = queryText.substring(start, end);
                    // Remove schema prefix if present
                    if (table.contains(".")) {
                        table = table.substring(table.lastIndexOf('.') + 1);
                    }
                    tables.add(table.toLowerCase());
                }
                idx = end;
            }
        }
        return tables;
    }

    /**
     * Validate an AI-rewritten query by executing it with LIMIT 0 wrapped in a subquery.
     * This is stronger than EXPLAIN — it catches runtime errors such as missing columns,
     * invalid function arguments, and type mismatches that EXPLAIN can miss.
     * Returns null if valid, or an error message string on failure.
     */
    private String validateRewriteViaDryRun(String connectionId, String dbType, String sql) {
        if (sql == null || sql.isBlank() || connectionId == null) {
            return null;
        }
        try {
            ConnectionRequest connection = credentialService.getDecryptedConnection(connectionId);
            try (java.sql.Connection conn = connectionService.getConnection(connectionId, connection);
                 java.sql.Statement stmt = conn.createStatement()) {
                stmt.setQueryTimeout(15);

                // Split SET preamble from the main SELECT (quote/comment-aware)
                boolean hashIsComment = !"postgres".equalsIgnoreCase(dbType);
                String[] splitResult = com.dbaagent.util.SqlStatementSplitter.splitSetPreamble(sql, hashIsComment);
                String selectPart = splitResult[splitResult.length - 1].trim();
                for (int si = 0; si < splitResult.length - 1; si++) {
                    stmt.execute(splitResult[si]);
                }

                if (selectPart == null || selectPart.isBlank()) {
                    return "No SELECT statement found in AI rewrite";
                }

                // Execute the rewrite with LIMIT 5 — this actually runs the query, catching
                // column-not-found, invalid function arguments, type mismatches, and verifying
                // the query returns real data (not just passes a syntax check).
                // We strip any existing LIMIT and add LIMIT 5 so the test is always fast.
                String cleanSelect = selectPart.trim();
                if (cleanSelect.endsWith(";")) {
                    cleanSelect = cleanSelect.substring(0, cleanSelect.length() - 1).trim();
                }
                String selectLower = cleanSelect.toLowerCase();
                boolean alreadyHasLimit = selectLower.matches("(?s).*\\blimit\\s+\\d+.*");
                String testSql = alreadyHasLimit
                    ? cleanSelect
                    : (com.dbaagent.util.SqlStatementSplitter.stripOrderBy(cleanSelect) + " LIMIT 5");
                try (java.sql.ResultSet rs = stmt.executeQuery(testSql)) {
                    return null; // query ran successfully
                }
            }
        } catch (java.sql.SQLException e) {
            String msg = e.getMessage();
            if (msg != null && msg.length() > 300) {
                msg = msg.substring(0, 300);
            }
            return msg;
        } catch (Exception e) {
            log.debug("Validation dry-run skipped: {}", e.getMessage());
            return null; // Don't block on non-SQL errors
        }
    }

    private String alignOptimizedQueryWithSchema(String connectionId, String dbType, String optimizedQuery) {
        if (optimizedQuery == null || optimizedQuery.isBlank()) {
            return optimizedQuery;
        }
        if (dbType == null || !"mysql".equalsIgnoreCase(dbType)) {
            return optimizedQuery;
        }
        if (connectionId == null || connectionId.isBlank()) {
            return optimizedQuery;
        }

        try {
            SchemaMetadata schemaMetadata = schemaScannerService.scanSchema(connectionId);
            if (schemaMetadata == null || schemaMetadata.getTables() == null) {
                return optimizedQuery;
            }

            return com.dbaagent.util.SqlIdentifierCaseAligner
                .alignTableIdentifiers(optimizedQuery, schemaMetadata);
        } catch (Exception e) {
            log.debug("Could not align optimized query with schema: {}", e.getMessage());
            return optimizedQuery;
        }
    }

    // Patterns for flexible section matching
    private static final java.util.regex.Pattern SECTION_HEADER_PATTERN =
        java.util.regex.Pattern.compile("^(?:#{1,3}\\s*|\\*\\*|\\d+\\.\\s*)?(.+?)(?:\\*\\*)?\\s*$", java.util.regex.Pattern.MULTILINE);

    private static final java.util.regex.Pattern SQL_CODE_BLOCK_PATTERN =
        java.util.regex.Pattern.compile("```(?:sql)?\\s*\\n([\\s\\S]*?)```", java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final java.util.regex.Pattern INDEX_PATTERN =
        java.util.regex.Pattern.compile("(?:CREATE\\s+(?:UNIQUE\\s+)?INDEX|ADD\\s+INDEX|idx_|index\\s+on)\\s*[^;]+", java.util.regex.Pattern.CASE_INSENSITIVE);

    private static final java.util.regex.Pattern PERCENTAGE_PATTERN =
        java.util.regex.Pattern.compile("(\\d+(?:\\.\\d+)?)\\s*%");

    private OptimizationResult parseAIResponse(String aiResponse, String originalQuery, SlowQuery context, String dbType) {
        OptimizationResult.OptimizationResultBuilder builder = OptimizationResult.builder()
            .originalQuery(originalQuery)
            .queryId(context != null ? context.getQueryId() : null);

        List<OptimizationSuggestion> suggestions = new ArrayList<>();
        List<String> indexRecommendations = new ArrayList<>();

        log.debug("Parsing AI response of length: {}", aiResponse.length());

        // Try to extract optimized query from code blocks
        String optimizedQuery = extractOptimizedQuery(aiResponse);
        if (optimizedQuery != null && !optimizedQuery.isEmpty()) {
            builder.optimizedQuery(optimizedQuery);
            log.debug("Extracted optimized query: {} chars", optimizedQuery.length());
        } else {
            log.debug("No optimized query extracted from AI response");
        }

        // Index recommendations are intentionally NOT extracted from the
        // single-query optimizer — they're only logically sound when computed
        // across the whole workload (see Workload Analysis / IndexRecommendationService).
        // The field stays on the DTO for backward-compat but is always empty here.
        // Defensive: drop any INDEX-category suggestion the model emitted anyway.

        // Extract suggestions from numbered lists and sections
        suggestions.addAll(extractSuggestions(aiResponse));
        suggestions.removeIf(s -> s.getCategory() != null
            && s.getCategory().toUpperCase(java.util.Locale.ROOT).contains("INDEX"));

        // Extract explanation - look for summary/explanation sections or use full response
        String explanation = extractExplanation(aiResponse);
        if (explanation != null && !explanation.isEmpty()) {
            builder.explanation(explanation);
        }

        // Extract estimated improvement percentage
        Double improvement = extractEstimatedImprovement(aiResponse);
        if (improvement != null) {
            builder.estimatedImprovement(improvement);
        }

        builder.suggestions(suggestions);
        builder.indexRecommendations(indexRecommendations);

        OptimizationResult result = builder.build();

        // If we got meaningful content, return it
        boolean hasStructuredData = (result.getOptimizedQuery() != null && !result.getOptimizedQuery().isEmpty()) ||
                            (result.getSuggestions() != null && !result.getSuggestions().isEmpty()) ||
                            (result.getIndexRecommendations() != null && !result.getIndexRecommendations().isEmpty());

        boolean hasExplanation = result.getExplanation() != null && !result.getExplanation().isEmpty();

        // If we have structured data but no explanation, add a summary from the response
        if (hasStructuredData && !hasExplanation) {
            String fallbackExplanation = extractFallbackExplanation(aiResponse);
            if (fallbackExplanation != null && !fallbackExplanation.isEmpty()) {
                return OptimizationResult.builder()
                    .queryId(result.getQueryId())
                    .originalQuery(result.getOriginalQuery())
                    .optimizedQuery(result.getOptimizedQuery())
                    .suggestions(result.getSuggestions())
                    .indexRecommendations(result.getIndexRecommendations())
                    .explanation(fallbackExplanation)
                    .estimatedImprovement(result.getEstimatedImprovement())
                    .generatedAt(LocalDateTime.now())
                    .build();
            }
        }

        // If no content at all, use raw response
        if (!hasStructuredData && !hasExplanation) {
            log.warn("No structured data extracted from AI response. Using raw response as explanation.");
            String fallbackExplanation = aiResponse.length() > 3000
                ? aiResponse.substring(0, 3000) + "..."
                : aiResponse;
            return OptimizationResult.builder()
                .queryId(result.getQueryId())
                .originalQuery(result.getOriginalQuery())
                .suggestions(Collections.emptyList())
                .indexRecommendations(Collections.emptyList())
                .explanation(fallbackExplanation)
                .generatedAt(LocalDateTime.now())
                .build();
        }

        log.info("Parsed AI response: {} suggestions, {} index recommendations, optimizedQuery={}, explanation={}",
            suggestions.size(), indexRecommendations.size(),
            optimizedQuery != null && !optimizedQuery.isEmpty(),
            explanation != null && !explanation.isEmpty());

        return result;
    }

    /**
     * Extract a fallback explanation from the AI response when main extraction fails
     */
    private String extractFallbackExplanation(String response) {
        // Try to find the first meaningful paragraph (not a header, not a code block)
        String[] paragraphs = response.split("\n\n+");
        StringBuilder explanation = new StringBuilder();

        for (String para : paragraphs) {
            String trimmed = para.trim();

            // Skip headers, code blocks, separators
            if (trimmed.startsWith("#") || trimmed.startsWith("```") ||
                trimmed.equals("---") || trimmed.isEmpty()) {
                continue;
            }

            // Skip very short paragraphs
            if (trimmed.length() < 30) continue;

            // Add meaningful content
            explanation.append(cleanDescription(trimmed)).append("\n\n");

            // Limit to ~1000 chars
            if (explanation.length() > 1000) break;
        }

        return explanation.toString().trim();
    }

    /**
     * Extract optimized/rewritten query from AI response.
     * After selecting the best SQL block, resolves any missing SET declarations
     * for @variables by scanning all other SQL blocks and the response text.
     */
    private String extractOptimizedQuery(String response) {
        String lowerResponse = response.toLowerCase();

        // Find all SQL code blocks
        java.util.regex.Matcher matcher = SQL_CODE_BLOCK_PATTERN.matcher(response);
        List<String> sqlBlocks = new ArrayList<>();
        List<Integer> blockPositions = new ArrayList<>();
        while (matcher.find()) {
            String sql = matcher.group(1).trim();
            if (!sql.isEmpty() && !sql.toLowerCase().startsWith("create index") &&
                !sql.toLowerCase().startsWith("add index") && sql.length() > 20) {
                sqlBlocks.add(sql);
                blockPositions.add(matcher.start());
            }
        }

        if (sqlBlocks.isEmpty()) {
            return null;
        }

        String selected = null;

        // If there's only one SQL block, use it
        if (sqlBlocks.size() == 1) {
            selected = sqlBlocks.get(0);
        }

        // Look for SQL block near "optimized", "rewritten", "correct", "recommended" keywords
        if (selected == null) {
            String[] optimizedKeywords = {
                "optimized query", "rewritten query", "improved query", "recommended query",
                "correct & optimized", "correct and optimized", "suggested query",
                "optimized postgresql", "optimized mysql", "better query",
                "✅ correct", "✅ optimized", "## optimized", "recommended rewrite",
                "✅ recommended", "optimized rewrite", "faster query", "efficient query",
                "lateral join", "simpler rewrite", "rewrite needed", "high impact",
                "postgresql-native", "mysql-native", "### ✅"
            };

            outer:
            for (String keyword : optimizedKeywords) {
                int keywordIdx = lowerResponse.indexOf(keyword.toLowerCase());
                if (keywordIdx != -1) {
                    for (int i = 0; i < sqlBlocks.size(); i++) {
                        int blockPos = blockPositions.get(i);
                        if (blockPos > keywordIdx && blockPos < keywordIdx + 800) {
                            String block = sqlBlocks.get(i);
                            String upperBlock = block.trim().toUpperCase();

                            if (!upperBlock.contains("SELECT") &&
                                (upperBlock.startsWith("SET ") || upperBlock.startsWith("--"))) {
                                if (i + 1 < sqlBlocks.size()) {
                                    String nextBlock = sqlBlocks.get(i + 1);
                                    if (nextBlock.trim().toUpperCase().contains("SELECT")) {
                                        selected = block + "\n\n" + nextBlock;
                                        break outer;
                                    }
                                }
                                break;
                            }

                            if (upperBlock.startsWith("SELECT") && i > 0) {
                                String prevBlock = sqlBlocks.get(i - 1);
                                String upperPrev = prevBlock.trim().toUpperCase();
                                if (upperPrev.startsWith("SET ") && !upperPrev.contains("SELECT")) {
                                    selected = prevBlock + "\n\n" + block;
                                    break outer;
                                }
                            }

                            selected = block;
                            break outer;
                        }
                    }
                }
            }
        }

        // Fallback: prefer longest SELECT-containing block
        if (selected == null) {
            String bestQuery = null;
            int bestQueryIdx = -1;
            for (int i = 0; i < sqlBlocks.size(); i++) {
                String sql = sqlBlocks.get(i);
                String upper = sql.toUpperCase().trim();
                if (upper.startsWith("SET ") && upper.contains("SELECT")) {
                    if (bestQuery == null || sql.length() > bestQuery.length()) {
                        bestQuery = sql;
                        bestQueryIdx = i;
                    }
                }
                if (upper.startsWith("SELECT") && sql.toLowerCase().contains("from")) {
                    if (bestQuery == null || sql.length() > bestQuery.length()) {
                        bestQuery = sql;
                        bestQueryIdx = i;
                    }
                }
            }

            if (bestQuery != null) {
                String upperBest = bestQuery.toUpperCase().trim();
                if (upperBest.startsWith("SELECT") && bestQueryIdx > 0) {
                    String prevBlock = sqlBlocks.get(bestQueryIdx - 1);
                    String upperPrev = prevBlock.trim().toUpperCase();
                    if (upperPrev.startsWith("SET ") && !upperPrev.contains("SELECT")) {
                        selected = prevBlock + "\n\n" + bestQuery;
                    }
                }
                if (selected == null) {
                    selected = bestQuery;
                }
            }
        }

        // Ultimate fallback: first SQL block
        if (selected == null) {
            selected = sqlBlocks.get(0);
        }

        // Resolve any missing SET declarations for @variables referenced in the query
        return resolveSetPreamble(selected, sqlBlocks, response);
    }

    /**
     * Extract index recommendations from AI response
     * Returns only actual CREATE INDEX statements or column index notation like "table(column)"
     */
    private List<String> extractIndexRecommendations(String response, String dbType) {
        List<String> recommendations = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        Set<String> indexNames = new HashSet<>();  // Track just the index names to dedupe

        // Words that should NOT be treated as table names in column notation
        Set<String> skipTableWords = Set.of(
            // SQL functions
            "any_value", "count", "sum", "max", "min", "avg", "coalesce", "ifnull", "nullif",
            "concat", "substring", "trim", "lower", "upper", "length", "replace", "cast",
            // Common false positive words from AI response labels
            "index", "indexes", "note", "optional", "example", "recommendation", "primary", "covering",
            "alternative", "secondary", "clustered", "composite", "unique", "partial",
            "impact", "priority", "solution", "problem", "issue", "query", "table",
            "large", "small", "medium", "high", "low", "slow", "fast", "old", "new",
            // Database features that look like table(column) but aren't
            "partitioning", "sharding", "replication", "caching", "indexing",
            // AI response phrases that look like table(content)
            "needed", "documented", "results", "required", "recommended", "expected",
            "suggested", "important", "critical", "warning", "error",
            // More false positives from AI responses
            "normalized", "aggregation", "denormalized", "optimization", "performance",
            "strategy", "approach", "technique", "method", "pattern", "analysis",
            "benefit", "advantage", "improvement", "reduction", "increase"
        );

        // Words that should NOT appear in column lists (indicating it's prose, not column names)
        Set<String> skipColumnWords = Set.of(
            "see", "above", "below", "rows", "millions", "thousands", "example", "optional",
            "needed", "required", "recommended", "alternative", "details", "refer",
            // Sentence fragments
            "to", "and", "the", "with", "for", "from", "align", "improve", "usage",
            "dangerous", "inconsistency", "filesort", "performance", "schema",
            // More prose words
            "uppercase", "lowercase", "once", "biggest", "win", "best", "worst", "better",
            "faster", "slower", "more", "less", "most", "least", "major", "minor"
        );

        // Extract CREATE INDEX statements from code blocks
        java.util.regex.Matcher blockMatcher = SQL_CODE_BLOCK_PATTERN.matcher(response);
        while (blockMatcher.find()) {
            String sql = blockMatcher.group(1).trim();
            if (sql.toLowerCase().contains("create index") || sql.toLowerCase().contains("add index")) {
                // Split by semicolon in case multiple statements
                boolean hashIsComment = !"postgres".equalsIgnoreCase(dbType);
                for (String stmt : com.dbaagent.util.SqlStatementSplitter.split(sql, hashIsComment)) {
                    String trimmed = stmt.trim();
                    // Skip comments
                    if (trimmed.startsWith("--")) continue;
                    if (!trimmed.isEmpty() && trimmed.length() > 10 &&
                        !seen.contains(trimmed.toLowerCase())) {
                        // Extract index name for deduplication
                        java.util.regex.Matcher nameMatcher = java.util.regex.Pattern.compile(
                            "(?i)INDEX\\s+(\\w+)", java.util.regex.Pattern.CASE_INSENSITIVE
                        ).matcher(trimmed);
                        if (nameMatcher.find()) {
                            String idxName = nameMatcher.group(1).toLowerCase();
                            if (!indexNames.contains(idxName)) {
                                recommendations.add(trimmed);
                                seen.add(trimmed.toLowerCase());
                                indexNames.add(idxName);
                            }
                        } else {
                            recommendations.add(trimmed);
                            seen.add(trimmed.toLowerCase());
                        }
                    }
                }
            }
        }

        // Look for inline CREATE INDEX statements (not in code blocks)
        // Must include ON clause to be a complete statement
        java.util.regex.Pattern createIndexPattern = java.util.regex.Pattern.compile(
            "CREATE\\s+(?:UNIQUE\\s+)?INDEX\\s+(\\w+)\\s+ON\\s+\\w+\\s*\\([^)]+\\)",
            java.util.regex.Pattern.CASE_INSENSITIVE
        );
        java.util.regex.Matcher createMatcher = createIndexPattern.matcher(response);
        while (createMatcher.find()) {
            String match = createMatcher.group().trim();
            String idxName = createMatcher.group(1).toLowerCase();
            // Skip if we already have an index with this name
            if (!indexNames.contains(idxName) && !seen.contains(match.toLowerCase())) {
                recommendations.add(match);
                seen.add(match.toLowerCase());
                indexNames.add(idxName);
            }
        }

        // Look for column notation like "table(column1, column2)" in bullet points
        // Only match real table names (contain underscore or are known patterns)
        java.util.regex.Pattern columnNotationPattern = java.util.regex.Pattern.compile(
            "`?([a-zA-Z][a-zA-Z0-9_]+)\\s*\\(([a-zA-Z_][a-zA-Z0-9_,\\s]+)\\)`?",
            java.util.regex.Pattern.CASE_INSENSITIVE
        );

        String[] lines = response.split("\n");
        for (String line : lines) {
            String trimmed = line.trim();

            // Skip separators, headers, short lines, and lines with emojis + labels
            if (trimmed.length() < 10 || trimmed.startsWith("#") ||
                trimmed.equals("--") || trimmed.equals("---") ||
                trimmed.matches("^-{2,}$") ||
                trimmed.matches("^[✅❌🔴🟠🟢⚠️]\\s*\\*\\*.*\\*\\*.*")) continue;

            // Look for column notation in bullet points
            if (trimmed.startsWith("-") || trimmed.startsWith("•") || trimmed.startsWith("*") ||
                trimmed.matches("^\\d+\\..*")) {

                java.util.regex.Matcher notationMatcher = columnNotationPattern.matcher(trimmed);
                while (notationMatcher.find()) {
                    String tableName = notationMatcher.group(1);
                    String columns = notationMatcher.group(2);

                    // Skip false positives - words that are not table names
                    String lowerTable = tableName.toLowerCase();
                    if (skipTableWords.contains(lowerTable)) continue;

                    // Check if columns contain skip words (indicating it's not a real column list)
                    String lowerColumns = columns.toLowerCase();
                    boolean hasSkipWord = false;
                    for (String skipWord : skipColumnWords) {
                        if (lowerColumns.contains(skipWord)) {
                            hasSkipWord = true;
                            break;
                        }
                    }
                    if (hasSkipWord) continue;

                    // Table names should typically contain underscore or be plural
                    // e.g., "orders", "user_accounts", "payment_transactions"
                    // Skip single generic words without underscore unless they look like real tables
                    if (!tableName.contains("_") && tableName.length() < 5 &&
                        !tableName.toLowerCase().endsWith("s")) continue;

                    String indexNotation = tableName + "(" + columns.trim() + ")";
                    if (!seen.contains(indexNotation.toLowerCase()) && indexNotation.length() > 5) {
                        recommendations.add(indexNotation);
                        seen.add(indexNotation.toLowerCase());
                    }
                }
            }
        }

        return recommendations;
    }

    /**
     * Extract suggestions from numbered lists, bullet points, and sections
     */
    private List<OptimizationSuggestion> extractSuggestions(String response) {
        List<OptimizationSuggestion> suggestions = new ArrayList<>();

        // First, try to extract from emoji-prefixed sections (🔴, 🟠, 🟢, etc.)
        suggestions.addAll(extractEmojiSections(response));

        if (!suggestions.isEmpty()) {
            return postProcessSuggestions(suggestions);
        }

        // Split by common section delimiters
        String[] sections = response.split("(?m)^(?:#{1,3}|\\*\\*\\d+\\.|\\d+\\.)\\s*");

        for (String section : sections) {
            if (section.trim().isEmpty()) continue;

            // Try to parse numbered items within each section
            java.util.regex.Pattern numberedPattern = java.util.regex.Pattern.compile(
                "(?m)^(?:(\\d+)\\.\\s*\\*\\*(.+?)\\*\\*|\\*\\*?(\\d+)\\.?\\s*(.+?)\\*\\*?)\\s*[:\\-]?\\s*(.*)$"
            );

            java.util.regex.Matcher matcher = numberedPattern.matcher(section);
            while (matcher.find()) {
                String title = matcher.group(2) != null ? matcher.group(2) : matcher.group(4);
                String description = matcher.group(5);

                if (title != null && !title.isEmpty()) {
                    OptimizationSuggestion suggestion = parseSuggestionItem(title, description, section);
                    if (suggestion != null) {
                        suggestions.add(suggestion);
                    }
                }
            }

            // Also look for bold items without numbers
            java.util.regex.Pattern boldPattern = java.util.regex.Pattern.compile(
                "\\*\\*(.+?)\\*\\*[:\\s]*([^*]+?)(?=\\*\\*|$)", java.util.regex.Pattern.DOTALL
            );
            matcher = boldPattern.matcher(section);
            while (matcher.find()) {
                String title = matcher.group(1).trim();
                String description = matcher.group(2).trim();

                // Skip common headers
                if (title.toLowerCase().contains("query") && title.toLowerCase().contains("optimiz")) continue;
                if (title.toLowerCase().equals("explanation")) continue;
                if (title.toLowerCase().equals("summary")) continue;

                if (!title.isEmpty() && description.length() > 10) {
                    OptimizationSuggestion suggestion = parseSuggestionItem(title, description, section);
                    if (suggestion != null && !containsSimilar(suggestions, suggestion)) {
                        suggestions.add(suggestion);
                    }
                }
            }
        }

        // If no structured suggestions found, try to extract from bullet points
        if (suggestions.isEmpty()) {
            suggestions.addAll(extractBulletPointSuggestions(response));
        }

        return postProcessSuggestions(suggestions);
    }

    /**
     * Post-process suggestions: filter junk, clean descriptions, cap at reasonable count.
     */
    private List<OptimizationSuggestion> postProcessSuggestions(List<OptimizationSuggestion> raw) {
        List<OptimizationSuggestion> filtered = new ArrayList<>();
        for (OptimizationSuggestion s : raw) {
            if (!isValidSuggestion(s)) continue;
            // Rebuild with cleaned title and description
            filtered.add(OptimizationSuggestion.builder()
                .category(s.getCategory())
                .title(cleanTitle(s.getTitle()))
                .description(cleanSuggestionDescription(s.getDescription()))
                .implementationSQL(s.getImplementationSQL())
                .priority(s.getPriority())
                .estimatedImpact(s.getEstimatedImpact())
                .build());
        }
        // Cap at 10 suggestions to avoid noisy output
        if (filtered.size() > 10) {
            filtered = filtered.subList(0, 10);
        }
        return filtered;
    }

    /**
     * Filter out non-actionable suggestion fragments: metadata, results, conversational text.
     */
    private boolean isValidSuggestion(OptimizationSuggestion s) {
        if (s == null || s.getTitle() == null) return false;
        String title = s.getTitle().toLowerCase().trim();
        String desc = s.getDescription() != null ? s.getDescription().toLowerCase().trim() : "";

        // Skip metadata titles (estimated impact, improvement percentages)
        if (title.matches(".*estimated\\s+(impact|improvement|performance).*")) return false;
        if (title.matches(".*overall\\s+estimated.*")) return false;
        if (title.matches("^\\d+[\\-–]\\d+\\s*%.*")) return false;
        // Improvement percentage as title ("80% overall performance improvement", "65% faster")
        if (title.matches("^\\d+%\\s+(overall|performance|improvement|faster|better|reduction).*")) return false;

        // Skip result/metric fragments ("12s → 2-4s", "76M → <5M")
        if (title.matches("^\\d+[smhMKGT]?\\s*[→>]\\s*[<~]?\\d+.*")) return false;

        // Skip non-actionable context/assumption labels (never actionable regardless of desc length)
        if (title.matches("(?i)^(key\\s+)?assumptions?$")) return false;
        if (title.matches("(?i)^precomputed\\s+columns?$")) return false;
        if (title.matches("(?i)^(context|background|note|summary|overview|conclusion)$")) return false;

        // Skip diagnostic labels that describe problems but not solutions
        if (title.matches("(?i)^problems?$")) return false;

        // Skip intro/conversational fragments
        if (title.equals("deep-dive optimization") || title.equals("deep dive optimization")) return false;
        if (title.matches("(?i)^explain\\s+analyze.*") && desc.length() < 30) return false;
        if (title.matches("(?i)^partitioning strategy$") && desc.contains("just tell me")) return false;
        if (title.matches("(?i)^solution$") && desc.length() < 30) return false;

        // Skip titles with bracket artifacts from broken markdown parsing ("[PATTERN]" -> "PATTERN]")
        if (title.matches("^[A-Z]+\\]\\s+.*") && title.length() < 40) return false;

        // Skip titles that are just arrows/symbols or start with → (description fragment parsed as title)
        if (title.matches("^[→>←<\\-–]+.*") && title.length() < 15) return false;

        // Skip very short titles with very short descriptions
        if (title.length() < 5 && desc.length() < 20) return false;

        // Skip suggestions where description is just a cross-reference, parenthetical, or single label
        if (desc.matches("^\\(?see\\s+(suggestions|above|below|section).*")) return false;
        if (desc.matches("^(issue|problem|note|todo|fix):?\\s*$")) return false;

        return true;
    }

    /**
     * Clean suggestion description: strip markdown artifacts, truncate embedded SQL, remove dividers.
     */
    private String cleanSuggestionDescription(String description) {
        if (description == null || description.isBlank()) return "";
        String d = description;

        // Remove markdown horizontal rules
        d = d.replaceAll("(?m)^-{3,}\\s*$", "").trim();
        d = d.replaceAll("\\s*-{3,}\\s*", " ").trim();

        // Remove markdown code block markers (but keep the content between them short)
        // Replace ```sql ... ``` blocks with truncated version
        java.util.regex.Matcher codeBlock = java.util.regex.Pattern.compile(
            "```(?:sql)?\\s*(.+?)```", java.util.regex.Pattern.DOTALL
        ).matcher(d);
        StringBuilder sb = new StringBuilder();
        while (codeBlock.find()) {
            String code = codeBlock.group(1).trim();
            String truncated = code.length() > 120
                ? code.substring(0, 120).replaceAll("\\s+", " ") + "..."
                : code.replaceAll("\\s+", " ");
            codeBlock.appendReplacement(sb, java.util.regex.Matcher.quoteReplacement(truncated));
        }
        codeBlock.appendTail(sb);
        d = sb.toString();

        // Remove stray backticks
        d = d.replace("```", "").replace("`", "");

        // Remove bold markers
        d = d.replaceAll("\\*\\*", "");

        // Strip markdown headers inside descriptions
        d = d.replaceAll("#{1,3}\\s+[^\\n]+", " ");

        // Strip inline metadata ("Priority: HIGH", "Estimated Impact: 25-35%")
        d = d.replaceAll("(?i)\\bPriority:\\s*(HIGH|MEDIUM|LOW|CRITICAL)\\b", "");
        d = d.replaceAll("(?i)\\bEstimated\\s+Impact:\\s*\\d+[\\-–]?\\d*\\s*%", "");

        // Collapse whitespace
        d = d.replaceAll("\\s+", " ").trim();

        // Remove trailing conversational filler
        d = d.replaceAll("(?i)\\s*(?:if you want,? I can[:\\.]?.*|just tell me\\.?)\\s*$", "").trim();

        // Truncate if still very long
        if (d.length() > 300) {
            d = d.substring(0, 297) + "...";
        }

        return d;
    }

    private OptimizationSuggestion parseSuggestionItem(String title, String description, String context) {
        if (title == null || title.isEmpty()) return null;

        // Determine category based on keywords
        String category = determineCategory(title + " " + description);

        // Determine priority based on keywords
        String priority = determinePriority(title + " " + description + " " + context);

        // Extract SQL if present
        String sql = null;
        java.util.regex.Matcher sqlMatcher = SQL_CODE_BLOCK_PATTERN.matcher(context);
        if (sqlMatcher.find()) {
            sql = sqlMatcher.group(1).trim();
        }

        // Extract impact percentage if mentioned
        Double impact = null;
        java.util.regex.Matcher impactMatcher = PERCENTAGE_PATTERN.matcher(title + " " + description);
        if (impactMatcher.find()) {
            try {
                impact = Double.parseDouble(impactMatcher.group(1));
            } catch (NumberFormatException ignored) {}
        }

        return OptimizationSuggestion.builder()
            .category(category)
            .title(cleanTitle(title))
            .description(cleanDescription(description))
            .implementationSQL(sql)
            .priority(priority)
            .estimatedImpact(impact)
            .build();
    }

    private List<OptimizationSuggestion> extractBulletPointSuggestions(String response) {
        List<OptimizationSuggestion> suggestions = new ArrayList<>();
        String[] lines = response.split("\n");

        for (int i = 0; i < lines.length; i++) {
            String line = lines[i].trim();

            // Look for bullet points or numbered items
            if (line.matches("^[-•*]\\s+.+") || line.matches("^\\d+\\.\\s+.+")) {
                String content = line.replaceFirst("^[-•*\\d.]+\\s*", "").trim();

                // Skip short items or items that look like sub-bullets
                if (content.length() < 15) continue;

                // Get additional context from following lines
                StringBuilder desc = new StringBuilder(content);
                for (int j = i + 1; j < lines.length && j < i + 3; j++) {
                    String nextLine = lines[j].trim();
                    if (nextLine.isEmpty() || nextLine.matches("^[-•*\\d.]+\\s+.+")) break;
                    if (!nextLine.startsWith("#") && !nextLine.startsWith("```")) {
                        desc.append(" ").append(nextLine);
                    }
                }

                String fullContent = desc.toString();
                String category = determineCategory(fullContent);
                String priority = determinePriority(fullContent);

                // Extract a title from the content
                String title = content.length() > 60 ? content.substring(0, 60) + "..." : content;

                suggestions.add(OptimizationSuggestion.builder()
                    .category(category)
                    .title(cleanTitle(title))
                    .description(cleanDescription(fullContent))
                    .priority(priority)
                    .build());

                // Limit to avoid too many suggestions
                if (suggestions.size() >= 10) break;
            }
        }

        return suggestions;
    }

    /**
     * Extract suggestions from emoji-prefixed sections (🔴, 🟠, 🟢, 🚨, etc.)
     */
    private List<OptimizationSuggestion> extractEmojiSections(String response) {
        List<OptimizationSuggestion> suggestions = new ArrayList<>();

        // Pattern to match emoji-prefixed section headers
        // e.g., "### 🔴 QUERY LOGIC ISSUE – Misuse of DISTINCT"
        // Match any emoji character at the start of a markdown header
        java.util.regex.Pattern emojiSectionPattern = java.util.regex.Pattern.compile(
            "(?m)^#{1,4}\\s*([🔴🟠🟢🔵⚠️❌✅🚨⛔🔶🔷💡📌🎯])\\s*(.+?)(?:\\s*[–:\\-]\\s*(.+))?$"
        );

        java.util.regex.Matcher matcher = emojiSectionPattern.matcher(response);
        List<int[]> sectionBounds = new ArrayList<>();
        List<String[]> sectionHeaders = new ArrayList<>();

        while (matcher.find()) {
            sectionBounds.add(new int[]{matcher.start(), matcher.end()});
            sectionHeaders.add(new String[]{
                matcher.group(1),  // emoji
                matcher.group(2),  // category
                matcher.group(3)   // title (may be null)
            });
        }

        // Extract content for each section
        for (int i = 0; i < sectionBounds.size(); i++) {
            int start = sectionBounds.get(i)[1];  // After header
            int end = (i + 1 < sectionBounds.size()) ? sectionBounds.get(i + 1)[0] : response.length();

            String content = response.substring(start, end).trim();
            String[] header = sectionHeaders.get(i);
            String emoji = header[0];
            String categoryText = header[1] != null ? header[1].trim() : "";
            String titleText = header[2] != null ? header[2].trim() : categoryText;

            // Skip if this is the "OPTIMIZED QUERY" section
            if (categoryText.toLowerCase().contains("optimized query")) continue;

            // Determine priority based on emoji
            String priority = "MEDIUM";
            if (emoji.equals("🔴") || emoji.equals("❌") || emoji.equals("🚨") || emoji.equals("⛔")) {
                priority = "HIGH";
            } else if (emoji.equals("🟢") || emoji.equals("✅") || emoji.equals("💡")) {
                priority = "LOW";
            }

            // Determine category
            String category = determineCategory(categoryText + " " + titleText);

            // Extract description (first paragraph before any code block)
            String description = content;
            int codeBlockIdx = content.indexOf("```");
            if (codeBlockIdx > 0) {
                description = content.substring(0, codeBlockIdx);
            }

            // Clean up description - extract "Problem" and "Solution" if present
            StringBuilder cleanDesc = new StringBuilder();
            if (description.contains("**Problem**") || description.contains("Problem")) {
                java.util.regex.Pattern problemPattern = java.util.regex.Pattern.compile(
                    "(?i)\\*?\\*?Problem\\*?\\*?[:\\s]*([^*]+?)(?=\\*?\\*?Solution|```|Priority|$)",
                    java.util.regex.Pattern.DOTALL
                );
                java.util.regex.Matcher problemMatcher = problemPattern.matcher(content);
                if (problemMatcher.find()) {
                    cleanDesc.append(problemMatcher.group(1).trim());
                }
            }

            if (cleanDesc.length() == 0) {
                // Use first 300 chars of description
                cleanDesc.append(description.length() > 300 ? description.substring(0, 300) + "..." : description);
            }

            // Extract SQL if present
            String sql = null;
            java.util.regex.Matcher sqlMatcher = SQL_CODE_BLOCK_PATTERN.matcher(content);
            if (sqlMatcher.find()) {
                sql = sqlMatcher.group(1).trim();
            }

            // Extract estimated impact if present
            Double impact = null;
            java.util.regex.Matcher impactMatcher = java.util.regex.Pattern.compile(
                "(?i)(?:estimated\\s+)?impact[:\\s]*(\\d{1,2})(?:[-–](\\d{1,2}))?\\s*%"
            ).matcher(content);
            if (impactMatcher.find()) {
                try {
                    if (impactMatcher.group(2) != null) {
                        // Range like "30-50%", use average
                        double low = Double.parseDouble(impactMatcher.group(1));
                        double high = Double.parseDouble(impactMatcher.group(2));
                        impact = (low + high) / 2;
                    } else {
                        impact = Double.parseDouble(impactMatcher.group(1));
                    }
                } catch (NumberFormatException ignored) {}
            }

            if (!titleText.isEmpty()) {
                suggestions.add(OptimizationSuggestion.builder()
                    .category(category)
                    .title(cleanTitle(titleText))
                    .description(cleanDescription(cleanDesc.toString()))
                    .implementationSQL(sql)
                    .priority(priority)
                    .estimatedImpact(impact)
                    .build());
            }
        }

        return suggestions;
    }

    private String determineCategory(String text) {
        String lower = text.toLowerCase();
        if (lower.contains("index") || lower.contains("idx_")) return "INDEX";
        if (lower.contains("rewrite") || lower.contains("restructure") || lower.contains("cte") || lower.contains("subquery")) return "QUERY_REWRITE";
        if (lower.contains("schema") || lower.contains("table design") || lower.contains("normalization")) return "SCHEMA";
        if (lower.contains("config") || lower.contains("parameter") || lower.contains("setting")) return "CONFIG";
        if (lower.contains("join") || lower.contains("where") || lower.contains("filter")) return "QUERY_REWRITE";
        if (lower.contains("cache") || lower.contains("buffer")) return "CONFIG";
        return "GENERAL";
    }

    private String determinePriority(String text) {
        String lower = text.toLowerCase();
        if (lower.contains("critical") || lower.contains("severe") || lower.contains("major") ||
            lower.contains("significant") || lower.contains("high impact") || lower.contains("high priority")) {
            return "HIGH";
        }
        if (lower.contains("minor") || lower.contains("low") || lower.contains("optional") ||
            lower.contains("consider") || lower.contains("might")) {
            return "LOW";
        }
        return "MEDIUM";
    }

    private String cleanTitle(String title) {
        if (title == null) return "";
        return title.replaceAll("\\*\\*", "").replaceAll("^[:\\-]+", "").trim();
    }

    private String cleanDescription(String description) {
        if (description == null) return "";
        String d = description;
        d = d.replaceAll("\\*\\*", "");
        d = d.replaceAll("(?m)^-{3,}\\s*$", "");
        d = d.replaceAll("\\s*-{3,}\\s*", " ");
        d = d.replaceAll("\\s+", " ");
        return d.trim();
    }

    private boolean containsSimilar(List<OptimizationSuggestion> suggestions, OptimizationSuggestion newSuggestion) {
        for (OptimizationSuggestion existing : suggestions) {
            if (existing.getTitle().equalsIgnoreCase(newSuggestion.getTitle())) {
                return true;
            }
        }
        return false;
    }

    /**
     * Extract explanation/summary from AI response
     */
    private String extractExplanation(String response) {
        // Look for explanation/summary sections with flexible matching
        String[] sectionHeaders = {"explanation", "summary", "analysis", "overview", "conclusion", "root cause", "why", "issue", "problem"};

        for (String header : sectionHeaders) {
            // Match headers like "## Explanation", "**Summary**:", "Why is this slow", etc.
            // Terminate only at real section boundaries: ## headers, ALL-CAPS bold headers (**SUGGESTIONS**), ---, or ```
            // Do NOT terminate at mixed-case bold items like **Non-sargable date expressions** (those are inline content)
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(
                "(?im)(?:^#{1,3}\\s*|\\*\\*)?(?:" + header + ")[^\\n]*(?:\\*\\*)?[:\\s]*\\n([\\s\\S]*?)(?=^#{1,3}\\s|^\\*\\*[A-Z][A-Z ]{2,}\\*\\*|^---\\s*$|```|$)",
                java.util.regex.Pattern.MULTILINE
            );
            java.util.regex.Matcher matcher = pattern.matcher(response);
            if (matcher.find()) {
                String content = matcher.group(1).trim();
                if (content.length() > 20) {
                    return cleanDescription(content);
                }
            }
        }

        // Try to find content after "The query is slow" or similar phrases
        java.util.regex.Pattern slowPattern = java.util.regex.Pattern.compile(
            "(?i)(?:the\\s+)?(?:query|sql)\\s+is\\s+slow[^.]*\\.([\\s\\S]{50,500}?)(?:```|$)"
        );
        java.util.regex.Matcher slowMatcher = slowPattern.matcher(response);
        if (slowMatcher.find()) {
            return cleanDescription(slowMatcher.group(0).trim());
        }

        // If no explicit section, try to extract the main content before any code blocks
        int firstCodeBlock = response.indexOf("```");
        if (firstCodeBlock > 50) {
            String beforeCode = response.substring(0, firstCodeBlock).trim();
            // Skip if it's just a header
            if (beforeCode.length() > 30 && !beforeCode.matches("(?s)^#{1,3}\\s*[^\\n]+$")) {
                return cleanDescription(beforeCode);
            }
        }

        // Look for content after the first code block if there's substantial text
        if (firstCodeBlock > 0) {
            int codeBlockEnd = response.indexOf("```", firstCodeBlock + 3);
            if (codeBlockEnd > 0 && codeBlockEnd < response.length() - 50) {
                String afterCode = response.substring(codeBlockEnd + 3).trim();
                // Find meaningful text (not just another code block)
                int nextCodeBlock = afterCode.indexOf("```");
                if (nextCodeBlock == -1 || nextCodeBlock > 50) {
                    String content = nextCodeBlock > 0 ? afterCode.substring(0, nextCodeBlock) : afterCode;
                    if (content.length() > 30) {
                        return cleanDescription(content);
                    }
                }
            }
        }

        // Use the full response if it's reasonably short and doesn't start with code
        if (response.length() < 2000 && !response.trim().startsWith("```")) {
            return cleanDescription(response);
        }

        return null;
    }

    /**
     * Extract estimated improvement percentage from AI response.
     * Returns value between 0-100 representing percentage improvement.
     *
     * Strategy:
     * 1. Look for the dedicated "## ESTIMATED IMPROVEMENT" section first (most reliable)
     * 2. Fall back to regex patterns in the full response text
     * 3. Handle ranges like "60-80%" (use midpoint) and multipliers like "3x faster"
     */
    private Double extractEstimatedImprovement(String response) {
        if (response == null || response.isBlank()) return null;

        // 1. Try to extract from the dedicated ## ESTIMATED IMPROVEMENT section
        Double sectionValue = extractFromEstimatedImprovementSection(response);
        if (sectionValue != null) return sectionValue;

        // 2. Look for explicit improvement mentions with percentage - use word boundaries
        String[] patterns = {
            "(?i)(?:estimated|expected|potential|overall)\\s+(?:improvement|performance gain|speedup)[:\\s]*\\b(\\d{1,3})\\s*[\\-–]\\s*(\\d{1,3})\\s*%",
            "(?i)(?:estimated|expected|potential|overall)\\s+(?:improvement|performance gain|speedup)[:\\s]*\\b(\\d{1,3})\\s*%",
            "(?i)\\b(\\d{1,3})\\s*[\\-–]\\s*(\\d{1,3})\\s*%\\s+(?:improvement|faster|better|reduction|speedup)",
            "(?i)\\b(\\d{1,3})\\s*%\\s+(?:improvement|faster|better|reduction|speedup)",
            "(?i)(?:improve|reduce|speed up).*?by\\s+\\b(\\d{1,3})\\s*[\\-–]\\s*(\\d{1,3})\\s*%",
            "(?i)(?:improve|reduce|speed up).*?by\\s+\\b(\\d{1,3})\\s*%",
            "(?i)(?:up to|around|approximately)\\s+\\b(\\d{1,3})\\s*%\\s+(?:faster|improvement|speedup)",
            "(?i)\\bperformance.*?\\b(\\d{1,3})\\s*%(?!\\d)"
        };

        for (String patternStr : patterns) {
            java.util.regex.Pattern pattern = java.util.regex.Pattern.compile(patternStr);
            java.util.regex.Matcher matcher = pattern.matcher(response);
            if (matcher.find()) {
                try {
                    if (matcher.groupCount() >= 2 && matcher.group(2) != null) {
                        // Range pattern: use midpoint
                        double low = Double.parseDouble(matcher.group(1));
                        double high = Double.parseDouble(matcher.group(2));
                        double midpoint = (low + high) / 2.0;
                        if (midpoint >= 1 && midpoint <= 99) return midpoint;
                    } else {
                        double value = Double.parseDouble(matcher.group(1));
                        if (value >= 1 && value <= 99) return value;
                    }
                } catch (NumberFormatException ignored) {}
            }
        }

        // 3. Handle multiplier patterns like "3x faster", "5-10x improvement"
        java.util.regex.Pattern multiplierRange = java.util.regex.Pattern.compile(
            "(?i)(\\d+(?:\\.\\d+)?)\\s*[\\-–]\\s*(\\d+(?:\\.\\d+)?)\\s*[xX]\\s+(?:faster|improvement|speedup|better)"
        );
        java.util.regex.Matcher mrMatcher = multiplierRange.matcher(response);
        if (mrMatcher.find()) {
            try {
                double low = Double.parseDouble(mrMatcher.group(1));
                double high = Double.parseDouble(mrMatcher.group(2));
                double midMultiplier = (low + high) / 2.0;
                if (midMultiplier > 1 && midMultiplier <= 100) {
                    double pct = (1 - 1.0 / midMultiplier) * 100;
                    if (pct >= 1 && pct <= 99) return Math.round(pct * 10) / 10.0;
                }
            } catch (NumberFormatException ignored) {}
        }

        java.util.regex.Pattern multiplierSingle = java.util.regex.Pattern.compile(
            "(?i)(\\d+(?:\\.\\d+)?)\\s*[xX]\\s+(?:faster|improvement|speedup|better)"
        );
        java.util.regex.Matcher msMatcher = multiplierSingle.matcher(response);
        if (msMatcher.find()) {
            try {
                double multiplier = Double.parseDouble(msMatcher.group(1));
                if (multiplier > 1 && multiplier <= 100) {
                    double pct = (1 - 1.0 / multiplier) * 100;
                    if (pct >= 1 && pct <= 99) return Math.round(pct * 10) / 10.0;
                }
            } catch (NumberFormatException ignored) {}
        }

        return null;
    }

    /**
     * Extract improvement from the dedicated ## ESTIMATED IMPROVEMENT section.
     * Handles: "60%", "60-80%", "~70%", "approximately 60%", "3x faster"
     */
    private Double extractFromEstimatedImprovementSection(String response) {
        java.util.regex.Pattern sectionPattern = java.util.regex.Pattern.compile(
            "(?im)^##\\s*ESTIMATED\\s+IMPROVEMENT\\s*$"
        );
        java.util.regex.Matcher sectionMatcher = sectionPattern.matcher(response);
        if (!sectionMatcher.find()) return null;

        // Get content until next ## or end of response
        int start = sectionMatcher.end();
        int end = response.length();
        java.util.regex.Matcher nextSection = java.util.regex.Pattern.compile("(?m)^##\\s").matcher(response);
        if (nextSection.find(start)) {
            end = nextSection.start();
        }
        String sectionContent = response.substring(start, end).trim();
        if (sectionContent.isBlank()) return null;

        // Try range: "60-80%"
        java.util.regex.Matcher rangeMatcher = java.util.regex.Pattern.compile(
            "(\\d{1,3})\\s*[\\-–]\\s*(\\d{1,3})\\s*%"
        ).matcher(sectionContent);
        if (rangeMatcher.find()) {
            try {
                double low = Double.parseDouble(rangeMatcher.group(1));
                double high = Double.parseDouble(rangeMatcher.group(2));
                double midpoint = (low + high) / 2.0;
                if (midpoint >= 1 && midpoint <= 99) return midpoint;
            } catch (NumberFormatException ignored) {}
        }

        // Try single: "60%", "~70%", "approximately 60%"
        java.util.regex.Matcher singleMatcher = java.util.regex.Pattern.compile(
            "(\\d{1,3}(?:\\.\\d+)?)\\s*%"
        ).matcher(sectionContent);
        if (singleMatcher.find()) {
            try {
                double value = Double.parseDouble(singleMatcher.group(1));
                if (value >= 1 && value <= 99) return value;
            } catch (NumberFormatException ignored) {}
        }

        // Try multiplier: "3x faster"
        java.util.regex.Matcher multMatcher = java.util.regex.Pattern.compile(
            "(\\d+(?:\\.\\d+)?)\\s*[xX]\\s+(?:faster|improvement|speedup|better)"
        ).matcher(sectionContent);
        if (multMatcher.find()) {
            try {
                double multiplier = Double.parseDouble(multMatcher.group(1));
                if (multiplier > 1 && multiplier <= 100) {
                    double pct = (1 - 1.0 / multiplier) * 100;
                    if (pct >= 1 && pct <= 99) return Math.round(pct * 10) / 10.0;
                }
            } catch (NumberFormatException ignored) {}
        }

        return null;
    }

    private String extractContent(String section, String header) {
        return section.substring(header.length()).trim();
    }

    private List<OptimizationSuggestion> parseSuggestions(String section) {
        List<OptimizationSuggestion> suggestions = new ArrayList<>();

        String[] parts = section.split("###");
        for (String part : parts) {
            if (part.trim().isEmpty() || part.trim().equals("SUGGESTIONS")) continue;

            try {
                String[] lines = part.trim().split("\n");
                if (lines.length == 0) continue;

                String titleLine = lines[0].trim();
                String category = "GENERAL";
                String title = titleLine;

                // Extract category from title format: [CATEGORY] Title
                if (titleLine.startsWith("[")) {
                    int endBracket = titleLine.indexOf("]");
                    if (endBracket > 0) {
                        category = titleLine.substring(1, endBracket).trim();
                        title = titleLine.substring(endBracket + 1).trim();
                    }
                }

                StringBuilder description = new StringBuilder();
                StringBuilder sql = new StringBuilder();
                String priority = "MEDIUM";
                Double impact = null;
                boolean inCodeBlock = false;

                for (int i = 1; i < lines.length; i++) {
                    String line = lines[i];
                    if (line.trim().startsWith("```")) {
                        inCodeBlock = !inCodeBlock;
                        continue;
                    }
                    if (inCodeBlock) {
                        sql.append(line).append("\n");
                    } else if (line.toLowerCase().startsWith("priority:")) {
                        priority = line.substring(9).trim().toUpperCase();
                    } else if (line.toLowerCase().startsWith("estimated impact:")) {
                        String impactStr = line.substring(17).replaceAll("[^0-9.]", "");
                        try {
                            if (!impactStr.isEmpty()) {
                                impact = Double.parseDouble(impactStr);
                            }
                        } catch (NumberFormatException ignored) {}
                    } else {
                        description.append(line).append(" ");
                    }
                }

                if (!title.isEmpty()) {
                    suggestions.add(OptimizationSuggestion.builder()
                        .category(category)
                        .title(title)
                        .description(description.toString().trim())
                        .implementationSQL(sql.toString().trim().isEmpty() ? null : sql.toString().trim())
                        .priority(priority)
                        .estimatedImpact(impact)
                        .build());
                }
            } catch (Exception e) {
                log.debug("Error parsing suggestion: {}", e.getMessage());
            }
        }

        return suggestions;
    }

    private List<String> parseIndexRecommendations(String section) {
        List<String> recommendations = new ArrayList<>();
        String content = extractContent(section, "INDEX RECOMMENDATIONS");

        for (String line : content.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("-") || trimmed.startsWith("*")) {
                recommendations.add(trimmed.substring(1).trim());
            } else if (trimmed.toLowerCase().contains("create index")) {
                recommendations.add(trimmed);
            }
        }

        return recommendations;
    }

    /**
     * Batch optimize multiple slow queries
     */
    public List<OptimizationResult> optimizeQueries(String connectionId, List<SlowQuery> queries, int limit) {
        List<OptimizationResult> results = new ArrayList<>();

        int count = 0;
        for (SlowQuery query : queries) {
            if (count >= limit) break;
            if (query.getQueryText() == null || query.getQueryText().isBlank()) continue;

            try {
                // For batch processing, we don't have sampleQuery - use queryText if it doesn't have placeholders
                // queryText from SlowQuery might be original (Performance Schema) or normalized
                String queryText = query.getQueryText();
                String sampleQuery = query.getSampleQuery();
                if (sampleQuery == null || sampleQuery.isBlank()) {
                    boolean hasPlaceholders = queryText != null &&
                        (queryText.contains("?") || queryText.matches(".*\\$\\d+.*"));
                    sampleQuery = !hasPlaceholders ? queryText : null;
                }
                OptimizationResult result = optimizeQuery(connectionId, queryText, sampleQuery, query);
                results.add(result);
                count++;
            } catch (Exception e) {
                log.warn("Failed to optimize query {}: {}", query.getQueryId(), e.getMessage());
            }
        }

        return results;
    }

    /**
     * Re-prompt the AI to fix a failed query rewrite by providing the error message.
     * Returns the corrected SQL, or null if the AI could not fix it.
     */
    public String retryOptimizationWithError(String connectionId, String originalSql, String failedSql, String errorMessage) {
        if (originalSql == null || failedSql == null || errorMessage == null) {
            return null;
        }

        try {
            ConnectionRequest connection = credentialService.getDecryptedConnection(connectionId);
            String dbType = providerRegistry.getCanonicalName(connection.getDbType());

            String prompt = String.format("""
                The following SQL query rewrite failed with a database error.

                **Original Query:**
                ```sql
                %s
                ```

                **Failed Rewrite:**
                ```sql
                %s
                ```

                **Error Message:**
                %s

                Please fix the rewrite to resolve this error while maintaining the same optimization intent.
                Return ONLY the corrected SQL query in a ```sql code block. Do not change the query semantics.
                The corrected query must return exactly the same rows as the original.
                """, originalSql, failedSql, errorMessage);

            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(getSystemPrompt(dbType)));
            messages.add(new UserMessage(prompt));

            String aiResponse = chatClient.prompt()
                .messages(messages)
                .options(org.springframework.ai.chat.prompt.ChatOptions.builder()
                    .temperature(0.1))
                .call()
                .content();

            if (aiResponse == null || aiResponse.isBlank()) {
                return null;
            }

            String correctedSql = extractOptimizedQuery(aiResponse);
            if (correctedSql != null && !correctedSql.isBlank()) {
                log.info("AI retry produced corrected SQL ({} chars)", correctedSql.length());
                return correctedSql;
            }

            return null;
        } catch (Exception e) {
            log.warn("AI retry failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Ensure optimization candidates exist for a query fingerprint.
     * Uses cached AI optimizations + stored sample query to avoid re-running AI.
     */
    @Transactional
    public boolean ensureOptimizationCandidates(String connectionId, String queryFingerprint) {
        if (connectionId == null || connectionId.isBlank() || queryFingerprint == null || queryFingerprint.isBlank()) {
            return false;
        }

        List<com.dbaagent.model.QueryOptimizationCandidateRun> existing =
            candidateService.getCandidates(connectionId, queryFingerprint);
        boolean needsRefresh = existing.isEmpty() || existing.stream().anyMatch(c ->
            c.getEstimatedCost() == null && c.getPlanSignature() == null && c.getPlanText() == null);
        if (!needsRefresh) {
            return false;
        }

        String existingOriginalSql = existing.stream()
            .filter(c -> "ORIGINAL".equalsIgnoreCase(c.getCandidateId()))
            .map(com.dbaagent.model.QueryOptimizationCandidateRun::getCandidateSql)
            .filter(s -> s != null && !s.isBlank())
            .findFirst()
            .orElse(null);

        String existingRewriteSql = existing.stream()
            .filter(c -> "AI_REWRITE".equalsIgnoreCase(c.getCandidateId()))
            .map(com.dbaagent.model.QueryOptimizationCandidateRun::getCandidateSql)
            .filter(s -> s != null && !s.isBlank())
            .findFirst()
            .orElse(null);

        Optional<QueryOptimizationCache> cache =
            cacheRepository.findByConnectionIdAndQueryFingerprint(connectionId, queryFingerprint);
        Optional<QueryFingerprint> fingerprint =
            fingerprintRepository.findByConnectionIdAndFingerprint(connectionId, queryFingerprint);

        // Fallback: fingerprint table uses SHA-256[:16] but cache may use MD5 (from slow query analysis).
        // Try MD5 of normalizedQuery first, then MD5 of normalized sampleQuery (for long queries
        // where normalizedQuery is truncated to 2000 chars but sampleQuery preserves up to 10000).
        if (cache.isEmpty() && fingerprint.isPresent()) {
            String[] candidateSources = {
                fingerprint.get().getNormalizedQuery(),
                fingerprint.get().getSampleQuery() != null
                    ? QueryNormalizer.normalize(fingerprint.get().getSampleQuery()) : null
            };
            for (String source : candidateSources) {
                if (source == null || source.isBlank()) continue;
                String md5Fingerprint = QueryNormalizer.generateMD5Hash(source);
                if (md5Fingerprint.equals(queryFingerprint)) continue;
                cache = cacheRepository.findByConnectionIdAndQueryFingerprint(connectionId, md5Fingerprint);
                if (cache.isPresent()) {
                    log.debug("Found cached optimization via MD5 fallback: {} -> {}", queryFingerprint, md5Fingerprint);
                    break;
                }
            }
        }

        String originalQuery = cache.map(QueryOptimizationCache::getOriginalQuery).orElse(null);
        if (originalQuery == null || originalQuery.isBlank()) {
            originalQuery = fingerprint.map(QueryFingerprint::getNormalizedQuery).orElse(null);
        }
        String sampleQuery = fingerprint.map(QueryFingerprint::getSampleQuery).orElse(null);
        if ((sampleQuery == null || sampleQuery.isBlank()) && existingOriginalSql != null) {
            sampleQuery = existingOriginalSql;
        }
        if (sampleQuery != null && !sampleQuery.isBlank()) {
            sampleQuery = sanitizeQueryText(sampleQuery);
        }
        if (originalQuery != null && !originalQuery.isBlank()) {
            originalQuery = sanitizeQueryText(originalQuery);
        }

        if ((originalQuery == null || originalQuery.isBlank()) && existingOriginalSql != null) {
            originalQuery = existingOriginalSql;
        }

        if ((sampleQuery == null || sampleQuery.isBlank()) && originalQuery != null && !originalQuery.isBlank()) {
            boolean hasPlaceholders = originalQuery.contains("?") || originalQuery.matches(".*\\$\\d+.*");
            if (!hasPlaceholders) {
                sampleQuery = originalQuery;
            }
        }

        if ((originalQuery == null || originalQuery.isBlank()) && sampleQuery != null && !sampleQuery.isBlank()) {
            originalQuery = sampleQuery;
        }

        if (originalQuery == null || originalQuery.isBlank()) {
            return false;
        }

        String optimizedQuery = cache.map(QueryOptimizationCache::getOptimizedQuery).orElse(null);
        if ((optimizedQuery == null || optimizedQuery.isBlank()) && existingRewriteSql != null) {
            optimizedQuery = existingRewriteSql;
        }
        if (optimizedQuery != null && !optimizedQuery.isBlank()) {
            // Preserve SET preamble through sanitization (sanitize strips everything before first DML keyword)
            String setPreambleBeforeSanitize = extractSetPreamble(optimizedQuery);
            optimizedQuery = sanitizeQueryText(optimizedQuery);
            if (setPreambleBeforeSanitize != null && extractSetPreamble(optimizedQuery) == null
                    && optimizedQuery != null && !optimizedQuery.isBlank()) {
                optimizedQuery = setPreambleBeforeSanitize + "\n" + optimizedQuery;
            }
        }

        String dbType = null;
        try {
            ConnectionRequest connection = credentialService.getDecryptedConnection(connectionId);
            dbType = providerRegistry.getCanonicalName(connection.getDbType());
        } catch (Exception e) {
            log.debug("Unable to resolve db type for candidate refresh: {}", e.getMessage());
        }

        recordOptimizationCandidates(
            connectionId,
            dbType,
            queryFingerprint,
            originalQuery,
            sampleQuery,
            optimizedQuery
        );

        return true;
    }

    private void recordOptimizationCandidates(
        String connectionId,
        String dbType,
        String queryFingerprint,
        String originalQuery,
        String sampleQuery,
        String optimizedQuery
    ) {
        if (connectionId == null || connectionId.isBlank() || queryFingerprint == null || queryFingerprint.isBlank()) {
            return;
        }

        String originalCandidateSql = (sampleQuery != null && !sampleQuery.isBlank()) ? sampleQuery : originalQuery;
        Map<String, CandidateSpec> candidates = new LinkedHashMap<>();

        if (originalCandidateSql != null && !originalCandidateSql.isBlank()) {
            String alignedOriginal = alignOptimizedQueryWithSchema(connectionId, dbType, originalCandidateSql);
            if (alignedOriginal != null && !alignedOriginal.isBlank()) {
                originalCandidateSql = alignedOriginal;
            }
            addCandidate(candidates, "ORIGINAL", originalCandidateSql);
        }

        if (optimizedQuery != null && !optimizedQuery.isBlank()) {
            // Preserve SET preamble (session variable declarations) that sanitize() would strip
            String setPreamble = extractSetPreamble(optimizedQuery);
            String sanitizedOptimized = sanitizeQueryText(optimizedQuery);
            if (sanitizedOptimized != null && !sanitizedOptimized.isBlank() && isExplainCandidate(sanitizedOptimized)) {
                String literalized = applySampleLiterals(sanitizedOptimized, sampleQuery);
                if (literalized != null && !literalized.isBlank()) {
                    sanitizedOptimized = literalized;
                }
                String alignedOptimized = alignOptimizedQueryWithSchema(connectionId, dbType, sanitizedOptimized);
                if (alignedOptimized != null && !alignedOptimized.isBlank()) {
                    sanitizedOptimized = alignedOptimized;
                }
                // Prepend SET preamble back so the candidate runs correctly
                if (setPreamble != null) {
                    sanitizedOptimized = setPreamble + "\n" + sanitizedOptimized;
                }
                addCandidate(candidates, "AI_REWRITE", sanitizedOptimized);
            }
        }

        if (originalCandidateSql != null && !originalCandidateSql.isBlank()) {
            try {
                SchemaMetadata schema = schemaScannerService.scanSchema(connectionId);
                List<CandidateSpec> joinCandidates = generateJoinOrderCandidates(
                    originalCandidateSql,
                    dbType,
                    schema,
                    8
                );
                for (CandidateSpec candidate : joinCandidates) {
                    addCandidate(candidates, candidate.id, candidate.sql);
                }
            } catch (Exception e) {
                log.debug("Join-order candidate generation skipped: {}", e.getMessage());
            }
        }

        for (CandidateSpec candidate : candidates.values()) {
            // Force-replace SQL for AI_REWRITE so fresh optimization results always take effect
            boolean forceReplace = "AI_REWRITE".equals(candidate.id);
            upsertCandidateWithOptd(
                connectionId,
                dbType,
                queryFingerprint,
                candidate.id,
                candidate.sql,
                forceReplace
            );
        }
    }

    private static class CandidateSpec {
        private final String id;
        private final String sql;

        private CandidateSpec(String id, String sql) {
            this.id = id;
            this.sql = sql;
        }
    }

    private void addCandidate(Map<String, CandidateSpec> candidates, String id, String sql) {
        if (id == null || id.isBlank() || sql == null || sql.isBlank()) {
            return;
        }
        String normalizedSql = sql.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
        for (CandidateSpec existing : candidates.values()) {
            String existingNormalized = existing.sql.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
            if (existingNormalized.equals(normalizedSql)) {
                return;
            }
        }
        candidates.putIfAbsent(id, new CandidateSpec(id, sql));
    }

    private List<CandidateSpec> generateJoinOrderCandidates(
        String sql,
        String dbType,
        SchemaMetadata schemaMetadata,
        int maxCandidates
    ) throws Exception {
        if (sql == null || sql.isBlank()) {
            return List.of();
        }

        String sanitized = sanitizeQueryText(sql);
        if (!isExplainCandidate(sanitized)) {
            return List.of();
        }

        net.sf.jsqlparser.statement.Statement stmt =
            net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(sanitized);
        if (!(stmt instanceof net.sf.jsqlparser.statement.select.Select)) {
            return List.of();
        }

        net.sf.jsqlparser.statement.select.Select select =
            (net.sf.jsqlparser.statement.select.Select) stmt;
        if (!(select.getSelectBody() instanceof net.sf.jsqlparser.statement.select.PlainSelect)) {
            return List.of();
        }

        net.sf.jsqlparser.statement.select.PlainSelect plain =
            (net.sf.jsqlparser.statement.select.PlainSelect) select.getSelectBody();
        if (plain.getJoins() == null || plain.getJoins().isEmpty()) {
            return List.of();
        }

        List<net.sf.jsqlparser.statement.select.Join> joins = plain.getJoins();
        List<net.sf.jsqlparser.schema.Table> tables = new ArrayList<>();
        if (!(plain.getFromItem() instanceof net.sf.jsqlparser.schema.Table)) {
            return List.of();
        }
        tables.add((net.sf.jsqlparser.schema.Table) plain.getFromItem());

        List<net.sf.jsqlparser.expression.Expression> joinConditions = new ArrayList<>();
        for (net.sf.jsqlparser.statement.select.Join join : joins) {
            if (!join.isInnerJoin() || join.isNatural() || join.isCross()
                || join.isLeft() || join.isRight() || join.isFull()
                || join.isSemi() || join.isApply()) {
                return List.of();
            }
            if (!(join.getRightItem() instanceof net.sf.jsqlparser.schema.Table)) {
                return List.of();
            }
            if (join.getUsingColumns() != null && !join.getUsingColumns().isEmpty()) {
                return List.of();
            }
            if (join.getOnExpressions() != null && !join.getOnExpressions().isEmpty()) {
                joinConditions.addAll(join.getOnExpressions());
            }
            tables.add((net.sf.jsqlparser.schema.Table) join.getRightItem());
        }

        net.sf.jsqlparser.expression.Expression combinedWhere = plain.getWhere();
        for (net.sf.jsqlparser.expression.Expression cond : joinConditions) {
            if (cond == null) continue;
            if (combinedWhere == null) {
                combinedWhere = cond;
            } else {
                combinedWhere = new net.sf.jsqlparser.expression.operators.conditional.AndExpression(
                    combinedWhere,
                    cond
                );
            }
        }

        String combinedWhereSql = combinedWhere != null ? combinedWhere.toString() : null;
        Map<String, Long> rowCounts = buildRowCountMap(schemaMetadata);
        List<List<net.sf.jsqlparser.schema.Table>> orders =
            generateJoinOrders(tables, rowCounts, maxCandidates);

        boolean useStraightJoin = dbType != null && dbType.equalsIgnoreCase("mysql");
        List<CandidateSpec> candidates = new ArrayList<>();
        for (List<net.sf.jsqlparser.schema.Table> order : orders) {
            String orderSignature = orderSignature(order);
            String candidateId = "JOIN_ORDER_" + QueryNormalizer.generateMD5Hash(orderSignature).substring(0, 8);
            String candidateSql = buildJoinOrderSql(sanitized, order, combinedWhereSql, useStraightJoin);
            if (candidateSql != null && !candidateSql.isBlank()) {
                candidates.add(new CandidateSpec(candidateId, candidateSql));
            }
        }
        return candidates;
    }

    private Map<String, Long> buildRowCountMap(SchemaMetadata schemaMetadata) {
        if (schemaMetadata == null || schemaMetadata.getTables() == null) {
            return Collections.emptyMap();
        }
        Map<String, Long> rowCounts = new HashMap<>();
        for (TableMetadata table : schemaMetadata.getTables()) {
            if (table.getName() == null) {
                continue;
            }
            String key = table.getName().toLowerCase(Locale.ROOT);
            if (table.getSchema() != null && !table.getSchema().isBlank()) {
                String qualified = (table.getSchema() + "." + table.getName()).toLowerCase(Locale.ROOT);
                rowCounts.putIfAbsent(qualified, table.getRowCount());
            }
            rowCounts.putIfAbsent(key, table.getRowCount());
        }
        return rowCounts;
    }

    private List<List<net.sf.jsqlparser.schema.Table>> generateJoinOrders(
        List<net.sf.jsqlparser.schema.Table> tables,
        Map<String, Long> rowCounts,
        int maxCandidates
    ) {
        List<List<net.sf.jsqlparser.schema.Table>> candidates = new ArrayList<>();
        candidates.add(copyTables(tables));

        if (!rowCounts.isEmpty()) {
            List<net.sf.jsqlparser.schema.Table> asc = copyTables(tables);
            asc.sort(Comparator.comparingLong(t -> rowCounts.getOrDefault(tableKey(t), Long.MAX_VALUE)));
            candidates.add(asc);

            List<net.sf.jsqlparser.schema.Table> desc = copyTables(tables);
            desc.sort(Comparator.comparingLong((net.sf.jsqlparser.schema.Table t) ->
                rowCounts.getOrDefault(tableKey(t), Long.MIN_VALUE)).reversed());
            candidates.add(desc);
        }

        if (tables.size() <= 5) {
            List<List<net.sf.jsqlparser.schema.Table>> perms = new ArrayList<>();
            permuteTables(copyTables(tables), 0, perms);
            if (!rowCounts.isEmpty()) {
                perms.sort(Comparator.comparingLong(order -> scoreOrder(order, rowCounts)));
            }
            candidates.addAll(perms);
        } else {
            List<net.sf.jsqlparser.schema.Table> reversed = copyTables(tables);
            Collections.reverse(reversed);
            candidates.add(reversed);
        }

        List<List<net.sf.jsqlparser.schema.Table>> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (List<net.sf.jsqlparser.schema.Table> order : candidates) {
            String signature = orderSignature(order);
            if (seen.add(signature)) {
                result.add(order);
                if (result.size() >= maxCandidates) {
                    break;
                }
            }
        }
        return result;
    }

    private void permuteTables(
        List<net.sf.jsqlparser.schema.Table> tables,
        int index,
        List<List<net.sf.jsqlparser.schema.Table>> results
    ) {
        if (index >= tables.size() - 1) {
            results.add(copyTables(tables));
            return;
        }
        for (int i = index; i < tables.size(); i++) {
            Collections.swap(tables, index, i);
            permuteTables(tables, index + 1, results);
            Collections.swap(tables, index, i);
        }
    }

    private long scoreOrder(List<net.sf.jsqlparser.schema.Table> order, Map<String, Long> rowCounts) {
        long score = 0;
        for (int i = 0; i < order.size(); i++) {
            long rows = rowCounts.getOrDefault(tableKey(order.get(i)), Long.MAX_VALUE / 10);
            score += rows * (i + 1);
        }
        return score;
    }

    private List<net.sf.jsqlparser.schema.Table> copyTables(List<net.sf.jsqlparser.schema.Table> tables) {
        List<net.sf.jsqlparser.schema.Table> copy = new ArrayList<>();
        for (net.sf.jsqlparser.schema.Table table : tables) {
            copy.add(cloneTable(table));
        }
        return copy;
    }

    private net.sf.jsqlparser.schema.Table cloneTable(net.sf.jsqlparser.schema.Table table) {
        net.sf.jsqlparser.schema.Table clone = new net.sf.jsqlparser.schema.Table();
        clone.setName(table.getName());
        clone.setSchemaName(table.getSchemaName());
        clone.setDatabase(table.getDatabase());
        clone.setAlias(table.getAlias());
        return clone;
    }

    private String tableKey(net.sf.jsqlparser.schema.Table table) {
        if (table.getSchemaName() != null && !table.getSchemaName().isBlank()) {
            return (table.getSchemaName() + "." + table.getName()).toLowerCase(Locale.ROOT);
        }
        return table.getName() != null ? table.getName().toLowerCase(Locale.ROOT) : "";
    }

    private String orderSignature(List<net.sf.jsqlparser.schema.Table> order) {
        List<String> parts = new ArrayList<>();
        for (net.sf.jsqlparser.schema.Table table : order) {
            parts.add(tableKey(table));
        }
        return String.join("|", parts);
    }

    private String buildJoinOrderSql(
        String baseSql,
        List<net.sf.jsqlparser.schema.Table> order,
        String combinedWhereSql,
        boolean useStraightJoin
    ) {
        try {
            net.sf.jsqlparser.statement.Statement stmt =
                net.sf.jsqlparser.parser.CCJSqlParserUtil.parse(baseSql);
            if (!(stmt instanceof net.sf.jsqlparser.statement.select.Select)) {
                return null;
            }
            net.sf.jsqlparser.statement.select.Select select =
                (net.sf.jsqlparser.statement.select.Select) stmt;
            if (!(select.getSelectBody() instanceof net.sf.jsqlparser.statement.select.PlainSelect)) {
                return null;
            }
            net.sf.jsqlparser.statement.select.PlainSelect plain =
                (net.sf.jsqlparser.statement.select.PlainSelect) select.getSelectBody();

            plain.setFromItem(cloneTable(order.get(0)));
            List<net.sf.jsqlparser.statement.select.Join> newJoins = new ArrayList<>();
            for (int i = 1; i < order.size(); i++) {
                net.sf.jsqlparser.statement.select.Join join = new net.sf.jsqlparser.statement.select.Join();
                join.setRightItem(cloneTable(order.get(i)));
                if (useStraightJoin) {
                    join.setStraight(true);
                    join.setInner(true);
                } else {
                    join.setCross(true);
                }
                join.setOnExpressions(Collections.emptyList());
                newJoins.add(join);
            }
            plain.setJoins(newJoins);

            if (combinedWhereSql != null && !combinedWhereSql.isBlank()) {
                net.sf.jsqlparser.expression.Expression whereExpr =
                    net.sf.jsqlparser.parser.CCJSqlParserUtil.parseCondExpression(combinedWhereSql);
                plain.setWhere(whereExpr);
            }

            return select.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private void upsertCandidateWithOptd(
        String connectionId,
        String dbType,
        String queryFingerprint,
        String candidateId,
        String candidateSql
    ) {
        upsertCandidateWithOptd(connectionId, dbType, queryFingerprint, candidateId, candidateSql, false);
    }

    private void upsertCandidateWithOptd(
        String connectionId,
        String dbType,
        String queryFingerprint,
        String candidateId,
        String candidateSql,
        boolean forceReplaceSql
    ) {
        var optdResponse = optdOptimizationService.optimizeQuery(connectionId, dbType, candidateSql);
        String planSignature = optdResponse.map(r -> r.getPlanSignature()).orElse(null);
        String planText = optdResponse.map(r -> r.getPlanText()).orElse(null);
        Double estimatedCost = optdResponse.map(r -> r.getEstimatedCost()).orElse(null);
        Double estimatedRows = optdResponse.map(r -> r.getEstimatedRows()).orElse(null);
        String warnings = optdResponse
            .map(r -> r.getWarnings() != null ? String.join("\n", r.getWarnings()) : null)
            .orElse(null);

        candidateService.upsertCandidate(
            connectionId,
            queryFingerprint,
            candidateId,
            candidateSql,
            planSignature,
            planText,
            estimatedCost,
            estimatedRows,
            warnings,
            forceReplaceSql
        );
    }

    private String applySampleLiterals(String optimizedQuery, String sampleQuery) {
        if (optimizedQuery == null || optimizedQuery.isBlank() || sampleQuery == null || sampleQuery.isBlank()) {
            return optimizedQuery;
        }
        String sanitizedSample = sanitizeQueryText(sampleQuery);
        return com.dbaagent.util.SqlLiteralSubstitution.substituteLiterals(optimizedQuery, sanitizedSample);
    }

    // ==================== Caching Methods ====================

    /**
     * Get cached optimization result for a query fingerprint.
     * Returns null if not cached.
     */
    @Transactional
    public OptimizationResult getCachedOptimization(String connectionId, String queryFingerprint) {
        if (connectionId == null || queryFingerprint == null || queryFingerprint.isBlank()) {
            return null;
        }

        try {
            Optional<QueryOptimizationCache> cached = cacheRepository.findByConnectionIdAndQueryFingerprint(
                connectionId, queryFingerprint);

            // Fallback: try MD5-based fingerprint if SHA-256[:16] lookup missed
            if (cached.isEmpty()) {
                cached = findCacheByAlternativeFingerprint(connectionId, queryFingerprint);
            }

            if (cached.isPresent()) {
                QueryOptimizationCache cache = cached.get();

                // Stale-engine guard: a rewrite produced by an older engine
                // version must not be served — regenerate it with the current
                // engine instead of returning a worse rewrite forever.
                Integer ver = cache.getEngineVersion();
                if (ver == null || ver < REWRITE_ENGINE_VERSION) {
                    log.info("Ignoring stale cached optimization for query {} (engine v{} < v{}) — regenerating",
                        queryFingerprint, ver, REWRITE_ENGINE_VERSION);
                    return null;
                }

                cache.recordAccess();
                cacheRepository.save(cache);

                log.debug("Retrieved cached optimization for query {} (accessed {} times)",
                    queryFingerprint, cache.getAccessCount());

                return convertCacheToResult(cache);
            }
        } catch (Exception e) {
            log.warn("Error retrieving cached optimization: {}", e.getMessage());
        }

        return null;
    }

    /**
     * Try to find a cache entry using an alternative fingerprint hash.
     * The fingerprint table uses SHA-256[:16] while the slow query analysis uses MD5.
     * This bridges the gap when cache was stored under one hash but looked up by the other.
     */
    private Optional<QueryOptimizationCache> findCacheByAlternativeFingerprint(String connectionId, String queryFingerprint) {
        Optional<QueryFingerprint> fp = fingerprintRepository.findByConnectionIdAndFingerprint(connectionId, queryFingerprint);
        if (fp.isEmpty()) {
            return Optional.empty();
        }

        // Try multiple hash sources: normalizedQuery may be truncated, so also try sampleQuery
        String[] candidates = {
            fp.get().getNormalizedQuery(),
            fp.get().getSampleQuery() != null ? QueryNormalizer.normalize(fp.get().getSampleQuery()) : null
        };

        for (String source : candidates) {
            if (source == null || source.isBlank()) continue;
            String md5Fingerprint = QueryNormalizer.generateMD5Hash(source);
            if (md5Fingerprint.equals(queryFingerprint)) continue;
            Optional<QueryOptimizationCache> fallback =
                cacheRepository.findByConnectionIdAndQueryFingerprint(connectionId, md5Fingerprint);
            if (fallback.isPresent()) {
                log.debug("Found cached optimization via MD5 fallback: {} -> {}", queryFingerprint, md5Fingerprint);
                return fallback;
            }
        }
        return Optional.empty();
    }

    /**
     * Get cached optimizations for multiple query fingerprints.
     * Returns a map of fingerprint -> optimization result.
     */
    @Transactional(readOnly = true)
    public Map<String, OptimizationResult> getCachedOptimizations(String connectionId, List<String> fingerprints) {
        Map<String, OptimizationResult> results = new HashMap<>();

        if (connectionId == null || fingerprints == null || fingerprints.isEmpty()) {
            return results;
        }

        try {
            List<QueryOptimizationCache> cached = cacheRepository.findByConnectionIdAndFingerprintsIn(
                connectionId, fingerprints);

            for (QueryOptimizationCache cache : cached) {
                // Skip rewrites produced by an older engine version (stale).
                if (cache.getEngineVersion() == null || cache.getEngineVersion() < REWRITE_ENGINE_VERSION) {
                    continue;
                }
                OptimizationResult result = convertCacheToResult(cache);
                if (result != null) {
                    results.put(cache.getQueryFingerprint(), result);
                }
            }

            // Fallback: for fingerprints not found, try MD5-based alternative hashes
            List<String> missing = fingerprints.stream()
                .filter(fp -> !results.containsKey(fp))
                .toList();
            if (!missing.isEmpty()) {
                List<QueryFingerprint> fps = fingerprintRepository.findByConnectionIdAndFingerprintIn(
                    connectionId, missing);
                List<String> altHashes = new ArrayList<>();
                Map<String, String> altToOriginal = new HashMap<>();
                for (QueryFingerprint fp : fps) {
                    // Try MD5 of normalizedQuery first, then MD5 of normalized sampleQuery
                    // (for long queries where normalizedQuery is truncated to 2000 chars)
                    String[] candidateSources = {
                        fp.getNormalizedQuery(),
                        fp.getSampleQuery() != null
                            ? QueryNormalizer.normalize(fp.getSampleQuery()) : null
                    };
                    for (String source : candidateSources) {
                        if (source == null || source.isBlank()) continue;
                        String md5 = QueryNormalizer.generateMD5Hash(source);
                        if (!md5.equals(fp.getFingerprint()) && !results.containsKey(md5)
                                && !altToOriginal.containsKey(md5)) {
                            altHashes.add(md5);
                            altToOriginal.put(md5, fp.getFingerprint());
                        }
                    }
                }
                if (!altHashes.isEmpty()) {
                    List<QueryOptimizationCache> fallbacks = cacheRepository.findByConnectionIdAndFingerprintsIn(
                        connectionId, altHashes);
                    for (QueryOptimizationCache cache : fallbacks) {
                        OptimizationResult result = convertCacheToResult(cache);
                        String originalFp = altToOriginal.get(cache.getQueryFingerprint());
                        if (result != null && originalFp != null) {
                            results.put(originalFp, result);
                        }
                    }
                    if (!fallbacks.isEmpty()) {
                        log.debug("Found {} cached optimizations via MD5 fallback", fallbacks.size());
                    }
                }
            }

            log.debug("Retrieved {} cached optimizations for {} fingerprints",
                results.size(), fingerprints.size());
        } catch (Exception e) {
            log.warn("Error retrieving cached optimizations: {}", e.getMessage());
        }

        return results;
    }

    /**
     * Save optimization result to cache.
     */
    @Transactional
    public void cacheOptimization(String connectionId, String queryFingerprint, OptimizationResult result) {
        if (connectionId == null || queryFingerprint == null || queryFingerprint.isBlank() || result == null) {
            return;
        }

        try {
            // Check if already cached - update if so
            Optional<QueryOptimizationCache> existing = cacheRepository.findByConnectionIdAndQueryFingerprint(
                connectionId, queryFingerprint);

            QueryOptimizationCache cache;
            if (existing.isPresent()) {
                cache = existing.get();
                log.debug("Updating existing cached optimization for query {}", queryFingerprint);
            } else {
                cache = QueryOptimizationCache.builder()
                    .connectionId(connectionId)
                    .queryFingerprint(queryFingerprint)
                    .createdAt(LocalDateTime.now())
                    .accessCount(0)
                    .build();
                log.debug("Creating new cached optimization for query {}", queryFingerprint);
            }

            // Update cache fields
            cache.setOriginalQuery(result.getOriginalQuery());
            cache.setOptimizedQuery(result.getOptimizedQuery());
            cache.setExplanation(result.getExplanation());
            cache.setEstimatedImprovement(result.getEstimatedImprovement());
            cache.setEngineVersion(REWRITE_ENGINE_VERSION);
            cache.setUpdatedAt(LocalDateTime.now());

            // Serialize suggestions to JSON
            if (result.getSuggestions() != null) {
                cache.setSuggestionsJson(objectMapper.writeValueAsString(result.getSuggestions()));
            }

            // Serialize index recommendations to JSON
            if (result.getIndexRecommendations() != null) {
                cache.setIndexRecommendationsJson(objectMapper.writeValueAsString(result.getIndexRecommendations()));
            }

            // Serialize EXPLAIN analysis to JSON
            if (result.getExplainAnalysis() != null) {
                cache.setExplainAnalysisJson(objectMapper.writeValueAsString(result.getExplainAnalysis()));
            }

            cacheRepository.save(cache);
            log.info("Cached optimization result for query {} in connection {}", queryFingerprint, connectionId);

        } catch (JsonProcessingException e) {
            log.warn("Error serializing optimization result for caching: {}", e.getMessage());
        } catch (Exception e) {
            log.warn("Error caching optimization result: {}", e.getMessage());
        }
    }

    /**
     * Delete cached optimization for a specific query.
     */
    @Transactional
    public void deleteCachedOptimization(String connectionId, String queryFingerprint) {
        try {
            Optional<QueryOptimizationCache> cached = cacheRepository.findByConnectionIdAndQueryFingerprint(
                connectionId, queryFingerprint);
            cached.ifPresent(cacheRepository::delete);
            log.debug("Deleted cached optimization for query {}", queryFingerprint);
        } catch (Exception e) {
            log.warn("Error deleting cached optimization: {}", e.getMessage());
        }
    }

    /**
     * Delete all cached optimizations for a connection.
     * Also evicts the explainAnalysis and schemaMetadata Redis caches so that
     * the next optimization request gets fresh EXPLAIN plans and schema data.
     */
    @Transactional
    public void clearConnectionCache(String connectionId) {
        // 1. Clear DB-backed optimization cache
        try {
            cacheRepository.deleteByConnectionId(connectionId);
            log.info("Cleared DB optimization cache for connection {}", connectionId);
        } catch (Exception e) {
            log.warn("Error clearing DB optimization cache: {}", e.getMessage());
        }

        // 2. Clear Redis explainAnalysis cache (all entries — keys are per-query so evict all)
        try {
            var explainCache = cacheManager.getCache("explainAnalysis");
            if (explainCache != null) {
                explainCache.clear();
                log.info("Cleared explainAnalysis Redis cache");
            }
        } catch (Exception e) {
            log.warn("Error clearing explainAnalysis cache: {}", e.getMessage());
        }

        // 3. Clear Redis schemaMetadata cache (stale schema can cause bad rewrites)
        try {
            var schemaCache = cacheManager.getCache("schemaMetadata");
            if (schemaCache != null) {
                schemaCache.clear();
                log.info("Cleared schemaMetadata Redis cache");
            }
        } catch (Exception e) {
            log.warn("Error clearing schemaMetadata cache: {}", e.getMessage());
        }
    }

    /**
     * Get cache statistics for a connection.
     */
    public Map<String, Object> getCacheStats(String connectionId) {
        Map<String, Object> stats = new HashMap<>();
        try {
            long count = cacheRepository.countByConnectionId(connectionId);
            stats.put("cachedCount", count);
            stats.put("connectionId", connectionId);
        } catch (Exception e) {
            log.warn("Error getting cache stats: {}", e.getMessage());
            stats.put("error", e.getMessage());
        }
        return stats;
    }

    /**
     * Convert cache entity to optimization result.
     */
    private OptimizationResult convertCacheToResult(QueryOptimizationCache cache) {
        // Compute canonical fingerprint before try block so it's accessible in catch.
        String cachedFp = cache.getQueryFingerprint();
        String canonical = cachedFp;
        if (cachedFp != null && cachedFp.length() > 16 && cache.getOriginalQuery() != null) {
            try {
                String normalized = QueryNormalizer.normalize(cache.getOriginalQuery());
                canonical = computeCanonicalFingerprint(normalized);
            } catch (Exception ignored) { /* keep cachedFp as fallback */ }
        }
        try {
            OptimizationResult.OptimizationResultBuilder builder = OptimizationResult.builder()
                .queryId(cachedFp)
                .canonicalFingerprint(canonical)
                .originalQuery(cache.getOriginalQuery())
                .optimizedQuery(cache.getOptimizedQuery())
                .explanation(cache.getExplanation())
                .estimatedImprovement(cache.getEstimatedImprovement())
                .generatedAt(cache.getUpdatedAt())
                .cached(true);  // Mark as cached result

            // Deserialize suggestions from JSON
            if (cache.getSuggestionsJson() != null && !cache.getSuggestionsJson().isBlank()) {
                try {
                    List<OptimizationSuggestion> suggestions = objectMapper.readValue(
                        cache.getSuggestionsJson(),
                        new TypeReference<List<OptimizationSuggestion>>() {}
                    );
                    builder.suggestions(suggestions);
                } catch (JsonProcessingException e) {
                    log.warn("Error deserializing cached suggestions: {}", e.getMessage());
                    builder.suggestions(Collections.emptyList());
                }
            } else {
                builder.suggestions(Collections.emptyList());
            }

            // Deserialize index recommendations from JSON
            if (cache.getIndexRecommendationsJson() != null && !cache.getIndexRecommendationsJson().isBlank()) {
                try {
                    List<String> indexRecs = objectMapper.readValue(
                        cache.getIndexRecommendationsJson(),
                        new TypeReference<List<String>>() {}
                    );
                    builder.indexRecommendations(indexRecs);
                } catch (JsonProcessingException e) {
                    log.warn("Error deserializing cached index recommendations: {}", e.getMessage());
                    builder.indexRecommendations(Collections.emptyList());
                }
            } else {
                builder.indexRecommendations(Collections.emptyList());
            }

            // Deserialize EXPLAIN analysis from JSON
            if (cache.getExplainAnalysisJson() != null && !cache.getExplainAnalysisJson().isBlank()) {
                try {
                    ExplainPlanAnalysis explainAnalysis = objectMapper.readValue(
                        cache.getExplainAnalysisJson(),
                        ExplainPlanAnalysis.class
                    );
                    builder.explainAnalysis(explainAnalysis);
                } catch (JsonProcessingException e) {
                    log.warn("Error deserializing cached EXPLAIN analysis: {}", e.getMessage());
                }
            }

            return builder.build();

        } catch (Exception e) {
            log.warn("Error deserializing cached optimization: {}", e.getMessage());
            return OptimizationResult.builder()
                .queryId(cache.getQueryFingerprint())
                .canonicalFingerprint(canonical)
                .originalQuery(cache.getOriginalQuery())
                .optimizedQuery(cache.getOptimizedQuery())
                .explanation(cache.getExplanation())
                .estimatedImprovement(cache.getEstimatedImprovement())
                .generatedAt(cache.getUpdatedAt())
                .cached(true)
                .suggestions(Collections.emptyList())
                .indexRecommendations(Collections.emptyList())
                .build();
        }
    }
}
