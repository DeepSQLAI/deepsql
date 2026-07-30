package com.dbaagent.service.codescan;

import com.dbaagent.model.ColumnAntiPattern;
import com.dbaagent.model.ColumnDisambiguation;
import com.dbaagent.model.SchemaDocumentation;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.TableMetadata;
import com.dbaagent.repository.ColumnAntiPatternRepository;
import com.dbaagent.repository.ColumnDisambiguationRepository;
import com.dbaagent.repository.SchemaDocumentationRepository;
import com.dbaagent.service.SchemaScannerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Composes schema-ambiguity signals from existing repos. No new ML; this just
 * orchestrates what the brain already knows so the UI can surface it and the
 * code scanner can use it as a focus prior.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SchemaAmbiguityService {

    private static final int MAX_MISSING_COLUMN_ITEMS = 60;
    private static final int MAX_SIMILAR_TABLE_PAIRS = 25;
    private static final int MAX_TOTAL_ITEMS = 200;
    private static final int GOD_TABLE_COLUMN_THRESHOLD = 50;
    private static final double JACCARD_THRESHOLD = 0.3;

    private final SchemaScannerService schemaScannerService;
    private final SchemaDocumentationRepository schemaDocRepository;
    private final ColumnDisambiguationRepository columnDisambiguationRepository;
    private final ColumnAntiPatternRepository columnAntiPatternRepository;

    /**
     * Compute the full ambiguity inventory for a connection. Caller is
     * expected to cap UI rendering itself; we cap to MAX_TOTAL_ITEMS so a
     * 5 000-table schema can't tank the request.
     */
    public List<AmbiguityItem> compute(String connectionId) {
        SchemaMetadata schema;
        try {
            schema = schemaScannerService.scanSchema(connectionId);
        } catch (Exception e) {
            log.warn("ambiguity: schema scan failed for {}: {}", connectionId, e.getMessage());
            return List.of();
        }

        List<SchemaDocumentation> docs = schemaDocRepository.findByConnectionId(connectionId);
        Set<String> documentedTables = new HashSet<>();
        Set<String> documentedColumns = new HashSet<>(); // "TABLE.COLUMN"
        for (SchemaDocumentation d : docs) {
            if (d.getObjectType() == SchemaDocumentation.DocumentationType.TABLE) {
                documentedTables.add(d.getObjectName().toUpperCase(Locale.ROOT));
            } else if (d.getObjectType() == SchemaDocumentation.DocumentationType.COLUMN) {
                String parent = d.getParentObject() == null ? "" : d.getParentObject().toUpperCase(Locale.ROOT);
                String col = d.getObjectName().toUpperCase(Locale.ROOT);
                documentedColumns.add(parent + "." + col);
            }
        }

        List<AmbiguityItem> items = new ArrayList<>();

        // ---- Missing table descriptions ----
        for (TableMetadata table : safe(schema.getTables())) {
            String name = table.getName();
            if (name == null) continue;
            if (!documentedTables.contains(name.toUpperCase(Locale.ROOT))) {
                items.add(new AmbiguityItem(
                    "MISSING_TABLE_DESCRIPTION",
                    name, null,
                    "No description for table " + name,
                    "Brain has no schema_documentation entry for this table. Code scan with focus on it could enrich.",
                    0.45,
                    Map.of("columnCount", table.getColumns() == null ? 0 : table.getColumns().size())
                ));
            }
        }

        // ---- Missing column descriptions (capped — there can be tens of thousands) ----
        int missingColumnsBudget = MAX_MISSING_COLUMN_ITEMS;
        for (TableMetadata table : safe(schema.getTables())) {
            if (missingColumnsBudget <= 0) break;
            if (table.getColumns() == null || table.getName() == null) continue;
            String tableUpper = table.getName().toUpperCase(Locale.ROOT);
            for (var col : table.getColumns()) {
                if (missingColumnsBudget <= 0) break;
                if (col == null || col.getName() == null) continue;
                String fqn = tableUpper + "." + col.getName().toUpperCase(Locale.ROOT);
                if (documentedColumns.contains(fqn)) continue;
                // Skip obviously-named columns to keep noise down.
                if (isObviouslyNamed(col.getName())) continue;
                items.add(new AmbiguityItem(
                    "MISSING_COLUMN_DESCRIPTION",
                    table.getName(), col.getName(),
                    "No description for " + table.getName() + "." + col.getName(),
                    "Cryptic or undocumented column; code may reveal its meaning.",
                    0.35,
                    Map.of("dataType", col.getDataType() == null ? "" : col.getDataType())
                ));
                missingColumnsBudget--;
            }
        }

        // ---- Similar table names (Jaccard on underscore tokens) ----
        items.addAll(findSimilarTablePairs(schema));

        // ---- Column-name disambiguation (user-curated + cross-table column collisions) ----
        items.addAll(findColumnCollisions(connectionId, schema));

        // ---- God tables ----
        for (TableMetadata table : safe(schema.getTables())) {
            int colCount = table.getColumns() == null ? 0 : table.getColumns().size();
            if (colCount > GOD_TABLE_COLUMN_THRESHOLD) {
                items.add(new AmbiguityItem(
                    "GOD_TABLE",
                    table.getName(), null,
                    table.getName() + " has " + colCount + " columns — likely a god table",
                    "Tables this wide usually conflate multiple concepts. Code references can disentangle them.",
                    0.55,
                    Map.of("columnCount", colCount)
                ));
            }
        }

        // ---- High-severity anti-patterns ----
        for (ColumnAntiPattern ap : columnAntiPatternRepository
            .findByConnectionIdAndSeverityOrderByDetectedAtDesc(connectionId, ColumnAntiPattern.Severity.HIGH)) {
            items.add(new AmbiguityItem(
                "ANTI_PATTERN",
                ap.getTableName(), ap.getColumnName(),
                ap.getTitle() == null ? ap.getPatternType() : ap.getTitle(),
                ap.getDescription() == null ? "" : ap.getDescription(),
                0.7,
                Map.of("patternType", ap.getPatternType() == null ? "" : ap.getPatternType(),
                       "affectedQueries", ap.getAffectedQueriesCount() == null ? 0 : ap.getAffectedQueriesCount())
            ));
        }
        for (ColumnAntiPattern ap : columnAntiPatternRepository
            .findByConnectionIdAndSeverityOrderByDetectedAtDesc(connectionId, ColumnAntiPattern.Severity.CRITICAL)) {
            items.add(new AmbiguityItem(
                "ANTI_PATTERN",
                ap.getTableName(), ap.getColumnName(),
                ap.getTitle() == null ? ap.getPatternType() : ap.getTitle(),
                ap.getDescription() == null ? "" : ap.getDescription(),
                0.85,
                Map.of("patternType", ap.getPatternType() == null ? "" : ap.getPatternType(),
                       "affectedQueries", ap.getAffectedQueriesCount() == null ? 0 : ap.getAffectedQueriesCount())
            ));
        }

        // ---- Sort by severity desc + cap ----
        items.sort(Comparator.comparingDouble(AmbiguityItem::severity).reversed());
        if (items.size() > MAX_TOTAL_ITEMS) {
            return new ArrayList<>(items.subList(0, MAX_TOTAL_ITEMS));
        }
        return items;
    }

    /**
     * Render a compact text block summarising the top ambiguity items, suitable
     * for inclusion in the LLM <focus> prompt block. Capped to ~3 KB.
     */
    public String summariseForFocus(String connectionId) {
        List<AmbiguityItem> items = compute(connectionId);
        StringBuilder sb = new StringBuilder();
        sb.append("Schema-derived focus (top ambiguities):\n");
        int budget = 3000;
        int count = 0;
        for (AmbiguityItem item : items) {
            if (budget <= 0) break;
            String line = "- [" + item.kind() + "] " + item.title() + "\n";
            if (line.length() > budget) break;
            sb.append(line);
            budget -= line.length();
            count++;
            if (count >= 25) break;
        }
        return count == 0 ? "" : sb.toString().trim();
    }

    // ---- helpers ----

    private List<AmbiguityItem> findSimilarTablePairs(SchemaMetadata schema) {
        List<TableMetadata> tables = safe(schema.getTables());
        List<AmbiguityItem> out = new ArrayList<>();
        if (tables.size() < 2) return out;

        // Pre-tokenise once.
        Map<String, Set<String>> tokens = new HashMap<>();
        for (TableMetadata t : tables) {
            if (t.getName() == null) continue;
            tokens.put(t.getName(), tokenise(t.getName()));
        }
        // Index tables by their tokens to bound the candidate-pair set.
        Map<String, List<String>> byToken = new HashMap<>();
        for (Map.Entry<String, Set<String>> e : tokens.entrySet()) {
            for (String tk : e.getValue()) {
                byToken.computeIfAbsent(tk, k -> new ArrayList<>()).add(e.getKey());
            }
        }

        Set<String> seenPairKeys = new HashSet<>();
        for (List<String> bucket : byToken.values()) {
            if (bucket.size() < 2) continue;
            for (int i = 0; i < bucket.size(); i++) {
                for (int j = i + 1; j < bucket.size(); j++) {
                    String a = bucket.get(i);
                    String b = bucket.get(j);
                    if (a.equalsIgnoreCase(b)) continue;
                    String pairKey = a.compareTo(b) < 0 ? a + "|" + b : b + "|" + a;
                    if (!seenPairKeys.add(pairKey)) continue;
                    double j2 = jaccard(tokens.get(a), tokens.get(b));
                    if (j2 >= JACCARD_THRESHOLD) {
                        out.add(new AmbiguityItem(
                            "SIMILAR_TABLE_NAMES",
                            a, null,
                            a + " ↔ " + b + " — similar names (Jaccard " + String.format("%.2f", j2) + ")",
                            "These tables share most of their name tokens. Code usage can clarify which is canonical.",
                            0.5 + (j2 - JACCARD_THRESHOLD) * 0.5,
                            Map.of("otherTable", b, "jaccard", j2)
                        ));
                    }
                }
            }
        }
        out.sort(Comparator.comparingDouble(AmbiguityItem::severity).reversed());
        if (out.size() > MAX_SIMILAR_TABLE_PAIRS) {
            return new ArrayList<>(out.subList(0, MAX_SIMILAR_TABLE_PAIRS));
        }
        return out;
    }

    private List<AmbiguityItem> findColumnCollisions(String connectionId, SchemaMetadata schema) {
        List<AmbiguityItem> out = new ArrayList<>();
        // User-curated column disambiguation
        for (ColumnDisambiguation d : columnDisambiguationRepository.findByConnectionId(connectionId)) {
            out.add(new AmbiguityItem(
                "COLUMN_DISAMBIGUATION",
                d.getPreferredTable(), d.getColumnName(),
                "Column " + d.getColumnName() + " — preferred table is " + d.getPreferredTable(),
                "Existing user-curated disambiguation. Confirm code matches.",
                0.4,
                Map.of("preferredTable", d.getPreferredTable() == null ? "" : d.getPreferredTable())
            ));
        }
        // Cross-table column-name collisions (column name appears in ≥3 tables).
        Map<String, List<String>> columnToTables = new LinkedHashMap<>();
        for (TableMetadata t : safe(schema.getTables())) {
            if (t.getName() == null || t.getColumns() == null) continue;
            for (var c : t.getColumns()) {
                if (c == null || c.getName() == null) continue;
                String key = c.getName().toUpperCase(Locale.ROOT);
                columnToTables.computeIfAbsent(key, k -> new ArrayList<>()).add(t.getName());
            }
        }
        for (Map.Entry<String, List<String>> e : columnToTables.entrySet()) {
            if (e.getValue().size() < 3) continue;
            // Skip obviously-shared columns.
            if (isObviouslyShared(e.getKey())) continue;
            out.add(new AmbiguityItem(
                "COLUMN_DISAMBIGUATION",
                null, e.getKey(),
                "Column " + e.getKey() + " appears in " + e.getValue().size() + " tables",
                "Multiple tables share this column name. Code usage can pinpoint canonical.",
                0.45,
                Map.of("tables", e.getValue().subList(0, Math.min(8, e.getValue().size())))
            ));
        }
        return out;
    }

    private static Set<String> tokenise(String name) {
        Set<String> out = new HashSet<>();
        for (String tk : name.toUpperCase(Locale.ROOT).split("[_\\s]+")) {
            if (tk.length() < 3) continue;
            out.add(tk);
            // Add stem (drop trailing 'S' for plurals so BOOKINGS matches BOOKING).
            if (tk.length() > 4 && tk.endsWith("S")) {
                out.add(tk.substring(0, tk.length() - 1));
            }
        }
        return out;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a == null || b == null || a.isEmpty() || b.isEmpty()) return 0;
        Set<String> inter = new HashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new HashSet<>(a);
        union.addAll(b);
        return (double) inter.size() / union.size();
    }

    private static boolean isObviouslyNamed(String columnName) {
        if (columnName == null) return false;
        String lower = columnName.toLowerCase(Locale.ROOT);
        return lower.equals("id") || lower.equals("created_at") || lower.equals("updated_at")
            || lower.equals("deleted_at") || lower.equals("created_by") || lower.equals("updated_by");
    }

    private static boolean isObviouslyShared(String upperColumnName) {
        return upperColumnName.equals("ID") || upperColumnName.equals("CREATED_AT")
            || upperColumnName.equals("UPDATED_AT") || upperColumnName.equals("DELETED_AT")
            || upperColumnName.equals("HOTEL_ID") || upperColumnName.equals("USER_ID");
    }

    private static <T> List<T> safe(List<T> in) {
        return in == null ? List.of() : in;
    }

    /** Surface item used by the Unresolved panel and the LLM focus prior. */
    public record AmbiguityItem(
        String kind,
        String targetTable,
        String targetColumn,
        String title,
        String detail,
        double severity,
        Map<String, Object> evidence
    ) {}
}
