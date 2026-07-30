package com.dbaagent.provider.postgres;

import com.dbaagent.provider.api.ConfigurationProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.*;

/**
 * PostgreSQL implementation of ConfigurationProvider.
 * Handles PostgreSQL-specific configuration analysis.
 */
@Slf4j
@Component
public class PostgresConfigurationProvider implements ConfigurationProvider {

    private static final String DATABASE_TYPE = "postgres";

    @Override
    public String getDatabaseType() {
        return DATABASE_TYPE;
    }

    @Override
    public Map<String, String> getConfigurationParameters(Connection connection) throws SQLException {
        Map<String, String> params = new HashMap<>();

        String query = "SELECT name, setting FROM pg_settings";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                params.put(rs.getString("name"), rs.getString("setting"));
            }
        }

        return params;
    }

    @Override
    public String getParameter(Connection connection, String parameterName) throws SQLException {
        String query = "SELECT setting FROM pg_settings WHERE name = ?";
        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, parameterName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("setting");
                }
            }
        }
        return null;
    }

    @Override
    public Map<String, String> getMemoryConfiguration(Connection connection) throws SQLException {
        Map<String, String> memoryConfig = new HashMap<>();

        String[] memoryParams = {
            "shared_buffers",
            "effective_cache_size",
            "work_mem",
            "maintenance_work_mem",
            "wal_buffers",
            "max_connections",
            "max_parallel_workers",
            "max_parallel_workers_per_gather",
            "effective_io_concurrency",
            "random_page_cost",
            "seq_page_cost",
            "temp_buffers",
            "huge_pages"
        };

        for (String param : memoryParams) {
            String value = getParameter(connection, param);
            if (value != null) {
                memoryConfig.put(param, value);
            }
        }

        return memoryConfig;
    }

    @Override
    public List<Map<String, Object>> analyzeConfiguration(Connection connection) throws SQLException {
        List<Map<String, Object>> recommendations = new ArrayList<>();
        Map<String, String> config = getMemoryConfiguration(connection);

        // Check shared_buffers
        String sharedBuffers = config.get("shared_buffers");
        if (sharedBuffers != null) {
            long sharedBuffersBytes = parseMemoryValue(sharedBuffers);
            long sharedBuffersMB = sharedBuffersBytes / (1024 * 1024);

            if (sharedBuffersMB < 256) {
                Map<String, Object> rec = new HashMap<>();
                rec.put("parameter", "shared_buffers");
                rec.put("currentValue", sharedBuffers);
                rec.put("recommendedValue", "25% of total RAM (min 256MB)");
                rec.put("priority", "HIGH");
                rec.put("reasoning", "shared_buffers is too small. Recommended: 25% of RAM for dedicated servers.");
                recommendations.add(rec);
            }
        }

        // Check effective_cache_size
        String effectiveCacheSize = config.get("effective_cache_size");
        String sharedBuffersVal = config.get("shared_buffers");
        if (effectiveCacheSize != null && sharedBuffersVal != null) {
            long cacheBytes = parseMemoryValue(effectiveCacheSize);
            long sharedBytes = parseMemoryValue(sharedBuffersVal);

            if (cacheBytes < sharedBytes * 2) {
                Map<String, Object> rec = new HashMap<>();
                rec.put("parameter", "effective_cache_size");
                rec.put("currentValue", effectiveCacheSize);
                rec.put("recommendedValue", "50-75% of total RAM");
                rec.put("priority", "MEDIUM");
                rec.put("reasoning", "effective_cache_size should be larger. This helps the planner make better decisions.");
                recommendations.add(rec);
            }
        }

        // Check work_mem
        String workMem = config.get("work_mem");
        String maxConnections = config.get("max_connections");
        if (workMem != null && maxConnections != null) {
            long workMemBytes = parseMemoryValue(workMem);
            int maxConn = Integer.parseInt(maxConnections);

            // work_mem * max_connections should not exceed available RAM
            // This is a simplified check
            if (workMemBytes < 4 * 1024 * 1024) { // Less than 4MB
                Map<String, Object> rec = new HashMap<>();
                rec.put("parameter", "work_mem");
                rec.put("currentValue", workMem);
                rec.put("recommendedValue", "4MB - 64MB depending on query complexity");
                rec.put("priority", "LOW");
                rec.put("reasoning", "work_mem is low. Complex queries may spill to disk. Consider increasing for faster sorts/hashes.");
                recommendations.add(rec);
            }
        }

        // Check maintenance_work_mem
        String maintenanceWorkMem = config.get("maintenance_work_mem");
        if (maintenanceWorkMem != null) {
            long maintMemBytes = parseMemoryValue(maintenanceWorkMem);

            if (maintMemBytes < 256 * 1024 * 1024) { // Less than 256MB
                Map<String, Object> rec = new HashMap<>();
                rec.put("parameter", "maintenance_work_mem");
                rec.put("currentValue", maintenanceWorkMem);
                rec.put("recommendedValue", "256MB - 1GB");
                rec.put("priority", "LOW");
                rec.put("reasoning", "Increasing maintenance_work_mem speeds up VACUUM, CREATE INDEX, and ALTER TABLE operations.");
                recommendations.add(rec);
            }
        }

        // Check random_page_cost for SSD
        String randomPageCost = config.get("random_page_cost");
        if (randomPageCost != null) {
            double cost = Double.parseDouble(randomPageCost);
            if (cost > 1.5) {
                Map<String, Object> rec = new HashMap<>();
                rec.put("parameter", "random_page_cost");
                rec.put("currentValue", randomPageCost);
                rec.put("recommendedValue", "1.1 - 1.5 for SSDs");
                rec.put("priority", "MEDIUM");
                rec.put("reasoning", "If using SSDs, lower random_page_cost improves index usage decisions.");
                recommendations.add(rec);
            }
        }

        return recommendations;
    }

    @Override
    public String getDatabaseVersion(Connection connection) throws SQLException {
        String query = "SELECT version()";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            if (rs.next()) {
                return rs.getString(1);
            }
        }
        return null;
    }

    private long parseMemoryValue(String value) {
        if (value == null || value.isEmpty()) return 0;

        value = value.trim().toUpperCase();

        try {
            if (value.endsWith("GB")) {
                return (long) (Double.parseDouble(value.replace("GB", "").trim()) * 1024 * 1024 * 1024);
            } else if (value.endsWith("MB")) {
                return (long) (Double.parseDouble(value.replace("MB", "").trim()) * 1024 * 1024);
            } else if (value.endsWith("KB")) {
                return (long) (Double.parseDouble(value.replace("KB", "").trim()) * 1024);
            } else if (value.endsWith("B")) {
                return Long.parseLong(value.replace("B", "").trim());
            } else {
                // PostgreSQL reports some values in 8KB blocks
                return Long.parseLong(value) * 8 * 1024;
            }
        } catch (NumberFormatException e) {
            log.debug("Could not parse memory value: {}", value);
            return 0;
        }
    }
}
