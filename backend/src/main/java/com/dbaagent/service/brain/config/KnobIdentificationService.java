package com.dbaagent.service.brain.config;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.brain.ConfigurationObservation;
import com.dbaagent.model.brain.KnobRanking;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.repository.brain.ConfigurationObservationRepository;
import com.dbaagent.repository.brain.KnobRankingRepository;
import com.dbaagent.service.ConnectionService;
import com.dbaagent.service.CredentialService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Brain 2.0: Knob Identification Service
 *
 * Identifies which configuration parameters (knobs) have the most impact
 * on database performance. Uses statistical analysis to rank knobs.
 *
 * Inspired by OtterTune's Lasso regression approach for knob identification.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class KnobIdentificationService {

    private final KnobRankingRepository rankingRepository;
    private final ConfigurationObservationRepository observationRepository;
    private final ConnectionService connectionService;
    private final CredentialService credentialService;
    private final DatabaseProviderRegistry providerRegistry;

    // PostgreSQL knobs with their metadata
    private static final Map<String, KnobMetadata> POSTGRES_KNOBS = Map.ofEntries(
        Map.entry("shared_buffers", new KnobMetadata("128MB", "16MB", "16GB", KnobRanking.KnobValueType.MEMORY, true, 1.0)),
        Map.entry("effective_cache_size", new KnobMetadata("4GB", "64MB", "128GB", KnobRanking.KnobValueType.MEMORY, false, 0.8)),
        Map.entry("work_mem", new KnobMetadata("4MB", "1MB", "1GB", KnobRanking.KnobValueType.MEMORY, false, 0.9)),
        Map.entry("maintenance_work_mem", new KnobMetadata("64MB", "1MB", "2GB", KnobRanking.KnobValueType.MEMORY, false, 0.6)),
        Map.entry("wal_buffers", new KnobMetadata("16MB", "32kB", "2GB", KnobRanking.KnobValueType.MEMORY, true, 0.5)),
        Map.entry("max_connections", new KnobMetadata("100", "1", "10000", KnobRanking.KnobValueType.INTEGER, true, 0.7)),
        Map.entry("random_page_cost", new KnobMetadata("4", "0.1", "10", KnobRanking.KnobValueType.FLOAT, false, 0.85)),
        Map.entry("effective_io_concurrency", new KnobMetadata("1", "0", "1000", KnobRanking.KnobValueType.INTEGER, false, 0.7)),
        Map.entry("max_parallel_workers_per_gather", new KnobMetadata("2", "0", "1024", KnobRanking.KnobValueType.INTEGER, false, 0.6)),
        Map.entry("max_parallel_workers", new KnobMetadata("8", "0", "1024", KnobRanking.KnobValueType.INTEGER, true, 0.5)),
        Map.entry("checkpoint_completion_target", new KnobMetadata("0.9", "0", "1", KnobRanking.KnobValueType.FLOAT, false, 0.4)),
        Map.entry("wal_level", new KnobMetadata("replica", null, null, KnobRanking.KnobValueType.ENUM, true, 0.3)),
        Map.entry("synchronous_commit", new KnobMetadata("on", null, null, KnobRanking.KnobValueType.ENUM, false, 0.7)),
        Map.entry("default_statistics_target", new KnobMetadata("100", "1", "10000", KnobRanking.KnobValueType.INTEGER, false, 0.5))
    );

    // MySQL knobs with their metadata
    private static final Map<String, KnobMetadata> MYSQL_KNOBS = Map.ofEntries(
        Map.entry("innodb_buffer_pool_size", new KnobMetadata("128MB", "5MB", "18446744073709551615", KnobRanking.KnobValueType.MEMORY, true, 1.0)),
        Map.entry("innodb_log_file_size", new KnobMetadata("48MB", "4MB", "512GB", KnobRanking.KnobValueType.MEMORY, true, 0.85)),
        Map.entry("innodb_log_buffer_size", new KnobMetadata("16MB", "1MB", "4GB", KnobRanking.KnobValueType.MEMORY, true, 0.6)),
        Map.entry("innodb_flush_log_at_trx_commit", new KnobMetadata("1", "0", "2", KnobRanking.KnobValueType.INTEGER, false, 0.9)),
        Map.entry("innodb_io_capacity", new KnobMetadata("200", "100", "2000000", KnobRanking.KnobValueType.INTEGER, false, 0.7)),
        Map.entry("innodb_io_capacity_max", new KnobMetadata("2000", "100", "2000000", KnobRanking.KnobValueType.INTEGER, false, 0.6)),
        Map.entry("max_connections", new KnobMetadata("151", "1", "100000", KnobRanking.KnobValueType.INTEGER, false, 0.7)),
        Map.entry("tmp_table_size", new KnobMetadata("16MB", "1KB", "18446744073709551615", KnobRanking.KnobValueType.MEMORY, false, 0.6)),
        Map.entry("max_heap_table_size", new KnobMetadata("16MB", "16KB", "18446744073709551615", KnobRanking.KnobValueType.MEMORY, false, 0.6)),
        Map.entry("sort_buffer_size", new KnobMetadata("256KB", "32KB", "18446744073709551615", KnobRanking.KnobValueType.MEMORY, false, 0.5)),
        Map.entry("join_buffer_size", new KnobMetadata("256KB", "128", "18446744073709551615", KnobRanking.KnobValueType.MEMORY, false, 0.5)),
        Map.entry("innodb_thread_concurrency", new KnobMetadata("0", "0", "1000", KnobRanking.KnobValueType.INTEGER, false, 0.4)),
        Map.entry("innodb_read_io_threads", new KnobMetadata("4", "1", "64", KnobRanking.KnobValueType.INTEGER, true, 0.5)),
        Map.entry("innodb_write_io_threads", new KnobMetadata("4", "1", "64", KnobRanking.KnobValueType.INTEGER, true, 0.5))
    );

    /**
     * Identify and rank knobs for a connection.
     */
    @Transactional
    public List<KnobRanking> identifyKnobs(String connectionId, KnobRanking.TargetMetric targetMetric) {
        log.info("Identifying important knobs for connection: {} targeting: {}", connectionId, targetMetric);

        try {
            ConnectionRequest connection = credentialService.getDecryptedConnection(connectionId);
            String dbType = providerRegistry.getCanonicalName(connection.getDbType());
            JdbcTemplate jdbc = connectionService.getJdbcTemplate(connectionId, connection);

            // Get current configuration
            Map<String, String> currentConfig = getCurrentConfiguration(jdbc, dbType);

            // Get historical observations for learning
            List<ConfigurationObservation> observations = observationRepository
                .findByConnectionIdOrderByObservedAtDesc(connectionId, PageRequest.of(0, 100));

            // Calculate knob rankings
            List<KnobRanking> rankings = calculateRankings(
                connectionId, dbType, currentConfig, observations, targetMetric);

            // Delete old rankings for this metric
            rankingRepository.deleteByConnectionIdAndTargetMetric(connectionId, targetMetric);

            // Save new rankings
            rankingRepository.saveAll(rankings);

            log.info("Identified {} important knobs for {}", rankings.size(), connectionId);
            return rankings;

        } catch (Exception e) {
            log.error("Error identifying knobs for connection {}: {}", connectionId, e.getMessage(), e);
            throw new RuntimeException("Failed to identify knobs: " + e.getMessage(), e);
        }
    }

    /**
     * Get current database configuration.
     */
    private Map<String, String> getCurrentConfiguration(JdbcTemplate jdbc, String dbType) {
        Map<String, String> config = new HashMap<>();

        if ("postgres".equals(dbType)) {
            try {
                List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT name, setting, unit FROM pg_settings WHERE name IN (" +
                    POSTGRES_KNOBS.keySet().stream().map(k -> "'" + k + "'").collect(Collectors.joining(",")) +
                    ")"
                );
                for (Map<String, Object> row : rows) {
                    String name = (String) row.get("name");
                    String setting = (String) row.get("setting");
                    String unit = (String) row.get("unit");
                    config.put(name, setting + (unit != null ? unit : ""));
                }
            } catch (Exception e) {
                log.warn("Could not get PostgreSQL settings: {}", e.getMessage());
            }
        } else if ("mysql".equals(dbType)) {
            try {
                List<Map<String, Object>> rows = jdbc.queryForList("SHOW GLOBAL VARIABLES");
                for (Map<String, Object> row : rows) {
                    String name = (String) row.get("Variable_name");
                    String value = (String) row.get("Value");
                    if (MYSQL_KNOBS.containsKey(name)) {
                        config.put(name, value);
                    }
                }
            } catch (Exception e) {
                log.warn("Could not get MySQL variables: {}", e.getMessage());
            }
        }

        return config;
    }

    /**
     * Calculate rankings using a simplified Lasso-inspired approach.
     */
    private List<KnobRanking> calculateRankings(
            String connectionId,
            String dbType,
            Map<String, String> currentConfig,
            List<ConfigurationObservation> observations,
            KnobRanking.TargetMetric targetMetric) {

        Map<String, KnobMetadata> knobMetadata = "postgres".equals(dbType) ?
            POSTGRES_KNOBS : MYSQL_KNOBS;

        List<KnobRanking> rankings = new ArrayList<>();
        int rank = 1;

        // Calculate impact scores
        Map<String, Double> impactScores = new HashMap<>();

        for (Map.Entry<String, KnobMetadata> entry : knobMetadata.entrySet()) {
            String knobName = entry.getKey();
            KnobMetadata metadata = entry.getValue();

            // Start with base importance
            double baseImpact = metadata.baseImportance;

            // Adjust based on historical observations if available
            if (!observations.isEmpty()) {
                double observedImpact = calculateObservedImpact(
                    knobName, observations, targetMetric);
                if (observedImpact > 0) {
                    // Blend base importance with observed impact
                    baseImpact = (baseImpact * 0.3) + (observedImpact * 0.7);
                }
            }

            // Adjust based on target metric
            baseImpact *= getMetricMultiplier(knobName, targetMetric);

            impactScores.put(knobName, baseImpact);
        }

        // Sort by impact and create rankings
        List<Map.Entry<String, Double>> sortedKnobs = impactScores.entrySet().stream()
            .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
            .collect(Collectors.toList());

        for (Map.Entry<String, Double> entry : sortedKnobs) {
            String knobName = entry.getKey();
            Double impactScore = entry.getValue();
            KnobMetadata metadata = knobMetadata.get(knobName);

            KnobRanking ranking = KnobRanking.builder()
                .connectionId(connectionId)
                .knobName(knobName)
                .dbType(dbType)
                .rank(rank++)
                .impactScore(impactScore)
                .targetMetric(targetMetric)
                .currentValue(currentConfig.get(knobName))
                .defaultValue(metadata.defaultValue)
                .minValue(metadata.minValue)
                .maxValue(metadata.maxValue)
                .valueType(metadata.valueType)
                .requiresRestart(metadata.requiresRestart)
                .confidenceScore(calculateConfidence(observations.size(), knobName))
                .sampleCount(observations.size())
                .calculatedAt(LocalDateTime.now())
                .build();

            rankings.add(ranking);
        }

        return rankings;
    }

    /**
     * Calculate observed impact from historical observations.
     */
    private double calculateObservedImpact(
            String knobName,
            List<ConfigurationObservation> observations,
            KnobRanking.TargetMetric targetMetric) {

        // Look for observations where this knob was changed
        double totalImpact = 0;
        int impactCount = 0;

        for (int i = 1; i < observations.size(); i++) {
            ConfigurationObservation current = observations.get(i);
            ConfigurationObservation previous = observations.get(i - 1);

            Map<String, String> currConfig = current.getConfiguration();
            Map<String, String> prevConfig = previous.getConfiguration();

            if (currConfig == null || prevConfig == null) continue;

            String currValue = currConfig.get(knobName);
            String prevValue = prevConfig.get(knobName);

            // Check if this knob was changed
            if (currValue != null && !currValue.equals(prevValue)) {
                // Calculate impact based on performance change
                Double improvement = current.getImprovementPercent();
                if (improvement != null) {
                    totalImpact += Math.abs(improvement);
                    impactCount++;
                }
            }
        }

        return impactCount > 0 ? totalImpact / impactCount : 0;
    }

    /**
     * Get multiplier based on target metric.
     */
    private double getMetricMultiplier(String knobName, KnobRanking.TargetMetric targetMetric) {
        return switch (targetMetric) {
            case LATENCY -> switch (knobName) {
                case "shared_buffers", "innodb_buffer_pool_size" -> 1.2;
                case "work_mem", "sort_buffer_size" -> 1.1;
                case "random_page_cost" -> 1.3;
                default -> 1.0;
            };
            case THROUGHPUT -> switch (knobName) {
                case "max_connections" -> 1.3;
                case "max_parallel_workers_per_gather", "innodb_thread_concurrency" -> 1.2;
                case "effective_io_concurrency", "innodb_io_capacity" -> 1.1;
                default -> 1.0;
            };
            case CACHE_HIT_RATIO -> switch (knobName) {
                case "shared_buffers", "innodb_buffer_pool_size" -> 1.5;
                case "effective_cache_size" -> 1.3;
                default -> 1.0;
            };
            default -> 1.0;
        };
    }

    /**
     * Calculate confidence score based on data availability.
     */
    private double calculateConfidence(int observationCount, String knobName) {
        double dataConfidence = Math.min(1.0, observationCount / 50.0);

        // Well-known knobs have higher base confidence
        double knobConfidence = 0.6;
        if (knobName.contains("buffer") || knobName.contains("memory") ||
            knobName.contains("cache")) {
            knobConfidence = 0.8;
        }

        return (dataConfidence * 0.5 + knobConfidence * 0.5) * 100;
    }

    /**
     * Get top ranked knobs for a connection.
     */
    public List<KnobRanking> getTopKnobs(String connectionId, KnobRanking.TargetMetric targetMetric, int limit) {
        return rankingRepository.findTopKnobs(connectionId, targetMetric, PageRequest.of(0, limit));
    }

    /**
     * Get all rankings for a connection.
     */
    public List<KnobRanking> getAllRankings(String connectionId) {
        return rankingRepository.findByConnectionIdOrderByTargetMetricAscRankAsc(connectionId);
    }

    /**
     * Knob metadata holder.
     */
    private record KnobMetadata(
        String defaultValue,
        String minValue,
        String maxValue,
        KnobRanking.KnobValueType valueType,
        boolean requiresRestart,
        double baseImportance
    ) {}
}
