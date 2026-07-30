package com.dbaagent.provider;

import com.dbaagent.provider.api.DatabaseDialect;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Registry for database dialects/providers.
 * Uses Spring auto-discovery to register all DatabaseDialect implementations.
 * Supports alias resolution (e.g., "postgresql" -> "postgres").
 */
@Component
@Slf4j
public class DatabaseProviderRegistry {

    private final Map<String, DatabaseDialect> dialects = new ConcurrentHashMap<>();

    /**
     * Constructor that auto-registers all discovered DatabaseDialect beans.
     * @param discoveredDialects List of dialects discovered by Spring
     */
    public DatabaseProviderRegistry(List<DatabaseDialect> discoveredDialects) {
        for (DatabaseDialect dialect : discoveredDialects) {
            registerDialect(dialect);
        }
        log.info("Registered {} database dialects: {}",
            getSupportedTypes().size(),
            getSupportedTypes());
    }

    /**
     * Register a database dialect.
     * Registers both the canonical name and all aliases.
     * @param dialect The dialect to register
     */
    public void registerDialect(DatabaseDialect dialect) {
        String canonical = normalize(dialect.getCanonicalName());
        registerAlias(canonical, dialect, true);

        // Register aliases
        if (dialect.getAliases() != null) {
            for (String alias : dialect.getAliases()) {
                String normalizedAlias = normalize(alias);
                if (!normalizedAlias.equals(canonical)) {
                    registerAlias(normalizedAlias, dialect, false);
                }
            }
        }
    }

    /**
     * Register a single alias for a dialect with collision detection.
     */
    private void registerAlias(String alias, DatabaseDialect dialect, boolean isCanonical) {
        DatabaseDialect existing = dialects.get(alias);
        if (existing != null && !existing.getCanonicalName().equals(dialect.getCanonicalName())) {
            log.warn("Alias collision: '{}' already registered for '{}', now being overwritten by '{}'",
                alias, existing.getCanonicalName(), dialect.getCanonicalName());
        }
        dialects.put(alias, dialect);
        if (isCanonical) {
            log.debug("Registered dialect: {} ({})", dialect.getDisplayName(), alias);
        } else {
            log.debug("Registered alias: {} -> {}", alias, dialect.getCanonicalName());
        }
    }

    /**
     * Get a dialect by database type.
     * Supports aliases and case-insensitive matching.
     * @param dbType The database type (e.g., "mysql", "PostgreSQL", "postgres")
     * @return The database dialect
     * @throws UnsupportedDatabaseException If the database type is not supported
     */
    public DatabaseDialect getDialect(String dbType) {
        String normalized = normalize(dbType);
        DatabaseDialect dialect = dialects.get(normalized);

        if (dialect == null) {
            throw new UnsupportedDatabaseException(dbType, getSupportedTypes());
        }

        return dialect;
    }

    /**
     * Check if a database type is supported.
     * @param dbType The database type to check
     * @return true if supported
     */
    public boolean isSupported(String dbType) {
        return dialects.containsKey(normalize(dbType));
    }

    /**
     * Get the canonical name for a database type (resolves aliases).
     * Use this to normalize dbType before any branching logic.
     * Examples: "postgresql" -> "postgres", "mariadb" -> "mysql", "aurora-mysql" -> "mysql"
     * @param dbType The database type (can be alias)
     * @return The canonical name, or the original if not found
     */
    public String getCanonicalName(String dbType) {
        String normalized = normalize(dbType);
        DatabaseDialect dialect = dialects.get(normalized);
        if (dialect != null) {
            return dialect.getCanonicalName();
        }
        // Return original normalized value if not found (let caller handle unknown types)
        return normalized;
    }

    /**
     * Get all supported database types (canonical names only).
     * @return Set of supported database type names
     */
    public Set<String> getSupportedTypes() {
        return dialects.values().stream()
            .map(DatabaseDialect::getCanonicalName)
            .collect(Collectors.toSet());
    }

    /**
     * Get all registered dialects.
     * @return List of all registered dialects
     */
    public List<DatabaseDialect> getAllDialects() {
        return dialects.values().stream()
            .distinct()
            .collect(Collectors.toList());
    }

    /**
     * Normalize a database type string for consistent lookup.
     * @param dbType The database type string
     * @return Normalized string (lowercase, trimmed)
     */
    private String normalize(String dbType) {
        if (dbType == null) {
            return "";
        }
        return dbType.toLowerCase().trim();
    }
}
