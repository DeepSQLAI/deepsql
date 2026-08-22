package com.dbaagent.service.codescan;

import com.dbaagent.model.CompanyKnowledgeEntry;
import com.dbaagent.model.SchemaDocumentation;
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
 * - Merges against existing company-knowledge entries and schema notes so the
 *   review queue does not surface overlapping intents.
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
        return aggregate(connectionId, jobId, raw, schema, List.of(), List.of());
    }

    public List<CodeKnowledgeSuggestion> aggregate(
        String connectionId,
        String jobId,
        List<CodeKnowledgeExtractor.RawSuggestion> raw,
        SchemaMetadata schema,
        List<CompanyKnowledgeEntry> existingKnowledge,
        List<SchemaDocumentation> existingDocs
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

        ExistingContextIndex existingIndex = new ExistingContextIndex(existingKnowledge, existingDocs);
        Map<String, CodeKnowledgeSuggestion> byIntent = new LinkedHashMap<>();

        for (var entry : grouped.entrySet()) {
            List<CodeKnowledgeExtractor.RawSuggestion> bucket = entry.getValue();
            bucket.sort(Comparator.comparingDouble((CodeKnowledgeExtractor.RawSuggestion r) -> r.confidence).reversed());
            CodeKnowledgeSuggestion suggestion = buildSuggestion(connectionId, jobId, bucket);
            String intentKey = existingIndex.intentKeyFor(suggestion);
            byIntent.merge(intentKey, suggestion, this::mergeSuggestions);
        }

        return new ArrayList<>(byIntent.values());
    }

    private CodeKnowledgeSuggestion buildSuggestion(
        String connectionId,
        String jobId,
        List<CodeKnowledgeExtractor.RawSuggestion> bucket
    ) {
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

        Map<String, Object> payload = new LinkedHashMap<>();
        if (primary.entryType != null) payload.put("entryType", primary.entryType);
        if (primary.objectKind != null) payload.put("objectKind", primary.objectKind);
        if (!primary.businessTerms.isEmpty()) payload.put("businessTerms", primary.businessTerms);
        if (primary.rationale != null) payload.put("rationale", primary.rationale);

        if (bucket.size() > 1) {
            payload.put("alternatives", buildAlternates(bucket.subList(1, bucket.size())));
        }
        suggestion.setPayload(payload);
        suggestion.setSourceFiles(buildSourceFiles(bucket));
        return suggestion;
    }

    private CodeKnowledgeSuggestion mergeSuggestions(CodeKnowledgeSuggestion primary, CodeKnowledgeSuggestion other) {
        if (other == null) return primary;
        if (primary == null) return other;

        if ((other.getConfidence() != null ? other.getConfidence() : 0)
            > (primary.getConfidence() != null ? primary.getConfidence() : 0)) {
            CodeKnowledgeSuggestion swap = primary;
            primary = other;
            other = swap;
        }

        Map<String, Object> payload = payloadOrNew(primary);
        appendAlternate(payload, other);

        if (other.getPayload() != null && other.getPayload().get("alternatives") instanceof List<?> alts) {
            for (Object alt : alts) {
                if (alt instanceof Map<?, ?> map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> cast = (Map<String, Object>) map;
                    appendAlternateMap(payload, cast);
                }
            }
        }

        primary.setPayload(payload);
        primary.setSourceFiles(mergeSourceFiles(primary.getSourceFiles(), other.getSourceFiles()));
        mergeExistingContextMarkers(payload, other.getPayload());
        return primary;
    }

    private static void appendAlternate(Map<String, Object> payload, CodeKnowledgeSuggestion other) {
        Map<String, Object> alt = new LinkedHashMap<>();
        alt.put("title", other.getTitle());
        alt.put("content", other.getContent());
        alt.put("confidence", other.getConfidence());
        if (other.getPayload() != null && other.getPayload().get("rationale") != null) {
            alt.put("rationale", other.getPayload().get("rationale"));
        }
        appendAlternateMap(payload, alt);
    }

    @SuppressWarnings("unchecked")
    private static void appendAlternateMap(Map<String, Object> payload, Map<String, Object> alt) {
        List<Map<String, Object>> alternates = (List<Map<String, Object>>) payload.computeIfAbsent(
            "alternatives",
            k -> new ArrayList<>()
        );
        alternates.add(alt);
    }

    private static void mergeExistingContextMarkers(Map<String, Object> target, Map<String, Object> source) {
        if (source == null) return;
        if (Boolean.TRUE.equals(source.get("mergedWithExisting"))) {
            target.put("mergedWithExisting", true);
        }
        for (String key : List.of("existingEntryId", "existingDocId", "existingTitle", "existingExcerpt")) {
            if (source.get(key) != null && target.get(key) == null) {
                target.put(key, source.get(key));
            }
        }
    }

    private static List<Map<String, Object>> buildAlternates(List<CodeKnowledgeExtractor.RawSuggestion> bucket) {
        List<Map<String, Object>> alternates = new ArrayList<>();
        for (var alt : bucket) {
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
        return alternates;
    }

    private static List<Map<String, Object>> buildSourceFiles(List<CodeKnowledgeExtractor.RawSuggestion> bucket) {
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
        return sources;
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> mergeSourceFiles(
        List<Map<String, Object>> left,
        List<Map<String, Object>> right
    ) {
        List<Map<String, Object>> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (List<Map<String, Object>> list : List.of(left, right)) {
            if (list == null) continue;
            for (Map<String, Object> sf : list) {
                String key = sf.get("path") + ":" + sf.get("startLine") + ":" + sf.get("endLine");
                if (seen.add(key)) merged.add(sf);
            }
        }
        return merged;
    }

    private static Map<String, Object> payloadOrNew(CodeKnowledgeSuggestion suggestion) {
        Map<String, Object> payload = suggestion.getPayload();
        if (payload == null) {
            payload = new LinkedHashMap<>();
            suggestion.setPayload(payload);
        }
        return payload;
    }

    private static String groupKey(CodeKnowledgeExtractor.RawSuggestion s) {
        String kind = s.kind == null ? "KNOWLEDGE_ENTRY" : s.kind.toUpperCase(Locale.ROOT);
        String table = s.targetTable == null ? "" : s.targetTable.toLowerCase(Locale.ROOT);
        String col = s.targetColumn == null ? "" : s.targetColumn.toLowerCase(Locale.ROOT);
        if ("KNOWLEDGE_ENTRY".equals(kind) && col.isEmpty() && table.isEmpty() && s.title != null) {
            return kind + "|TITLE|" + normalizeText(s.title);
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

    private static String normalizeText(String value) {
        if (value == null) return "";
        return value.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }

    /**
     * Indexes live knowledge-base rows so new scan proposals can be folded into a
     * single review intent when they overlap an existing rule or schema note.
     */
    static final class ExistingContextIndex {
        private final Map<String, CompanyKnowledgeEntry> knowledgeByTitle = new HashMap<>();
        private final Map<String, CompanyKnowledgeEntry> knowledgeByLink = new HashMap<>();
        private final Map<String, SchemaDocumentation> docsByTarget = new HashMap<>();

        ExistingContextIndex(List<CompanyKnowledgeEntry> knowledge, List<SchemaDocumentation> docs) {
            if (knowledge != null) {
                for (CompanyKnowledgeEntry entry : knowledge) {
                    if (entry == null || entry.getId() == null) continue;
                    if (entry.getTitle() != null) {
                        knowledgeByTitle.put(normalizeText(entry.getTitle()), entry);
                    }
                    indexKnowledgeLinks(entry);
                }
            }
            if (docs != null) {
                for (SchemaDocumentation doc : docs) {
                    if (doc == null || doc.getId() == null) continue;
                    String key = schemaDocTargetKey(doc);
                    if (!key.isBlank()) {
                        docsByTarget.put(key, doc);
                    }
                }
            }
        }

        private void indexKnowledgeLinks(CompanyKnowledgeEntry entry) {
            String entryType = entry.getEntryType() == null
                ? "business_rule"
                : entry.getEntryType().name().toLowerCase(Locale.ROOT);
            if (entry.getLinkedColumns() != null) {
                for (String col : entry.getLinkedColumns()) {
                    if (col == null || col.isBlank()) continue;
                    knowledgeByLink.put("col|" + entryType + "|" + normalizeText(col), entry);
                }
            }
            if (entry.getLinkedTables() != null) {
                for (String table : entry.getLinkedTables()) {
                    if (table == null || table.isBlank()) continue;
                    knowledgeByLink.put("table|" + entryType + "|" + normalizeText(table), entry);
                }
            }
        }

        String intentKeyFor(CodeKnowledgeSuggestion suggestion) {
            ExistingMatch match = findMatch(suggestion);
            if (match != null) {
                annotateExisting(suggestion, match);
                return match.intentKey();
            }
            return internalIntentKey(suggestion);
        }

        private ExistingMatch findMatch(CodeKnowledgeSuggestion suggestion) {
            if (suggestion.getTargetKind() == CodeKnowledgeSuggestion.TargetKind.SCHEMA_DOC) {
                String key = normalizeSchemaTarget(suggestion.getTargetObject());
                SchemaDocumentation doc = docsByTarget.get(key);
                if (doc != null) {
                    return ExistingMatch.forDoc(doc);
                }
                return null;
            }

            CompanyKnowledgeEntry byTitle = suggestion.getTitle() == null
                ? null
                : knowledgeByTitle.get(normalizeText(suggestion.getTitle()));
            if (byTitle != null) {
                return ExistingMatch.forEntry(byTitle);
            }

            String entryType = resolveEntryTypeKey(suggestion);
            if (suggestion.getLinkedColumns() != null) {
                for (String col : suggestion.getLinkedColumns()) {
                    CompanyKnowledgeEntry hit = knowledgeByLink.get("col|" + entryType + "|" + normalizeText(col));
                    if (hit != null) return ExistingMatch.forEntry(hit);
                }
            }
            if (suggestion.getLinkedTables() != null) {
                for (String table : suggestion.getLinkedTables()) {
                    CompanyKnowledgeEntry hit = knowledgeByLink.get("table|" + entryType + "|" + normalizeText(table));
                    if (hit != null) return ExistingMatch.forEntry(hit);
                }
            }
            if (suggestion.getTargetObject() != null) {
                CompanyKnowledgeEntry hit = knowledgeByLink.get(
                    "table|" + entryType + "|" + normalizeText(suggestion.getTargetObject())
                );
                if (hit != null) return ExistingMatch.forEntry(hit);
            }
            return null;
        }

        private static void annotateExisting(CodeKnowledgeSuggestion suggestion, ExistingMatch match) {
            Map<String, Object> payload = suggestion.getPayload();
            if (payload == null) {
                payload = new LinkedHashMap<>();
                suggestion.setPayload(payload);
            }
            payload.put("mergedWithExisting", true);
            if (match.entry() != null) {
                payload.put("existingEntryId", match.entry().getId());
                payload.put("existingTitle", match.entry().getTitle());
                payload.put("existingExcerpt", excerpt(match.entry().getContent()));
            }
            if (match.doc() != null) {
                payload.put("existingDocId", match.doc().getId());
                payload.put("existingTitle", schemaDocLabel(match.doc()));
                payload.put("existingExcerpt", excerpt(match.doc().getDescription()));
            }
        }

        private static String internalIntentKey(CodeKnowledgeSuggestion suggestion) {
            String kind = suggestion.getTargetKind() == null ? "KNOWLEDGE_ENTRY" : suggestion.getTargetKind().name();
            String target = suggestion.getTargetObject() == null ? "" : normalizeText(suggestion.getTargetObject());
            String title = suggestion.getTitle() == null ? "" : normalizeText(suggestion.getTitle());
            return "NEW|" + kind + "|" + target + "|" + title;
        }

        private static String resolveEntryTypeKey(CodeKnowledgeSuggestion suggestion) {
            Object raw = suggestion.getPayload() == null ? null : suggestion.getPayload().get("entryType");
            if (raw == null) return "business_rule";
            return raw.toString().trim().toLowerCase(Locale.ROOT);
        }

        private static String schemaDocTargetKey(SchemaDocumentation doc) {
            if (doc.getObjectType() == SchemaDocumentation.DocumentationType.COLUMN) {
                String parent = bareName(doc.getParentObject());
                return normalizeSchemaTarget(parent + "." + doc.getObjectName());
            }
            return normalizeSchemaTarget(doc.getObjectName());
        }

        private static String schemaDocLabel(SchemaDocumentation doc) {
            if (doc.getObjectType() == SchemaDocumentation.DocumentationType.COLUMN) {
                return bareName(doc.getParentObject()) + "." + doc.getObjectName();
            }
            return doc.getObjectName();
        }

        private static String bareName(String value) {
            if (value == null) return "";
            int dot = value.lastIndexOf('.');
            return dot >= 0 ? value.substring(dot + 1) : value;
        }

        private static String normalizeSchemaTarget(String target) {
            if (target == null) return "";
            String trimmed = target.trim().toLowerCase(Locale.ROOT);
            int dot = trimmed.lastIndexOf('.');
            if (dot < 0) return trimmed;
            String left = trimmed.substring(0, dot);
            String right = trimmed.substring(dot + 1);
            int leftDot = left.lastIndexOf('.');
            if (leftDot >= 0) {
                left = left.substring(leftDot + 1);
            }
            return left + "." + right;
        }

        private static String excerpt(String text) {
            if (text == null) return "";
            String trimmed = text.trim();
            if (trimmed.length() <= 480) return trimmed;
            return trimmed.substring(0, 477) + "…";
        }
    }

    private record ExistingMatch(CompanyKnowledgeEntry entry, SchemaDocumentation doc) {
        static ExistingMatch forEntry(CompanyKnowledgeEntry entry) {
            return new ExistingMatch(entry, null);
        }

        static ExistingMatch forDoc(SchemaDocumentation doc) {
            return new ExistingMatch(null, doc);
        }

        String intentKey() {
            if (entry != null) return "EXISTING_ENTRY|" + entry.getId();
            return "EXISTING_DOC|" + doc.getId();
        }
    }
}
