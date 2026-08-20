package com.dbaagent.service.brain.config;

import com.dbaagent.model.ConfigurationRecommendation;
import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.brain.*;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.repository.brain.*;
import com.dbaagent.service.ConnectionService;
import com.dbaagent.service.CredentialService;
import com.dbaagent.service.brain.workload.WorkloadCharacterizationService;
import com.dbaagent.service.brain.workload.WorkloadMetricsCollectorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Brain 2.0: Configuration Tuning Service
 *
 * ML-based configuration recommendation using Gaussian Process-inspired approach.
 * Combines learned observations with AI reasoning for optimal recommendations.
 *
 * Inspired by OtterTune's GP regression and Bayesian optimization.
 */
@Service
@Slf4j
public class ConfigTuningService {

    private final ChatClient chatClient;
    private final KnobRankingRepository knobRankingRepository;
    private final ConfigurationObservationRepository observationRepository;
    private final TuningExperimentRepository experimentRepository;
    private final WorkloadProfileRepository profileRepository;
    private final WorkloadMetricsSnapshotRepository snapshotRepository;
    private final BrainV2AlertRepository alertRepository;
    private final KnobIdentificationService knobIdentificationService;
    private final WorkloadCharacterizationService workloadService;
    private final WorkloadMetricsCollectorService metricsCollectorService;
    private final ConnectionService connectionService;
    private final CredentialService credentialService;
    private final DatabaseProviderRegistry providerRegistry;

    @Autowired
    public ConfigTuningService(
            ChatClient.Builder chatClientBuilder,
            KnobRankingRepository knobRankingRepository,
            ConfigurationObservationRepository observationRepository,
            TuningExperimentRepository experimentRepository,
            WorkloadProfileRepository profileRepository,
            WorkloadMetricsSnapshotRepository snapshotRepository,
            BrainV2AlertRepository alertRepository,
            KnobIdentificationService knobIdentificationService,
            WorkloadCharacterizationService workloadService,
            WorkloadMetricsCollectorService metricsCollectorService,
            ConnectionService connectionService,
            CredentialService credentialService,
            DatabaseProviderRegistry providerRegistry) {
        this.chatClient = chatClientBuilder.build();
        this.knobRankingRepository = knobRankingRepository;
        this.observationRepository = observationRepository;
        this.experimentRepository = experimentRepository;
        this.profileRepository = profileRepository;
        this.snapshotRepository = snapshotRepository;
        this.alertRepository = alertRepository;
        this.knobIdentificationService = knobIdentificationService;
        this.workloadService = workloadService;
        this.metricsCollectorService = metricsCollectorService;
        this.connectionService = connectionService;
        this.credentialService = credentialService;
        this.providerRegistry = providerRegistry;
        log.info("ConfigTuningService initialized with Spring AI ChatClient");
    }

    /**
     * Generate configuration recommendations using ML + AI hybrid approach.
     */
    @Transactional
    public List<ConfigurationRecommendation> generateRecommendations(String connectionId) {
        log.info("Generating ML-based config recommendations for connection: {}", connectionId);

        try {
            // Ensure we have workload characterization
            WorkloadProfile profile = workloadService.getProfile(connectionId)
                .orElseGet(() -> workloadService.characterizeWorkload(connectionId));

            // Ensure we have knob rankings
            List<KnobRanking> rankings = knobRankingRepository
                .findByConnectionIdAndTargetMetricOrderByRankAsc(
                    connectionId, KnobRanking.TargetMetric.LATENCY);

            if (rankings.isEmpty()) {
                rankings = knobIdentificationService.identifyKnobs(
                    connectionId, KnobRanking.TargetMetric.LATENCY);
            }

            // Get current configuration
            ConnectionRequest connection = credentialService.getDecryptedConnection(connectionId);
            String dbType = providerRegistry.getCanonicalName(connection.getDbType());
            JdbcTemplate jdbc = connectionService.getJdbcTemplate(connectionId, connection);
            Map<String, String> currentConfig = getCurrentConfig(jdbc, dbType);

            // Get successful observations from similar workloads
            List<ConfigurationObservation> successfulObs = findSuccessfulConfigs(connectionId, profile);

            // Get top knobs to tune
            List<KnobRanking> topKnobs = rankings.stream().limit(5).collect(Collectors.toList());

            InstanceSpecs instanceSpecs = extractInstanceSpecs(connection);

            // Generate recommendations using AI
            List<ConfigurationRecommendation> recommendations = generateAIRecommendations(
                connectionId, dbType, profile, topKnobs, currentConfig, successfulObs, instanceSpecs);

            log.info("Generated {} recommendations for connection {}", recommendations.size(), connectionId);
            return recommendations;

        } catch (Exception e) {
            log.error("Error generating recommendations for {}: {}", connectionId, e.getMessage(), e);
            throw new RuntimeException("Failed to generate recommendations: " + e.getMessage(), e);
        }
    }

    /**
     * Get current database configuration.
     */
    private Map<String, String> getCurrentConfig(JdbcTemplate jdbc, String dbType) {
        Map<String, String> config = new HashMap<>();

        try {
            if ("postgres".equals(dbType)) {
                List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT name, setting, unit FROM pg_settings");
                for (Map<String, Object> row : rows) {
                    String name = (String) row.get("name");
                    String setting = (String) row.get("setting");
                    String unit = row.get("unit") != null ? (String) row.get("unit") : "";
                    config.put(name, setting + unit);
                }
            } else if ("mysql".equals(dbType)) {
                List<Map<String, Object>> rows = jdbc.queryForList("SHOW GLOBAL VARIABLES");
                for (Map<String, Object> row : rows) {
                    String name = (String) row.get("Variable_name");
                    String value = (String) row.get("Value");
                    config.put(name, value);
                }
            }
        } catch (Exception e) {
            log.warn("Could not get current config: {}", e.getMessage());
        }

        return config;
    }

    /**
     * Find successful configurations from this and similar workloads.
     */
    private List<ConfigurationObservation> findSuccessfulConfigs(
            String connectionId, WorkloadProfile profile) {

        List<ConfigurationObservation> successful = new ArrayList<>();

        // Get successful observations from this connection
        successful.addAll(observationRepository.findSuccessfulObservations(
            connectionId, PageRequest.of(0, 10)));

        // Get from similar workloads (knowledge transfer)
        List<WorkloadProfile> similarProfiles = workloadService.findSimilarProfiles(connectionId, 3);
        for (WorkloadProfile similar : similarProfiles) {
            if (similar.getOptimalConfig() != null) {
                // Create synthetic observation from optimal config
                ConfigurationObservation obs = ConfigurationObservation.builder()
                    .connectionId(similar.getConnectionId())
                    .configuration(similar.getOptimalConfig())
                    .observationType(ConfigurationObservation.ObservationType.RECOMMENDED)
                    .improvementPercent(similar.getPerformanceScore())
                    .observedAt(similar.getProfiledAt())
                    .build();
                successful.add(obs);
            }
        }

        return successful;
    }

    /**
     * Generate recommendations using AI with learned context.
     */
    private List<ConfigurationRecommendation> generateAIRecommendations(
            String connectionId,
            String dbType,
            WorkloadProfile profile,
            List<KnobRanking> topKnobs,
            Map<String, String> currentConfig,
            List<ConfigurationObservation> successfulObs,
            InstanceSpecs instanceSpecs) {

        // Build prompt with context
        String prompt = buildTuningPrompt(dbType, profile, topKnobs, currentConfig, successfulObs, instanceSpecs);

        String systemPrompt = """
            You are an expert database performance tuner with deep knowledge of %s configuration.
            You have access to workload analysis, knob importance rankings, and historical performance data.
            You may also be provided with instance sizing (vCPU, RAM, storage/IOPS).

            Your task is to recommend specific configuration changes that will improve performance.

            IMPORTANT RULES:
            1. Only recommend changes to the TOP 5 most impactful knobs
            2. Provide specific values, not ranges
            3. Consider the workload type when making recommendations
            4. Learn from successful configurations in similar workloads
            5. Explain why each change will help
            6. If instance RAM/vCPUs are provided, size memory/CPU related knobs to fit that hardware (do not exceed available RAM)

            Format each recommendation as:
            KNOB: <knob_name>
            CURRENT: <current_value>
            RECOMMENDED: <recommended_value>
            PRIORITY: <HIGH/MEDIUM/LOW>
            IMPROVEMENT: <estimated percentage>
            REASON: <brief explanation>
            RESTART: <YES/NO>
            ---
            """.formatted(dbType.toUpperCase());

        try {
            String response = chatClient.prompt()
                .messages(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(prompt)
                ))
                .call()
                .content();

            return parseAIRecommendations(connectionId, response);

        } catch (Exception e) {
            log.error("Error getting AI recommendations: {}", e.getMessage());
            return generateFallbackRecommendations(connectionId, topKnobs, currentConfig);
        }
    }

    /**
     * Build detailed prompt for the tuning model.
     */
    private String buildTuningPrompt(
            String dbType,
            WorkloadProfile profile,
            List<KnobRanking> topKnobs,
            Map<String, String> currentConfig,
            List<ConfigurationObservation> successfulObs,
            InstanceSpecs instanceSpecs) {

        StringBuilder prompt = new StringBuilder();

        prompt.append("## Environment / Instance Specs\n");
        prompt.append(buildInstanceSpecsSection(instanceSpecs, dbType));
        prompt.append("\n");

        prompt.append("## Workload Profile\n");
        prompt.append("Type: ").append(profile.getWorkloadType()).append("\n");
        prompt.append("Subtype: ").append(profile.getWorkloadSubtype()).append("\n");
        prompt.append("Confidence: ").append(String.format("%.1f%%", profile.getClassificationConfidence())).append("\n\n");

        prompt.append("## Top Impactful Knobs (ranked by ML analysis)\n");
        for (KnobRanking knob : topKnobs) {
            prompt.append(String.format("- %s (rank %d, impact: %.2f)\n",
                knob.getKnobName(), knob.getRank(), knob.getImpactScore()));
            prompt.append(String.format("  Current: %s, Default: %s, Range: [%s - %s]\n",
                currentConfig.getOrDefault(knob.getKnobName(), "unknown"),
                knob.getDefaultValue(),
                knob.getMinValue() != null ? knob.getMinValue() : "N/A",
                knob.getMaxValue() != null ? knob.getMaxValue() : "N/A"));
            if (knob.getRequiresRestart()) {
                prompt.append("  ⚠️ Requires restart\n");
            }
        }

        prompt.append("\n## Successful Configurations from Similar Workloads\n");
        if (successfulObs.isEmpty()) {
            prompt.append("No historical data available - use best practices.\n");
        } else {
            for (ConfigurationObservation obs : successfulObs.stream().limit(3).toList()) {
                prompt.append(String.format("Config with %.1f%% improvement:\n",
                    obs.getImprovementPercent() != null ? obs.getImprovementPercent() : 0.0));
                if (obs.getConfiguration() != null) {
                    for (KnobRanking knob : topKnobs) {
                        String value = obs.getConfiguration().get(knob.getKnobName());
                        if (value != null) {
                            prompt.append(String.format("  %s = %s\n", knob.getKnobName(), value));
                        }
                    }
                }
            }
        }

        prompt.append("\n## Task\n");
        prompt.append("Recommend optimal values for the top knobs to improve ");
        prompt.append(profile.getWorkloadType()).append(" workload performance.\n");

        return prompt.toString();
    }

    private InstanceSpecs extractInstanceSpecs(ConnectionRequest connection) {
        return new InstanceSpecs(
            connection.getCloudProvider(),
            connection.getManagedService(),
            connection.getInstanceClass(),
            connection.getInstanceVcpus(),
            connection.getInstanceMemoryGb(),
            connection.getStorageType(),
            connection.getStorageMaxIops()
        );
    }

    private String buildInstanceSpecsSection(InstanceSpecs specs, String dbType) {
        StringBuilder sb = new StringBuilder();

        boolean any =
            isNotBlank(specs.cloudProvider()) ||
            isNotBlank(specs.managedService()) ||
            isNotBlank(specs.instanceClass()) ||
            specs.vcpus() != null ||
            specs.memoryGb() != null ||
            isNotBlank(specs.storageType()) ||
            specs.maxIops() != null;

        if (!any) {
            sb.append("Not provided.\n");
            sb.append("If you set instance RAM/vCPUs/IOPS on the connection, tuning recommendations can be sized accurately.\n");
            return sb.toString();
        }

        if (isNotBlank(specs.cloudProvider())) sb.append("Cloud provider: ").append(specs.cloudProvider()).append("\n");
        if (isNotBlank(specs.managedService())) sb.append("Managed service: ").append(specs.managedService()).append("\n");
        if (isNotBlank(specs.instanceClass())) sb.append("Instance class: ").append(specs.instanceClass()).append("\n");
        if (specs.vcpus() != null) sb.append("vCPUs/vCores: ").append(specs.vcpus()).append("\n");
        if (specs.memoryGb() != null) sb.append("Memory: ").append(trimTrailingZeros(specs.memoryGb())).append(" GB\n");
        if (isNotBlank(specs.storageType())) sb.append("Storage type: ").append(specs.storageType()).append("\n");
        if (specs.maxIops() != null) sb.append("Max IOPS: ").append(specs.maxIops()).append("\n");

        // Simple sizing helpers for common memory knobs
        if (specs.memoryGb() != null && specs.memoryGb() > 0) {
            sb.append("\nSizing helpers (derived from memory):\n");
            if ("postgres".equals(dbType)) {
                double sharedBuffersGb = specs.memoryGb() * 0.25;
                double effectiveCacheSizeGb = specs.memoryGb() * 0.75;
                sb.append("- shared_buffers ≈ 25% RAM = ").append(trimTrailingZeros(sharedBuffersGb)).append(" GB\n");
                sb.append("- effective_cache_size ≈ 75% RAM = ").append(trimTrailingZeros(effectiveCacheSizeGb)).append(" GB\n");
            } else if ("mysql".equals(dbType)) {
                double innodbBufferPoolGb = specs.memoryGb() * 0.70;
                sb.append("- innodb_buffer_pool_size ≈ 70% RAM = ").append(trimTrailingZeros(innodbBufferPoolGb)).append(" GB\n");
            }
        }

        return sb.toString();
    }

    private boolean isNotBlank(String value) {
        return value != null && !value.trim().isEmpty();
    }

    private String trimTrailingZeros(Double value) {
        if (value == null) {
            return "unknown";
        }
        String formatted = String.format(java.util.Locale.ROOT, "%.2f", value);
        return formatted.replaceAll("\\.?0+$", "");
    }

    private record InstanceSpecs(
        String cloudProvider,
        String managedService,
        String instanceClass,
        Integer vcpus,
        Double memoryGb,
        String storageType,
        Integer maxIops
    ) {}

    /**
     * Parse AI response into recommendations.
     */
    private List<ConfigurationRecommendation> parseAIRecommendations(String connectionId, String response) {
        List<ConfigurationRecommendation> recommendations = new ArrayList<>();

        String[] blocks = response.split("---");
        for (String block : blocks) {
            if (block.trim().isEmpty()) continue;

            try {
                String knob = extractValue(block, "KNOB:");
                String current = extractValue(block, "CURRENT:");
                String recommended = extractValue(block, "RECOMMENDED:");
                String priority = extractValue(block, "PRIORITY:");
                String improvement = extractValue(block, "IMPROVEMENT:");
                String reason = extractValue(block, "REASON:");
                String restart = extractValue(block, "RESTART:");

                if (knob != null && recommended != null) {
                    ConfigurationRecommendation.Priority p = switch (priority != null ? priority.toUpperCase() : "MEDIUM") {
                        case "HIGH", "CRITICAL" -> ConfigurationRecommendation.Priority.HIGH;
                        case "LOW" -> ConfigurationRecommendation.Priority.LOW;
                        default -> ConfigurationRecommendation.Priority.MEDIUM;
                    };

                    Double improvementPct = null;
                    if (improvement != null) {
                        try {
                            improvementPct = Double.parseDouble(improvement.replaceAll("[^0-9.]", ""));
                        } catch (NumberFormatException ignored) {}
                    }

                    recommendations.add(ConfigurationRecommendation.builder()
                        .connectionId(connectionId)
                        .parameterName(knob)
                        .currentValue(current)
                        .recommendedValue(recommended)
                        .priority(p)
                        .reasoning(reason)
                        .estimatedImprovementPercent(improvementPct)
                        .requiresRestart(restart != null && restart.equalsIgnoreCase("YES"))
                        .category("Brain 2.0 ML")
                        .build());
                }
            } catch (Exception e) {
                log.debug("Could not parse recommendation block: {}", e.getMessage());
            }
        }

        return recommendations;
    }

    private String extractValue(String block, String prefix) {
        for (String line : block.split("\n")) {
            if (line.trim().startsWith(prefix)) {
                return line.substring(line.indexOf(prefix) + prefix.length()).trim();
            }
        }
        return null;
    }

    /**
     * Generate fallback recommendations when AI is unavailable.
     */
    private List<ConfigurationRecommendation> generateFallbackRecommendations(
            String connectionId,
            List<KnobRanking> topKnobs,
            Map<String, String> currentConfig) {

        List<ConfigurationRecommendation> recommendations = new ArrayList<>();

        for (KnobRanking knob : topKnobs.stream().limit(3).toList()) {
            // Simple rule-based recommendation
            String currentValue = currentConfig.get(knob.getKnobName());
            if (currentValue == null) continue;

            recommendations.add(ConfigurationRecommendation.builder()
                .connectionId(connectionId)
                .parameterName(knob.getKnobName())
                .currentValue(currentValue)
                .recommendedValue("(Requires detailed analysis)")
                .priority(knob.getRank() <= 2 ?
                    ConfigurationRecommendation.Priority.HIGH :
                    ConfigurationRecommendation.Priority.MEDIUM)
                .reasoning(String.format(
                    "Knob ranked #%d by ML analysis with impact score %.2f",
                    knob.getRank(), knob.getImpactScore()))
                .requiresRestart(knob.getRequiresRestart())
                .category("Brain 2.0 ML (Fallback)")
                .build());
        }

        return recommendations;
    }

    /**
     * Start a tuning experiment.
     */
    @Transactional
    public TuningExperiment startExperiment(
            String connectionId,
            Map<String, Map<String, String>> knobChanges,
            String recommendationId) {

        // Check for running experiment
        if (experimentRepository.existsByConnectionIdAndStatus(
                connectionId, TuningExperiment.ExperimentStatus.RUNNING)) {
            throw new IllegalStateException("An experiment is already running for this connection");
        }

        // Record baseline metrics
        Map<String, Double> baselineMetrics = collectCurrentMetrics(connectionId);

        TuningExperiment experiment = TuningExperiment.builder()
            .connectionId(connectionId)
            .knobChanges(knobChanges)
            .recommendationId(recommendationId)
            .baselineMetrics(baselineMetrics)
            .baselineLatencyP50(baselineMetrics.get("latency_p50"))
            .baselineLatencyP99(baselineMetrics.get("latency_p99"))
            .baselineThroughput(baselineMetrics.get("throughput"))
            .baselineCacheHitRatio(baselineMetrics.get("cache_hit_ratio"))
            .observationPeriodMinutes(30)
            .build();

        experiment.start();
        experimentRepository.save(experiment);

        log.info("Started tuning experiment {} for connection {}", experiment.getId(), connectionId);
        return experiment;
    }

    /**
     * Complete a tuning experiment.
     */
    @Transactional
    public TuningExperiment completeExperiment(String experimentId) {
        TuningExperiment experiment = experimentRepository.findById(experimentId)
            .orElseThrow(() -> new IllegalArgumentException("Experiment not found: " + experimentId));

        // Collect new metrics
        Map<String, Double> newMetrics = collectCurrentMetrics(experiment.getConnectionId());
        experiment.setNewMetrics(newMetrics);
        experiment.setNewLatencyP50(newMetrics.get("latency_p50"));
        experiment.setNewLatencyP99(newMetrics.get("latency_p99"));
        experiment.setNewThroughput(newMetrics.get("throughput"));
        experiment.setNewCacheHitRatio(newMetrics.get("cache_hit_ratio"));

        experiment.complete();

        // Save observation for future learning
        ConfigurationObservation observation = ConfigurationObservation.builder()
            .connectionId(experiment.getConnectionId())
            .configuration(extractConfiguration(experiment.getKnobChanges()))
            .metricsBefore(experiment.getBaselineMetrics())
            .metricsAfter(newMetrics)
            .latencyP50Before(experiment.getBaselineLatencyP50())
            .latencyP50After(experiment.getNewLatencyP50())
            .throughputBefore(experiment.getBaselineThroughput())
            .throughputAfter(experiment.getNewThroughput())
            .improvementPercent(experiment.getOverallImprovementPercent())
            .observationType(ConfigurationObservation.ObservationType.EXPERIMENT)
            .build();
        observationRepository.save(observation);

        // Create alert for result
        if (experiment.isSuccessful()) {
            alertRepository.save(BrainV2Alert.experimentSuccess(
                experiment.getConnectionId(),
                experiment.getOverallImprovementPercent()));
        }

        experimentRepository.save(experiment);

        log.info("Completed experiment {} with {}% improvement",
            experimentId, String.format("%.1f", experiment.getOverallImprovementPercent()));

        return experiment;
    }

    /**
     * Collect current database metrics for experiment baseline/completion.
     * Uses actual data from WorkloadMetricsSnapshot with proper rate-based calculations.
     */
    private Map<String, Double> collectCurrentMetrics(String connectionId) {
        Map<String, Double> metrics = new HashMap<>();

        try {
            ConnectionRequest connection = credentialService.getDecryptedConnection(connectionId);
            String dbType = providerRegistry.getCanonicalName(connection.getDbType());

            // Get recent workload metrics snapshots (last 5)
            List<WorkloadMetricsSnapshot> snapshots = snapshotRepository
                .findByConnectionIdOrderByCollectedAtDesc(connectionId, PageRequest.of(0, 5));

            if (snapshots.isEmpty()) {
                log.warn("No metrics snapshots for {}, triggering fresh collection", connectionId);
                WorkloadMetricsSnapshot fresh = metricsCollectorService.collectMetrics(connectionId);
                snapshots = List.of(fresh);
            }

            // Define metric keys based on database type
            List<String> gaugeKeys;
            List<String> counterKeys;

            if ("postgres".equals(dbType)) {
                gaugeKeys = List.of("cache_hit_ratio", "read_write_ratio", "numbackends");
                counterKeys = List.of("xact_commit", "xact_rollback", "tup_fetched", "tup_inserted",
                                       "tup_updated", "tup_deleted", "blks_read", "blks_hit");
            } else { // mysql - keys are LOWERCASE
                gaugeKeys = List.of("innodb_buffer_hit_ratio", "read_write_ratio", "threads_running");
                counterKeys = List.of("com_select", "com_insert", "com_update", "com_delete",
                                       "slow_queries", "innodb_rows_read");
            }

            // Collect gauge metrics (can average directly)
            Map<String, List<Double>> metricHistory = new HashMap<>();
            for (WorkloadMetricsSnapshot snapshot : snapshots) {
                Map<String, Object> raw = snapshot.getRawMetrics();
                if (raw == null) continue;

                for (String key : gaugeKeys) {
                    Object value = raw.get(key);
                    if (value instanceof Number) {
                        metricHistory.computeIfAbsent(key, k -> new ArrayList<>())
                            .add(((Number) value).doubleValue());
                    }
                }
            }

            // Compute gauge averages
            for (Map.Entry<String, List<Double>> entry : metricHistory.entrySet()) {
                double avg = entry.getValue().stream()
                    .mapToDouble(Double::doubleValue)
                    .average()
                    .orElse(0.0);
                metrics.put(entry.getKey(), avg);
            }

            // Compute counter rates (delta between first and last snapshot)
            if (snapshots.size() >= 2) {
                WorkloadMetricsSnapshot newest = snapshots.get(0);
                WorkloadMetricsSnapshot oldest = snapshots.get(snapshots.size() - 1);

                long intervalSeconds = Math.max(1,
                    Duration.between(oldest.getCollectedAt(), newest.getCollectedAt()).getSeconds());

                Map<String, Object> newestRaw = newest.getRawMetrics();
                Map<String, Object> oldestRaw = oldest.getRawMetrics();

                if (newestRaw != null && oldestRaw != null) {
                    double totalOps = 0;
                    for (String key : counterKeys) {
                        Object newVal = newestRaw.get(key);
                        Object oldVal = oldestRaw.get(key);
                        if (newVal instanceof Number && oldVal instanceof Number) {
                            double delta = ((Number) newVal).doubleValue() - ((Number) oldVal).doubleValue();
                            if (delta >= 0) {  // Handle counter resets
                                double rate = delta / intervalSeconds;
                                metrics.put(key + "_rate", rate);
                                // Sum for throughput calculation
                                if (key.startsWith("com_") || key.equals("xact_commit")) {
                                    totalOps += rate;
                                }
                            }
                        }
                    }
                    metrics.put("throughput_qps", totalOps);
                }
            }

            // Derive latency from pg_stat_statements if available (PostgreSQL)
            if ("postgres".equals(dbType) && !snapshots.isEmpty()) {
                Map<String, Object> raw = snapshots.get(0).getRawMetrics();
                if (raw != null) {
                    // pg_stat_statements metrics if available
                    Object avgMean = raw.get("pg_stat_avg_mean_exec_time");
                    if (avgMean instanceof Number) {
                        double latencyMs = ((Number) avgMean).doubleValue();
                        metrics.put("latency_avg_ms", latencyMs);
                        // Approximate P50/P99 from average (rough estimation)
                        metrics.put("latency_p50", latencyMs * 0.8);  // P50 typically lower than mean
                        metrics.put("latency_p99", latencyMs * 3.0);  // P99 typically 2-4x mean
                    }
                }
            }

            // Set cache_hit_ratio in standard key if not already set
            if (!metrics.containsKey("cache_hit_ratio") && metrics.containsKey("innodb_buffer_hit_ratio")) {
                metrics.put("cache_hit_ratio", metrics.get("innodb_buffer_hit_ratio"));
            }

            // Map throughput to standard key
            if (metrics.containsKey("throughput_qps")) {
                metrics.put("throughput", metrics.get("throughput_qps"));
            }

            log.info("Collected {} metrics for experiment on connection {}: cache_hit={}, throughput={}",
                metrics.size(), connectionId,
                String.format("%.2f", metrics.getOrDefault("cache_hit_ratio", 0.0)),
                String.format("%.1f", metrics.getOrDefault("throughput", 0.0)));

        } catch (Exception e) {
            log.error("Failed to collect metrics for {}: {}", connectionId, e.getMessage(), e);
            // Return zeros as fallback to avoid breaking experiment flow
            metrics.put("latency_p50", 0.0);
            metrics.put("latency_p99", 0.0);
            metrics.put("throughput", 0.0);
            metrics.put("cache_hit_ratio", 0.0);
        }

        return metrics;
    }

    private Map<String, String> extractConfiguration(Map<String, Map<String, String>> knobChanges) {
        Map<String, String> config = new HashMap<>();
        for (Map.Entry<String, Map<String, String>> entry : knobChanges.entrySet()) {
            config.put(entry.getKey(), entry.getValue().get("new"));
        }
        return config;
    }

    /**
     * Cancel/delete a tuning experiment.
     */
    @Transactional
    public void cancelExperiment(String experimentId) {
        TuningExperiment experiment = experimentRepository.findById(experimentId)
            .orElseThrow(() -> new IllegalArgumentException("Experiment not found: " + experimentId));

        log.info("Cancelling experiment {} (status: {})", experimentId, experiment.getStatus());
        experimentRepository.delete(experiment);
    }

    public String getConnectionId(String experimentId) {
        return experimentRepository.findById(experimentId)
            .map(TuningExperiment::getConnectionId)
            .orElseThrow(() -> new IllegalArgumentException("Experiment not found: " + experimentId));
    }

    /**
     * Get experiment history.
     */
    public List<TuningExperiment> getExperimentHistory(String connectionId, int limit) {
        return experimentRepository.findByConnectionIdOrderByCreatedAtDesc(
            connectionId, PageRequest.of(0, limit));
    }

    /**
     * Get success rate for experiments.
     */
    public double getExperimentSuccessRate(String connectionId) {
        long completed = experimentRepository.countByConnectionIdAndStatus(
            connectionId, TuningExperiment.ExperimentStatus.COMPLETED);
        if (completed == 0) return 0;

        List<TuningExperiment> successful = experimentRepository.findSuccessfulExperiments(
            connectionId, PageRequest.of(0, 1000));
        return (double) successful.size() / completed * 100;
    }
}
