package com.dbaagent.service;

import com.dbaagent.model.*;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.repository.QueryLineageRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for analyzing slow queries from database statistics
 */
@Service
@Slf4j
public class SlowQueryService {

    private final CredentialService credentialService;
    private final ExplainPlanService explainPlanService;
    private final ChatClient chatClient;
    private final SlowQueryCollectorService slowQueryCollector;
    private final DatabaseProviderRegistry providerRegistry;
    private final QueryLineageRepository queryLineageRepository;

    @Autowired
    public SlowQueryService(
            CredentialService credentialService,
            ExplainPlanService explainPlanService,
            ChatClient.Builder chatClientBuilder,
            SlowQueryCollectorService slowQueryCollector,
            DatabaseProviderRegistry providerRegistry,
            QueryLineageRepository queryLineageRepository) {
        this.credentialService = credentialService;
        this.explainPlanService = explainPlanService;
        this.chatClient = chatClientBuilder.build();
        this.slowQueryCollector = slowQueryCollector;
        this.providerRegistry = providerRegistry;
        this.queryLineageRepository = queryLineageRepository;
        log.info("SlowQueryService initialized with Spring AI ChatClient");
    }

    /**
     * Analyze slow queries for a connection (full analysis incl. EXPLAIN + AI summary).
     */
    public SlowQueryAnalysis analyzeSlowQueries(
        String connectionId,
        SlowQueryAnalysis.TimeRange timeRange,
        Double thresholdMs,
        Integer limit
    ) {
        return analyzeSlowQueries(connectionId, timeRange, thresholdMs, limit, true);
    }

    /**
     * Analyze slow queries for a connection.
     *
     * @param enrichWithExplain when {@code false}, skips the expensive per-query
     *     EXPLAIN enrichment and the AI summary. Used by the on-demand
     *     "Analyze now" endpoint to stay under the client's HTTP timeout — a full
     *     enrichment run executes one EXPLAIN per query (~5 s each on a remote
     *     MySQL/Postgres) plus one LLM summary call, which on 100 queries adds
     *     ~8–10 min of latency, well past the 300 s frontend timeout. The
     *     "Tracked Queries" panel only needs the relational {@code slow_query_run}
     *     rows, which {@link SlowQueryDailyAnalysisService} persists from the
     *     collected counters alone — EXPLAIN-derived suggestions / index
     *     recommendations live in the JSON blob on {@code slow_query_history} and
     *     are populated by the nightly daily job (01:30) where time is abundant.
     */
    public SlowQueryAnalysis analyzeSlowQueries(
        String connectionId,
        SlowQueryAnalysis.TimeRange timeRange,
        Double thresholdMs,
        Integer limit,
        boolean enrichWithExplain
    ) {
        try {
            // Get connection with credentials
            ConnectionRequest connection = credentialService.getDecryptedConnection(connectionId);
            String dbType = providerRegistry.getCanonicalName(connection.getDbType());

            SlowQueryAnalysis analysis = SlowQueryAnalysis.builder()
                .connectionId(connectionId)
                .analysisDate(LocalDateTime.now())
                .timeRange(timeRange != null ? timeRange : SlowQueryAnalysis.TimeRange.LAST_24_HOURS)
                .slowQueryThresholdMs(thresholdMs != null ? thresholdMs : 100.0)
                .build();

            // Analyze slow queries using provider-agnostic collector
            collectAndAnalyzeSlowQueries(
                connectionId, connection, dbType, analysis,
                limit != null ? limit : 10, enrichWithExplain);

            // Some collectors return queries whose `queryText` was already
            // truncated by the database server (pg_stat_statements'
            // track_activity_query_size, performance_schema_max_sql_text_length).
            // If we've previously ingested slow-log files for this connection,
            // vault DB already has the full text — swap it in so EXPLAIN /
            // AI optimization can actually work.
            recoverTruncatedQueriesFromLineage(connectionId, analysis.getTopSlowQueries());

            // Calculate overall health
            analysis.setOverallHealth(analysis.calculateOverallHealth());

            // Generate recommendations
            generateRecommendations(analysis);

            // AI summary is the second source of multi-minute latency in a full
            // run (one LLM call). Skip it in fast mode for the same reason we
            // skip EXPLAIN — the on-demand path needs to respond well under the
            // 300 s client timeout. The nightly job (01:30) populates it.
            if (enrichWithExplain) {
                generateAISummary(analysis);
            } else {
                analysis.setAiSummary("AI summary generated by the nightly analysis (01:30).");
            }

            log.info("Slow query analysis completed (enrichWithExplain={}): {}",
                enrichWithExplain, analysis.getSummaryStats());
            return analysis;

        } catch (Exception e) {
            log.error("Error analyzing slow queries", e);
            throw new RuntimeException("Failed to analyze slow queries: " + e.getMessage(), e);
        }
    }

    /**
     * Length of the prefix we use to search `query_lineage` for a non-
     * truncated copy of a query that came back truncated from live stats.
     *
     * Reasoning: pg_stat_statements truncates at a byte boundary
     * (default 1024B). The stored truncated text is always a prefix of
     * the original, but the last few bytes may be in the middle of a
     * token (a partial identifier, a half-finished string literal).
     * We trim a small slack window off the end of the truncated text
     * before LIKE'ing — a 256-char prefix is more than enough to be
     * unique within a single connection's history (the chance of two
     * distinct queries sharing the same 256-char opening is effectively
     * zero in practice), and trimming the tail absorbs the token-
     * boundary issue.
     */
    private static final int RECOVERY_PREFIX_LENGTH = 256;

    /**
     * The smallest truncated-text length where prefix-recovery makes
     * sense. Below this, the prefix is too short to be discriminating.
     */
    private static final int RECOVERY_MIN_TRUNCATED_LENGTH = 128;

    /**
     * For each slow query flagged `sourceTruncated=true`, look in
     * `query_lineage` (vault DB) for a previously-ingested log-file row
     * whose `query_text` starts with the same prefix and is strictly
     * longer than the truncated text. If we find one, swap the truncated
     * text out for the full one and set `queryTextRecoveredFromLogs=true`.
     *
     * Best-effort — any failure is logged and the analysis proceeds with
     * the truncated text + the loud warning we already emit.
     */
    void recoverTruncatedQueriesFromLineage(String connectionId, List<SlowQuery> slowQueries) {
        if (connectionId == null || slowQueries == null || slowQueries.isEmpty()) {
            return;
        }
        int recovered = 0;
        int attempted = 0;
        for (SlowQuery sq : slowQueries) {
            if (!Boolean.TRUE.equals(sq.getSourceTruncated())) continue;
            String truncated = sq.getQueryText();
            if (truncated == null || truncated.length() < RECOVERY_MIN_TRUNCATED_LENGTH) continue;
            attempted++;
            try {
                String escapedPrefix = buildLikePrefixForRecovery(truncated);
                QueryLineage match = queryLineageRepository.findLongestByConnectionIdAndQueryTextPrefix(
                    connectionId,
                    escapedPrefix,
                    truncated.length()
                );
                if (match != null && match.getQueryText() != null) {
                    sq.setQueryText(match.getQueryText());
                    // The normalized form should match too — overwrite if
                    // lineage has one so downstream consumers (hashing,
                    // brain context) see consistent text.
                    if (match.getNormalizedQuery() != null && !match.getNormalizedQuery().isBlank()) {
                        sq.setNormalizedQuery(match.getNormalizedQuery());
                    }
                    sq.setQueryTextRecoveredFromLogs(true);
                    recovered++;
                }
            } catch (Exception e) {
                log.debug("Recovery lookup failed for connection {}: {}", connectionId, e.getMessage());
            }
        }
        if (attempted > 0) {
            log.info("Slow query recovery: {}/{} truncated queries recovered from query_lineage for connection {}",
                recovered, attempted, connectionId);
        }
    }

    /**
     * Build a SQL-LIKE-ready prefix for the recovery lookup.
     *
     * Steps:
     *   1. Take the first {@link #RECOVERY_PREFIX_LENGTH} chars, BUT cap
     *      to (truncatedText.length() - 32) to leave slack for the
     *      partial-token tail (pg_stat_statements may cut in the middle
     *      of an identifier or literal).
     *   2. Escape the SQL LIKE wildcards `%`, `_`, and the escape char
     *      `\` itself. The repo query uses `ESCAPE '\\'` so these become
     *      literal characters in the pattern.
     *   3. Append `%` so the LIKE matches anything that starts with this
     *      prefix.
     *
     * Package-private for direct testing.
     */
    static String buildLikePrefixForRecovery(String truncatedText) {
        int slack = 32;
        int prefixLen = Math.min(RECOVERY_PREFIX_LENGTH, Math.max(64, truncatedText.length() - slack));
        String prefix = truncatedText.substring(0, prefixLen);
        // Escape the LIKE wildcards. Note that the order matters: escape
        // the backslash first so we don't double-escape the backslashes
        // we add for % and _.
        String escaped = prefix
            .replace("\\", "\\\\")
            .replace("%", "\\%")
            .replace("_", "\\_");
        return escaped + "%";
    }

    /**
     * Collect and analyze slow queries using the provider-agnostic collector.
     * Supports PostgreSQL (pg_stat_statements) and MySQL (Performance Schema, slow_log).
     */
    private void collectAndAnalyzeSlowQueries(
        String connectionId,
        ConnectionRequest connection,
        String dbType,
        SlowQueryAnalysis analysis,
        int limit,
        boolean enrichWithExplain
    ) {
        try {
            // Use multi-source collector (handles both PostgreSQL and MySQL)
            List<SlowQuery> slowQueries = slowQueryCollector.collectSlowQueries(
                connectionId,
                connection,
                analysis.getSlowQueryThresholdMs(),
                limit,
                24  // Hours back parameter for table/file sources
            );

            // Add queries to analysis
            slowQueries.forEach(analysis::addSlowQuery);

            // Set statistics
            analysis.setTotalSlowQueries((long) slowQueries.size());
            analysis.setTotalQueriesAnalyzed((long) slowQueries.size());

            // Log which sources were used
            if (!slowQueries.isEmpty()) {
                Set<String> sources = slowQueries.stream()
                    .map(SlowQuery::getSource)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet());
                log.info("Collected {} {} slow queries from sources: {}", slowQueries.size(), dbType, sources);
            }

            // Analyze each slow query with EXPLAIN (sequential per-query round
            // trips to the target DB — skipped in fast mode; see
            // analyzeSlowQueries javadoc).
            if (enrichWithExplain) {
                enrichWithExplainAnalysis(connection.getId(), slowQueries);
            }

        } catch (Exception e) {
            log.error("Error analyzing {} slow queries", dbType, e);
            addErrorRecommendations(analysis, dbType, e);
        }
    }

    /**
     * Add database-specific error recommendations based on the exception.
     */
    private void addErrorRecommendations(SlowQueryAnalysis analysis, String dbType, Exception e) {
        String errorMessage = e.getMessage();

        if ("postgres".equals(dbType)) {
            if (errorMessage != null && errorMessage.contains("pg_stat_statements")) {
                analysis.addRecommendation("Install pg_stat_statements extension: CREATE EXTENSION pg_stat_statements");
                analysis.addRecommendation("Add to postgresql.conf: shared_preload_libraries = 'pg_stat_statements'");
            }
            analysis.addRecommendation("Unable to analyze slow queries; check pg_stat_statements access and permissions.");
        } else if ("mysql".equals(dbType)) {
            if (errorMessage != null && errorMessage.contains("performance_schema")) {
                analysis.addRecommendation("Enable Performance Schema in MySQL configuration: performance_schema=ON");
            }
            if (errorMessage != null && errorMessage.contains("slow_log")) {
                analysis.addRecommendation("Enable slow query log: SET GLOBAL slow_query_log = 'ON'");
                analysis.addRecommendation("Set slow query log output: SET GLOBAL log_output = 'TABLE,FILE'");
            }
            analysis.addRecommendation("Unable to analyze slow queries; check privileges for performance_schema and slow_log.");
        } else {
            analysis.addRecommendation("Unable to analyze slow queries for database type: " + dbType);
        }
    }

    /**
     * Enrich slow queries with EXPLAIN plan analysis
     */
    private void enrichWithExplainAnalysis(String connectionId, List<SlowQuery> slowQueries) {
        for (SlowQuery query : slowQueries) {
            try {
                // Skip non-SELECT queries
                if (query.getQueryText() == null || 
                    !query.getQueryText().trim().toUpperCase().startsWith("SELECT")) {
                    continue;
                }

                // Run EXPLAIN analysis
                ExplainPlanAnalysis explainAnalysis = explainPlanService.analyzeQuery(
                    connectionId,
                    query.getQueryText(),
                    false  // Don't use ANALYZE (don't actually execute)
                );

                query.setExplainAnalysis(explainAnalysis);

                // Add EXPLAIN issues as suggestions
                if (explainAnalysis.getIssues() != null) {
                    for (PerformanceIssue issue : explainAnalysis.getIssues()) {
                        query.addSuggestion(issue.getMessage() + ": " + issue.getRecommendation());
                    }
                }

                // Add index recommendations
                if (explainAnalysis.getIndexRecommendations() != null) {
                    explainAnalysis.getIndexRecommendations().forEach(query::addIndexRecommendation);
                }

            } catch (Exception e) {
                log.warn("Failed to run EXPLAIN for query: {}", query.getQueryId(), e);
                // Continue with other queries
            }
        }
    }

    /**
     * Generate general recommendations
     */
    private void generateRecommendations(SlowQueryAnalysis analysis) {
        if (analysis.getTopSlowQueries() == null || analysis.getTopSlowQueries().isEmpty()) {
            return;
        }

        // Collect all unique index recommendations
        Set<String> indexSuggestions = new HashSet<>();
        for (SlowQuery query : analysis.getTopSlowQueries()) {
            if (query.getSuggestedIndexes() != null) {
                query.getSuggestedIndexes().forEach(analysis::addIndexRecommendation);
                for (IndexRecommendation rec : query.getSuggestedIndexes()) {
                    if (rec.getSuggestedSQL() != null) {
                        indexSuggestions.add(rec.getSuggestedSQL());
                    }
                }
            }
        }

        // Check for common patterns
        long selectStarCount = analysis.getTopSlowQueries().stream()
            .filter(q -> q.getQueryText() != null && q.getQueryText().toUpperCase().contains("SELECT *"))
            .count();

        if (selectStarCount > 3) {
            analysis.addRecommendation("Avoid SELECT * in queries. Specify only needed columns to reduce data transfer.");
        }

        long poorEfficiencyCount = analysis.getTopSlowQueries().stream()
            .filter(SlowQuery::hasPoorEfficiency)
            .count();

        if (poorEfficiencyCount > 2) {
            analysis.addRecommendation("Multiple queries have poor efficiency (examining many more rows than returned). Review WHERE clauses and add indexes.");
        }

        // Check cache hit ratio for PostgreSQL
        OptionalDouble avgCacheHitRatio = analysis.getTopSlowQueries().stream()
            .filter(q -> q.getCacheHitRatio() != null)
            .mapToDouble(SlowQuery::getCacheHitRatio)
            .average();

        if (avgCacheHitRatio.isPresent() && avgCacheHitRatio.getAsDouble() < 90.0) {
            analysis.addRecommendation(String.format(
                "Low buffer cache hit ratio (%.1f%%). Consider increasing shared_buffers or effective_cache_size.",
                avgCacheHitRatio.getAsDouble()
            ));
        }
    }

    /**
     * Generate AI summary of slow query analysis
     */
    private void generateAISummary(SlowQueryAnalysis analysis) {
        try {
            StringBuilder prompt = new StringBuilder();
            prompt.append("Analyze this slow query report:\n\n");
            prompt.append(String.format("Database Health: %s\n", analysis.getOverallHealth()));
            prompt.append(String.format("Total Slow Queries: %d\n", analysis.getTotalSlowQueries()));
            prompt.append(String.format("Threshold: %.2fms\n\n", analysis.getSlowQueryThresholdMs()));

            prompt.append("Top 5 Slowest Queries:\n");
            int count = 0;
            for (SlowQuery query : analysis.getTopSlowQueries()) {
                if (count++ >= 5) break;
                prompt.append(String.format(
                    "%d. [%s] Avg: %.2fms, Calls: %,d, Total: %.2fs\n   Query: %s\n",
                    count,
                    query.getSeverity(),
                    query.getAvgExecutionTimeMs(),
                    query.getCallCount(),
                    query.getTotalExecutionTimeMs() / 1000.0,
                    truncateQuery(query.getQueryText(), 100)
                ));
            }

            prompt.append("\nProvide:\n");
            prompt.append("1. Brief summary of the performance situation\n");
            prompt.append("2. Top 3 priorities to address\n");
            prompt.append("3. Expected impact if optimizations are applied\n");
            prompt.append("4. Any patterns or common issues detected\n");

            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(
                "You are a database performance expert. Analyze slow query reports and provide actionable insights."));
            messages.add(new UserMessage(prompt.toString()));

            String summary = chatClient.prompt()
                .messages(messages)
                .call()
                .content();

            analysis.setAiSummary(summary);

        } catch (Exception e) {
            log.warn("Failed to generate AI summary", e);
            analysis.setAiSummary("AI summary unavailable");
        }
    }


    /**
     * Truncate query text for display
     */
    private String truncateQuery(String query, int maxLength) {
        if (query == null) {
            return "";
        }
        if (query.length() <= maxLength) {
            return query;
        }
        return query.substring(0, maxLength) + "...";
    }
}
