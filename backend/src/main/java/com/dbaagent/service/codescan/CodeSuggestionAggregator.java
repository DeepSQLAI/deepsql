package com.dbaagent.service.codescan;

import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.TableMetadata;
import com.dbaagent.model.code.CodeKnowledgeSuggestion;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

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
 * Collapses raw extractor output into deduplicated suggestion rows.
 *
 * - Validates targetTable / targetColumn against the live schema (drops misses).
 * - Groups by (targetKind, targetObject) so multiple proposals for the same
 *   table/column collapse into one row with alternates attached.
 * - Carries through provenance ({@code sourceFiles}) for the review UI.
 */
@Component
@Slf4j
public class CodeSuggestionAggregator {

    public List<CodeKnowledgeSuggestion> aggregate(
        String connectionId,
        String jobId,
        List<CodeKnowledgeExtractor.RawSuggestion> raw,
        SchemaMetadata schema
    ) {
        if (raw == null || raw.isEmpty()) return List.of();
        Map<String, String> tableLookup = buildTableLookup(schema);
        Map<String, String> columnLookup = buildColumnLookup(schema);

        Map<String, List<CodeKnowledgeExtractor.RawSuggestion>> grouped = new LinkedHashMap<>();
        for (var s : raw) {
            String normalizedTable = s.targetTable == null ? null : tableLookup.get(s.targetTable.toLowerCase(Locale.ROOT));
            if (s.targetTable != null && normalizedTable == null) {
                continue;
            }
            String normalizedColumn = null;
            if (s.targetColumn != null && normalizedTable != null) {
                String fqnKey = (normalizedTable + "." + s.targetColumn).toLowerCase(Locale.ROOT);
                String canonicalCol = columnLookup.get(fqnKey);
                if (canonicalCol == null) {
                    continue;
                }
                normalizedColumn = canonicalCol;
            }
            s.targetTable = normalizedTable;
            s.targetColumn = normalizedColumn;

            String key = groupKey(s);
            grouped.computeIfAbsent(key, k -> new ArrayList<>()).add(s);
        }

        List<CodeKnowledgeSuggestion> out = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            List<CodeKnowledgeExtractor.RawSuggestion> bucket = entry.getValue();
            bucket.sort(Comparator.comparingDouble((CodeKnowledgeExtractor.RawSuggestion r) -> r.confidence).reversed());
            CodeKnowledgeExtractor.RawSuggestion primary = bucket.get(0);

            CodeKnowledgeSuggestion suggestion = CodeKnowledgeSuggestion.builder()
                .jobId(jobId)
                .connectionId(connectionId)
                .targetKind(resolveTargetKind(primary))
                .targetObject(resolveTargetObject(primary))
                .title(primary.title)
                .content(primary.content)
                .linkedTables(primary.linkedTables)
                .linkedColumns(primary.linkedColumns)
                .confidence(clampConfidence(primary.confidence))
                .status(CodeKnowledgeSuggestion.Status.PENDING)
                .build();

            // payload carries entry type, businessTerms, and alternates
            Map<String, Object> payload = new LinkedHashMap<>();
            if (primary.entryType != null) payload.put("entryType", primary.entryType);
            if (primary.objectKind != null) payload.put("objectKind", primary.objectKind);
            if (!primary.businessTerms.isEmpty()) payload.put("businessTerms", primary.businessTerms);
            if (primary.rationale != null) payload.put("rationale", primary.rationale);

            if (bucket.size() > 1) {
                List<Map<String, Object>> alternates = new ArrayList<>();
                for (int i = 1; i < bucket.size(); i++) {
                    var alt = bucket.get(i);
                    Map<String, Object> a = new LinkedHashMap<>();
                    a.put("title", alt.title);
                    a.put("content", alt.content);
                    a.put("confidence", alt.confidence);
                    a.put("rationale", alt.rationale);
                    a.put("sourcePath", alt.sourcePath);
                    a.put("sourceStartLine", alt.sourceStartLine);
                    a.put("sourceEndLine", alt.sourceEndLine);
                    alternates.add(a);
                }
                payload.put("alternatives", alternates);
            }
            suggestion.setPayload(payload);

            // Source files: primary chunk + dedup of alt chunks
            List<Map<String, Object>> sources = new ArrayList<>();
            Set<String> seenSources = new HashSet<>();
            for (var item : bucket) {
                String key = item.sourcePath + ":" + item.sourceStartLine + ":" + item.sourceEndLine;
                if (!seenSources.add(key)) continue;
                Map<String, Object> sf = new LinkedHashMap<>();
                sf.put("path", item.sourcePath);
                sf.put("startLine", item.sourceStartLine);
                sf.put("endLine", item.sourceEndLine);
                if (item.rationale != null) sf.put("rationale", item.rationale);
                sources.add(sf);
            }
            suggestion.setSourceFiles(sources);

            out.add(suggestion);
        }
        return out;
    }

    private static String groupKey(CodeKnowledgeExtractor.RawSuggestion s) {
        String kind = s.kind == null ? "KNOWLEDGE_ENTRY" : s.kind.toUpperCase(Locale.ROOT);
        String table = s.targetTable == null ? "" : s.targetTable.toLowerCase(Locale.ROOT);
        String col = s.targetColumn == null ? "" : s.targetColumn.toLowerCase(Locale.ROOT);
        if ("KNOWLEDGE_ENTRY".equals(kind) && col.isEmpty() && table.isEmpty() && s.title != null) {
            // narrative entries with no schema target — group by title to dedupe near-duplicates
            return kind + "|TITLE|" + s.title.toLowerCase(Locale.ROOT);
        }
        return kind + "|" + table + "|" + col;
    }

    private static CodeKnowledgeSuggestion.TargetKind resolveTargetKind(CodeKnowledgeExtractor.RawSuggestion s) {
        if (s.kind != null && s.kind.equalsIgnoreCase("SCHEMA_DOC")) {
            return CodeKnowledgeSuggestion.TargetKind.SCHEMA_DOC;
        }
        return CodeKnowledgeSuggestion.TargetKind.KNOWLEDGE_ENTRY;
    }

    private static String resolveTargetObject(CodeKnowledgeExtractor.RawSuggestion s) {
        if (s.targetTable == null) return null;
        if (s.targetColumn != null) return s.targetTable + "." + s.targetColumn;
        return s.targetTable;
    }

    private static double clampConfidence(double c) {
        if (Double.isNaN(c)) return 0.5;
        if (c < 0) return 0;
        if (c > 1) return 1;
        return c;
    }

    private static Map<String, String> buildTableLookup(SchemaMetadata schema) {
        Map<String, String> lookup = new HashMap<>();
        if (schema == null || schema.getTables() == null) return lookup;
        for (TableMetadata t : schema.getTables()) {
            if (t == null || t.getName() == null) continue;
            String canonical = t.getName();
            lookup.putIfAbsent(canonical.toLowerCase(Locale.ROOT), canonical);
            if (t.getSchema() != null && !t.getSchema().isBlank()) {
                String fq = t.getSchema() + "." + canonical;
                lookup.putIfAbsent(fq.toLowerCase(Locale.ROOT), canonical);
            }
        }
        return lookup;
    }

    /** Maps lowercase "table.column" → canonical "table.column" string from the schema. */
    private static Map<String, String> buildColumnLookup(SchemaMetadata schema) {
        Map<String, String> lookup = new HashMap<>();
        if (schema == null || schema.getTables() == null) return lookup;
        for (TableMetadata t : schema.getTables()) {
            if (t == null || t.getColumns() == null) continue;
            for (var col : t.getColumns()) {
                if (col == null || col.getName() == null) continue;
                String key = (t.getName() + "." + col.getName()).toLowerCase(Locale.ROOT);
                lookup.putIfAbsent(key, col.getName());
            }
        }
        return lookup;
    }
}
