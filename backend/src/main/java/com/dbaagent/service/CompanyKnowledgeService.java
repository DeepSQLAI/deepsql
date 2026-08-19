package com.dbaagent.service;

import com.dbaagent.model.ColumnMetadata;
import com.dbaagent.model.CompanyKnowledgeEntry;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.TableMetadata;
import com.dbaagent.model.TrainingDataEmbedding;
import com.dbaagent.repository.CompanyKnowledgeEntryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.SQLException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class CompanyKnowledgeService {

    private static final Pattern COLUMN_ANNOTATION_PATTERN = Pattern.compile("(?<!@)@@([A-Za-z_][\\w$.]*)");
    private static final Pattern TABLE_ANNOTATION_PATTERN = Pattern.compile("(?<!@)@([A-Za-z_][\\w$.]*)");

    // Per-entry content cap for hint/diagnostics output. Sized to fit a complete business rule
    // (including trailing operative clauses like FX-conversion notes); the outer section cap in
    // SchemaRelationshipReasoningService still bounds the total companyKnowledgeContext.
    private static final int RULE_CONTENT_CAP_CHARS = 3000;

    private final CompanyKnowledgeEntryRepository companyKnowledgeEntryRepository;
    private final TrainingService trainingService;
    private final SchemaScannerService schemaScannerService;
    private final org.springframework.context.ApplicationEventPublisher eventPublisher;

    // ── RAG-driven selector tunables ─────────────────────────────────────────
    // Total char budget for the company-knowledge section emitted to the LLM.
    @Value("${app.chat.company-knowledge.char-budget:12000}")
    private int charBudget;

    // Hard ceiling on the number of entries included (regardless of budget remaining).
    @Value("${app.chat.company-knowledge.max-entries:12}")
    private int maxEntries;

    // Minimum embedding cosine score for a RAG hit to qualify on its own. Tuned to
    // the magnitude of the hybrid-search scores this codebase actually produces
    // (~0.01–0.05 for real signal). Set to 0 to admit every RAG candidate. Borderline
    // hits can still survive via the link-floor pass below.
    @Value("${app.chat.company-knowledge.score-floor:0.0}")
    private double scoreFloor;

    // Score weights when combining the embedding cosine with link/coverage signals.
    // The link/coverage bonus is added on top of the embedding score so a weak cosine
    // hit with a strong table-link match still ranks above a strong cosine hit with
    // no link to focus tables — same intuition as the legacy keyword scorer, but
    // applied as a re-ranker over RAG candidates instead of a load-all selector.
    @Value("${app.chat.company-knowledge.link-bonus-table:1.20}")
    private double linkBonusTable;

    @Value("${app.chat.company-knowledge.link-bonus-column:0.90}")
    private double linkBonusColumn;

    @Value("${app.chat.company-knowledge.coverage-bonus-complete:0.24}")
    private double coverageBonusComplete;

    @Value("${app.chat.company-knowledge.coverage-bonus-partial:0.12}")
    private double coverageBonusPartial;

    @Value("${app.chat.company-knowledge.business-rule-bonus:0.18}")
    private double businessRuleBonus;

    // Score floor applied to entries surfaced ONLY via the link-floor pass — i.e.
    // entries whose linked_tables intersect focus tables but which didn't make the
    // RAG cut. We give them a synthetic baseline so they sort against the RAG hits.
    @Value("${app.chat.company-knowledge.link-floor-base-score:0.50}")
    private double linkFloorBaseScore;

    public record RelevantKnowledgeContext(String hintContext, String diagnosticsContext, List<CompanyKnowledgeEntry> entries) {}

    public List<CompanyKnowledgeEntry> listEntries(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return List.of();
        }
        return annotateEntries(connectionId, fetchEntries(connectionId), loadSchemaQuietly(connectionId));
    }

    public List<CompanyKnowledgeEntry> findLinkedEntries(String connectionId, String tableReference) {
        if (connectionId == null || connectionId.isBlank() || tableReference == null || tableReference.isBlank()) {
            return List.of();
        }
        return annotateEntries(connectionId,
            fetchEntries(connectionId).stream()
                .filter(entry -> entryLinksToTable(entry, tableReference))
                .toList(),
            loadSchemaQuietly(connectionId)
        );
    }

    /**
     * RAG-driven company-knowledge selector — the path used by {@code get_brain_context}
     * and the unified chat retrieval flow.
     *
     * <p>Replaces the old "load every entry, score by keywords, take top 4" approach.
     * Inputs are the COMPANY_KNOWLEDGE-typed embedding hits already retrieved by the
     * RAG pipeline. We re-rank those hits with link/coverage boosts and merge a
     * targeted "link-floor" pass (entries whose {@code linked_tables} intersect the
     * focus tables) so high-precision rules survive even when their cosine score is
     * borderline. Output is bounded by char budget rather than a hard top-K so a
     * large CK corpus can surface more relevant rules without exploding the prompt.
     */
    public RelevantKnowledgeContext selectFromRagHits(
        String connectionId,
        List<TrainingDataEmbedding> ragHits,
        Collection<String> focusTables,
        SchemaMetadata schema
    ) {
        if (connectionId == null || connectionId.isBlank()) {
            return new RelevantKnowledgeContext("", "", List.of());
        }

        Set<String> normalizedFocus = normalizeFocusTables(focusTables);

        // 1) Build {entryId -> bestRagScore} from the COMPANY_KNOWLEDGE hits the RAG
        //    pipeline already produced. Two-source pipeline:
        //      - Stage 1 (cosine-similarity hits): score is the embedding similarity.
        //        Apply the configured floor.
        //      - Stage 2 (table-targeted hits): score is null because the upstream
        //        filter is membership-based, not similarity-based. These are ALREADY a
        //        strong signal (the entry is linked to a focus table), so we assign
        //        them the link-floor baseline so they survive any non-zero floor.
        Map<String, Double> ragScoreById = new HashMap<>();
        if (ragHits != null) {
            for (TrainingDataEmbedding hit : ragHits) {
                if (hit == null
                    || hit.getType() != TrainingDataEmbedding.TrainingDataType.COMPANY_KNOWLEDGE
                    || hit.getId() == null) {
                    continue;
                }
                double score;
                if (hit.getScore() == null) {
                    score = linkFloorBaseScore;
                } else {
                    score = hit.getScore();
                    if (score < scoreFloor) {
                        continue;
                    }
                }
                ragScoreById.merge(hit.getId(), score, Math::max);
            }
        }

        // 2) Link-floor recall: targeted JSONB query for entries whose linked_tables
        //    intersect the focus set. Only triggers when focus tables exist — we do
        //    NOT load all entries.
        Map<String, Double> baseScoreById = new HashMap<>(ragScoreById);
        if (!normalizedFocus.isEmpty()) {
            String[] focusArray = normalizedFocus.toArray(new String[0]);
            try {
                List<CompanyKnowledgeEntry> linkFloor =
                    companyKnowledgeEntryRepository.findByLinkedTablesAny(connectionId, focusArray);
                for (CompanyKnowledgeEntry entry : linkFloor) {
                    if (entry == null || entry.getId() == null) continue;
                    baseScoreById.putIfAbsent(entry.getId(), linkFloorBaseScore);
                }
            } catch (Exception e) {
                log.warn("Link-floor recall failed for {} (focus={}): {}",
                    connectionId, normalizedFocus, e.getMessage());
            }
        }

        if (baseScoreById.isEmpty()) {
            return new RelevantKnowledgeContext("", "", List.of());
        }

        // 3) Load the underlying entries by id (single batched query) and annotate.
        List<CompanyKnowledgeEntry> entries =
            companyKnowledgeEntryRepository.findAllById(baseScoreById.keySet());
        if (entries.isEmpty()) {
            return new RelevantKnowledgeContext("", "", List.of());
        }
        SchemaMetadata effectiveSchema = schema != null ? schema : loadSchemaQuietly(connectionId);
        List<CompanyKnowledgeEntry> annotated = annotateEntries(connectionId, entries, effectiveSchema);

        // 4) Re-rank: combinedScore = embeddingScore + link/coverage/type bonus.
        List<RankedKnowledge> ranked = annotated.stream()
            .map(entry -> {
                double base = baseScoreById.getOrDefault(entry.getId(), 0.0);
                double bonus = computeRerankBonus(entry, normalizedFocus);
                int combined = (int) Math.round((base + bonus) * 100.0);
                return new RankedKnowledge(entry, combined);
            })
            .filter(item -> item.score() > 0)
            .sorted(Comparator.comparingInt(RankedKnowledge::score).reversed()
                .thenComparing(item -> safe(item.entry().getTitle()), String.CASE_INSENSITIVE_ORDER))
            .toList();

        if (ranked.isEmpty()) {
            return new RelevantKnowledgeContext("", "", List.of());
        }

        // 5) Char-budget accumulation — replaces the legacy hard top-4 cap.
        StringBuilder hints = new StringBuilder();
        hints.append("=== COMPANY KNOWLEDGE HINTS ===\n");
        StringBuilder diagnostics = new StringBuilder();
        diagnostics.append("=== COMPANY KNOWLEDGE DIAGNOSTICS ===\n");
        List<CompanyKnowledgeEntry> selected = new ArrayList<>();
        for (RankedKnowledge item : ranked) {
            if (selected.size() >= maxEntries) {
                break;
            }
            int beforeHint = hints.length();
            int beforeDiag = diagnostics.length();
            appendHintEntry(hints, item.entry());
            appendDiagnosticsEntry(diagnostics, item.entry());
            if (hints.length() > charBudget) {
                hints.setLength(beforeHint);
                diagnostics.setLength(beforeDiag);
                break;
            }
            selected.add(item.entry());
        }

        if (selected.isEmpty()) {
            return new RelevantKnowledgeContext("", "", List.of());
        }
        return new RelevantKnowledgeContext(hints.toString().trim(), diagnostics.toString().trim(), selected);
    }

    private Set<String> normalizeFocusTables(Collection<String> focusTables) {
        Set<String> normalized = new LinkedHashSet<>();
        if (focusTables == null) {
            return normalized;
        }
        for (String value : focusTables) {
            if (value == null) continue;
            String canonical = SchemaObjectNameUtil.normalizedCanonicalTableName(value);
            if (!canonical.isBlank()) {
                normalized.add(canonical);
            }
        }
        return normalized;
    }

    private double computeRerankBonus(CompanyKnowledgeEntry entry, Set<String> normalizedFocus) {
        if (entry == null) return 0.0;
        double bonus = 0.0;

        if (!normalizedFocus.isEmpty() && entry.getLinkedTables() != null) {
            for (String linkedTable : entry.getLinkedTables()) {
                if (focusMatchesTable(normalizedFocus, linkedTable)) {
                    bonus += linkBonusTable;
                }
            }
        }
        if (!normalizedFocus.isEmpty() && entry.getLinkedColumns() != null) {
            for (String linkedColumn : entry.getLinkedColumns()) {
                String linkedTable = tableReferenceFromColumn(linkedColumn);
                if (!linkedTable.isBlank() && focusMatchesTable(normalizedFocus, linkedTable)) {
                    bonus += linkBonusColumn;
                }
            }
        }
        if (entry.getEntryType() == CompanyKnowledgeEntry.EntryType.BUSINESS_RULE) {
            bonus += businessRuleBonus;
        }
        if (entry.getCoverageStatus() != null) {
            switch (entry.getCoverageStatus()) {
                case "COMPLETE" -> bonus += coverageBonusComplete;
                case "PARTIAL" -> bonus += coverageBonusPartial;
                default -> { }
            }
        }
        return bonus;
    }

    /**
     * Tolerant focus-vs-linked-table comparison: a focus token matches the linked
     * table either by canonical equality, or when the linked table is a schema-qualified
     * suffix of the focus (e.g., focus {@code PRODUCT_PRICING} matches linked
     * {@code analytics_db.PRODUCT_PRICING}). Mirrors the SQL anchor logic in
     * {@link CompanyKnowledgeEntryRepository#findByLinkedTablesAny} so the in-memory
     * re-ranker stays consistent with the link-floor recall pass.
     */
    private boolean focusMatchesTable(Set<String> normalizedFocus, String linkedTable) {
        if (linkedTable == null || linkedTable.isBlank()) {
            return false;
        }
        String canonical = SchemaObjectNameUtil.normalizedCanonicalTableName(linkedTable);
        if (canonical.isBlank()) {
            return false;
        }
        if (normalizedFocus.contains(canonical)) {
            return true;
        }
        int lastDot = canonical.lastIndexOf('.');
        if (lastDot > 0 && normalizedFocus.contains(canonical.substring(lastDot + 1))) {
            return true;
        }
        for (String focus : normalizedFocus) {
            int focusLastDot = focus.lastIndexOf('.');
            if (focusLastDot > 0 && canonical.equals(focus.substring(focusLastDot + 1))) {
                return true;
            }
        }
        return false;
    }

    private void appendHintEntry(StringBuilder sb, CompanyKnowledgeEntry entry) {
        sb.append("- ").append(entry.getTitle());
        if (entry.getEntryType() != null) {
            sb.append(" [").append(entry.getEntryType().name()).append("]");
        }
        sb.append(": ").append(compact(entry.getContent(), RULE_CONTENT_CAP_CHARS)).append("\n");
        if (entry.getLinkedTables() != null && !entry.getLinkedTables().isEmpty()) {
            sb.append("  Linked tables: ").append(String.join(", ", entry.getLinkedTables())).append("\n");
        }
        if (entry.getLinkedColumns() != null && !entry.getLinkedColumns().isEmpty()) {
            sb.append("  Linked columns: ")
                .append(String.join(", ", entry.getLinkedColumns().stream().limit(8).toList()))
                .append("\n");
        }

        List<String> hintedTables = new ArrayList<>();
        if (entry.getMentionedTables() != null) {
            for (String mentionedTable : entry.getMentionedTables()) {
                if (mentionedTable == null || mentionedTable.isBlank()) {
                    continue;
                }
                boolean alreadyLinked = entry.getLinkedTables() != null
                    && entry.getLinkedTables().stream().anyMatch(linked ->
                    SchemaObjectNameUtil.referencesSameTable(linked, mentionedTable));
                if (!alreadyLinked) {
                    hintedTables.add(mentionedTable);
                }
            }
        }
        if (!hintedTables.isEmpty()) {
            sb.append("  Possible related tables from note: ")
                .append(String.join(", ", hintedTables.stream().limit(8).toList()))
                .append("\n");
        }
    }

    private void appendDiagnosticsEntry(StringBuilder sb, CompanyKnowledgeEntry entry) {
        sb.append("- ").append(entry.getTitle());
        if (entry.getEntryType() != null) {
            sb.append(" [").append(entry.getEntryType().name()).append("]");
        }
        List<String> statusTokens = new ArrayList<>();
        if (notBlank(entry.getCoverageStatus())) {
            statusTokens.add("coverage=" + entry.getCoverageStatus());
        }
        if (!statusTokens.isEmpty()) {
            sb.append(" {").append(String.join(", ", statusTokens)).append("}");
        }
        sb.append(": ").append(compact(entry.getContent(), RULE_CONTENT_CAP_CHARS)).append("\n");
        if (entry.getLinkedTables() != null && !entry.getLinkedTables().isEmpty()) {
            sb.append("  Linked tables: ").append(String.join(", ", entry.getLinkedTables())).append("\n");
        }
        if (entry.getLinkedColumns() != null && !entry.getLinkedColumns().isEmpty()) {
            sb.append("  Linked columns: ")
                .append(String.join(", ", entry.getLinkedColumns().stream().limit(8).toList()))
                .append("\n");
        }
        if (entry.getUnlinkedMentions() != null && !entry.getUnlinkedMentions().isEmpty()) {
            sb.append("  Mentioned but not linked: ")
                .append(String.join(", ", entry.getUnlinkedMentions().stream().limit(8).toList()))
                .append("\n");
        }
        if (entry.getInvalidMentions() != null && !entry.getInvalidMentions().isEmpty()) {
            sb.append("  Invalid or unknown references: ")
                .append(String.join(", ", entry.getInvalidMentions().stream().limit(8).toList()))
                .append("\n");
        }
    }

    @Transactional
    public CompanyKnowledgeEntry createEntry(CompanyKnowledgeEntry entry) {
        validate(entry, true);
        entry.setEntryType(normalizeEntryType(entry.getEntryType()));
        SchemaMetadata schema = loadSchemaQuietly(entry.getConnectionId());
        NormalizedLinks links = normalizeLinkedReferences(entry, schema);
        entry.setLinkedTables(links.linkedTables());
        entry.setLinkedColumns(links.linkedColumns());
        CompanyKnowledgeEntry saved = companyKnowledgeEntryRepository.save(entry);
        // Embedding + semantic-model rebuild run AFTER commit, off the request
        // thread (see CompanyKnowledgeIndexer) — they used to block the caller ~15s.
        eventPublisher.publishEvent(new CompanyKnowledgeIndexer.EntryUpserted(saved.getConnectionId(), saved.getId()));
        return annotateEntry(saved, schema);
    }

    @Transactional
    public CompanyKnowledgeEntry updateEntry(String entryId, CompanyKnowledgeEntry request) {
        validate(request, false);
        CompanyKnowledgeEntry existing = companyKnowledgeEntryRepository.findById(entryId)
            .orElseThrow(() -> new IllegalArgumentException("Company knowledge entry not found"));

        if (request.getConnectionId() != null
            && !request.getConnectionId().isBlank()
            && !existing.getConnectionId().equals(request.getConnectionId())) {
            throw new IllegalArgumentException("connectionId cannot be changed");
        }

        existing.setTitle(request.getTitle());
        existing.setEntryType(normalizeEntryType(request.getEntryType()));
        existing.setContent(request.getContent());
        SchemaMetadata schema = loadSchemaQuietly(existing.getConnectionId());
        NormalizedLinks links = normalizeLinkedReferences(request, schema);
        existing.setLinkedTables(links.linkedTables());
        existing.setLinkedColumns(links.linkedColumns());
        if (request.getCreatedBy() != null && !request.getCreatedBy().isBlank()) {
            existing.setCreatedBy(request.getCreatedBy());
        }

        CompanyKnowledgeEntry saved = companyKnowledgeEntryRepository.save(existing);
        eventPublisher.publishEvent(new CompanyKnowledgeIndexer.EntryUpserted(saved.getConnectionId(), saved.getId()));
        return annotateEntry(saved, schema);
    }

    @Transactional
    public void deleteEntry(String entryId) {
        CompanyKnowledgeEntry existing = companyKnowledgeEntryRepository.findById(entryId)
            .orElseThrow(() -> new IllegalArgumentException("Company knowledge entry not found"));
        companyKnowledgeEntryRepository.delete(existing);
        // Removing the embedding row is a fast local op — keep it inline so the
        // deleted rule stops matching immediately. The heavier semantic-model
        // rebuild runs after commit, off-thread.
        trainingService.deleteCompanyKnowledgeEmbedding(existing.getConnectionId(), existing.getId());
        eventPublisher.publishEvent(new CompanyKnowledgeIndexer.EntryDeleted(existing.getConnectionId()));
    }

    private void validate(CompanyKnowledgeEntry entry, boolean requireConnectionId) {
        if (entry == null) {
            throw new IllegalArgumentException("Request body is required");
        }
        if (requireConnectionId && (entry.getConnectionId() == null || entry.getConnectionId().isBlank())) {
            throw new IllegalArgumentException("connectionId is required");
        }
        if (entry.getTitle() == null || entry.getTitle().isBlank()) {
            throw new IllegalArgumentException("title is required");
        }
        if (entry.getContent() == null || entry.getContent().isBlank()) {
            throw new IllegalArgumentException("content is required");
        }
    }

    private CompanyKnowledgeEntry.EntryType normalizeEntryType(CompanyKnowledgeEntry.EntryType entryType) {
        return entryType != null ? entryType : CompanyKnowledgeEntry.EntryType.COMPANY_CONTEXT;
    }

    private NormalizedLinks normalizeLinkedReferences(CompanyKnowledgeEntry entry, SchemaMetadata schema) {
        LinkedHashSet<String> linkedTables = new LinkedHashSet<>(normalizeLinkedTables(entry.getLinkedTables()));
        LinkedHashSet<String> linkedColumns = new LinkedHashSet<>(normalizeLinkedColumns(entry.getLinkedColumns()));

        AnnotationLinks annotationLinks = extractAnnotationLinks(entry.getContent(), schema);
        linkedTables.addAll(annotationLinks.linkedTables());
        linkedColumns.addAll(annotationLinks.linkedColumns());

        return new NormalizedLinks(List.copyOf(linkedTables), List.copyOf(linkedColumns));
    }

    private List<String> normalizeLinkedTables(List<String> linkedTables) {
        if (linkedTables == null || linkedTables.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String linkedTable : linkedTables) {
            String canonical = SchemaObjectNameUtil.canonicalTableReference(linkedTable);
            if (!canonical.isBlank()) {
                normalized.add(canonical);
            }
        }
        return List.copyOf(normalized);
    }

    private List<String> normalizeLinkedColumns(List<String> linkedColumns) {
        if (linkedColumns == null || linkedColumns.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String linkedColumn : linkedColumns) {
            String canonical = canonicalColumnReference(linkedColumn);
            if (!canonical.isBlank()) {
                normalized.add(canonical);
            }
        }
        return List.copyOf(normalized);
    }

    private AnnotationLinks extractAnnotationLinks(String content, SchemaMetadata schema) {
        if (!notBlank(content)) {
            return AnnotationLinks.empty();
        }

        LinkedHashSet<String> linkedTables = new LinkedHashSet<>();
        LinkedHashSet<String> linkedColumns = new LinkedHashSet<>();

        Matcher columnMatcher = COLUMN_ANNOTATION_PATTERN.matcher(content);
        while (columnMatcher.find()) {
            String token = columnMatcher.group(1);
            String canonicalColumn = resolveAnnotatedColumnReference(token, schema);
            if (!canonicalColumn.isBlank()) {
                linkedColumns.add(canonicalColumn);
            }
        }

        Matcher tableMatcher = TABLE_ANNOTATION_PATTERN.matcher(content);
        while (tableMatcher.find()) {
            String token = tableMatcher.group(1);
            String canonicalTable = resolveAnnotatedTableReference(token, schema);
            if (!canonicalTable.isBlank()) {
                linkedTables.add(canonicalTable);
            }
        }

        return new AnnotationLinks(List.copyOf(linkedTables), List.copyOf(linkedColumns));
    }

    private String resolveAnnotatedTableReference(String token, SchemaMetadata schema) {
        String sanitized = sanitizeAnnotationToken(token);
        if (!notBlank(sanitized)) {
            return "";
        }
        if (schema != null) {
            String resolved = resolveSchemaTableReference(schema, sanitized);
            if (!resolved.isBlank()) {
                return resolved;
            }
        }
        return SchemaObjectNameUtil.canonicalTableReference(sanitized);
    }

    private String resolveAnnotatedColumnReference(String token, SchemaMetadata schema) {
        String sanitized = sanitizeAnnotationToken(token);
        if (!notBlank(sanitized)) {
            return "";
        }
        if (schema != null) {
            int lastDot = sanitized.lastIndexOf('.');
            if (lastDot > 0 && lastDot < sanitized.length() - 1) {
                String resolved = canonicalColumnReferenceInSchema(
                    sanitized.substring(0, lastDot),
                    sanitized.substring(lastDot + 1),
                    schema
                );
                if (!resolved.isBlank()) {
                    return resolved;
                }
            }
        }
        return canonicalColumnReference(sanitized);
    }

    private String sanitizeAnnotationToken(String token) {
        if (!notBlank(token)) {
            return "";
        }
        return token.trim().replaceAll("[,.;:]++$", "");
    }

    public String canonicalColumnReference(String reference) {
        if (reference == null || reference.isBlank()) {
            return "";
        }
        String sanitized = reference.trim()
            .replace("`", "")
            .replace("\"", "")
            .replace("[", "")
            .replace("]", "");
        int lastDot = sanitized.lastIndexOf('.');
        if (lastDot <= 0 || lastDot >= sanitized.length() - 1) {
            return "";
        }
        String column = sanitized.substring(lastDot + 1).trim();
        String tableReference = sanitized.substring(0, lastDot);
        String canonicalTable = SchemaObjectNameUtil.canonicalTableReference(tableReference);
        if (canonicalTable.isBlank() || column.isBlank()) {
            return "";
        }
        return canonicalTable + "." + column;
    }

    private boolean entryLinksToTable(CompanyKnowledgeEntry entry, String tableReference) {
        if (entry == null || tableReference == null || tableReference.isBlank()) {
            return false;
        }
        if (entry.getLinkedTables() != null && entry.getLinkedTables().stream()
            .anyMatch(linkedTable -> SchemaObjectNameUtil.referencesSameTable(linkedTable, tableReference))) {
            return true;
        }
        if (entry.getLinkedColumns() == null) {
            return false;
        }
        return entry.getLinkedColumns().stream()
            .map(this::tableReferenceFromColumn)
            .filter(value -> !value.isBlank())
            .anyMatch(linkedTable -> SchemaObjectNameUtil.referencesSameTable(linkedTable, tableReference));
    }

    private String tableReferenceFromColumn(String columnReference) {
        if (columnReference == null || columnReference.isBlank()) {
            return "";
        }
        int lastDot = columnReference.lastIndexOf('.');
        if (lastDot <= 0) {
            return "";
        }
        return SchemaObjectNameUtil.canonicalTableReference(columnReference.substring(0, lastDot));
    }

    private List<CompanyKnowledgeEntry> fetchEntries(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return List.of();
        }
        return companyKnowledgeEntryRepository.findByConnectionIdOrderByRecency(connectionId);
    }

    private List<CompanyKnowledgeEntry> annotateEntries(
        String connectionId,
        List<CompanyKnowledgeEntry> entries,
        SchemaMetadata schema
    ) {
        if (entries == null || entries.isEmpty()) {
            return List.of();
        }
        return entries.stream()
            .map(entry -> annotateEntry(entry, schema != null ? schema : loadSchemaQuietly(connectionId)))
            .toList();
    }

    private CompanyKnowledgeEntry annotateEntry(CompanyKnowledgeEntry entry, SchemaMetadata schema) {
        if (entry == null) {
            return null;
        }

        NormalizedLinks effectiveLinks = normalizeLinkedReferences(entry, schema);
        repairPersistedLinksIfNeeded(entry, effectiveLinks);
        entry.setLinkedTables(effectiveLinks.linkedTables());
        entry.setLinkedColumns(effectiveLinks.linkedColumns());

        AnnotationResolutionAnalysis annotationAnalysis = schema != null
            ? resolveAnnotationMentions(entry.getContent(), schema)
            : AnnotationResolutionAnalysis.empty();
        LinkedHashSet<String> unlinkedMentions = new LinkedHashSet<>();
        LinkedHashSet<String> invalidMentions = new LinkedHashSet<>(annotationAnalysis.invalidMentions());

        String joinCoverageStatus = "NOT_APPLICABLE";
        String coverageStatus = evaluateCoverageStatus(
            entry,
            annotationAnalysis.mentionedTables(),
            annotationAnalysis.mentionedColumns(),
            unlinkedMentions,
            invalidMentions,
            joinCoverageStatus
        );

        entry.setMentionedTables(annotationAnalysis.mentionedTables());
        entry.setMentionedColumns(annotationAnalysis.mentionedColumns());
        entry.setUnlinkedMentions(List.copyOf(unlinkedMentions));
        entry.setInvalidMentions(List.copyOf(invalidMentions));
        entry.setJoinCoverageStatus(joinCoverageStatus);
        entry.setCoverageStatus(coverageStatus);
        return entry;
    }

    private void repairPersistedLinksIfNeeded(CompanyKnowledgeEntry entry, NormalizedLinks effectiveLinks) {
        if (entry.getId() == null || entry.getId().isBlank()) {
            return;
        }
        List<String> currentTables = normalizeLinkedTables(entry.getLinkedTables());
        List<String> currentColumns = normalizeLinkedColumns(entry.getLinkedColumns());
        if (currentTables.equals(effectiveLinks.linkedTables()) && currentColumns.equals(effectiveLinks.linkedColumns())) {
            return;
        }

        entry.setLinkedTables(effectiveLinks.linkedTables());
        entry.setLinkedColumns(effectiveLinks.linkedColumns());
        companyKnowledgeEntryRepository.save(entry);
    }

    private String evaluateCoverageStatus(
        CompanyKnowledgeEntry entry,
        List<String> mentionedTables,
        List<String> mentionedColumns,
        Set<String> unlinkedMentions,
        Set<String> invalidMentions,
        String joinCoverageStatus
    ) {
        boolean hasLinks = (entry.getLinkedTables() != null && !entry.getLinkedTables().isEmpty())
            || (entry.getLinkedColumns() != null && !entry.getLinkedColumns().isEmpty());
        boolean hasMentions = (mentionedTables != null && !mentionedTables.isEmpty())
            || (mentionedColumns != null && !mentionedColumns.isEmpty());
        if (!invalidMentions.isEmpty()) {
            return "CONFLICTING";
        }
        if (!hasLinks && !hasMentions) {
            return "NONE";
        }
        return "COMPLETE";
    }

    private AnnotationResolutionAnalysis resolveAnnotationMentions(String content, SchemaMetadata schema) {
        if (!notBlank(content) || schema == null) {
            return AnnotationResolutionAnalysis.empty();
        }

        LinkedHashSet<String> mentionedTables = new LinkedHashSet<>();
        LinkedHashSet<String> mentionedColumns = new LinkedHashSet<>();
        LinkedHashSet<String> invalidMentions = new LinkedHashSet<>();

        Matcher columnMatcher = COLUMN_ANNOTATION_PATTERN.matcher(content);
        while (columnMatcher.find()) {
            String token = sanitizeAnnotationToken(columnMatcher.group(1));
            String canonicalColumn = resolveAnnotatedColumnReference(token, schema);
            if (canonicalColumn.isBlank()) {
                invalidMentions.add(token);
            } else {
                mentionedColumns.add(canonicalColumn);
            }
        }

        Matcher tableMatcher = TABLE_ANNOTATION_PATTERN.matcher(content);
        while (tableMatcher.find()) {
            String token = sanitizeAnnotationToken(tableMatcher.group(1));
            String canonicalTable = resolveAnnotatedTableReference(token, schema);
            if (canonicalTable.isBlank()) {
                invalidMentions.add(token);
            } else {
                mentionedTables.add(canonicalTable);
            }
        }

        return new AnnotationResolutionAnalysis(
            List.copyOf(mentionedTables),
            List.copyOf(mentionedColumns),
            List.copyOf(invalidMentions)
        );
    }

    private String canonicalColumnReferenceInSchema(String tableReference, String columnName, SchemaMetadata schema) {
        String canonicalTable = resolveSchemaTableReference(schema, tableReference);
        if (canonicalTable.isBlank() || !notBlank(columnName)) {
            return "";
        }
        Optional<TableMetadata> table = schema.getTables().stream()
            .filter(Objects::nonNull)
            .filter(candidate -> SchemaObjectNameUtil.referencesSameTable(canonicalTableReference(candidate), canonicalTable))
            .findFirst();
        if (table.isEmpty()) {
            return "";
        }
        return table.get().getColumns().stream()
            .filter(Objects::nonNull)
            .map(ColumnMetadata::getName)
            .filter(this::notBlank)
            .filter(name -> name.equalsIgnoreCase(columnName.trim()))
            .findFirst()
            .map(name -> canonicalTable + "." + name)
            .orElse("");
    }

    private String resolveSchemaTableReference(SchemaMetadata schema, String tableReference) {
        if (schema == null || schema.getTables() == null || !notBlank(tableReference)) {
            return "";
        }
        String normalizedReference = normalizeReferenceText(tableReference).trim();
        for (TableMetadata table : schema.getTables()) {
            if (table == null || !notBlank(table.getName())) {
                continue;
            }
            for (String phrase : tableReferencePhrases(table)) {
                if (normalizedReference.equals(phrase.trim())) {
                    return canonicalTableReference(table);
                }
            }
        }
        String canonicalReference = SchemaObjectNameUtil.canonicalTableReference(tableReference);
        for (TableMetadata table : schema.getTables()) {
            if (table != null && SchemaObjectNameUtil.referencesSameTable(canonicalTableReference(table), canonicalReference)) {
                return canonicalTableReference(table);
            }
        }
        return "";
    }

    private List<String> tableReferencePhrases(TableMetadata table) {
        String canonical = canonicalTableReference(table);
        String bare = table.getName();
        LinkedHashSet<String> phrases = new LinkedHashSet<>();
        if (notBlank(bare)) {
            phrases.add(normalizeReferenceText(bare));
            phrases.add(normalizeReferenceText(bare.replace('_', ' ')));
        }
        if (notBlank(canonical)) {
            phrases.add(normalizeReferenceText(canonical));
            phrases.add(normalizeReferenceText(canonical.replace('.', ' ').replace('_', ' ')));
        }
        return List.copyOf(phrases);
    }

    private String canonicalTableReference(TableMetadata table) {
        if (table == null || !notBlank(table.getName())) {
            return "";
        }
        if (table.getSchema() == null || table.getSchema().isBlank()) {
            return table.getName();
        }
        return table.getSchema() + "." + table.getName();
    }

    private String normalizeReferenceText(String text) {
        if (!notBlank(text)) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT)
            .replace('`', ' ')
            .replace('"', ' ')
            .replace('[', ' ')
            .replace(']', ' ')
            .replaceAll("[^a-z0-9_. ]+", " ")
            .replaceAll("\\s+", " ")
            .trim();
    }

    private SchemaMetadata loadSchemaQuietly(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return null;
        }
        try {
            return schemaScannerService.scanSchema(connectionId);
        } catch (SQLException e) {
            log.warn("Failed to load schema for company knowledge diagnostics on {}: {}", connectionId, e.getMessage());
            return null;
        }
    }

    private String compact(String content, int maxLength) {
        if (content == null) {
            return "";
        }
        String normalized = content.trim().replaceAll("\\s+", " ");
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, maxLength - 3) + "...";
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private record RankedKnowledge(CompanyKnowledgeEntry entry, int score) {}

    private record AnnotationLinks(List<String> linkedTables, List<String> linkedColumns) {
        private static AnnotationLinks empty() {
            return new AnnotationLinks(List.of(), List.of());
        }
    }

    private record AnnotationResolutionAnalysis(
        List<String> mentionedTables,
        List<String> mentionedColumns,
        List<String> invalidMentions
    ) {
        private static AnnotationResolutionAnalysis empty() {
            return new AnnotationResolutionAnalysis(List.of(), List.of(), List.of());
        }
    }

    private record NormalizedLinks(List<String> linkedTables, List<String> linkedColumns) {}
}
