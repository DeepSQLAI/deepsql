package com.dbaagent.service;

import com.dbaagent.model.*;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.provider.api.IntrospectionProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
@Slf4j
public class SchemaScannerService {
    private final ConnectionService connectionService;
    private final CredentialService credentialService;
    private final CacheManager cacheManager;
    private final CacheMetricsService cacheMetricsService;
    private final DatabaseProviderRegistry providerRegistry;

    public SchemaMetadata scanSchema(String connectionId) throws SQLException {
        Cache cache = cacheManager.getCache("schemaMetadata");
        if (cache != null) {
            SchemaMetadata cached = cache.get(connectionId, SchemaMetadata.class);
            if (cached != null) {
                cacheMetricsService.recordGet("schemaMetadata", true);
                return cached;
            }
            cacheMetricsService.recordGet("schemaMetadata", false);
        }

        ConnectionRequest request = credentialService.getDecryptedConnection(connectionId);
        try (Connection connection = connectionService.getConnection(connectionId, request)) {
            String dbType = providerRegistry.getCanonicalName(request.getDbType());
            IntrospectionProvider provider = providerRegistry.getDialect(dbType).introspection();
            if (provider == null) {
                throw new IllegalArgumentException("IntrospectionProvider not available for: " + dbType);
            }

            SchemaMetadata schema = provider.scanSchema(connection, request.getDatabase());
            if (schema == null) {
                schema = new SchemaMetadata();
            }
            schema.setDatabaseName(request.getDatabase());
            schema.setDbType(request.getDbType());

            schema.setTotalTables((long) schema.getTables().stream()
                .filter(t -> "table".equals(t.getType())).count());
            schema.setTotalViews((long) schema.getTables().stream()
                .filter(t -> "view".equals(t.getType())).count());
            schema.setTotalSizeBytes(schema.getTables().stream()
                .mapToLong(t -> t.getSizeBytes() != null ? t.getSizeBytes() : 0)
                .sum());

            if (cache != null) {
                cache.put(connectionId, schema);
                cacheMetricsService.recordPut("schemaMetadata");
            }
            return schema;
        }
    }

    public void evictSchemaCache(String connectionId) {
        Cache cache = cacheManager.getCache("schemaMetadata");
        if (cache == null) {
            return;
        }
        cache.evict(connectionId);
        cacheMetricsService.recordEvict("schemaMetadata", 1);
    }

    /**
     * Check if schema is already cached for a connection.
     */
    public boolean isSchemaCached(String connectionId) {
        Cache cache = cacheManager.getCache("schemaMetadata");
        if (cache == null) {
            return false;
        }
        return cache.get(connectionId, SchemaMetadata.class) != null;
    }

    /**
     * Pre-warm the schema cache asynchronously.
     * Call this when a connection is selected to avoid cold start on first chat.
     */
    @Async
    public CompletableFuture<Boolean> warmupSchemaCache(String connectionId) {
        if (isSchemaCached(connectionId)) {
            log.debug("Schema already cached for connection: {}", connectionId);
            return CompletableFuture.completedFuture(true);
        }

        log.info("Pre-warming schema cache for connection: {}", connectionId);
        long startTime = System.currentTimeMillis();

        try {
            scanSchema(connectionId);
            long duration = System.currentTimeMillis() - startTime;
            log.info("Schema cache warmed for connection {} in {}ms", connectionId, duration);
            return CompletableFuture.completedFuture(true);
        } catch (Exception e) {
            log.warn("Failed to pre-warm schema cache for connection {}: {}", connectionId, e.getMessage());
            return CompletableFuture.completedFuture(false);
        }
    }

}
