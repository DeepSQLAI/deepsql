package com.dbaagent.service.brain.classification;

import com.dbaagent.service.ConnectionService;
import com.dbaagent.service.CredentialService;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.InferredTableRelationship;
import com.dbaagent.model.TableRelationshipClassification;
import com.dbaagent.repository.InferredTableRelationshipRepository;
import com.dbaagent.repository.TableRelationshipClassificationRepository;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;

/**
 * Service for classifying and analyzing table relationships.
 *
 * Combines multiple sources:
 * - Foreign key constraints (STRONG relationships)
 * - Column naming patterns (INFERRED relationships)
 * - Query join patterns from slow query logs (WEAK relationships)
 *
 * Analyzes:
 * - Relationship types (ONE_TO_ONE, ONE_TO_MANY, MANY_TO_MANY)
 * - Data integrity (orphan records)
 * - Index coverage
 * - Join frequency
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class RelationshipClassificationService {

    private final ConnectionService connectionService;
    private final CredentialService credentialService;
    private final TableRelationshipClassificationRepository relationshipRepository;
    private final InferredTableRelationshipRepository inferredRelationshipRepository;

    /**
     * Classify all relationships for a connection.
     */
    @Transactional
    public RelationshipClassificationResult classifyRelationships(String connectionId, String schemaClassificationId) {
        log.info("Classifying relationships for connection: {}", connectionId);

        ConnectionRequest connRequest = credentialService.getDecryptedConnection(connectionId);
        if (connRequest == null) {
            log.warn("Connection not found: {}", connectionId);
            return RelationshipClassificationResult.builder()
                .connectionId(connectionId)
                .relationships(List.of())
                .summary(new HashMap<>())
                .build();
        }

        String dbType = connRequest.getDbType();
        List<TableRelationshipClassification> relationships = new ArrayList<>();

        try (Connection conn = connectionService.getConnection(connectionId, connRequest)) {
            // 1. Get STRONG relationships from FK constraints
            List<TableRelationshipClassification> strongRelationships =
                getStrongRelationships(conn, dbType, connectionId, schemaClassificationId);
            relationships.addAll(strongRelationships);
            log.info("Found {} strong relationships (FK constraints)", strongRelationships.size());

            // 2. Get INFERRED relationships from naming patterns
            List<TableRelationshipClassification> inferredRelationships =
                getInferredRelationships(connectionId, schemaClassificationId, strongRelationships);
            relationships.addAll(inferredRelationships);
            log.info("Found {} inferred relationships (naming patterns)", inferredRelationships.size());

            // 3. Get WEAK relationships from query patterns
            List<TableRelationshipClassification> weakRelationships =
                getWeakRelationships(connectionId, schemaClassificationId, strongRelationships, inferredRelationships);
            relationships.addAll(weakRelationships);
            log.info("Found {} weak relationships (join patterns)", weakRelationships.size());

            // 4. Check data integrity for all relationships
            checkDataIntegrity(conn, dbType, relationships);

            // 5. Check index coverage
            checkIndexCoverage(conn, dbType, relationships);

            // 6. Save all relationships
            relationshipRepository.saveAll(relationships);

        } catch (Exception e) {
            log.error("Error classifying relationships for connection {}: {}", connectionId, e.getMessage(), e);
        }

        // Generate summary
        Map<String, Object> summary = generateSummary(relationships);

        return RelationshipClassificationResult.builder()
            .connectionId(connectionId)
            .schemaClassificationId(schemaClassificationId)
            .relationships(relationships)
            .summary(summary)
            .build();
    }

    /**
     * Get STRONG relationships from FK constraints.
     */
    private List<TableRelationshipClassification> getStrongRelationships(
            Connection conn, String dbType, String connectionId, String schemaClassificationId) {

        List<TableRelationshipClassification> relationships = new ArrayList<>();

        String sql;
        if ("postgresql".equalsIgnoreCase(dbType)) {
            sql = """
                SELECT
                    tc.table_name as source_table,
                    kcu.column_name as source_column,
                    ccu.table_name as target_table,
                    ccu.column_name as target_column,
                    rc.delete_rule,
                    rc.update_rule
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage kcu
                    ON tc.constraint_name = kcu.constraint_name
                    AND tc.table_schema = kcu.table_schema
                JOIN information_schema.constraint_column_usage ccu
                    ON ccu.constraint_name = tc.constraint_name
                    AND ccu.table_schema = tc.table_schema
                JOIN information_schema.referential_constraints rc
                    ON rc.constraint_name = tc.constraint_name
                WHERE tc.constraint_type = 'FOREIGN KEY'
                    AND tc.table_schema NOT IN ('pg_catalog', 'information_schema')
                """;
        } else {
            sql = """
                SELECT
                    kcu.TABLE_NAME as source_table,
                    kcu.COLUMN_NAME as source_column,
                    kcu.REFERENCED_TABLE_NAME as target_table,
                    kcu.REFERENCED_COLUMN_NAME as target_column,
                    rc.DELETE_RULE as delete_rule,
                    rc.UPDATE_RULE as update_rule
                FROM information_schema.KEY_COLUMN_USAGE kcu
                JOIN information_schema.REFERENTIAL_CONSTRAINTS rc
                    ON kcu.CONSTRAINT_NAME = rc.CONSTRAINT_NAME
                    AND kcu.TABLE_SCHEMA = rc.CONSTRAINT_SCHEMA
                WHERE kcu.REFERENCED_TABLE_NAME IS NOT NULL
                    AND kcu.TABLE_SCHEMA = DATABASE()
                """;
        }

        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String deleteRule = rs.getString("delete_rule");
                boolean isCascading = "CASCADE".equalsIgnoreCase(deleteRule);

                relationships.add(TableRelationshipClassification.builder()
                    .connectionId(connectionId)
                    .schemaClassificationId(schemaClassificationId)
                    .sourceTable(rs.getString("source_table"))
                    .sourceColumn(rs.getString("source_column"))
                    .targetTable(rs.getString("target_table"))
                    .targetColumn(rs.getString("target_column"))
                    .relationshipType("ONE_TO_MANY") // Default, will be refined
                    .strength("STRONG")
                    .isCascading(isCascading)
                    .confidenceScore(BigDecimal.valueOf(100))
                    .validationStatus("VALIDATED")
                    .build());
            }
        } catch (Exception e) {
            log.error("Error getting FK relationships: {}", e.getMessage(), e);
        }

        return relationships;
    }

    /**
     * Get INFERRED relationships from InferredTableRelationship repository.
     */
    private List<TableRelationshipClassification> getInferredRelationships(
            String connectionId, String schemaClassificationId,
            List<TableRelationshipClassification> strongRelationships) {

        List<TableRelationshipClassification> relationships = new ArrayList<>();

        // Get inferred relationships from the inference service
        List<InferredTableRelationship> inferred =
            inferredRelationshipRepository.findByConnectionIdOrderByConfidenceScoreDesc(connectionId);

        // Track existing relationships to avoid duplicates
        Set<String> existingKeys = new HashSet<>();
        for (TableRelationshipClassification strong : strongRelationships) {
            existingKeys.add(makeRelationshipKey(strong.getSourceTable(), strong.getTargetTable(), strong.getSourceColumn()));
        }

        for (InferredTableRelationship inf : inferred) {
            String key = makeRelationshipKey(inf.getSourceTable(), inf.getTargetTable(), inf.getSourceColumn());
            if (existingKeys.contains(key)) {
                continue; // Skip if already covered by FK constraint
            }

            // Only include if confidence is reasonable
            if (inf.getConfidenceScore() != null && inf.getConfidenceScore().doubleValue() >= 10) {
                relationships.add(TableRelationshipClassification.builder()
                    .connectionId(connectionId)
                    .schemaClassificationId(schemaClassificationId)
                    .sourceTable(inf.getSourceTable())
                    .sourceColumn(inf.getSourceColumn())
                    .targetTable(inf.getTargetTable())
                    .targetColumn(inf.getTargetColumn())
                    .relationshipType(inf.getCardinality() != null ? mapCardinality(inf.getCardinality()) : "ONE_TO_MANY")
                    .strength("INFERRED")
                    .joinFrequency(inf.getJoinCount())
                    .confidenceScore(inf.getConfidenceScore())
                    .validationStatus(inf.getStatus() != null ? inf.getStatus() : "PENDING")
                    .build());

                existingKeys.add(key);
            }
        }

        return relationships;
    }

    /**
     * Get WEAK relationships from query join patterns only (not covered by FK or inferred).
     */
    private List<TableRelationshipClassification> getWeakRelationships(
            String connectionId, String schemaClassificationId,
            List<TableRelationshipClassification> strongRelationships,
            List<TableRelationshipClassification> inferredRelationships) {

        // For weak relationships, we look at join patterns with lower confidence
        List<TableRelationshipClassification> relationships = new ArrayList<>();

        Set<String> existingKeys = new HashSet<>();
        for (TableRelationshipClassification rel : strongRelationships) {
            existingKeys.add(makeRelationshipKey(rel.getSourceTable(), rel.getTargetTable(), rel.getSourceColumn()));
        }
        for (TableRelationshipClassification rel : inferredRelationships) {
            existingKeys.add(makeRelationshipKey(rel.getSourceTable(), rel.getTargetTable(), rel.getSourceColumn()));
        }

        // Get low-confidence inferred relationships as "weak"
        List<InferredTableRelationship> inferred =
            inferredRelationshipRepository.findByConnectionIdOrderByConfidenceScoreDesc(connectionId);

        for (InferredTableRelationship inf : inferred) {
            String key = makeRelationshipKey(inf.getSourceTable(), inf.getTargetTable(), inf.getSourceColumn());
            if (existingKeys.contains(key)) {
                continue;
            }

            // Low confidence (5-10) relationships are "weak"
            if (inf.getConfidenceScore() != null &&
                inf.getConfidenceScore().doubleValue() >= 5 &&
                inf.getConfidenceScore().doubleValue() < 10 &&
                inf.getJoinCount() > 0) {

                relationships.add(TableRelationshipClassification.builder()
                    .connectionId(connectionId)
                    .schemaClassificationId(schemaClassificationId)
                    .sourceTable(inf.getSourceTable())
                    .sourceColumn(inf.getSourceColumn())
                    .targetTable(inf.getTargetTable())
                    .targetColumn(inf.getTargetColumn())
                    .relationshipType("ONE_TO_MANY")
                    .strength("WEAK")
                    .joinFrequency(inf.getJoinCount())
                    .confidenceScore(inf.getConfidenceScore())
                    .validationStatus("PENDING")
                    .build());

                existingKeys.add(key);
            }
        }

        return relationships;
    }

    private String makeRelationshipKey(String sourceTable, String targetTable, String sourceColumn) {
        return (sourceTable + ":" + targetTable + ":" + sourceColumn).toLowerCase();
    }

    private String mapCardinality(String cardinality) {
        if (cardinality == null) return "ONE_TO_MANY";
        return switch (cardinality.toUpperCase()) {
            case "1:1", "ONE_TO_ONE" -> "ONE_TO_ONE";
            case "N:N", "M:N", "MANY_TO_MANY" -> "MANY_TO_MANY";
            default -> "ONE_TO_MANY";
        };
    }

    /**
     * Check data integrity (orphan records) for relationships.
     */
    private void checkDataIntegrity(Connection conn, String dbType,
                                     List<TableRelationshipClassification> relationships) {
        for (TableRelationshipClassification rel : relationships) {
            if (rel.getSourceColumn() == null || rel.getTargetColumn() == null) {
                continue;
            }

            try {
                // Count orphan records (source values not in target)
                String orphanSql = String.format(
                    "SELECT COUNT(*) as orphan_count FROM \"%s\" s " +
                    "WHERE s.\"%s\" IS NOT NULL AND NOT EXISTS " +
                    "(SELECT 1 FROM \"%s\" t WHERE t.\"%s\" = s.\"%s\")",
                    rel.getSourceTable(), rel.getSourceColumn(),
                    rel.getTargetTable(), rel.getTargetColumn(), rel.getSourceColumn());

                try (PreparedStatement stmt = conn.prepareStatement(orphanSql);
                     ResultSet rs = stmt.executeQuery()) {
                    if (rs.next()) {
                        long orphanCount = rs.getLong("orphan_count");
                        rel.setOrphanCount(orphanCount);

                        // Calculate integrity percentage
                        String countSql = String.format(
                            "SELECT COUNT(*) FROM \"%s\" WHERE \"%s\" IS NOT NULL",
                            rel.getSourceTable(), rel.getSourceColumn());
                        try (PreparedStatement countStmt = conn.prepareStatement(countSql);
                             ResultSet countRs = countStmt.executeQuery()) {
                            if (countRs.next()) {
                                long totalCount = countRs.getLong(1);
                                if (totalCount > 0) {
                                    double integrityPct = (totalCount - orphanCount) * 100.0 / totalCount;
                                    rel.setDataIntegrityPct(BigDecimal.valueOf(integrityPct)
                                        .setScale(2, RoundingMode.HALF_UP));
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Could not check integrity for {}.{} -> {}.{}: {}",
                    rel.getSourceTable(), rel.getSourceColumn(),
                    rel.getTargetTable(), rel.getTargetColumn(), e.getMessage());
            }
        }
    }

    /**
     * Check index coverage for FK columns.
     */
    private void checkIndexCoverage(Connection conn, String dbType,
                                     List<TableRelationshipClassification> relationships) {
        for (TableRelationshipClassification rel : relationships) {
            if (rel.getSourceColumn() == null) {
                continue;
            }

            try {
                String indexSql;
                if ("postgresql".equalsIgnoreCase(dbType)) {
                    indexSql = """
                        SELECT COUNT(*) > 0 as has_index
                        FROM pg_indexes
                        WHERE tablename = ? AND indexdef LIKE '%' || ? || '%'
                        """;
                } else {
                    indexSql = """
                        SELECT COUNT(*) > 0 as has_index
                        FROM information_schema.STATISTICS
                        WHERE TABLE_NAME = ? AND COLUMN_NAME = ? AND TABLE_SCHEMA = DATABASE()
                        """;
                }

                try (PreparedStatement stmt = conn.prepareStatement(indexSql)) {
                    stmt.setString(1, rel.getSourceTable());
                    stmt.setString(2, rel.getSourceColumn());
                    try (ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            rel.setHasIndex(rs.getBoolean("has_index"));
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Could not check index for {}.{}: {}",
                    rel.getSourceTable(), rel.getSourceColumn(), e.getMessage());
            }
        }
    }

    /**
     * Generate summary statistics for relationships.
     */
    private Map<String, Object> generateSummary(List<TableRelationshipClassification> relationships) {
        Map<String, Object> summary = new HashMap<>();

        summary.put("total_relationships", relationships.size());

        // Count by strength
        long strongCount = relationships.stream().filter(r -> "STRONG".equals(r.getStrength())).count();
        long inferredCount = relationships.stream().filter(r -> "INFERRED".equals(r.getStrength())).count();
        long weakCount = relationships.stream().filter(r -> "WEAK".equals(r.getStrength())).count();
        summary.put("strong_count", strongCount);
        summary.put("inferred_count", inferredCount);
        summary.put("weak_count", weakCount);

        // Count by type
        long oneToOne = relationships.stream().filter(r -> "ONE_TO_ONE".equals(r.getRelationshipType())).count();
        long oneToMany = relationships.stream().filter(r -> "ONE_TO_MANY".equals(r.getRelationshipType())).count();
        long manyToMany = relationships.stream().filter(r -> "MANY_TO_MANY".equals(r.getRelationshipType())).count();
        summary.put("one_to_one_count", oneToOne);
        summary.put("one_to_many_count", oneToMany);
        summary.put("many_to_many_count", manyToMany);

        // Integrity issues
        long integrityIssues = relationships.stream()
            .filter(r -> r.getOrphanCount() != null && r.getOrphanCount() > 0)
            .count();
        summary.put("integrity_issues_count", integrityIssues);

        // Missing indexes
        long missingIndexes = relationships.stream()
            .filter(r -> r.getHasIndex() != null && !r.getHasIndex() && r.getJoinFrequency() != null && r.getJoinFrequency() > 0)
            .count();
        summary.put("missing_indexes_count", missingIndexes);

        // Pending validation
        long pendingValidation = relationships.stream()
            .filter(r -> "PENDING".equals(r.getValidationStatus()))
            .count();
        summary.put("pending_validation_count", pendingValidation);

        return summary;
    }

    /**
     * Get all relationships for a connection.
     */
    public List<TableRelationshipClassification> getRelationships(String connectionId) {
        return relationshipRepository.findLatestByConnectionIdOrderBySourceTableAsc(connectionId);
    }

    /**
     * Get relationships with integrity issues.
     */
    public List<TableRelationshipClassification> getIntegrityIssues(String connectionId) {
        return relationshipRepository.findWithIntegrityIssues(connectionId);
    }

    /**
     * Get relationships missing indexes.
     */
    public List<TableRelationshipClassification> getMissingIndexes(String connectionId) {
        return relationshipRepository.findUnindexedRelationships(connectionId);
    }

    /**
     * Update relationship validation status.
     */
    @Transactional
    public void updateValidationStatus(String relationshipId, String status) {
        relationshipRepository.updateValidationStatus(relationshipId, status);
    }

    // ==================== Data Classes ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RelationshipClassificationResult {
        private String connectionId;
        private String schemaClassificationId;
        private List<TableRelationshipClassification> relationships;
        private Map<String, Object> summary;
    }
}
