package com.dbaagent.service;

import com.dbaagent.model.ConfigurationRecommendation;
import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.repository.ConfigurationRecommendationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Service for analyzing database configuration and generating tuning recommendations
 * Supports both PostgreSQL and MySQL
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DatabaseConfigurationService {

    private final ConfigurationRecommendationRepository recommendationRepository;
    private final CredentialService credentialService;
    private final ConnectionService connectionService;
    private final DatabaseProviderRegistry providerRegistry;

    private static final long MB = 1024 * 1024;
    private static final long GB = 1024 * MB;

    /**
     * Analyze database configuration and generate recommendations
     */
    @Transactional
    public List<ConfigurationRecommendation> analyzeConfiguration(String connectionId) {
        log.info("Analyzing configuration for connection: {}", connectionId);

        try {
            // Get connection with credentials
            ConnectionRequest connRequest = credentialService.getDecryptedConnection(connectionId);

            // Create connection
            try (Connection conn = connectionService.getConnection(connectionId, connRequest)) {
                // Use canonical name from connection request for consistent alias handling
                String dbType = providerRegistry.getCanonicalName(connRequest.getDbType());
                String productName = conn.getMetaData().getDatabaseProductName();

                // Get instance specifications
                InstanceSpecs specs = detectInstanceSpecs(conn, productName);

                // Get current settings
                Map<String, String> currentSettings = getCurrentSettings(conn, productName);

                // Generate recommendations
                List<ConfigurationRecommendation> recommendations = new ArrayList<>();

                if ("postgres".equals(dbType)) {
                    recommendations.addAll(generatePostgreSQLRecommendations(connectionId, specs, currentSettings));
                } else if ("mysql".equals(dbType)) {
                    recommendations.addAll(generateMySQLRecommendations(connectionId, specs, currentSettings));
                }

                // Save recommendations
                List<ConfigurationRecommendation> saved = recommendationRepository.saveAll(recommendations);
                log.info("Generated {} configuration recommendations", saved.size());

                return saved;
            }
        } catch (Exception e) {
            log.error("Error analyzing configuration: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to analyze configuration: " + e.getMessage(), e);
        }
    }

    /**
     * Detect instance specifications (CPU, RAM, disk type)
     */
    private InstanceSpecs detectInstanceSpecs(Connection conn, String dbType) throws Exception {
        InstanceSpecs specs = new InstanceSpecs();

        try (Statement stmt = conn.createStatement()) {
            if (dbType.toLowerCase().contains("postgres")) {
                // Get PostgreSQL system info
                // CPU cores
                ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM pg_stat_activity");
                // Approximate RAM from shared_buffers
                rs = stmt.executeQuery("SHOW shared_buffers");
                if (rs.next()) {
                    String value = rs.getString(1);
                    specs.totalRamMB = parseMemoryValue(value) * 4; // Estimate total RAM
                }

                // Get actual RAM if available
                rs = stmt.executeQuery("SELECT setting::bigint * 8192 / 1024 / 1024 as ram_mb " +
                                      "FROM pg_settings WHERE name = 'shared_buffers'");
                if (rs.next()) {
                    long sharedBuffers = rs.getLong("ram_mb");
                    specs.totalRamMB = Math.max(specs.totalRamMB, sharedBuffers * 4);
                }

                // Assume SSD for modern systems (can be enhanced with actual detection)
                specs.diskType = "SSD";
                specs.cpuCores = Runtime.getRuntime().availableProcessors(); // Fallback

            } else if (dbType.toLowerCase().contains("mysql")) {
                // Get MySQL system info
                ResultSet rs = stmt.executeQuery("SHOW VARIABLES LIKE 'innodb_buffer_pool_size'");
                if (rs.next()) {
                    long bufferPool = Long.parseLong(rs.getString("Value"));
                    specs.totalRamMB = (bufferPool / MB) * 2; // Estimate total RAM
                }

                specs.diskType = "SSD";
                specs.cpuCores = Runtime.getRuntime().availableProcessors();
            }
        }

        // Defaults if detection failed
        if (specs.totalRamMB == 0) {
            specs.totalRamMB = 4096; // 4GB default
        }
        if (specs.cpuCores == 0) {
            specs.cpuCores = 4; // 4 cores default
        }

        log.info("Detected specs: RAM={}MB, CPU={} cores, Disk={}",
                 specs.totalRamMB, specs.cpuCores, specs.diskType);

        return specs;
    }

    /**
     * Get current database settings
     */
    private Map<String, String> getCurrentSettings(Connection conn, String dbType) throws Exception {
        Map<String, String> settings = new HashMap<>();

        try (Statement stmt = conn.createStatement()) {
            if (dbType.toLowerCase().contains("postgres")) {
                ResultSet rs = stmt.executeQuery("SELECT name, setting, unit FROM pg_settings " +
                                                "WHERE name IN ('shared_buffers', 'effective_cache_size', " +
                                                "'work_mem', 'maintenance_work_mem', 'wal_buffers', " +
                                                "'max_connections', 'max_parallel_workers_per_gather', " +
                                                "'max_parallel_workers', 'random_page_cost', " +
                                                "'effective_io_concurrency', 'checkpoint_completion_target', " +
                                                "'min_wal_size', 'max_wal_size')");
                while (rs.next()) {
                    String name = rs.getString("name");
                    String value = rs.getString("setting");
                    String unit = rs.getString("unit");
                    settings.put(name, value + (unit != null ? unit : ""));
                }
            } else if (dbType.toLowerCase().contains("mysql")) {
                ResultSet rs = stmt.executeQuery("SHOW VARIABLES WHERE Variable_name IN " +
                                                "('innodb_buffer_pool_size', 'innodb_log_file_size', " +
                                                "'innodb_log_buffer_size', 'max_connections', " +
                                                "'tmp_table_size', 'max_heap_table_size', " +
                                                "'innodb_io_capacity', 'innodb_flush_log_at_trx_commit', " +
                                                "'innodb_file_per_table')");
                while (rs.next()) {
                    settings.put(rs.getString("Variable_name"), rs.getString("Value"));
                }
            }
        }

        return settings;
    }

    /**
     * Generate PostgreSQL-specific recommendations
     */
    private List<ConfigurationRecommendation> generatePostgreSQLRecommendations(
            String connectionId, InstanceSpecs specs, Map<String, String> current) {

        List<ConfigurationRecommendation> recommendations = new ArrayList<>();

        // shared_buffers: 25% of RAM
        long recommendedSharedBuffers = (specs.totalRamMB * 25 / 100);
        long currentSharedBuffers = parseMemoryValue(current.getOrDefault("shared_buffers", "0"));
        if (Math.abs(currentSharedBuffers - recommendedSharedBuffers) > recommendedSharedBuffers * 0.2) {
            recommendations.add(ConfigurationRecommendation.builder()
                .connectionId(connectionId)
                .parameterName("shared_buffers")
                .currentValue(currentSharedBuffers + "MB")
                .recommendedValue(recommendedSharedBuffers + "MB")
                .priority(ConfigurationRecommendation.Priority.HIGH)
                .reasoning("Shared buffers should be ~25% of total RAM for optimal caching")
                .impactDescription("Improves cache hit ratio and reduces disk I/O")
                .applySql("ALTER SYSTEM SET shared_buffers = '" + recommendedSharedBuffers + "MB';")
                .estimatedImprovementPercent(15.0)
                .category("Memory")
                .requiresRestart(true)
                .build());
        }

        // effective_cache_size: 50-75% of RAM
        long recommendedCacheSize = (specs.totalRamMB * 65 / 100);
        long currentCacheSize = parseMemoryValue(current.getOrDefault("effective_cache_size", "0"));
        if (Math.abs(currentCacheSize - recommendedCacheSize) > recommendedCacheSize * 0.2) {
            recommendations.add(ConfigurationRecommendation.builder()
                .connectionId(connectionId)
                .parameterName("effective_cache_size")
                .currentValue(currentCacheSize + "MB")
                .recommendedValue(recommendedCacheSize + "MB")
                .priority(ConfigurationRecommendation.Priority.MEDIUM)
                .reasoning("Helps query planner make better decisions about index usage")
                .impactDescription("Improves query planning and execution")
                .applySql("ALTER SYSTEM SET effective_cache_size = '" + recommendedCacheSize + "MB';")
                .estimatedImprovementPercent(10.0)
                .category("Memory")
                .requiresRestart(false)
                .build());
        }

        // work_mem: RAM / max_connections / 4
        int maxConnections = Integer.parseInt(current.getOrDefault("max_connections", "100"));
        long recommendedWorkMem = (specs.totalRamMB / maxConnections / 4);
        recommendedWorkMem = Math.max(4, Math.min(recommendedWorkMem, 256)); // Clamp between 4MB and 256MB
        long currentWorkMem = parseMemoryValue(current.getOrDefault("work_mem", "0"));
        if (Math.abs(currentWorkMem - recommendedWorkMem) > recommendedWorkMem * 0.3) {
            recommendations.add(ConfigurationRecommendation.builder()
                .connectionId(connectionId)
                .parameterName("work_mem")
                .currentValue(currentWorkMem + "MB")
                .recommendedValue(recommendedWorkMem + "MB")
                .priority(ConfigurationRecommendation.Priority.MEDIUM)
                .reasoning("Memory for sort and hash operations per connection")
                .impactDescription("Speeds up sorting and joins, reduces temp file usage")
                .applySql("ALTER SYSTEM SET work_mem = '" + recommendedWorkMem + "MB';")
                .estimatedImprovementPercent(8.0)
                .category("Memory")
                .requiresRestart(false)
                .build());
        }

        // maintenance_work_mem: RAM / 16
        long recommendedMaintenanceMem = Math.min(2048, specs.totalRamMB / 16);
        long currentMaintenanceMem = parseMemoryValue(current.getOrDefault("maintenance_work_mem", "0"));
        if (Math.abs(currentMaintenanceMem - recommendedMaintenanceMem) > recommendedMaintenanceMem * 0.3) {
            recommendations.add(ConfigurationRecommendation.builder()
                .connectionId(connectionId)
                .parameterName("maintenance_work_mem")
                .currentValue(currentMaintenanceMem + "MB")
                .recommendedValue(recommendedMaintenanceMem + "MB")
                .priority(ConfigurationRecommendation.Priority.LOW)
                .reasoning("Memory for VACUUM, CREATE INDEX, and maintenance operations")
                .impactDescription("Speeds up index creation and VACUUM operations")
                .applySql("ALTER SYSTEM SET maintenance_work_mem = '" + recommendedMaintenanceMem + "MB';")
                .estimatedImprovementPercent(5.0)
                .category("Memory")
                .requiresRestart(false)
                .build());
        }

        // random_page_cost: 1.1 for SSD, 4.0 for HDD
        double recommendedPageCost = specs.diskType.equals("SSD") ? 1.1 : 4.0;
        double currentPageCost = Double.parseDouble(current.getOrDefault("random_page_cost", "4.0"));
        if (Math.abs(currentPageCost - recommendedPageCost) > 0.5) {
            recommendations.add(ConfigurationRecommendation.builder()
                .connectionId(connectionId)
                .parameterName("random_page_cost")
                .currentValue(String.valueOf(currentPageCost))
                .recommendedValue(String.valueOf(recommendedPageCost))
                .priority(ConfigurationRecommendation.Priority.HIGH)
                .reasoning("Lower cost for SSD encourages index usage over sequential scans")
                .impactDescription("Query planner will prefer index scans, improving performance")
                .applySql("ALTER SYSTEM SET random_page_cost = " + recommendedPageCost + ";")
                .estimatedImprovementPercent(12.0)
                .category("I/O")
                .requiresRestart(false)
                .build());
        }

        // effective_io_concurrency: 200 for SSD, 2 for HDD
        int recommendedIOConcurrency = specs.diskType.equals("SSD") ? 200 : 2;
        int currentIOConcurrency = Integer.parseInt(current.getOrDefault("effective_io_concurrency", "1"));
        if (Math.abs(currentIOConcurrency - recommendedIOConcurrency) > 50) {
            recommendations.add(ConfigurationRecommendation.builder()
                .connectionId(connectionId)
                .parameterName("effective_io_concurrency")
                .currentValue(String.valueOf(currentIOConcurrency))
                .recommendedValue(String.valueOf(recommendedIOConcurrency))
                .priority(ConfigurationRecommendation.Priority.MEDIUM)
                .reasoning("Number of concurrent disk I/O operations for " + specs.diskType)
                .impactDescription("Better utilizes SSD parallel I/O capabilities")
                .applySql("ALTER SYSTEM SET effective_io_concurrency = " + recommendedIOConcurrency + ";")
                .estimatedImprovementPercent(7.0)
                .category("I/O")
                .requiresRestart(false)
                .build());
        }

        // max_parallel_workers_per_gather: CPU cores / 2
        int recommendedParallelWorkers = Math.max(2, specs.cpuCores / 2);
        int currentParallelWorkers = Integer.parseInt(current.getOrDefault("max_parallel_workers_per_gather", "0"));
        if (currentParallelWorkers < recommendedParallelWorkers) {
            recommendations.add(ConfigurationRecommendation.builder()
                .connectionId(connectionId)
                .parameterName("max_parallel_workers_per_gather")
                .currentValue(String.valueOf(currentParallelWorkers))
                .recommendedValue(String.valueOf(recommendedParallelWorkers))
                .priority(ConfigurationRecommendation.Priority.MEDIUM)
                .reasoning("Enables parallel query execution on multi-core systems")
                .impactDescription("Large scans and aggregations will use multiple CPU cores")
                .applySql("ALTER SYSTEM SET max_parallel_workers_per_gather = " + recommendedParallelWorkers + ";")
                .estimatedImprovementPercent(10.0)
                .category("CPU")
                .requiresRestart(false)
                .build());
        }

        return recommendations;
    }

    /**
     * Generate MySQL-specific recommendations
     */
    private List<ConfigurationRecommendation> generateMySQLRecommendations(
            String connectionId, InstanceSpecs specs, Map<String, String> current) {

        List<ConfigurationRecommendation> recommendations = new ArrayList<>();

        // innodb_buffer_pool_size: 70-80% of RAM
        long recommendedBufferPool = (specs.totalRamMB * 75 / 100) * MB;
        long currentBufferPool = Long.parseLong(current.getOrDefault("innodb_buffer_pool_size", "0"));
        if (Math.abs(currentBufferPool - recommendedBufferPool) > recommendedBufferPool * 0.2) {
            recommendations.add(ConfigurationRecommendation.builder()
                .connectionId(connectionId)
                .parameterName("innodb_buffer_pool_size")
                .currentValue((currentBufferPool / MB) + "MB")
                .recommendedValue((recommendedBufferPool / MB) + "MB")
                .priority(ConfigurationRecommendation.Priority.CRITICAL)
                .reasoning("InnoDB buffer pool should be 70-80% of RAM for optimal caching")
                .impactDescription("Dramatically reduces disk I/O by caching data and indexes in memory")
                .applySql("SET GLOBAL innodb_buffer_pool_size = " + recommendedBufferPool + ";")
                .estimatedImprovementPercent(25.0)
                .category("Memory")
                .requiresRestart(true)
                .build());
        }

        // innodb_log_file_size: 256MB - 2GB
        long recommendedLogSize = Math.min(2048L * MB, Math.max(256L * MB, specs.totalRamMB * MB / 8));
        long currentLogSize = Long.parseLong(current.getOrDefault("innodb_log_file_size", "0"));
        if (Math.abs(currentLogSize - recommendedLogSize) > recommendedLogSize * 0.3) {
            recommendations.add(ConfigurationRecommendation.builder()
                .connectionId(connectionId)
                .parameterName("innodb_log_file_size")
                .currentValue((currentLogSize / MB) + "MB")
                .recommendedValue((recommendedLogSize / MB) + "MB")
                .priority(ConfigurationRecommendation.Priority.HIGH)
                .reasoning("Larger log files reduce checkpoint frequency and improve write performance")
                .impactDescription("Reduces I/O during heavy write workloads")
                .applySql("SET GLOBAL innodb_log_file_size = " + recommendedLogSize + ";")
                .estimatedImprovementPercent(15.0)
                .category("I/O")
                .requiresRestart(true)
                .build());
        }

        // innodb_io_capacity: 200 for SSD, 100 for HDD
        int recommendedIOCapacity = specs.diskType.equals("SSD") ? 2000 : 200;
        int currentIOCapacity = Integer.parseInt(current.getOrDefault("innodb_io_capacity", "200"));
        if (Math.abs(currentIOCapacity - recommendedIOCapacity) > 500) {
            recommendations.add(ConfigurationRecommendation.builder()
                .connectionId(connectionId)
                .parameterName("innodb_io_capacity")
                .currentValue(String.valueOf(currentIOCapacity))
                .recommendedValue(String.valueOf(recommendedIOCapacity))
                .priority(ConfigurationRecommendation.Priority.MEDIUM)
                .reasoning("I/O operations per second for background tasks on " + specs.diskType)
                .impactDescription("Better utilizes SSD performance for flushing and purging")
                .applySql("SET GLOBAL innodb_io_capacity = " + recommendedIOCapacity + ";")
                .estimatedImprovementPercent(8.0)
                .category("I/O")
                .requiresRestart(false)
                .build());
        }

        return recommendations;
    }

    /**
     * Parse memory value with unit (e.g., "128MB", "1GB", "8192kB")
     */
    private long parseMemoryValue(String value) {
        if (value == null || value.isEmpty()) {
            return 0;
        }

        value = value.trim().toUpperCase();

        try {
            if (value.endsWith("GB")) {
                return Long.parseLong(value.replace("GB", "")) * 1024;
            } else if (value.endsWith("MB")) {
                return Long.parseLong(value.replace("MB", ""));
            } else if (value.endsWith("KB")) {
                return Long.parseLong(value.replace("KB", "")) / 1024;
            } else if (value.endsWith("8KB")) {
                // PostgreSQL sometimes uses 8kB blocks
                return Long.parseLong(value.replace("8KB", "")) * 8 / 1024;
            } else {
                // Assume bytes, convert to MB
                return Long.parseLong(value) / (1024 * 1024);
            }
        } catch (NumberFormatException e) {
            log.warn("Failed to parse memory value: {}", value);
            return 0;
        }
    }

    /**
     * Get all recommendations for a connection
     */
    @Transactional(readOnly = true)
    public List<ConfigurationRecommendation> getRecommendations(String connectionId) {
        return recommendationRepository.findLatestByConnectionId(connectionId);
    }

    /**
     * Get pending recommendations
     */
    @Transactional(readOnly = true)
    public List<ConfigurationRecommendation> getPendingRecommendations(String connectionId) {
        return recommendationRepository.findByConnectionIdAndStatusOrderByPriorityAscCreatedAtDesc(
            connectionId,
            ConfigurationRecommendation.Status.PENDING
        );
    }

    /**
     * Load recommendation by id or throw.
     */
    public ConfigurationRecommendation requireById(String id) {
        return recommendationRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Recommendation not found: " + id));
    }

    /**
     * Mark recommendation as applied
     */
    @Transactional
    public ConfigurationRecommendation markAsApplied(String id) {
        ConfigurationRecommendation recommendation = requireById(id);

        recommendation.markAsApplied();
        return recommendationRepository.save(recommendation);
    }

    /**
     * Dismiss recommendation
     */
    @Transactional
    public ConfigurationRecommendation dismissRecommendation(String id) {
        ConfigurationRecommendation recommendation = recommendationRepository.findById(id)
            .orElseThrow(() -> new IllegalArgumentException("Recommendation not found: " + id));

        recommendation.dismiss();
        return recommendationRepository.save(recommendation);
    }

    /**
     * Delete recommendation
     */
    @Transactional
    public void deleteRecommendation(String id) {
        recommendationRepository.deleteById(id);
    }

    /**
     * Internal class to hold instance specifications
     */
    private static class InstanceSpecs {
        long totalRamMB;
        int cpuCores;
        String diskType = "SSD"; // Default to SSD
    }
}
