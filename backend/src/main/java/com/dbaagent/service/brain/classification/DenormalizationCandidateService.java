package com.dbaagent.service.brain.classification;

import com.dbaagent.service.ConnectionService;
import com.dbaagent.service.CredentialService;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.InferredTableRelationship;
import com.dbaagent.repository.InferredTableRelationshipRepository;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for identifying denormalization candidates.
 *
 * Identifies tables that are:
 * - Joined together in 80%+ of queries
 * - Small lookup tables joined to large fact tables
 * - Causing N+1 query patterns
 *
 * Recommends:
 * - Adding redundant columns to avoid joins
 * - Materialized views
 * - Caching strategies
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DenormalizationCandidateService {

    private final ConnectionService connectionService;
    private final CredentialService credentialService;
    private final InferredTableRelationshipRepository relationshipRepository;

    // Thresholds
    private static final int HIGH_JOIN_FREQUENCY = 100;
    private static final int SMALL_LOOKUP_MAX_ROWS = 10000;
    private static final double BENEFIT_THRESHOLD = 0.5;

    /**
     * Identify denormalization candidates for all tables in a connection.
     */
    public Map<String, DenormalizationCandidate> identifyDenormalizationCandidates(String connectionId) {
        Map<String, DenormalizationCandidate> results = new HashMap<>();

        ConnectionRequest connRequest = credentialService.getDecryptedConnection(connectionId);
        if (connRequest == null) {
            log.warn("Connection not found: {}", connectionId);
            return results;
        }

        try (Connection conn = connectionService.getConnection(connectionId, connRequest)) {
            // Get table sizes
            Map<String, Long> tableSizes = getTableSizes(conn, connRequest.getDbType());

            // Get join relationships
            List<InferredTableRelationship> relationships =
                relationshipRepository.findByConnectionIdOrderByJoinCountDesc(connectionId);

            // Group relationships by source table
            Map<String, List<InferredTableRelationship>> tableRelationships = relationships.stream()
                .collect(Collectors.groupingBy(r -> r.getSourceTable().toLowerCase()));

            // Analyze each table
            for (Map.Entry<String, List<InferredTableRelationship>> entry : tableRelationships.entrySet()) {
                String tableName = entry.getKey();
                List<InferredTableRelationship> rels = entry.getValue();

                DenormalizationCandidate candidate = analyzeTable(
                    tableName, rels, tableSizes, connRequest.getDbType());

                if (candidate.getIsCandidate()) {
                    results.put(tableName.toLowerCase(), candidate);
                }
            }

            // Also analyze tables that don't have relationships yet
            for (String tableName : tableSizes.keySet()) {
                if (!results.containsKey(tableName.toLowerCase())) {
                    results.put(tableName.toLowerCase(), DenormalizationCandidate.builder()
                        .tableName(tableName)
                        .isCandidate(false)
                        .benefitScore(BigDecimal.ZERO)
                        .reasoning("No frequent join patterns detected")
                        .recommendations(List.of())
                        .build());
                }
            }

        } catch (Exception e) {
            log.error("Error identifying denormalization candidates for connection {}: {}",
                connectionId, e.getMessage(), e);
        }

        return results;
    }

    private Map<String, Long> getTableSizes(Connection conn, String dbType) {
        Map<String, Long> sizes = new HashMap<>();

        String query;
        if ("postgresql".equalsIgnoreCase(dbType) || "postgres".equalsIgnoreCase(dbType)) {
            query = """
                SELECT relname as table_name, n_live_tup as row_count
                FROM pg_stat_user_tables
                WHERE schemaname NOT IN ('pg_catalog', 'information_schema')
                """;
        } else {
            query = """
                SELECT TABLE_NAME as table_name, TABLE_ROWS as row_count
                FROM information_schema.TABLES
                WHERE TABLE_SCHEMA NOT IN ('mysql', 'information_schema', 'performance_schema', 'sys')
                AND TABLE_TYPE = 'BASE TABLE'
                """;
        }

        try (PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                sizes.put(rs.getString("table_name").toLowerCase(), rs.getLong("row_count"));
            }
        } catch (Exception e) {
            log.debug("Error getting table sizes: {}", e.getMessage());
        }

        return sizes;
    }

    private DenormalizationCandidate analyzeTable(String tableName,
                                                   List<InferredTableRelationship> relationships,
                                                   Map<String, Long> tableSizes,
                                                   String dbType) {
        Long sourceSize = tableSizes.getOrDefault(tableName.toLowerCase(), 0L);

        List<JoinCandidate> joinCandidates = new ArrayList<>();
        double totalBenefit = 0;

        for (InferredTableRelationship rel : relationships) {
            String targetTable = rel.getTargetTable().toLowerCase();
            Long targetSize = tableSizes.getOrDefault(targetTable, 0L);
            int joinCount = rel.getJoinCount() != null ? rel.getJoinCount() : 0;

            // Calculate benefit for embedding this relationship
            double benefit = 0;

            // High join frequency is a strong signal
            if (joinCount >= HIGH_JOIN_FREQUENCY) {
                benefit += 0.4;
            } else if (joinCount >= 50) {
                benefit += 0.2;
            }

            // Small lookup table being joined is a good candidate
            if (targetSize <= SMALL_LOOKUP_MAX_ROWS && sourceSize > SMALL_LOOKUP_MAX_ROWS) {
                benefit += 0.3;
            }

            // 1:1 or N:1 relationships are easier to denormalize
            String cardinality = rel.getCardinality();
            if ("1:1".equals(cardinality) || "N:1".equals(cardinality)) {
                benefit += 0.2;
            }

            if (benefit > 0.2) {
                String reason;
                String strategy;

                if (targetSize <= SMALL_LOOKUP_MAX_ROWS) {
                    reason = String.format("Small lookup table (%,d rows) joined %d times", targetSize, joinCount);
                    strategy = String.format("Add redundant columns from %s to %s", targetTable, tableName);
                } else {
                    reason = String.format("High join frequency (%d joins) with %s", joinCount, targetTable);
                    strategy = String.format("Create materialized view joining %s and %s", tableName, targetTable);
                }

                joinCandidates.add(JoinCandidate.builder()
                    .targetTable(targetTable)
                    .joinColumn(rel.getSourceColumn())
                    .joinFrequency(joinCount)
                    .targetTableSize(targetSize)
                    .benefit(BigDecimal.valueOf(benefit).setScale(2, RoundingMode.HALF_UP))
                    .reasoning(reason)
                    .strategy(strategy)
                    .build());

                totalBenefit += benefit;
            }
        }

        // Average the benefit if multiple candidates
        if (!joinCandidates.isEmpty()) {
            totalBenefit = totalBenefit / joinCandidates.size();
        }

        boolean isCandidate = totalBenefit >= BENEFIT_THRESHOLD;

        List<String> recommendations = new ArrayList<>();
        if (isCandidate) {
            recommendations.add("Consider denormalizing to reduce JOIN overhead");

            // Sort by benefit and add specific recommendations
            joinCandidates.sort((a, b) -> b.getBenefit().compareTo(a.getBenefit()));
            for (int i = 0; i < Math.min(3, joinCandidates.size()); i++) {
                recommendations.add(joinCandidates.get(i).getStrategy());
            }

            recommendations.add("Test query performance before and after denormalization");
            recommendations.add("Consider data consistency implications");
        }

        String reasoning;
        if (isCandidate) {
            reasoning = String.format("Found %d join patterns with avg benefit score %.2f",
                joinCandidates.size(), totalBenefit);
        } else if (joinCandidates.isEmpty()) {
            reasoning = "No significant join patterns found";
        } else {
            reasoning = String.format("Join patterns found but benefit score (%.2f) below threshold", totalBenefit);
        }

        return DenormalizationCandidate.builder()
            .tableName(tableName)
            .isCandidate(isCandidate)
            .benefitScore(BigDecimal.valueOf(totalBenefit).setScale(2, RoundingMode.HALF_UP))
            .joinCandidates(joinCandidates)
            .tableRowCount(sourceSize)
            .reasoning(reasoning)
            .recommendations(recommendations)
            .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class JoinCandidate {
        private String targetTable;
        private String joinColumn;
        private Integer joinFrequency;
        private Long targetTableSize;
        private BigDecimal benefit;
        private String reasoning;
        private String strategy;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DenormalizationCandidate {
        private String tableName;
        private Boolean isCandidate;
        private BigDecimal benefitScore;
        private List<JoinCandidate> joinCandidates;
        private Long tableRowCount;
        private String reasoning;
        private List<String> recommendations;
    }
}
