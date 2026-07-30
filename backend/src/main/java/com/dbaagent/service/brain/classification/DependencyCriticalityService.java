package com.dbaagent.service.brain.classification;

import com.dbaagent.service.ConnectionService;
import com.dbaagent.service.CredentialService;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.InferredTableRelationship;
import com.dbaagent.provider.DatabaseProviderRegistry;
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
 * Service for assessing dependency criticality of tables.
 *
 * Measures:
 * - Number of tables that depend on this table (dependents)
 * - Number of tables this table depends on (dependencies)
 * - Depth in dependency graph (how many layers of dependencies)
 * - Cascade impact (what would break if this table is modified)
 *
 * Criticality Levels:
 * - CRITICAL: Core table, many dependents, high cascade impact
 * - HIGH: Important table, several dependents
 * - MEDIUM: Moderate dependencies
 * - LOW: Few dependencies, leaf table
 * - ISOLATED: No dependencies
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DependencyCriticalityService {

    private final ConnectionService connectionService;
    private final CredentialService credentialService;
    private final InferredTableRelationshipRepository relationshipRepository;
    private final DatabaseProviderRegistry providerRegistry;

    // Thresholds
    private static final int CRITICAL_DEPENDENT_COUNT = 10;
    private static final int HIGH_DEPENDENT_COUNT = 5;
    private static final int MEDIUM_DEPENDENT_COUNT = 2;

    /**
     * Assess dependency criticality for all tables in a connection.
     */
    public Map<String, DependencyCriticality> assessDependencyCriticality(String connectionId) {
        Map<String, DependencyCriticality> results = new HashMap<>();

        ConnectionRequest connRequest = credentialService.getDecryptedConnection(connectionId);
        if (connRequest == null) {
            log.warn("Connection not found: {}", connectionId);
            return results;
        }

        try (Connection conn = connectionService.getConnection(connectionId, connRequest)) {
            String dbType = providerRegistry.getCanonicalName(connRequest.getDbType());

            // Get FK-based dependencies from database
            Map<String, Set<String>> dependsOn = new HashMap<>();  // table -> tables it depends on
            Map<String, Set<String>> dependedBy = new HashMap<>(); // table -> tables that depend on it

            loadForeignKeyDependencies(conn, dbType, dependsOn, dependedBy);

            // Also load inferred relationships
            loadInferredRelationships(connectionId, dependsOn, dependedBy);

            // Get all tables
            Set<String> allTables = new HashSet<>();
            allTables.addAll(dependsOn.keySet());
            allTables.addAll(dependedBy.keySet());

            // Also get tables without any relationships
            addOrphanTables(conn, dbType, allTables);

            // Calculate criticality for each table
            for (String tableName : allTables) {
                Set<String> dependencies = dependsOn.getOrDefault(tableName, Set.of());
                Set<String> dependents = dependedBy.getOrDefault(tableName, Set.of());

                // Calculate cascade impact (transitive closure of dependents)
                Set<String> cascadeImpact = calculateCascadeImpact(tableName, dependedBy);

                // Calculate dependency depth
                int depth = calculateDependencyDepth(tableName, dependsOn, new HashSet<>());

                DependencyCriticality criticality = calculateCriticality(
                    tableName, dependencies, dependents, cascadeImpact, depth);
                results.put(tableName.toLowerCase(), criticality);
            }
        } catch (Exception e) {
            log.error("Error assessing dependency criticality for connection {}: {}", connectionId, e.getMessage(), e);
        }

        return results;
    }

    private void loadForeignKeyDependencies(Connection conn, String dbType,
                                             Map<String, Set<String>> dependsOn,
                                             Map<String, Set<String>> dependedBy) {
        String query;
        if ("postgres".equals(dbType)) {
            query = """
                SELECT
                    cl.relname as source_table,
                    clr.relname as target_table
                FROM pg_constraint c
                JOIN pg_class cl ON c.conrelid = cl.oid
                JOIN pg_namespace ns ON cl.relnamespace = ns.oid
                JOIN pg_class clr ON c.confrelid = clr.oid
                JOIN pg_namespace nsr ON clr.relnamespace = nsr.oid
                WHERE c.contype = 'f'
                AND ns.nspname NOT IN ('pg_catalog', 'information_schema')
                """;
        } else {
            query = """
                SELECT
                    TABLE_NAME as source_table,
                    REFERENCED_TABLE_NAME as target_table
                FROM information_schema.KEY_COLUMN_USAGE
                WHERE REFERENCED_TABLE_NAME IS NOT NULL
                AND TABLE_SCHEMA NOT IN ('mysql', 'information_schema', 'performance_schema', 'sys')
                """;
        }

        try (PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                String sourceTable = rs.getString("source_table").toLowerCase();
                String targetTable = rs.getString("target_table").toLowerCase();

                // Source depends on target (FK reference)
                dependsOn.computeIfAbsent(sourceTable, k -> new HashSet<>()).add(targetTable);
                // Target is depended on by source
                dependedBy.computeIfAbsent(targetTable, k -> new HashSet<>()).add(sourceTable);

                // Ensure both tables exist in maps
                dependsOn.computeIfAbsent(targetTable, k -> new HashSet<>());
                dependedBy.computeIfAbsent(sourceTable, k -> new HashSet<>());
            }
        } catch (Exception e) {
            log.debug("Error loading FK dependencies: {}", e.getMessage());
        }
    }

    private void loadInferredRelationships(String connectionId,
                                            Map<String, Set<String>> dependsOn,
                                            Map<String, Set<String>> dependedBy) {
        List<InferredTableRelationship> relationships =
            relationshipRepository.findByConnectionIdOrderByJoinCountDesc(connectionId);

        for (InferredTableRelationship rel : relationships) {
            String sourceTable = rel.getSourceTable().toLowerCase();
            String targetTable = rel.getTargetTable().toLowerCase();

            dependsOn.computeIfAbsent(sourceTable, k -> new HashSet<>()).add(targetTable);
            dependedBy.computeIfAbsent(targetTable, k -> new HashSet<>()).add(sourceTable);

            dependsOn.computeIfAbsent(targetTable, k -> new HashSet<>());
            dependedBy.computeIfAbsent(sourceTable, k -> new HashSet<>());
        }
    }

    private void addOrphanTables(Connection conn, String dbType, Set<String> allTables) {
        String query;
        if ("postgres".equals(dbType)) {
            query = """
                SELECT relname as table_name
                FROM pg_stat_user_tables
                WHERE schemaname NOT IN ('pg_catalog', 'information_schema')
                """;
        } else {
            query = """
                SELECT TABLE_NAME as table_name
                FROM information_schema.TABLES
                WHERE TABLE_SCHEMA NOT IN ('mysql', 'information_schema', 'performance_schema', 'sys')
                AND TABLE_TYPE = 'BASE TABLE'
                """;
        }

        try (PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                allTables.add(rs.getString("table_name").toLowerCase());
            }
        } catch (Exception e) {
            log.debug("Error loading table list: {}", e.getMessage());
        }
    }

    private Set<String> calculateCascadeImpact(String tableName, Map<String, Set<String>> dependedBy) {
        Set<String> visited = new HashSet<>();
        Queue<String> queue = new LinkedList<>();
        queue.add(tableName);

        while (!queue.isEmpty()) {
            String current = queue.poll();
            Set<String> dependents = dependedBy.getOrDefault(current, Set.of());
            for (String dependent : dependents) {
                if (!visited.contains(dependent)) {
                    visited.add(dependent);
                    queue.add(dependent);
                }
            }
        }

        return visited;
    }

    private int calculateDependencyDepth(String tableName, Map<String, Set<String>> dependsOn, Set<String> visited) {
        if (visited.contains(tableName)) {
            return 0; // Circular dependency protection
        }
        visited.add(tableName);

        Set<String> dependencies = dependsOn.getOrDefault(tableName, Set.of());
        if (dependencies.isEmpty()) {
            return 0;
        }

        int maxDepth = 0;
        for (String dep : dependencies) {
            maxDepth = Math.max(maxDepth, 1 + calculateDependencyDepth(dep, dependsOn, new HashSet<>(visited)));
        }
        return maxDepth;
    }

    private DependencyCriticality calculateCriticality(String tableName,
                                                         Set<String> dependencies,
                                                         Set<String> dependents,
                                                         Set<String> cascadeImpact,
                                                         int dependencyDepth) {
        int dependentCount = dependents.size();
        int dependencyCount = dependencies.size();
        int cascadeCount = cascadeImpact.size();

        // Calculate criticality score (0-100)
        double score = 0;

        // Dependent count is most important (up to 40 points)
        score += Math.min(40, dependentCount * 4);

        // Cascade impact (up to 30 points)
        score += Math.min(30, cascadeCount * 2);

        // Dependency depth (up to 20 points)
        score += Math.min(20, dependencyDepth * 5);

        // Having dependencies also matters (up to 10 points)
        score += Math.min(10, dependencyCount * 2);

        // Determine criticality level
        String criticalityLevel;
        List<String> recommendations = new ArrayList<>();
        List<String> impactAreas = new ArrayList<>();

        if (dependentCount >= CRITICAL_DEPENDENT_COUNT || cascadeCount >= 15) {
            criticalityLevel = "CRITICAL";
            recommendations.add("Requires extensive testing before any schema changes");
            recommendations.add("Document all dependent applications and services");
            recommendations.add("Establish change management process");
            impactAreas.add("Schema changes affect " + cascadeCount + " tables");
            impactAreas.add("High risk of cascading failures");
        } else if (dependentCount >= HIGH_DEPENDENT_COUNT || cascadeCount >= 8) {
            criticalityLevel = "HIGH";
            recommendations.add("Test schema changes in staging environment first");
            recommendations.add("Coordinate changes with dependent table owners");
            impactAreas.add("Moderate cascade risk");
        } else if (dependentCount >= MEDIUM_DEPENDENT_COUNT || cascadeCount >= 3) {
            criticalityLevel = "MEDIUM";
            recommendations.add("Standard testing sufficient");
            recommendations.add("Review dependent tables before changes");
        } else if (dependentCount > 0 || dependencyCount > 0) {
            criticalityLevel = "LOW";
            recommendations.add("Minimal impact expected from changes");
        } else {
            criticalityLevel = "ISOLATED";
            recommendations.add("Table has no dependencies - safe to modify");
            recommendations.add("Consider if this table should be connected to the schema");
        }

        // Add specific impact areas
        if (dependentCount > 0) {
            impactAreas.add(String.format("%d direct dependents: %s",
                dependentCount, dependents.stream().limit(5).collect(Collectors.joining(", "))));
        }
        if (dependencyCount > 0) {
            impactAreas.add(String.format("%d dependencies: %s",
                dependencyCount, dependencies.stream().limit(5).collect(Collectors.joining(", "))));
        }

        String reasoning = String.format(
            "%d dependents, %d dependencies, depth %d, cascade impact %d tables",
            dependentCount, dependencyCount, dependencyDepth, cascadeCount);

        return DependencyCriticality.builder()
            .tableName(tableName)
            .criticalityLevel(criticalityLevel)
            .criticalityScore(BigDecimal.valueOf(score).setScale(2, RoundingMode.HALF_UP))
            .dependentCount(dependentCount)
            .dependencyCount(dependencyCount)
            .cascadeImpactCount(cascadeCount)
            .dependencyDepth(dependencyDepth)
            .directDependents(new ArrayList<>(dependents))
            .directDependencies(new ArrayList<>(dependencies))
            .cascadeImpactTables(new ArrayList<>(cascadeImpact))
            .impactAreas(impactAreas)
            .recommendations(recommendations)
            .reasoning(reasoning)
            .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DependencyCriticality {
        private String tableName;
        private String criticalityLevel;  // CRITICAL, HIGH, MEDIUM, LOW, ISOLATED
        private BigDecimal criticalityScore;
        private Integer dependentCount;
        private Integer dependencyCount;
        private Integer cascadeImpactCount;
        private Integer dependencyDepth;
        private List<String> directDependents;
        private List<String> directDependencies;
        private List<String> cascadeImpactTables;
        private List<String> impactAreas;
        private List<String> recommendations;
        private String reasoning;
    }
}
