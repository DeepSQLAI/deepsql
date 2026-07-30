package com.dbaagent.service.brain.classification;

import com.dbaagent.model.*;
import com.dbaagent.repository.*;
import com.dbaagent.service.brain.classification.AccessPatternClassificationService.TableAccessPattern;
import com.dbaagent.service.brain.classification.AntiPatternDetectionService.TableAntiPatterns;
import com.dbaagent.service.brain.classification.AntiPatternDetectionService.DetectedAntiPattern;
import com.dbaagent.service.brain.classification.TemporalClassificationService.TemporalClassification;
import com.dbaagent.service.brain.classification.TemporalClassificationService.TimestampColumnInfo;
import com.dbaagent.service.brain.classification.TableHealthScoreService.TableHealthScore;
import com.dbaagent.service.brain.classification.BusinessDomainClassificationService.DomainClassification;
import com.dbaagent.service.brain.classification.DataSensitivityClassificationService.SensitivityClassification;
import com.dbaagent.service.brain.classification.DataSensitivityClassificationService.SensitiveColumnInfo;
import com.dbaagent.service.brain.classification.PartitionReadinessService.PartitionReadiness;
import com.dbaagent.service.brain.classification.PartitionReadinessService.PartitionKeyCandidate;
import com.dbaagent.service.brain.classification.DataLifecycleClassificationService.DataLifecycle;
import com.dbaagent.service.brain.classification.CacheAffinityClassificationService.CacheAffinity;
import com.dbaagent.service.brain.classification.QueryComplexityClassificationService.QueryComplexity;
import com.dbaagent.service.brain.classification.SchemaEvolutionRiskService.SchemaEvolutionRisk;
import com.dbaagent.service.brain.classification.DenormalizationCandidateService.DenormalizationCandidate;
import com.dbaagent.service.brain.classification.DenormalizationCandidateService.JoinCandidate;
import com.dbaagent.service.brain.classification.CostAttributionService.CostAttribution;
import com.dbaagent.service.brain.classification.ShardingReadinessService.ShardingReadiness;
import com.dbaagent.service.brain.classification.ShardingReadinessService.ShardKeyCandidate;
import com.dbaagent.service.brain.classification.DataQualityScoreService.DataQualityScore;
import com.dbaagent.service.brain.classification.DependencyCriticalityService.DependencyCriticality;
import com.dbaagent.service.brain.classification.GrowthPredictionService.GrowthPrediction;
import com.dbaagent.service.QueryExecutorService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Minimum confidence threshold for inferred relationships to be included
 * in schema classification. Relationships below this threshold are ignored.
 */
@SuppressWarnings("unused")

/**
 * Service for classifying database schema patterns.
 * Implements BRAIN-design.md Section 6: "Schema Classification"
 *
 * Detects patterns:
 * - Star Schema
 * - Snowflake Schema
 * - Hybrid (Star + Snowflake)
 * - OLTP-style normalized
 * - Reporting / data-mart
 * - Anti-pattern (cycles, unclear ownership)
 *
 * Enhanced with:
 * - Access Pattern Classification (READ_HEAVY, WRITE_HEAVY, etc.)
 * - Anti-Pattern Detection (GOD_TABLE, SPARSE_TABLE, etc.)
 * - Temporal Classification (TIME_SERIES, SCD_TYPE_2, etc.)
 * - Table Health Scores
 * - Business Domain Classification
 * - Data Sensitivity Classification
 * - Partition Readiness Evaluation
 * - Relationship Classification
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SchemaClassificationService {

    private final SchemaClassificationRepository schemaClassificationRepository;
    private final TableClassificationRepository tableClassificationRepository;
    private final TableRelationshipClassificationRepository tableRelationshipClassificationRepository;
    private final KeyColumnAnalysisRepository keyColumnAnalysisRepository;
    private final ColumnProfileRepository columnProfileRepository;
    private final QueryExecutorService queryExecutorService;
    private final InferredTableRelationshipRepository inferredTableRelationshipRepository;

    // Enhanced classification services (Phase 1-5)
    private final AccessPatternClassificationService accessPatternService;
    private final AntiPatternDetectionService antiPatternService;
    private final TemporalClassificationService temporalService;
    private final TableHealthScoreService healthScoreService;
    private final BusinessDomainClassificationService domainService;
    private final DataSensitivityClassificationService sensitivityService;
    private final PartitionReadinessService partitionService;
    private final RelationshipClassificationService relationshipService;

    // Phase 6 classification services
    private final DataLifecycleClassificationService lifecycleService;
    private final CacheAffinityClassificationService cacheAffinityService;
    private final QueryComplexityClassificationService queryComplexityService;
    private final SchemaEvolutionRiskService schemaRiskService;
    private final DenormalizationCandidateService denormalizationService;
    private final CostAttributionService costService;
    private final ShardingReadinessService shardingService;
    private final DataQualityScoreService dataQualityService;
    private final DependencyCriticalityService dependencyCriticalityService;
    private final GrowthPredictionService growthPredictionService;

    /**
     * Minimum confidence threshold for inferred relationships.
     * Set to 10 to include column-pattern-based inferences (which have confidence ~15).
     */
    private static final BigDecimal MIN_INFERRED_CONFIDENCE = BigDecimal.valueOf(10);
    private static final long CLASSIFICATION_TASK_TIMEOUT_SECONDS = 90;

    // Caps how many of the ~16 enhanced classification services materialize
    // their full per-table metadata into the heap at once. The executor below is
    // virtual-thread-per-task (unbounded), so without this gate every service
    // fans out across the ENTIRE schema simultaneously; on large (~600-table)
    // schemas the combined materialization OOMs the -Xmx3g heap, and
    // -XX:+ExitOnOutOfMemoryError then kills the JVM silently (exit 0, no logs),
    // orphaning the brain-init-stage task so db-scheduler revives it ~30 min
    // later — the observed crash loop. 4 keeps peak memory to ~4 services' worth;
    // raise via brain.classification.max-concurrency if the heap has headroom.
    @org.springframework.beans.factory.annotation.Value("${brain.classification.max-concurrency:4}")
    private int maxClassificationConcurrency = 4;

    private volatile Semaphore classificationGate;

    private Semaphore classificationGate() {
        Semaphore gate = classificationGate;
        if (gate == null) {
            synchronized (this) {
                gate = classificationGate;
                if (gate == null) {
                    int permits = maxClassificationConcurrency > 0 ? maxClassificationConcurrency : 4;
                    gate = new Semaphore(permits);
                    classificationGate = gate;
                }
            }
        }
        return gate;
    }

    /**
     * Classify the database schema pattern.
     */
    public SchemaClassification classifySchema(String connectionId) {
        log.info("Classifying schema for connection: {}", connectionId);

        LocalDateTime now = LocalDateTime.now();
        Map<String, Object> reasoning = new HashMap<>();

        // Step 1: Build table graph from Key Columns Analysis
        TableGraph graph = buildTableGraph(connectionId);
        log.info("Built table graph with {} tables and {} relationships",
            graph.getTables().size(), graph.getRelationships().size());

        // Step 2: Classify individual tables
        List<TableNode> classifiedTables = classifyTables(graph, reasoning);
        log.info("Classified tables: {} facts, {} dimensions, {} orphaned",
            classifiedTables.stream().filter(t -> "FACT".equals(t.getRole())).count(),
            classifiedTables.stream().filter(t -> "DIMENSION".equals(t.getRole())).count(),
            classifiedTables.stream().filter(t -> "ORPHANED".equals(t.getRole())).count());

        // Step 3: Detect global pattern
        String globalPattern = detectGlobalPattern(classifiedTables, graph, reasoning);
        BigDecimal confidence = calculateConfidence(globalPattern, classifiedTables, graph);

        // Step 4: Calculate metrics
        int factCount = (int) classifiedTables.stream().filter(t -> "FACT".equals(t.getRole())).count();
        int dimCount = (int) classifiedTables.stream().filter(t -> "DIMENSION".equals(t.getRole())).count();
        int bridgeCount = (int) classifiedTables.stream().filter(t -> "BRIDGE".equals(t.getRole())).count();
        int orphanedCount = (int) classifiedTables.stream().filter(t -> "ORPHANED".equals(t.getRole())).count();

        BigDecimal avgFanOut = calculateAvgFanOut(classifiedTables, graph);
        int maxDepth = calculateMaxDimensionDepth(classifiedTables);
        String normalization = estimateNormalizationLevel(graph);

        boolean hasCycles = detectCycles(graph);
        boolean unclearOwnership = orphanedCount > (classifiedTables.size() * 0.3);  // >30% orphaned
        int missingFKs = estimateMissingForeignKeys(graph);

        // Step 5: Run enhanced classification services IN PARALLEL for performance
        log.info("Running enhanced classification services in parallel...");
        long classificationStartTime = System.currentTimeMillis();

        // Use virtual threads for parallel execution (Java 21+)
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        try {
            // Launch all classification tasks in parallel
            CompletableFuture<Map<String, TableAccessPattern>> accessPatternsFuture = runClassificationTask(
                executor,
                "classify access patterns",
                () -> {
                    Map<String, TableAccessPattern> result = accessPatternService.classifyAccessPatterns(connectionId);
                    log.info("Classified access patterns for {} tables", result.size());
                    return result;
                });

            CompletableFuture<Map<String, TableAntiPatterns>> antiPatternsFuture = runClassificationTask(
                executor,
                "detect anti-patterns",
                () -> {
                    Map<String, TableAntiPatterns> result = antiPatternService.detectAntiPatterns(connectionId);
                    log.info("Detected anti-patterns for {} tables", result.size());
                    return result;
                });

            CompletableFuture<Map<String, TemporalClassification>> temporalFuture = runClassificationTask(
                executor,
                "classify temporal patterns",
                () -> {
                    Map<String, TemporalClassification> result = temporalService.classifyTemporalPatterns(connectionId);
                    log.info("Classified temporal patterns for {} tables", result.size());
                    return result;
                });

            CompletableFuture<Map<String, DomainClassification>> domainFuture = runClassificationTask(
                executor,
                "classify business domains",
                () -> {
                    Map<String, DomainClassification> result = domainService.classifyBusinessDomains(connectionId);
                    log.info("Classified business domains for {} tables", result.size());
                    return result;
                });

            CompletableFuture<Map<String, SensitivityClassification>> sensitivityFuture = runClassificationTask(
                executor,
                "classify data sensitivity",
                () -> {
                    Map<String, SensitivityClassification> result = sensitivityService.classifyDataSensitivity(connectionId);
                    log.info("Classified data sensitivity for {} tables", result.size());
                    return result;
                });

            CompletableFuture<Map<String, PartitionReadiness>> partitionFuture = runClassificationTask(
                executor,
                "evaluate partition readiness",
                () -> {
                    Map<String, PartitionReadiness> result = partitionService.evaluatePartitionReadiness(connectionId);
                    log.info("Evaluated partition readiness for {} tables", result.size());
                    return result;
                });

            CompletableFuture<Map<String, DataLifecycle>> lifecycleFuture = runClassificationTask(
                executor,
                "classify data lifecycle",
                () -> {
                    Map<String, DataLifecycle> result = lifecycleService.classifyDataLifecycle(connectionId);
                    log.info("Classified data lifecycle for {} tables", result.size());
                    return result;
                });

            CompletableFuture<Map<String, CacheAffinity>> cacheAffinityFuture = runClassificationTask(
                executor,
                "classify cache affinity",
                () -> {
                    Map<String, CacheAffinity> result = cacheAffinityService.classifyCacheAffinity(connectionId);
                    log.info("Classified cache affinity for {} tables", result.size());
                    return result;
                });

            CompletableFuture<Map<String, QueryComplexity>> queryComplexityFuture = runClassificationTask(
                executor,
                "classify query complexity",
                () -> {
                    Map<String, QueryComplexity> result = queryComplexityService.classifyQueryComplexity(connectionId);
                    log.info("Classified query complexity for {} tables", result.size());
                    return result;
                });

            CompletableFuture<Map<String, SchemaEvolutionRisk>> schemaRiskFuture = runClassificationTask(
                executor,
                "assess schema evolution risk",
                () -> {
                    Map<String, SchemaEvolutionRisk> result = schemaRiskService.assessSchemaEvolutionRisk(connectionId);
                    log.info("Assessed schema evolution risk for {} tables", result.size());
                    return result;
                });

            CompletableFuture<Map<String, DenormalizationCandidate>> denormalizationFuture = runClassificationTask(
                executor,
                "identify denormalization candidates",
                () -> {
                    Map<String, DenormalizationCandidate> result = denormalizationService.identifyDenormalizationCandidates(connectionId);
                    log.info("Identified denormalization candidates for {} tables", result.size());
                    return result;
                });

            CompletableFuture<Map<String, CostAttribution>> costFuture = runClassificationTask(
                executor,
                "calculate cost attribution",
                () -> {
                    Map<String, CostAttribution> result = costService.calculateCostAttribution(connectionId);
                    log.info("Calculated cost attribution for {} tables", result.size());
                    return result;
                });

            CompletableFuture<Map<String, ShardingReadiness>> shardingFuture = runClassificationTask(
                executor,
                "assess sharding readiness",
                () -> {
                    Map<String, ShardingReadiness> result = shardingService.assessShardingReadiness(connectionId);
                    log.info("Assessed sharding readiness for {} tables", result.size());
                    return result;
                });

            CompletableFuture<Map<String, DataQualityScore>> dataQualityFuture = runClassificationTask(
                executor,
                "calculate data quality",
                () -> {
                    Map<String, DataQualityScore> result = dataQualityService.calculateDataQuality(connectionId);
                    log.info("Calculated data quality for {} tables", result.size());
                    return result;
                });

            CompletableFuture<Map<String, DependencyCriticality>> dependencyFuture = runClassificationTask(
                executor,
                "assess dependency criticality",
                () -> {
                    Map<String, DependencyCriticality> result = dependencyCriticalityService.assessDependencyCriticality(connectionId);
                    log.info("Assessed dependency criticality for {} tables", result.size());
                    return result;
                });

            CompletableFuture<Map<String, GrowthPrediction>> growthFuture = runClassificationTask(
                executor,
                "predict growth",
                () -> {
                    Map<String, GrowthPrediction> result = growthPredictionService.predictGrowth(connectionId);
                    log.info("Predicted growth for {} tables", result.size());
                    return result;
                });

            // Wait for all futures to complete and collect results
            CompletableFuture.allOf(
                accessPatternsFuture, antiPatternsFuture, temporalFuture, domainFuture,
                sensitivityFuture, partitionFuture, lifecycleFuture, cacheAffinityFuture,
                queryComplexityFuture, schemaRiskFuture, denormalizationFuture, costFuture,
                shardingFuture, dataQualityFuture, dependencyFuture, growthFuture
            ).join();

            // Get results from futures
            Map<String, TableAccessPattern> accessPatterns = accessPatternsFuture.join();
            Map<String, TableAntiPatterns> antiPatterns = antiPatternsFuture.join();
            Map<String, TemporalClassification> temporalClassifications = temporalFuture.join();
            Map<String, DomainClassification> domainClassifications = domainFuture.join();
            Map<String, SensitivityClassification> sensitivityClassifications = sensitivityFuture.join();
            Map<String, PartitionReadiness> partitionReadiness = partitionFuture.join();
            Map<String, DataLifecycle> lifecycleClassifications = lifecycleFuture.join();
            Map<String, CacheAffinity> cacheAffinityClassifications = cacheAffinityFuture.join();
            Map<String, QueryComplexity> queryComplexityClassifications = queryComplexityFuture.join();
            Map<String, SchemaEvolutionRisk> schemaRiskClassifications = schemaRiskFuture.join();
            Map<String, DenormalizationCandidate> denormalizationCandidates = denormalizationFuture.join();
            Map<String, CostAttribution> costAttributions = costFuture.join();
            Map<String, ShardingReadiness> shardingReadinessMap = shardingFuture.join();
            Map<String, DataQualityScore> dataQualityScores = dataQualityFuture.join();
            Map<String, DependencyCriticality> dependencyCriticalities = dependencyFuture.join();
            Map<String, GrowthPrediction> growthPredictions = growthFuture.join();

            long classificationDuration = System.currentTimeMillis() - classificationStartTime;
            log.info("Completed all classification services in {} ms (parallel execution)", classificationDuration);

            // Calculate aggregate statistics
        int tablesWithAntiPatterns = (int) antiPatterns.values().stream()
            .filter(ap -> ap.getAntiPatternCount() > 0)
            .count();
        int criticalAntiPatterns = (int) antiPatterns.values().stream()
            .filter(ap -> "CRITICAL".equals(ap.getOverallSeverity()) || "HIGH".equals(ap.getOverallSeverity()))
            .count();
        int piiTablesCount = (int) sensitivityClassifications.values().stream()
            .filter(sc -> !"PUBLIC".equals(sc.getSensitivityLevel()))
            .count();
        int partitionCandidatesCount = (int) partitionReadiness.values().stream()
            .filter(pr -> pr.getReadinessLevel() != null && pr.getReadinessLevel().startsWith("PARTITION_CANDIDATE"))
            .count();

        // Step 6: Save schema classification
        SchemaClassification schemaClassification = SchemaClassification.builder()
            .connectionId(connectionId)
            .globalPattern(globalPattern)
            .confidenceScore(confidence)
            .totalTables(classifiedTables.size())
            .factTables(factCount)
            .dimensionTables(dimCount)
            .bridgeTables(bridgeCount)
            .orphanedTables(orphanedCount)
            .avgFanOut(avgFanOut)
            .maxDimensionDepth(maxDepth)
            .normalizationLevel(normalization)
            .hasCycles(hasCycles)
            .unclearOwnership(unclearOwnership)
            .missingForeignKeys(missingFKs)
            .classificationReasoning(reasoning)
            .tablesWithAntiPatterns(tablesWithAntiPatterns)
            .criticalAntiPatterns(criticalAntiPatterns)
            .piiTablesCount(piiTablesCount)
            .partitionCandidatesCount(partitionCandidatesCount)
            .classifiedAt(now)
            .build();

        schemaClassificationRepository.save(schemaClassification);

        // Step 7: Save individual table classifications with enhanced data
        BigDecimal totalHealthScore = BigDecimal.ZERO;
        int healthScoreCount = 0;

        for (TableNode table : classifiedTables) {
            String tableNameLower = table.getTableName().toLowerCase();

            // Get enhanced classifications for this table
            TableAccessPattern accessPattern = accessPatterns.get(tableNameLower);
            TableAntiPatterns tableAntiPatterns = antiPatterns.get(tableNameLower);
            TemporalClassification temporal = temporalClassifications.get(tableNameLower);
            DomainClassification domain = domainClassifications.get(tableNameLower);
            SensitivityClassification sensitivity = sensitivityClassifications.get(tableNameLower);
            PartitionReadiness partition = partitionReadiness.get(tableNameLower);

            // Phase 6 classifications
            DataLifecycle lifecycle = lifecycleClassifications.get(tableNameLower);
            CacheAffinity cacheAff = cacheAffinityClassifications.get(tableNameLower);
            QueryComplexity queryComp = queryComplexityClassifications.get(tableNameLower);
            SchemaEvolutionRisk schemaRisk = schemaRiskClassifications.get(tableNameLower);
            DenormalizationCandidate denorm = denormalizationCandidates.get(tableNameLower);
            CostAttribution cost = costAttributions.get(tableNameLower);
            ShardingReadiness sharding = shardingReadinessMap.get(tableNameLower);
            DataQualityScore quality = dataQualityScores.get(tableNameLower);
            DependencyCriticality depCrit = dependencyCriticalities.get(tableNameLower);
            GrowthPrediction growth = growthPredictions.get(tableNameLower);

            // Build anti-patterns list for JSON
            List<Map<String, Object>> antiPatternsList = new ArrayList<>();
            if (tableAntiPatterns != null && tableAntiPatterns.getAntiPatterns() != null) {
                for (DetectedAntiPattern ap : tableAntiPatterns.getAntiPatterns()) {
                    antiPatternsList.add(Map.of(
                        "type", ap.getType(),
                        "severity", ap.getSeverity(),
                        "details", ap.getDetails() != null ? ap.getDetails() : Map.of(),
                        "recommendation", ap.getRecommendation() != null ? ap.getRecommendation() : ""
                    ));
                }
            }

            // Build timestamp columns list for JSON
            List<Map<String, Object>> timestampColumnsList = new ArrayList<>();
            if (temporal != null && temporal.getTimestampColumns() != null) {
                for (TimestampColumnInfo tsc : temporal.getTimestampColumns()) {
                    timestampColumnsList.add(Map.of(
                        "column", tsc.getColumn(),
                        "type", tsc.getType(),
                        "indexed", tsc.isIndexed()
                    ));
                }
            }

            // Build domain indicators list for JSON
            List<Map<String, Object>> domainIndicatorsList = new ArrayList<>();
            if (domain != null && domain.getIndicators() != null) {
                domainIndicatorsList = domain.getIndicators();
            }

            // Build sensitive columns list for JSON
            List<Map<String, Object>> sensitiveColumnsList = new ArrayList<>();
            if (sensitivity != null && sensitivity.getSensitiveColumns() != null) {
                for (SensitiveColumnInfo sc : sensitivity.getSensitiveColumns()) {
                    sensitiveColumnsList.add(Map.of(
                        "column", sc.getColumn(),
                        "type", sc.getSensitivityType(),
                        "data_type", sc.getDataType(),
                        "confidence", sc.getConfidence()
                    ));
                }
            }

            // Build partition key candidates list for JSON
            List<Map<String, Object>> partitionCandidatesList = new ArrayList<>();
            if (partition != null && partition.getPartitionKeyCandidates() != null) {
                for (PartitionKeyCandidate pk : partition.getPartitionKeyCandidates()) {
                    partitionCandidatesList.add(Map.of(
                        "column", pk.getColumn(),
                        "type", pk.getPartitionType(),
                        "cardinality", pk.getCardinality(),
                        "benefit_estimate", pk.getBenefitEstimate()
                    ));
                }
            }

            // Build denormalization candidates list for JSON
            List<Map<String, Object>> denormCandidatesList = new ArrayList<>();
            if (denorm != null && denorm.getJoinCandidates() != null) {
                for (JoinCandidate jc : denorm.getJoinCandidates()) {
                    denormCandidatesList.add(Map.of(
                        "target_table", jc.getTargetTable(),
                        "join_column", jc.getJoinColumn() != null ? jc.getJoinColumn() : "",
                        "join_frequency", jc.getJoinFrequency() != null ? jc.getJoinFrequency() : 0,
                        "strategy", jc.getStrategy() != null ? jc.getStrategy() : ""
                    ));
                }
            }

            // Build shard key candidates list for JSON
            List<Map<String, Object>> shardKeyCandidatesList = new ArrayList<>();
            if (sharding != null && sharding.getShardKeyCandidates() != null) {
                for (ShardKeyCandidate sk : sharding.getShardKeyCandidates()) {
                    shardKeyCandidatesList.add(Map.of(
                        "column", sk.getColumnName(),
                        "suitability_score", sk.getSuitabilityScore(),
                        "pattern_match", sk.getPatternMatch() != null ? sk.getPatternMatch() : ""
                    ));
                }
            }

            TableClassification tc = TableClassification.builder()
                .schemaClassificationId(schemaClassification.getId())
                .connectionId(connectionId)
                .tableName(tableNameLower)  // Use lowercase to prevent case-sensitive duplicates
                .tableRole(table.getRole())
                .confidenceScore(table.getConfidence())
                .rowCount(table.getRowCount())
                .columnCount(table.getColumnCount())
                .foreignKeyCount(table.getForeignKeyCount())
                .inboundJoinCount(table.getInboundJoinCount())
                .outboundJoinCount(table.getOutboundJoinCount())
                .isNormalized(table.getIsNormalized())
                .hasSurrogateKey(table.getHasSurrogateKey())
                .granularityLevel(table.getGranularity())
                .depthFromFact(table.getDepthFromFact())
                .clusterId(table.getClusterId())
                .classificationReasoning(table.getReasoning())
                // Phase 1: Access patterns
                .accessPattern(accessPattern != null ? accessPattern.getAccessPattern() : null)
                .readCount(accessPattern != null ? accessPattern.getReadCount() : 0L)
                .writeCount(accessPattern != null ? accessPattern.getWriteCount() : 0L)
                .updateCount(accessPattern != null ? accessPattern.getUpdateCount() : 0L)
                .deleteCount(accessPattern != null ? accessPattern.getDeleteCount() : 0L)
                .readWriteRatio(accessPattern != null ? accessPattern.getReadWriteRatio() : null)
                // Phase 1: Anti-patterns
                .antiPatterns(antiPatternsList.isEmpty() ? null : antiPatternsList)
                .antiPatternCount(tableAntiPatterns != null ? tableAntiPatterns.getAntiPatternCount() : 0)
                .antiPatternSeverity(tableAntiPatterns != null ? tableAntiPatterns.getOverallSeverity() : "NONE")
                // Phase 2: Temporal classification
                .temporalType(temporal != null ? temporal.getTemporalType() : "NONE")
                .hasTimestampColumns(temporal != null && temporal.isHasTimestampColumns())
                .timestampColumns(timestampColumnsList.isEmpty() ? null : timestampColumnsList)
                // Phase 3: Business domain
                .businessDomain(domain != null ? domain.getBusinessDomain() : "UNKNOWN")
                .domainConfidence(domain != null ? domain.getConfidence() : null)
                .domainIndicators(domainIndicatorsList.isEmpty() ? null : domainIndicatorsList)
                // Phase 4: Data sensitivity
                .sensitivityLevel(sensitivity != null ? sensitivity.getSensitivityLevel() : "PUBLIC")
                .sensitiveColumns(sensitiveColumnsList.isEmpty() ? null : sensitiveColumnsList)
                .sensitivityConfidence(sensitivity != null ? sensitivity.getConfidence() : null)
                // Phase 4: Partition readiness
                .partitionReadiness(partition != null ? partition.getReadinessLevel() : "NOT_PARTITION_CANDIDATE")
                .partitionKeyCandidates(partitionCandidatesList.isEmpty() ? null : partitionCandidatesList)
                .estimatedPartitionBenefit(partition != null ? partition.getEstimatedBenefit() : null)
                // Phase 6: Data lifecycle
                .dataLifecycle(lifecycle != null ? lifecycle.getLifecycleStage() : null)
                .daysSinceLastAccess(lifecycle != null ? lifecycle.getDaysSinceLastAccess() : null)
                .accessFrequencyPerDay(lifecycle != null ? lifecycle.getAccessFrequencyPerDay() : null)
                // Phase 6: Cache affinity
                .cacheAffinity(cacheAff != null ? cacheAff.getAffinityLevel() : null)
                .cacheAffinityScore(cacheAff != null ? cacheAff.getAffinityScore() : null)
                .estimatedCacheHitRate(cacheAff != null && cacheAff.getEstimatedCacheHitRate() != null
                    ? BigDecimal.valueOf(cacheAff.getEstimatedCacheHitRate()) : null)
                // Phase 6: Query complexity
                .queryComplexity(queryComp != null ? queryComp.getComplexityLevel() : null)
                .avgQueryComplexityScore(queryComp != null ? queryComp.getAvgComplexityScore() : null)
                .analyzedQueryCount(queryComp != null ? queryComp.getQueryCount() : null)
                // Phase 6: Schema evolution risk
                .schemaEvolutionRisk(schemaRisk != null ? schemaRisk.getRiskLevel() : null)
                .schemaRiskScore(schemaRisk != null ? schemaRisk.getRiskScore() : null)
                .estimatedDdlImpact(schemaRisk != null ? schemaRisk.getEstimatedDdlImpact() : null)
                .schemaRiskFactors(schemaRisk != null ? convertToObjectMap(schemaRisk.getFactors()) : null)
                // Phase 6: Denormalization
                .denormalizationCandidate(denorm != null ? denorm.getIsCandidate() : false)
                .denormalizationBenefitScore(denorm != null ? denorm.getBenefitScore() : null)
                .denormalizationCandidates(denormCandidatesList.isEmpty() ? null : denormCandidatesList)
                // Phase 6: Cost attribution
                .costTier(cost != null ? cost.getCostTier() : null)
                .estimatedMonthlyCost(cost != null ? cost.getEstimatedMonthlyCost() : null)
                .costPercentageOfDb(cost != null ? cost.getCostPercentageOfDb() : null)
                .costBreakdown(cost != null ? convertBigDecimalMapToObjectMap(cost.getCostBreakdown()) : null)
                // Phase 6: Sharding readiness
                .shardingReadiness(sharding != null ? sharding.getReadinessLevel() : null)
                .shardingReadinessScore(sharding != null ? sharding.getReadinessScore() : null)
                .recommendedShardCount(sharding != null ? sharding.getRecommendedShardCount() : null)
                .shardKeyCandidates(shardKeyCandidatesList.isEmpty() ? null : shardKeyCandidatesList)
                // Phase 6: Data quality
                .dataQualityLevel(quality != null ? quality.getQualityLevel() : null)
                .dataQualityScore(quality != null ? quality.getOverallScore() : null)
                .qualityDimensions(quality != null ? convertDoubleMapToObjectMap(quality.getDimensions()) : null)
                // Phase 6: Dependency criticality
                .dependencyCriticality(depCrit != null ? depCrit.getCriticalityLevel() : null)
                .dependencyCriticalityScore(depCrit != null ? depCrit.getCriticalityScore() : null)
                .dependentTableCount(depCrit != null ? depCrit.getDependentCount() : null)
                .dependencyTableCount(depCrit != null ? depCrit.getDependencyCount() : null)
                .cascadeImpactCount(depCrit != null ? depCrit.getCascadeImpactCount() : null)
                // Phase 6: Growth prediction
                .growthCategory(growth != null ? growth.getGrowthCategory() : null)
                .monthlyGrowthRate(growth != null ? growth.getMonthlyGrowthRate() : null)
                .predictedSize90Days(growth != null ? growth.getPredictedSize90Days() : null)
                .predictedRows90Days(growth != null ? growth.getPredictedRows90Days() : null)
                .daysToSizeWarning(growth != null ? growth.getDaysToWarningThreshold() : null)
                .growthPredictionConfidence(growth != null ? growth.getConfidence() : null)
                .build();

            tableClassificationRepository.save(tc);
        }

        // Step 8: Run health score calculation (after table classifications are saved)
        try {
            Map<String, TableHealthScore> healthScores = healthScoreService.calculateHealthScores(connectionId);
            log.info("Calculated health scores for {} tables", healthScores.size());

            // Update table classifications with health scores
            for (Map.Entry<String, TableHealthScore> entry : healthScores.entrySet()) {
                String tableNameLower = entry.getKey();
                TableHealthScore healthScore = entry.getValue();

                // Find and update the table classification
                tableClassificationRepository
                    .findBySchemaClassificationIdAndTableName(schemaClassification.getId(), tableNameLower)
                    .ifPresent(tc -> {
                        tc.setHealthScore(healthScore.getHealthScore());
                        tc.setHealthBreakdown(healthScore.getBreakdown());
                        tableClassificationRepository.save(tc);
                    });

                totalHealthScore = totalHealthScore.add(healthScore.getHealthScore());
                healthScoreCount++;
            }

            // Update average health score on schema classification
            if (healthScoreCount > 0) {
                BigDecimal avgHealth = totalHealthScore.divide(
                    BigDecimal.valueOf(healthScoreCount), 2, RoundingMode.HALF_UP);
                schemaClassification.setAvgHealthScore(avgHealth);
                schemaClassificationRepository.save(schemaClassification);
            }
        } catch (Exception e) {
            log.warn("Could not calculate health scores: {}", e.getMessage());
        }

        // Step 9: Classify relationships
        runBestEffortAction(executor, "classify relationships", () -> {
            relationshipService.classifyRelationships(connectionId, schemaClassification.getId());
            log.info("Classified relationships for schema");
        });

            int staleTableRows = tableClassificationRepository.deleteStaleRunsForConnection(connectionId);
            int staleRelationshipRows = tableRelationshipClassificationRepository.deleteStaleRunsForConnection(connectionId);
            if (staleTableRows > 0 || staleRelationshipRows > 0) {
                log.info("Cleaned up {} stale table classifications and {} stale relationship classifications for connection {}",
                    staleTableRows, staleRelationshipRows, connectionId);
            }

            log.info("Schema classified as {} with {}% confidence. Tables with anti-patterns: {}, PII tables: {}, Partition candidates: {}",
                globalPattern, confidence.doubleValue(), tablesWithAntiPatterns, piiTablesCount, partitionCandidatesCount);

            return schemaClassification;
        } finally {
            executor.shutdown();
        }
    }

    private <T> CompletableFuture<Map<String, T>> runClassificationTask(
            ExecutorService executor,
            String taskName,
            Supplier<Map<String, T>> supplier) {
        return CompletableFuture.supplyAsync(() -> {
                // Bound peak concurrency: at most maxClassificationConcurrency of
                // the ~16 services materialize their full per-table metadata at
                // once, so a large schema can't OOM the heap and crash the JVM.
                Semaphore gate = classificationGate();
                gate.acquireUninterruptibly();
                try {
                    Map<String, T> result = supplier.get();
                    return result != null ? result : new HashMap<String, T>();
                } finally {
                    gate.release();
                }
            }, executor)
            .orTimeout(CLASSIFICATION_TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .handle((result, throwable) -> {
                if (throwable == null) {
                    return result != null ? result : new HashMap<String, T>();
                }
                Throwable cause = unwrapCompletionThrowable(throwable);
                if (cause instanceof TimeoutException) {
                    log.warn("Timed out trying to {} after {} seconds. Continuing with partial classification.",
                        taskName, CLASSIFICATION_TASK_TIMEOUT_SECONDS);
                } else {
                    log.warn("Could not {}: {}", taskName, cause.getMessage());
                }
                return new HashMap<String, T>();
            });
    }

    private Throwable unwrapCompletionThrowable(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null
                && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        return current;
    }

    private void runBestEffortAction(ExecutorService executor, String actionName, Runnable action) {
        CompletableFuture.runAsync(action, executor)
            .orTimeout(CLASSIFICATION_TASK_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .handle((ignored, throwable) -> {
                if (throwable == null) {
                    return null;
                }
                Throwable cause = unwrapCompletionThrowable(throwable);
                if (cause instanceof TimeoutException) {
                    log.warn("Timed out trying to {} after {} seconds. Continuing with partial classification.",
                        actionName, CLASSIFICATION_TASK_TIMEOUT_SECONDS);
                } else {
                    log.warn("Could not {}: {}", actionName, cause.getMessage());
                }
                return null;
            })
            .join();
    }

    /**
     * Build a graph of tables and their relationships.
     */
    private TableGraph buildTableGraph(String connectionId) {
        TableGraph graph = TableGraph.builder().build();

        // Get all key column analyses
        List<KeyColumnAnalysis> analyses = keyColumnAnalysisRepository
            .findByConnectionIdOrderByImportanceScoreDesc(connectionId);

        // Group by table
        Map<String, List<KeyColumnAnalysis>> tableAnalyses = analyses.stream()
            .collect(Collectors.groupingBy(KeyColumnAnalysis::getTableName));

        // Create nodes
        for (Map.Entry<String, List<KeyColumnAnalysis>> entry : tableAnalyses.entrySet()) {
            String tableName = entry.getKey();
            List<KeyColumnAnalysis> columns = entry.getValue();

            // Get table stats
            long rowCount = columns.stream()
                .filter(c -> c.getTotalRows() != null)
                .findFirst()
                .map(KeyColumnAnalysis::getTotalRows)
                .orElse(0L);

            int columnCount = columns.size();
            int joinCount = columns.stream().mapToInt(KeyColumnAnalysis::getJoinCount).sum();

            TableNode node = TableNode.builder()
                .tableName(tableName)
                .rowCount(rowCount)
                .columnCount(columnCount)
                .totalJoinCount(joinCount)
                .build();

            graph.addTable(node);
        }

        // Create relationships from KeyColumnAnalysis (FK column naming patterns)
        // Consider columns with join count > 0, not just TRUE_KEY (many DBs don't have proper FK constraints)
        for (KeyColumnAnalysis analysis : analyses) {
            if (analysis.getJoinCount() > 0) {
                String sourceTable = analysis.getTableName();
                String fkColumn = analysis.getColumnName();

                // Skip if column is named 'id' (PK, not FK)
                if (fkColumn.equalsIgnoreCase("id")) {
                    continue;
                }

                // Try to infer target table (heuristic: FK column name often contains target table name)
                String targetTable = inferTargetTable(fkColumn, graph.getTables().keySet());

                if (targetTable != null && !targetTable.equals(sourceTable)) {
                    TableRelationship rel = TableRelationship.builder()
                        .sourceTable(sourceTable)
                        .targetTable(targetTable)
                        .columnName(fkColumn)
                        .joinCount(analysis.getJoinCount())
                        .cardinality("N:1")  // FK relationship is many-to-one
                        .build();

                    graph.addRelationship(rel);
                }
            }
        }

        // Add inferred relationships from slow query log analysis
        addInferredRelationships(connectionId, graph);

        return graph;
    }

    /**
     * Add inferred relationships from slow query log JOIN pattern analysis.
     * Only includes high-confidence relationships that aren't already in the graph.
     */
    private void addInferredRelationships(String connectionId, TableGraph graph) {
        List<InferredTableRelationship> inferredRelationships =
            inferredTableRelationshipRepository.findHighConfidenceRelationships(
                connectionId, MIN_INFERRED_CONFIDENCE);

        log.info("Found {} high-confidence inferred relationships for schema classification",
            inferredRelationships.size());

        // Build case-insensitive lookup map for existing tables
        Map<String, String> lowerToActualTableName = new HashMap<>();
        for (String tableName : graph.getTables().keySet()) {
            lowerToActualTableName.put(tableName.toLowerCase(), tableName);
        }

        // Track existing relationships to avoid duplicates (case-insensitive)
        Set<String> existingRelationships = graph.getRelationships().stream()
            .map(r -> r.getSourceTable().toLowerCase() + "->" + r.getTargetTable().toLowerCase())
            .collect(Collectors.toSet());

        int addedCount = 0;
        for (InferredTableRelationship inferred : inferredRelationships) {
            String inferredSourceLower = inferred.getSourceTable().toLowerCase();
            String inferredTargetLower = inferred.getTargetTable().toLowerCase();
            String relKey = inferredSourceLower + "->" + inferredTargetLower;

            // Skip if relationship already exists (from KeyColumnAnalysis)
            if (existingRelationships.contains(relKey)) {
                continue;
            }

            // Find actual table names (respecting original case from KeyColumnAnalysis)
            String sourceTable = lowerToActualTableName.getOrDefault(inferredSourceLower, inferred.getSourceTable());
            String targetTable = lowerToActualTableName.getOrDefault(inferredTargetLower, inferred.getTargetTable());

            // Ensure both tables exist in the graph
            if (!lowerToActualTableName.containsKey(inferredSourceLower)) {
                // Add table node if missing (use the inferred name)
                graph.addTable(TableNode.builder()
                    .tableName(sourceTable)
                    .rowCount(0L)
                    .columnCount(0)
                    .totalJoinCount(inferred.getJoinCount())
                    .build());
                lowerToActualTableName.put(inferredSourceLower, sourceTable);
            }
            if (!lowerToActualTableName.containsKey(inferredTargetLower)) {
                graph.addTable(TableNode.builder()
                    .tableName(targetTable)
                    .rowCount(0L)
                    .columnCount(0)
                    .totalJoinCount(inferred.getJoinCount())
                    .build());
                lowerToActualTableName.put(inferredTargetLower, targetTable);
            }

            // Add the relationship using actual table names
            TableRelationship rel = TableRelationship.builder()
                .sourceTable(sourceTable)
                .targetTable(targetTable)
                .columnName(inferred.getSourceColumn())
                .joinCount(inferred.getJoinCount())
                .cardinality(inferred.getCardinality() != null ? inferred.getCardinality() : "N:1")
                .build();

            graph.addRelationship(rel);
            existingRelationships.add(relKey);
            addedCount++;
        }

        log.info("Added {} inferred relationships to schema graph", addedCount);
    }

    /**
     * Infer target table from FK column name.
     * Example: "user_id" -> "users" or "user"
     */
    private String inferTargetTable(String fkColumn, Set<String> allTables) {
        String column = fkColumn.toLowerCase();

        // Remove common suffixes
        String baseName = column
            .replaceAll("_(id|key|fk|ref)$", "")
            .replaceAll("id$", "")  // Handle bookingid -> booking
            .replaceAll("^(fk_|ref_)", "");

        // Build case-insensitive lookup
        Map<String, String> lowerToActual = new HashMap<>();
        for (String table : allTables) {
            lowerToActual.put(table.toLowerCase(), table);
        }

        // Try exact match
        if (lowerToActual.containsKey(baseName)) {
            return lowerToActual.get(baseName);
        }

        // Try plural form
        String plural = baseName + "s";
        if (lowerToActual.containsKey(plural)) {
            return lowerToActual.get(plural);
        }

        // Try singular form (remove trailing 's')
        if (baseName.endsWith("s")) {
            String singular = baseName.substring(0, baseName.length() - 1);
            if (lowerToActual.containsKey(singular)) {
                return lowerToActual.get(singular);
            }
        }

        // Handle prefixed columns like "pm_hotel" -> "hotel"
        if (baseName.contains("_")) {
            // Try the part after the last underscore
            int lastUnderscore = baseName.lastIndexOf('_');
            String suffix = baseName.substring(lastUnderscore + 1);
            if (lowerToActual.containsKey(suffix)) {
                return lowerToActual.get(suffix);
            }
            if (lowerToActual.containsKey(suffix + "s")) {
                return lowerToActual.get(suffix + "s");
            }

            // Try each segment
            String[] parts = baseName.split("_");
            for (String part : parts) {
                if (part.length() > 2) {
                    if (lowerToActual.containsKey(part)) {
                        return lowerToActual.get(part);
                    }
                    if (lowerToActual.containsKey(part + "s")) {
                        return lowerToActual.get(part + "s");
                    }
                }
            }
        }

        // Try finding table that starts with or contains base name
        for (String tableLower : lowerToActual.keySet()) {
            if (tableLower.startsWith(baseName) || tableLower.endsWith(baseName)) {
                return lowerToActual.get(tableLower);
            }
        }

        return null;
    }

    /**
     * Classify individual tables as FACT, DIMENSION, etc.
     */
    private List<TableNode> classifyTables(TableGraph graph, Map<String, Object> reasoning) {
        List<TableNode> tables = new ArrayList<>(graph.getTables().values());

        // Find potential fact tables (large tables with many outbound FKs)
        for (TableNode table : tables) {
            int outbound = graph.getOutboundRelationships(table.getTableName()).size();
            int inbound = graph.getInboundRelationships(table.getTableName()).size();
            long rowCount = table.getRowCount();

            // Classification logic
            // FACT table: many outbound FKs to dimensions
            // Relaxed criteria: either (outbound >= 3) OR (outbound >= 2 AND high join activity)
            boolean likelyFact = outbound >= 3 ||
                (outbound >= 2 && table.getTotalJoinCount() > 50) ||
                (outbound >= 2 && rowCount > 10000);

            // Also consider tables with PAYMENTS/TRANSACTIONS/ORDERS naming as potential facts
            String tableNameLower = table.getTableName().toLowerCase();
            boolean hasFactLikeName = tableNameLower.contains("payment") ||
                tableNameLower.contains("transaction") ||
                tableNameLower.contains("order") ||
                tableNameLower.contains("booking") ||
                tableNameLower.contains("invoice") ||
                tableNameLower.contains("fact_");

            // Detect log/audit/event tables - these are NOT dimensions
            boolean isLogTable = tableNameLower.contains("_log") ||
                tableNameLower.contains("logs") ||
                tableNameLower.contains("_audit") ||
                tableNameLower.contains("audit_") ||
                tableNameLower.contains("_history") ||
                tableNameLower.contains("_event") ||
                tableNameLower.contains("events") ||
                tableNameLower.contains("_trail") ||
                tableNameLower.contains("_changelog");

            if (likelyFact || (hasFactLikeName && outbound >= 2)) {
                // Likely a FACT table: many FKs to dimensions
                table.setRole("FACT");
                double confidence = 70.0 + Math.min(30, outbound * 5);
                if (rowCount > 10000) confidence = Math.min(100, confidence + 10);
                if (hasFactLikeName) confidence = Math.min(100, confidence + 5);
                table.setConfidence(BigDecimal.valueOf(confidence));
                table.setReasoning(String.format(
                    "Classified as FACT: %d outbound joins, %,d rows, %d total join count",
                    outbound, rowCount, table.getTotalJoinCount()));
                table.setDepthFromFact(0);
            } else if (isLogTable) {
                // Log/audit/event tables - append-only event stores, NOT dimensions
                table.setRole("EVENT_LOG");
                double confidence = 85.0;
                if (rowCount > 10000) confidence = Math.min(95, confidence + 5);
                table.setConfidence(BigDecimal.valueOf(confidence));
                table.setReasoning(String.format(
                    "Classified as EVENT_LOG: table name pattern indicates log/audit/event data, %,d rows",
                    rowCount));
                table.setDepthFromFact(null);  // Not part of dimension hierarchy
            } else if (inbound >= 2 && outbound <= 2 && (rowCount < 100000 || rowCount == 0)) {
                // Likely a DIMENSION: many tables join to it, few outbound FKs
                table.setRole("DIMENSION");
                table.setConfidence(BigDecimal.valueOf(75.0 + Math.min(20, inbound * 5)));
                table.setReasoning(String.format(
                    "Classified as DIMENSION: %d inbound joins, %,d rows", inbound, rowCount));
                table.setDepthFromFact(1);
            } else if (inbound >= 1 && outbound >= 1 && rowCount < 10000) {
                // Likely a BRIDGE table: connects other tables
                table.setRole("BRIDGE");
                table.setConfidence(BigDecimal.valueOf(70.0));
                table.setReasoning(String.format(
                    "Classified as BRIDGE: %d inbound, %d outbound joins", inbound, outbound));
            } else if (rowCount < 1000 && inbound >= 1) {
                // Small lookup table
                table.setRole("LOOKUP");
                table.setConfidence(BigDecimal.valueOf(85.0));
                table.setReasoning(String.format(
                    "Classified as LOOKUP: Small table (%,d rows) with %d inbound joins",
                    rowCount, inbound));
            } else if (inbound == 0 && outbound == 0) {
                // No relationships
                table.setRole("ORPHANED");
                table.setConfidence(BigDecimal.valueOf(95.0));
                table.setReasoning("Classified as ORPHANED: No relationships detected");
            } else {
                // Default
                table.setRole("DIMENSION");
                table.setConfidence(BigDecimal.valueOf(50.0));
                table.setReasoning("Default classification based on heuristics");
                table.setDepthFromFact(1);
            }

            table.setInboundJoinCount(inbound);
            table.setOutboundJoinCount(outbound);
        }

        return tables;
    }

    /**
     * Detect global schema pattern.
     */
    private String detectGlobalPattern(List<TableNode> tables, TableGraph graph, Map<String, Object> reasoning) {
        long factCount = tables.stream().filter(t -> "FACT".equals(t.getRole())).count();
        long dimCount = tables.stream().filter(t -> "DIMENSION".equals(t.getRole())).count();
        long orphanedCount = tables.stream().filter(t -> "ORPHANED".equals(t.getRole())).count();

        int maxDepth = calculateMaxDimensionDepth(tables);
        boolean hasCycles = detectCycles(graph);
        double orphanedRatio = (double) orphanedCount / tables.size();

        String pattern;
        StringBuilder reasoningText = new StringBuilder();

        if (hasCycles && orphanedRatio > 0.3) {
            pattern = "ANTI_PATTERN";
            reasoningText.append("Detected anti-pattern: cycles in schema and >30% orphaned tables");
        } else if (factCount >= 1 && dimCount >= 3) {
            if (maxDepth == 1) {
                pattern = "STAR";
                reasoningText.append(String.format(
                    "Star schema detected: %d fact table(s), %d dimensions, max depth 1",
                    factCount, dimCount));
            } else if (maxDepth >= 2) {
                pattern = "SNOWFLAKE";
                reasoningText.append(String.format(
                    "Snowflake schema detected: %d fact table(s), %d dimensions, max depth %d",
                    factCount, dimCount, maxDepth));
            } else {
                pattern = "HYBRID";
                reasoningText.append("Hybrid schema: mix of star and snowflake patterns");
            }
        } else if (dimCount > factCount * 3) {
            pattern = "OLTP";
            reasoningText.append(String.format(
                "OLTP-style schema: highly normalized, %d dimensions vs %d facts",
                dimCount, factCount));
        } else if (orphanedRatio < 0.1 && factCount == 0) {
            pattern = "REPORTING";
            reasoningText.append("Reporting schema: denormalized tables, no clear fact tables");
        } else {
            pattern = "HYBRID";
            reasoningText.append("Hybrid schema: doesn't fit clear pattern");
        }

        reasoning.put("globalPatternReasoning", reasoningText.toString());
        reasoning.put("factCount", factCount);
        reasoning.put("dimensionCount", dimCount);
        reasoning.put("orphanedCount", orphanedCount);
        reasoning.put("maxDepth", maxDepth);
        reasoning.put("hasCycles", hasCycles);

        return pattern;
    }

    private BigDecimal calculateConfidence(String pattern, List<TableNode> tables, TableGraph graph) {
        double confidence = 70.0;  // Base

        long factCount = tables.stream().filter(t -> "FACT".equals(t.getRole())).count();
        long dimCount = tables.stream().filter(t -> "DIMENSION".equals(t.getRole())).count();

        if ("STAR".equals(pattern) || "SNOWFLAKE".equals(pattern)) {
            if (factCount >= 1 && dimCount >= 4) confidence += 20;
            if (factCount == 1) confidence += 10;  // Single fact is clearer
        } else if ("ANTI_PATTERN".equals(pattern)) {
            confidence = 90.0;  // High confidence in problems
        }

        return BigDecimal.valueOf(confidence).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal calculateAvgFanOut(List<TableNode> tables, TableGraph graph) {
        List<TableNode> facts = tables.stream()
            .filter(t -> "FACT".equals(t.getRole()))
            .collect(Collectors.toList());

        if (facts.isEmpty()) {
            return BigDecimal.ZERO;
        }

        double avgFanOut = facts.stream()
            .mapToInt(f -> graph.getOutboundRelationships(f.getTableName()).size())
            .average()
            .orElse(0.0);

        return BigDecimal.valueOf(avgFanOut).setScale(2, RoundingMode.HALF_UP);
    }

    private int calculateMaxDimensionDepth(List<TableNode> tables) {
        return tables.stream()
            .filter(t -> "DIMENSION".equals(t.getRole()) && t.getDepthFromFact() != null)
            .mapToInt(TableNode::getDepthFromFact)
            .max()
            .orElse(1);
    }

    private String estimateNormalizationLevel(TableGraph graph) {
        // Simplified: based on average FK count per table
        double avgFKs = graph.getTables().values().stream()
            .mapToInt(t -> graph.getOutboundRelationships(t.getTableName()).size())
            .average()
            .orElse(0.0);

        if (avgFKs < 1) return "DENORMALIZED";
        if (avgFKs >= 1 && avgFKs < 2) return "1NF";
        if (avgFKs >= 2 && avgFKs < 4) return "2NF";
        return "3NF";
    }

    private boolean detectCycles(TableGraph graph) {
        // Simple cycle detection using DFS
        Set<String> visited = new HashSet<>();
        Set<String> recursionStack = new HashSet<>();

        for (String table : graph.getTables().keySet()) {
            if (hasCycleDFS(table, graph, visited, recursionStack)) {
                return true;
            }
        }

        return false;
    }

    private boolean hasCycleDFS(String table, TableGraph graph, Set<String> visited, Set<String> recStack) {
        if (recStack.contains(table)) {
            return true;  // Cycle detected
        }

        if (visited.contains(table)) {
            return false;
        }

        visited.add(table);
        recStack.add(table);

        for (TableRelationship rel : graph.getOutboundRelationships(table)) {
            if (hasCycleDFS(rel.getTargetTable(), graph, visited, recStack)) {
                return true;
            }
        }

        recStack.remove(table);
        return false;
    }

    private int estimateMissingForeignKeys(TableGraph graph) {
        // Heuristic: columns with high JOIN count but not classified as TRUE_KEY
        return 0;  // Placeholder
    }

    /**
     * Get latest schema classification.
     */
    public Optional<SchemaClassification> getLatestClassification(String connectionId) {
        return schemaClassificationRepository.findLatestByConnectionId(connectionId);
    }

    /**
     * Get table classifications.
     */
    public List<TableClassification> getTableClassifications(String connectionId) {
        return tableClassificationRepository.findLatestByConnectionIdOrderByTableNameAsc(connectionId);
    }

    // Inner classes
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class TableGraph {
        @Builder.Default
        private Map<String, TableNode> tables = new HashMap<>();
        @Builder.Default
        private List<TableRelationship> relationships = new ArrayList<>();

        public void addTable(TableNode table) {
            tables.put(table.getTableName(), table);
        }

        public void addRelationship(TableRelationship rel) {
            relationships.add(rel);
        }

        public List<TableRelationship> getOutboundRelationships(String tableName) {
            return relationships.stream()
                .filter(r -> r.getSourceTable().equals(tableName))
                .collect(Collectors.toList());
        }

        public List<TableRelationship> getInboundRelationships(String tableName) {
            return relationships.stream()
                .filter(r -> r.getTargetTable().equals(tableName))
                .collect(Collectors.toList());
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class TableNode {
        private String tableName;
        private Long rowCount;
        private Integer columnCount;
        private Integer totalJoinCount;
        private String role;
        private BigDecimal confidence;
        private Integer inboundJoinCount;
        private Integer outboundJoinCount;
        private Integer foreignKeyCount;
        private Boolean isNormalized;
        private Boolean hasSurrogateKey;
        private String granularity;
        private Integer depthFromFact;
        private Integer clusterId;
        private String reasoning;
    }

    @Data
    @Builder
    @AllArgsConstructor
    private static class TableRelationship {
        private String sourceTable;
        private String targetTable;
        private String columnName;
        private Integer joinCount;
        private String cardinality;
    }

    /**
     * Get all table classifications for a connection.
     */
    public List<TableClassification> getAllTableClassifications(String connectionId) {
        Optional<SchemaClassification> latest = getLatestClassification(connectionId);
        if (latest.isEmpty()) {
            return List.of();
        }
        return tableClassificationRepository.findBySchemaClassificationId(latest.get().getId());
    }

    /**
     * Get tables by role (FACT, DIMENSION, etc.).
     */
    public List<TableClassification> getTablesByRole(String connectionId, String role) {
        return tableClassificationRepository.findLatestByConnectionIdAndTableRole(connectionId, role);
    }

    /**
     * Get fact tables for a connection.
     */
    public List<TableClassification> getFactTables(String connectionId) {
        return getTablesByRole(connectionId, "FACT");
    }

    /**
     * Get dimension tables for a connection.
     */
    public List<TableClassification> getDimensionTables(String connectionId) {
        return getTablesByRole(connectionId, "DIMENSION");
    }

    // Helper methods for map conversion

    private Map<String, Object> convertToObjectMap(Map<String, Double> doubleMap) {
        if (doubleMap == null) return null;
        Map<String, Object> result = new HashMap<>();
        doubleMap.forEach((k, v) -> result.put(k, v));
        return result;
    }

    private Map<String, Object> convertDoubleMapToObjectMap(Map<String, Double> doubleMap) {
        if (doubleMap == null) return null;
        Map<String, Object> result = new HashMap<>();
        doubleMap.forEach((k, v) -> result.put(k, v));
        return result;
    }

    private Map<String, Object> convertBigDecimalMapToObjectMap(Map<String, BigDecimal> bdMap) {
        if (bdMap == null) return null;
        Map<String, Object> result = new HashMap<>();
        bdMap.forEach((k, v) -> result.put(k, v));
        return result;
    }
}
