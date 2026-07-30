package com.dbaagent.service.agent;

import com.dbaagent.service.RetrievedContextResult;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@Slf4j
public class SchemaRelationshipReasoningService {

    private static final String SYSTEM_PROMPT = """
        You are a senior DBA and data modeler helping explain how two schema tables relate.

        Use only the supplied vault-backed schema documentation, relationship notes, company knowledge, and semantic context.
        Do not invent foreign keys or join paths that are not supported by the supplied context.
        Do not talk about internal tools, metadata catalogs, or implementation details.

        Your job:
        - infer the practical relationship between the requested tables from the supplied context
        - explain how the tables fit together in plain DBA/developer language
        - call out likely join keys or the business meaning of the relationship only if the supplied context supports it
        - if the context is weak, say so clearly and set hasUsefulContext=false

        Return valid JSON only with this shape:
        {
          "hasUsefulContext": true,
          "summary": "string",
          "primaryFindings": ["string"],
          "supportingEvidence": ["string"],
          "matchedTables": ["TABLE_A", "TABLE_B"],
          "confidence": 0.0,
          "sourceKind": "company_knowledge|semantic_model|mixed",
          "reason": "string"
        }
        """;

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public SchemaRelationshipReasoningService(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.objectMapper = objectMapper;
    }

    public Optional<RelationshipReasoningResult> reason(
        String question,
        MetadataRequestScope requestScope,
        RetrievedContextResult retrievedContext
    ) {
        if (requestScope == null || !requestScope.pairScoped() || requestScope.requestedTables().size() < 2 || retrievedContext == null) {
            return Optional.empty();
        }

        String companyKnowledgeContext = safeCap(retrievedContext.companyKnowledgeContext(), 5000);
        String trainingContext = safeCap(retrievedContext.trainingContext(), 5000);
        if (companyKnowledgeContext.isBlank() && trainingContext.isBlank()) {
            return Optional.empty();
        }

        try {
            List<Message> messages = List.of(
                new SystemMessage(SYSTEM_PROMPT),
                new UserMessage(buildPrompt(question, requestScope, retrievedContext, companyKnowledgeContext, trainingContext))
            );
            String content = chatClient.prompt().messages(messages).call().content();
            JsonNode root = parseJson(content);
            if (root == null || !root.isObject()) {
                return Optional.empty();
            }
            boolean hasUsefulContext = root.path("hasUsefulContext").asBoolean(false);
            if (!hasUsefulContext) {
                return Optional.empty();
            }
            String summary = text(root, "summary");
            if (summary.isBlank()) {
                return Optional.empty();
            }
            LinkedHashSet<String> matchedTables = new LinkedHashSet<>(requestScope.requestedTables());
            JsonNode matchedTablesNode = root.path("matchedTables");
            if (matchedTablesNode.isArray()) {
                matchedTablesNode.forEach(node -> {
                    String value = node == null ? "" : node.asText("");
                    if (!value.isBlank()) {
                        matchedTables.add(value.trim());
                    }
                });
            }

            return Optional.of(new RelationshipReasoningResult(
                summary,
                stringList(root.path("primaryFindings")),
                stringList(root.path("supportingEvidence")),
                Set.copyOf(matchedTables),
                bounded(root.path("confidence").asDouble(0.78d)),
                text(root, "sourceKind").isBlank() ? inferSourceKind(companyKnowledgeContext, trainingContext) : text(root, "sourceKind"),
                text(root, "reason")
            ));
        } catch (Exception e) {
            log.warn("Schema relationship reasoning from retrieved context failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    private String buildPrompt(
        String question,
        MetadataRequestScope requestScope,
        RetrievedContextResult retrievedContext,
        String companyKnowledgeContext,
        String trainingContext
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("question", question);
        payload.put("requestedTables", requestScope.requestedTables());
        payload.put("requestedAnswerStyle", requestScope.answerStyle().name());
        payload.put("retrievalIntent", retrievedContext.retrievalIntent() == null ? "GENERAL" : retrievedContext.retrievalIntent().name());
        payload.put("ragTableNames", retrievedContext.ragTableNames());
        payload.put("typeCounts", retrievedContext.typeCounts());
        payload.put("companyKnowledgeContext", companyKnowledgeContext);
        payload.put("trainingContext", trainingContext);
        return toJson(payload);
    }

    private JsonNode parseJson(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return null;
        }
        String cleaned = rawContent.trim()
            .replaceAll("^```json\\s*", "")
            .replaceAll("^```\\s*", "")
            .replaceAll("\\s*```$", "")
            .trim();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end >= start) {
            cleaned = cleaned.substring(start, end + 1);
        }
        try {
            return objectMapper.readTree(cleaned);
        } catch (Exception e) {
            log.debug("Failed to parse relationship reasoning JSON: {}", cleaned, e);
            return null;
        }
    }

    private String text(JsonNode root, String field) {
        if (root == null || field == null || field.isBlank()) {
            return "";
        }
        JsonNode node = root.path(field);
        return node.isMissingNode() || node.isNull() ? "" : node.asText("").trim();
    }

    private List<String> stringList(JsonNode node) {
        if (node == null || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            if (item != null && !item.isNull()) {
                String value = item.asText("").trim();
                if (!value.isBlank()) {
                    values.add(value);
                }
            }
        });
        return values;
    }

    private double bounded(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return 0.78d;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private String inferSourceKind(String companyKnowledgeContext, String trainingContext) {
        if (!companyKnowledgeContext.isBlank() && !trainingContext.isBlank()) {
            return "mixed";
        }
        return !companyKnowledgeContext.isBlank() ? "company_knowledge" : "semantic_model";
    }

    private String safeCap(String value, int maxLength) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }

    private String toJson(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            return "{}";
        }
    }

    public record RelationshipReasoningResult(
        String summary,
        List<String> primaryFindings,
        List<String> supportingEvidence,
        Set<String> matchedTables,
        double confidence,
        String sourceKind,
        String reason
    ) {
        public RelationshipReasoningResult {
            primaryFindings = primaryFindings == null ? List.of() : List.copyOf(primaryFindings);
            supportingEvidence = supportingEvidence == null ? List.of() : List.copyOf(supportingEvidence);
            matchedTables = matchedTables == null ? Set.of() : Set.copyOf(matchedTables);
            sourceKind = sourceKind == null ? "mixed" : sourceKind.toLowerCase(Locale.ROOT);
            reason = reason == null ? "" : reason;
        }
    }
}
