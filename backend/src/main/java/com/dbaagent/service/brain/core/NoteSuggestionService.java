package com.dbaagent.service.brain.core;

import com.dbaagent.dto.NoteSuggestionDTO;
import com.dbaagent.model.ColumnDisambiguation;
import com.dbaagent.model.KeyColumnAnalysis;
import com.dbaagent.model.SchemaDocumentation;
import com.dbaagent.model.TableClassification;
import com.dbaagent.repository.ColumnDisambiguationRepository;
import com.dbaagent.repository.KeyColumnAnalysisRepository;
import com.dbaagent.repository.SchemaDocumentationRepository;
import com.dbaagent.repository.TableClassificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service that generates suggestions for which tables/columns need documentation.
 * Uses existing Brain analysis data: Key Columns, Schema Classification, Disambiguation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NoteSuggestionService {

    private final KeyColumnAnalysisRepository keyColumnRepository;
    private final TableClassificationRepository tableClassificationRepository;
    private final SchemaDocumentationRepository documentationRepository;
    private final ColumnDisambiguationRepository disambiguationRepository;

    private static final BigDecimal HIGH_IMPORTANCE_THRESHOLD = new BigDecimal("70");
    private static final BigDecimal CRITICAL_IMPORTANCE_THRESHOLD = new BigDecimal("85");

    /**
     * Get suggested tables and columns that need documentation.
     *
     * @param connectionId the database connection ID
     * @param limit maximum number of suggestions to return
     * @return response with prioritized suggestions
     */
    public NoteSuggestionDTO.Response getSuggestions(String connectionId, int limit) {
        log.debug("Generating note suggestions for connection: {}", connectionId);

        // Get existing documentation to exclude already-documented items
        Set<String> documentedItems = getDocumentedItems(connectionId);

        List<NoteSuggestionDTO> suggestions = new ArrayList<>();

        // P0: High-importance columns with anti-patterns
        suggestions.addAll(getHighImportanceColumnsWithIssues(connectionId, documentedItems));

        // P0: Tables with critical anti-patterns
        suggestions.addAll(getCriticalAntiPatternTables(connectionId, documentedItems));

        // P1: Top key columns by importance (most used in queries)
        suggestions.addAll(getTopKeyColumns(connectionId, documentedItems));

        // P1: Ambiguous columns (same name in multiple tables)
        suggestions.addAll(getAmbiguousColumns(connectionId, documentedItems));

        // P2: Important tables (FACT, DIMENSION) without notes
        suggestions.addAll(getImportantTables(connectionId, documentedItems));

        // P2: Sensitive data tables
        suggestions.addAll(getSensitiveTables(connectionId, documentedItems));

        // Deduplicate by table.column key
        suggestions = deduplicateSuggestions(suggestions);

        // Sort by score descending, then by priority
        suggestions.sort((a, b) -> {
            int priorityCompare = a.getPriority().compareTo(b.getPriority());
            if (priorityCompare != 0) return priorityCompare;
            return Double.compare(b.getScore(), a.getScore());
        });

        // Limit results
        if (suggestions.size() > limit) {
            suggestions = suggestions.subList(0, limit);
        }

        return NoteSuggestionDTO.Response.builder()
                .suggestions(suggestions)
                .totalCount(suggestions.size())
                .generatedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
                .build();
    }

    private Set<String> getDocumentedItems(String connectionId) {
        List<SchemaDocumentation> docs = documentationRepository.findByConnectionId(connectionId);
        Set<String> documented = new HashSet<>();

        for (SchemaDocumentation doc : docs) {
            if (doc.getObjectType() == SchemaDocumentation.DocumentationType.TABLE) {
                documented.add("TABLE:" + doc.getObjectName().toLowerCase());
            } else if (doc.getObjectType() == SchemaDocumentation.DocumentationType.COLUMN) {
                String key = "COLUMN:" + doc.getParentObject().toLowerCase() + "." + doc.getObjectName().toLowerCase();
                documented.add(key);
            }
        }

        return documented;
    }

    /**
     * P0: High-importance columns that have anti-patterns detected
     */
    private List<NoteSuggestionDTO> getHighImportanceColumnsWithIssues(String connectionId, Set<String> documented) {
        List<KeyColumnAnalysis> columns = keyColumnRepository
                .findByConnectionIdAndHasAntiPatternsTrueOrderByImportanceScoreDesc(connectionId);

        return columns.stream()
                .filter(col -> col.getImportanceScore() != null &&
                        col.getImportanceScore().compareTo(HIGH_IMPORTANCE_THRESHOLD) >= 0)
                .filter(col -> !isDocumented(documented, "COLUMN", col.getTableName(), col.getColumnName()))
                .limit(5)
                .map(col -> NoteSuggestionDTO.builder()
                        .scopeType("COLUMN")
                        .tableName(col.getTableName())
                        .columnName(col.getColumnName())
                        .priority("P0")
                        .reason("High-usage column with detected issues")
                        .indicators(buildColumnIndicators(col))
                        .score(col.getImportanceScore().doubleValue() + 10) // Boost for having issues
                        .suggestedPrompt(buildColumnPrompt(col))
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * P0: Tables with critical or high severity anti-patterns
     */
    private List<NoteSuggestionDTO> getCriticalAntiPatternTables(String connectionId, Set<String> documented) {
        List<TableClassification> tables = tableClassificationRepository
                .findLatestByConnectionIdOrderByTableNameAsc(connectionId);

        return tables.stream()
                .filter(t -> "CRITICAL".equals(t.getAntiPatternSeverity()) || "HIGH".equals(t.getAntiPatternSeverity()))
                .filter(t -> !isDocumented(documented, "TABLE", t.getTableName(), null))
                .limit(3)
                .map(t -> NoteSuggestionDTO.builder()
                        .scopeType("TABLE")
                        .tableName(t.getTableName())
                        .columnName(null)
                        .priority("P0")
                        .reason("Table has " + t.getAntiPatternSeverity().toLowerCase() + " severity issues")
                        .indicators(buildTableIndicators(t))
                        .score(95.0)
                        .suggestedPrompt("Document the purpose of this table and any known issues or constraints.")
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * P1: Top key columns by importance score
     */
    private List<NoteSuggestionDTO> getTopKeyColumns(String connectionId, Set<String> documented) {
        List<KeyColumnAnalysis> columns = keyColumnRepository
                .findByConnectionIdOrderByImportanceScoreDesc(connectionId);

        return columns.stream()
                .filter(col -> col.getImportanceScore() != null &&
                        col.getImportanceScore().compareTo(CRITICAL_IMPORTANCE_THRESHOLD) >= 0)
                .filter(col -> !Boolean.TRUE.equals(col.getHasAntiPatterns())) // Exclude P0 items
                .filter(col -> !isDocumented(documented, "COLUMN", col.getTableName(), col.getColumnName()))
                .limit(5)
                .map(col -> NoteSuggestionDTO.builder()
                        .scopeType("COLUMN")
                        .tableName(col.getTableName())
                        .columnName(col.getColumnName())
                        .priority("P1")
                        .reason("Frequently used in queries")
                        .indicators(buildColumnIndicators(col))
                        .score(col.getImportanceScore().doubleValue())
                        .suggestedPrompt(buildColumnPrompt(col))
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * P1: Ambiguous columns (same column name appears in multiple tables)
     */
    private List<NoteSuggestionDTO> getAmbiguousColumns(String connectionId, Set<String> documented) {
        List<ColumnDisambiguation> ambiguous = disambiguationRepository.findByConnectionId(connectionId);

        // For ambiguous columns, we want to suggest documenting in the preferred table
        return ambiguous.stream()
                .filter(col -> !isDocumented(documented, "COLUMN", col.getPreferredTable(), col.getColumnName()))
                .limit(5)
                .map(col -> NoteSuggestionDTO.builder()
                        .scopeType("COLUMN")
                        .tableName(col.getPreferredTable())
                        .columnName(col.getColumnName())
                        .priority("P1")
                        .reason("Column name exists in multiple tables - clarify meaning")
                        .indicators(List.of(
                                "Ambiguous column: appears in multiple tables",
                                "Preferred table: " + col.getPreferredTable()
                        ))
                        .score(80.0)
                        .suggestedPrompt("Explain what this column represents and why " +
                                col.getPreferredTable() + " is the canonical source.")
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * P2: Important tables (FACT, DIMENSION, BRIDGE) without documentation
     */
    private List<NoteSuggestionDTO> getImportantTables(String connectionId, Set<String> documented) {
        List<TableClassification> tables = tableClassificationRepository
                .findLatestByConnectionIdOrderByTableNameAsc(connectionId);

        Set<String> importantRoles = Set.of("FACT", "DIMENSION", "BRIDGE");

        return tables.stream()
                .filter(t -> importantRoles.contains(t.getTableRole()))
                .filter(t -> !isDocumented(documented, "TABLE", t.getTableName(), null))
                .limit(5)
                .map(t -> NoteSuggestionDTO.builder()
                        .scopeType("TABLE")
                        .tableName(t.getTableName())
                        .columnName(null)
                        .priority("P2")
                        .reason(t.getTableRole() + " table in schema")
                        .indicators(buildTableIndicators(t))
                        .score(70.0)
                        .suggestedPrompt("Document the business purpose of this " +
                                t.getTableRole().toLowerCase() + " table and its key relationships.")
                        .build())
                .collect(Collectors.toList());
    }

    /**
     * P2: Tables with sensitive data
     */
    private List<NoteSuggestionDTO> getSensitiveTables(String connectionId, Set<String> documented) {
        List<TableClassification> tables = tableClassificationRepository
                .findLatestByConnectionIdOrderByTableNameAsc(connectionId);

        Set<String> sensitiveLevels = Set.of("PII_HIGH", "PII_MEDIUM", "FINANCIAL", "HEALTH", "REGULATED");

        return tables.stream()
                .filter(t -> t.getSensitivityLevel() != null && sensitiveLevels.contains(t.getSensitivityLevel()))
                .filter(t -> !isDocumented(documented, "TABLE", t.getTableName(), null))
                .limit(3)
                .map(t -> NoteSuggestionDTO.builder()
                        .scopeType("TABLE")
                        .tableName(t.getTableName())
                        .columnName(null)
                        .priority("P2")
                        .reason("Contains " + formatSensitivity(t.getSensitivityLevel()) + " data")
                        .indicators(buildTableIndicators(t))
                        .score(65.0)
                        .suggestedPrompt("Document data handling requirements, retention policies, and access controls.")
                        .build())
                .collect(Collectors.toList());
    }

    private boolean isDocumented(Set<String> documented, String scopeType, String tableName, String columnName) {
        if ("TABLE".equals(scopeType)) {
            return documented.contains("TABLE:" + tableName.toLowerCase());
        } else {
            return documented.contains("COLUMN:" + tableName.toLowerCase() + "." + columnName.toLowerCase());
        }
    }

    private List<String> buildColumnIndicators(KeyColumnAnalysis col) {
        List<String> indicators = new ArrayList<>();

        if (col.getImportanceScore() != null) {
            indicators.add("Importance: " + col.getImportanceScore().intValue());
        }
        if (col.getJoinCount() != null && col.getJoinCount() > 0) {
            indicators.add("Used in " + col.getJoinCount() + " JOINs");
        }
        if (col.getWhereCount() != null && col.getWhereCount() > 0) {
            indicators.add("Used in " + col.getWhereCount() + " WHERE clauses");
        }
        if (col.getDistinctQueriesCount() != null && col.getDistinctQueriesCount() > 0) {
            indicators.add("Found in " + col.getDistinctQueriesCount() + " unique queries");
        }
        if (Boolean.TRUE.equals(col.getHasAntiPatterns())) {
            indicators.add("Has " + col.getAntiPatternCount() + " anti-pattern(s)");
        }
        if (col.getIndexName() == null && col.getWhereCount() != null && col.getWhereCount() > 3) {
            indicators.add("Missing index (filtered often)");
        }

        return indicators;
    }

    private List<String> buildTableIndicators(TableClassification t) {
        List<String> indicators = new ArrayList<>();

        if (t.getTableRole() != null) {
            indicators.add("Role: " + t.getTableRole());
        }
        if (t.getAccessPattern() != null) {
            indicators.add("Access: " + t.getAccessPattern().replace("_", " ").toLowerCase());
        }
        if (t.getBusinessDomain() != null && !"UNKNOWN".equals(t.getBusinessDomain())) {
            indicators.add("Domain: " + t.getBusinessDomain().toLowerCase());
        }
        if (t.getHealthScore() != null) {
            indicators.add("Health: " + t.getHealthScore() + "/100");
        }
        if (t.getAntiPatterns() != null && !t.getAntiPatterns().isEmpty()) {
            indicators.add("Issues: " + t.getAntiPatterns());
        }

        return indicators;
    }

    private String buildColumnPrompt(KeyColumnAnalysis col) {
        StringBuilder prompt = new StringBuilder();

        if (col.getJoinCount() != null && col.getJoinCount() > 0) {
            prompt.append("This column is used for JOINs. ");
        }
        if (col.getWhereCount() != null && col.getWhereCount() > 0) {
            prompt.append("Frequently filtered. ");
        }

        prompt.append("Explain what values this column contains and any business rules.");

        if (col.getDistinctCount() != null && col.getDistinctCount() < 20) {
            prompt.append(" List valid values if it's an enum/status column.");
        }

        return prompt.toString();
    }

    private String formatSensitivity(String level) {
        return switch (level) {
            case "PII_HIGH" -> "high-sensitivity PII";
            case "PII_MEDIUM" -> "PII";
            case "FINANCIAL" -> "financial";
            case "HEALTH" -> "health/medical";
            case "REGULATED" -> "regulated";
            default -> "sensitive";
        };
    }

    private List<NoteSuggestionDTO> deduplicateSuggestions(List<NoteSuggestionDTO> suggestions) {
        Map<String, NoteSuggestionDTO> uniqueMap = new LinkedHashMap<>();

        for (NoteSuggestionDTO suggestion : suggestions) {
            String key = suggestion.getScopeType() + ":" +
                    suggestion.getTableName() + ":" +
                    (suggestion.getColumnName() != null ? suggestion.getColumnName() : "");

            // Keep the one with higher priority (lower P number) or higher score
            if (!uniqueMap.containsKey(key) ||
                    suggestion.getPriority().compareTo(uniqueMap.get(key).getPriority()) < 0) {
                uniqueMap.put(key, suggestion);
            }
        }

        return new ArrayList<>(uniqueMap.values());
    }
}
