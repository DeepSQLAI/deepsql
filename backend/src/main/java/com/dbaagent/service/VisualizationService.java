package com.dbaagent.service;

import com.dbaagent.model.*;
import com.dbaagent.repository.InferredTableRelationshipRepository;
import com.dbaagent.repository.SchemaDocumentationRepository;
import com.dbaagent.repository.SchemaClassificationRepository;
import com.dbaagent.repository.TableClassificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class VisualizationService {

    private final InferredTableRelationshipRepository inferredTableRelationshipRepository;
    private final SchemaDocumentationRepository schemaDocumentationRepository;
    private final TableClassificationRepository tableClassificationRepository;
    private final SchemaClassificationRepository schemaClassificationRepository;
    private final ConnectionService connectionService;
    private final CredentialService credentialService;

    /**
     * Generate ER diagram from schema only (FK relationships). Use for backward compatibility.
     */
    public ErDiagramData generateErDiagram(SchemaMetadata schema) {
        return generateErDiagram(schema, null);
    }

    /**
     * Generate ER diagram from schema and persisted inferred relationships.
     * When connectionId is provided, relationships inferred from slow query JOINs
     * (stored in inferred_table_relationship) are merged in so the diagram shows
     * both FK-based and inferred links. Loading is fast because inferred data is
     * already persisted and indexed by connection_id.
     */
    public ErDiagramData generateErDiagram(SchemaMetadata schema, String connectionId) {
        ErDiagramData erDiagram = new ErDiagramData();
        
        // Generate entities from tables
        List<ErEntity> entities = new ArrayList<>();
        Map<String, Integer> tablePositions = new HashMap<>();
        int gridSize = (int) Math.ceil(Math.sqrt(schema.getTables().size()));
        int x = 0, y = 0;
        int spacing = 300;
        
        Map<String, String> tableDescriptions = new HashMap<>();
        if (connectionId != null) {
            List<SchemaDocumentation> tableDocs = schemaDocumentationRepository
                .findByConnectionIdAndObjectType(connectionId, SchemaDocumentation.DocumentationType.TABLE);
            for (SchemaDocumentation doc : tableDocs) {
                if (doc.getObjectName() != null && doc.getDescription() != null) {
                    tableDescriptions.put(doc.getObjectName().toLowerCase(), doc.getDescription());
                }
            }
        }

        // Load row counts from multiple sources (in priority order):
        // 1. table_classification (latest schema classification)
        // 2. Direct database query (same as SQL Runner uses)
        Map<String, Long> rowCountsByTable = new HashMap<>();
        if (connectionId != null) {
            try {
                // First try: table_classification
                Optional<SchemaClassification> latestSchema = schemaClassificationRepository
                    .findLatestByConnectionId(connectionId);
                if (latestSchema.isPresent()) {
                    List<TableClassification> classifications = tableClassificationRepository
                        .findBySchemaClassificationId(latestSchema.get().getId());
                    for (TableClassification tc : classifications) {
                        if (tc.getTableName() != null && tc.getRowCount() != null) {
                            rowCountsByTable.put(tc.getTableName().toLowerCase(), tc.getRowCount());
                        }
                    }
                    log.debug("Loaded {} row counts from table_classification for connection {}", 
                        rowCountsByTable.size(), connectionId);
                }
            } catch (Exception e) {
                log.warn("Failed to load row counts from table_classification: {}", e.getMessage());
            }
            
            // Second try: Direct database query (same approach as QueryExecutorService)
            // This ensures we get row counts even if table_classification is empty
            try {
                ConnectionRequest request = credentialService.getDecryptedConnection(connectionId);
                String dbType = request.getDbType();
                String database = request.getDatabase();
                
                try (Connection conn = connectionService.getConnection(connectionId, request)) {
                    if ("mysql".equalsIgnoreCase(dbType) || "mariadb".equalsIgnoreCase(dbType)) {
                        String query = "SELECT TABLE_NAME, TABLE_ROWS FROM INFORMATION_SCHEMA.TABLES " +
                            "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE'";
                        try (PreparedStatement stmt = conn.prepareStatement(query)) {
                            stmt.setString(1, database);
                            try (ResultSet rs = stmt.executeQuery()) {
                                while (rs.next()) {
                                    String tableName = rs.getString("TABLE_NAME");
                                    // Use getObject to preserve NULL vs 0 distinction
                                    Object rowCountObj = rs.getObject("TABLE_ROWS");
                                    Long rowCount = null;
                                    if (rowCountObj != null) {
                                        if (rowCountObj instanceof Number) {
                                            rowCount = ((Number) rowCountObj).longValue();
                                        }
                                    }
                                    // Only add if not already in map (table_classification takes priority) and rowCount is not null
                                    if (tableName != null && rowCount != null && 
                                        !rowCountsByTable.containsKey(tableName.toLowerCase())) {
                                        rowCountsByTable.put(tableName.toLowerCase(), rowCount);
                                        log.debug("Loaded row count for table {}: {}", tableName, rowCount);
                                    }
                                }
                            }
                        }
                    } else if ("postgresql".equalsIgnoreCase(dbType) || "postgres".equalsIgnoreCase(dbType)) {
                        String query = "SELECT t.tablename as table_name, " +
                            "(SELECT reltuples::bigint FROM pg_class WHERE relname = t.tablename) as row_count " +
                            "FROM pg_tables t WHERE t.schemaname = 'public'";
                        try (Statement stmt = conn.createStatement();
                             ResultSet rs = stmt.executeQuery(query)) {
                            while (rs.next()) {
                                String tableName = rs.getString("table_name");
                                long relTuples = rs.getLong("row_count");
                                // Only add if not already in map and reltuples is valid (>= 0)
                                if (tableName != null && relTuples >= 0 && 
                                    !rowCountsByTable.containsKey(tableName.toLowerCase())) {
                                    rowCountsByTable.put(tableName.toLowerCase(), relTuples);
                                }
                            }
                        }
                    }
                    log.debug("Loaded {} total row counts (including direct DB query) for connection {}", 
                        rowCountsByTable.size(), connectionId);
                }
            } catch (Exception e) {
                log.warn("Failed to load row counts from direct database query: {}", e.getMessage());
            }
        }

        for (TableMetadata table : schema.getTables()) {
            ErEntity entity = new ErEntity();
            entity.setName(table.getName());
            entity.setType(table.getType());
            // Use row count from schema scan if available and valid (> 0), otherwise fallback to:
            // 1. table_classification (latest schema classification)
            // 2. Direct database query (same as SQL Runner uses)
            Long rowCount = table.getRowCount();
            // If schema scan returned null, 0, or negative, try fallback sources
            if ((rowCount == null || rowCount <= 0) && table.getName() != null) {
                String tableNameLower = table.getName().toLowerCase();
                Long fallbackRowCount = rowCountsByTable.get(tableNameLower);
                if (fallbackRowCount != null && fallbackRowCount > 0) {
                    rowCount = fallbackRowCount;
                    log.debug("Using fallback row count for table {}: {} (schema scan had: {})", 
                        table.getName(), fallbackRowCount, table.getRowCount());
                } else if (rowCount == null) {
                    log.debug("No row count found for table {} (checked: {})", table.getName(), tableNameLower);
                }
            }
            entity.setRowCount(rowCount);
            entity.setSizeBytes(table.getSizeBytes());
            if (table.getName() != null) {
                entity.setDescription(tableDescriptions.get(table.getName().toLowerCase()));
            }

            // Convert columns to entity columns
            List<ErEntityColumn> entityColumns = table.getColumns().stream()
                .map(col -> {
                    ErEntityColumn ec = new ErEntityColumn();
                    ec.setName(col.getName());
                    ec.setType(col.getDataType() + 
                        (col.getMaxLength() != null ? "(" + col.getMaxLength() + ")" : ""));
                    ec.setPrimaryKey(col.getPrimaryKey());
                    ec.setNullable(col.getNullable());
                    return ec;
                })
                .collect(Collectors.toList());
            entity.setColumns(entityColumns);
            
            // Calculate position in grid layout
            Position position = new Position();
            position.setX((double) (x * spacing + 100));
            position.setY((double) (y * spacing + 100));
            entity.setPosition(position);
            
            entities.add(entity);
            tablePositions.put(table.getName(), entities.size() - 1);
            
            x++;
            if (x >= gridSize) {
                x = 0;
                y++;
            }
        }
        
        erDiagram.setEntities(entities);
        
        // Generate relationships from foreign keys
        Set<String> seenRelKeys = new HashSet<>();
        List<Relationship> relationships = schema.getRelationships().stream()
            .map(rel -> {
                Relationship relationship = new Relationship();
                relationship.setFrom(rel.getFromTable() != null ? rel.getFromTable() : "");
                relationship.setTo(rel.getToTable() != null ? rel.getToTable() : "");
                String fromCol = rel.getFromColumn() != null ? rel.getFromColumn() : "";
                String toCol = rel.getToColumn() != null ? rel.getToColumn() : "";
                relationship.setForeignKey(fromCol);
                relationship.setFromKey(fromCol);
                relationship.setToKey(toCol);
                String relType = determineRelationshipType(schema, rel);
                relationship.setType(relType != null ? relType : "");
                seenRelKeys.add(relKey(rel.getFromTable(), rel.getToTable(), fromCol));
                return relationship;
            })
            .collect(Collectors.toList());
        
        // Merge inferred relationships from slow query JOINs (persisted in inferred_table_relationship)
        if (connectionId != null) {
            List<InferredTableRelationship> inferred = inferredTableRelationshipRepository
                .findByConnectionIdOrderByConfidenceScoreDesc(connectionId);
            // Case-insensitive lookup: lowercase name -> actual schema table name (so links match entity names)
            Map<String, String> tableNameByLower = new HashMap<>();
            for (TableMetadata t : schema.getTables()) {
                String name = t.getName();
                if (name != null) {
                    tableNameByLower.put(name.toLowerCase(), name);
                }
            }
            for (InferredTableRelationship inf : inferred) {
                if ("REJECTED".equals(inf.getStatus())) {
                    continue;
                }
                String sourceNorm = normalizeTableName(inf.getSourceTable());
                String targetNorm = normalizeTableName(inf.getTargetTable());
                String sourceActual = sourceNorm != null ? tableNameByLower.get(sourceNorm.toLowerCase()) : null;
                String targetActual = targetNorm != null ? tableNameByLower.get(targetNorm.toLowerCase()) : null;
                if (sourceActual == null || targetActual == null) {
                    continue;
                }
                String key = relKey(sourceActual, targetActual, inf.getSourceColumn());
                if (!seenRelKeys.add(key)) {
                    continue;
                }
                Relationship r = new Relationship();
                r.setFrom(sourceActual != null ? sourceActual : "");
                r.setTo(targetActual != null ? targetActual : "");
                String srcCol = inf.getSourceColumn() != null ? inf.getSourceColumn() : "";
                String tgtCol = inf.getTargetColumn() != null ? inf.getTargetColumn() : "";
                r.setForeignKey(srcCol);
                r.setFromKey(srcCol);
                r.setToKey(tgtCol);
                r.setType(inf.getCardinality() != null ? inf.getCardinality() : "inferred");
                relationships.add(r);
            }
        }
        
        erDiagram.setRelationships(relationships);
        
        return erDiagram;
    }

    private static String relKey(String from, String to, String fk) {
        return from + "|" + to + "|" + (fk != null ? fk : "");
    }

    /** Strip schema prefix (e.g. public.payment_refunds -> payment_refunds) for matching. */
    private static String normalizeTableName(String tableName) {
        if (tableName == null || tableName.isEmpty()) {
            return tableName;
        }
        int dot = tableName.indexOf('.');
        return dot >= 0 ? tableName.substring(dot + 1) : tableName;
    }

    public DependencyGraphData generateDependencyGraph(SchemaMetadata schema) {
        return generateDependencyGraph(schema, null);
    }

    public DependencyGraphData generateDependencyGraph(SchemaMetadata schema, String connectionId) {
        DependencyGraphData graph = new DependencyGraphData();
        
        // Create nodes for all tables
        Map<String, GraphNode> nodeMap = new HashMap<>();
        
        for (TableMetadata table : schema.getTables()) {
            GraphNode node = new GraphNode();
            node.setId(table.getName());
            node.setType(table.getType());
            node.setDependencies(new ArrayList<>());
            nodeMap.put(table.getName(), node);
        }
        
        // Build dependency relationships from foreign keys
        List<GraphEdge> edges = new ArrayList<>();
        Set<String> seenEdges = new HashSet<>();
        
        for (RelationshipMetadata rel : schema.getRelationships()) {
            GraphNode fromNode = nodeMap.get(rel.getFromTable());
            GraphNode toNode = nodeMap.get(rel.getToTable());
            
            if (fromNode != null && toNode != null) {
                String edgeKey = rel.getFromTable() + "|" + rel.getToTable();
                if (seenEdges.add(edgeKey)) {
                    if (!fromNode.getDependencies().contains(rel.getToTable())) {
                        fromNode.getDependencies().add(rel.getToTable());
                    }
                    GraphEdge edge = new GraphEdge();
                    edge.setFrom(rel.getFromTable());
                    edge.setTo(rel.getToTable());
                    edge.setRelation("foreign_key");
                    edges.add(edge);
                }
            }
        }
        
        // Add inferred relationships from slow query JOINs (case-insensitive, schema-prefix normalized)
        if (connectionId != null) {
            List<InferredTableRelationship> inferred = inferredTableRelationshipRepository
                .findByConnectionIdOrderByConfidenceScoreDesc(connectionId);
            Map<String, String> tableNameByLower = new HashMap<>();
            for (TableMetadata t : schema.getTables()) {
                String name = t.getName();
                if (name != null) {
                    tableNameByLower.put(name.toLowerCase(), name);
                }
            }
            for (InferredTableRelationship inf : inferred) {
                if ("REJECTED".equals(inf.getStatus())) continue;
                String sourceNorm = normalizeTableName(inf.getSourceTable());
                String targetNorm = normalizeTableName(inf.getTargetTable());
                String sourceActual = sourceNorm != null ? tableNameByLower.get(sourceNorm.toLowerCase()) : null;
                String targetActual = targetNorm != null ? tableNameByLower.get(targetNorm.toLowerCase()) : null;
                if (sourceActual == null || targetActual == null) continue;
                String edgeKey = sourceActual + "|" + targetActual;
                if (!seenEdges.add(edgeKey)) continue;
                GraphNode fromNode = nodeMap.get(sourceActual);
                GraphNode toNode = nodeMap.get(targetActual);
                if (fromNode != null && toNode != null) {
                    if (!fromNode.getDependencies().contains(targetActual)) {
                        fromNode.getDependencies().add(targetActual);
                    }
                    GraphEdge edge = new GraphEdge();
                    edge.setFrom(sourceActual);
                    edge.setTo(targetActual);
                    edge.setRelation("inferred");
                    edges.add(edge);
                }
            }
        }
        
        // Also check for view dependencies (views depend on tables they reference)
        for (TableMetadata table : schema.getTables()) {
            if ("view".equals(table.getType())) {
                GraphNode viewNode = nodeMap.get(table.getName());
                if (viewNode != null) {
                    // Views typically depend on multiple tables
                    // This is a simplified version - in production, parse view definition
                    for (TableMetadata otherTable : schema.getTables()) {
                        if ("table".equals(otherTable.getType()) && 
                            !otherTable.getName().equals(table.getName())) {
                            if (!viewNode.getDependencies().contains(otherTable.getName())) {
                                viewNode.getDependencies().add(otherTable.getName());
                                
                                GraphEdge edge = new GraphEdge();
                                edge.setFrom(table.getName());
                                edge.setTo(otherTable.getName());
                                edge.setRelation("view_dependency");
                                edges.add(edge);
                            }
                        }
                    }
                }
            }
        }
        
        graph.setNodes(new ArrayList<>(nodeMap.values()));
        graph.setEdges(edges);
        
        return graph;
    }

    private String determineRelationshipType(SchemaMetadata schema, RelationshipMetadata rel) {
        // Check if the reverse relationship exists (many-to-many)
        boolean reverseExists = schema.getRelationships().stream()
            .anyMatch(r -> r.getFromTable().equals(rel.getToTable()) && 
                          r.getToTable().equals(rel.getFromTable()));
        
        if (reverseExists) {
            return "many-to-many";
        }
        
        // Check if the "to" table has a unique constraint on the referenced column
        // This would indicate one-to-one, but for simplicity, default to one-to-many
        // In production, query the database for unique constraints
        
        return "one-to-many";
    }
}


