package com.dbaagent.service.agent;

import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.service.ChatQuestionRoutingService;
import com.dbaagent.service.SchemaQuestionUtil;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

@Service
public class MetadataRequestScopeResolver {

    public MetadataRequestScope resolve(
        String question,
        SchemaMetadata schema,
        ChatQuestionRoutingService.QuestionRoute route,
        PromptIntent promptIntent
    ) {
        if (question == null || question.isBlank()) {
            return MetadataRequestScope.empty(question);
        }

        List<String> exactTables = SchemaQuestionUtil.resolveExactSchemaTables(schema, question);
        String lowerQuestion = question.toLowerCase(Locale.ROOT);
        MetadataRequestScope.FactType factType = detectFactType(lowerQuestion, route, promptIntent);
        boolean exactFact = isStrictFactQuestion(lowerQuestion);
        MetadataRequestScope.AnswerStyle answerStyle = detectAnswerStyle(lowerQuestion, factType);
        boolean pairScoped = exactTables.size() >= 2
            && (factType == MetadataRequestScope.FactType.RELATIONSHIPS
            || factType == MetadataRequestScope.FactType.JOIN_COLUMNS);

        return new MetadataRequestScope(
            exactFact ? MetadataRequestScope.Mode.STRICT_FACT : MetadataRequestScope.Mode.ANALYTIC_METADATA,
            factType,
            pairScoped ? exactTables.subList(0, 2) : exactTables,
            List.of(),
            pairScoped,
            !exactTables.isEmpty(),
            question,
            answerStyle
        );
    }

    private MetadataRequestScope.FactType detectFactType(
        String lowerQuestion,
        ChatQuestionRoutingService.QuestionRoute route,
        PromptIntent promptIntent
    ) {
        if (isPerformanceColumnImpactQuestion(lowerQuestion, promptIntent)) {
            return MetadataRequestScope.FactType.PERFORMANCE_COLUMN_IMPACT;
        }
        if (SchemaQuestionUtil.looksLikeExactTableColumnQuestion(lowerQuestion)) {
            return MetadataRequestScope.FactType.TABLE_COLUMNS;
        }
        if (SchemaQuestionUtil.looksLikeExactTableRowCountQuestion(lowerQuestion)) {
            return MetadataRequestScope.FactType.TABLE_ROW_COUNT;
        }
        if (SchemaQuestionUtil.looksLikeExactTableIndexQuestion(lowerQuestion)) {
            return MetadataRequestScope.FactType.TABLE_INDEXES;
        }
        if (SchemaQuestionUtil.looksLikeExactTableKeyColumnQuestion(lowerQuestion)) {
            return MetadataRequestScope.FactType.TABLE_KEY_COLUMNS;
        }
        if (lowerQuestion.contains("join column")
            || lowerQuestion.contains("join columns")
            || lowerQuestion.contains("columns are joined")
            || lowerQuestion.contains("joined commonly")
            || lowerQuestion.contains("commonly joined")) {
            return MetadataRequestScope.FactType.JOIN_COLUMNS;
        }
        if (route != null && route.brainTopic() == ChatQuestionRoutingService.BrainTopic.RELATIONSHIPS) {
            return MetadataRequestScope.FactType.RELATIONSHIPS;
        }
        if (route != null && route.brainTopic() == ChatQuestionRoutingService.BrainTopic.CLASSIFICATION) {
            return MetadataRequestScope.FactType.CLASSIFICATION;
        }
        if (promptIntent != null && promptIntent.isRelationshipFocused()) {
            return MetadataRequestScope.FactType.RELATIONSHIPS;
        }
        return MetadataRequestScope.FactType.GENERAL;
    }

    private boolean isPerformanceColumnImpactQuestion(String lowerQuestion, PromptIntent promptIntent) {
        if (promptIntent == null || promptIntent.domain() != PromptIntent.Domain.PERFORMANCE) {
            return false;
        }
        boolean columnSignal = promptIntent.subjectTypes().contains(PromptIntent.SubjectType.COLUMN)
            || lowerQuestion.matches(".*\\b(columns?|fields?)\\b.*");
        boolean performanceSignal = lowerQuestion.matches(".*\\b(performance|slow|slowness|latency|query|queries|bottleneck|impact|impacting|impactful|important|critical|hot|hottest|usage|used|pressure)\\b.*");
        return columnSignal && performanceSignal;
    }

    private boolean isStrictFactQuestion(String lowerQuestion) {
        return SchemaQuestionUtil.looksLikeExactTableColumnQuestion(lowerQuestion)
            || SchemaQuestionUtil.looksLikeExactTableRowCountQuestion(lowerQuestion)
            || SchemaQuestionUtil.looksLikeExactTableIndexQuestion(lowerQuestion)
            || SchemaQuestionUtil.looksLikeExactTableKeyColumnQuestion(lowerQuestion);
    }

    private MetadataRequestScope.AnswerStyle detectAnswerStyle(String lowerQuestion, MetadataRequestScope.FactType factType) {
        boolean exploratory = lowerQuestion.startsWith("how ")
            || lowerQuestion.startsWith("why ")
            || lowerQuestion.contains("how are ")
            || lowerQuestion.contains("why are ")
            || lowerQuestion.contains("why is ")
            || lowerQuestion.contains("what is the relationship")
            || lowerQuestion.contains("how should")
            || lowerQuestion.contains("how do i join")
            || lowerQuestion.contains("how to join")
            || lowerQuestion.contains("what does this mean");
        if (exploratory && (factType == MetadataRequestScope.FactType.RELATIONSHIPS
            || factType == MetadataRequestScope.FactType.JOIN_COLUMNS)) {
            return MetadataRequestScope.AnswerStyle.EXPLANATORY;
        }
        return MetadataRequestScope.AnswerStyle.FACTUAL;
    }
}
