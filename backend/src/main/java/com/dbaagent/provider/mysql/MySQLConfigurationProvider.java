package com.dbaagent.provider.mysql;

import com.dbaagent.provider.api.ConfigurationProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.*;

/**
 * MySQL implementation of ConfigurationProvider.
 * Handles MySQL-specific configuration analysis.
 */
@Slf4j
@Component
public class MySQLConfigurationProvider implements ConfigurationProvider {

    private static final String DATABASE_TYPE = "mysql";

    @Override
    public String getDatabaseType() {
        return DATABASE_TYPE;
    }

    @Override
    public Map<String, String> getConfigurationParameters(Connection connection) throws SQLException {
        Map<String, String> params = new HashMap<>();

        String query = "SHOW GLOBAL VARIABLES";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                params.put(rs.getString(1), rs.getString(2));
            }
        }

        return params;
    }

    @Override
    public String getParameter(Connection connection, String parameterName) throws SQLException {
        String query = "SHOW GLOBAL VARIABLES LIKE ?";
        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, parameterName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(2);
                }
            }
        }
        return null;
    }

    @Override
    public Map<String, String> getMemoryConfiguration(Connection connection) throws SQLException {
        Map<String, String> memoryConfig = new HashMap<>();

        String[] memoryParams = {
            "innodb_buffer_pool_size",
            "innodb_log_buffer_size",
            "key_buffer_size",
            "sort_buffer_size",
            "read_buffer_size",
            "read_rnd_buffer_size",
            "join_buffer_size",
            "tmp_table_size",
            "max_heap_table_size",
            "query_cache_size",
            "thread_cache_size",
            "table_open_cache",
            "max_connections"
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

        // Check InnoDB buffer pool size
        String bufferPoolSize = config.get("innodb_buffer_pool_size");
        if (bufferPoolSize != null) {
            long size = Long.parseLong(bufferPoolSize);
            long sizeGB = size / (1024 * 1024 * 1024);

            // Get total system memory estimate from buffer pool stats
            if (sizeGB < 1) {
                Map<String, Object> rec = new HashMap<>();
                rec.put("parameter", "innodb_buffer_pool_size");
                rec.put("currentValue", formatBytes(size));
                rec.put("recommendedValue", "70-80% of available RAM");
                rec.put("priority", "HIGH");
                rec.put("reasoning", "InnoDB buffer pool is small. Increase for better caching.");
                recommendations.add(rec);
            }
        }

        // Check max_connections vs thread_cache_size
        String maxConn = config.get("max_connections");
        String threadCache = config.get("thread_cache_size");
        if (maxConn != null && threadCache != null) {
            int max = Integer.parseInt(maxConn);
            int cache = Integer.parseInt(threadCache);

            if (cache < max / 4) {
                Map<String, Object> rec = new HashMap<>();
                rec.put("parameter", "thread_cache_size");
                rec.put("currentValue", String.valueOf(cache));
                rec.put("recommendedValue", String.valueOf(max / 4));
                rec.put("priority", "MEDIUM");
                rec.put("reasoning", "Thread cache is small relative to max_connections. May cause thread creation overhead.");
                recommendations.add(rec);
            }
        }

        // Check tmp_table_size
        String tmpTableSize = config.get("tmp_table_size");
        String maxHeapSize = config.get("max_heap_table_size");
        if (tmpTableSize != null && maxHeapSize != null) {
            long tmp = Long.parseLong(tmpTableSize);
            long heap = Long.parseLong(maxHeapSize);

            if (tmp != heap) {
                Map<String, Object> rec = new HashMap<>();
                rec.put("parameter", "tmp_table_size / max_heap_table_size");
                rec.put("currentValue", formatBytes(tmp) + " / " + formatBytes(heap));
                rec.put("recommendedValue", "Both should be equal");
                rec.put("priority", "LOW");
                rec.put("reasoning", "tmp_table_size and max_heap_table_size should match for optimal temporary table handling.");
                recommendations.add(rec);
            }
        }

        // Check query cache (deprecated in MySQL 8.0)
        String version = getDatabaseVersion(connection);
        if (version != null && !version.startsWith("8.")) {
            String queryCache = config.get("query_cache_size");
            if (queryCache != null && Long.parseLong(queryCache) > 0) {
                Map<String, Object> rec = new HashMap<>();
                rec.put("parameter", "query_cache_size");
                rec.put("currentValue", formatBytes(Long.parseLong(queryCache)));
                rec.put("recommendedValue", "0 (disabled)");
                rec.put("priority", "MEDIUM");
                rec.put("reasoning", "Query cache can cause contention and is deprecated. Consider disabling.");
                recommendations.add(rec);
            }
        }

        return recommendations;
    }

    @Override
    public String getDatabaseVersion(Connection connection) throws SQLException {
        String query = "SELECT VERSION()";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            if (rs.next()) {
                return rs.getString(1);
            }
        }
        return null;
    }

    private String formatBytes(long bytes) {
        if (bytes >= 1024L * 1024 * 1024) {
            return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
        } else if (bytes >= 1024L * 1024) {
            return String.format("%.2f MB", bytes / (1024.0 * 1024));
        } else if (bytes >= 1024) {
            return String.format("%.2f KB", bytes / 1024.0);
        }
        return bytes + " bytes";
    }
}
