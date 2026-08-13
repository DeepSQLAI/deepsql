package com.dbaagent.service;

import com.dbaagent.model.*;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.provider.api.IntrospectionProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.*;

/**
 * Advanced schema introspection service for detailed database structure analysis.
 * Provides information about tables, columns, indexes, constraints, and relationships.
 * Delegates database-specific operations to the appropriate IntrospectionProvider.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SchemaIntrospectionService {

    private final ConnectionService connectionService;
    private final CredentialService credentialService;
    private final DatabaseProviderRegistry providerRegistry;

    /**
     * Get detailed information about a specific table.
     */
    public TableDetails getTableDetails(String connectionId, String tableName) {
        try {
            ConnectionRequest connRequest = credentialService.getDecryptedConnection(connectionId);
            IntrospectionProvider provider = providerRegistry.getDialect(connRequest.getDbType()).introspection();
            String database = connRequest.getDatabase();

            try (Connection connection = connectionService.getConnection(connectionId, connRequest)) {
                List<ColumnDetail> columns = provider.getColumnDetails(connection, database, tableName);
                List<IndexDetail> indexes = provider.getIndexDetails(connection, database, tableName);
                List<ConstraintDetail> constraints = provider.getConstraintDetails(connection, database, tableName);
                List<ForeignKeyDetail> foreignKeys = provider.getForeignKeyDetails(connection, database, tableName);
                Long rowCount = provider.getTableRowCount(connection, database, tableName);
                String tableSize = provider.getTableSize(connection, database, tableName);

                String schemaName = provider.getDefaultSchema();
                String bareName = tableName;
                if (tableName != null && tableName.contains(".")) {
                    int dot = tableName.indexOf('.');
                    schemaName = tableName.substring(0, dot);
                    bareName = tableName.substring(dot + 1);
                }

                return TableDetails.builder()
                        .tableName(bareName != null ? bareName : tableName)
                        .tableSchema(schemaName)
                        .columns(columns)
                        .indexes(indexes)
                        .constraints(constraints)
                        .foreignKeys(foreignKeys)
                        .rowCount(rowCount)
                        .tableSize(tableSize)
                        .build();
            }
        } catch (Exception e) {
            log.error("Failed to get table details for {}: {}", tableName, e.getMessage(), e);
            return TableDetails.builder()
                    .tableName(tableName)
                    .columns(new ArrayList<>())
                    .indexes(new ArrayList<>())
                    .constraints(new ArrayList<>())
                    .foreignKeys(new ArrayList<>())
                    .build();
        }
    }

    /**
     * Get all table relationships (foreign key mappings) for the connection.
     */
    public Map<String, List<String>> getTableRelationships(String connectionId) {
        try {
            ConnectionRequest connRequest = credentialService.getDecryptedConnection(connectionId);
            IntrospectionProvider provider = providerRegistry.getDialect(connRequest.getDbType()).introspection();

            try (Connection connection = connectionService.getConnection(connectionId, connRequest)) {
                return provider.getTableRelationships(connection, connRequest.getDatabase());
            }
        } catch (Exception e) {
            log.error("Error fetching table relationships: {}", e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * Get all tables in the database with basic metadata.
     */
    public List<Map<String, Object>> getAllTables(String connectionId) {
        try {
            ConnectionRequest connRequest = credentialService.getDecryptedConnection(connectionId);
            IntrospectionProvider provider = providerRegistry.getDialect(connRequest.getDbType()).introspection();

            try (Connection connection = connectionService.getConnection(connectionId, connRequest)) {
                return provider.getAllTablesWithMetadata(connection, connRequest.getDatabase());
            }
        } catch (Exception e) {
            log.error("Error fetching all tables: {}", e.getMessage());
            return new ArrayList<>();
        }
    }
}
