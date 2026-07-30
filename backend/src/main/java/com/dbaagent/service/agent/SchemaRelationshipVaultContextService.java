package com.dbaagent.service.agent;

import com.dbaagent.model.SchemaDocumentation;
import com.dbaagent.model.SemanticJoinModel;
import com.dbaagent.model.SemanticTableModel;
import com.dbaagent.model.TrainingDataEmbedding;
import com.dbaagent.repository.SchemaDocumentationRepository;
import com.dbaagent.service.RetrievalIntent;
import com.dbaagent.service.RetrievedContextResult;
import com.dbaagent.service.SemanticModelService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class SchemaRelationshipVaultContextService {

    private final SemanticModelService semanticModelService;
    private final SchemaDocumentationRepository schemaDocumentationRepository;

    public SchemaRelationshipVaultContextService(
        SemanticModelService semanticModelService,
        SchemaDocumentationRepository schemaDocumentationRepository
    ) {
        this.semanticModelService = semanticModelService;
        this.schemaDocumentationRepository = schemaDocumentationRepository;
    }

    public Optional<RetrievedContextResult> buildExactContext(String connectionId, MetadataRequestScope requestScope) {
        return loadExactContext(connectionId, requestScope).map(ExactRelationshipVaultContext::retrievedContext);
    }

    public Optional<ExactRelationshipVaultContext> loadExactContext(String connectionId, MetadataRequestScope requestScope) {
        if (connectionId == null
            || connectionId.isBlank()
            || requestScope == null
            || !requestScope.pairScoped()
            || requestScope.requestedTables().size() < 2) {
            return Optional.empty();
        }

        Set<String> normalizedRequestedTables = requestScope.requestedTables().stream()
            .filter(Objects::nonNull)
            .map(this::normalize)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (normalizedRequestedTables.size() < 2) {
            return Optional.empty();
        }

        List<SemanticTableModel> semanticTables = semanticModelService.getSemanticTables(connectionId, requestScope.requestedTables()).stream()
            .filter(table -> normalizedRequestedTables.contains(normalize(table.getTableName())))
            .sorted(Comparator.comparing(SemanticTableModel::getTableName, String.CASE_INSENSITIVE_ORDER))
            .toList();

        List<SemanticJoinModel> semanticJoins = semanticModelService.getSemanticJoins(connectionId, requestScope.requestedTables()).stream()
            .filter(join -> matchesExactPair(normalizedRequestedTables, join))
            .sorted(Comparator
                .comparing(SemanticJoinModel::getPreferred, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(SemanticJoinModel::getConfidenceScore, Comparator.nullsLast(Comparator.reverseOrder()))
                .thenComparing(SemanticJoinModel::getSourceTable, String.CASE_INSENSITIVE_ORDER))
            .toList();

        List<SchemaDocumentation> docs = schemaDocumentationRepository.findByConnectionId(connectionId).stream()
            .filter(Objects::nonNull)
            .filter(doc -> matchesDocumentationScope(normalizedRequestedTables, doc, semanticJoins))
            .sorted(Comparator
                .comparing((SchemaDocumentation doc) -> doc.getObjectType().name())
                .thenComparing(doc -> safe(doc.getParentObject()), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(doc -> safe(doc.getObjectName()), String.CASE_INSENSITIVE_ORDER))
            .toList();

        if (semanticTables.isEmpty() && semanticJoins.isEmpty() && docs.isEmpty()) {
            return Optional.empty();
        }

        String trainingContext = buildSemanticContext(semanticTables, semanticJoins);
        String companyKnowledgeContext = buildDocumentationContext(docs);
        if (trainingContext.isBlank() && companyKnowledgeContext.isBlank()) {
            return Optional.empty();
        }

        LinkedHashSet<String> ragTables = new LinkedHashSet<>();
        semanticTables.stream().map(SemanticTableModel::getTableName).filter(Objects::nonNull).forEach(ragTables::add);
        semanticJoins.stream().map(SemanticJoinModel::getSourceTable).filter(Objects::nonNull).forEach(ragTables::add);
        semanticJoins.stream().map(SemanticJoinModel::getTargetTable).filter(Objects::nonNull).forEach(ragTables::add);
        if (ragTables.isEmpty()) {
            ragTables.addAll(requestScope.requestedTables());
        }

        Map<TrainingDataEmbedding.TrainingDataType, Long> typeCounts = new LinkedHashMap<>();
        if (!docs.isEmpty()) {
            typeCounts.put(TrainingDataEmbedding.TrainingDataType.DOCUMENTATION, (long) docs.size());
        }
        if (!semanticJoins.isEmpty()) {
            typeCounts.put(TrainingDataEmbedding.TrainingDataType.RELATIONSHIP, (long) semanticJoins.size());
        }

        RetrievedContextResult retrievedContext = new RetrievedContextResult(
            trainingContext,
            companyKnowledgeContext,
            "",
            List.of(),
            ragTables,
            RetrievalIntent.GENERAL,
            0,
            semanticTables.size() + semanticJoins.size() + docs.size(),
            0,
            typeCounts,
            false,
            null
        );

        return Optional.of(new ExactRelationshipVaultContext(
            retrievedContext,
            semanticTables,
            semanticJoins,
            docs
        ));
    }

    private boolean matchesExactPair(Set<String> normalizedRequestedTables, SemanticJoinModel join) {
        if (join == null) {
            return false;
        }
        Set<String> pair = Set.of(normalize(join.getSourceTable()), normalize(join.getTargetTable()));
        return pair.containsAll(normalizedRequestedTables) && normalizedRequestedTables.containsAll(pair);
    }

    private boolean matchesDocumentationScope(
        Set<String> normalizedRequestedTables,
        SchemaDocumentation doc,
        List<SemanticJoinModel> semanticJoins
    ) {
        String objectName = normalize(doc.getObjectName());
        String parentObject = normalize(doc.getParentObject());
        if (doc.getObjectType() == SchemaDocumentation.DocumentationType.TABLE) {
            return normalizedRequestedTables.contains(objectName);
        }
        if (doc.getObjectType() != SchemaDocumentation.DocumentationType.COLUMN) {
            return normalizedRequestedTables.contains(objectName) || normalizedRequestedTables.contains(parentObject);
        }
        return normalizedRequestedTables.contains(parentObject);
    }

    private String buildSemanticContext(List<SemanticTableModel> semanticTables, List<SemanticJoinModel> semanticJoins) {
        List<String> sections = new ArrayList<>();
        if (!semanticTables.isEmpty()) {
            StringBuilder sb = new StringBuilder("Exact semantic table models:\n");
            for (SemanticTableModel table : semanticTables) {
                sb.append("- ").append(table.getTableName()).append("\n");
                if (notBlank(table.getBusinessDescription())) {
                    sb.append("  meaning: ").append(table.getBusinessDescription()).append("\n");
                }
                if (notBlank(table.getGrainDescription())) {
                    sb.append("  grain: ").append(table.getGrainDescription()).append("\n");
                }
                if (notBlank(table.getBusinessTerms())) {
                    sb.append("  aliases: ").append(table.getBusinessTerms()).append("\n");
                }
            }
            sections.add(sb.toString().trim());
        }
        if (!semanticJoins.isEmpty()) {
            StringBuilder sb = new StringBuilder("Exact semantic joins:\n");
            for (SemanticJoinModel join : semanticJoins) {
                sb.append("- ").append(safe(join.getJoinExpression()));
                List<String> attrs = new ArrayList<>();
                if (notBlank(join.getRelationshipType())) {
                    attrs.add(join.getRelationshipType());
                }
                if (notBlank(join.getEvidenceSource())) {
                    attrs.add(join.getEvidenceSource());
                }
                if (join.getConfidenceScore() != null) {
                    attrs.add("confidence=" + join.getConfidenceScore().stripTrailingZeros().toPlainString());
                }
                if (!attrs.isEmpty()) {
                    sb.append(" [").append(String.join(", ", attrs)).append("]");
                }
                sb.append("\n");
            }
            sections.add(sb.toString().trim());
        }
        return String.join("\n\n", sections);
    }

    private String buildDocumentationContext(List<SchemaDocumentation> docs) {
        if (docs.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder("Exact schema documentation:\n");
        for (SchemaDocumentation doc : docs.stream().limit(12).toList()) {
            String label = switch (doc.getObjectType()) {
                case TABLE -> "TABLE " + doc.getObjectName();
                case COLUMN -> "COLUMN " + safe(doc.getParentObject()) + "." + doc.getObjectName();
                case BUSINESS_TERM -> "TERM " + doc.getObjectName();
            };
            sb.append("- ").append(label).append(": ").append(safe(doc.getDescription())).append("\n");
            if (notBlank(doc.getBusinessTerms())) {
                sb.append("  aliases: ").append(doc.getBusinessTerms()).append("\n");
            }
            if (notBlank(doc.getExamples())) {
                sb.append("  examples: ").append(doc.getExamples()).append("\n");
            }
        }
        return sb.toString().trim();
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String safe(String value) {
        return value == null ? "" : value.trim();
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    public record ExactRelationshipVaultContext(
        RetrievedContextResult retrievedContext,
        List<SemanticTableModel> semanticTables,
        List<SemanticJoinModel> semanticJoins,
        List<SchemaDocumentation> documentation
    ) {
        public ExactRelationshipVaultContext {
            semanticTables = semanticTables == null ? List.of() : List.copyOf(semanticTables);
            semanticJoins = semanticJoins == null ? List.of() : List.copyOf(semanticJoins);
            documentation = documentation == null ? List.of() : List.copyOf(documentation);
        }
    }
}
