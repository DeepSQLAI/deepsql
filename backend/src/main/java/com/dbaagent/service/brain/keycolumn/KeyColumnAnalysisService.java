package com.dbaagent.service.brain.keycolumn;

import com.dbaagent.util.PatternUtil;
import com.dbaagent.repository.brain.BrainRuleRepository;
import com.dbaagent.model.brain.BrainRule;
import com.dbaagent.dto.*;
import com.dbaagent.model.*;
import com.dbaagent.repository.*;
import com.dbaagent.service.CredentialService;
import com.dbaagent.service.EnhancedSqlParserService;
import com.dbaagent.service.QueryExecutorService;
import com.dbaagent.service.SchemaScannerService;
import com.dbaagent.service.SkewAnalysisService;
import com.dbaagent.service.SlowQueryHistoryService;
import com.dbaagent.service.SqlUsageService;
import com.dbaagent.service.brain.analysis.ColumnProfilingService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Service for analyzing key columns based on query usage patterns.
 * Identifies important columns and detects anti-patterns.
 */
@Service
@Slf4j
public class KeyColumnAnalysisService {

    private final EnhancedSqlParserService sqlParserService;
    private final KeyColumnAnalysisRepository keyColumnAnalysisRepository;
    private final ColumnAntiPatternRepository antiPatternRepository;
    private final SlowQueryHistoryRepository slowQueryHistoryRepository;
    private final SlowQueryHistoryService slowQueryHistoryService;
    private final QueryLineageRepository queryLineageRepository;
    private final QueryPerformanceHistoryRepository queryPerformanceHistoryRepository;
    private final CompositeIndexRecommendationRepository compositeIndexRecommendationRepository;
    private final ColumnProfileRepository columnProfileRepository;
    private final BrainRuleRepository brainRuleRepository;
    private final QueryExecutorService queryExecutorService;
    private final SkewAnalysisService skewAnalysisService;
    private final CredentialService credentialService;
    private final ColumnDisambiguationRepository columnDisambiguationRepository;
    private final SchemaScannerService schemaScannerService;
    private final SqlUsageService sqlUsageService;
    private final ColumnValueCollectionService columnValueCollectionService;
    private final ColumnProfilingService columnProfilingService;
    private final TransactionTemplate transactionTemplate;

    public KeyColumnAnalysisService(
        EnhancedSqlParserService sqlParserService,
        KeyColumnAnalysisRepository keyColumnAnalysisRepository,
        ColumnAntiPatternRepository antiPatternRepository,
        SlowQueryHistoryRepository slowQueryHistoryRepository,
        SlowQueryHistoryService slowQueryHistoryService,
        QueryLineageRepository queryLineageRepository,
        QueryPerformanceHistoryRepository queryPerformanceHistoryRepository,
        CompositeIndexRecommendationRepository compositeIndexRecommendationRepository,
        ColumnProfileRepository columnProfileRepository,
        BrainRuleRepository brainRuleRepository,
        QueryExecutorService queryExecutorService,
        SkewAnalysisService skewAnalysisService,
        CredentialService credentialService,
        ColumnDisambiguationRepository columnDisambiguationRepository,
        SchemaScannerService schemaScannerService,
        SqlUsageService sqlUsageService,
        ColumnValueCollectionService columnValueCollectionService,
        ColumnProfilingService columnProfilingService,
        PlatformTransactionManager transactionManager
    ) {
        this.sqlParserService = sqlParserService;
        this.keyColumnAnalysisRepository = keyColumnAnalysisRepository;
        this.antiPatternRepository = antiPatternRepository;
        this.slowQueryHistoryRepository = slowQueryHistoryRepository;
        this.slowQueryHistoryService = slowQueryHistoryService;
        this.queryLineageRepository = queryLineageRepository;
        this.queryPerformanceHistoryRepository = queryPerformanceHistoryRepository;
        this.compositeIndexRecommendationRepository = compositeIndexRecommendationRepository;
        this.columnProfileRepository = columnProfileRepository;
        this.brainRuleRepository = brainRuleRepository;
        this.queryExecutorService = queryExecutorService;
        this.skewAnalysisService = skewAnalysisService;
        this.credentialService = credentialService;
        this.columnDisambiguationRepository = columnDisambiguationRepository;
        this.schemaScannerService = schemaScannerService;
        this.sqlUsageService = sqlUsageService;
        this.columnValueCollectionService = columnValueCollectionService;
        this.columnProfilingService = columnProfilingService;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
    }

    @Value("${brain.key-columns.weight.join:3}")
    private int joinWeight;

    @Value("${brain.key-columns.weight.where:2}")
    private int whereWeight;

    @Value("${brain.key-columns.weight.group-by:2}")
    private int groupByWeight;

    @Value("${brain.key-columns.weight.order-by:1}")
    private int orderByWeight;

    @Value("${brain.key-columns.lookback-days:90}")
    private int lookbackDays;

    // Rate limiting configuration to prevent CPU spikes on large databases
    @Value("${brain.key-columns.max-queries-per-source:1000}")
    private int maxQueriesPerSource;

    @Value("${brain.key-columns.batch-size:100}")
    private int batchSize;

    @Value("${brain.key-columns.delay-between-batches-ms:50}")
    private int delayBetweenBatchesMs;

    /**
     * Analyze key columns for a connection
     */
    public KeyColumnAnalysisResult analyzeKeyColumns(String connectionId) {
        return analyzeKeyColumns(connectionId, true);
    }

    /**
     * Analyze key columns and optionally trigger downstream column-value learning.
     */
    public KeyColumnAnalysisResult analyzeKeyColumns(String connectionId, boolean triggerColumnValueRefresh) {
        log.info("Starting key column analysis for connection: {}", connectionId);

        // NOW: Perform analysis in main transaction
        KeyColumnAnalysisResult result = performAnalysis(connectionId);

        if (triggerColumnValueRefresh) {
            // AFTER: Trigger column value collection for low-cardinality columns (async)
            // This helps the chat agent generate better SQL filters with exact values
            triggerColumnValueCollection(connectionId);
        }

        return result;
    }

    /**
     * Trigger column value collection asynchronously after key column analysis.
     * Collects distinct values for low-cardinality columns and embeds them
     * in Azure AI Search for better SQL filtering suggestions.
     */
    private void triggerColumnValueCollection(String connectionId) {
        try {
            // Run async to not block the response
            Thread.ofVirtual().start(() -> {
                try {
                    log.info("Starting async column value collection for connection: {}", connectionId);
                    columnValueCollectionService.analyzeColumnValues(connectionId, null);
                    log.info("Column value collection completed for connection: {}", connectionId);
                } catch (Exception e) {
                    log.warn("Failed to collect column values for {}: {}", connectionId, e.getMessage());
                }
            });
        } catch (Exception e) {
            log.warn("Failed to start column value collection: {}", e.getMessage());
        }
    }

    /**
     * Clean up old analysis data in a separate transaction
     */
    @Transactional(propagation = org.springframework.transaction.annotation.Propagation.REQUIRES_NEW)
    public void cleanupOldAnalysisData(String connectionId) {
        log.info("Cleaning up old analysis data for connection: {}", connectionId);
        keyColumnAnalysisRepository.deleteByConnectionId(connectionId);
        antiPatternRepository.deleteByConnectionId(connectionId);
        compositeIndexRecommendationRepository.deleteByConnectionId(connectionId);
        log.info("Old analysis data cleaned up successfully");
    }

    /**
     * Perform the actual analysis
     */
    @Transactional
    private KeyColumnAnalysisResult performAnalysis(String connectionId) {
        log.info("Performing key column analysis for connection: {}", connectionId);

        // Get the user database name to filter out system schemas
        // Catch any exceptions to prevent transaction rollback
        String userDatabase = null;
        try {
            userDatabase = getUserDatabase(connectionId);
            log.info("Filtering analysis to user database: {}", userDatabase);
        } catch (Exception e) {
            log.warn("Could not retrieve database name, will filter system schemas only: {}", e.getMessage());
        }

        // Step 1: Collect and parse queries
        Map<String, ColumnUsageAggregator> aggregators = new HashMap<>();
        LocalDateTime since = LocalDateTime.now().minusDays(lookbackDays);
        int queriesAnalyzed = 0;

        Map<String, String> disambiguationMap = columnDisambiguationRepository.findByConnectionId(connectionId)
            .stream()
            .filter(entry -> entry.getColumnName() != null && entry.getPreferredTable() != null)
            .collect(Collectors.toMap(
                entry -> entry.getColumnName().toLowerCase(Locale.ROOT),
                entry -> entry.getPreferredTable().toLowerCase(Locale.ROOT),
                (a, b) -> a
            ));

        SchemaMetadata schema = null;
        try {
            schema = schemaScannerService.scanSchema(connectionId);
        } catch (Exception e) {
            log.warn("Could not load schema for disambiguation: {}", e.getMessage());
        }
        Map<String, Set<String>> schemaColumnMap = schema != null
            ? buildSchemaColumnMap(schema)
            : Collections.emptyMap();

        // Parse slow queries (fallback when no slow-query lineage exists)
        boolean hasSlowQueryLineage = queryLineageRepository
            .countByConnectionIdAndSourceLike(connectionId, "SLOW_QUERY%") > 0;

        if (!hasSlowQueryLineage) {
            int slowQueryProcessed = 0;
            int page = 0;
            boolean any = false;

            while (slowQueryProcessed < maxQueriesPerSource) {
                List<SlowQueryHistory> slowQueries = slowQueryHistoryRepository
                    .findByConnectionIdSince(connectionId, since, PageRequest.of(page, batchSize));

                if (slowQueries.isEmpty()) {
                    break;
                }
                any = true;

                for (SlowQueryHistory history : slowQueries) {
                    if (slowQueryProcessed >= maxQueriesPerSource) {
                        break;
                    }
                    try {
                        SlowQueryAnalysis analysis = slowQueryHistoryService.getAnalysisData(history);
                        if (analysis.getTopSlowQueries() == null || analysis.getTopSlowQueries().isEmpty()) {
                            continue;
                        }

                        for (SlowQuery slowQuery : analysis.getTopSlowQueries()) {
                            String queryText = slowQuery.getQueryText();
                            if (queryText == null || queryText.isBlank()) {
                                queryText = slowQuery.getNormalizedQuery();
                            }
                            if (queryText == null || queryText.isBlank()) {
                                continue;
                            }

                            ColumnUsageExtraction extraction = sqlParserService.extractColumnUsage(queryText);
                            Set<String> usageTables = Collections.emptySet();
                            if (hasUnqualifiedColumns(extraction)) {
                                SqlUsage usage = schema != null
                                    ? sqlUsageService.parseUsage(queryText, schema, disambiguationMap)
                                    : sqlUsageService.parseUsage(connectionId, queryText, disambiguationMap);
                                resolveMissingTableNames(extraction, usage, disambiguationMap);
                                if (usage != null && usage.getTables() != null) {
                                    usageTables = usage.getTables();
                                }
                            }
                            LocalDateTime queryTime = slowQuery.getLastSeen() != null
                                ? slowQuery.getLastSeen()
                                : history.getCreatedAt();
                            String querySignature = buildQuerySignature(
                                slowQuery.getQueryId(),
                                slowQuery.getNormalizedQuery(),
                                queryText
                            );
                            int usageWeight = computeSlowQueryWeight(slowQuery);

                            processExtraction(extraction, aggregators, "SLOW_QUERY",
                                userDatabase, queryTime, querySignature, usageWeight, usageTables, schemaColumnMap);
                            queriesAnalyzed++;
                            slowQueryProcessed++;

                            // Add delay between batches to prevent CPU spikes
                            if (slowQueryProcessed % batchSize == 0) {
                                log.info("Processed {} slow queries...", slowQueryProcessed);
                                if (delayBetweenBatchesMs > 0) {
                                    Thread.sleep(delayBetweenBatchesMs);
                                }
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        log.warn("Key column analysis interrupted during slow query processing");
                        break;
                    } catch (Exception e) {
                        log.debug("Error parsing slow query history: {}", e.getMessage());
                    }
                }

                if (slowQueries.size() < batchSize) {
                    break;
                }
                page++;
            }

            if (!any) {
                log.info("No slow query history found for key column analysis");
            } else {
                log.info("Processed {} slow query history records (max {})",
                    slowQueryProcessed, maxQueriesPerSource);
            }
        } else {
            log.info("Slow query lineage entries detected; skipping history parsing to avoid duplicates");
        }

        // Parse query lineage in pages to avoid loading entire history into memory
        int lineageProcessed = 0;
        int lineagePage = 0;
        boolean anyLineage = false;

        while (lineageProcessed < maxQueriesPerSource) {
            List<QueryLineage> lineages = queryLineageRepository
                .findByConnectionIdSince(connectionId, since, PageRequest.of(lineagePage, batchSize));

            if (lineages.isEmpty()) {
                break;
            }
            anyLineage = true;

            for (QueryLineage lineage : lineages) {
                if (lineageProcessed >= maxQueriesPerSource) {
                    break;
                }
                try {
                    String queryText = lineage.getQueryText();
                    if (queryText == null || queryText.isEmpty()) {
                        queryText = lineage.getNormalizedQuery();
                    }
                    if (queryText != null && !queryText.isEmpty()) {
                        ColumnUsageExtraction extraction = sqlParserService.extractColumnUsage(queryText);
                        Set<String> usageTables = Collections.emptySet();
                        if (hasUnqualifiedColumns(extraction)) {
                            SqlUsage usage = schema != null
                                ? sqlUsageService.parseUsage(queryText, schema, disambiguationMap)
                                : sqlUsageService.parseUsage(connectionId, queryText, disambiguationMap);
                            resolveMissingTableNames(extraction, usage, disambiguationMap);
                            if (usage != null && usage.getTables() != null) {
                                usageTables = usage.getTables();
                            }
                        }
                        LocalDateTime queryTime = lineage.getLastSeenAt() != null
                            ? lineage.getLastSeenAt()
                            : lineage.getCreatedAt();
                        String querySignature = buildQuerySignature(
                            lineage.getQueryHash(),
                            lineage.getNormalizedQuery(),
                            queryText
                        );
                        int usageWeight = computeLineageWeight(lineage);
                        String sourceCategory = normalizeSourceForUsage(lineage.getSource());
                        processExtraction(extraction, aggregators, sourceCategory, userDatabase,
                            queryTime, querySignature, usageWeight, usageTables, schemaColumnMap);
                        queriesAnalyzed++;
                        lineageProcessed++;

                        // Add delay between batches to prevent CPU spikes
                        if (lineageProcessed % batchSize == 0) {
                            log.info("Processed {} lineage records...", lineageProcessed);
                            if (delayBetweenBatchesMs > 0) {
                                Thread.sleep(delayBetweenBatchesMs);
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Key column analysis interrupted during lineage processing");
                    break;
                } catch (Exception e) {
                    log.debug("Error parsing lineage query: {}", e.getMessage());
                }
            }

            if (lineages.size() < batchSize) {
                break;
            }
            lineagePage++;
        }

        if (!anyLineage) {
            log.info("No query lineage records found for key column analysis");
        } else {
            log.info("Processed {} lineage records (max {})",
                lineageProcessed, maxQueriesPerSource);
        }

        // Parse query performance history in pages to avoid loading entire history into memory
        int perfProcessed = 0;
        int perfPage = 0;
        boolean anyPerf = false;

        while (perfProcessed < maxQueriesPerSource) {
            List<QueryPerformanceHistory> perfHistory = queryPerformanceHistoryRepository
                .findRecentHistory(connectionId, since, PageRequest.of(perfPage, batchSize));

            if (perfHistory.isEmpty()) {
                break;
            }
            anyPerf = true;

            for (QueryPerformanceHistory perf : perfHistory) {
                if (perfProcessed >= maxQueriesPerSource) {
                    break;
                }
                try {
                    String queryText = perf.getQueryText();
                    if ((queryText == null || queryText.isEmpty()) && perf.getNormalizedQuery() != null) {
                        queryText = perf.getNormalizedQuery();
                    }
                    if (queryText != null && !queryText.isEmpty()) {
                        ColumnUsageExtraction extraction = sqlParserService.extractColumnUsage(queryText);
                        Set<String> usageTables = Collections.emptySet();
                        if (hasUnqualifiedColumns(extraction)) {
                            SqlUsage usage = schema != null
                                ? sqlUsageService.parseUsage(queryText, schema, disambiguationMap)
                                : sqlUsageService.parseUsage(connectionId, queryText, disambiguationMap);
                            resolveMissingTableNames(extraction, usage, disambiguationMap);
                            if (usage != null && usage.getTables() != null) {
                                usageTables = usage.getTables();
                            }
                        }
                        String querySignature = buildQuerySignature(
                            perf.getQueryHash(),
                            perf.getNormalizedQuery(),
                            queryText
                        );
                        int usageWeight = computePerformanceWeight(perf);
                        processExtraction(extraction, aggregators, "PERF_HISTORY", userDatabase,
                            perf.getExecutionTimestamp(), querySignature, usageWeight, usageTables, schemaColumnMap);
                        queriesAnalyzed++;
                        perfProcessed++;

                        // Add delay between batches to prevent CPU spikes
                        if (perfProcessed % batchSize == 0) {
                            log.info("Processed {} performance history records...", perfProcessed);
                            if (delayBetweenBatchesMs > 0) {
                                Thread.sleep(delayBetweenBatchesMs);
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Key column analysis interrupted during performance history processing");
                    break;
                } catch (Exception e) {
                    log.debug("Error parsing performance history query: {}", e.getMessage());
                }
            }

            if (perfHistory.size() < batchSize) {
                break;
            }
            perfPage++;
        }

        if (!anyPerf) {
            log.info("No performance history records found for key column analysis");
        } else {
            log.info("Processed {} performance history records (max {})",
                perfProcessed, maxQueriesPerSource);
        }

        log.info("Analyzed {} queries total, found {} unique columns", queriesAnalyzed, aggregators.size());

        if (schema != null) {
            seedSchemaKeyCandidates(aggregators, schema, userDatabase);
        }

        // Step 2: Calculate scores and create analyses
        List<KeyColumnAnalysis> analyses = new ArrayList<>();
        LocalDateTime analyzedAt = LocalDateTime.now();

        for (Map.Entry<String, ColumnUsageAggregator> entry : aggregators.entrySet()) {
            ColumnUsageAggregator agg = entry.getValue();
            double score = calculateScore(agg);

            KeyColumnAnalysis analysis = KeyColumnAnalysis.builder()
                .connectionId(connectionId)
                .tableName(agg.tableName)
                .columnName(agg.columnName)
                .joinCount(agg.joinCount)
                .whereCount(agg.whereCount)
                .groupByCount(agg.groupByCount)
                .orderByCount(agg.orderByCount)
                .totalUsageCount(agg.totalUsageCount)
                .importanceScore(BigDecimal.valueOf(score))
                .slowQueryUsage(agg.slowQueryUsage)
                .lineageUsage(agg.lineageUsage)
                .performanceHistoryUsage(agg.performanceHistoryUsage)
                .distinctQueriesCount(agg.distinctQueriesCount)
                .analyzedAt(analyzedAt)
                .firstSeenAt(agg.firstSeenAt)
                .lastSeenAt(agg.lastSeenAt)
                .hasAntiPatterns(false)
                .antiPatternCount(0)
                .keyType(agg.schemaKeyType)
                .keyConfidence(agg.schemaKeyConfidence != null ? BigDecimal.valueOf(agg.schemaKeyConfidence) : null)
                .build();

            analyses.add(analysis);
        }

        // Step 3: Enhanced Analysis
        log.info("Running enhanced analysis...");

        // 3a0: Ensure column profiles exist (needed for cardinality/selectivity)
        try {
            log.info("Running column profiling to ensure cardinality data is available...");
            columnProfilingService.profileConnection(connectionId);
        } catch (Exception e) {
            log.warn("Column profiling failed, cardinality data may be incomplete: {}", e.getMessage());
        }

        // 3a: Enrich with cardinality (Oracle-style selectivity)
        enrichWithCardinality(analyses, connectionId);

        // 3a2: Enrich with data skew analysis
        enrichWithSkew(analyses, connectionId);

        // 3a3: Classify keys (true vs accidental)
        classifyKeys(analyses, connectionId);

        // 3a4: Detect partitioning candidates
        detectPartitioningCandidates(analyses);

        // 3a5: Apply user-defined rules
        applyUserRules(analyses, connectionId);

        // 3b: Calculate frequency and recency scores
        calculateFrequencyAndRecency(analyses, since);

        // 3c: Calculate enhanced importance score
        calculateEnhancedScore(analyses);

        // 3d: Fetch index usage statistics
        enrichWithIndexStats(analyses, connectionId);

        // 3e: Calculate ML prediction score
        calculateMLPredictionScore(analyses);

        // Step 4: Detect anti-patterns
        List<ColumnAntiPattern> antiPatterns = detectAntiPatterns(connectionId, analyses);

        // Step 5: Detect composite index opportunities
        List<CompositeIndexRecommendation> compositeRecs = detectCompositeIndexes(connectionId, aggregators);

        // Step 6: Replace existing results in a single transaction to avoid data loss on failure
        log.info("Saving {} analyses, {} anti-patterns, {} composite recommendations",
            analyses.size(), antiPatterns.size(), compositeRecs.size());
        replaceAnalysisData(connectionId, analyses, antiPatterns, compositeRecs);

        log.info("Analysis complete. Found {} key columns, {} anti-patterns, {} composite recommendations",
            analyses.size(), antiPatterns.size(), compositeRecs.size());

        // Step 5: Build result
        return buildResult(connectionId, analyses, antiPatterns, analyzedAt, queriesAnalyzed);
    }

    private void replaceAnalysisData(
        String connectionId,
        List<KeyColumnAnalysis> analyses,
        List<ColumnAntiPattern> antiPatterns,
        List<CompositeIndexRecommendation> compositeRecs
    ) {
        transactionTemplate.executeWithoutResult(status -> {
            keyColumnAnalysisRepository.deleteByConnectionId(connectionId);
            antiPatternRepository.deleteByConnectionId(connectionId);
            compositeIndexRecommendationRepository.deleteByConnectionId(connectionId);
            keyColumnAnalysisRepository.flush();
            antiPatternRepository.flush();
            compositeIndexRecommendationRepository.flush();
            keyColumnAnalysisRepository.saveAll(analyses);
            antiPatternRepository.saveAll(antiPatterns);
            compositeIndexRecommendationRepository.saveAll(compositeRecs);
        });
    }

    /**
     * Get existing analysis results
     */
    public KeyColumnAnalysisResult getKeyColumns(String connectionId, Integer limit,
                                                  String tableName, Boolean antiPatternsOnly) {
        List<KeyColumnAnalysis> analyses;

        if (tableName != null && !tableName.isEmpty()) {
            analyses = keyColumnAnalysisRepository.findByConnectionIdAndTableNameOrderByImportanceScoreDesc(
                connectionId, tableName);
        } else if (antiPatternsOnly != null && antiPatternsOnly) {
            analyses = keyColumnAnalysisRepository.findByConnectionIdAndHasAntiPatternsTrueOrderByImportanceScoreDesc(
                connectionId);
        } else {
            analyses = keyColumnAnalysisRepository.findByConnectionIdOrderByImportanceScoreDesc(connectionId);
        }

        // Apply limit
        if (limit != null && limit > 0 && analyses.size() > limit) {
            analyses = analyses.subList(0, limit);
        }

        List<ColumnAntiPattern> antiPatterns = antiPatternRepository
            .findByConnectionIdOrderBySeverityDescDetectedAtDesc(connectionId);

        LocalDateTime analyzedAt = analyses.isEmpty() ? LocalDateTime.now() :
            analyses.get(0).getAnalyzedAt();

        return buildResult(connectionId, analyses, antiPatterns, analyzedAt, 0);
    }

    private void processExtraction(ColumnUsageExtraction extraction,
                                    Map<String, ColumnUsageAggregator> aggregators,
                                    String source,
                                    String userDatabase,
                                    LocalDateTime queryTime,
                                    String querySignature,
                                    int usageWeight,
                                    Set<String> usageTables,
                                    Map<String, Set<String>> schemaColumnMap) {
        int weight = Math.max(1, usageWeight);
        LocalDateTime usageTime = queryTime != null ? queryTime : LocalDateTime.now();

        // Process JOIN columns
        for (ColumnUsageDetail detail : extraction.getJoinColumns()) {
            List<String> tableNames = resolveDetailTables(detail, usageTables, schemaColumnMap);
            if (tableNames.isEmpty()) {
                log.debug("Skipping column {} - no resolved table", detail.getColumnName());
                continue;
            }
            for (String tableName : tableNames) {
                if (isSystemTable(tableName, userDatabase)) {
                    log.debug("Skipping system table: {}", tableName);
                    continue;
                }
                String key = makeKey(tableName, detail.getColumnName());
                ColumnUsageAggregator agg = aggregators.computeIfAbsent(key,
                    k -> new ColumnUsageAggregator(tableName, detail.getColumnName()));
                agg.joinCount += weight;
                agg.totalUsageCount += weight;
                agg.recordUsage(usageTime, querySignature);
                incrementSourceUsage(agg, source, weight);
            }
        }

        // Process WHERE columns
        for (ColumnUsageDetail detail : extraction.getWhereColumns()) {
            List<String> tableNames = resolveDetailTables(detail, usageTables, schemaColumnMap);
            if (tableNames.isEmpty()) {
                log.debug("Skipping column {} - no resolved table", detail.getColumnName());
                continue;
            }
            for (String tableName : tableNames) {
                if (isSystemTable(tableName, userDatabase)) {
                    log.debug("Skipping system table: {}", tableName);
                    continue;
                }
                String key = makeKey(tableName, detail.getColumnName());
                ColumnUsageAggregator agg = aggregators.computeIfAbsent(key,
                    k -> new ColumnUsageAggregator(tableName, detail.getColumnName()));
                agg.whereCount += weight;
                agg.totalUsageCount += weight;
                agg.recordUsage(usageTime, querySignature);
                incrementSourceUsage(agg, source, weight);
            }
        }

        // Process GROUP BY columns
        for (ColumnUsageDetail detail : extraction.getGroupByColumns()) {
            List<String> tableNames = resolveDetailTables(detail, usageTables, schemaColumnMap);
            if (tableNames.isEmpty()) {
                log.debug("Skipping column {} - no resolved table", detail.getColumnName());
                continue;
            }
            for (String tableName : tableNames) {
                if (isSystemTable(tableName, userDatabase)) {
                    log.debug("Skipping system table: {}", tableName);
                    continue;
                }
                String key = makeKey(tableName, detail.getColumnName());
                ColumnUsageAggregator agg = aggregators.computeIfAbsent(key,
                    k -> new ColumnUsageAggregator(tableName, detail.getColumnName()));
                agg.groupByCount += weight;
                agg.totalUsageCount += weight;
                agg.recordUsage(usageTime, querySignature);
                incrementSourceUsage(agg, source, weight);
            }
        }

        // Process ORDER BY columns
        for (ColumnUsageDetail detail : extraction.getOrderByColumns()) {
            List<String> tableNames = resolveDetailTables(detail, usageTables, schemaColumnMap);
            if (tableNames.isEmpty()) {
                log.debug("Skipping column {} - no resolved table", detail.getColumnName());
                continue;
            }
            for (String tableName : tableNames) {
                if (isSystemTable(tableName, userDatabase)) {
                    log.debug("Skipping system table: {}", tableName);
                    continue;
                }
                String key = makeKey(tableName, detail.getColumnName());
                ColumnUsageAggregator agg = aggregators.computeIfAbsent(key,
                    k -> new ColumnUsageAggregator(tableName, detail.getColumnName()));
                agg.orderByCount += weight;
                agg.totalUsageCount += weight;
                agg.recordUsage(usageTime, querySignature);
                incrementSourceUsage(agg, source, weight);
            }
        }
    }

    private List<String> resolveDetailTables(
        ColumnUsageDetail detail,
        Set<String> usageTables,
        Map<String, Set<String>> schemaColumnMap
    ) {
        if (detail == null) {
            return Collections.emptyList();
        }
        if (detail.getTableName() != null && !detail.getTableName().isBlank()) {
            return Collections.singletonList(detail.getTableName());
        }
        if (detail.getColumnName() == null || detail.getColumnName().isBlank()) {
            return Collections.emptyList();
        }

        String columnKey = detail.getColumnName().toLowerCase(Locale.ROOT);
        Set<String> candidates = schemaColumnMap.get(columnKey);
        if (candidates == null || candidates.isEmpty()) {
            return Collections.emptyList();
        }

        if (usageTables != null && !usageTables.isEmpty()) {
            List<String> matches = candidates.stream()
                .filter(table -> usageTables.contains(table.toLowerCase(Locale.ROOT)))
                .sorted()
                .toList();
            return matches;
        }

        if (candidates.size() == 1) {
            return Collections.singletonList(candidates.iterator().next());
        }

        return Collections.emptyList();
    }

    private Map<String, Set<String>> buildSchemaColumnMap(SchemaMetadata schema) {
        Map<String, Set<String>> map = new HashMap<>();
        if (schema == null || schema.getTables() == null) {
            return map;
        }
        for (TableMetadata table : schema.getTables()) {
            if (table.getColumns() == null || table.getName() == null) {
                continue;
            }
            String tableName = table.getName().toLowerCase(Locale.ROOT);
            for (ColumnMetadata column : table.getColumns()) {
                if (column.getName() == null) {
                    continue;
                }
                String columnKey = column.getName().toLowerCase(Locale.ROOT);
                map.computeIfAbsent(columnKey, key -> new HashSet<>()).add(tableName);
            }
        }
        return map;
    }

    private boolean hasUnqualifiedColumns(ColumnUsageExtraction extraction) {
        return hasUnqualified(extraction.getJoinColumns()) ||
            hasUnqualified(extraction.getWhereColumns()) ||
            hasUnqualified(extraction.getGroupByColumns()) ||
            hasUnqualified(extraction.getOrderByColumns());
    }

    private boolean hasUnqualified(List<ColumnUsageDetail> details) {
        if (details == null) {
            return false;
        }
        for (ColumnUsageDetail detail : details) {
            if (detail.getColumnName() != null &&
                (detail.getTableName() == null || detail.getTableName().isBlank())) {
                return true;
            }
        }
        return false;
    }

    private void resolveMissingTableNames(
        ColumnUsageExtraction extraction,
        SqlUsage usage,
        Map<String, String> disambiguationMap
    ) {
        if (usage == null) {
            return;
        }

        Map<String, Set<String>> columnToTables = new HashMap<>();
        if (usage.getColumns() != null) {
            for (String columnRef : usage.getColumns()) {
                if (columnRef == null || !columnRef.contains(".")) {
                    continue;
                }
                String[] parts = columnRef.split("\\.", 2);
                if (parts.length < 2) {
                    continue;
                }
                String table = parts[0].trim();
                String column = parts[1].trim();
                if (table.isEmpty() || column.isEmpty()) {
                    continue;
                }
                columnToTables
                    .computeIfAbsent(column.toLowerCase(Locale.ROOT), key -> new HashSet<>())
                    .add(table);
            }
        }

        String singleTable = null;
        if (usage.getTables() != null && usage.getTables().size() == 1) {
            singleTable = usage.getTables().iterator().next();
        }

        resolveMissing(extraction.getJoinColumns(), disambiguationMap, columnToTables, singleTable);
        resolveMissing(extraction.getWhereColumns(), disambiguationMap, columnToTables, singleTable);
        resolveMissing(extraction.getGroupByColumns(), disambiguationMap, columnToTables, singleTable);
        resolveMissing(extraction.getOrderByColumns(), disambiguationMap, columnToTables, singleTable);
    }

    private void resolveMissing(
        List<ColumnUsageDetail> details,
        Map<String, String> disambiguationMap,
        Map<String, Set<String>> columnToTables,
        String singleTable
    ) {
        if (details == null) {
            return;
        }
        for (ColumnUsageDetail detail : details) {
            if (detail.getColumnName() == null) {
                continue;
            }
            if (detail.getTableName() != null && !detail.getTableName().isBlank()) {
                continue;
            }

            String columnKey = detail.getColumnName().toLowerCase(Locale.ROOT);
            String resolved = null;

            if (disambiguationMap != null) {
                resolved = disambiguationMap.get(columnKey);
            }

            if (resolved == null) {
                Set<String> tables = columnToTables.get(columnKey);
                if (tables != null && tables.size() == 1) {
                    resolved = tables.iterator().next();
                }
            }

            if (resolved == null && singleTable != null) {
                resolved = singleTable;
            }

            if (resolved != null) {
                detail.setTableName(resolved);
            }
        }
    }

    private void incrementSourceUsage(ColumnUsageAggregator agg, String source, int weight) {
        if (source == null) {
            return;
        }
        switch (source) {
            case "SLOW_QUERY" -> agg.slowQueryUsage += weight;
            case "PERF_HISTORY" -> agg.performanceHistoryUsage += weight;
            case "LINEAGE" -> agg.lineageUsage += weight;
            default -> {
                // Unknown sources are treated as lineage usage by default.
                agg.lineageUsage += weight;
            }
        }
    }

    private String normalizeSourceForUsage(String source) {
        if (source == null) {
            return "LINEAGE";
        }
        String normalized = source.toUpperCase(Locale.ROOT);
        if (normalized.contains("SLOW")) {
            return "SLOW_QUERY";
        }
        if (normalized.contains("PERF")) {
            return "PERF_HISTORY";
        }
        return "LINEAGE";
    }

    private String buildQuerySignature(String queryHash, String normalizedQuery, String queryText) {
        if (queryHash != null && !queryHash.isBlank()) {
            return queryHash;
        }
        if (normalizedQuery != null && !normalizedQuery.isBlank()) {
            return normalizedQuery;
        }
        if (queryText != null && !queryText.isBlank()) {
            return Integer.toHexString(queryText.hashCode());
        }
        return null;
    }

    private int computeSlowQueryWeight(SlowQuery slowQuery) {
        if (slowQuery == null) {
            return 1;
        }
        int weight = 1 + computeSeverityWeight(slowQuery.getSeverity());

        if (slowQuery.getPerformanceImpact() != null) {
            weight += Math.min(3, (int) Math.round(slowQuery.getPerformanceImpact() / 25.0));
        }

        if (slowQuery.getCallCount() != null) {
            weight += Math.min(3, (int) Math.log10(slowQuery.getCallCount() + 1));
        }

        return Math.min(10, weight);
    }

    private int computeLineageWeight(QueryLineage lineage) {
        if (lineage == null) {
            return 1;
        }

        int weight = 1 + computeSeverityWeight(lineage.getSeverity());

        if (lineage.getPerformanceImpact() != null) {
            weight += Math.min(3, (int) Math.round(lineage.getPerformanceImpact() / 25.0));
        }

        if (lineage.getCallCount() != null) {
            weight += Math.min(3, (int) Math.log10(lineage.getCallCount() + 1));
        }

        if (lineage.getAvgExecutionTimeMs() != null) {
            weight += Math.min(2, (int) Math.floor(lineage.getAvgExecutionTimeMs() / 1000.0));
        }

        return Math.min(10, weight);
    }

    private int computePerformanceWeight(QueryPerformanceHistory perf) {
        if (perf == null || perf.getExecutionTimeMs() == null) {
            return 1;
        }

        double execTime = perf.getExecutionTimeMs();
        int weight = 1;

        if (execTime >= 5000) {
            weight += 3;
        } else if (execTime >= 1000) {
            weight += 2;
        } else if (execTime >= 200) {
            weight += 1;
        }

        return Math.min(6, weight);
    }

    private int computeSeverityWeight(SlowQuery.Severity severity) {
        if (severity == null) {
            return 0;
        }
        return switch (severity) {
            case CRITICAL -> 3;
            case HIGH -> 2;
            case MEDIUM -> 1;
            case LOW -> 0;
        };
    }

    private int computeSeverityWeight(String severity) {
        if (severity == null || severity.isBlank()) {
            return 0;
        }
        try {
            return computeSeverityWeight(SlowQuery.Severity.valueOf(severity.toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return 0;
        }
    }

    private String makeKey(String tableName, String columnName) {
        // Normalize to lowercase to prevent case-sensitive duplicates (e.g., "Users" vs "users")
        String normalizedTable = (tableName != null ? tableName.toLowerCase() : "unknown");
        String normalizedColumn = (columnName != null ? columnName.toLowerCase() : "unknown");
        return normalizedTable + "." + normalizedColumn;
    }

    private void seedSchemaKeyCandidates(
        Map<String, ColumnUsageAggregator> aggregators,
        SchemaMetadata schema,
        String userDatabase
    ) {
        if (schema == null) {
            return;
        }

        LocalDateTime now = LocalDateTime.now();

        if (schema.getRelationships() != null) {
            for (RelationshipMetadata rel : schema.getRelationships()) {
                addSchemaCandidate(aggregators, rel.getFromTable(), rel.getFromColumn(),
                    userDatabase, now, true);
                addSchemaCandidate(aggregators, rel.getToTable(), rel.getToColumn(),
                    userDatabase, now, true);
            }
        }

        Map<String, Set<String>> columnMap = buildSchemaColumnMap(schema);
        for (Map.Entry<String, Set<String>> entry : columnMap.entrySet()) {
            String columnName = entry.getKey();
            if (!isLikelyKeyColumn(columnName)) {
                continue;
            }
            Set<String> tables = entry.getValue();
            if (tables.size() < 3) {
                continue;
            }
            for (String tableName : tables) {
                addSchemaCandidate(aggregators, tableName, columnName,
                    userDatabase, now, false);
            }
        }
    }

    private boolean isLikelyKeyColumn(String columnName) {
        if (columnName == null) {
            return false;
        }
        String name = columnName.toLowerCase(Locale.ROOT);
        if (name.equals("id")) {
            return true;
        }
        return name.endsWith("_id") || name.endsWith("id") || name.endsWith("_key") || name.endsWith("key");
    }

    private void addSchemaCandidate(
        Map<String, ColumnUsageAggregator> aggregators,
        String tableName,
        String columnName,
        String userDatabase,
        LocalDateTime usageTime,
        boolean isForeignKey
    ) {
        if (tableName == null || columnName == null) {
            return;
        }
        String table = tableName.toLowerCase(Locale.ROOT);
        String column = columnName.toLowerCase(Locale.ROOT);
        if (isSystemTable(table, userDatabase)) {
            return;
        }

        String key = makeKey(table, column);
        ColumnUsageAggregator agg = aggregators.computeIfAbsent(key,
            k -> new ColumnUsageAggregator(table, column));

        int boost = isForeignKey ? 2 : 1;
        agg.joinCount += boost;
        agg.totalUsageCount += boost;
        agg.lineageUsage += boost;
        agg.recordUsage(usageTime, "schema:" + key);

        if (agg.schemaKeyType == null) {
            agg.schemaKeyType = "TRUE_KEY";
            agg.schemaKeyConfidence = isForeignKey ? 0.8 : 0.6;
        }
    }

    private double calculateScore(ColumnUsageAggregator agg) {
        int weightedSum = (agg.joinCount * joinWeight) +
                          (agg.whereCount * whereWeight) +
                          (agg.groupByCount * groupByWeight) +
                          (agg.orderByCount * orderByWeight);

        // Normalize to 0-100 scale (50 weighted uses = 100 score)
        return Math.min(100.0, (weightedSum / 50.0) * 100.0);
    }

    private List<ColumnAntiPattern> detectAntiPatterns(String connectionId,
                                                        List<KeyColumnAnalysis> analyses) {
        List<ColumnAntiPattern> patterns = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();

        for (KeyColumnAnalysis analysis : analyses) {
            // A column that is already indexed (indexName set by enrichWithIndexStats)
            // or is a known key (TRUE_KEY, or a PRIMARY/UNIQUE label from any future
            // classifier) can never be flagged UNINDEXED_* — that combination is a
            // contradiction, not a finding. This is what stops resolved primary keys
            // from generating "unindexed join/filter" noise every single run.
            boolean isKnownKeyOrIndexed = analysis.getIndexName() != null
                || isKeyLikeType(analysis.getKeyType());

            // Rule 1: Unindexed filter columns (skip if already indexed)
            if (analysis.getWhereCount() >= 5 && !isKnownKeyOrIndexed) {
                ColumnAntiPattern pattern = ColumnAntiPattern.builder()
                    .connectionId(connectionId)
                    .tableName(analysis.getTableName())
                    .columnName(analysis.getColumnName())
                    .patternType("UNINDEXED_FILTER")
                    .severity(analysis.getWhereCount() > 10 ?
                        ColumnAntiPattern.Severity.HIGH : ColumnAntiPattern.Severity.MEDIUM)
                    .title("Column frequently used in filters")
                    .description(String.format("Column '%s.%s' is used in WHERE clauses %d times but may not be indexed",
                        analysis.getTableName(), analysis.getColumnName(), analysis.getWhereCount()))
                    .recommendation(String.format(
                        "Consider creating an index: CREATE INDEX idx_%s_%s ON %s(%s);",
                        analysis.getTableName(), analysis.getColumnName(),
                        analysis.getTableName(), analysis.getColumnName()))
                    .affectedQueriesCount(analysis.getWhereCount())
                    .detectedAt(now)
                    .build();

                patterns.add(pattern);
                analysis.setHasAntiPatterns(true);
                analysis.setAntiPatternCount(analysis.getAntiPatternCount() + 1);
            }

            // Rule 2: Unindexed JOIN columns (skip if already indexed)
            if (analysis.getJoinCount() >= 5 && !isKnownKeyOrIndexed) {
                ColumnAntiPattern pattern = ColumnAntiPattern.builder()
                    .connectionId(connectionId)
                    .tableName(analysis.getTableName())
                    .columnName(analysis.getColumnName())
                    .patternType("UNINDEXED_JOIN")
                    .severity(analysis.getJoinCount() > 20 ?
                        ColumnAntiPattern.Severity.CRITICAL : ColumnAntiPattern.Severity.HIGH)
                    .title("Column frequently used in JOINs")
                    .description(String.format("Column '%s.%s' is used in JOIN operations %d times but may not be indexed",
                        analysis.getTableName(), analysis.getColumnName(), analysis.getJoinCount()))
                    .recommendation(String.format(
                        "Critical: Create an index immediately: CREATE INDEX idx_%s_%s ON %s(%s);",
                        analysis.getTableName(), analysis.getColumnName(),
                        analysis.getTableName(), analysis.getColumnName()))
                    .affectedQueriesCount(analysis.getJoinCount())
                    .detectedAt(now)
                    .build();

                patterns.add(pattern);
                analysis.setHasAntiPatterns(true);
                analysis.setAntiPatternCount(analysis.getAntiPatternCount() + 1);
            }

            // Rule 3: Unindexed ORDER BY (skip if already indexed)
            if (analysis.getOrderByCount() >= 3 && !isKnownKeyOrIndexed) {
                ColumnAntiPattern pattern = ColumnAntiPattern.builder()
                    .connectionId(connectionId)
                    .tableName(analysis.getTableName())
                    .columnName(analysis.getColumnName())
                    .patternType("UNINDEXED_ORDERBY")
                    .severity(ColumnAntiPattern.Severity.MEDIUM)
                    .title("Column frequently used in ORDER BY")
                    .description(String.format("Column '%s.%s' is used in ORDER BY clauses %d times",
                        analysis.getTableName(), analysis.getColumnName(), analysis.getOrderByCount()))
                    .recommendation(String.format(
                        "Consider creating an index to improve sort performance: CREATE INDEX idx_%s_%s ON %s(%s);",
                        analysis.getTableName(), analysis.getColumnName(),
                        analysis.getTableName(), analysis.getColumnName()))
                    .affectedQueriesCount(analysis.getOrderByCount())
                    .detectedAt(now)
                    .build();

                patterns.add(pattern);
                analysis.setHasAntiPatterns(true);
                analysis.setAntiPatternCount(analysis.getAntiPatternCount() + 1);
            }

            // Rule 4: Heavy skew in JOIN columns
            if (analysis.getIsHeavilySkewed() != null && analysis.getIsHeavilySkewed() &&
                analysis.getJoinCount() >= 3 && analysis.getSkewCoefficient() != null) {

                double skewPct = analysis.getSkewCoefficient() * 100;
                String skewCategory = skewAnalysisService.categorizeSkew(analysis.getSkewCoefficient());

                ColumnAntiPattern pattern = ColumnAntiPattern.builder()
                    .connectionId(connectionId)
                    .tableName(analysis.getTableName())
                    .columnName(analysis.getColumnName())
                    .patternType("HEAVY_SKEW_JOIN")
                    .severity(ColumnAntiPattern.Severity.MEDIUM)
                    .title(String.format("Heavily skewed column (%s skew) used in JOINs", skewCategory))
                    .description(String.format(
                        "Column '%s.%s' has %.1f%% skew (top value is %.1f%% of data) and is used in %d JOINs. " +
                        "This can cause uneven data distribution and JOIN performance issues.",
                        analysis.getTableName(), analysis.getColumnName(),
                        skewPct, skewPct, analysis.getJoinCount()))
                    .recommendation(String.format(
                        "Consider: 1) Using hash-based joins, 2) Partitioning by a different column, " +
                        "3) Pre-aggregating skewed values, or 4) Query rewriting to handle skew"))
                    .affectedQueriesCount(analysis.getJoinCount())
                    .detectedAt(now)
                    .build();

                patterns.add(pattern);
                analysis.setHasAntiPatterns(true);
                analysis.setAntiPatternCount(analysis.getAntiPatternCount() + 1);
            }

            // Rule 5: Heavy skew in GROUP BY columns (informational)
            if (analysis.getIsHeavilySkewed() != null && analysis.getIsHeavilySkewed() &&
                analysis.getGroupByCount() >= 5 && analysis.getSkewCoefficient() != null) {

                double skewPct = analysis.getSkewCoefficient() * 100;

                ColumnAntiPattern pattern = ColumnAntiPattern.builder()
                    .connectionId(connectionId)
                    .tableName(analysis.getTableName())
                    .columnName(analysis.getColumnName())
                    .patternType("HEAVY_SKEW_GROUPBY")
                    .severity(ColumnAntiPattern.Severity.LOW)
                    .title("Heavily skewed column used in GROUP BY")
                    .description(String.format(
                        "Column '%s.%s' has %.1f%% skew and is used in %d GROUP BY operations. " +
                        "Skewed GROUP BY can lead to unbalanced aggregation workloads.",
                        analysis.getTableName(), analysis.getColumnName(),
                        skewPct, analysis.getGroupByCount()))
                    .recommendation(
                        "Consider: 1) Pre-computing aggregates for common values, " +
                        "2) Using approximate aggregation, or 3) Implementing stratified sampling")
                    .affectedQueriesCount(analysis.getGroupByCount())
                    .detectedAt(now)
                    .build();

                patterns.add(pattern);
                analysis.setHasAntiPatterns(true);
                analysis.setAntiPatternCount(analysis.getAntiPatternCount() + 1);
            }

            // Rule 6: Accidental key (high cardinality but no semantic meaning)
            if ("ACCIDENTAL_KEY".equals(analysis.getKeyType())) {
                ColumnAntiPattern pattern = ColumnAntiPattern.builder()
                    .connectionId(connectionId)
                    .tableName(analysis.getTableName())
                    .columnName(analysis.getColumnName())
                    .patternType("ACCIDENTAL_KEY")
                    .severity(ColumnAntiPattern.Severity.LOW)
                    .title("Column has high cardinality but no semantic key meaning")
                    .description(String.format(
                        "Column '%s.%s' has %s cardinality (selectivity: %.2f) but is never used in JOINs. " +
                        "This suggests it's not a meaningful key, just incidentally unique data.",
                        analysis.getTableName(), analysis.getColumnName(),
                        analysis.getSelectivity() != null && analysis.getSelectivity().doubleValue() > 0.95 ? "perfect" : "high",
                        analysis.getSelectivity() != null ? analysis.getSelectivity() : 0.0))
                    .recommendation(
                        "Review if this column should be: 1) Used as a foreign key, " +
                        "2) Indexed for lookups, or 3) Simply acknowledged as unique data with no relational purpose")
                    .affectedQueriesCount(0)
                    .detectedAt(now)
                    .build();

                patterns.add(pattern);
                analysis.setHasAntiPatterns(true);
                analysis.setAntiPatternCount(analysis.getAntiPatternCount() + 1);
            }
        }

        return patterns;
    }

    /**
     * True for any keyType label that means "this column is already a real key" —
     * TRUE_KEY is what this classifier actually assigns (see classifyKeys), while
     * PRIMARY/UNIQUE are accepted defensively in case a future classifier or an
     * imported/legacy row uses those labels instead.
     */
    private boolean isKeyLikeType(String keyType) {
        return "TRUE_KEY".equals(keyType) || "PRIMARY".equals(keyType) || "UNIQUE".equals(keyType);
    }

    /**
     * Safely get skew category with error handling
     */
    private String getSkewCategory(KeyColumnAnalysis analysis) {
        try {
            if (analysis.getSkewCoefficient() != null && skewAnalysisService != null) {
                return skewAnalysisService.categorizeSkew(analysis.getSkewCoefficient());
            }
        } catch (Exception e) {
            log.debug("Error categorizing skew for {}.{}: {}",
                analysis.getTableName(), analysis.getColumnName(), e.getMessage());
        }
        return null;
    }

    private KeyColumnAnalysisResult buildResult(String connectionId,
                                                 List<KeyColumnAnalysis> analyses,
                                                 List<ColumnAntiPattern> antiPatterns,
                                                 LocalDateTime analyzedAt,
                                                 int queriesAnalyzed) {
        // Sort by score descending (handle null scores)
        analyses.sort((a, b) -> {
            BigDecimal scoreA = a.getImportanceScore();
            BigDecimal scoreB = b.getImportanceScore();
            if (scoreA == null && scoreB == null) return 0;
            if (scoreA == null) return 1; // null scores go to end
            if (scoreB == null) return -1;
            return scoreB.compareTo(scoreA);
        });

        List<KeyColumnScore> topColumns = new ArrayList<>();
        for (KeyColumnAnalysis analysis : analyses) {
            // Skip if table or column name is null
            if (analysis.getTableName() == null || analysis.getColumnName() == null) {
                log.warn("Skipping analysis with null table/column name: table={}, column={}",
                    analysis.getTableName(), analysis.getColumnName());
                continue;
            }

            // Get anti-patterns for this column
            List<AntiPatternSummary> columnPatterns = new ArrayList<>();
            for (ColumnAntiPattern pattern : antiPatterns) {
                if (pattern.getTableName() != null && pattern.getColumnName() != null &&
                    pattern.getTableName().equals(analysis.getTableName()) &&
                    pattern.getColumnName().equals(analysis.getColumnName())) {

                    AntiPatternSummary summary = AntiPatternSummary.builder()
                        .id(pattern.getId())
                        .patternType(pattern.getPatternType())
                        .severity(pattern.getSeverity() != null ? pattern.getSeverity().name() : null)
                        .title(pattern.getTitle())
                        .description(pattern.getDescription())
                        .recommendation(pattern.getRecommendation())
                        .affectedQueriesCount(pattern.getAffectedQueriesCount())
                        .status(pattern.getStatus() != null ? pattern.getStatus().name() : null)
                        .build();

                    columnPatterns.add(summary);
                }
            }

            KeyColumnScore score = KeyColumnScore.builder()
                .tableName(analysis.getTableName())
                .columnName(analysis.getColumnName())
                .importanceScore(analysis.getImportanceScore())
                .enhancedImportanceScore(analysis.getEnhancedImportanceScore())
                .usageBreakdown(UsageBreakdown.builder()
                    .joinCount(analysis.getJoinCount())
                    .whereCount(analysis.getWhereCount())
                    .groupByCount(analysis.getGroupByCount())
                    .orderByCount(analysis.getOrderByCount())
                    .totalUsage(analysis.getTotalUsageCount())
                    .slowQueryUsage(analysis.getSlowQueryUsage() != null ? analysis.getSlowQueryUsage() : 0)
                    .lineageUsage(analysis.getLineageUsage() != null ? analysis.getLineageUsage() : 0)
                    .performanceHistoryUsage(analysis.getPerformanceHistoryUsage() != null ? analysis.getPerformanceHistoryUsage() : 0)
                    .distinctQueriesCount(analysis.getDistinctQueriesCount() != null ? analysis.getDistinctQueriesCount() : 0)
                    .build())
                .distinctCount(analysis.getDistinctCount())
                .totalRows(analysis.getTotalRows())
                .selectivity(analysis.getSelectivity())
                .cardinalityRatio(analysis.getCardinalityRatio())
                .nullRatio(analysis.getNullRatio())
                .skewCoefficient(analysis.getSkewCoefficient())
                .isHeavilySkewed(analysis.getIsHeavilySkewed())
                .skewCategory(getSkewCategory(analysis))
                .topValues(getTopValuesForColumn(connectionId, analysis))
                .keyType(analysis.getKeyType())
                .keyConfidence(analysis.getKeyConfidence())
                .isPartitionCandidate(analysis.getIsPartitionCandidate())
                .partitioningType(analysis.getPartitioningType())
                .partitioningScore(analysis.getPartitioningScore())
                .partitioningRecommendation(analysis.getPartitioningRecommendation())
                .frequencyScore(analysis.getFrequencyScore())
                .recencyScore(analysis.getRecencyScore())
                .mlPredictionScore(analysis.getMlPredictionScore())
                .usesPerDay(analysis.getUsesPerDay())
                .indexName(analysis.getIndexName())
                .indexUsageCount(analysis.getIndexUsageCount())
                .indexScanCount(analysis.getIndexScanCount())
                .hasUnusedIndex(analysis.getHasUnusedIndex())
                .isIndexed(analysis.getIndexName() != null)
                .hasAntiPatterns(analysis.getHasAntiPatterns())
                .antiPatterns(columnPatterns)
                .build();

            topColumns.add(score);
        }

        return KeyColumnAnalysisResult.builder()
            .topColumns(topColumns)
            .totalColumnsAnalyzed(analyses.size())
            .antiPatternsDetected(antiPatterns.size())
            .analyzedAt(analyzedAt)
            .metadata(AnalysisMetadata.builder()
                .analyzedAt(analyzedAt)
                .queriesAnalyzed(queriesAnalyzed)
                .isStale(false)
                .lookbackDays(lookbackDays)
                .build())
            .build();
    }

    /**
     * Enrich analysis with cardinality data (Oracle-style selectivity)
     */
    private void enrichWithCardinality(List<KeyColumnAnalysis> analyses, String connectionId) {
        log.info("Enriching with cardinality data for {} columns", analyses.size());

        for (KeyColumnAnalysis analysis : analyses) {
            try {
                Optional<ColumnProfile> profileOpt = columnProfileRepository
                    .findByConnectionIdAndTableNameAndColumnName(
                        connectionId, analysis.getTableName(), analysis.getColumnName()
                    );

                if (profileOpt.isPresent()) {
                    ColumnProfile profile = profileOpt.get();
                    analysis.setDistinctCount(profile.getDistinctCount());
                    analysis.setTotalRows(profile.getTotalRows());

                    // Calculate selectivity (distinctCount / totalRows)
                    if (profile.getTotalRows() != null && profile.getTotalRows() > 0) {
                        double selectivity = (double) profile.getDistinctCount() / profile.getTotalRows();
                        analysis.setSelectivity(BigDecimal.valueOf(selectivity));
                        analysis.setCardinalityRatio(BigDecimal.valueOf(selectivity));

                        // Calculate NULL ratio
                        if (profile.getNullCount() != null) {
                            double nullRatio = (double) profile.getNullCount() / profile.getTotalRows();
                            analysis.setNullRatio(BigDecimal.valueOf(nullRatio));
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Could not enrich cardinality for {}.{}: {}",
                    analysis.getTableName(), analysis.getColumnName(), e.getMessage());
            }
        }
    }

    /**
     * Enrich analysis with data skew information from ColumnProfile.
     * Implements BRAIN-design.md Section 3.2: "Data skew (top-N values)"
     */
    private void enrichWithSkew(List<KeyColumnAnalysis> analyses, String connectionId) {
        log.info("Enriching with data skew analysis for {} columns", analyses.size());

        for (KeyColumnAnalysis analysis : analyses) {
            try {
                Optional<ColumnProfile> profileOpt = columnProfileRepository
                    .findByConnectionIdAndTableNameAndColumnName(
                        connectionId, analysis.getTableName(), analysis.getColumnName()
                    );

                if (profileOpt.isPresent()) {
                    ColumnProfile profile = profileOpt.get();

                    // Copy skew coefficient from profile
                    if (profile.getSkewCoefficient() != null) {
                        analysis.setSkewCoefficient(profile.getSkewCoefficient());

                        // Flag as heavily skewed if coefficient > 0.7
                        analysis.setIsHeavilySkewed(profile.getSkewCoefficient() > 0.7);

                        log.debug("Column {}.{} has skew coefficient: {} (category: {})",
                            analysis.getTableName(),
                            analysis.getColumnName(),
                            profile.getSkewCoefficient(),
                            skewAnalysisService.categorizeSkew(profile.getSkewCoefficient()));
                    }
                }
            } catch (Exception e) {
                log.debug("Could not enrich skew for {}.{}: {}",
                    analysis.getTableName(), analysis.getColumnName(), e.getMessage());
            }
        }
    }

    /**
     * Classify keys as TRUE_KEY, ACCIDENTAL_KEY, SURROGATE_KEY, or NON_KEY.
     * Implements BRAIN-design.md Section 7: "True keys vs accidental keys"
     *
     * Classification Logic:
     * - TRUE_KEY: Has PK/UK constraint OR (high cardinality + heavy JOIN usage)
     * - SURROGATE_KEY: Auto-increment pattern (id, uuid) with perfect cardinality
     * - ACCIDENTAL_KEY: High cardinality but no semantic meaning (never joined)
     * - NON_KEY: Everything else
     */
    private void classifyKeys(List<KeyColumnAnalysis> analyses, String connectionId) {
        log.info("Classifying keys (true vs accidental) for {} columns", analyses.size());

        for (KeyColumnAnalysis analysis : analyses) {
            try {
                // Skip if table or column name is null
                if (analysis.getTableName() == null || analysis.getColumnName() == null) {
                    log.debug("Skipping key classification for analysis with null table/column name");
                    continue;
                }

                if (analysis.getKeyType() != null && !analysis.getKeyType().isBlank() &&
                    !"NON_KEY".equals(analysis.getKeyType())) {
                    continue;
                }

                String keyType = "NON_KEY";
                double confidence = 0.0;

                // Get cardinality metrics
                boolean hasPerfectCardinality = analysis.getSelectivity() != null &&
                    analysis.getSelectivity().doubleValue() > 0.95;
                boolean hasHighCardinality = analysis.getSelectivity() != null &&
                    analysis.getSelectivity().doubleValue() > 0.5;
                boolean isHeavilyUsedInJoins = analysis.getJoinCount() >= 5;
                boolean isModeratelyUsedInJoins = analysis.getJoinCount() >= 2;
                boolean isNeverJoined = analysis.getJoinCount() == 0;

                // Check naming patterns
                String columnName = analysis.getColumnName().toLowerCase();
                boolean isSurrogateNaming = PatternUtil.containsPattern(columnName, "(id|_id|uuid|key|_key)");
                boolean isPrimaryKeyNaming = columnName.equals("id") ||
                                            columnName.equals(analysis.getTableName().toLowerCase() + "_id");

                // Check for PK/UK constraint from index metadata
                boolean hasKeyConstraint = checkForKeyConstraint(analysis, connectionId);

                // Classification Logic
                if (hasKeyConstraint) {
                    // Explicit constraint - definitely a TRUE_KEY
                    keyType = "TRUE_KEY";
                    confidence = 1.0;
                    log.debug("Column {}.{} classified as TRUE_KEY (has PK/UK constraint)",
                        analysis.getTableName(), analysis.getColumnName());
                }
                else if (hasPerfectCardinality && isHeavilyUsedInJoins) {
                    // Perfect cardinality + heavy JOIN usage = TRUE_KEY (likely FK)
                    keyType = "TRUE_KEY";
                    confidence = 0.9;
                    log.debug("Column {}.{} classified as TRUE_KEY (perfect cardinality + {} JOINs)",
                        analysis.getTableName(), analysis.getColumnName(), analysis.getJoinCount());
                }
                else if (hasPerfectCardinality && isPrimaryKeyNaming && isNeverJoined) {
                    // Perfect cardinality + primary key naming but never joined = SURROGATE_KEY
                    keyType = "SURROGATE_KEY";
                    confidence = 0.85;
                    log.debug("Column {}.{} classified as SURROGATE_KEY (perfect cardinality + PK naming)",
                        analysis.getTableName(), analysis.getColumnName());
                }
                else if (hasPerfectCardinality && isSurrogateNaming && isModeratelyUsedInJoins) {
                    // Perfect cardinality + surrogate naming + some JOINs = TRUE_KEY
                    keyType = "TRUE_KEY";
                    confidence = 0.75;
                    log.debug("Column {}.{} classified as TRUE_KEY (perfect cardinality + surrogate naming + JOINs)",
                        analysis.getTableName(), analysis.getColumnName());
                }
                else if (hasHighCardinality && isNeverJoined && !isSurrogateNaming) {
                    // High cardinality but never joined and not a key-like name = ACCIDENTAL_KEY
                    keyType = "ACCIDENTAL_KEY";
                    confidence = 0.7;
                    log.debug("Column {}.{} classified as ACCIDENTAL_KEY (high cardinality but no JOIN usage)",
                        analysis.getTableName(), analysis.getColumnName());
                }
                else if (hasPerfectCardinality && isNeverJoined && !isPrimaryKeyNaming) {
                    // Perfect cardinality but never joined and not PK naming = ACCIDENTAL_KEY
                    keyType = "ACCIDENTAL_KEY";
                    confidence = 0.8;
                    log.debug("Column {}.{} classified as ACCIDENTAL_KEY (perfect cardinality but unused)",
                        analysis.getTableName(), analysis.getColumnName());
                }

                analysis.setKeyType(keyType);
                analysis.setKeyConfidence(BigDecimal.valueOf(confidence));

            } catch (Exception e) {
                log.debug("Could not classify key for {}.{}: {}",
                    analysis.getTableName(), analysis.getColumnName(), e.getMessage());
                analysis.setKeyType("NON_KEY");
                analysis.setKeyConfidence(BigDecimal.ZERO);
            }
        }
    }

    /**
     * Check if a column has a primary key or unique constraint.
     */
    private boolean checkForKeyConstraint(KeyColumnAnalysis analysis, String connectionId) {
        try {
            // Check if the column has an index marked as PRIMARY or UNIQUE
            if (analysis.getIndexName() != null) {
                String indexName = analysis.getIndexName().toLowerCase();
                // Common naming patterns for primary keys and unique constraints
                return indexName.contains("primary") ||
                       indexName.contains("pk_") ||
                       indexName.contains("_pk") ||
                       indexName.contains("unique") ||
                       indexName.contains("uk_") ||
                       indexName.contains("_uk");
            }
            return false;
        } catch (Exception e) {
            log.debug("Could not check key constraint for {}.{}: {}",
                analysis.getTableName(), analysis.getColumnName(), e.getMessage());
            return false;
        }
    }

    /**
     * Detect columns that are good candidates for partitioning.
     * Implements BRAIN-design.md Section 7: "Partitioning candidates"
     *
     * Strategies:
     * - RANGE: Time-based columns (dates, timestamps) with temporal queries
     * - LIST: Low-cardinality categorical columns heavily used in filters
     * - HASH: High-cardinality columns on very large tables for parallel processing
     */
    private void detectPartitioningCandidates(List<KeyColumnAnalysis> analyses) {
        log.info("Detecting partitioning candidates for {} columns", analyses.size());

        for (KeyColumnAnalysis analysis : analyses) {
            try {
                boolean isCandidate = false;
                String partitioningType = null;
                double score = 0.0;
                String recommendation = null;

                String columnName = analysis.getColumnName().toLowerCase();
                Long totalRows = analysis.getTotalRows() != null ? analysis.getTotalRows() : 0L;

                // Strategy 1: RANGE Partitioning (Time-based columns)
                boolean isTimeColumn = PatternUtil.containsPattern(columnName, "(date|time|timestamp|created|updated|modified)");
                boolean hasTemporalQueries = analysis.getWhereCount() >= 5 || analysis.getOrderByCount() >= 3;
                boolean isLargeTable = totalRows > 1_000_000;

                if (isTimeColumn && hasTemporalQueries && isLargeTable) {
                    isCandidate = true;
                    partitioningType = "RANGE";
                    score = 70.0;

                    // Boost score based on usage
                    if (analysis.getWhereCount() >= 10) score += 10;
                    if (analysis.getOrderByCount() >= 5) score += 10;
                    if (totalRows > 10_000_000) score += 10;

                    recommendation = String.format(
                        "RANGE partitioning recommended for column '%s.%s'. " +
                        "Column appears to be time-based and is frequently used in WHERE clauses (%d times) " +
                        "and ORDER BY (%d times) on a large table (%,d rows). " +
                        "Consider partitioning by month or year: " +
                        "PARTITION BY RANGE (YEAR(%s)) or PARTITION BY RANGE (TO_DAYS(%s))",
                        analysis.getTableName(), analysis.getColumnName(),
                        analysis.getWhereCount(), analysis.getOrderByCount(), totalRows,
                        analysis.getColumnName(), analysis.getColumnName()
                    );

                    log.debug("Column {}.{} is RANGE partition candidate (score: {})",
                        analysis.getTableName(), analysis.getColumnName(), score);
                }

                // Strategy 2: LIST Partitioning (Low-cardinality categorical columns)
                boolean hasLowCardinality = analysis.getSelectivity() != null &&
                    analysis.getSelectivity().doubleValue() < 0.01; // < 1% unique values
                boolean isHeavilyFiltered = analysis.getWhereCount() >= 10;
                boolean hasReasonableDistinctCount = analysis.getDistinctCount() != null &&
                    analysis.getDistinctCount() >= 2 && analysis.getDistinctCount() <= 50;

                if (!isCandidate && hasLowCardinality && isHeavilyFiltered &&
                    hasReasonableDistinctCount && isLargeTable) {
                    isCandidate = true;
                    partitioningType = "LIST";
                    score = 65.0;

                    // Boost score
                    if (analysis.getWhereCount() >= 20) score += 10;
                    if (analysis.getDistinctCount() <= 20) score += 10;
                    if (totalRows > 10_000_000) score += 15;

                    recommendation = String.format(
                        "LIST partitioning recommended for column '%s.%s'. " +
                        "Column has low cardinality (%,d distinct values, %.2f%% selectivity) " +
                        "and is heavily used in WHERE clauses (%d times) on a large table (%,d rows). " +
                        "Consider partitioning by distinct values: " +
                        "PARTITION BY LIST (%s) with one partition per major category",
                        analysis.getTableName(), analysis.getColumnName(),
                        analysis.getDistinctCount(),
                        analysis.getSelectivity().doubleValue() * 100,
                        analysis.getWhereCount(), totalRows,
                        analysis.getColumnName()
                    );

                    log.debug("Column {}.{} is LIST partition candidate (score: {})",
                        analysis.getTableName(), analysis.getColumnName(), score);
                }

                // Strategy 3: HASH Partitioning (High-cardinality on very large tables)
                boolean hasHighCardinality = analysis.getSelectivity() != null &&
                    analysis.getSelectivity().doubleValue() > 0.5;
                boolean isVeryLargeTable = totalRows > 10_000_000;
                boolean isHeavilyJoined = analysis.getJoinCount() >= 10;
                boolean isKeyColumn = "TRUE_KEY".equals(analysis.getKeyType()) ||
                                     "SURROGATE_KEY".equals(analysis.getKeyType());

                if (!isCandidate && hasHighCardinality && isVeryLargeTable &&
                    (isHeavilyJoined || isKeyColumn)) {
                    isCandidate = true;
                    partitioningType = "HASH";
                    score = 60.0;

                    // Boost score
                    if (analysis.getJoinCount() >= 20) score += 15;
                    if (totalRows > 50_000_000) score += 15;
                    if (totalRows > 100_000_000) score += 10;

                    recommendation = String.format(
                        "HASH partitioning recommended for column '%s.%s'. " +
                        "Column has high cardinality (%.2f%% selectivity) and the table is very large (%,d rows). " +
                        "%s " +
                        "HASH partitioning can enable parallel query execution and better load distribution. " +
                        "Consider: PARTITION BY HASH (%s) PARTITIONS 8",
                        analysis.getTableName(), analysis.getColumnName(),
                        analysis.getSelectivity().doubleValue() * 100, totalRows,
                        isHeavilyJoined ?
                            String.format("Column is heavily used in JOINs (%d times).", analysis.getJoinCount()) :
                            "Column is a key column.",
                        analysis.getColumnName()
                    );

                    log.debug("Column {}.{} is HASH partition candidate (score: {})",
                        analysis.getTableName(), analysis.getColumnName(), score);
                }

                // Set results
                analysis.setIsPartitionCandidate(isCandidate);
                analysis.setPartitioningType(partitioningType);
                analysis.setPartitioningScore(isCandidate ? BigDecimal.valueOf(score) : null);
                analysis.setPartitioningRecommendation(recommendation);

            } catch (Exception e) {
                log.debug("Could not detect partitioning candidate for {}.{}: {}",
                    analysis.getTableName(), analysis.getColumnName(), e.getMessage());
                analysis.setIsPartitionCandidate(false);
            }
        }

        long candidatesFound = analyses.stream()
            .filter(a -> a.getIsPartitionCandidate() != null && a.getIsPartitionCandidate())
            .count();
        log.info("Found {} partitioning candidates", candidatesFound);
    }

    /**
     * Apply user-defined rules and domain knowledge hints.
     * Implements BRAIN-design.md Section 3.4: "User-Defined Rules & Hints"
     *
     * Rules can:
     * - Exclude tables/columns from analysis
     * - Mark soft foreign keys
     * - Boost/reduce importance scores
     * - Override default recommendations
     */
    private void applyUserRules(List<KeyColumnAnalysis> analyses, String connectionId) {
        List<BrainRule> rules = brainRuleRepository
            .findByConnectionIdAndIsActiveTrueOrderByCreatedAtDesc(connectionId);

        if (rules.isEmpty()) {
            log.debug("No active rules found for connection {}", connectionId);
            return;
        }

        log.info("Applying {} user-defined rules", rules.size());
        int rulesApplied = 0;

        // Index rules by table and column for efficient lookup
        for (KeyColumnAnalysis analysis : analyses) {
            for (BrainRule rule : rules) {
                boolean applies = false;

                // Check if rule applies to this table/column
                if (rule.getTableName() != null && rule.getColumnName() != null) {
                    // Column-specific rule
                    applies = rule.getTableName().equals(analysis.getTableName()) &&
                             rule.getColumnName().equals(analysis.getColumnName());
                } else if (rule.getTableName() != null) {
                    // Table-wide rule
                    applies = rule.getTableName().equals(analysis.getTableName());
                }

                if (!applies) {
                    continue;
                }

                // Apply rule based on type
                switch (rule.getRuleType()) {
                    case "SOFT_FOREIGN_KEY":
                        // Boost JOIN importance
                        int joinBoost = (rule.getConfidence() / 10);  // 0-10 extra JOINs
                        analysis.setJoinCount(analysis.getJoinCount() + joinBoost);
                        analysis.setKeyType("TRUE_KEY");
                        analysis.setKeyConfidence(BigDecimal.valueOf(rule.getConfidence() / 100.0));
                        rulesApplied++;
                        log.debug("Applied SOFT_FOREIGN_KEY rule to {}.{} (boost: {} JOINs)",
                            analysis.getTableName(), analysis.getColumnName(), joinBoost);
                        break;

                    case "HIGH_PRIORITY":
                        // Boost enhanced score later in the pipeline
                        // We'll add a marker that the enhanced scoring can check
                        analysis.setFrequencyScore(BigDecimal.valueOf(rule.getConfidence()));
                        rulesApplied++;
                        log.debug("Applied HIGH_PRIORITY rule to {}.{} (confidence: {})",
                            analysis.getTableName(), analysis.getColumnName(), rule.getConfidence());
                        break;

                    case "LOW_PRIORITY":
                        // Reduce enhanced score
                        analysis.setFrequencyScore(BigDecimal.valueOf(-rule.getConfidence()));
                        rulesApplied++;
                        log.debug("Applied LOW_PRIORITY rule to {}.{}",
                            analysis.getTableName(), analysis.getColumnName());
                        break;

                    case "PARTITIONING_CANDIDATE":
                        // Mark as partitioning candidate with high score
                        analysis.setIsPartitionCandidate(true);
                        analysis.setPartitioningScore(BigDecimal.valueOf(rule.getConfidence()));
                        analysis.setPartitioningRecommendation(rule.getRuleText());
                        rulesApplied++;
                        log.debug("Applied PARTITIONING_CANDIDATE rule to {}.{}",
                            analysis.getTableName(), analysis.getColumnName());
                        break;

                    case "NO_INDEX_RECOMMENDATION":
                        // Mark to suppress index recommendations (handled in anti-pattern detection)
                        // We'll add a flag or skip this column in recommendations
                        log.debug("Applied NO_INDEX_RECOMMENDATION rule to {}.{}",
                            analysis.getTableName(), analysis.getColumnName());
                        rulesApplied++;
                        break;

                    default:
                        log.debug("Unknown rule type: {} for {}.{}",
                            rule.getRuleType(), analysis.getTableName(), analysis.getColumnName());
                }
            }
        }

        log.info("Applied {} rules to analyses", rulesApplied);
    }

    /**
     * Calculate frequency and recency scores
     */
    private void calculateFrequencyAndRecency(List<KeyColumnAnalysis> analyses, LocalDateTime since) {
        log.info("Calculating frequency and recency scores");
        LocalDateTime now = LocalDateTime.now();
        long daysSinceStart = java.time.temporal.ChronoUnit.DAYS.between(since, now);
        if (daysSinceStart == 0) daysSinceStart = 1;

        for (KeyColumnAnalysis analysis : analyses) {
            // Frequency: uses per day
            double usesPerDay = (double) analysis.getTotalUsageCount() / daysSinceStart;
            analysis.setUsesPerDay(BigDecimal.valueOf(usesPerDay));
            analysis.setFrequencyScore(BigDecimal.valueOf(Math.min(100, usesPerDay * 10)));

            // Recency: exponential decay (half-life = 30 days)
            if (analysis.getLastSeenAt() != null) {
                long daysSinceLastUse = java.time.temporal.ChronoUnit.DAYS.between(analysis.getLastSeenAt(), now);
                double decayFactor = Math.pow(0.5, daysSinceLastUse / 30.0);
                double recencyScore = analysis.getImportanceScore().doubleValue() * decayFactor;
                analysis.setRecencyScore(BigDecimal.valueOf(recencyScore));
            } else {
                analysis.setRecencyScore(analysis.getImportanceScore());
            }
        }
    }

    /**
     * Calculate enhanced importance score incorporating selectivity
     */
    private void calculateEnhancedScore(List<KeyColumnAnalysis> analyses) {
        log.info("Calculating enhanced importance scores with selectivity");

        for (KeyColumnAnalysis analysis : analyses) {
            double baseScore = analysis.getImportanceScore().doubleValue();
            double enhancedScore = baseScore;

            // Apply selectivity boost for high-cardinality columns in WHERE/JOIN
            if (analysis.getSelectivity() != null) {
                double selectivity = analysis.getSelectivity().doubleValue();

                // High selectivity (unique-like) columns in WHERE/JOIN get boost
                if ((analysis.getWhereCount() > 0 || analysis.getJoinCount() > 0) && selectivity > 0.5) {
                    double selectivityBoost = 1.0 + (selectivity * 0.5); // Up to 1.5x
                    enhancedScore *= selectivityBoost;
                }

                // Low selectivity columns in GROUP BY get penalty
                if (analysis.getGroupByCount() > 0 && selectivity < 0.01) {
                    enhancedScore *= 0.8; // 20% penalty
                }
            }

            // Apply recency boost
            if (analysis.getRecencyScore() != null) {
                double recencyFactor = baseScore > 0
                    ? (analysis.getRecencyScore().doubleValue() / baseScore)
                    : 1.0;
                enhancedScore *= (0.7 + 0.3 * recencyFactor); // 70-100% based on recency
            }

            // Apply frequency boost
            if (analysis.getFrequencyScore() != null) {
                double freqBoost = Math.min(1.3, 1.0 + (analysis.getFrequencyScore().doubleValue() / 200.0));
                enhancedScore *= freqBoost;
            }

            // Boost columns used across many distinct queries (breadth of usage)
            if (analysis.getDistinctQueriesCount() != null && analysis.getDistinctQueriesCount() > 0) {
                double distinctBoost = Math.min(1.25, 1.0 + (analysis.getDistinctQueriesCount() / 200.0));
                enhancedScore *= distinctBoost;
            }

            // Boost columns that appear in slow queries
            if (analysis.getSlowQueryUsage() != null && analysis.getSlowQueryUsage() > 0) {
                double slowBoost = Math.min(1.4, 1.0 + (analysis.getSlowQueryUsage() / 20.0));
                enhancedScore *= slowBoost;
            }

            // Boost columns that appear in performance history
            if (analysis.getPerformanceHistoryUsage() != null && analysis.getPerformanceHistoryUsage() > 0) {
                double perfBoost = Math.min(1.2, 1.0 + (analysis.getPerformanceHistoryUsage() / 50.0));
                enhancedScore *= perfBoost;
            }

            // Apply NULL ratio penalty for JOIN columns
            if (analysis.getNullRatio() != null && analysis.getJoinCount() > 0) {
                double nullRatio = analysis.getNullRatio().doubleValue();
                if (nullRatio > 0.3) {
                    // High NULL ratio in JOIN columns is problematic
                    enhancedScore *= (1.0 - (nullRatio * 0.3)); // Up to 30% penalty
                }
            }

            analysis.setEnhancedImportanceScore(BigDecimal.valueOf(Math.min(100, enhancedScore)));
        }
    }

    /**
     * Fetch index metadata via {@link QueryExecutorService#getTableIndexes} (dialect-agnostic —
     * dispatches through {@code IntrospectionProvider}, not a Postgres-specific query) and set
     * {@code indexName} on every analysis whose column is covered by an index. This is what
     * {@link #detectAntiPatterns} gates UNINDEXED_* on — before this method actually populated
     * indexName, every column (including primary keys) looked unindexed forever, so PK/UK
     * columns kept generating UNINDEXED_JOIN/UNINDEXED_FILTER noise no matter how they were
     * actually indexed.
     */
    private void enrichWithIndexStats(List<KeyColumnAnalysis> analyses, String connectionId) {
        log.info("Fetching index metadata for {} key column analyses", analyses.size());

        Map<String, List<TableIndex>> indexesByTable = new HashMap<>();
        for (KeyColumnAnalysis analysis : analyses) {
            String tableName = analysis.getTableName();
            if (tableName == null) {
                continue;
            }
            List<TableIndex> indexes = indexesByTable.computeIfAbsent(tableName, t -> {
                try {
                    return queryExecutorService.getTableIndexes(connectionId, t);
                } catch (Exception e) {
                    log.debug("Could not fetch indexes for table {}: {}", t, e.getMessage());
                    return Collections.emptyList();
                }
            });
            if (indexes.isEmpty()) {
                continue;
            }

            String columnName = analysis.getColumnName();
            // Prefer the primary-key index, then any unique index, then any index
            // that covers the column — matches how detectAntiPatterns treats
            // PRIMARY/UNIQUE as strictly stronger signal than a plain index.
            TableIndex best = null;
            for (TableIndex index : indexes) {
                if (index.getColumns() == null || !containsColumnIgnoreCase(index.getColumns(), columnName)) {
                    continue;
                }
                if (index.isPrimary()) {
                    best = index;
                    break;
                }
                if (best == null || (index.isUnique() && !best.isUnique())) {
                    best = index;
                }
            }

            if (best != null) {
                analysis.setIndexName(best.getName());
                if ("NON_KEY".equals(analysis.getKeyType()) || analysis.getKeyType() == null) {
                    if (best.isPrimary()) {
                        analysis.setKeyType("TRUE_KEY");
                    }
                }
            }
        }
    }

    private boolean containsColumnIgnoreCase(List<String> columns, String columnName) {
        if (columnName == null) {
            return false;
        }
        for (String column : columns) {
            if (columnName.equalsIgnoreCase(column)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Detect and recommend composite indexes
     */
    private List<CompositeIndexRecommendation> detectCompositeIndexes(
            String connectionId,
            Map<String, ColumnUsageAggregator> aggregators) {

        log.info("Detecting composite index opportunities");
        List<CompositeIndexRecommendation> recommendations = new ArrayList<>();

        // Group by table and find co-occurring columns
        Map<String, List<String>> tableColumns = new HashMap<>();
        for (Map.Entry<String, ColumnUsageAggregator> entry : aggregators.entrySet()) {
            String tableName = entry.getValue().tableName;
            String columnName = entry.getValue().columnName;
            tableColumns.computeIfAbsent(tableName, k -> new ArrayList<>()).add(columnName);
        }

        // For each table with multiple key columns, suggest composite indexes
        for (Map.Entry<String, List<String>> entry : tableColumns.entrySet()) {
            String tableName = entry.getKey();
            List<String> columns = entry.getValue();

            if (columns.size() >= 2) {
                // Simple heuristic: recommend composite index for top 2-3 columns
                List<String> topColumns = columns.stream()
                    .limit(3)
                    .toList();

                String columnNamesJson = String.format("[\"%s\"]", String.join("\",\"", topColumns));
                String indexName = String.format("idx_%s_%s", tableName, String.join("_", topColumns));
                String sql = String.format("CREATE INDEX %s ON %s(%s);",
                    indexName, tableName, String.join(", ", topColumns));

                CompositeIndexRecommendation rec = CompositeIndexRecommendation.builder()
                    .connectionId(connectionId)
                    .tableName(tableName)
                    .columnNames(columnNamesJson)
                    .recommendationReason("Columns frequently used together in queries")
                    .coOccurrenceCount(5) // Placeholder
                    .estimatedBenefitScore(BigDecimal.valueOf(75.0))
                    .suggestedIndexSql(sql)
                    .priority(CompositeIndexRecommendation.Priority.HIGH)
                    .status(CompositeIndexRecommendation.Status.PENDING)
                    .createdAt(LocalDateTime.now())
                    .build();

                recommendations.add(rec);
            }
        }

        log.info("Generated {} composite index recommendations", recommendations.size());
        return recommendations;
    }

    /**
     * Retrieves top values for a column from ColumnProfile and converts to DTO.
     *
     * @param connectionId Database connection ID
     * @param analysis Key column analysis
     * @return List of TopValueInfo DTOs
     */
    private List<TopValueInfo> getTopValuesForColumn(String connectionId, KeyColumnAnalysis analysis) {
        try {
            Optional<ColumnProfile> profileOpt = columnProfileRepository
                .findByConnectionIdAndTableNameAndColumnName(
                    connectionId, analysis.getTableName(), analysis.getColumnName()
                );

            if (profileOpt.isPresent() && profileOpt.get().getTopValues() != null) {
                ColumnProfile profile = profileOpt.get();
                List<SkewAnalysisService.TopValue> topValues =
                    skewAnalysisService.parseTopValues(profile.getTopValues());

                // Convert to DTOs
                return topValues.stream()
                    .map(tv -> TopValueInfo.builder()
                        .value(tv.getValue())
                        .count(tv.getCount())
                        .percentage(tv.getPercentage())
                        .build())
                    .collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.debug("Could not retrieve top values for {}.{}: {}",
                analysis.getTableName(), analysis.getColumnName(), e.getMessage());
        }

        return new ArrayList<>();
    }

    /**
     * Calculate ML-based prediction score (heuristic approach)
     */
    private void calculateMLPredictionScore(List<KeyColumnAnalysis> analyses) {
        log.info("Calculating ML prediction scores");

        for (KeyColumnAnalysis analysis : analyses) {
            // Heuristic-based "ML" score using multiple features
            double mlScore = 0.0;

            // Feature 1: Usage frequency (0-30 points)
            mlScore += Math.min(30, analysis.getTotalUsageCount() * 2);

            // Feature 2: JOIN importance (0-25 points)
            mlScore += Math.min(25, analysis.getJoinCount() * 5);

            // Feature 3: Cardinality impact (0-20 points)
            if (analysis.getSelectivity() != null) {
                double selectivity = analysis.getSelectivity().doubleValue();
                if (selectivity > 0.8) mlScore += 20; // High cardinality
                else if (selectivity > 0.3) mlScore += 10; // Medium
            }

            // Feature 4: WHERE clause usage (0-15 points)
            mlScore += Math.min(15, analysis.getWhereCount() * 3);

            // Feature 5: Anti-pattern presence (0-10 points)
            if (analysis.getHasAntiPatterns()) {
                mlScore += analysis.getAntiPatternCount() * 5;
            }

            analysis.setMlPredictionScore(BigDecimal.valueOf(Math.min(100, mlScore)));
        }
    }

    /**
     * Inner class for aggregating column usage
     */
    private static class ColumnUsageAggregator {
        String tableName;
        String columnName;
        int joinCount = 0;
        int whereCount = 0;
        int groupByCount = 0;
        int orderByCount = 0;
        int totalUsageCount = 0;
        int slowQueryUsage = 0;
        int lineageUsage = 0;
        int performanceHistoryUsage = 0;
        LocalDateTime firstSeenAt;
        LocalDateTime lastSeenAt;
        int distinctQueriesCount = 0;
        Set<String> distinctQueries = new HashSet<>();
        String schemaKeyType;
        Double schemaKeyConfidence;

        ColumnUsageAggregator(String tableName, String columnName) {
            // Normalize to lowercase to prevent case-sensitive duplicates
            this.tableName = tableName != null ? tableName.toLowerCase() : null;
            this.columnName = columnName != null ? columnName.toLowerCase() : null;
            this.firstSeenAt = null;
            this.lastSeenAt = null;
        }

        void recordUsage(LocalDateTime usageTime, String querySignature) {
            if (usageTime != null) {
                if (firstSeenAt == null || usageTime.isBefore(firstSeenAt)) {
                    firstSeenAt = usageTime;
                }
                if (lastSeenAt == null || usageTime.isAfter(lastSeenAt)) {
                    lastSeenAt = usageTime;
                }
            }

            if (querySignature != null && distinctQueries.add(querySignature)) {
                distinctQueriesCount++;
            }
        }
    }

    /**
     * Get the user database name from the connection configuration
     */
    private String getUserDatabase(String connectionId) {
        if (connectionId == null || connectionId.isEmpty()) {
            log.debug("No connection ID provided for database retrieval");
            return null;
        }

        try {
            var connectionDetails = credentialService.getDecryptedConnection(connectionId);
            if (connectionDetails == null) {
                log.debug("Connection details not found for connection {}", connectionId);
                return null;
            }
            String database = connectionDetails.getDatabase();
            log.info("Retrieved database name '{}' for connection {}", database, connectionId);
            return database != null ? database.toLowerCase() : null;
        } catch (RuntimeException e) {
            // Connection not found or decryption failed - this is expected for test connections
            log.debug("Could not retrieve database name for connection {}: {}", connectionId, e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("Unexpected error retrieving database name for connection {}: {}", connectionId, e.getMessage());
            return null;
        }
    }

    /**
     * Check if a table is from a system schema (should be excluded from analysis).
     * Filters out:
     * - information_schema (MySQL/PostgreSQL system catalog)
     * - mysql (MySQL system database)
     * - sys (MySQL system database)
     * - performance_schema (MySQL performance monitoring)
     * - pg_catalog (PostgreSQL system catalog)
     * - Tables that don't match the user's database schema
     */
    private boolean isSystemTable(String tableName, String userDatabase) {
        if (tableName == null) {
            return true;
        }

        String tableNameLower = tableName.toLowerCase();

        // Common system schemas across databases
        if (tableNameLower.startsWith("information_schema.") ||
            tableNameLower.startsWith("mysql.") ||
            tableNameLower.startsWith("sys.") ||
            tableNameLower.startsWith("performance_schema.") ||
            tableNameLower.startsWith("pg_catalog.") ||
            tableNameLower.startsWith("pg_temp")) {
            return true;
        }

        // For unqualified table names (no schema prefix), check if table name itself is a system table
        if (!tableNameLower.contains(".")) {
            // System schema names
            if (tableNameLower.equals("information_schema") ||
                tableNameLower.equals("mysql") ||
                tableNameLower.equals("sys") ||
                tableNameLower.equals("performance_schema") ||
                tableNameLower.equals("pg_catalog")) {
                return true;
            }
            // Common system table names that appear unqualified
            if (tableNameLower.equals("tables") ||
                tableNameLower.equals("columns") ||
                tableNameLower.equals("schemata") ||
                tableNameLower.equals("views") ||
                tableNameLower.equals("routines") ||
                tableNameLower.equals("statistics") ||
                tableNameLower.equals("user") ||
                tableNameLower.equals("db")) {
                log.debug("Skipping likely system table (unqualified): {}", tableName);
                return true;
            }
        }

        // If we have the user database, filter to only tables from that database
        if (userDatabase != null && !userDatabase.isEmpty()) {
            // Check if table is qualified with a schema/database prefix
            if (tableNameLower.contains(".")) {
                String schemaPrefix = tableNameLower.split("\\.")[0];
                // Allow if it matches the user database
                return !schemaPrefix.equals(userDatabase);
            }
            // Unqualified tables are assumed to be from the current database - allow them
            return false;
        }

        // No user database info - allow by default (conservative approach)
        return false;
    }
}
