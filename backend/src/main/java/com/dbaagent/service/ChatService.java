package com.dbaagent.service;

import com.dbaagent.model.ChatResponse;
import com.dbaagent.model.ChatResultSet;
import com.dbaagent.model.ChatFeedback;
import com.dbaagent.model.ColumnMetadata;
import com.dbaagent.model.IndexMetadata;
import com.dbaagent.model.QueryRequest;
import com.dbaagent.model.QueryResult;
import com.dbaagent.model.QueryExecutionOrigin;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.SchemaDocumentation;
import com.dbaagent.model.TableMetadata;
import com.dbaagent.service.brain.classification.SchemaClassificationService;
import com.dbaagent.model.QualifiedTableName;
import com.dbaagent.service.pipeline.ColumnValueContext;
import com.dbaagent.service.pipeline.PipelineContext;
import com.dbaagent.service.pipeline.PipelineProgressListener;
import com.dbaagent.service.pipeline.PipelineResult;
import com.dbaagent.service.pipeline.QueryGenerationPipeline;
import com.dbaagent.service.pipeline.ResolvedContext;
import com.dbaagent.model.TrainingDataEmbedding;
import com.dbaagent.model.SlowQueryHistory;
import com.dbaagent.model.SlowQueryAnalysis;
import com.dbaagent.model.SlowQuery;
import com.dbaagent.model.IndexRecommendationEntity;
import com.dbaagent.model.QueryOptimizationCandidateRun;
import com.dbaagent.model.KeyColumnAnalysis;
import com.dbaagent.model.InferredTableRelationship;
import com.dbaagent.model.GrowthAnomaly;
import com.dbaagent.model.TableClassification;
import com.dbaagent.model.TableStatsHistory;
import com.dbaagent.repository.TableClassificationRepository;
import com.dbaagent.repository.brain.WorkloadProfileRepository;
import com.dbaagent.repository.brain.KnobRankingRepository;
import com.dbaagent.repository.brain.ColumnStatisticsRepository;
import com.dbaagent.repository.brain.PlanPatternRepository;
import com.dbaagent.repository.TableStatsHistoryRepository;
import com.dbaagent.repository.SlowQueryHistoryRepository;
import com.dbaagent.repository.QueryPerformanceRegressionRepository;
import com.dbaagent.repository.KeyColumnAnalysisRepository;
import com.dbaagent.repository.InferredTableRelationshipRepository;
import com.dbaagent.repository.GrowthAnomalyRepository;
import com.dbaagent.repository.IndexRecommendationRepository;
import com.dbaagent.repository.ColumnValueCacheRepository;
import com.dbaagent.repository.UserRepository;
import com.dbaagent.service.agent.AgentDecision;
import com.dbaagent.service.agent.AgentExecutionResult;
import com.dbaagent.service.agent.AgentIntent;
import com.dbaagent.service.agent.AgentOrchestrator;
import com.dbaagent.service.agent.AgentObservation;
import com.dbaagent.service.agent.AgentPlan;
import com.dbaagent.service.agent.AgentPlanStep;
import com.dbaagent.service.agent.AgentPlanTask;
import com.dbaagent.service.agent.AgentProgressEvent;
import com.dbaagent.service.agent.AgentProgressListener;
import com.dbaagent.service.agent.AgentRunService;
import com.dbaagent.service.agent.AnswerContract;
import com.dbaagent.service.agent.PerformanceExecutor;
import com.dbaagent.service.agent.PromptIntent;
import com.dbaagent.service.agent.PromptIntentAnalyzer;
import com.dbaagent.service.agent.SchemaMetadataExecutor;
import com.dbaagent.service.agent.AgentToolResult;
import com.dbaagent.service.agent.AgentTaskKind;
import com.dbaagent.service.agent.AgentTaskResult;
import com.dbaagent.service.agent.MetadataRequestScope;
import com.dbaagent.service.agent.MetadataRequestScopeResolver;
import com.dbaagent.service.agent.VerifiedAnswer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dbaagent.util.QueryNormalizer;
import com.dbaagent.service.security.AccessControlService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.lang.Nullable;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@Slf4j
public class ChatService {

    // ContextType is defined in ChatContextAssembler — use fully qualified references.

    public record StreamResult(
        Flux<String> metadataStream,
        Flux<String> progressStream,
        Flux<String> resultStream,
        Flux<String> tokenStream
    ) {}

    private record PreparedConversationTurn(
        String chatId,
        ResolvedConversationContext resolvedConversationContext,
        ConversationCarryoverDecision carryoverDecision,
        List<com.dbaagent.service.agent.AgentExecutionContext.ConversationTurn> conversationHistory,
        String effectiveQuestion,
        ChatQuestionRoutingService.QuestionRoute questionRoute,
        PromptIntent promptIntent
    ) {}

    private static final String AGENTIC_FAILURE_MESSAGE =
        "DeepSQL could not complete this run because the agent runtime hit an internal execution failure before it could finish scouting the available sources. Please retry once; if it repeats, ask an admin to inspect the agent run trace.";
    private static final String DATABASE_CONNECTION_UNAVAILABLE_MESSAGE =
        "DeepSQL can't access this database connection right now. Please reach out to your admin to verify the connection credentials and connectivity.";

    private enum RetrievalIntent {
        GENERAL,
        VALUE_LOOKUP,
        BUSINESS_MEANING,
        SQL_EXAMPLE
    }

    private final SchemaScannerService schemaScannerService;
    private final QueryExecutorService queryExecutorService;
    private final TrainingService trainingService;
    private final ChatHistoryService chatHistoryService;
    private final SchemaClassificationService schemaClassificationService;
    private final FeedbackService feedbackService;
    private final BusinessRuleMemoryService businessRuleMemoryService;
    private final CredentialService credentialService;
    private final TableClassificationRepository tableClassificationRepository;
    private final SlowQueryHistoryRepository slowQueryHistoryRepository;
    private final QueryPerformanceRegressionRepository queryPerformanceRegressionRepository;
    private final KeyColumnAnalysisRepository keyColumnAnalysisRepository;
    private final InferredTableRelationshipRepository inferredTableRelationshipRepository;
    private final GrowthAnomalyRepository growthAnomalyRepository;
    private final IndexRecommendationRepository indexRecommendationRepository;
    private final TableStatsHistoryRepository tableStatsHistoryRepository;
    private final ColumnValueCacheRepository columnValueCacheRepository;
    private final WorkloadProfileRepository workloadProfileRepository;
    private final KnobRankingRepository knobRankingRepository;
    private final ColumnStatisticsRepository columnStatisticsRepository;
    private final PlanPatternRepository planPatternRepository;
    private final QueryOptimizationService queryOptimizationService;
    private final OptimizationCandidateService candidateService;
    private final ObjectMapper objectMapper;
    private final ChatClient chatClient;
    private final ChatMemory chatMemory;
    private final QuestionAnswerAdvisor questionAnswerAdvisor;
    private final ChatContextAssembler contextAssembler;
    private final SqlExecutionPipeline sqlExecutionPipeline;
    private final QueryGenerationPipeline queryGenerationPipeline;
    private final ChatQuestionRoutingService chatQuestionRoutingService;
    private final AgentOrchestrator agentOrchestrator;
    private final AgentRunService agentRunService;
    private final ChatRetrievalContextService chatRetrievalContextService;
    private final ConversationContextService conversationContextService;
    private final ChatScopeGuardService chatScopeGuardService;
    private final PromptIntentAnalyzer promptIntentAnalyzer;
    private final SchemaMetadataExecutor schemaMetadataExecutor;
    private final PerformanceExecutor performanceExecutor;
    private final MetadataRequestScopeResolver metadataRequestScopeResolver;
    private final AccessControlService accessControlService;
    private final UserRepository userRepository;
    private final UserDataAccessPolicyService userDataAccessPolicyService;

    @Value("classpath:prompts/dba-system-prompt.st")
    private Resource systemPromptResource;

    @Value("${spring.ai.rag.advisor.enabled:false}")
    private boolean ragAdvisorEnabled;

    @Value("${spring.ai.rag.advisor.comparison-mode:false}")
    private boolean ragComparisonMode;

    @Value("${app.chat.auto-learn-feedback.enabled:true}")
    private boolean autoLearnFeedbackEnabled;

    @Value("${app.pipeline.enabled:true}")
    private boolean pipelineEnabled;

    @Value("${app.pipeline.explain-validation.enabled:true}")
    private boolean explainValidationEnabled;

    @Value("${app.chat.agentic.enabled:true}")
    private boolean agenticEnabled;

    @Value("${app.chat.rag.general-top-k:20}")
    private int ragGeneralTopK;

    @Value("${app.chat.rag.intent-top-k:25}")
    private int ragIntentTopK;

    @Autowired
    public ChatService(
            SchemaScannerService schemaScannerService,
            QueryExecutorService queryExecutorService,
            TrainingService trainingService,
            ChatHistoryService chatHistoryService,
            SchemaClassificationService schemaClassificationService,
            FeedbackService feedbackService,
            BusinessRuleMemoryService businessRuleMemoryService,
            CredentialService credentialService,
            TableClassificationRepository tableClassificationRepository,
            SlowQueryHistoryRepository slowQueryHistoryRepository,
            QueryPerformanceRegressionRepository queryPerformanceRegressionRepository,
            KeyColumnAnalysisRepository keyColumnAnalysisRepository,
            InferredTableRelationshipRepository inferredTableRelationshipRepository,
            GrowthAnomalyRepository growthAnomalyRepository,
            IndexRecommendationRepository indexRecommendationRepository,
            TableStatsHistoryRepository tableStatsHistoryRepository,
            ColumnValueCacheRepository columnValueCacheRepository,
            WorkloadProfileRepository workloadProfileRepository,
            KnobRankingRepository knobRankingRepository,
            ColumnStatisticsRepository columnStatisticsRepository,
            PlanPatternRepository planPatternRepository,
            QueryOptimizationService queryOptimizationService,
            OptimizationCandidateService candidateService,
            ObjectMapper objectMapper,
            ChatClient.Builder chatClientBuilder,
            @org.springframework.lang.Nullable ChatMemory chatMemory,
            @org.springframework.lang.Nullable QuestionAnswerAdvisor questionAnswerAdvisor,
            ChatContextAssembler contextAssembler,
            SqlExecutionPipeline sqlExecutionPipeline,
            QueryGenerationPipeline queryGenerationPipeline,
            ChatQuestionRoutingService chatQuestionRoutingService,
            AgentOrchestrator agentOrchestrator,
            AgentRunService agentRunService,
            ChatRetrievalContextService chatRetrievalContextService,
            ConversationContextService conversationContextService,
            ChatScopeGuardService chatScopeGuardService,
            PromptIntentAnalyzer promptIntentAnalyzer,
            SchemaMetadataExecutor schemaMetadataExecutor,
            PerformanceExecutor performanceExecutor,
            MetadataRequestScopeResolver metadataRequestScopeResolver,
            AccessControlService accessControlService,
            UserRepository userRepository,
            UserDataAccessPolicyService userDataAccessPolicyService) {
        this.schemaScannerService = schemaScannerService;
        this.queryExecutorService = queryExecutorService;
        this.trainingService = trainingService;
        this.chatHistoryService = chatHistoryService;
        this.schemaClassificationService = schemaClassificationService;
        this.feedbackService = feedbackService;
        this.businessRuleMemoryService = businessRuleMemoryService;
        this.credentialService = credentialService;
        this.tableClassificationRepository = tableClassificationRepository;
        this.slowQueryHistoryRepository = slowQueryHistoryRepository;
        this.queryPerformanceRegressionRepository = queryPerformanceRegressionRepository;
        this.keyColumnAnalysisRepository = keyColumnAnalysisRepository;
        this.inferredTableRelationshipRepository = inferredTableRelationshipRepository;
        this.growthAnomalyRepository = growthAnomalyRepository;
        this.indexRecommendationRepository = indexRecommendationRepository;
        this.tableStatsHistoryRepository = tableStatsHistoryRepository;
        this.columnValueCacheRepository = columnValueCacheRepository;
        this.workloadProfileRepository = workloadProfileRepository;
        this.knobRankingRepository = knobRankingRepository;
        this.columnStatisticsRepository = columnStatisticsRepository;
        this.planPatternRepository = planPatternRepository;
        this.queryOptimizationService = queryOptimizationService;
        this.candidateService = candidateService;
        this.objectMapper = objectMapper;
        this.chatMemory = chatMemory;
        this.questionAnswerAdvisor = questionAnswerAdvisor;
        this.contextAssembler = contextAssembler;
        this.sqlExecutionPipeline = sqlExecutionPipeline;
        this.queryGenerationPipeline = queryGenerationPipeline;
        this.chatQuestionRoutingService = chatQuestionRoutingService;
        this.agentOrchestrator = agentOrchestrator;
        this.agentRunService = agentRunService;
        this.chatRetrievalContextService = chatRetrievalContextService;
        this.conversationContextService = conversationContextService;
        this.chatScopeGuardService = chatScopeGuardService;
        this.promptIntentAnalyzer = promptIntentAnalyzer;
        this.schemaMetadataExecutor = schemaMetadataExecutor;
        this.performanceExecutor = performanceExecutor;
        this.metadataRequestScopeResolver = metadataRequestScopeResolver;
        this.accessControlService = accessControlService;
        this.userRepository = userRepository;
        this.userDataAccessPolicyService = userDataAccessPolicyService;

        // Build ChatClient with optional memory advisor for automatic conversation history
        if (chatMemory != null) {
            this.chatClient = chatClientBuilder
                    .defaultAdvisors(MessageChatMemoryAdvisor.builder(chatMemory).build())
                    .build();
            log.info("ChatService initialized with Spring AI ChatClient and Chat Memory (JDBC/PostgreSQL)");
        } else {
            this.chatClient = chatClientBuilder.build();
            log.info("ChatService initialized with Spring AI ChatClient (no Chat Memory - JDBC repository unavailable)");
        }

        // Log QuestionAnswerAdvisor status
        if (questionAnswerAdvisor != null) {
            log.info("QuestionAnswerAdvisor available for RAG (enable with spring.ai.rag.advisor.enabled=true)");
        } else {
            log.info("QuestionAnswerAdvisor not available (VectorStore not configured)");
        }
    }

    // Spring AI Chat Memory conversation ID key
    private static final String CHAT_MEMORY_CONVERSATION_ID_KEY = "chat_memory_conversation_id";
    private static final Pattern SQL_CODE_BLOCK = Pattern.compile("```\\s*sql\\s*([\\s\\S]*?)```", Pattern.CASE_INSENSITIVE);
    private static final Pattern GENERIC_CODE_BLOCK = Pattern.compile("```\\s*([\\s\\S]*?)```");
    private static final Pattern INLINE_CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern USER_REQUEST_PATTERN = Pattern.compile(
        "(?:USER REQUEST|Manager's request):\\s*(.*?)(?:\\n\\n(?:Please|CRITICAL|Return|Do not)|$)",
        Pattern.CASE_INSENSITIVE | Pattern.DOTALL
    );
    private static final Pattern BUSINESS_RULE_SIGNAL_PATTERN = Pattern.compile(
        "(?i)(should\\s+join|must\\s+join|join\\s+on|instead\\s+of|correct\\s+join|for\\s+.*\\s+use\\s+.*|\\btype\\s*=\\s*\\w+|\\bmode\\s*=\\s*\\w+|business\\s+rule)"
    );
    private static final Pattern FOLLOW_UP_CONTEXT_PATTERN = Pattern.compile(
        "(?i)\\b(it|its|that|those|them|these|same|same one|same table|same metric|same thing|do it|do this|use that|use those|which one|what about|how about|and for|what if|that table|that column|that query|those columns|those tables|those keys|one of those|one of them)\\b"
    );
    private static final Pattern CLARIFICATION_FOLLOW_UP_PATTERN = Pattern.compile(
        "(?i)^(?:yes|no|use|using|with|without|include|exclude|instead|rather|switch|change|filter|group|sort|order|based on)\\b"
            + "|\\b(?:you asked|clarif(?:y|ied|ication))\\b"
            + "|\\b[a-z_][a-z0-9_]*\\.[a-z_][a-z0-9_]*\\b"
    );
    private static final int AGENT_HISTORY_MESSAGES = 6;

    /** Cap string to max length (simple substring, no word-boundary). */
    private static String cap(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }

    /**
     * Build system prompt using Spring AI PromptTemplate.
     * Uses the template from classpath:prompts/dba-system-prompt.st
     */
    private String buildSystemPromptFromTemplate(
            String dbType,
            String dbSpecificRules,
            String schemaContext,
            String classificationContext,
            String performanceContext,
            String brainContext,
            String feedbackContext,
            String companyKnowledgeContext,
            String trainingContext,
            String columnValueContext,
            String resolutionHints) {
        try {
            PromptTemplate template = new PromptTemplate(systemPromptResource);
            Map<String, Object> params = new HashMap<>();
            params.put("dbType", dbType != null ? dbType.toUpperCase() : "SQL");
            params.put("dbSpecificRules", dbSpecificRules != null ? dbSpecificRules : "");
            params.put("schemaContext", schemaContext != null ? schemaContext : "");
            params.put("classificationContext", classificationContext != null ? classificationContext : "");
            params.put("performanceContext", performanceContext != null ? performanceContext : "");
            params.put("brainContext", brainContext != null ? brainContext : "");
            params.put("feedbackContext", feedbackContext != null ? feedbackContext : "");
            params.put("companyKnowledgeContext", companyKnowledgeContext != null ? companyKnowledgeContext : "");
            params.put("trainingContext", trainingContext != null ? trainingContext : "");
            params.put("columnValueContext", columnValueContext != null ? columnValueContext : "");
            params.put("resolutionHints", resolutionHints != null ? resolutionHints : "");

            return template.render(params);
        } catch (Exception e) {
            log.warn("Failed to load prompt template, using fallback: {}", e.getMessage());
            // Fallback to inline prompt if template fails
            return buildFallbackSystemPrompt(dbType, dbSpecificRules, schemaContext,
                    classificationContext, performanceContext, brainContext, feedbackContext, companyKnowledgeContext, trainingContext);
        }
    }

    /**
     * Fallback system prompt builder when template loading fails.
     */
    private String buildFallbackSystemPrompt(
            String dbType,
            String dbSpecificRules,
            String schemaContext,
            String classificationContext,
            String performanceContext,
            String brainContext,
            String feedbackContext,
            String companyKnowledgeContext,
            String trainingContext) {
        return "You are an expert Database Administrator (DBA) assistant specialized in " +
            (dbType != null ? dbType.toUpperCase() : "SQL") + ". " +
            "Help the user by answering questions about the database structure, writing SQL queries, or analyzing performance. " +
            "If providing SQL, use markdown code blocks (```sql ... ```).\n\n" +
            (dbSpecificRules != null ? dbSpecificRules + "\n\n" : "") +
            "Schema Context:\n" + (schemaContext != null ? schemaContext : "") +
            (classificationContext != null ? classificationContext : "") +
            (performanceContext != null ? performanceContext : "") +
            (brainContext != null ? brainContext : "") +
            (feedbackContext != null ? feedbackContext : "") +
            (companyKnowledgeContext != null ? companyKnowledgeContext : "") +
            (trainingContext != null ? trainingContext : "");
    }

    /**
     * Check if this is a simple metadata question that can be answered directly
     * from cached schema without LLM calls.
     * Returns the direct answer if applicable, null otherwise.
     */
    private String retiredDirectSchemaAnswer(String message, SchemaMetadata schema) {
        if (message == null || schema == null) {
            return null;
        }

        String actualQuestion = extractActualUserQuestion(message);
        String lowerMessage = actualQuestion.toLowerCase().trim();

        TableMetadata exactSchemaTable = SchemaQuestionUtil.resolveExactSchemaTable(schema, actualQuestion);
        if (exactSchemaTable != null) {
            if (SchemaQuestionUtil.looksLikeExactTableColumnCountQuestion(lowerMessage)) {
                return formatExactTableColumnCountAnswer(exactSchemaTable);
            }
            if (SchemaQuestionUtil.looksLikeExactTableColumnListQuestion(lowerMessage)) {
                return formatExactTableColumnListAnswer(exactSchemaTable);
            }
            if (SchemaQuestionUtil.looksLikeExactTableRowCountQuestion(lowerMessage)) {
                if (exactSchemaTable.getRowCount() == null) {
                    return null;
                }
                return formatExactTableRowCountAnswer(exactSchemaTable);
            }
            if (SchemaQuestionUtil.looksLikeExactTableIndexQuestion(lowerMessage)) {
                if (exactSchemaTable.getIndexes() == null || exactSchemaTable.getIndexes().isEmpty()) {
                    return null;
                }
                return formatExactTableIndexAnswer(exactSchemaTable, lowerMessage);
            }
        }

        // Bail out if the question has qualifiers that need SQL (scoped, temporal, conditional)
        // These need LLM + SQL to answer correctly, not a simple count from metadata
        if (hasScopedOrTemporalQualifiers(lowerMessage)) {
            log.debug("Fast path: Skipping due to scoped/temporal qualifiers in '{}'", lowerMessage);
            return null;
        }

        // "How many tables do I have?" or similar (simple, unscoped questions only)
        if (lowerMessage.matches(".*(how many|count|number of).*(tables?).*") &&
            !lowerMessage.contains("rows") && !lowerMessage.contains("record")) {
            long tableCount = resolveTableCount(schema);
            return String.format("You have **%d tables** in the `%s` database.",
                tableCount, schemaDisplayName(schema));
        }

        // "How many views do I have?"
        if (lowerMessage.matches(".*(how many|count|number of).*(views?).*")) {
            long viewCount = resolveViewCount(schema);
            return String.format("You have **%d views** in the `%s` database.",
                viewCount, schemaDisplayName(schema));
        }

        // "What's the database size?" or "How big is the database?"
        if (lowerMessage.matches(".*(database|total|overall).*(size|big|large).*") ||
            lowerMessage.matches(".*(how (big|large)|size of).*(database).*")) {
            long sizeBytes = schema.getTotalSizeBytes() != null ? schema.getTotalSizeBytes() : 0;
            String formattedSize = contextAssembler.formatBytes(sizeBytes);
            return String.format("The total database size is **%s** (%d bytes).",
                formattedSize, sizeBytes);
        }

        // "List all tables" or "Show me the tables" - simple list
        if (lowerMessage.matches("^(list|show|what are).*tables?$") ||
            lowerMessage.equals("tables") || lowerMessage.equals("show tables")) {
            if (schema.getTables() == null || schema.getTables().isEmpty()) {
                return "No tables found in the database.";
            }
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("**%d tables** in `%s`:\n\n",
                resolveTableCount(schema), schemaDisplayName(schema)));
            schema.getTables().stream()
                .filter(this::isPhysicalTable)
                .limit(50) // Limit for readability
                .forEach(t -> sb.append(String.format("- `%s` (%s rows)\n",
                    t.getName(), t.getRowCount() != null ? t.getRowCount().toString() : "?")));
            long tableCount = resolveTableCount(schema);
            if (tableCount > 50) {
                sb.append(String.format("\n... and %d more tables", tableCount - 50));
            }
            return sb.toString();
        }

        // "What are my largest tables?" or "Biggest tables" or "Tables by size"
        if (lowerMessage.matches(".*(largest|biggest|heaviest|top).*tables?.*") ||
            lowerMessage.matches(".*tables?.*(by size|sorted by size|largest|biggest).*")) {
            if (schema.getTables() == null || schema.getTables().isEmpty()) {
                return "No tables found in the database.";
            }
            // Determine sort order: by size if "size" mentioned, otherwise by row count
            boolean sortBySize = lowerMessage.contains("size") || lowerMessage.contains("bytes") ||
                                 lowerMessage.contains("storage") || lowerMessage.contains("disk");

            StringBuilder sb = new StringBuilder();
            if (sortBySize) {
                sb.append("### Largest Tables by Size\n\n");
                sb.append("| Table | Size | Rows |\n");
                sb.append("|-------|------|------|\n");
                schema.getTables().stream()
                    .filter(this::isPhysicalTable)
                    .filter(t -> t.getSizeBytes() != null)
                    .sorted((a, b) -> Long.compare(
                        b.getSizeBytes() != null ? b.getSizeBytes() : 0,
                        a.getSizeBytes() != null ? a.getSizeBytes() : 0))
                    .limit(15)
                    .forEach(t -> sb.append(String.format("| `%s` | %s | %s |\n",
                        t.getName(),
                        contextAssembler.formatBytes(t.getSizeBytes()),
                        t.getRowCount() != null ? contextAssembler.formatRowCount(t.getRowCount()) : "?")));
            } else {
                sb.append("### Largest Tables by Row Count\n\n");
                sb.append("| Table | Rows | Size |\n");
                sb.append("|-------|------|------|\n");
                schema.getTables().stream()
                    .filter(this::isPhysicalTable)
                    .filter(t -> t.getRowCount() != null)
                    .sorted((a, b) -> Long.compare(
                        b.getRowCount() != null ? b.getRowCount() : 0,
                        a.getRowCount() != null ? a.getRowCount() : 0))
                    .limit(15)
                    .forEach(t -> sb.append(String.format("| `%s` | %s | %s |\n",
                        t.getName(),
                        contextAssembler.formatRowCount(t.getRowCount()),
                        t.getSizeBytes() != null ? contextAssembler.formatBytes(t.getSizeBytes()) : "?")));
            }
            return sb.toString();
        }

        // "How many indexes?" or "Index count"
        if (lowerMessage.matches(".*(how many|count|number of).*(indexes?|indices).*")) {
            long indexCount = schema.getTables() != null ?
                schema.getTables().stream()
                    .filter(t -> t.getIndexes() != null)
                    .mapToLong(t -> t.getIndexes().size())
                    .sum() : 0;
            return String.format("You have **%d indexes** across all tables in the `%s` database.",
                indexCount, schema.getDatabaseName());
        }

        // "What database type?" or "Database info" (NOT version - that needs SQL)
        // Version questions should fall back to LLM since we don't have version in schema metadata
        if ((lowerMessage.matches(".*(what|which).*(database|db).*(type|engine).*") ||
             lowerMessage.matches(".*(database|db).*(info|information|details).*")) &&
            !lowerMessage.contains("version")) {
            StringBuilder sb = new StringBuilder();
            sb.append("### Database Information\n\n");
            sb.append(String.format("- **Type:** %s\n", schema.getDbType() != null ? schema.getDbType().toUpperCase() : "Unknown"));
            sb.append(String.format("- **Database Name:** `%s`\n", schemaDisplayName(schema)));
            sb.append(String.format("- **Tables:** %d\n", resolveTableCount(schema)));
            sb.append(String.format("- **Views:** %d\n", resolveViewCount(schema)));
            sb.append(String.format("- **Total Size:** %s\n",
                schema.getTotalSizeBytes() != null ? contextAssembler.formatBytes(schema.getTotalSizeBytes()) : "Unknown"));
            return sb.toString();
        }

        // "Show schema summary" or "Database overview"
        if (lowerMessage.matches(".*(schema|database).*(summary|overview|stats|statistics).*") ||
            lowerMessage.equals("overview") || lowerMessage.equals("summary")) {
            StringBuilder sb = new StringBuilder();
            sb.append(String.format("### Database Overview: `%s`\n\n", schemaDisplayName(schema)));
            sb.append(String.format("| Metric | Value |\n"));
            sb.append("|--------|-------|\n");
            sb.append(String.format("| Database Type | %s |\n", schema.getDbType() != null ? schema.getDbType().toUpperCase() : "?"));
            sb.append(String.format("| Tables | %d |\n", resolveTableCount(schema)));
            sb.append(String.format("| Views | %d |\n", resolveViewCount(schema)));
            sb.append(String.format("| Total Size | %s |\n",
                schema.getTotalSizeBytes() != null ? contextAssembler.formatBytes(schema.getTotalSizeBytes()) : "?"));

            // Add top 5 largest tables
            if (schema.getTables() != null && !schema.getTables().isEmpty()) {
                sb.append("\n**Top 5 Largest Tables:**\n");
                schema.getTables().stream()
                    .filter(t -> isPhysicalTable(t) && t.getRowCount() != null)
                    .sorted((a, b) -> Long.compare(b.getRowCount(), a.getRowCount()))
                    .limit(5)
                    .forEach(t -> sb.append(String.format("- `%s`: %s rows\n",
                        t.getName(), contextAssembler.formatRowCount(t.getRowCount()))));
            }
            return sb.toString();
        }

        return null; // Not a direct metadata answer
    }

    private long resolveTableCount(SchemaMetadata schema) {
        if (schema.getTotalTables() != null) {
            return schema.getTotalTables();
        }
        if (schema.getTables() == null) {
            return 0;
        }
        return schema.getTables().stream()
            .filter(this::isPhysicalTable)
            .count();
    }

    private long resolveViewCount(SchemaMetadata schema) {
        if (schema.getTotalViews() != null) {
            return schema.getTotalViews();
        }
        if (schema.getTables() == null) {
            return 0;
        }
        return schema.getTables().stream()
            .filter(this::isView)
            .count();
    }

    private boolean isPhysicalTable(com.dbaagent.model.TableMetadata table) {
        return table != null && !isView(table);
    }

    private boolean isView(com.dbaagent.model.TableMetadata table) {
        return table != null && "view".equalsIgnoreCase(table.getType());
    }

    private String schemaDisplayName(SchemaMetadata schema) {
        return schema.getDatabaseName() != null && !schema.getDatabaseName().isBlank()
            ? schema.getDatabaseName()
            : "database";
    }


    /**
     * Try to answer slow query and performance questions directly from ingested logs.
     * Handles: slowest query, top N queries, performance health, query stats.
     * Returns a formatted answer if slow query data is available, null otherwise.
     */
    private String retiredDirectSlowQueryAnswer(String message, String connectionId) {
        if (message == null || connectionId == null) {
            return null;
        }

        String actualQuestion = extractActualUserQuestion(message);

        String lowerMessage = actualQuestion.toLowerCase().trim();

        // Detect question type
        boolean isSlowQueryQuestion = lowerMessage.matches(".*(slowest|slow|worst|heaviest|most expensive)\\s+(query|queries).*") ||
            lowerMessage.matches(".*slow\\s+query.*");
        boolean isTopNQuestion = lowerMessage.matches(".*(top|show|list)\\s+\\d*\\s*(slow|worst|expensive).*") ||
            lowerMessage.matches(".*\\d+\\s+(slow|worst|expensive)\\s+(query|queries).*");
        boolean isHealthQuestion = lowerMessage.matches(".*(performance|query|database)\\s+(health|status|summary).*") ||
            lowerMessage.matches(".*(how.*perform|health\\s+check|health\\s+status).*");
        boolean isStatsQuestion = lowerMessage.matches(".*(slow\\s+query|performance)\\s+(stats|statistics|metrics|numbers).*");

        if (!isSlowQueryQuestion && !isTopNQuestion && !isHealthQuestion && !isStatsQuestion) {
            return null;
        }

        String questionType = isHealthQuestion ? "health" : isStatsQuestion ? "stats" : isTopNQuestion ? "topN" : "slowest";
        log.info("Fast path: Detected {} question, checking ingested logs for connection {}", questionType, connectionId);

        try {
            // Get only the latest slow query history (optimized: single record fetch)
            Optional<SlowQueryHistory> latestOpt = slowQueryHistoryRepository
                .findFirstByConnectionIdOrderByCreatedAtDesc(connectionId);

            if (latestOpt.isEmpty()) {
                log.debug("No slow query history found for connection {}", connectionId);
                return null; // Fall back to LLM
            }

            SlowQueryHistory latest = latestOpt.get();
            if (latest.getAnalysisData() == null || latest.getAnalysisData().isEmpty()) {
                return null; // Fall back to LLM
            }

            // Parse the analysis data to get actual slow queries
            SlowQueryAnalysis analysis = objectMapper.readValue(
                latest.getAnalysisData(), SlowQueryAnalysis.class);

            if (analysis.getTopSlowQueries() == null || analysis.getTopSlowQueries().isEmpty()) {
                return "No slow queries have been recorded in the ingested logs. Try ingesting slow query logs first via **Performance > Slow Query Analysis > Ingest Logs**.";
            }

            StringBuilder sb = new StringBuilder();

            // === HEALTH/STATUS QUESTION ===
            if (isHealthQuestion || isStatsQuestion) {
                sb.append("### Query Performance Health\n\n");

                // Overall health status
                String health = latest.getOverallHealth() != null ? latest.getOverallHealth() : "UNKNOWN";
                sb.append(String.format("**Overall Health:** %s\n\n", health));

                // Stats table
                sb.append("| Metric | Value |\n");
                sb.append("|--------|-------|\n");
                sb.append(String.format("| Total Slow Queries | %,d |\n",
                    latest.getTotalSlowQueries() != null ? latest.getTotalSlowQueries() : 0));
                sb.append(String.format("| Critical Severity | %d |\n",
                    latest.getCriticalCount() != null ? latest.getCriticalCount() : 0));
                sb.append(String.format("| High Severity | %d |\n",
                    latest.getHighCount() != null ? latest.getHighCount() : 0));
                if (latest.getTotalDatabaseTimeMs() != null && latest.getTotalDatabaseTimeMs() > 0) {
                    double totalSeconds = latest.getTotalDatabaseTimeMs() / 1000.0;
                    sb.append(String.format("| Total DB Time | %.1f sec |\n", totalSeconds));
                }
                sb.append(String.format("| Last Analyzed | %s |\n",
                    latest.getCreatedAt() != null ? latest.getCreatedAt().toString() : "?"));

                // Add severity breakdown if available
                long criticalPct = latest.getTotalSlowQueries() != null && latest.getTotalSlowQueries() > 0 ?
                    (latest.getCriticalCount() != null ? latest.getCriticalCount() * 100 / latest.getTotalSlowQueries() : 0) : 0;
                if (criticalPct > 0) {
                    sb.append(String.format("\n**Warning:** %d%% of slow queries are CRITICAL severity.\n", criticalPct));
                }

                log.info("Fast path: Returning performance health status");
                return sb.toString();
            }

            // === TOP N QUERIES QUESTION ===
            if (isTopNQuestion) {
                // Extract N from message (default to 10)
                int topN = 10;
                java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\b(\\d+)\\b");
                java.util.regex.Matcher matcher = pattern.matcher(lowerMessage);
                if (matcher.find()) {
                    topN = Math.min(Integer.parseInt(matcher.group(1)), 20); // Cap at 20
                }

                sb.append(String.format("### Top %d Slow Queries\n\n", topN));
                sb.append("| # | Severity | Exec Time | Calls | Query (truncated) |\n");
                sb.append("|---|----------|-----------|-------|-------------------|\n");

                var sortedQueries = analysis.getTopSlowQueries().stream()
                    .sorted((a, b) -> Double.compare(contextAssembler.getBestExecutionTime(b), contextAssembler.getBestExecutionTime(a)))
                    .limit(topN)
                    .toList();

                int rank = 1;
                for (SlowQuery sq : sortedQueries) {
                    double execTime = contextAssembler.getBestExecutionTime(sq);
                    String execTimeStr = execTime >= 1000 ?
                        String.format("%.1fs", execTime / 1000.0) :
                        String.format("%.0fms", execTime);
                    String severity = sq.getSeverity() != null ? sq.getSeverity().toString() : "LOW";
                    String queryPreview = sq.getNormalizedQuery() != null ? sq.getNormalizedQuery() : sq.getQueryText();
                    if (queryPreview != null && queryPreview.length() > 60) {
                        queryPreview = queryPreview.substring(0, 60) + "...";
                    }
                    // Escape pipes in query text for markdown table
                    if (queryPreview != null) {
                        queryPreview = queryPreview.replace("|", "\\|").replace("\n", " ");
                    }
                    sb.append(String.format("| %d | %s | %s | %d | `%s` |\n",
                        rank++, severity, execTimeStr,
                        sq.getCallCount() != null ? sq.getCallCount() : 0,
                        queryPreview != null ? queryPreview : "?"));
                }

                sb.append(String.format("\n*%d queries from ingested logs*", analysis.getTopSlowQueries().size()));
                log.info("Fast path: Returning top {} slow queries", topN);
                return sb.toString();
            }

            // === SLOWEST SINGLE QUERY QUESTION ===
            SlowQuery slowest = analysis.getTopSlowQueries().stream()
                .max((a, b) -> Double.compare(contextAssembler.getBestExecutionTime(a), contextAssembler.getBestExecutionTime(b)))
                .orElse(null);

            if (slowest == null) {
                return null;
            }

            sb.append("### Your Slowest Query (from production logs)\n\n");

            double execTime = contextAssembler.getBestExecutionTime(slowest);
            if (execTime >= 1000) {
                sb.append(String.format("**Execution Time:** %.2f seconds\n\n", execTime / 1000.0));
            } else {
                sb.append(String.format("**Execution Time:** %.0f ms\n\n", execTime));
            }

            if (slowest.getSeverity() != null) {
                sb.append("**Severity:** ").append(slowest.getSeverity()).append("\n");
            }
            if (slowest.getCallCount() != null && slowest.getCallCount() > 0) {
                sb.append("**Call Count:** ").append(slowest.getCallCount()).append(" times\n");
            }
            if (slowest.getRowsExamined() != null && slowest.getRowsExamined() > 0) {
                sb.append("**Rows Examined:** ").append(String.format("%,d", slowest.getRowsExamined())).append("\n");
            }

            // Show affected tables
            if (slowest.getAffectedTables() != null && !slowest.getAffectedTables().isEmpty()) {
                sb.append("**Tables:** ").append(String.join(", ", slowest.getAffectedTables())).append("\n");
            }

            // Show the full query verbatim. We used to truncate at 1000 chars
            // for "readability" — but users actually need the full SQL so they
            // can run EXPLAIN against it, or copy/paste to optimize. Long
            // queries are wrapped in the markdown code block; chat clients
            // handle scrolling.
            String queryText = slowest.getNormalizedQuery() != null ? slowest.getNormalizedQuery() : slowest.getQueryText();
            if (queryText != null) {
                sb.append("\n**Query:**\n```sql\n").append(queryText).append("\n```\n");

                // Surface server-side truncation. Three cases:
                //   1. Not truncated → no warning.
                //   2. Truncated but recovered from previously-ingested slow
                //      log files in query_lineage → note the recovery so the
                //      user knows EXPLAIN will still work.
                //   3. Truncated and not recovered → loud warning; EXPLAIN
                //      against this text will fail or return a partial plan.
                if (Boolean.TRUE.equals(slowest.getSourceTruncated())) {
                    if (Boolean.TRUE.equals(slowest.getQueryTextRecoveredFromLogs())) {
                        sb.append("\n> ℹ The live stats source truncated this query at the "
                            + "database server (default 1024B), but DeepSQL recovered the full "
                            + "text from previously-ingested slow-log data. EXPLAIN will work.\n");
                    } else {
                        sb.append("\n> ⚠ This query was truncated at the database server before "
                            + "DeepSQL could see it, and we don't have a full-text copy from "
                            + "previously-ingested slow logs. To see the full SQL, either ingest "
                            + "the slow query log file for this connection, or ask your DBA to "
                            + "raise `pg_stat_statements.track_activity_query_size` (PostgreSQL) "
                            + "or `performance_schema_max_sql_text_length` (MySQL) and restart, "
                            + "then wait for the slow query to recur. EXPLAIN against this query "
                            + "will fail or return a partial plan.\n");
                    }
                }
            }

            // Show suggestions
            if (slowest.getSuggestions() != null && !slowest.getSuggestions().isEmpty()) {
                sb.append("\n**Optimization Suggestions:**\n");
                for (String suggestion : slowest.getSuggestions()) {
                    sb.append("- ").append(suggestion).append("\n");
                }
            }

            sb.append("\n*Data from ingested slow query logs, analyzed at ");
            sb.append(latest.getCreatedAt() != null ? latest.getCreatedAt().toString() : "unknown");
            sb.append("*");

            log.info("Fast path: Returning slowest query from ingested logs (exec time: {} ms)", execTime);
            return sb.toString();

        } catch (Exception e) {
            log.debug("Failed to parse slow query data for fast path: {}", e.getMessage());
            return null; // Fall back to LLM
        }
    }

    /**
     * Try to answer index recommendation questions directly from stored recommendations.
     * Handles: "What indexes should I add?", "Missing indexes", "Index recommendations"
     */
    private String retiredDirectIndexRecommendationAnswer(String message, String connectionId) {
        if (message == null || connectionId == null) {
            return null;
        }

        // Extract the actual user question
        String actualQuestion = extractActualUserQuestion(message);

        String lowerMessage = actualQuestion.toLowerCase().trim();

        // Match index recommendation questions
        boolean isIndexQuestion = lowerMessage.matches(".*(index|indexes|indices).*(recommend|suggestion|missing|should|need|add|create).*") ||
            lowerMessage.matches(".*(recommend|suggest|missing|need).*(index|indexes|indices).*") ||
            lowerMessage.matches(".*(what|which).*(index|indexes|indices).*(should|need|add|create).*") ||
            lowerMessage.matches(".*missing\\s+index.*") ||
            lowerMessage.matches(".*index\\s+recommendation.*");

        if (!isIndexQuestion) {
            return null;
        }

        log.info("Fast path: Detected index recommendation question for connection {}", connectionId);

        try {
            // Get pending index recommendations
            List<IndexRecommendationEntity> recommendations = indexRecommendationRepository
                .findByConnectionIdAndStatusOrderByPriorityAscCreatedAtDesc(
                    connectionId, IndexRecommendationEntity.Status.PENDING);

            if (recommendations == null || recommendations.isEmpty()) {
                return "### Index Recommendations\n\n" +
                    "No pending index recommendations found.\n\n" +
                    "To generate recommendations:\n" +
                    "1. Ingest slow query logs via **Performance > Slow Query Analysis**\n" +
                    "2. Run **Brain > Key Column Analysis**\n" +
                    "3. Check **Operations > Index Recommendations**";
            }

            StringBuilder sb = new StringBuilder();
            sb.append("### Index Recommendations\n\n");
            sb.append(String.format("Found **%d pending** index recommendations:\n\n", recommendations.size()));

            // Group by priority
            var highPriority = recommendations.stream()
                .filter(r -> r.getPriority() == IndexRecommendationEntity.Priority.HIGH)
                .toList();
            var mediumPriority = recommendations.stream()
                .filter(r -> r.getPriority() == IndexRecommendationEntity.Priority.MEDIUM)
                .toList();
            var lowPriority = recommendations.stream()
                .filter(r -> r.getPriority() == IndexRecommendationEntity.Priority.LOW)
                .toList();

            if (!highPriority.isEmpty()) {
                sb.append("#### High Priority\n\n");
                sb.append("| Table | Columns | Impact | Affected Queries |\n");
                sb.append("|-------|---------|--------|------------------|\n");
                for (var rec : highPriority.stream().limit(10).toList()) {
                    sb.append(String.format("| `%s` | `%s` | %d%% | %d |\n",
                        rec.getTableName(),
                        rec.getColumnNames(),
                        rec.getEstimatedImpact() != null ? rec.getEstimatedImpact() : 0,
                        rec.getAffectedQueries() != null ? rec.getAffectedQueries() : 0));
                }
                sb.append("\n");
            }

            if (!mediumPriority.isEmpty()) {
                sb.append("#### Medium Priority\n\n");
                sb.append("| Table | Columns | Impact | Affected Queries |\n");
                sb.append("|-------|---------|--------|------------------|\n");
                for (var rec : mediumPriority.stream().limit(5).toList()) {
                    sb.append(String.format("| `%s` | `%s` | %d%% | %d |\n",
                        rec.getTableName(),
                        rec.getColumnNames(),
                        rec.getEstimatedImpact() != null ? rec.getEstimatedImpact() : 0,
                        rec.getAffectedQueries() != null ? rec.getAffectedQueries() : 0));
                }
                sb.append("\n");
            }

            if (!lowPriority.isEmpty() && highPriority.isEmpty() && mediumPriority.isEmpty()) {
                sb.append("#### Low Priority\n\n");
                sb.append("| Table | Columns | Impact |\n");
                sb.append("|-------|---------|--------|\n");
                for (var rec : lowPriority.stream().limit(5).toList()) {
                    sb.append(String.format("| `%s` | `%s` | %d%% |\n",
                        rec.getTableName(),
                        rec.getColumnNames(),
                        rec.getEstimatedImpact() != null ? rec.getEstimatedImpact() : 0));
                }
                sb.append("\n");
            }

            // Show first CREATE INDEX statement as example
            if (!highPriority.isEmpty() && highPriority.get(0).getCreateStatement() != null) {
                sb.append("**Top recommendation SQL:**\n```sql\n");
                sb.append(highPriority.get(0).getCreateStatement());
                sb.append("\n```\n");
                if (highPriority.get(0).getReason() != null) {
                    sb.append("\n*Reason: " + highPriority.get(0).getReason() + "*\n");
                }
            }

            log.info("Fast path: Returning {} index recommendations", recommendations.size());
            return sb.toString();

        } catch (Exception e) {
            log.debug("Failed to get index recommendations for fast path: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Try to optimize a user-provided SQL query directly (AI + optd candidates).
     * Triggers when SQL is provided and the intent is optimization, or when the message is just SQL.
     */
    private String retiredDirectQueryOptimizationAnswer(String message, String connectionId) {
        if (message == null || connectionId == null) {
            return null;
        }

        String actualQuestion = extractActualUserQuestion(message);

        String sql = extractSqlFromMessage(actualQuestion);
        if (sql == null || sql.isBlank()) {
            return null;
        }

        boolean optimizeIntent = hasOptimizationIntent(actualQuestion);
        boolean queryOnly = startsWithSqlKeyword(actualQuestion);
        if (!optimizeIntent && !queryOnly) {
            return null;
        }

        try {
            QueryOptimizationService.OptimizationResult result =
                queryOptimizationService.optimizeQuery(connectionId, sql, sql, null);

            String fingerprint = QueryNormalizer.generateFingerprint(sql);
            QueryOptimizationCandidateRun bestCandidate = candidateService
                .getBestCandidate(connectionId, fingerprint)
                .orElse(null);

            return buildOptimizationDirectResponse(result, bestCandidate);
        } catch (Exception e) {
            log.debug("Failed to optimize query via fast path: {}", e.getMessage());
            return null;
        }
    }

    private String extractSqlFromMessage(String message) {
        if (message == null) {
            return null;
        }

        Matcher matcher = SQL_CODE_BLOCK.matcher(message);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }

        matcher = GENERIC_CODE_BLOCK.matcher(message);
        if (matcher.find()) {
            String candidate = matcher.group(1).trim();
            if (looksLikeSql(candidate)) {
                return candidate;
            }
        }

        matcher = INLINE_CODE.matcher(message);
        while (matcher.find()) {
            String candidate = matcher.group(1).trim();
            if (looksLikeSql(candidate)) {
                return candidate;
            }
        }

        String sanitized = QueryNormalizer.sanitize(message);
        if (looksLikeSql(sanitized)) {
            return sanitized;
        }

        return null;
    }

    private boolean looksLikeSql(String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        String type = QueryNormalizer.detectQueryType(candidate);
        return !"UNKNOWN".equals(type) && !"OTHER".equals(type);
    }

    private boolean startsWithSqlKeyword(String message) {
        if (message == null) {
            return false;
        }
        String trimmed = message.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        String upper = trimmed.toUpperCase();
        return upper.startsWith("SELECT") || upper.startsWith("WITH") || upper.startsWith("UPDATE") ||
            upper.startsWith("INSERT") || upper.startsWith("DELETE");
    }

    /**
     * Returns true when the question is asking for actual data rows from the database
     * (records, rankings, counts, lists of entities, business metrics, etc.).
     *
     * These questions MUST be answered with a SQL query. If the LLM responds without SQL
     * for one of these, it has almost certainly hallucinated the answer.
     */
    private boolean isDataRetrievalQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String q = question.toLowerCase();

        // Ranking / top-N / bottom-N patterns
        if (q.matches(".*(top|bottom|least|most|highest|lowest|worst|best|slowest|fastest)\\s+\\d+.*")) return true;
        if (q.matches(".*(top|bottom|least|most|highest|lowest|worst|best)\\s+(\\d+\\s+)?(accounts?|users?|customers?|orders?|records?|rows?|queries?|tables?|sessions?|transactions?|products?|bookings?|hotels?|properties?|tenants?|clients?).*")) return true;

        // "Show me / list / give me / find" entity requests
        if (q.matches(".*(show me|list|give me|find|fetch|retrieve|get me|display|return)\\s+.*\\b(accounts?|users?|customers?|orders?|records?|rows?|entries?|data|results?|transactions?|bookings?|hotels?|properties?).*")) return true;

        // "Which X are / have / do" questions — expect data rows as answer
        if (q.matches(".*which\\s+\\w+\\s+(are|have|do|did|has|were|is).*")) return true;

        // Row/record count questions (business data, not schema metadata)
        if (q.matches(".*(how many|count of|number of)\\s+.*(rows?|records?|accounts?|users?|customers?|orders?|bookings?|sessions?|transactions?).*")) return true;

        // Engagement, activity, churn, usage patterns
        if (q.matches(".*(least|most|zero|no|without|never|active|inactive|engaged|churned|dormant|unused)\\s+.*(accounts?|users?|customers?|sessions?|logins?|activity|usage|engagement).*")) return true;

        // Explicit data/report requests
        if (q.matches(".*(report|summary|breakdown|overview|analysis)\\s+(of|on|for).*\\b(last|past|since|in the).*\\b(days?|weeks?|months?|years?).*")) return true;

        // Time-bounded data questions
        if (q.matches(".*in the (last|past)\\s+\\d+\\s+(days?|weeks?|months?).*") &&
            q.matches(".*(accounts?|users?|customers?|orders?|bookings?|queries?|transactions?|sessions?).*")) return true;

        return false;
    }

    private boolean isExactTableColumnCountQuestion(String lowerMessage) {
        return SchemaQuestionUtil.looksLikeExactTableColumnCountQuestion(lowerMessage);
    }

    private boolean isExactTableColumnListQuestion(String lowerMessage) {
        return SchemaQuestionUtil.looksLikeExactTableColumnListQuestion(lowerMessage);
    }

    private boolean isExactTableRowCountQuestion(String lowerMessage) {
        return SchemaQuestionUtil.looksLikeExactTableRowCountQuestion(lowerMessage);
    }

    private boolean isExactTableIndexQuestion(String lowerMessage) {
        return SchemaQuestionUtil.looksLikeExactTableIndexQuestion(lowerMessage);
    }

    private String formatExactTableColumnCountAnswer(TableMetadata table) {
        int columnCount = table.getColumns() != null ? table.getColumns().size() : 0;
        return String.format(
            "Table `%s` has **%d columns**.",
            table.getName(),
            columnCount
        );
    }

    private String formatExactTableColumnListAnswer(TableMetadata table) {
        int columnCount = table.getColumns() != null ? table.getColumns().size() : 0;
        StringBuilder sb = new StringBuilder();
        sb.append("Table `").append(table.getName()).append("` has **").append(columnCount).append(" columns**.\n\n");
        if (columnCount == 0) {
            sb.append("I don’t have column details for this table in the current schema snapshot.");
            return sb.toString();
        }

        sb.append("Columns:\n");
        table.getColumns().stream()
            .filter(Objects::nonNull)
            .sorted(Comparator.comparingInt(column -> column.getOrdinalPosition() != null ? column.getOrdinalPosition() : Integer.MAX_VALUE))
            .forEach(column -> sb.append("- ").append(formatColumnLine(column)).append("\n"));
        return sb.toString();
    }

    private String formatExactTableRowCountAnswer(TableMetadata table) {
        if (table.getRowCount() == null) {
            return String.format(
                "I don’t have a row count for `%s` in the current schema snapshot.",
                table.getName()
            );
        }
        return String.format(
            "Table `%s` has an estimated **%s rows** in the current schema snapshot.",
            table.getName(),
            contextAssembler.formatRowCount(table.getRowCount())
        );
    }

    private String formatExactTableIndexAnswer(TableMetadata table, String lowerQuestion) {
        List<IndexMetadata> indexes = table.getIndexes() != null
            ? table.getIndexes().stream().filter(Objects::nonNull).toList()
            : List.of();
        boolean countQuestion = SchemaQuestionUtil.looksLikeExactTableIndexCountQuestion(lowerQuestion);
        if (countQuestion) {
            return String.format(
                "Table `%s` has **%d indexes** in the current schema snapshot.",
                table.getName(),
                indexes.size()
            );
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Table `").append(table.getName()).append("` has **").append(indexes.size())
            .append(" indexes** in the current schema snapshot.\n\n");
        if (indexes.isEmpty()) {
            sb.append("I don’t have any index entries recorded for this table.");
            return sb.toString();
        }

        sb.append("Indexes:\n");
        indexes.stream()
            .sorted(Comparator.comparing(index -> index.getName() != null ? index.getName() : "", String.CASE_INSENSITIVE_ORDER))
            .forEach(index -> sb.append("- ").append(formatIndexLine(index)).append("\n"));
        return sb.toString();
    }

    private String formatExactTableKeyColumnAnswer(
        TableMetadata table,
        List<ExactSchemaKeyColumnUtil.KeyColumnDescriptor> keyColumns,
        String lowerQuestion
    ) {
        boolean countQuestion = lowerQuestion.matches(".*(how many|count|number of).*(key columns?|primary keys?|foreign keys?|join columns?).*");
        if (countQuestion) {
            return String.format(
                "Table `%s` has **%d key columns**: %s.",
                table.getName(),
                keyColumns.size(),
                keyColumns.stream()
                    .map(descriptor -> "`" + descriptor.columnName() + "`")
                    .collect(Collectors.joining(", "))
            );
        }

        StringBuilder sb = new StringBuilder();
        sb.append("The most relevant key columns in `")
            .append(table.getName())
            .append("` are:\n");
        keyColumns.forEach(descriptor -> sb.append("- `")
            .append(descriptor.columnName())
            .append("` — ")
            .append(descriptor.summary())
            .append("\n"));
        return sb.toString();
    }

    private String formatColumnLine(ColumnMetadata column) {
        StringBuilder attributes = new StringBuilder();
        if (Boolean.TRUE.equals(column.getPrimaryKey())) {
            attributes.append("primary key");
        }
        if (column.getNullable() != null) {
            if (attributes.length() > 0) {
                attributes.append("; ");
            }
            attributes.append(Boolean.TRUE.equals(column.getNullable()) ? "nullable" : "not null");
        }
        if (column.getMaxLength() != null && column.getMaxLength() > 0) {
            if (attributes.length() > 0) {
                attributes.append("; ");
            }
            attributes.append("max length ").append(column.getMaxLength());
        }

        return "`" + column.getName() + "` — `"
            + (column.getDataType() != null ? column.getDataType() : "?")
            + "`"
            + (attributes.length() > 0 ? "; " + attributes : "");
    }

    private String formatIndexLine(IndexMetadata index) {
        StringBuilder attributes = new StringBuilder();
        if (Boolean.TRUE.equals(index.getUnique())) {
            attributes.append("unique");
        }
        if (index.getIndexType() != null && !index.getIndexType().isBlank()) {
            if (attributes.length() > 0) {
                attributes.append("; ");
            }
            attributes.append(index.getIndexType().toLowerCase(Locale.ROOT));
        }

        String columns = index.getColumns() == null || index.getColumns().isEmpty()
            ? "unspecified columns"
            : index.getColumns().stream()
                .filter(Objects::nonNull)
                .map(column -> "`" + column + "`")
                .collect(Collectors.joining(", "));

        return "`" + (index.getName() != null ? index.getName() : "?")
            + "` — columns: " + columns
            + (attributes.length() > 0 ? "; " + attributes : "");
    }

    private boolean isClarifyingQuestionResponse(String responseContent) {
        if (responseContent == null || responseContent.isBlank()) {
            return false;
        }
        if (SQL_CODE_BLOCK.matcher(responseContent).find()) {
            return false;
        }

        String lower = responseContent.toLowerCase();
        boolean asksQuestion = lower.contains("?");
        boolean ambiguitySignal =
            lower.contains("which column") ||
            lower.contains("which table") ||
            lower.contains("confirm the exact") ||
            lower.contains("confirm the table") ||
            lower.contains("do you mean") ||
            lower.contains("can't safely choose") ||
            lower.contains("cannot safely choose") ||
            lower.contains("need one clarification") ||
            lower.contains("should define");

        return asksQuestion && ambiguitySignal;
    }

    private boolean hasUnresolvedSqlPlaceholder(String sql) {
        if (sql == null || sql.isBlank()) {
            return false;
        }
        return sql.matches("(?s).*(<\\s*[a-zA-Z0-9_]+\\s*>).*");
    }

    /**
     * Returns a re-prompt system message that forces the LLM to generate SQL
     * instead of a hallucinated answer.
     */
    private String buildSqlRequiredRePrompt(String question) {
        return "STOP. Your previous response answered the user's question without executing a SQL query against the database. " +
            "This is not allowed — you MUST NOT fabricate, invent, or estimate data that exists in the database. " +
            "\n\nThe user asked: \"" + question + "\"" +
            "\n\nYou MUST generate a SQL query (in a ```sql ... ``` block) to retrieve the real answer from the database. " +
            "The system will execute it and return real results. " +
            "Do NOT provide any data values, names, counts, or lists that are not the result of an actual database query. " +
            "If a critical table, metric definition, or time/date column is ambiguous, respond ONLY with a clarifying question. " +
            "Never emit placeholder SQL such as <time_column> or guessed columns that are not visible in schema context.";
    }

    /**
     * Extracts the :::plan ... ::: block from an agent first-pass response.
     * Returns the plan text, or null if no plan block is present.
     */
    private static final java.util.regex.Pattern PLAN_BLOCK_PATTERN =
        java.util.regex.Pattern.compile(":::plan\\s*\\n([\\s\\S]*?)\\n\\s*:::", java.util.regex.Pattern.CASE_INSENSITIVE);

    private String extractPlanFromResponse(String response) {
        if (response == null) return null;
        java.util.regex.Matcher m = PLAN_BLOCK_PATTERN.matcher(response);
        return m.find() ? m.group(1).trim() : null;
    }

    /**
     * Strips internal agent blocks from the final visible message so the Output tab
     * only contains business insights — no raw SQL, no plan blocks, no [ASK] prefix.
     * These blocks live in the Inspect tab instead.
     */
    private static final java.util.regex.Pattern STRIP_PLAN_PATTERN =
        java.util.regex.Pattern.compile(":::plan[\\s\\S]*?:::", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern STRIP_SQL_PATTERN =
        java.util.regex.Pattern.compile("```sql[\\s\\S]*?```", java.util.regex.Pattern.CASE_INSENSITIVE);
    private static final java.util.regex.Pattern STRIP_CODE_PATTERN =
        java.util.regex.Pattern.compile("```[\\s\\S]*?```");
    private static final java.util.regex.Pattern STRIP_ASK_PREFIX =
        java.util.regex.Pattern.compile("^\\[ASK\\]\\s*", java.util.regex.Pattern.MULTILINE | java.util.regex.Pattern.CASE_INSENSITIVE);

    private String stripInternalAgentBlocks(String text) {
        if (text == null) return null;
        String result = STRIP_PLAN_PATTERN.matcher(text).replaceAll("");
        result = STRIP_SQL_PATTERN.matcher(result).replaceAll("");
        result = STRIP_CODE_PATTERN.matcher(result).replaceAll("");
        result = STRIP_ASK_PREFIX.matcher(result).replaceAll("");
        // Collapse excessive blank lines
        result = result.replaceAll("\\n{3,}", "\n\n").trim();
        // Return empty string when ALL content was internal (plan+SQL only).
        // Callers must handle this case with a meaningful fallback — do NOT
        // return the original text because the frontend will strip it too.
        return result;
    }

    /**
     * Builds the second-pass system message shown to the LLM after SQL results are available.
     * For brain-agent calls, instructs the LLM to format as an executive briefing with
     * chart/table artifacts. For regular chat, uses a concise summary instruction.
     */
    private String buildSecondPassInstruction(String projectId, List<String> executedQueries, List<QueryResult> queryResults) {
        if (queryResults == null || queryResults.isEmpty()) {
            return "No query results were available. Ask a concise clarifying question instead of guessing.";
        }

        StringBuilder formattedResults = new StringBuilder();
        for (int i = 0; i < queryResults.size(); i++) {
            QueryResult result = queryResults.get(i);
            String queryLabel = (executedQueries != null && i < executedQueries.size())
                ? executedQueries.get(i)
                : "(query unavailable)";
            formattedResults.append("Query ").append(i + 1).append(":\n")
                .append(queryLabel)
                .append("\nResult ").append(i + 1).append(":\n")
                .append(sqlExecutionPipeline.formatQueryResultForAI(result))
                .append("\n\n");
        }

        boolean isAgentMode = "brain-agent".equals(projectId);
        if (isAgentMode) {
            int rowCount = queryResults.stream()
                .mapToInt(r -> r != null && r.getRowCount() != null ? r.getRowCount() : 0)
                .sum();
            boolean isEmpty = rowCount == 0;
            String resultNote = isEmpty
                ? "The query returned 0 rows — no matching records were found."
                : "The query returned " + rowCount + " row(s). Use the exact values below.";
            return "DATABASE QUERY COMPLETE.\n" +
                resultNote + "\n\n" +
                "--- ACTUAL RESULTS ---\n" +
                formattedResults +
                "\n--- END OF RESULTS ---\n\n" +
                "Now write your executive briefing below. START IMMEDIATELY with your summary — " +
                "do not repeat or reference this system message.\n\n" +
                "HARD RULES (violations break the display):\n" +
                "• Plain English only — no ```sql``` blocks, no :::plan blocks, no code of any kind.\n" +
                "• If 0 rows: explain what was checked and why it returned empty (date range, data availability, etc.).\n" +
                "• If data exists: summarise key findings with exact numbers.\n" +
                "• Use :::chart:type or :::table blocks for structured data — they render as visuals.\n" +
                "• Never fabricate numbers. Never say 'I ran a query'.\n\n" +
                "Begin your briefing:";
        }
        return "The query was executed successfully. Here are the results:\n" +
            formattedResults +
            "\n\nPlease provide a final summarized answer to the user based on these results.";
    }

    /**
     * Second-pass instruction when multiple SQL queries were executed (agent multi-step plan).
     * Combines all results and asks for a unified executive briefing.
     */
    private String buildMultiQuerySecondPassInstruction(List<String> sqls, List<QueryResult> results) {
        String combinedResults = sqlExecutionPipeline.formatMultipleQueryResults(sqls, results);
        int totalRows = results.stream()
            .mapToInt(r -> r != null && r.getRowCount() != null ? r.getRowCount() : 0)
            .sum();
        String resultNote = totalRows == 0
            ? "All queries returned 0 rows — no matching records were found."
            : "Queries returned a combined " + totalRows + " row(s) across " + results.size() + " steps.";
        return "ALL " + results.size() + " DATABASE QUERIES COMPLETE.\n" +
            resultNote + "\n\n" +
            "--- ACTUAL RESULTS (ALL STEPS) ---\n" +
            combinedResults +
            "\n--- END OF RESULTS ---\n\n" +
            "Now write your executive briefing below. START IMMEDIATELY with your summary — " +
            "do not repeat or reference this system message.\n\n" +
            "HARD RULES:\n" +
            "• Plain English only — no ```sql``` blocks, no :::plan blocks, no code of any kind.\n" +
            "• Synthesise ALL query results into a unified answer — not step-by-step.\n" +
            "• If 0 rows: explain what was checked and why it returned empty.\n" +
            "• If data exists: lead with the headline number, then supporting detail.\n" +
            "• Use :::chart:type or :::table blocks for structured data — they render as visuals.\n" +
            "• Never fabricate numbers. Never mention SQL or table names.\n\n" +
            "Begin your briefing:";
    }

    private boolean hasOptimizationIntent(String message) {
        if (message == null) {
            return false;
        }
        String lower = message.toLowerCase();
        return lower.contains("optimiz") || lower.contains("rewrite") || lower.contains("tune") ||
            lower.contains("performance") || lower.contains("slow") || lower.contains("faster") ||
            lower.contains("speed up") || lower.contains("improve");
    }

    private String buildOptimizationDirectResponse(
        QueryOptimizationService.OptimizationResult result,
        QueryOptimizationCandidateRun bestCandidate
    ) {
        StringBuilder sb = new StringBuilder();
        sb.append("### Query Optimization\n\n");

        if (bestCandidate != null) {
            boolean measured = bestCandidate.getMedianMs() != null || bestCandidate.getBenchmarkMs() != null;
            sb.append("**Best Recommended Plan (");
            sb.append(measured ? "fastest measured" : "predicted fastest");
            sb.append(")**\n");
            sb.append("- Candidate: ").append(bestCandidate.getCandidateId()).append("\n");

            if (measured) {
                Double runtime = bestCandidate.getMedianMs() != null
                    ? bestCandidate.getMedianMs()
                    : bestCandidate.getBenchmarkMs();
                sb.append("- Runtime: ").append(formatDurationMs(runtime)).append("\n");
            } else if (bestCandidate.getEstimatedCost() != null) {
                sb.append("- Estimated cost: ").append(formatCost(bestCandidate.getEstimatedCost())).append("\n");
            }

            if (bestCandidate.getPlanSignature() != null) {
                sb.append("- Plan signature: `").append(bestCandidate.getPlanSignature()).append("`\n");
            }

            if (bestCandidate.getCandidateSql() != null && !bestCandidate.getCandidateSql().isBlank()) {
                sb.append("\n```sql\n").append(bestCandidate.getCandidateSql()).append("\n```\n\n");
            } else {
                sb.append("\n");
            }
        }

        if (result != null) {
            if (result.getOptimizedQuery() != null && !result.getOptimizedQuery().isBlank() &&
                (bestCandidate == null || bestCandidate.getCandidateSql() == null ||
                    !result.getOptimizedQuery().trim().equalsIgnoreCase(bestCandidate.getCandidateSql().trim()))) {
                sb.append("**AI Rewrite**\n```sql\n");
                sb.append(result.getOptimizedQuery().trim());
                sb.append("\n```\n\n");
            }

            if (result.getSuggestions() != null && !result.getSuggestions().isEmpty()) {
                sb.append("**Top Suggestions**\n");
                result.getSuggestions().stream().limit(3).forEach(s -> {
                    String title = s.getTitle() != null ? s.getTitle() : s.getCategory();
                    String desc = s.getDescription();
                    if (desc != null && !desc.isBlank()) {
                        sb.append("- ").append(title).append(": ").append(desc).append("\n");
                    } else {
                        sb.append("- ").append(title).append("\n");
                    }
                });
                sb.append("\n");
            }

            if (result.getIndexRecommendations() != null && !result.getIndexRecommendations().isEmpty()) {
                sb.append("**Index Recommendations**\n");
                result.getIndexRecommendations().stream().limit(3).forEach(rec -> {
                    sb.append("- ").append(rec).append("\n");
                });
                sb.append("\n");
            }

            if (result.getEstimatedImprovement() != null) {
                sb.append("**Estimated Improvement:** ");
                sb.append(String.format("%.0f%%", result.getEstimatedImprovement()));
                sb.append("\n");
            }
        }

        return sb.toString().trim();
    }

    private String formatDurationMs(Double ms) {
        if (ms == null) {
            return "N/A";
        }
        if (ms >= 1000) {
            return String.format("%.2fs", ms / 1000.0);
        }
        return String.format("%.0fms", ms);
    }

    private String formatCost(Double cost) {
        if (cost == null) {
            return "N/A";
        }
        if (cost >= 1_000_000) {
            return String.format("%.1fM", cost / 1_000_000.0);
        }
        if (cost >= 1_000) {
            return String.format("%.1fK", cost / 1_000.0);
        }
        return String.format("%.2f", cost);
    }

    private String retiredRoutedMetadataAnswer(
            ChatQuestionRoutingService.QuestionRoute questionRoute,
            String actualUserQuestion,
            String connectionId,
            SchemaMetadata schema) {
        if (questionRoute == null || !questionRoute.isBrainMetadata()) {
            return null;
        }

        return switch (questionRoute.brainTopic()) {
            case SCHEMA -> retiredDirectSchemaAnswer(actualUserQuestion, schema);
            case PERFORMANCE -> {
                String answer = retiredDirectSlowQueryAnswer(actualUserQuestion, connectionId);
                if (answer == null) {
                    answer = retiredDirectIndexRecommendationAnswer(actualUserQuestion, connectionId);
                }
                if (answer == null) {
                    answer = retiredDirectQueryOptimizationAnswer(actualUserQuestion, connectionId);
                }
                yield answer;
            }
            case KEY_COLUMNS -> retiredDirectKeyColumnAnswer(actualUserQuestion, connectionId, schema);
            case RELATIONSHIPS -> retiredDirectRelationshipAnswer(actualUserQuestion, connectionId, schema);
            case GROWTH -> retiredDirectGrowthAnswer(actualUserQuestion, connectionId, schema);
            case CLASSIFICATION -> retiredDirectSchemaClassificationAnswer(actualUserQuestion, connectionId, schema);
            case WORKLOAD, TUNING -> retiredDirectWorkloadAnswer(actualUserQuestion, connectionId);
            case GENERAL -> null;
        };
    }

    private String retiredDirectKeyColumnAnswer(String message, String connectionId, SchemaMetadata schema) {
        if (message == null || connectionId == null || schema == null) {
            return null;
        }

        String actualQuestion = extractActualUserQuestion(message);
        String lowerMessage = actualQuestion.toLowerCase(Locale.ROOT).trim();
        TableMetadata exactSchemaTable = SchemaQuestionUtil.looksLikeExactTableKeyColumnQuestion(actualQuestion)
            ? SchemaQuestionUtil.resolveExactSchemaTable(schema, actualQuestion)
            : null;

        if (exactSchemaTable != null) {
            List<ExactSchemaKeyColumnUtil.KeyColumnDescriptor> exactKeyColumns =
                ExactSchemaKeyColumnUtil.collectKeyColumns(schema, exactSchemaTable);
            List<KeyColumnAnalysis> analyzedColumns = keyColumnAnalysisRepository
                .findByConnectionIdOrderByImportanceScoreDesc(connectionId).stream()
                .filter(analysis -> SchemaObjectNameUtil.referencesSameTable(exactSchemaTable.getName(), analysis.getTableName()))
                .filter(this::isMeaningfulKeyColumn)
                .limit(8)
                .toList();
            List<ExactSchemaKeyColumnUtil.KeyColumnDescriptor> resolvedKeyColumns =
                ExactSchemaKeyColumnUtil.mergeWithAnalyzedColumns(exactKeyColumns, analyzedColumns);
            if (!resolvedKeyColumns.isEmpty()) {
                return formatExactTableKeyColumnAnswer(exactSchemaTable, resolvedKeyColumns, lowerMessage);
            }
            return null;
        }

        List<String> mentionedTables = findMentionedTables(lowerMessage, schema);

        List<KeyColumnAnalysis> rankedColumns = keyColumnAnalysisRepository
            .findByConnectionIdOrderByImportanceScoreDesc(connectionId);
        if (!mentionedTables.isEmpty()) {
            rankedColumns = rankedColumns.stream()
                .filter(analysis -> matchesMentionedTable(mentionedTables, analysis.getTableName()))
                .toList();
        }

        List<KeyColumnAnalysis> meaningfulColumns = rankedColumns.stream()
            .filter(this::isMeaningfulKeyColumn)
            .toList();

        boolean countQuestion = lowerMessage.matches(".*(how many|count|number of).*(key columns?|primary keys?|foreign keys?).*");
        if (!meaningfulColumns.isEmpty()) {
            List<KeyColumnAnalysis> topColumns = meaningfulColumns.stream().limit(8).toList();
            long distinctTables = meaningfulColumns.stream()
                .map(KeyColumnAnalysis::getTableName)
                .filter(Objects::nonNull)
                .distinct()
                .count();

            String topSummary = topColumns.stream()
                .map(col -> "`" + col.getTableName() + "." + col.getColumnName() + "`")
                .collect(Collectors.joining(", "));

            if (countQuestion) {
                return String.format(
                    "Stored metadata identifies **%d KEY COLUMNS** across **%d TABLES**. Top KEY COLUMN entries: %s.",
                    meaningfulColumns.size(),
                    distinctTables,
                    topSummary
                );
            }

            if (!mentionedTables.isEmpty()) {
                return String.format(
                    "Top key columns across the mentioned tables (%s): %s.",
                    String.join(", ", mentionedTables),
                    topSummary
                );
            }

            return "Top key columns from stored metadata: " + topSummary + ".";
        }

        List<String> scopedTables = mentionedTables.isEmpty()
            ? schema.getTables().stream().map(t -> t.getName()).filter(Objects::nonNull).toList()
            : mentionedTables;

        List<String> primaryKeyColumns = new ArrayList<>();
        for (var table : schema.getTables()) {
            if (table.getName() == null || !matchesMentionedTable(scopedTables, table.getName())) {
                continue;
            }
            for (var column : table.getColumns()) {
                if (Boolean.TRUE.equals(column.getPrimaryKey())) {
                    primaryKeyColumns.add(table.getName() + "." + column.getName());
                }
            }
        }

        long relationshipCount = schema.getRelationships() == null ? 0 : schema.getRelationships().stream()
            .filter(rel -> mentionedTables.isEmpty()
                || matchesMentionedTable(mentionedTables, rel.getFromTable())
                || matchesMentionedTable(mentionedTables, rel.getToTable()))
            .count();

        if (!primaryKeyColumns.isEmpty()) {
            String preview = primaryKeyColumns.stream()
                .limit(8)
                .map(col -> "`" + col + "`")
                .collect(Collectors.joining(", "));
            return String.format(
                "Cached schema metadata shows **%d KEY COLUMNS** (primary-key columns) across **%d TABLES** and **%d RELATIONSHIPS**. Primary keys: %s.",
                primaryKeyColumns.size(),
                scopedTables.size(),
                relationshipCount,
                preview
            );
        }

        return "I do not have stored key-column rankings for this connection yet, and the cached schema metadata does not expose any primary-key columns.";
    }

    private boolean isMeaningfulKeyColumn(KeyColumnAnalysis analysis) {
        if (analysis == null) {
            return false;
        }

        if (analysis.getKeyType() != null && !"NON_KEY".equalsIgnoreCase(analysis.getKeyType())) {
            return true;
        }
        if (analysis.getImportanceScore() != null && analysis.getImportanceScore().compareTo(BigDecimal.ONE) >= 0) {
            return true;
        }
        return safeInt(analysis.getJoinCount()) > 0
            || safeInt(analysis.getWhereCount()) > 0
            || safeInt(analysis.getGroupByCount()) > 0
            || safeInt(analysis.getOrderByCount()) > 0
            || safeInt(analysis.getTotalUsageCount()) > 0;
    }

    private String retiredDirectRelationshipAnswer(String message, String connectionId, SchemaMetadata schema) {
        if (message == null || connectionId == null || schema == null) {
            return null;
        }

        String lowerMessage = extractActualUserQuestion(message).toLowerCase(Locale.ROOT).trim();
        List<String> mentionedTables = findMentionedTables(lowerMessage, schema);

        List<InferredTableRelationship> inferred = inferredTableRelationshipRepository.findHighConfidenceRelationships(
            connectionId, BigDecimal.valueOf(25));

        if (!mentionedTables.isEmpty()) {
            inferred = inferred.stream()
                .filter(rel -> matchesMentionedTable(mentionedTables, rel.getSourceTable())
                    || matchesMentionedTable(mentionedTables, rel.getTargetTable()))
                .toList();
        }

        if (!inferred.isEmpty()) {
            List<String> lines = inferred.stream()
                .limit(8)
                .map(rel -> String.format(
                    "- `%s.%s` -> `%s.%s` (%s confidence, observed %dx)",
                    rel.getSourceTable(),
                    rel.getSourceColumn(),
                    rel.getTargetTable(),
                    rel.getTargetColumn(),
                    rel.getConfidenceScore() != null ? rel.getConfidenceScore().stripTrailingZeros().toPlainString() + "%" : "high",
                    safeInt(rel.getJoinCount())
                ))
                .toList();

            if (mentionedTables.size() >= 2 && lines.isEmpty()) {
                return String.format(
                    "I could not find a direct stored relationship between `%s` and `%s`.",
                    mentionedTables.get(0),
                    mentionedTables.get(1)
                );
            }

            return "Stored relationship metadata:\n" + String.join("\n", lines);
        }

        List<String> schemaRelationships = new ArrayList<>();
        if (schema.getRelationships() != null) {
            schemaRelationships = schema.getRelationships().stream()
                .filter(rel -> mentionedTables.isEmpty()
                    || matchesMentionedTable(mentionedTables, rel.getFromTable())
                    || matchesMentionedTable(mentionedTables, rel.getToTable()))
                .limit(8)
                .map(rel -> String.format(
                    "- `%s.%s` -> `%s.%s`%s",
                    rel.getFromTable(),
                    rel.getFromColumn(),
                    rel.getToTable(),
                    rel.getToColumn(),
                    rel.getRelationshipType() != null ? " (" + rel.getRelationshipType() + ")" : ""
                ))
                .toList();
        }

        if (!schemaRelationships.isEmpty()) {
            return "Cached schema relationships:\n" + String.join("\n", schemaRelationships);
        }

        if (mentionedTables.size() >= 2) {
            return String.format(
                "I could not find a direct relationship between `%s` and `%s` in the stored metadata.",
                mentionedTables.get(0),
                mentionedTables.get(1)
            );
        }

        return "I do not have stored relationship metadata for this connection yet.";
    }

    private String retiredDirectGrowthAnswer(String message, String connectionId, SchemaMetadata schema) {
        if (message == null || connectionId == null || schema == null) {
            return null;
        }

        String lowerMessage = extractActualUserQuestion(message).toLowerCase(Locale.ROOT).trim();
        List<String> mentionedTables = findMentionedTables(lowerMessage, schema);

        List<GrowthAnomaly> anomalies = growthAnomalyRepository.findRecentAnomalies(
            connectionId, LocalDateTime.now().minusDays(90));
        if (!mentionedTables.isEmpty()) {
            anomalies = anomalies.stream()
                .filter(a -> matchesMentionedTable(mentionedTables, a.getTableName()))
                .toList();
        }

        if (!anomalies.isEmpty()) {
            List<String> lines = anomalies.stream()
                .limit(5)
                .map(anomaly -> {
                    String growth = anomaly.getSizeGrowthPercent() != null
                        ? String.format("%.1f%% size growth", anomaly.getSizeGrowthPercent())
                        : anomaly.getRowCountGrowth() != null
                            ? String.format("%,d row growth", anomaly.getRowCountGrowth())
                            : "growth anomaly";
                    return String.format(
                        "- `%s`: %s (%s)",
                        anomaly.getTableName(),
                        growth,
                        anomaly.getSeverity()
                    );
                })
                .toList();
            return "Recent table growth signals:\n" + String.join("\n", lines);
        }

        List<TableStatsHistory> latestSnapshots = tableStatsHistoryRepository.findLatestSnapshotsForConnection(connectionId);
        if (!mentionedTables.isEmpty()) {
            latestSnapshots = latestSnapshots.stream()
                .filter(snapshot -> matchesMentionedTable(mentionedTables, snapshot.getTableName()))
                .toList();
        }

        List<TableStatsHistory> rankedGrowth = latestSnapshots.stream()
            .filter(snapshot -> snapshot.getSizeGrowthBytes() != null || snapshot.getRowCountGrowth() != null)
            .sorted(Comparator
                .comparingLong((TableStatsHistory snapshot) -> snapshot.getRowCountGrowth() != null
                    ? snapshot.getRowCountGrowth()
                    : Long.MIN_VALUE)
                .thenComparingLong(snapshot -> snapshot.getSizeGrowthBytes() != null
                    ? snapshot.getSizeGrowthBytes()
                    : Long.MIN_VALUE)
                .reversed())
            .limit(5)
            .toList();

        if (!rankedGrowth.isEmpty()) {
            List<String> lines = rankedGrowth.stream()
                .map(snapshot -> {
                    String rowGrowth = snapshot.getRowCountGrowth() != null
                        ? String.format("%,d rows", snapshot.getRowCountGrowth())
                        : "n/a";
                    String percent = snapshot.getRowCountGrowthPercent() != null
                        ? String.format(" (%.1f%%)", snapshot.getRowCountGrowthPercent())
                        : "";
                    return String.format("- `%s`: +%s%s", snapshot.getTableName(), rowGrowth, percent);
                })
                .toList();
            return "Fastest-growing tables from stored growth snapshots:\n" + String.join("\n", lines);
        }

        return "I do not have stored growth snapshots for this connection yet, so I cannot rank table growth without collecting metadata first.";
    }

    private String retiredDirectSchemaClassificationAnswer(String message, String connectionId, SchemaMetadata schema) {
        if (message == null || connectionId == null) {
            return null;
        }

        String lowerMessage = extractActualUserQuestion(message).toLowerCase(Locale.ROOT).trim();
        var classificationOpt = schemaClassificationService.getLatestClassification(connectionId);
        if (classificationOpt.isEmpty()) {
            return "I do not have stored schema classification for this connection yet, so table roles like fact, dimension, and business-domain groupings are not available.";
        }

        List<TableClassification> tables = tableClassificationRepository.findLatestByConnectionIdOrderByTableNameAsc(connectionId);
        if (tables.isEmpty()) {
            return "I have schema classification metadata, but no table role details are stored yet for this connection.";
        }

        boolean largestQuestion = lowerMessage.matches(".*\\b(largest|biggest|top|heaviest)\\b.*");
        boolean asksPatternSummary = lowerMessage.contains("pattern")
            || (lowerMessage.contains("fact") && lowerMessage.contains("dimension"));
        if (largestQuestion) {
            if (lowerMessage.contains("fact")) {
                return buildRankedRoleResponse("Largest FACT tables", tables, "FACT", schema, lowerMessage);
            }
            if (lowerMessage.contains("dimension")) {
                return buildRankedRoleResponse("Largest DIMENSION tables", tables, "DIMENSION", schema, lowerMessage);
            }
            if (lowerMessage.contains("bridge")) {
                return buildRankedRoleResponse("Largest BRIDGE tables", tables, "BRIDGE", schema, lowerMessage);
            }
            if (lowerMessage.contains("lookup")) {
                return buildRankedRoleResponse("Largest LOOKUP tables", tables, "LOOKUP", schema, lowerMessage);
            }
            if (lowerMessage.contains("orphaned")) {
                return buildRankedRoleResponse("Largest ORPHANED tables", tables, "ORPHANED", schema, lowerMessage);
            }
        }

        if (asksPatternSummary) {
            var classification = classificationOpt.get();
            List<String> factTables = tables.stream()
                .filter(table -> "FACT".equalsIgnoreCase(table.getTableRole()))
                .limit(5)
                .map(TableClassification::getTableName)
                .toList();
            List<String> dimensionTables = tables.stream()
                .filter(table -> "DIMENSION".equalsIgnoreCase(table.getTableRole()))
                .limit(5)
                .map(TableClassification::getTableName)
                .toList();

            return String.format(
                "Stored schema classification shows a **%s** PATTERN with **%d TABLES**. FACT tables: %s. DIMENSION tables: %s.",
                classification.getGlobalPattern(),
                classification.getTotalTables() != null ? classification.getTotalTables() : tables.size(),
                factTables.isEmpty() ? "none identified" : String.join(", ", factTables),
                dimensionTables.isEmpty() ? "none identified" : String.join(", ", dimensionTables)
            );
        }

        if (lowerMessage.contains("fact")) {
            return buildRoleResponse("FACT tables", tables, "FACT");
        }
        if (lowerMessage.contains("dimension")) {
            return buildRoleResponse("DIMENSION tables", tables, "DIMENSION");
        }
        if (lowerMessage.contains("bridge")) {
            return buildRoleResponse("BRIDGE tables", tables, "BRIDGE");
        }
        if (lowerMessage.contains("lookup")) {
            return buildRoleResponse("LOOKUP tables", tables, "LOOKUP");
        }
        if (lowerMessage.contains("orphaned")) {
            return buildRoleResponse("ORPHANED tables", tables, "ORPHANED");
        }

        var classification = classificationOpt.get();
        List<String> factTables = tables.stream()
            .filter(table -> "FACT".equalsIgnoreCase(table.getTableRole()))
            .limit(5)
            .map(TableClassification::getTableName)
            .toList();
        List<String> dimensionTables = tables.stream()
            .filter(table -> "DIMENSION".equalsIgnoreCase(table.getTableRole()))
            .limit(5)
            .map(TableClassification::getTableName)
            .toList();

        if (lowerMessage.contains("business table")) {
            String facts = factTables.isEmpty() ? "none identified" : String.join(", ", factTables);
            String dimensions = dimensionTables.isEmpty() ? "none identified" : String.join(", ", dimensionTables);
            return "Core business tables from stored schema classification:\n"
                + "- FACT: " + facts + "\n"
                + "- DIMENSION: " + dimensions;
        }

        return String.format(
            "Stored schema classification shows a **%s** PATTERN with **%d TABLES**. FACT tables: %s. DIMENSION tables: %s.",
            classification.getGlobalPattern(),
            classification.getTotalTables() != null ? classification.getTotalTables() : tables.size(),
            factTables.isEmpty() ? "none identified" : String.join(", ", factTables),
            dimensionTables.isEmpty() ? "none identified" : String.join(", ", dimensionTables)
        );
    }

    private String buildRoleResponse(String label, List<TableClassification> tables, String role) {
        List<String> matchingTables = tables.stream()
            .filter(table -> role.equalsIgnoreCase(table.getTableRole()))
            .map(TableClassification::getTableName)
            .filter(Objects::nonNull)
            .distinct()
            .limit(10)
            .toList();

        if (matchingTables.isEmpty()) {
            return "No " + label.toLowerCase(Locale.ROOT) + " are currently identified in stored schema classification.";
        }

        return label + ": " + matchingTables.stream()
            .map(name -> "`" + name + "`")
            .collect(Collectors.joining(", "));
    }

    private String buildRankedRoleResponse(
            String label,
            List<TableClassification> classifications,
            String role,
            SchemaMetadata schema,
            String lowerMessage) {
        Map<String, TableMetadata> schemaByTable = schema == null || schema.getTables() == null
            ? Map.of()
            : schema.getTables().stream()
                .filter(Objects::nonNull)
                .filter(table -> table.getName() != null)
                .collect(Collectors.toMap(
                    table -> table.getName().toLowerCase(Locale.ROOT),
                    table -> table,
                    (left, right) -> left
                ));

        boolean sortBySize = lowerMessage.contains("size")
            || lowerMessage.contains("storage")
            || lowerMessage.contains("disk")
            || lowerMessage.contains("bytes");

        List<TableClassification> matching = classifications.stream()
            .filter(table -> role.equalsIgnoreCase(table.getTableRole()))
            .filter(table -> table.getTableName() != null)
            .collect(Collectors.toMap(
                table -> table.getTableName().toLowerCase(Locale.ROOT),
                table -> table,
                (left, right) -> left,
                LinkedHashMap::new
            ))
            .values()
            .stream()
            .sorted((left, right) -> {
                TableMetadata leftSchema = schemaByTable.get(left.getTableName().toLowerCase(Locale.ROOT));
                TableMetadata rightSchema = schemaByTable.get(right.getTableName().toLowerCase(Locale.ROOT));
                long leftMetric = sortBySize
                    ? resolveClassificationSize(leftSchema)
                    : resolveClassificationRowCount(left, leftSchema);
                long rightMetric = sortBySize
                    ? resolveClassificationSize(rightSchema)
                    : resolveClassificationRowCount(right, rightSchema);
                int metricCompare = Long.compare(rightMetric, leftMetric);
                if (metricCompare != 0) {
                    return metricCompare;
                }
                return left.getTableName().compareToIgnoreCase(right.getTableName());
            })
            .limit(10)
            .toList();

        if (matching.isEmpty()) {
            return "No " + label.toLowerCase(Locale.ROOT) + " are currently identified in stored schema classification.";
        }

        StringBuilder response = new StringBuilder(label).append(":\n");
        for (TableClassification table : matching) {
            TableMetadata schemaTable = schemaByTable.get(table.getTableName().toLowerCase(Locale.ROOT));
            long rowCount = resolveClassificationRowCount(table, schemaTable);
            long sizeBytes = resolveClassificationSize(schemaTable);
            response.append("- `").append(table.getTableName()).append("`");
            if (rowCount > 0) {
                response.append(" — ").append(contextAssembler.formatRowCount(rowCount)).append(" rows");
            }
            if (sortBySize && sizeBytes > 0) {
                response.append(" — ").append(contextAssembler.formatBytes(sizeBytes));
            }
            response.append("\n");
        }
        return response.toString().trim();
    }

    private long resolveClassificationRowCount(TableClassification classification, TableMetadata schemaTable) {
        if (schemaTable != null && schemaTable.getRowCount() != null && schemaTable.getRowCount() > 0) {
            return schemaTable.getRowCount();
        }
        if (classification != null && classification.getRowCount() != null && classification.getRowCount() > 0) {
            return classification.getRowCount();
        }
        return 0L;
    }

    private long resolveClassificationSize(TableMetadata schemaTable) {
        if (schemaTable != null && schemaTable.getSizeBytes() != null && schemaTable.getSizeBytes() > 0) {
            return schemaTable.getSizeBytes();
        }
        return 0L;
    }

    private List<String> findMentionedTables(String question, SchemaMetadata schema) {
        if (question == null || schema == null || schema.getTables() == null || schema.getTables().isEmpty()) {
            return List.of();
        }

        String normalizedQuestion = SchemaTableMatchUtil.normalizeQuestion(question);

        return schema.getTables().stream()
            .map(table -> table.getName())
            .filter(Objects::nonNull)
            .sorted(Comparator.comparingInt(String::length).reversed())
            .filter(tableName -> mentionsTable(normalizedQuestion, tableName))
            .distinct()
            .toList();
    }

    private boolean mentionsTable(String normalizedQuestion, String tableName) {
        return SchemaTableMatchUtil.mentionsTable(normalizedQuestion, tableName);
    }

    private boolean matchesMentionedTable(List<String> mentionedTables, String candidateTableName) {
        if (candidateTableName == null || mentionedTables == null || mentionedTables.isEmpty()) {
            return false;
        }
        return mentionedTables.stream().anyMatch(table -> table.equalsIgnoreCase(candidateTableName));
    }

    private Set<ChatContextAssembler.ContextType> buildMetadataRouteContextTypes(
        String question,
        ChatQuestionRoutingService.QuestionRoute questionRoute
    ) {
        Set<ChatContextAssembler.ContextType> needed = new HashSet<>(contextAssembler.determineNeededContext(question));
        if (questionRoute == null || !questionRoute.isBrainMetadata()) {
            return needed;
        }

        switch (questionRoute.brainTopic()) {
            case KEY_COLUMNS -> {
                needed.add(ChatContextAssembler.ContextType.KEY_COLUMNS);
                needed.add(ChatContextAssembler.ContextType.RELATIONSHIPS);
                needed.add(ChatContextAssembler.ContextType.SEMANTIC_MODEL);
            }
            case RELATIONSHIPS -> {
                needed.add(ChatContextAssembler.ContextType.RELATIONSHIPS);
                needed.add(ChatContextAssembler.ContextType.KEY_COLUMNS);
                needed.add(ChatContextAssembler.ContextType.SEMANTIC_MODEL);
            }
            case PERFORMANCE -> {
                needed.add(ChatContextAssembler.ContextType.SLOW_QUERIES);
                needed.add(ChatContextAssembler.ContextType.REGRESSIONS);
                needed.add(ChatContextAssembler.ContextType.INDEX_RECOMMENDATIONS);
                needed.add(ChatContextAssembler.ContextType.KEY_COLUMNS);
                needed.add(ChatContextAssembler.ContextType.SEMANTIC_MODEL);
            }
            case GROWTH -> needed.add(ChatContextAssembler.ContextType.GROWTH);
            case CLASSIFICATION -> {
                needed.add(ChatContextAssembler.ContextType.CLASSIFICATION);
                needed.add(ChatContextAssembler.ContextType.SEMANTIC_MODEL);
            }
            case WORKLOAD, TUNING -> needed.add(ChatContextAssembler.ContextType.BRAIN_INSIGHTS);
            case SCHEMA, GENERAL -> {
                // Schema questions already rely on cached schema context. Additional sections are optional.
            }
        }

        return needed;
    }

    private int safeInt(Integer value) {
        return value != null ? value : 0;
    }

    private String buildRoutingInstruction(ChatQuestionRoutingService.QuestionRoute questionRoute) {
        if (questionRoute == null) {
            return "";
        }

        if (questionRoute.isBrainMetadata()) {
            return "ROUTING DECISION: This question is about database metadata and stored insights. "
                + "Answer from cached schema, performance, key-column, relationship, growth, workload, and classification context first. "
                + "Do NOT generate SQL against application tables. If SQL fallback is absolutely necessary, query only metadata catalogs such as "
                + "INFORMATION_SCHEMA, performance_schema, pg_catalog, or pg_stat_* views.";
        }

        if (questionRoute.isBiQuery()) {
            return "ROUTING DECISION: This is a BI/data-discovery question. "
                + "Generate SQL against the connected business tables, not metadata catalogs. "
                + "Prefer recent data windows and bounded result sets when possible.";
        }

        return "";
    }

    private String buildBrainMetadataCorrectionPrompt(String question) {
        return "ROUTING CORRECTION. The user asked a metadata/Brain-style question: \"" + question + "\".\n\n"
            + "Do not query application tables and do not produce SQL against business tables. "
            + "Answer from cached schema/performance/classification metadata. "
            + "If SQL fallback is necessary, query only INFORMATION_SCHEMA, performance_schema, pg_catalog, or pg_stat_* metadata views. "
            + "If the stored metadata is insufficient, say that directly instead of guessing.";
    }

    /**
     * Try to answer workload type questions directly from stored workload profile.
     * Handles: "What's my workload type?", "Is this OLTP or OLAP?", "Workload characteristics"
     */
    private String retiredDirectWorkloadAnswer(String message, String connectionId) {
        if (message == null || connectionId == null) {
            return null;
        }

        // Extract the actual user question
        String actualQuestion = extractActualUserQuestion(message);

        String lowerMessage = actualQuestion.toLowerCase().trim();

        // Match workload type questions
        boolean isWorkloadQuestion = lowerMessage.matches(".*(workload|work load).*(type|kind|pattern|characteristic|profile).*") ||
            lowerMessage.matches(".*(what|which).*(type|kind).*(workload|database|db).*") ||
            lowerMessage.matches(".*(is this|is it|am i running).*(oltp|olap|mixed|read|write).*") ||
            lowerMessage.matches(".*(oltp|olap).*(or|vs|versus).*") ||
            lowerMessage.matches(".*(read|write).*(heavy|intensive|dominant).*") ||
            lowerMessage.matches(".*workload\\s+(analysis|summary|overview).*");

        if (!isWorkloadQuestion) {
            return null;
        }

        log.info("Fast path: Detected workload type question for connection {}", connectionId);

        try {
            var profileOpt = workloadProfileRepository.findByConnectionId(connectionId);

            if (profileOpt.isEmpty()) {
                return "### Workload Profile\n\n" +
                    "No workload profile found for this connection.\n\n" +
                    "To generate a workload profile:\n" +
                    "1. Go to **Brain > Overview**\n" +
                    "2. Click **Collect Metrics** to gather database metrics\n" +
                    "3. Click **Characterize Workload** to analyze the workload type";
            }

            var profile = profileOpt.get();
            StringBuilder sb = new StringBuilder();
            sb.append("### Workload Profile\n\n");

            sb.append(String.format("**Workload Type:** %s\n\n", profile.getWorkloadType()));

            if (profile.getWorkloadSubtype() != null) {
                sb.append(String.format("**Subtype:** %s\n", profile.getWorkloadSubtype()));
            }

            if (profile.getClassificationConfidence() != null) {
                sb.append(String.format("**Confidence:** %.0f%%\n\n", profile.getClassificationConfidence()));
            }

            // Performance metrics table
            sb.append("| Metric | Value |\n");
            sb.append("|--------|-------|\n");

            if (profile.getThroughputQps() != null) {
                sb.append(String.format("| Throughput | %.1f QPS |\n", profile.getThroughputQps()));
            }
            if (profile.getLatencyP50Ms() != null) {
                sb.append(String.format("| P50 Latency | %.1f ms |\n", profile.getLatencyP50Ms()));
            }
            if (profile.getLatencyP99Ms() != null) {
                sb.append(String.format("| P99 Latency | %.1f ms |\n", profile.getLatencyP99Ms()));
            }
            if (profile.getPerformanceScore() != null) {
                sb.append(String.format("| Performance Score | %.0f/100 |\n", profile.getPerformanceScore()));
            }

            // Workload type descriptions
            sb.append("\n**What this means:**\n");
            switch (profile.getWorkloadType()) {
                case OLTP -> sb.append("- High concurrency, short transactions\n" +
                    "- Optimize for: connection pooling, index efficiency, low latency\n" +
                    "- Key settings: `max_connections`, `shared_buffers`, `effective_cache_size`");
                case OLAP -> sb.append("- Complex analytical queries, aggregations\n" +
                    "- Optimize for: parallel query execution, large memory buffers\n" +
                    "- Key settings: `work_mem`, `parallel_tuple_cost`, `max_parallel_workers`");
                case MIXED -> sb.append("- Combination of transactional and analytical workloads\n" +
                    "- Consider: read replicas for analytics, query prioritization\n" +
                    "- Balance between OLTP and OLAP tuning strategies");
                case WRITE_HEAVY -> sb.append("- Predominantly write operations (INSERT/UPDATE/DELETE)\n" +
                    "- Optimize for: WAL settings, checkpoint frequency, bulk loading\n" +
                    "- Key settings: `wal_buffers`, `checkpoint_completion_target`");
                case READ_HEAVY -> sb.append("- Predominantly read operations (SELECT)\n" +
                    "- Optimize for: caching, index coverage, read replicas\n" +
                    "- Key settings: `shared_buffers`, `effective_cache_size`");
                case BATCH -> sb.append("- Batch processing workloads\n" +
                    "- Optimize for: bulk operations, maintenance windows\n" +
                    "- Consider: partitioning, parallel processing");
                default -> sb.append("- Workload pattern not yet fully characterized");
            }

            if (profile.getLastUpdatedAt() != null) {
                sb.append(String.format("\n\n*Profile updated: %s*", profile.getLastUpdatedAt()));
            }

            log.info("Fast path: Returning workload profile (type: {})", profile.getWorkloadType());
            return sb.toString();

        } catch (Exception e) {
            log.debug("Failed to get workload profile for fast path: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extract the user-authored question when frontend wraps the message with tab instructions.
     */
    private String extractActualUserQuestion(String message) {
        if (message == null) {
            return "";
        }

        Matcher matcher = USER_REQUEST_PATTERN.matcher(message);
        if (matcher.find()) {
            String extracted = matcher.group(1) != null ? matcher.group(1).trim() : "";
            if (!extracted.isEmpty()) {
                return extracted;
            }
        }

        int userRequestIdx = message.toUpperCase().lastIndexOf("USER REQUEST:");
        if (userRequestIdx >= 0) {
            String extracted = message.substring(userRequestIdx + "USER REQUEST:".length()).trim();
            if (!extracted.isEmpty()) {
                return extracted;
            }
        }

        return message.trim();
    }

    private String extractWrappedMessageContext(String message) {
        if (message == null || message.isBlank()) {
            return "";
        }

        Matcher matcher = USER_REQUEST_PATTERN.matcher(message);
        if (matcher.find()) {
            String wrappedContext = message.substring(0, matcher.start()).trim();
            if (!wrappedContext.isEmpty()) {
                // Guardrail to avoid unbounded prompt growth on malformed payloads.
                int maxChars = 12000;
                return wrappedContext.length() <= maxChars
                    ? wrappedContext
                    : wrappedContext.substring(0, maxChars);
            }
        }

        return "";
    }

    private RetrievalIntent detectRetrievalIntent(String userQuestion) {
        if (userQuestion == null || userQuestion.isBlank()) {
            return RetrievalIntent.GENERAL;
        }

        String lower = userQuestion.toLowerCase();
        if (lower.contains("valid values") ||
            lower.contains("allowed values") ||
            lower.contains("possible values") ||
            lower.contains("status values") ||
            lower.contains("enum") ||
            lower.contains("picklist") ||
            lower.contains("dropdown")) {
            return RetrievalIntent.VALUE_LOOKUP;
        }

        if (lower.contains("business meaning") ||
            lower.contains("what does") && lower.contains("mean") ||
            lower.contains("definition") ||
            lower.contains("semantics") ||
            lower.contains("domain meaning")) {
            return RetrievalIntent.BUSINESS_MEANING;
        }

        if (lower.contains("example query") ||
            lower.contains("sample query") ||
            lower.contains("sql example") ||
            lower.contains("write sql") ||
            lower.contains("how to query")) {
            return RetrievalIntent.SQL_EXAMPLE;
        }

        return RetrievalIntent.GENERAL;
    }

    private int resolveRetrievalTopK(RetrievalIntent intent) {
        int generalTopK = Math.max(1, ragGeneralTopK);
        int intentTopK = Math.max(generalTopK, ragIntentTopK);
        return intent == RetrievalIntent.GENERAL ? generalTopK : intentTopK;
    }

    /**
     * Extract table names from RAG retrieval results so they can inform schema context selection.
     * Parses the metadata JSON of each result to find table name references from multiple fields:
     * - tableName (SCHEMA_DDL, COLUMN_VALUES)
     * - objectName (DOCUMENTATION — may be schema-qualified like "schema.TABLE")
     * - parentObject (DOCUMENTATION column docs — references the parent table)
     * - tablesUsed (QUERY_EXAMPLE — comma-separated list)
     */
    private Set<String> extractTableNamesFromRag(List<TrainingDataEmbedding> ragResults) {
        Set<String> tables = new HashSet<>();
        if (ragResults == null) return tables;
        for (TrainingDataEmbedding item : ragResults) {
            String meta = item.getMetadata();
            if (meta == null || meta.isBlank()) continue;
            try {
                var node = objectMapper.readTree(meta);
                addTableName(tables, node, "tableName");
                // Only use objectName as table when it's not a column-level doc
                String objectType = node.has("objectType") ? node.get("objectType").asText() : "";
                if (!"COLUMN".equalsIgnoreCase(objectType)) {
                    addTableName(tables, node, "objectName");
                }
                addTableName(tables, node, "parentObject");
                if (node.has("tablesUsed")) {
                    String tablesUsed = node.get("tablesUsed").asText();
                    if (tablesUsed != null && !tablesUsed.isBlank()) {
                        for (String t : tablesUsed.split(",")) {
                            String trimmed = t.trim();
                            if (!trimmed.isEmpty()) {
                                tables.add(stripSchemaPrefix(trimmed));
                            }
                        }
                    }
                }
                if (node.has("linkedTables") && node.get("linkedTables").isArray()) {
                    for (var linkedTable : node.get("linkedTables")) {
                        addTableName(tables, linkedTable.asText());
                    }
                }
                if (node.has("linkedColumns") && node.get("linkedColumns").isArray()) {
                    for (var linkedColumn : node.get("linkedColumns")) {
                        String columnReference = linkedColumn.asText();
                        int lastDot = columnReference != null ? columnReference.lastIndexOf('.') : -1;
                        if (lastDot > 0) {
                            addTableName(tables, columnReference.substring(0, lastDot));
                        }
                    }
                }
            } catch (Exception ignored) {
                // Non-JSON metadata or parse error — skip silently
            }
        }
        return tables;
    }

    private void addTableName(Set<String> tables, com.fasterxml.jackson.databind.JsonNode node, String field) {
        if (!node.has(field)) return;
        String val = node.get(field).asText();
        if (val == null || val.isBlank()) return;
        tables.add(stripSchemaPrefix(val));
    }

    private void addTableName(Set<String> tables, String tableReference) {
        if (tableReference == null || tableReference.isBlank()) {
            return;
        }
        tables.add(stripSchemaPrefix(tableReference));
    }

    /** Strip schema prefix (e.g., "idb_database.HOTEL_SERVICES" → "HOTEL_SERVICES") */
    private String stripSchemaPrefix(String name) {
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1) : name;
    }

    private List<TrainingDataEmbedding> prioritizeTrainingDataByIntent(
            List<TrainingDataEmbedding> retrieved,
            RetrievalIntent intent) {
        if (retrieved == null || retrieved.isEmpty() || intent == RetrievalIntent.GENERAL) {
            return retrieved;
        }

        Map<TrainingDataEmbedding.TrainingDataType, Integer> priority = new HashMap<>();
        if (intent == RetrievalIntent.VALUE_LOOKUP) {
            priority.put(TrainingDataEmbedding.TrainingDataType.COLUMN_VALUES, 1);
            priority.put(TrainingDataEmbedding.TrainingDataType.DOCUMENTATION, 2);
            priority.put(TrainingDataEmbedding.TrainingDataType.BUSINESS_TERM, 3);
            priority.put(TrainingDataEmbedding.TrainingDataType.COMPANY_KNOWLEDGE, 4);
            priority.put(TrainingDataEmbedding.TrainingDataType.QUERY_EXAMPLE, 5);
            priority.put(TrainingDataEmbedding.TrainingDataType.SCHEMA_DDL, 6);
            priority.put(TrainingDataEmbedding.TrainingDataType.RELATIONSHIP, 7);
        } else if (intent == RetrievalIntent.BUSINESS_MEANING) {
            priority.put(TrainingDataEmbedding.TrainingDataType.DOCUMENTATION, 1);
            priority.put(TrainingDataEmbedding.TrainingDataType.BUSINESS_TERM, 2);
            priority.put(TrainingDataEmbedding.TrainingDataType.COMPANY_KNOWLEDGE, 3);
            priority.put(TrainingDataEmbedding.TrainingDataType.COLUMN_VALUES, 4);
            priority.put(TrainingDataEmbedding.TrainingDataType.SCHEMA_DDL, 5);
            priority.put(TrainingDataEmbedding.TrainingDataType.QUERY_EXAMPLE, 6);
            priority.put(TrainingDataEmbedding.TrainingDataType.RELATIONSHIP, 7);
        } else if (intent == RetrievalIntent.SQL_EXAMPLE) {
            priority.put(TrainingDataEmbedding.TrainingDataType.QUERY_EXAMPLE, 1);
            priority.put(TrainingDataEmbedding.TrainingDataType.RELATIONSHIP, 2);
            priority.put(TrainingDataEmbedding.TrainingDataType.DOCUMENTATION, 3);
            priority.put(TrainingDataEmbedding.TrainingDataType.COMPANY_KNOWLEDGE, 4);
            priority.put(TrainingDataEmbedding.TrainingDataType.COLUMN_VALUES, 5);
            priority.put(TrainingDataEmbedding.TrainingDataType.SCHEMA_DDL, 6);
            priority.put(TrainingDataEmbedding.TrainingDataType.BUSINESS_TERM, 7);
        }

        return retrieved.stream()
            .sorted(
                Comparator.comparingInt((TrainingDataEmbedding item) ->
                    priority.getOrDefault(item.getType(), 999))
                    .thenComparing(
                        TrainingDataEmbedding::getScore,
                        Comparator.nullsLast(Comparator.reverseOrder())
                    )
            )
            .collect(Collectors.toList());
    }

    /**
     * Check if the question contains qualifiers that require SQL to answer correctly.
     * These include schema-scoped, temporal, or conditional questions.
     * Examples that should return true:
     * - "how many tables in schema X" (schema-scoped)
     * - "how many tables created last week" (temporal)
     * - "how many tables with more than 1000 rows" (conditional)
     */
    private boolean hasScopedOrTemporalQualifiers(String lowerMessage) {
        // Schema qualifiers
        if (lowerMessage.matches(".*\\b(in schema|in the .* schema|schema\\s+\\w+)\\b.*")) {
            return true;
        }

        // Table-scoped qualifiers (e.g., "indexes on users", "columns in orders", "for table X")
        if (lowerMessage.matches(".*\\b(on|in|for|of)\\s+(the\\s+)?\\w+\\s*(table)?\\b.*") &&
            (lowerMessage.contains("index") || lowerMessage.contains("column") ||
             lowerMessage.contains("constraint") || lowerMessage.contains("foreign key"))) {
            return true;
        }

        // Temporal qualifiers
        if (lowerMessage.matches(".*\\b(today|yesterday|last week|last month|this week|this month|since|after|before|created|added|modified|updated|recent|new)\\b.*")) {
            return true;
        }

        // Conditional qualifiers
        if (lowerMessage.matches(".*\\b(where|with|that have|that are|containing|larger than|smaller than|more than|less than|greater|empty|non-empty)\\b.*")) {
            return true;
        }

        // Specific object references (e.g., "tables like X", "tables starting with")
        if (lowerMessage.matches(".*\\b(like|starting with|ending with|matching|named|called)\\b.*")) {
            return true;
        }

        return false;
    }

    /**
     * Check if this is a simple schema question that doesn't need RAG retrieval.
     */
    private boolean isSimpleSchemaQuestion(String message) {
        if (message == null) return false;
        String lower = extractActualUserQuestion(message).toLowerCase();

        if (isExactTableColumnCountQuestion(lower)
            || isExactTableColumnListQuestion(lower)
            || isExactTableRowCountQuestion(lower)
            || isExactTableIndexQuestion(lower)) {
            return true;
        }

        // Don't skip RAG if the question has scoped/temporal qualifiers - those need context
        if (hasScopedOrTemporalQualifiers(lower)) {
            return false;
        }

        return lower.matches(".*(how many|count|number of).*(tables?|views?).*") ||
               lower.matches("^(list|show|what are).*tables?$") ||
               lower.equals("tables") || lower.equals("show tables") ||
               lower.matches(".*(database|total).*(size|big).*");
    }

    public ChatResponse processMessage(String connectionId, String message, String threadId) {
        return processTextMessage(connectionId, message, threadId, null, null);
    }

    public ChatResponse processMessage(String connectionId, String message, String threadId, String userId) {
        return processTextMessage(connectionId, message, threadId, userId, null);
    }

    public ChatResponse processMessage(String connectionId, String message, String threadId, String userId, String chatId) {
        return processTextMessage(connectionId, message, threadId, userId, chatId);
    }

    public ChatResponse processImageMessage(String connectionId, String message, String threadId, String userId, String imageData) {
        return new ChatResponse("Image analysis is not supported in V1.", false);
    }

    public ChatResponse processMessage(String connectionId, String message, String threadId, String userId, String imageData, String chatId, String projectId) {
        if (imageData != null && !imageData.isBlank()) {
            return new ChatResponse("Image analysis is not supported in V1.", false);
        }
        return processTextMessage(connectionId, message, threadId, userId, chatId);
    }

    private ChatResponse processTextMessage(String connectionId, String message, String threadId, String userId, String chatId) {
        String actorUsername = resolveExecutionActorUsername(userId);
        boolean actorIsAdmin = isAdminUser(actorUsername);
        return QueryActorContextHolder.withActor(
            actorUsername,
            () -> executeAgenticChatTurn(connectionId, message, chatId, actorUsername, actorIsAdmin, AgentProgressListener.noop())
        );
    }

    @Deprecated(forRemoval = true)
    private ChatResponse retiredProcessMessage(String connectionId, String message, String threadId, String userId, String imageData, String chatId, String projectId) {
        return retiredProcessMessage(connectionId, message, threadId, userId, imageData, chatId, projectId, true);
    }

    private ChatResponse retiredProcessMessage(String connectionId, String message, String threadId, String userId, String imageData, String chatId, String projectId, boolean allowAgentic) {
        try {
            long startTime = System.currentTimeMillis();
            String actualUserQuestion = extractActualUserQuestion(message);
            RetrievalIntent retrievalIntent = detectRetrievalIntent(actualUserQuestion);
            String explicitBusinessRule = extractBusinessRuleTeaching(actualUserQuestion);
            ChatQuestionRoutingService.QuestionRoute questionRoute = chatQuestionRoutingService.classify(actualUserQuestion);
            PromptIntent promptIntent = promptIntentAnalyzer.analyze(
                actualUserQuestion,
                actualUserQuestion,
                ResolvedConversationContext.empty(),
                questionRoute
            );
            boolean metadataRoute = questionRoute.isBrainMetadata();
            boolean biQueryRoute = questionRoute.isBiQuery();
            log.info("Chat routing: type={}, topic={} for question='{}'",
                questionRoute.type(),
                questionRoute.brainTopic(),
                actualUserQuestion.length() > 80 ? actualUserQuestion.substring(0, 80) + "..." : actualUserQuestion);

            if (allowAgentic && !metadataRoute && (imageData == null || imageData.isEmpty())) {
                AgentDecision earlyAgenticDecision = agentOrchestrator.previewDecision(agenticEnabled, actualUserQuestion, questionRoute);
                if (earlyAgenticDecision == null) {
                    earlyAgenticDecision = AgentDecision.none();
                }
                if (earlyAgenticDecision.useAgenticFlow() && !agentOrchestrator.requiresSchema(earlyAgenticDecision.intent())) {
                    var earlyAgenticResult = agentOrchestrator.execute(
                        agenticEnabled,
                        connectionId,
                        actualUserQuestion,
                        null,
                        earlyAgenticDecision,
                        promptIntent
                    );
                    if (earlyAgenticResult.isPresent()) {
                        log.info("AGENTIC PATH (schema-free): Answered '{}' in {}ms",
                            actualUserQuestion.length() > 50 ? actualUserQuestion.substring(0, 50) + "..." : actualUserQuestion,
                            System.currentTimeMillis() - startTime);
                        return buildAgenticResponse(
                            connectionId,
                            chatId,
                            actualUserQuestion,
                            actualUserQuestion,
                            questionRoute,
                            ResolvedConversationContext.empty(),
                            earlyAgenticResult.get(),
                            ConversationCarryoverDecision.empty(),
                            accessControlService.isCurrentUserAdmin(),
                            System.currentTimeMillis() - startTime
                        );
                    }
                }

                SchemaMetadata schema = schemaScannerService.scanSchema(connectionId);
                AgentDecision schemaAwareDecision = earlyAgenticDecision.useAgenticFlow()
                    ? earlyAgenticDecision
                    : agentOrchestrator.previewDecision(agenticEnabled, actualUserQuestion, questionRoute);
                if (schemaAwareDecision == null) {
                    schemaAwareDecision = AgentDecision.none();
                }

                var schemaAwareAgenticResult = agentOrchestrator.execute(
                    agenticEnabled,
                    connectionId,
                    actualUserQuestion,
                    schema,
                    schemaAwareDecision,
                    promptIntent
                );
                if (schemaAwareAgenticResult.isPresent()) {
                    log.info("AGENTIC PATH (schema-aware): Answered '{}' in {}ms",
                        actualUserQuestion.length() > 50 ? actualUserQuestion.substring(0, 50) + "..." : actualUserQuestion,
                        System.currentTimeMillis() - startTime);
                    return buildAgenticResponse(
                        connectionId,
                        chatId,
                        actualUserQuestion,
                        actualUserQuestion,
                        questionRoute,
                        ResolvedConversationContext.empty(),
                        schemaAwareAgenticResult.get(),
                        ConversationCarryoverDecision.empty(),
                        accessControlService.isCurrentUserAdmin(),
                        System.currentTimeMillis() - startTime
                    );
                }
                log.warn("Agentic flow produced no result for '{}' inside retired pipeline; continuing with deterministic fallback",
                    actualUserQuestion.length() > 80 ? actualUserQuestion.substring(0, 80) + "..." : actualUserQuestion);
            }

            // 1. Get Schema Context (needed for metadata fast path and schema-aware flows)
            SchemaMetadata schema = schemaScannerService.scanSchema(connectionId);
            log.debug("Schema scan completed in {}ms", System.currentTimeMillis() - startTime);

            // 2. FAST PATH: Check if we can answer directly from cached metadata (no LLM needed)
            // This handles simple questions like "how many tables?" in ~50ms instead of ~25s
            if (imageData == null || imageData.isEmpty()) {
                VerifiedAnswer verifiedAnswer = resolveVerifiedMetadataAnswer(
                    connectionId,
                    actualUserQuestion,
                    questionRoute,
                    promptIntent,
                    schema,
                    ResolvedConversationContext.empty(),
                    metadataRequestScopeResolver.resolve(actualUserQuestion, schema, questionRoute, promptIntent)
                );
                MetadataRequestScope requestScope = metadataRequestScopeResolver.resolve(actualUserQuestion, schema, questionRoute, promptIntent);
                if (verifiedAnswer != null && requestScope.isStrictFact()) {
                    log.info("FAST PATH: Answered '{}' directly from schema cache in {}ms",
                        actualUserQuestion.length() > 50 ? actualUserQuestion.substring(0, 50) + "..." : actualUserQuestion,
                        System.currentTimeMillis() - startTime);
                    return buildUnifiedMetadataResponse(
                        connectionId,
                        chatId,
                        actualUserQuestion,
                        actualUserQuestion,
                        questionRoute,
                        promptIntent,
                        ResolvedConversationContext.empty(),
                        ConversationCarryoverDecision.empty(),
                        verifiedAnswer
                    );
                }

                var agenticResult = agentOrchestrator.maybeExecute(agenticEnabled, connectionId, actualUserQuestion, schema, questionRoute, promptIntent);
                if (agenticResult.isPresent()) {
                    log.info("AGENTIC PATH: Answered '{}' in {}ms",
                        actualUserQuestion.length() > 50 ? actualUserQuestion.substring(0, 50) + "..." : actualUserQuestion,
                        System.currentTimeMillis() - startTime);
                    return buildAgenticResponse(
                        connectionId,
                        chatId,
                        actualUserQuestion,
                        actualUserQuestion,
                        questionRoute,
                        ResolvedConversationContext.empty(),
                        agenticResult.get(),
                        ConversationCarryoverDecision.empty(),
                        accessControlService.isCurrentUserAdmin(),
                        System.currentTimeMillis() - startTime
                    );
                }
            }

            // 3. Determine what context is needed based on the question (lazy loading)
            Set<ChatContextAssembler.ContextType> neededContext = contextAssembler.determineNeededContext(actualUserQuestion);
            log.info("Context types needed: {} for question: {}", neededContext.isEmpty() ? "MINIMAL" : neededContext,
                actualUserQuestion.length() > 50 ? actualUserQuestion.substring(0, 50) + "..." : actualUserQuestion);

            // 4. Parallelize independent context retrieval operations
            // RAG, classification, performance, brain, guardrails/feedback are mostly independent.
            // RAG must complete before schema context (RAG-found tables inform schema window).
            final String fExplicitBusinessRule = explicitBusinessRule;
            final Set<ChatContextAssembler.ContextType> fNeededContext = neededContext;

            // RAG retrieval (must complete before schema context)
            CompletableFuture<RetrievedContextResult> ragFuture = CompletableFuture.supplyAsync(() ->
                metadataRoute
                    ? RetrievedContextResult.skipped(
                        chatRetrievalContextService.detectRetrievalIntent(actualUserQuestion),
                        "brain_metadata_route"
                    )
                    : chatRetrievalContextService.buildContext(connectionId, actualUserQuestion, schema));

            // Classification context (independent)
            CompletableFuture<String> classificationFuture = CompletableFuture.supplyAsync(() ->
                fNeededContext.contains(ChatContextAssembler.ContextType.CLASSIFICATION)
                    ? contextAssembler.buildClassificationContext(connectionId) : "");

            // Performance context (independent)
            CompletableFuture<String> performanceFuture = CompletableFuture.supplyAsync(() ->
                contextAssembler.buildPerformanceInsightsContext(connectionId, fNeededContext, actualUserQuestion));

            // Brain context (independent)
            CompletableFuture<String> brainFuture = CompletableFuture.supplyAsync(() ->
                fNeededContext.contains(ChatContextAssembler.ContextType.BRAIN_INSIGHTS)
                    ? contextAssembler.buildBrainContext(connectionId) : "");

            // Guardrails + feedback (independent)
            CompletableFuture<String> feedbackFuture = CompletableFuture.supplyAsync(() -> {
                List<BusinessRuleMemoryService.SqlGuardrail> guardrails = new ArrayList<>(
                    businessRuleMemoryService.resolveApplicableGuardrails(connectionId, actualUserQuestion, schema)
                );
                if (!fExplicitBusinessRule.isEmpty()) {
                    guardrails.addAll(
                        businessRuleMemoryService.extractGuardrailsFromText(fExplicitBusinessRule, null, null, schema)
                    );
                    guardrails = dedupeGuardrails(guardrails);
                }
                String fb = feedbackService.buildFeedbackContext(connectionId);
                String gc = businessRuleMemoryService.buildGuardrailContext(guardrails);
                if (!gc.isEmpty()) fb = fb + gc;
                if (!fExplicitBusinessRule.isEmpty()) {
                    fb = fb + "\n- [CURRENT USER CORRECTION] " + fExplicitBusinessRule + "\n";
                }
                return fb;
            });

            // Wait for all parallel tasks
            CompletableFuture.allOf(ragFuture, classificationFuture, performanceFuture,
                brainFuture, feedbackFuture).join();

            RetrievedContextResult ragContext = ragFuture.join();
            String trainingContext = ragContext.trainingContext();
            String companyKnowledgeContext = ragContext.companyKnowledgeContext();
            Set<String> ragTableNames = ragContext.ragTableNames();
            String classificationContext = classificationFuture.join();
            String performanceContext = performanceFuture.join();
            String brainContext = brainFuture.join();
            String feedbackContext = feedbackFuture.join();
            String semanticContext = fNeededContext.contains(ChatContextAssembler.ContextType.SEMANTIC_MODEL)
                ? contextAssembler.buildSemanticModelContext(connectionId, actualUserQuestion, ragTableNames)
                : "";
            if (!semanticContext.isBlank()) {
                classificationContext = semanticContext + "\n" + classificationContext;
            }

            // Build schema context with RAG-informed table selection (depends on RAG result)
            String schemaContext = contextAssembler.buildSchemaContext(connectionId, schema, actualUserQuestion, ragTableNames);

            // Resolve guardrails (already done in feedbackFuture, but we need the list for SQL validation later)
            List<BusinessRuleMemoryService.SqlGuardrail> applicableGuardrails = new ArrayList<>(
                businessRuleMemoryService.resolveApplicableGuardrails(connectionId, actualUserQuestion, schema)
            );
            if (!explicitBusinessRule.isEmpty()) {
                applicableGuardrails.addAll(
                    businessRuleMemoryService.extractGuardrailsFromText(explicitBusinessRule, null, null, schema)
                );
                applicableGuardrails = dedupeGuardrails(applicableGuardrails);
            }

            if (!feedbackContext.isEmpty()) {
                log.debug("Including {} chars of feedback context", feedbackContext.length());
            }

            log.info("Total context building time: {}ms", System.currentTimeMillis() - startTime);

            // 9. Prepare Messages with enhanced context
            // Always start with the fresh system prompt
            List<Message> messagesToSend = new ArrayList<>();
            String dbType = schema.getDbType();
            String dbSpecificRules = contextAssembler.buildDatabaseSpecificRules(dbType);

            // Apply token budget — log per-section sizes and truncate if over limit.
            // Truncation priority: training > brain > performance > classification > feedback.
            // Schema and DB rules are never truncated.
            // Initial token budget pass (pipeline results will re-apply below)
            String[] budgetedSections = contextAssembler.applyTokenBudget(
                schemaContext, classificationContext, performanceContext,
                brainContext, feedbackContext, companyKnowledgeContext, trainingContext, dbSpecificRules,
                "", ""
            );
            schemaContext = budgetedSections[0];
            classificationContext = budgetedSections[1];
            performanceContext = budgetedSections[2];
            brainContext = budgetedSections[3];
            feedbackContext = budgetedSections[4];
            companyKnowledgeContext = budgetedSections[5];
            trainingContext = budgetedSections[6];

            // Build initial system prompt (will be replaced with enriched prompt after pipeline)
            String systemPrompt = buildSystemPromptFromTemplate(
                dbType,
                dbSpecificRules,
                schemaContext,
                classificationContext,
                performanceContext,
                brainContext,
                feedbackContext,
                companyKnowledgeContext,
                trainingContext,
                "",
                ""
            );

            // Enhance system prompt if image is provided
            if (imageData != null && !imageData.isEmpty()) {
                systemPrompt += "\n\nCRITICAL: The user has provided a screenshot/image with this message. You MUST analyze the image carefully. Describe everything you see including: tables with column names and values, charts/graphs with their data points, metrics displayed with their labels and numbers, UI elements, buttons, and any visible text. The image data is included as a base64-encoded data URL in the user's message. Use this visual information to provide accurate, context-aware responses.";
            }
            
            messagesToSend.add(new SystemMessage(systemPrompt));
            String routingInstruction = buildRoutingInstruction(questionRoute);
            if (!routingInstruction.isBlank()) {
                messagesToSend.add(new SystemMessage(routingInstruction));
            }
            String wrappedContext = extractWrappedMessageContext(message);
            if (!wrappedContext.isEmpty()) {
                messagesToSend.add(new SystemMessage(
                    "Additional tab-specific context supplied by UI:\n" + wrappedContext
                ));
            }

            // Handle chat session - use active chat for single-threaded approach
            String actualChatId = chatId;

            if (actualChatId == null) {
                // Auto-use active chat for the connection (single-threaded approach)
                var activeChat = chatHistoryService.getOrCreateActiveChat(connectionId, currentChatOwnerUsername());
                actualChatId = activeChat.getId();
                log.info("-> Using active chat: {}", actualChatId);
            }

            if (!explicitBusinessRule.isEmpty()) {
                persistBusinessRuleLearning(
                    connectionId,
                    actualChatId,
                    actualUserQuestion,
                    explicitBusinessRule,
                    userId,
                    schema
                );
            }

            // Load history from PostgreSQL (for fallback or initial sync to Redis)
            // When ChatMemory is available, it will handle conversation history automatically
            // When ChatMemory is unavailable, we fall back to PostgreSQL-loaded history
            boolean useChatMemoryAdvisor = (chatMemory != null);

            if (!useChatMemoryAdvisor) {
                // Fallback: manually load history from PostgreSQL when Redis is unavailable
                var history = chatHistoryService.getChatMessages(actualChatId);
                for (var msg : history) {
                    if (msg.getRole() == com.dbaagent.model.ChatMessage.MessageRole.USER) {
                        messagesToSend.add(new UserMessage(msg.getContent()));
                    } else if (msg.getRole() == com.dbaagent.model.ChatMessage.MessageRole.ASSISTANT) {
                        messagesToSend.add(new AssistantMessage(msg.getContent()));
                    }
                }
                log.info("-> Loaded {} messages from PostgreSQL chat history (JDBC memory unavailable)", history.size());
            } else {
                log.info("-> Using Spring AI Chat Memory (JDBC) for conversation history, chatId={}", actualChatId);
            }

            // Append current user message with image support
            // For vision-capable models, include the full base64 image data
            if (imageData != null && !imageData.isEmpty()) {
                String textContent = (actualUserQuestion != null && !actualUserQuestion.trim().isEmpty())
                    ? actualUserQuestion
                    : "Please analyze the screenshot image I've attached and help me with the dashboard based on what you see in the image.";

                // Include the full base64 image data URL in the message
                // Vision-capable models (like GPT-4 Vision) can process this from the text
                // The image is already in data URL format: data:image/png;base64,{base64Data}
                String enhancedMessage = textContent + "\n\n[IMAGE_DATA:" + imageData + "]";

                log.info("Including image in message for vision processing, image data length: {} chars", imageData.length());

                messagesToSend.add(new UserMessage(enhancedMessage));
            } else {
                messagesToSend.add(new UserMessage(actualUserQuestion));
            }

            // Anti-hallucination: reinforce SQL requirement for data retrieval questions
            if ((biQueryRoute || isDataRetrievalQuestion(actualUserQuestion))) {
                messagesToSend.add(new SystemMessage(
                    "REMINDER — DATA INTEGRITY MANDATE: The user is asking for real data from the database. " +
                    "You MUST generate a SQL query (```sql ... ```) to retrieve this information. " +
                    "Do NOT invent, estimate, or fabricate any data values, names, counts, or records."
                ));
            }

            // === Multi-Step Pipeline ===
            PipelineResult pipelineResult = null;
            boolean useQueryPipeline = pipelineEnabled && biQueryRoute;
            if (useQueryPipeline) {
                var pipelineCtx = new PipelineContext(
                    connectionId, actualUserQuestion, schema.getDbType(),
                    schemaContext, schema, ragContext, feedbackContext,
                    classificationContext, performanceContext, brainContext,
                    dbSpecificRules, messagesToSend,
                    PipelineProgressListener.NOOP
                );

                pipelineResult = queryGenerationPipeline.execute(pipelineCtx);

                // Enrich system prompt with pipeline results — hard caps per design
                String columnValueContext = pipelineResult.columnValueContext() != null
                    ? cap(pipelineResult.columnValueContext().formattedContext(), 2000) : "";
                String resolutionHints = pipelineResult.resolvedContext() != null
                    ? cap(queryGenerationPipeline.buildResolutionHints(connectionId, pipelineResult.resolvedContext()), 800) : "";

                // Re-apply token budget with pipeline-enriched sections
                var enrichedBudget = contextAssembler.applyTokenBudget(schemaContext, classificationContext,
                    performanceContext, brainContext, feedbackContext, companyKnowledgeContext, trainingContext,
                    dbSpecificRules, columnValueContext, resolutionHints);
                String enrichedSystemPrompt = buildSystemPromptFromTemplate(
                    dbType, dbSpecificRules, enrichedBudget[0], enrichedBudget[1],
                    enrichedBudget[2], enrichedBudget[3], enrichedBudget[4], enrichedBudget[5], enrichedBudget[6],
                    enrichedBudget[7], enrichedBudget[8]
                );
                // Preserve image analysis instructions if present
                if (imageData != null && !imageData.isEmpty()) {
                    enrichedSystemPrompt += "\n\nCRITICAL: The user has provided a screenshot/image with this message. You MUST analyze the image carefully. Describe everything you see including: tables with column names and values, charts/graphs with their data points, metrics displayed with their labels and numbers, UI elements, buttons, and any visible text. The image data is included as a base64-encoded data URL in the user's message. Use this visual information to provide accurate, context-aware responses.";
                }
                messagesToSend.set(0, new SystemMessage(enrichedSystemPrompt));

                log.info("Pipeline completed in {}ms, steps={}, historyMatched={}",
                    pipelineResult.totalDurationMs(), pipelineResult.stepsExecuted(),
                    pipelineResult.historyMatched());
            } else if (pipelineEnabled) {
                log.debug("Skipping query generation pipeline for routed question type {}", questionRoute.type());
            }

            // 3. Call LLM via Spring AI ChatClient (First Pass)
            final String conversationId = actualChatId;
            final boolean useQuestionAnswerAdvisor = ragAdvisorEnabled && questionAnswerAdvisor != null;

            String responseContent;

            // If history matched with valid SQL, use synthetic response instead of LLM call
            if (pipelineResult != null && pipelineResult.historyMatched() && pipelineResult.hasSql()) {
                responseContent = pipelineResult.response();
                log.info("Using pipeline history-match response (skipping LLM call)");
            } else {
                if (useQuestionAnswerAdvisor) {
                    log.info("Using QuestionAnswerAdvisor for RAG (Spring AI VectorStore)");
                } else {
                    log.debug("Using manual RAG from TrainingService");
                }

                responseContent = chatClient.prompt()
                    .messages(messagesToSend)
                    .advisors(advisorSpec -> {
                        if (useChatMemoryAdvisor) {
                            advisorSpec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, conversationId);
                        }
                        if (useQuestionAnswerAdvisor) {
                            advisorSpec.advisors(questionAnswerAdvisor);
                        }
                    })
                    .call()
                    .content();
            }

            log.info("AI Response: {}", responseContent);

            // Capture first-pass response for plan extraction (brain-agent inspect tab).
            // responseContent may be overwritten by SQL execution / repair passes below.
            final String firstPassResponse = responseContent;

            // Log RAG approach used for comparison
            if (ragComparisonMode) {
                log.info("=== RAG COMPARISON RESULT ===");
                log.info("RAG approach used: {}", useQuestionAnswerAdvisor ? "Manual RAG + QuestionAnswerAdvisor (dual)" : "Manual RAG only");
                log.info("Response length: {} chars", responseContent != null ? responseContent.length() : 0);
            }

            // 4. Extract and Execute SQL if present
            // Treat "brain-agent" in either projectId or userId as agent mode.
            boolean isAgentMode = "brain-agent".equals(projectId) || "brain-agent".equals(userId);
            List<String> extractedSqlQueries = new ArrayList<>(sqlExecutionPipeline.extractAllSqlFromResponse(responseContent));
            String sql = extractedSqlQueries.isEmpty() ? null : extractedSqlQueries.get(0);
            log.info("Extracted {} SQL block(s); agent mode: {}", extractedSqlQueries.size(), isAgentMode);

            QueryResult queryResult = null;
            List<String> executedQueries = new ArrayList<>();
            List<QueryResult> collectedQueryResults = new ArrayList<>();
            boolean multiPassOccurred = false;

            // 4a. Anti-hallucination enforcement: if the question requires real data but the LLM
            // responded without SQL, force a re-prompt demanding a query.
            if (sql == null && (biQueryRoute || isDataRetrievalQuestion(actualUserQuestion))) {
                if (isClarifyingQuestionResponse(responseContent)) {
                    log.info("Preserving clarifying response for ambiguous data-retrieval question instead of forcing SQL");
                } else {
                log.warn("ANTI-HALLUCINATION: Data retrieval question answered without SQL. Forcing re-prompt. Question: {}",
                    actualUserQuestion.length() > 100 ? actualUserQuestion.substring(0, 100) + "..." : actualUserQuestion);
                List<Message> rePromptMessages = new ArrayList<>(messagesToSend);
                rePromptMessages.add(new AssistantMessage(responseContent));
                rePromptMessages.add(new SystemMessage(buildSqlRequiredRePrompt(actualUserQuestion)));
                String correctedResponse = chatClient.prompt()
                    .messages(rePromptMessages)
                    .advisors(advisorSpec -> {
                        if (useChatMemoryAdvisor) {
                            advisorSpec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, conversationId);
                        }
                        if (useQuestionAnswerAdvisor) {
                            advisorSpec.advisors(questionAnswerAdvisor);
                        }
                    })
                    .call()
                    .content();
                List<String> correctedSqlBlocks = sqlExecutionPipeline.extractAllSqlFromResponse(correctedResponse);
                if (!correctedSqlBlocks.isEmpty()) {
                    String correctedSql = correctedSqlBlocks.get(correctedSqlBlocks.size() - 1);
                    if (hasUnresolvedSqlPlaceholder(correctedSql)) {
                        log.info("Anti-hallucination re-prompt produced placeholder SQL; returning clarification instead");
                        responseContent = isClarifyingQuestionResponse(firstPassResponse)
                            ? firstPassResponse
                            : "I need one clarification before I can run SQL safely: please specify the exact date/time column or business timestamp that should define this timeframe.";
                        extractedSqlQueries = new ArrayList<>();
                        sql = null;
                    } else {
                        log.info("Anti-hallucination re-prompt produced {} SQL block(s)", correctedSqlBlocks.size());
                        responseContent = correctedResponse;
                        extractedSqlQueries = new ArrayList<>(correctedSqlBlocks);
                        sql = correctedSql;
                    }
                } else {
                    log.warn("Anti-hallucination re-prompt did not produce SQL. Returning clarifying response.");
                    responseContent = correctedResponse;
                }
                multiPassOccurred = true;
                }
            }

            if (metadataRoute && !extractedSqlQueries.isEmpty()) {
                List<String> metadataSqlQueries = extractedSqlQueries.stream()
                    .filter(candidate -> chatQuestionRoutingService.isMetadataOnlySql(candidate, dbType))
                    .collect(Collectors.toCollection(ArrayList::new));

                if (metadataSqlQueries.isEmpty()) {
                    log.info("Metadata route produced non-metadata SQL, requesting metadata-only correction");
                    List<Message> correctionMessages = new ArrayList<>(messagesToSend);
                    correctionMessages.add(new AssistantMessage(responseContent));
                    correctionMessages.add(new SystemMessage(buildBrainMetadataCorrectionPrompt(actualUserQuestion)));

                    responseContent = chatClient.prompt()
                        .messages(correctionMessages)
                        .advisors(advisorSpec -> {
                            if (useChatMemoryAdvisor) {
                                advisorSpec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, conversationId);
                            }
                            if (useQuestionAnswerAdvisor) {
                                advisorSpec.advisors(questionAnswerAdvisor);
                            }
                        })
                        .call()
                        .content();

                    extractedSqlQueries = sqlExecutionPipeline.extractAllSqlFromResponse(responseContent).stream()
                        .filter(candidate -> chatQuestionRoutingService.isMetadataOnlySql(candidate, dbType))
                        .collect(Collectors.toCollection(ArrayList::new));
                    multiPassOccurred = true;
                } else if (metadataSqlQueries.size() != extractedSqlQueries.size()) {
                    log.info("Filtered non-metadata SQL blocks for metadata route");
                    extractedSqlQueries = metadataSqlQueries;
                }

                sql = extractedSqlQueries.isEmpty() ? null : extractedSqlQueries.get(0);
                if (sql == null) {
                    String cleanedResponse = stripInternalAgentBlocks(responseContent);
                    responseContent = (cleanedResponse == null || cleanedResponse.isBlank())
                        ? "I could not answer that from stored metadata alone. Ask a more specific schema or performance question, or initialize metadata analysis for this connection."
                        : cleanedResponse;
                }
            }

            if (sql != null && hasUnresolvedSqlPlaceholder(sql)) {
                log.info("Blocking execution of SQL with unresolved placeholder markers");
                extractedSqlQueries.clear();
                sql = null;
                responseContent = isClarifyingQuestionResponse(firstPassResponse)
                    ? firstPassResponse
                    : "I need one clarification before I can run SQL safely: please specify the exact date/time column or business timestamp to use for this request.";
            }

            if (!extractedSqlQueries.isEmpty()) {
                log.info("Attempting execution for {} SQL block(s)", extractedSqlQueries.size());
                try {
                    for (String candidateSql : extractedSqlQueries) {
                        if (!sqlExecutionPipeline.isAutoExecutableQuery(candidateSql)) {
                            log.info("Skipping non-read SQL block from auto-execution");
                            continue;
                        }

                        // Fail fast when model SQL violates learned business constraints.
                        BusinessRuleMemoryService.SqlGuardrailEvaluation preExecutionGuardrailCheck =
                            businessRuleMemoryService.evaluateSql(candidateSql, applicableGuardrails);
                        if (!preExecutionGuardrailCheck.passed()) {
                            throw new IllegalStateException(
                                "Learned SQL guardrail violation: " + preExecutionGuardrailCheck.summary()
                            );
                        }

                        QueryRequest queryRequest = new QueryRequest();
                        queryRequest.setQuery(candidateSql);
                        queryRequest.setLimit(100);
                        queryRequest.setExecutionOrigin(QueryExecutionOrigin.CHAT);
                        QueryResult executedResult = queryExecutorService.executeQuery(
                            connectionId,
                            queryRequest,
                            QueryExecutionContext.chat()
                        );
                        collectedQueryResults.add(executedResult);
                        executedQueries.add(candidateSql);
                        queryResult = executedResult;
                        sql = candidateSql;
                    }

                    // EXPLAIN validation for stream path: runs inside the loop before sparse-result refinement.
                    // Note: query already executed above; EXPLAIN here catches bad plans and triggers repair.
                    if (explainValidationEnabled && useQueryPipeline) {
                        var validation = queryGenerationPipeline.validateSql(connectionId, sql, dbType);
                        if (!validation.valid()) {
                            log.info("EXPLAIN validation failed: {}. Feeding into repair path.", validation.error());
                            throw new RuntimeException("EXPLAIN validation: " + validation.error());
                        }
                    }


                    boolean refinedSparseResult = false;
                    if (queryResult != null && sqlExecutionPipeline.shouldAttemptSparseResultRefinement(
                        actualUserQuestion,
                        queryResult,
                        feedbackContext,
                        explicitBusinessRule
                    )) {
                        log.info("Detected sparse SQL result for metric question; attempting semantic refinement");
                        SqlExecutionPipeline.SqlRepairResult sparseRefinement = sqlExecutionPipeline.attemptSparseResultRefinement(
                            connectionId,
                            actualUserQuestion,
                            dbType,
                            messagesToSend,
                            responseContent,
                            sql,
                            queryResult,
                            conversationId,
                            useChatMemoryAdvisor,
                            useQuestionAnswerAdvisor,
                            feedbackContext,
                            explicitBusinessRule,
                            applicableGuardrails,
                            dbSpecificRules
                        );

                        if (sparseRefinement.success()) {
                            refinedSparseResult = true;
                            multiPassOccurred = true;
                            queryResult = sparseRefinement.queryResult();
                            sql = sparseRefinement.correctedSql();
                            if (!executedQueries.isEmpty()) {
                                executedQueries.set(executedQueries.size() - 1, sql);
                            }
                            if (!collectedQueryResults.isEmpty()) {
                                collectedQueryResults.set(collectedQueryResults.size() - 1, queryResult);
                            }
                            responseContent = sparseRefinement.finalResponse();
                            log.info("Improved sparse-result SQL via semantic refinement");
                        }
                    }

                    if (!refinedSparseResult && queryResult != null) {
                        // 5. Call AI again with data results to provide a summarized answer
                        // This second LLM call adds intermediate messages that pollute chat memory.
                        multiPassOccurred = true;
                        messagesToSend.add(new AssistantMessage(responseContent));
                        // Resolve effective projectId — frontend historically sent 'brain-agent' as userId
                        String effectiveProjectId = (projectId != null) ? projectId
                            : ("brain-agent".equals(userId) ? "brain-agent" : null);
                        // For multi-query agent runs, pass combined results; otherwise single result.
                        String secondPassInstruction = (isAgentMode && collectedQueryResults.size() > 1)
                            ? buildMultiQuerySecondPassInstruction(executedQueries, collectedQueryResults)
                            : buildSecondPassInstruction(effectiveProjectId, executedQueries, collectedQueryResults);
                        messagesToSend.add(new SystemMessage(secondPassInstruction));

                        responseContent = chatClient.prompt()
                            .messages(messagesToSend)
                            .advisors(advisorSpec -> {
                                if (useChatMemoryAdvisor) {
                                    advisorSpec.param(CHAT_MEMORY_CONVERSATION_ID_KEY, conversationId);
                                }
                                if (useQuestionAnswerAdvisor) {
                                    advisorSpec.advisors(questionAnswerAdvisor);
                                }
                            })
                            .call()
                            .content();
                    }

                } catch (Exception e) {
                    log.error("Auto-execution of SQL failed with exception: {}", e.getClass().getName(), e);
                    String errorMessage = sqlExecutionPipeline.getFullErrorMessage(e);
                    log.error("SQL execution error details - Full message: {}", errorMessage);
                    log.error("Failed SQL query was: {}", sql);

                    if (metadataRoute) {
                        log.info("Skipping SQL repair for metadata-routed question after metadata query failure");
                        sql = null;
                        queryResult = null;
                        responseContent = "I could not complete the metadata query for that question: " + errorMessage;
                    } else {
                        multiPassOccurred = true; // repair attempt involves internal LLM calls
                        SqlExecutionPipeline.SqlRepairResult repairResult = sqlExecutionPipeline.attemptSqlRepairAndExecute(
                            connectionId,
                            actualUserQuestion,
                            dbType,
                            messagesToSend,
                            responseContent,
                            sql,
                            errorMessage,
                            conversationId,
                            useChatMemoryAdvisor,
                            useQuestionAnswerAdvisor,
                            explicitBusinessRule,
                            applicableGuardrails,
                            dbSpecificRules
                        );

                        if (repairResult.success()) {
                            queryResult = repairResult.queryResult();
                            sql = repairResult.correctedSql();
                            responseContent = repairResult.finalResponse();
                            try {
                                trainingService.trainWithQueryExample(
                                    connectionId,
                                    actualUserQuestion,
                                    sql,
                                    queryResult,
                                    userId
                                );
                                log.debug("Stored corrected query as training example");
                            } catch (Exception te) {
                                log.warn("Failed to store corrected training example", te);
                            }
                        } else {
                            responseContent = repairResult.finalResponse();
                        }
                    }
                }
            }

            // 6. Process Final Response
            // For agent-mode, strip internal blocks (:::plan, ```sql, [ASK] prefix)
            // from the visible message so the Output tab shows only business insights.
            String visibleMessage = responseContent;
            if (isAgentMode) {
                visibleMessage = stripInternalAgentBlocks(responseContent);
                if (visibleMessage == null || visibleMessage.trim().isEmpty()) {
                    // The LLM produced only internal blocks (plan+SQL) with no business text.
                    // Provide a meaningful fallback so the Output tab is never blank.
                    String resultSummary = !collectedQueryResults.isEmpty()
                        ? sqlExecutionPipeline.formatMultipleQueryResults(
                            executedQueries.isEmpty() ? (sql != null ? List.of(sql) : List.of()) : executedQueries,
                            collectedQueryResults)
                        : (queryResult != null ? sqlExecutionPipeline.formatQueryResultForAI(queryResult) : "No results available.");
                    visibleMessage = "**Analysis complete.** The queries were executed but the briefing could not be generated automatically.\n\n" +
                        "**Raw results:**\n\n" + resultSummary + "\n\n" +
                        "_Open the Inspect tab to review the analysis plan and SQL queries that ran._";
                }
            }

            ChatResponse response = new ChatResponse();
            response.setMessage(visibleMessage);
            response.setSuccess(true);
            response.setChatId(actualChatId);
            response.setMode("unified");

            if (sql != null) {
                response.setSql(sql);
            }
            if (queryResult != null) {
                response.setData(queryResult);
            }

            // For agent-mode calls, include the execution plan and collected SQL queries
            // so the frontend Inspect tab can show exactly what was run.
            if (isAgentMode) {
                String plan = extractPlanFromResponse(firstPassResponse);
                if (plan != null) {
                    response.setPlan(plan);
                }
                // Show all executed SQL blocks in the Inspect tab.
                if (!executedQueries.isEmpty()) {
                    response.setExecutedQueries(executedQueries);
                }
            }

            // 7. Update History - save to both PostgreSQL and Redis
            // PostgreSQL: Primary storage for chat history
            var userMessage = chatHistoryService.addMessage(actualChatId, com.dbaagent.model.ChatMessage.MessageRole.USER, actualUserQuestion, null);
            var assistantMessage = chatHistoryService.addMessage(
                actualChatId,
                com.dbaagent.model.ChatMessage.MessageRole.ASSISTANT,
                responseContent,
                sql,
                buildAssistantMetadataJson(response)
            );
            captureConversationTurn(
                connectionId,
                actualChatId,
                userMessage.getId(),
                assistantMessage.getId(),
                actualUserQuestion,
                actualUserQuestion,
                visibleMessage,
                questionRoute,
                null,
                queryResult,
                sql,
                response.getConfidence(),
                response.getAgentRunId(),
                response.isSuccess(),
                ResolvedConversationContext.empty(),
                null,
                null,
                List.of(),
                null
            );
            log.info("-> Saved messages to PostgreSQL chat: {}", actualChatId);

            // Keep JDBC chat memory aligned with canonical PostgreSQL history.
            // Only needed when internal multi-pass LLM calls (SQL repair/sparse refinement) occurred,
            // which can pollute the memory with intermediate exchanges.
            if (multiPassOccurred) {
                syncConversationMemoryFromHistory(actualChatId);
            }

            return response;

        } catch (Exception e) {
            log.error("Error processing chat message with Azure OpenAI", e);
            return new ChatResponse(
                "I encountered an error processing your request: " + e.getMessage(),
                false
            );
        }
    }

    public StreamResult streamProcessMessage(String connectionId, String message, String chatId) {
        return streamProcessMessage(connectionId, message, null, chatId);
    }

    public StreamResult streamProcessMessage(String connectionId, String message, String userId, String chatId) {
        try {
            log.info("Starting streaming chat for connection: {}", connectionId);
            String actualUserQuestion = extractActualUserQuestion(message);
            String actorUsername = resolveExecutionActorUsername(userId);
            boolean actorIsAdmin = isAdminUser(actorUsername);
            return QueryActorContextHolder.withActor(actorUsername, () -> {
                PreparedConversationTurn prepared = prepareConversationTurn(connectionId, chatId, actualUserQuestion);
                ChatResponse scopeGuardResponse = maybeBuildScopeGuardrailResponse(
                    connectionId,
                    prepared.chatId(),
                    actualUserQuestion,
                    prepared.effectiveQuestion(),
                    prepared.questionRoute(),
                    prepared.resolvedConversationContext()
                );
                if (scopeGuardResponse != null) {
                    return singleMessageStream(scopeGuardResponse);
                }
                return buildAgenticStreamResult(connectionId, actualUserQuestion, prepared, null, actorUsername, actorIsAdmin);
            });
        } catch (Exception e) {
            log.error("Error initializing streaming chat", e);
            return new StreamResult(Flux.empty(), Flux.empty(), Flux.error(e), Flux.error(e));
        }
    }

    private StreamResult singleMessageStream(ChatResponse response) {
        Flux<String> metadataStream = buildStreamMetadataJson(response) != null
            ? Flux.just(buildStreamMetadataJson(response))
            : Flux.empty();
        Flux<String> resultStream = buildStreamResultJson(response) != null
            ? Flux.just(buildStreamResultJson(response))
            : Flux.empty();
        return new StreamResult(metadataStream, Flux.empty(), resultStream, Flux.just(response.getMessage()));
    }

    private StreamResult buildAgenticStreamResult(
        String connectionId,
        String actualUserQuestion,
        PreparedConversationTurn prepared,
        @Nullable SchemaMetadata preloadedMetadataSchema,
        String actorUsername,
        boolean actorIsAdmin
    ) {
        String resolvedChatId = resolveChatId(connectionId, prepared.chatId());
        Sinks.Many<String> metadataSink = Sinks.many().replay().all();
        Sinks.Many<String> progressSink = Sinks.many().replay().all();
        Sinks.Many<String> resultSink = Sinks.many().replay().all();
        Sinks.Many<String> tokenSink = Sinks.many().replay().all();

        String metadataJson = buildStreamMetadataJson(resolvedChatId, "unified");
        if (metadataJson != null) {
            metadataSink.tryEmitNext(metadataJson);
        }

        CompletableFuture.runAsync(() -> {
            QueryActorContextHolder.withActor(actorUsername, () -> {
                AgentProgressListener progressListener = event -> {
                    String progressJson = buildAgentProgressJson(event);
                    if (progressJson != null) {
                        progressSink.tryEmitNext(progressJson);
                    }
                };

                try {
                    ChatResponse response = executePreparedAgenticChatTurn(
                        connectionId,
                        actualUserQuestion,
                        prepared,
                        preloadedMetadataSchema,
                        actorUsername,
                        actorIsAdmin,
                        progressListener
                    );
                    String resultJson = buildStreamResultJson(response);
                    if (resultJson != null) {
                        resultSink.tryEmitNext(resultJson);
                    }
                    if (response.getMessage() != null && !response.getMessage().isBlank()) {
                        tokenSink.tryEmitNext(response.getMessage());
                    }
                } catch (Exception e) {
                    log.warn("Agentic streaming flow failed: {}", e.getMessage(), e);
                    ChatResponse failure = new ChatResponse(resolveAgenticFailureMessage(e), false);
                    failure.setChatId(resolvedChatId);
                    failure.setMode("unified");
                    failure.setRun(new ChatResponse.RunInfo(null, "failed", "FRESH", "GENERAL", null, "failed", false));
                    failure.setArtifacts(new ChatResponse.Artifacts(List.of(), List.of(), List.of()));
                    failure.setUi(new ChatResponse.UiHints(actorIsAdmin, "compact"));
                    String resultJson = buildStreamResultJson(failure);
                    if (resultJson != null) {
                        resultSink.tryEmitNext(resultJson);
                    }
                    tokenSink.tryEmitNext(failure.getMessage());
                } finally {
                    metadataSink.tryEmitComplete();
                    progressSink.tryEmitComplete();
                    resultSink.tryEmitComplete();
                    tokenSink.tryEmitComplete();
                }
            });
        });

        return new StreamResult(
            metadataSink.asFlux(),
            progressSink.asFlux(),
            resultSink.asFlux(),
            tokenSink.asFlux()
        );
    }

    private ChatResponse executeAgenticChatTurn(
        String connectionId,
        String message,
        String chatId,
        String actorUsername,
        boolean actorIsAdmin,
        AgentProgressListener progressListener
    ) {
        long startTime = System.currentTimeMillis();
        String actualUserQuestion = extractActualUserQuestion(message);
        if (actualUserQuestion == null || actualUserQuestion.isBlank()) {
            return new ChatResponse("Please provide a message.", false);
        }

        try {
            PreparedConversationTurn prepared = prepareConversationTurn(connectionId, chatId, actualUserQuestion);
            return executePreparedAgenticChatTurn(
                connectionId,
                actualUserQuestion,
                prepared,
                null,
                actorUsername,
                actorIsAdmin,
                progressListener
            );
        } catch (Exception e) {
            log.error("Error processing agentic chat message", e);
            return buildAgenticFailureResponse(
                connectionId,
                chatId,
                actualUserQuestion,
                actualUserQuestion,
                chatQuestionRoutingService.classify(actualUserQuestion),
                ResolvedConversationContext.empty(),
                e
            );
        }
    }

    private ChatResponse executePreparedAgenticChatTurn(
        String connectionId,
        String actualUserQuestion,
        PreparedConversationTurn prepared,
        @Nullable SchemaMetadata preloadedMetadataSchema,
        String actorUsername,
        boolean actorIsAdmin,
        AgentProgressListener progressListener
    ) {
        long startTime = System.currentTimeMillis();

        try {
            log.info("Chat routing: type={}, topic={} for question='{}'",
                prepared.questionRoute().type(),
                prepared.questionRoute().brainTopic(),
                prepared.effectiveQuestion().length() > 80 ? prepared.effectiveQuestion().substring(0, 80) + "..." : prepared.effectiveQuestion());
            ChatResponse scopeGuardResponse = maybeBuildScopeGuardrailResponse(
                connectionId,
                prepared.chatId(),
                actualUserQuestion,
                prepared.effectiveQuestion(),
                prepared.questionRoute(),
                prepared.resolvedConversationContext()
            );
            if (scopeGuardResponse != null) {
                return scopeGuardResponse;
            }
            UserDataAccessPolicyService.PromptDecision policyDecision = userDataAccessPolicyService.evaluatePrompt(
                connectionId,
                actorUsername,
                actorIsAdmin,
                prepared.effectiveQuestion()
            );
            if (!policyDecision.allowed()) {
                return buildScopeGuardrailResponse(
                    connectionId,
                    prepared.chatId(),
                    actualUserQuestion,
                    prepared.effectiveQuestion(),
                    prepared.questionRoute(),
                    prepared.resolvedConversationContext(),
                    policyDecision.responseMessage(),
                    "policy_blocked"
                );
            }
            String policyAwareEffectiveQuestion = userDataAccessPolicyService.decorateQuestionWithPolicy(
                policyDecision.policy(),
                prepared.effectiveQuestion()
            );

            SchemaMetadata schema = preloadedMetadataSchema != null ? preloadedMetadataSchema : schemaScannerService.scanSchema(connectionId);
            ChatResponse vaultFirstMetadataResponse = tryBuildVaultFirstMetadataResponse(
                connectionId,
                prepared.chatId(),
                actualUserQuestion,
                prepared.effectiveQuestion(),
                prepared.questionRoute(),
                prepared.promptIntent(),
                schema,
                prepared.resolvedConversationContext(),
                prepared.carryoverDecision()
            );
            if (vaultFirstMetadataResponse != null) {
                log.info("VAULT-FIRST METADATA PATH: Answered '{}' from stored evidence in {}ms",
                    actualUserQuestion.length() > 50 ? actualUserQuestion.substring(0, 50) + "..." : actualUserQuestion,
                    System.currentTimeMillis() - startTime);
                return vaultFirstMetadataResponse;
            }

            AgentDecision agentDecision = agentOrchestrator.previewDecision(
                agenticEnabled,
                policyAwareEffectiveQuestion,
                prepared.questionRoute()
            );
            var agenticResult = agentOrchestrator.execute(
                agenticEnabled,
                connectionId,
                actualUserQuestion,
                policyAwareEffectiveQuestion,
                prepared.chatId(),
                prepared.conversationHistory(),
                prepared.resolvedConversationContext(),
                prepared.promptIntent(),
                schema,
                agentDecision,
                prepared.carryoverDecision(),
                progressListener != null ? progressListener : AgentProgressListener.noop()
            );
            if (agenticResult.isPresent()) {
                log.info("AGENTIC PATH ({}): Answered '{}' in {}ms",
                    schema == null ? "schema-free" : "schema-aware",
                    actualUserQuestion.length() > 50 ? actualUserQuestion.substring(0, 50) + "..." : actualUserQuestion,
                    System.currentTimeMillis() - startTime);
                return buildAgenticResponse(
                    connectionId,
                    prepared.chatId(),
                    actualUserQuestion,
                    prepared.effectiveQuestion(),
                    prepared.questionRoute(),
                    prepared.resolvedConversationContext(),
                    agenticResult.get(),
                    prepared.carryoverDecision(),
                    actorIsAdmin,
                    System.currentTimeMillis() - startTime
                );
            }

            log.warn("Agentic flow produced no result for '{}'; returning standardized agentic failure",
                actualUserQuestion.length() > 80 ? actualUserQuestion.substring(0, 80) + "..." : actualUserQuestion);
            return buildAgenticFailureResponse(
                connectionId,
                prepared.chatId(),
                actualUserQuestion,
                prepared.effectiveQuestion(),
                prepared.questionRoute(),
                prepared.resolvedConversationContext(),
                null
            );
        } catch (Exception e) {
            log.error("Error processing agentic chat message", e);
            return buildAgenticFailureResponse(
                connectionId,
                prepared.chatId(),
                actualUserQuestion,
                prepared.effectiveQuestion(),
                prepared.questionRoute(),
                prepared.resolvedConversationContext(),
                e
            );
        }
    }

    private ChatResponse tryBuildVaultFirstMetadataResponse(
        String connectionId,
        String chatId,
        String actualUserQuestion,
        String effectiveQuestion,
        ChatQuestionRoutingService.QuestionRoute questionRoute,
        PromptIntent promptIntent,
        @Nullable SchemaMetadata preloadedSchema,
        ResolvedConversationContext resolvedConversationContext,
        ConversationCarryoverDecision carryoverDecision
    ) {
        if (promptIntent == null || !promptIntent.isMetadataDomain()) {
            return null;
        }

        MetadataRequestScope requestScope = metadataRequestScopeResolver.resolve(
            effectiveQuestion,
            preloadedSchema,
            questionRoute,
            promptIntent
        );
        if (requestScope == null) {
            return null;
        }

        SchemaMetadata schema = preloadedSchema != null ? preloadedSchema : tryLoadSchemaForMetadataResponse(connectionId);
        if (schema == null) {
            return null;
        }

        VerifiedAnswer verifiedAnswer = resolveVerifiedMetadataAnswer(
            connectionId,
            effectiveQuestion,
            questionRoute,
            promptIntent,
            schema,
            resolvedConversationContext,
            requestScope
        );
        if (verifiedAnswer != null) {
            return buildUnifiedMetadataResponse(
                connectionId,
                chatId,
                actualUserQuestion,
                effectiveQuestion,
                questionRoute,
                promptIntent,
                resolvedConversationContext,
                carryoverDecision,
                verifiedAnswer
            );
        }

        return null;
    }

    @Nullable
    private SchemaMetadata tryLoadSchemaForMetadataResponse(String connectionId) {
        try {
            return schemaScannerService.scanSchema(connectionId);
        } catch (SQLException e) {
            log.warn("Failed to load schema for metadata fast path on {}: {}", connectionId, e.getMessage());
            return null;
        }
    }

    private VerifiedAnswer resolveVerifiedMetadataAnswer(
        String connectionId,
        String effectiveQuestion,
        ChatQuestionRoutingService.QuestionRoute questionRoute,
        PromptIntent promptIntent,
        SchemaMetadata schema,
        ResolvedConversationContext resolvedConversationContext,
        MetadataRequestScope requestScope
    ) {
        if (promptIntent == null) {
            return null;
        }
        if (promptIntent.domain() == PromptIntent.Domain.PERFORMANCE) {
            return performanceExecutor.execute(promptIntent, effectiveQuestion, connectionId, schema, resolvedConversationContext).orElse(null);
        }
        if (promptIntent.domain() == PromptIntent.Domain.SCHEMA) {
            return schemaMetadataExecutor.execute(promptIntent, questionRoute, effectiveQuestion, connectionId, schema, resolvedConversationContext, requestScope).orElse(null);
        }
        return null;
    }

    private String resolveChatId(String connectionId, String chatId) {
        if (chatId != null && !chatId.isBlank()) {
            return chatId;
        }
        if (connectionId == null || connectionId.isBlank()) {
            return chatId;
        }
        return chatHistoryService.getOrCreateActiveChat(connectionId, currentChatOwnerUsername()).getId();
    }

    /**
     * Builds a unified ChatResponse, saving messages to both PostgreSQL and JDBC memory stores.
     */
    private ChatResponse buildUnifiedMetadataResponse(
        String connectionId,
        String chatId,
        String userQuestion,
        String effectiveQuestion,
        ChatQuestionRoutingService.QuestionRoute questionRoute,
        PromptIntent promptIntent,
        ResolvedConversationContext resolvedConversationContext,
        ConversationCarryoverDecision carryoverDecision,
        VerifiedAnswer verifiedAnswer
    ) {
        String resolvedChatId = chatId;
        if (resolvedChatId == null) {
            var activeChat = chatHistoryService.getOrCreateActiveChat(connectionId, currentChatOwnerUsername());
            resolvedChatId = activeChat.getId();
        }

        ChatResponse response = new ChatResponse();
        response.setMessage(verifiedAnswer.renderedMessage());
        response.setChatId(resolvedChatId);
        response.setSuccess(true);
        response.setMode("unified");
        response.setConfidence(verifiedAnswer.evidence().confidence());
        response.setPlan("Goal: Answer the request using verified metadata evidence\n- " + verifiedAnswer.stepTitle() + " (" + verifiedAnswer.toolName() + ")");
        response.setToolsUsed(List.of(verifiedAnswer.toolName()));

        String agentRunId = createSyntheticRunTrace(
            connectionId,
            resolvedChatId,
            userQuestion,
            questionRoute,
            carryoverDecision,
            verifiedAnswer.toolName(),
            verifiedAnswer.stepTitle(),
            verifiedAnswer.stepKind(),
            verifiedAnswer.renderedMessage(),
            mergeUnifiedObservationData(questionRoute, carryoverDecision, resolvedConversationContext, promptIntent, verifiedAnswer)
        );
        response.setAgentRunId(agentRunId);
        PromptIntent responseIntent = verifiedAnswer.promptIntent() != null ? verifiedAnswer.promptIntent() : promptIntent;
        response.setRun(buildRunInfo(
            agentRunId,
            "completed",
            threadModeFromCarryover(carryoverDecision),
            responseIntent,
            null,
            verifiedAnswer.answerContract() != null
                && verifiedAnswer.answerContract().followUpPrompt() != null
                && !verifiedAnswer.answerContract().followUpPrompt().isBlank()
                    ? "clarification_ready"
                    : "completed",
            verifiedAnswer.verificationReport() != null
                && (verifiedAnswer.verificationReport().passed()
                    || verifiedAnswer.verificationReport().verifiedInsufficiency())
        ));
        response.setArtifacts(buildArtifacts(List.of(), null, verifiedAnswer.answerContract()));
        response.setUi(new ChatResponse.UiHints(accessControlService.isCurrentUserAdmin(), "compact"));

        var userMessage = chatHistoryService.addMessage(resolvedChatId, com.dbaagent.model.ChatMessage.MessageRole.USER, userQuestion, null);
        var assistantMessage = chatHistoryService.addMessage(
            resolvedChatId,
            com.dbaagent.model.ChatMessage.MessageRole.ASSISTANT,
            verifiedAnswer.renderedMessage(),
            verifiedAnswer.answerContract().executedSql(),
            buildAssistantMetadataJson(response)
        );
        captureConversationTurn(
            connectionId,
            resolvedChatId,
            userMessage.getId(),
            assistantMessage.getId(),
            userQuestion,
            effectiveQuestion,
            verifiedAnswer.renderedMessage(),
            questionRoute,
            promptIntent != null ? promptIntent.domain().name() + ":" + promptIntent.taskType().name() : null,
            null,
            verifiedAnswer.answerContract().executedSql(),
            response.getConfidence(),
            response.getAgentRunId(),
            true,
            resolvedConversationContext,
            null,
            "cached_metadata",
            verifiedAnswer.answerContract() != null ? verifiedAnswer.answerContract().supportingEvidence() : List.of(),
            "completed"
        );
        if (response.getAgentRunId() != null) {
            agentRunService.attachChatArtifacts(response.getAgentRunId(), resolvedChatId, userMessage.getId(), assistantMessage.getId());
        }

        if (chatMemory != null) {
            chatMemory.add(resolvedChatId, new UserMessage(userQuestion));
            chatMemory.add(resolvedChatId, new AssistantMessage(verifiedAnswer.renderedMessage()));
        }
        return response;
    }

    private ChatResponse buildUnifiedDirectResponse(
        String connectionId,
        String chatId,
        String userQuestion,
        String effectiveQuestion,
        ChatQuestionRoutingService.QuestionRoute questionRoute,
        ResolvedConversationContext resolvedConversationContext,
        ConversationCarryoverDecision carryoverDecision,
        String answer
    ) {
        String resolvedChatId = chatId;
        if (resolvedChatId == null) {
            var activeChat = chatHistoryService.getOrCreateActiveChat(connectionId, currentChatOwnerUsername());
            resolvedChatId = activeChat.getId();
        }

        ChatResponse response = new ChatResponse();
        response.setMessage(answer);
        response.setChatId(resolvedChatId);
        response.setSuccess(true);
        response.setMode("unified");
        response.setConfidence(0.96);

        String agentRunId = createSyntheticRunTrace(
            connectionId,
            resolvedChatId,
            userQuestion,
            questionRoute,
            carryoverDecision,
            "fast_path_lookup",
            "Check cached metadata in vault DB",
            "lookup",
            "Vault/cache fast path answered the request without SQL execution",
            mergeUnifiedObservationData(questionRoute, carryoverDecision, resolvedConversationContext)
        );
        response.setAgentRunId(agentRunId);
        response.setPlan("Goal: Answer the request directly from cached metadata\n- Check cached metadata in vault DB (fast_path_lookup)");
        response.setToolsUsed(List.of("fast_path_lookup"));

        var userMessage = chatHistoryService.addMessage(resolvedChatId, com.dbaagent.model.ChatMessage.MessageRole.USER, userQuestion, null);
        var assistantMessage = chatHistoryService.addMessage(
            resolvedChatId,
            com.dbaagent.model.ChatMessage.MessageRole.ASSISTANT,
            answer,
            null,
            buildAssistantMetadataJson(response)
        );
        captureConversationTurn(
            connectionId,
            resolvedChatId,
            userMessage.getId(),
            assistantMessage.getId(),
            userQuestion,
            effectiveQuestion,
            answer,
            questionRoute,
            null,
            null,
            null,
            response.getConfidence(),
            response.getAgentRunId(),
            true,
            resolvedConversationContext,
            null,
            "cached_metadata",
            List.of(),
            "completed"
        );
        if (response.getAgentRunId() != null) {
            agentRunService.attachChatArtifacts(response.getAgentRunId(), resolvedChatId, userMessage.getId(), assistantMessage.getId());
        }

        if (chatMemory != null) {
            chatMemory.add(resolvedChatId, new UserMessage(userQuestion));
            chatMemory.add(resolvedChatId, new AssistantMessage(answer));
        }
        return response;
    }

    private ChatResponse buildAgenticFailureResponse(
        String connectionId,
        String chatId,
        String userQuestion,
        String effectiveQuestion,
        ChatQuestionRoutingService.QuestionRoute questionRoute,
        ResolvedConversationContext resolvedConversationContext,
        @Nullable Throwable failure
    ) {
        String resolvedChatId = chatId;
        String failureMessage = resolveAgenticFailureMessage(failure);
        try {
            resolvedChatId = resolveChatId(connectionId, chatId);
        } catch (Exception e) {
            log.warn("Failed to resolve chatId for agentic failure response", e);
        }

        ChatResponse response = new ChatResponse();
        response.setMessage(failureMessage);
        response.setSuccess(false);
        response.setChatId(resolvedChatId);
        response.setMode("unified");
        response.setRun(new ChatResponse.RunInfo(null, "failed", "FRESH", "GENERAL", null, "failed", false));
        response.setArtifacts(new ChatResponse.Artifacts(List.of(), List.of(), List.of()));
        response.setUi(new ChatResponse.UiHints(accessControlService.isCurrentUserAdmin(), "compact"));

        if (resolvedChatId == null || resolvedChatId.isBlank() || userQuestion == null || userQuestion.isBlank()) {
            return response;
        }

        try {
            var userMessage = chatHistoryService.addMessage(
                resolvedChatId,
                com.dbaagent.model.ChatMessage.MessageRole.USER,
                userQuestion,
                null
            );
            var assistantMessage = chatHistoryService.addMessage(
                resolvedChatId,
                com.dbaagent.model.ChatMessage.MessageRole.ASSISTANT,
                failureMessage,
                null,
                buildAssistantMetadataJson(response)
            );
            captureConversationTurn(
                connectionId,
                resolvedChatId,
                userMessage.getId(),
                assistantMessage.getId(),
                userQuestion,
                effectiveQuestion,
                failureMessage,
                questionRoute,
                null,
                null,
                null,
                null,
                null,
                false,
                resolvedConversationContext,
                null,
                null,
                List.of(),
                "failed"
            );

            if (chatMemory != null) {
                chatMemory.add(resolvedChatId, new UserMessage(userQuestion));
                chatMemory.add(resolvedChatId, new AssistantMessage(failureMessage));
            }
        } catch (Exception e) {
            log.warn("Failed to persist standardized agentic failure response for chatId={}", resolvedChatId, e);
        }

        return response;
    }

    private String resolveAgenticFailureMessage(@Nullable Throwable failure) {
        if (failure == null) {
            return AGENTIC_FAILURE_MESSAGE;
        }
        if (failure instanceof UserDataAccessPolicyException userDataAccessPolicyException) {
            return userDataAccessPolicyException.getMessage();
        }
        if (isDatabaseConnectionUnavailable(failure)) {
            return DATABASE_CONNECTION_UNAVAILABLE_MESSAGE;
        }
        return AGENTIC_FAILURE_MESSAGE;
    }

    private String resolveExecutionActorUsername(@Nullable String explicitUserId) {
        if (explicitUserId != null && !explicitUserId.isBlank() && !"brain-agent".equalsIgnoreCase(explicitUserId)) {
            return explicitUserId;
        }
        return accessControlService.getCurrentUsername();
    }

    private String currentChatOwnerUsername() {
        String actorUsername = QueryActorContextHolder.currentUsername();
        if (actorUsername != null && !actorUsername.isBlank()) {
            return actorUsername;
        }
        return accessControlService.getCurrentUsername();
    }

    private boolean isAdminUser(@Nullable String username) {
        if (username == null || username.isBlank()) {
            return accessControlService.isCurrentUserAdmin();
        }
        return userRepository.findByUsernameIgnoreCase(username)
            .map(com.dbaagent.model.User::isAdmin)
            .orElse(accessControlService.isCurrentUserAdmin());
    }

    private boolean isDatabaseConnectionUnavailable(Throwable failure) {
        Throwable current = failure;
        while (current != null) {
            if (current instanceof SQLException sqlException && isConnectionOrAuthenticationSqlException(sqlException)) {
                return true;
            }
            String className = current.getClass().getName();
            if ("org.springframework.jdbc.CannotGetJdbcConnectionException".equals(className)
                || "org.springframework.dao.DataAccessResourceFailureException".equals(className)) {
                return true;
            }
            if (messageIndicatesDatabaseAccessFailure(current.getMessage())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private boolean isConnectionOrAuthenticationSqlException(SQLException exception) {
        String sqlState = exception.getSQLState();
        if (sqlState != null && sqlState.length() >= 2) {
            String category = sqlState.substring(0, 2);
            if ("08".equals(category) || "28".equals(category)) {
                return true;
            }
        }
        return messageIndicatesDatabaseAccessFailure(exception.getMessage());
    }

    private boolean messageIndicatesDatabaseAccessFailure(@Nullable String message) {
        if (message == null || message.isBlank()) {
            return false;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        return lower.contains("access denied for user")
            || lower.contains("authentication failed")
            || lower.contains("login failed")
            || lower.contains("password authentication failed")
            || lower.contains("communications link failure")
            || lower.contains("connection refused")
            || lower.contains("connect timed out")
            || lower.contains("connection timed out")
            || lower.contains("connection reset")
            || lower.contains("the network adapter could not establish the connection")
            || lower.contains("unknown host")
            || lower.contains("too many connections")
            || lower.contains("unable to acquire jdbc connection")
            || lower.contains("failed to obtain jdbc connection");
    }

    private String createSyntheticRunTrace(
        String connectionId,
        String chatId,
        String userQuestion,
        ChatQuestionRoutingService.QuestionRoute questionRoute,
        ConversationCarryoverDecision carryoverDecision,
        String toolName,
        String stepTitle,
        String stepKind,
        String observationSummary,
        Map<String, Object> observationData
    ) {
        try {
            AgentIntent intent = questionRoute != null && questionRoute.isBrainMetadata()
                ? AgentIntent.METADATA_ANALYSIS
                : AgentIntent.UNIVERSAL_CHAT;
            String taskId = questionRoute != null && questionRoute.isBrainMetadata() ? "metadata-lookup" : "unified-metadata-lookup";
            AgentPlanTask task = new AgentPlanTask(
                taskId,
                stepTitle,
                AgentTaskKind.LOOKUP,
                List.of(),
                toolName,
                userQuestion,
                "Return a direct answer without generating SQL when cached metadata already resolves the request.",
                Map.of(
                    "routeType", questionRoute != null ? questionRoute.type().name() : "GENERAL",
                    "brainTopic", questionRoute != null ? questionRoute.brainTopic().name() : "GENERAL"
                )
            );
            AgentPlanStep step = new AgentPlanStep(
                toolName,
                stepTitle,
                toolName,
                Map.of(
                    "routeType", questionRoute != null ? questionRoute.type().name() : "GENERAL",
                    "brainTopic", questionRoute != null ? questionRoute.brainTopic().name() : "GENERAL"
                ),
                taskId,
                List.of(),
                stepKind
            );
            AgentPlan plan = new AgentPlan(intent, "Answer the request using unified metadata evidence", List.of(task), List.of(step));
            var run = agentRunService.startRun(connectionId, chatId, userQuestion, plan);
            if (run == null || run.getId() == null || run.getId().isBlank()) {
                return null;
            }
            AgentToolResult toolResult = new AgentToolResult(
                new AgentObservation(
                    "fast_path",
                    observationSummary,
                    observationData
                ),
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                0.96
            );
            agentRunService.recordStep(run.getId(), 0, step, toolResult);
            agentRunService.completeRun(
                run.getId(),
                new AgentExecutionResult(
                    run.getId(),
                    intent,
                    observationSummary,
                    null,
                    plan.summarize(),
                    List.of(),
                    List.of(toolName),
                    0.96
                )
            );
            return run.getId();
        } catch (Exception e) {
            log.warn("Failed to create unified metadata trace for chatId={}", chatId, e);
            return null;
        }
    }

    private Map<String, Object> mergeUnifiedObservationData(
        ChatQuestionRoutingService.QuestionRoute questionRoute,
        ConversationCarryoverDecision carryoverDecision,
        ResolvedConversationContext resolvedConversationContext
    ) {
        Map<String, Object> data = new LinkedHashMap<>();
        if (questionRoute != null) {
            data.put("routeType", questionRoute.type().name());
            data.put("brainTopic", questionRoute.brainTopic().name());
            data.put("intent", questionRoute.isBrainMetadata() ? AgentIntent.METADATA_ANALYSIS.name() : AgentIntent.UNIVERSAL_CHAT.name());
        }
        if (carryoverDecision != null) {
            data.put("reuseMode", carryoverDecision.reuseMode().name());
            data.put("reuseConfidence", carryoverDecision.reuseConfidence());
            data.put("carryoverRationale", carryoverDecision.rationale());
            if (carryoverDecision.matchedContextId() != null) {
                data.put("matchedContextId", carryoverDecision.matchedContextId());
            }
            if (carryoverDecision.isTopicReset()) {
                data.put("topicReset", true);
            }
        }
        if (resolvedConversationContext != null && resolvedConversationContext.hasMatchedContext()) {
            Map<String, Object> resolved = resolvedConversationContext.resolvedContext();
            if (!stringListValue(resolved.get("tables")).isEmpty()) {
                data.put("resolvedTables", stringListValue(resolved.get("tables")));
            }
            if (!stringListValue(resolved.get("joinConditions")).isEmpty()) {
                data.put("chosenJoinPath", stringListValue(resolved.get("joinConditions")));
            }
            String temporalColumn = objectToString(resolved.get("chosenTemporalColumn"));
            if (temporalColumn != null && !temporalColumn.isBlank()) {
                data.put("chosenTemporalColumn", temporalColumn);
            }
        }
        return data;
    }

    private Map<String, Object> mergeUnifiedObservationData(
        ChatQuestionRoutingService.QuestionRoute questionRoute,
        ConversationCarryoverDecision carryoverDecision,
        ResolvedConversationContext resolvedConversationContext,
        PromptIntent promptIntent,
        VerifiedAnswer verifiedAnswer
    ) {
        Map<String, Object> data = mergeUnifiedObservationData(questionRoute, carryoverDecision, resolvedConversationContext);
        if (promptIntent != null) {
            data.put("promptIntent", Map.of(
                "domain", promptIntent.domain().name(),
                "taskType", promptIntent.taskType().name(),
                "subjectTypes", promptIntent.subjectTypes().stream().map(Enum::name).toList(),
                "requestedOutput", promptIntent.requestedOutput().name(),
                "requiresSql", promptIntent.requiresSql(),
                "requiresLiveMetadata", promptIntent.requiresLiveMetadata(),
                "requiresCachedMetadata", promptIntent.requiresCachedMetadata(),
                "requiresDocs", promptIntent.requiresDocs()
            ));
        }
        if (verifiedAnswer != null) {
            data.putAll(verifiedAnswer.observationData());
        }
        return data;
    }

    private ChatResponse maybeBuildScopeGuardrailResponse(
        String connectionId,
        String chatId,
        String userQuestion,
        String effectiveQuestion,
        ChatQuestionRoutingService.QuestionRoute questionRoute,
        ResolvedConversationContext resolvedConversationContext
    ) {
        ChatScopeGuardService.ScopeDecision decision = chatScopeGuardService.evaluate(
            userQuestion,
            effectiveQuestion,
            questionRoute
        );
        if (decision.allowed()) {
            return null;
        }
        return buildScopeGuardrailResponse(
            connectionId,
            chatId,
            userQuestion,
            effectiveQuestion,
            questionRoute,
            resolvedConversationContext,
            decision.responseMessage(),
            decision.reasonCode()
        );
    }

    private ChatResponse buildScopeGuardrailResponse(
        String connectionId,
        String chatId,
        String userQuestion,
        String effectiveQuestion,
        ChatQuestionRoutingService.QuestionRoute questionRoute,
        ResolvedConversationContext resolvedConversationContext,
        String refusalMessage,
        String reasonCode
    ) {
        String resolvedChatId = chatId;
        try {
            resolvedChatId = resolveChatId(connectionId, chatId);
        } catch (Exception e) {
            log.warn("Failed to resolve chatId for scope guardrail response", e);
        }

        ChatResponse response = new ChatResponse();
        response.setMessage(refusalMessage);
        response.setSuccess(false);
        response.setChatId(resolvedChatId);
        response.setMode("unified");
        response.setRun(new ChatResponse.RunInfo(null, "blocked", "FRESH", "GENERAL", null, reasonCode, false));
        response.setArtifacts(new ChatResponse.Artifacts(List.of(), List.of(), List.of()));
        response.setUi(new ChatResponse.UiHints(accessControlService.isCurrentUserAdmin(), "compact"));

        if (resolvedChatId == null || resolvedChatId.isBlank() || userQuestion == null || userQuestion.isBlank()) {
            return response;
        }

        try {
            var userMessage = chatHistoryService.addMessage(
                resolvedChatId,
                com.dbaagent.model.ChatMessage.MessageRole.USER,
                userQuestion,
                null
            );
            var assistantMessage = chatHistoryService.addMessage(
                resolvedChatId,
                com.dbaagent.model.ChatMessage.MessageRole.ASSISTANT,
                refusalMessage,
                null,
                buildAssistantMetadataJson(response)
            );
            captureConversationTurn(
                connectionId,
                resolvedChatId,
                userMessage.getId(),
                assistantMessage.getId(),
                userQuestion,
                effectiveQuestion,
                refusalMessage,
                questionRoute,
                reasonCode,
                null,
                null,
                1.0,
                null,
                false,
                resolvedConversationContext,
                null,
                null,
                List.of(),
                reasonCode
            );

            if (chatMemory != null) {
                chatMemory.add(resolvedChatId, new UserMessage(userQuestion));
                chatMemory.add(resolvedChatId, new AssistantMessage(refusalMessage));
            }
        } catch (Exception e) {
            log.warn("Failed to persist scope guardrail response for chatId={}", resolvedChatId, e);
        }

        return response;
    }

    private List<String> stringListValue(Object value) {
        if (value instanceof List<?> list) {
            return list.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .filter(item -> !item.isBlank())
                .toList();
        }
        if (value == null) {
            return List.of();
        }
        String rendered = String.valueOf(value);
        return rendered.isBlank() ? List.of() : List.of(rendered);
    }

    private String objectToString(Object value) {
        if (value == null) {
            return null;
        }
        String rendered = String.valueOf(value);
        return rendered.isBlank() ? null : rendered;
    }

    private ChatResponse buildAgenticResponse(
        String connectionId,
        String chatId,
        String userQuestion,
        String effectiveQuestion,
        ChatQuestionRoutingService.QuestionRoute questionRoute,
        ResolvedConversationContext resolvedConversationContext,
        AgentExecutionResult result,
        ConversationCarryoverDecision carryoverDecision,
        boolean adminTraceAvailable,
        long durationMs
    ) {
        String resolvedChatId = chatId;
        if (resolvedChatId == null) {
            var activeChat = chatHistoryService.getOrCreateActiveChat(connectionId, currentChatOwnerUsername());
            resolvedChatId = activeChat.getId();
        }

        String answerContractSql = result.answerContract() != null ? result.answerContract().executedSql() : null;
        String primarySql = answerContractSql != null && !answerContractSql.isBlank()
            ? answerContractSql
            : (result.executedQueries().isEmpty() ? null : result.executedQueries().getFirst());
        boolean exposePrimarySql = result.intent() != AgentIntent.METADATA_ANALYSIS;
        boolean exposeStructuredResults = shouldExposeStructuredResults(result);

        ChatResponse response = new ChatResponse();
        response.setMessage(result.message());
        response.setSuccess(true);
        response.setChatId(resolvedChatId);
        response.setMode("unified");
        response.setPlan(result.planSummary());
        response.setExecutedQueries(result.executedQueries());
        response.setToolsUsed(result.toolsUsed());
        response.setConfidence(result.confidence());
        response.setAgentRunId(result.runId());
        if (exposeStructuredResults && result.taskResults() != null && !result.taskResults().isEmpty()) {
            response.setResultSets(result.taskResults().stream().map(this::toChatResultSet).toList());
        }
        if (exposePrimarySql && primarySql != null) {
            response.setSql(primarySql);
        }
        if (exposeStructuredResults && result.primaryResult() != null) {
            response.setData(result.primaryResult());
        }
        response.setRun(buildRunInfo(
            result.runId(),
            "completed",
            threadModeFromCarryover(carryoverDecision),
            result.promptIntent(),
            durationMs,
            result.answerContract() != null && result.answerContract().followUpPrompt() != null && !result.answerContract().followUpPrompt().isBlank()
                ? "clarification_ready"
                : "completed",
            result.verificationReport() != null && (result.verificationReport().passed() || result.verificationReport().verifiedInsufficiency())
        ));
        response.setArtifacts(buildArtifacts(
            result.executedQueries(),
            response.getResultSets(),
            result.answerContract()
        ));
        response.setUi(new ChatResponse.UiHints(adminTraceAvailable, "compact"));

        var userMessage = chatHistoryService.addMessage(
            resolvedChatId,
            com.dbaagent.model.ChatMessage.MessageRole.USER,
            userQuestion,
            null
        );
        var assistantMessage = chatHistoryService.addMessage(
            resolvedChatId,
            com.dbaagent.model.ChatMessage.MessageRole.ASSISTANT,
            result.message(),
            exposePrimarySql ? primarySql : null,
            buildAssistantMetadataJson(response)
        );
        if (result.runId() != null) {
            agentRunService.attachChatArtifacts(result.runId(), resolvedChatId, userMessage.getId(), assistantMessage.getId());
        }
        captureConversationTurn(
            connectionId,
            resolvedChatId,
            userMessage.getId(),
            assistantMessage.getId(),
            userQuestion,
            effectiveQuestion,
            result.message(),
            questionRoute,
            result.promptIntent() != null
                ? result.promptIntent().domain().name() + ":" + result.promptIntent().taskType().name()
                : (result.intent() != null ? result.intent().name() : null),
            result.primaryResult(),
            answerContractSql != null && !answerContractSql.isBlank() ? answerContractSql : primarySql,
            response.getConfidence(),
            result.runId(),
            true,
            resolvedConversationContext,
            response.getRun() != null ? response.getRun().getThreadMode() : null,
            deriveSourceTier(result),
            response.getArtifacts() != null ? response.getArtifacts().getEvidenceSummaries() : List.of(),
            response.getRun() != null ? response.getRun().getStopReason() : null
        );

        if (chatMemory != null) {
            chatMemory.add(resolvedChatId, new UserMessage(userQuestion));
            chatMemory.add(resolvedChatId, new AssistantMessage(result.message()));
        }
        return response;
    }

    private void captureConversationTurn(
        String connectionId,
        String chatId,
        String userMessageId,
        String assistantMessageId,
        String userQuestion,
        String effectiveQuestion,
        String assistantMessage,
        ChatQuestionRoutingService.QuestionRoute questionRoute,
        String intent,
        QueryResult queryResult,
        String sourceSql,
        Double confidence,
        String agentRunId,
        boolean success,
        ResolvedConversationContext resolvedConversationContext,
        @Nullable String threadMode,
        @Nullable String sourceTier,
        List<String> evidenceSummaries,
        @Nullable String stopReason
    ) {
        try {
            conversationContextService.recordTurn(new ConversationContextService.TurnSnapshotRequest(
                connectionId,
                chatId,
                userMessageId,
                assistantMessageId,
                userQuestion,
                effectiveQuestion,
                assistantMessage,
                questionRoute != null ? questionRoute.type().name() : null,
                intent,
                sourceSql,
                queryResult,
                confidence,
                agentRunId,
                success,
                resolvedConversationContext,
                threadMode,
                sourceTier,
                evidenceSummaries,
                stopReason
            ));
        } catch (Exception e) {
            log.warn("Failed to persist chat turn context for chatId={}: {}", chatId, e.getMessage());
        }
    }

    private ChatResponse.RunInfo buildRunInfo(
        @Nullable String runId,
        String status,
        String threadMode,
        @Nullable PromptIntent promptIntent,
        @Nullable Long durationMs,
        String stopReason,
        boolean verified
    ) {
        return new ChatResponse.RunInfo(
            runId,
            status,
            threadMode,
            promptIntent == null ? PromptIntent.Domain.GENERAL.name() : promptIntent.domain().name(),
            durationMs,
            stopReason,
            verified
        );
    }

    private ChatResponse.Artifacts buildArtifacts(
        List<String> executedQueries,
        List<ChatResultSet> resultSets,
        @Nullable AnswerContract answerContract
    ) {
        List<String> sqlArtifacts = new ArrayList<>();
        if (answerContract != null
            && answerContract.executedSql() != null
            && !answerContract.executedSql().isBlank()) {
            sqlArtifacts.add(answerContract.executedSql());
        }
        if (executedQueries != null) {
            sqlArtifacts.addAll(executedQueries.stream()
                .filter(Objects::nonNull)
                .filter(query -> !query.isBlank())
                .toList());
        }
        sqlArtifacts = sqlArtifacts.stream().distinct().toList();
        List<String> evidenceSummaries = answerContract == null
            ? List.of()
            : java.util.stream.Stream.concat(
                answerContract.primaryFindings().stream(),
                answerContract.supportingEvidence().stream()
            )
            .filter(Objects::nonNull)
            .filter(item -> !item.isBlank())
            .limit(12)
            .toList();
        return new ChatResponse.Artifacts(
            List.copyOf(sqlArtifacts),
            resultSets == null ? List.of() : List.copyOf(resultSets),
            evidenceSummaries
        );
    }

    private String threadModeFromCarryover(@Nullable ConversationCarryoverDecision carryoverDecision) {
        if (carryoverDecision == null) {
            return "FRESH";
        }
        return switch (carryoverDecision.reuseMode()) {
            case ANSWER_CLARIFICATION -> "CLARIFICATION_REPLY";
            case NARROW_EXISTING_SCOPE -> "REUSE_SCOPE";
            case NEW_INTENT -> "RESET_TOPIC";
            case NONE -> "FRESH";
        };
    }

    private String deriveSourceTier(AgentExecutionResult result) {
        if (result == null || result.verificationReport() == null) {
            return "unknown";
        }
        return result.verificationReport().sourceStrength().name().toLowerCase(Locale.ROOT);
    }

    private String buildAssistantMetadataJson(ChatResponse response) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("mode", response.getMode());
            metadata.put("chatId", response.getChatId());
            if (response.getAnswer() != null) {
                metadata.put("answer", response.getAnswer());
            }
            if (response.getPlan() != null) {
                metadata.put("plan", response.getPlan());
            }
            if (response.getExecutedQueries() != null && !response.getExecutedQueries().isEmpty()) {
                metadata.put("executedQueries", response.getExecutedQueries());
            }
            if (response.getToolsUsed() != null && !response.getToolsUsed().isEmpty()) {
                metadata.put("toolsUsed", response.getToolsUsed());
            }
            if (response.getConfidence() != null) {
                metadata.put("confidence", response.getConfidence());
            }
            if (response.getSql() != null) {
                metadata.put("sql", response.getSql());
            }
            if (response.getAgentRunId() != null) {
                metadata.put("agentRunId", response.getAgentRunId());
            }
            if (response.getResultSets() != null && !response.getResultSets().isEmpty()) {
                metadata.put("resultSets", response.getResultSets());
            }
            if (response.getRun() != null) {
                metadata.put("run", response.getRun());
            }
            if (response.getArtifacts() != null) {
                metadata.put("artifacts", response.getArtifacts());
            }
            if (response.getUi() != null) {
                metadata.put("ui", response.getUi());
            }
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            log.warn("Failed to serialize assistant chat metadata", e);
            return null;
        }
    }

    private ChatResultSet toChatResultSet(AgentTaskResult taskResult) {
        return new ChatResultSet(
            taskResult.taskId(),
            taskResult.title(),
            taskResult.kind().name(),
            taskResult.status(),
            taskResult.summary(),
            taskResult.dependsOn(),
            taskResult.executedQueries(),
            taskResult.primaryResult(),
            taskResult.confidence()
        );
    }

    private boolean shouldExposeStructuredResults(AgentExecutionResult result) {
        if (result == null || result.promptIntent() == null) {
            return false;
        }
        PromptIntent promptIntent = result.promptIntent();
        return promptIntent.domain() == PromptIntent.Domain.BI
            && promptIntent.requiresSql()
            && promptIntent.requestedOutput() == PromptIntent.RequestedOutput.SQL_RESULT;
    }

    private String buildStreamResultJson(ChatResponse response) {
        try {
            return objectMapper.writeValueAsString(response);
        } catch (Exception e) {
            log.warn("Failed to serialize stream result payload", e);
            return null;
        }
    }

    private String buildStreamMetadataJson(ChatResponse response) {
        return buildAssistantMetadataJson(response);
    }

    private String buildStreamMetadataJson(String chatId, String mode) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            if (chatId != null && !chatId.isBlank()) {
                metadata.put("chatId", chatId);
            }
            metadata.put("mode", mode);
            metadata.put("ui", Map.of("defaultVisibility", "compact"));
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            log.warn("Failed to serialize stream metadata", e);
            return null;
        }
    }

    private String buildAgentProgressJson(AgentProgressEvent event) {
        if (event == null) {
            return null;
        }
        Map<String, Object> payload = new HashMap<>();
        payload.put("key", event.key());
        payload.put("label", event.label());
        payload.put("detail", event.detail());
        payload.put("status", event.status());
        if (event.stepIndex() != null) {
            payload.put("stepIndex", event.stepIndex());
        }
        if (event.toolName() != null && !event.toolName().isBlank()) {
            payload.put("toolName", event.toolName());
        }
        if (event.runId() != null && !event.runId().isBlank()) {
            payload.put("agentRunId", event.runId());
        }
        if (event.confidence() != null) {
            payload.put("confidence", event.confidence());
        }
        return writeJsonQuietly(payload);
    }

    private String writeJsonQuietly(Map<String, Object> payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.debug("Failed to serialize streaming payload", e);
            return null;
        }
    }

    private PreparedConversationTurn prepareConversationTurn(String connectionId, String chatId, String actualUserQuestion) {
        String resolvedChatId = resolveChatId(connectionId, chatId);
        ResolvedConversationContext matchedContext =
            conversationContextService.resolveRelatedContext(connectionId, resolvedChatId, actualUserQuestion);
        ChatQuestionRoutingService.QuestionRoute rawRoute = chatQuestionRoutingService.classify(actualUserQuestion);
        ConversationCarryoverDecision carryoverDecision =
            conversationContextService.decideCarryover(actualUserQuestion, rawRoute, matchedContext);
        if (carryoverDecision == null) {
            carryoverDecision = ConversationCarryoverDecision.empty();
        }

        List<com.dbaagent.service.agent.AgentExecutionContext.ConversationTurn> conversationHistory =
            loadRecentConversationHistory(resolvedChatId);
        String effectiveQuestion = actualUserQuestion;

        return new PreparedConversationTurn(
            resolvedChatId,
            matchedContext,
            carryoverDecision,
            conversationHistory,
            effectiveQuestion,
            rawRoute,
            promptIntentAnalyzer.analyze(actualUserQuestion, effectiveQuestion, matchedContext, rawRoute)
        );
    }

    private List<com.dbaagent.service.agent.AgentExecutionContext.ConversationTurn> loadRecentConversationHistory(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return List.of();
        }

        try {
            List<com.dbaagent.model.ChatMessage> allMessages = chatHistoryService.getChatMessages(chatId);
            if (allMessages == null || allMessages.isEmpty()) {
                return List.of();
            }

            int startIndex = Math.max(0, allMessages.size() - AGENT_HISTORY_MESSAGES);
            List<com.dbaagent.service.agent.AgentExecutionContext.ConversationTurn> turns = new ArrayList<>();
            for (com.dbaagent.model.ChatMessage msg : allMessages.subList(startIndex, allMessages.size())) {
                if (msg == null || msg.getContent() == null || msg.getContent().isBlank()) {
                    continue;
                }
                turns.add(new com.dbaagent.service.agent.AgentExecutionContext.ConversationTurn(
                    msg.getRole() != null ? msg.getRole().name().toLowerCase(Locale.ROOT) : "user",
                    cap(msg.getContent().replaceAll("\\s+", " ").trim(), 900)
                ));
            }
            return List.copyOf(turns);
        } catch (Exception e) {
            log.warn("Failed to load recent conversation history for chatId={}: {}", chatId, e.getMessage());
            return List.of();
        }
    }

    private String buildConversationAwareQuestion(
            String actualUserQuestion,
            List<com.dbaagent.service.agent.AgentExecutionContext.ConversationTurn> conversationHistory) {
        if (actualUserQuestion == null || actualUserQuestion.isBlank()) {
            return actualUserQuestion;
        }
        if (conversationHistory == null || conversationHistory.isEmpty() || !needsConversationCarryover(actualUserQuestion)) {
            return actualUserQuestion;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Recent conversation context:\n");
        for (com.dbaagent.service.agent.AgentExecutionContext.ConversationTurn turn : conversationHistory) {
            if (turn == null || turn.content() == null || turn.content().isBlank()) {
                continue;
            }
            String role = switch (turn.role().toLowerCase(Locale.ROOT)) {
                case "assistant" -> "Assistant";
                case "system" -> "System";
                default -> "User";
            };
            sb.append(role).append(": ").append(cap(turn.content(), 320)).append("\n");
        }
        sb.append("Current user request: ").append(actualUserQuestion);
        return sb.toString();
    }

    private boolean needsConversationCarryover(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }

        String normalized = question.toLowerCase(Locale.ROOT).trim();
        return FOLLOW_UP_CONTEXT_PATTERN.matcher(normalized).find()
            || CLARIFICATION_FOLLOW_UP_PATTERN.matcher(normalized).find()
            || normalized.startsWith("and ")
            || normalized.startsWith("also ")
            || normalized.startsWith("then ")
            || normalized.startsWith("same ")
            || normalized.startsWith("now ")
            || normalized.startsWith("only ")
            || normalized.startsWith("just ")
            || normalized.startsWith("continue ")
            || normalized.startsWith("keep ");
    }

    private void syncConversationMemoryFromHistory(String chatId) {
        if (chatMemory == null || chatId == null || chatId.isBlank()) {
            return;
        }

        try {
            chatMemory.clear(chatId);
            var canonicalHistory = chatHistoryService.getChatMessages(chatId);
            for (var msg : canonicalHistory) {
                if (msg.getRole() == com.dbaagent.model.ChatMessage.MessageRole.USER) {
                    chatMemory.add(chatId, new UserMessage(msg.getContent()));
                } else if (msg.getRole() == com.dbaagent.model.ChatMessage.MessageRole.ASSISTANT) {
                    chatMemory.add(chatId, new AssistantMessage(msg.getContent()));
                }
            }
            log.debug("Synced JDBC chat memory from PostgreSQL history: chatId={}, messages={}",
                chatId, canonicalHistory.size());
        } catch (Exception e) {
            log.warn("Failed to sync JDBC chat memory from history for chatId={}", chatId, e);
        }
    }

    private List<BusinessRuleMemoryService.SqlGuardrail> dedupeGuardrails(
            List<BusinessRuleMemoryService.SqlGuardrail> guardrails) {
        if (guardrails == null || guardrails.isEmpty()) {
            return List.of();
        }

        Map<String, BusinessRuleMemoryService.SqlGuardrail> deduped = new HashMap<>();
        for (BusinessRuleMemoryService.SqlGuardrail guardrail : guardrails) {
            if (guardrail == null) {
                continue;
            }
            String key = String.join("|",
                guardrail.type() != null ? guardrail.type().name() : "",
                guardrail.tableName() != null ? guardrail.tableName().toLowerCase() : "",
                guardrail.columnName() != null ? guardrail.columnName().toLowerCase() : "",
                guardrail.value() != null ? guardrail.value().toLowerCase() : ""
            );

            BusinessRuleMemoryService.SqlGuardrail existing = deduped.get(key);
            if (existing == null) {
                deduped.put(key, guardrail);
                continue;
            }

            int existingConfidence = existing.confidence() != null ? existing.confidence() : 0;
            int currentConfidence = guardrail.confidence() != null ? guardrail.confidence() : 0;
            if (currentConfidence > existingConfidence) {
                deduped.put(key, guardrail);
            }
        }

        return new ArrayList<>(deduped.values());
    }

    public RetrievedContextResult debugRagContext(String connectionId, String question) {
        String actualQuestion = extractActualUserQuestion(question);
        try {
            SchemaMetadata schema = schemaScannerService.scanSchema(connectionId);
            return chatRetrievalContextService.buildContext(connectionId, actualQuestion, schema);
        } catch (Exception e) {
            log.warn("Failed to load schema for debug RAG context, using empty schema: {}", e.getMessage());
            SchemaMetadata emptySchema = new SchemaMetadata();
            emptySchema.setTables(new java.util.ArrayList<>());
            emptySchema.setRelationships(new java.util.ArrayList<>());
            return chatRetrievalContextService.buildContext(connectionId, actualQuestion, emptySchema);
        }
    }

    private RagContextResult buildManualRagContext(
        String connectionId,
        String actualUserQuestion,
        RetrievalIntent retrievalIntent,
        SchemaMetadata schema
    ) {
        if (isSimpleSchemaQuestion(actualUserQuestion)) {
            log.debug("Skipping RAG for simple schema question");
            return RagContextResult.skipped(retrievalIntent, "simple_schema_question");
        }

        long ragStart = System.currentTimeMillis();
        int retrievalTopK = resolveRetrievalTopK(retrievalIntent);

        // Stage 1 + 3: Hybrid RAG search (reused as Stage 3 fallback)
        List<TrainingDataEmbedding> ragResults = trainingService.cachedRetrieveRelevant(
            connectionId, actualUserQuestion, retrievalTopK);
        ragResults = prioritizeTrainingDataByIntent(ragResults, retrievalIntent);

        // Stage 1: Resolve relevant tables (substring + RAG + 2-hop FK expansion)
        String dbType = schema.getDbType();
        Set<QualifiedTableName> resolvedTables = trainingService.resolveRelevantTables(
                schema, actualUserQuestion, ragResults, dbType);
        log.info("Stage 1 resolved {} tables: {}", resolvedTables.size(), resolvedTables);

        // Stage 2: Targeted retrieval by table name (if v2 index active)
        List<TrainingDataEmbedding> stage2Results = trainingService.retrieveTargetedByTables(
                connectionId, resolvedTables, 100);
        log.info("Stage 2 retrieved {} targeted docs (stage2Enabled={})",
                stage2Results.size(), !stage2Results.isEmpty());

        // Stage 3: Dedup RAG results against Stage 2 (no extra embedding call)
        List<TrainingDataEmbedding> stage3Results = trainingService.deduplicateAgainstTargeted(
                ragResults, stage2Results);
        log.info("Stage 3 fallback: {} docs (after dedup from {} RAG results)",
                stage3Results.size(), ragResults.size());

        // Build training context: targeted first, then fallback
        String trainingContext = trainingService.buildTrainingContext(stage2Results, stage3Results);
        log.info("Training context: {}", trainingContext);

        // Extract table names from all results for schema context window
        List<TrainingDataEmbedding> allResults = new ArrayList<>(stage2Results);
        allResults.addAll(stage3Results);
        Set<String> ragTableNames = extractTableNamesFromRag(allResults);

        long manualRagDuration = System.currentTimeMillis() - ragStart;
        log.debug("Three-stage RAG retrieval (stage2={}, stage3={}, intent={}, ragTables={}) in {}ms",
            stage2Results.size(), stage3Results.size(), retrievalIntent, ragTableNames.size(), manualRagDuration);

        // Comparison mode logging
        if (ragComparisonMode && questionAnswerAdvisor != null) {
            log.info("=== RAG COMPARISON MODE ===");
            log.info("Manual RAG: {} examples retrieved in {}ms", allResults.size(), manualRagDuration);
            log.info("QuestionAnswerAdvisor: Will inject additional VectorStore context (separate from manual RAG)");
            log.info("Note: With advisor enabled, LLM receives BOTH manual RAG context in system prompt AND advisor-injected context");
        }

        Map<TrainingDataEmbedding.TrainingDataType, Long> typeCounts = allResults.stream()
            .filter(Objects::nonNull)
            .collect(Collectors.groupingBy(TrainingDataEmbedding::getType, Collectors.counting()));

        return new RagContextResult(
            trainingContext,
            ragTableNames,
            retrievalIntent,
            retrievalTopK,
            allResults.size(),
            manualRagDuration,
            typeCounts,
            false,
            null
        );
    }

    public record RagContextResult(
        String trainingContext,
        Set<String> ragTableNames,
        RetrievalIntent retrievalIntent,
        int retrievalTopK,
        int resultCount,
        long durationMs,
        Map<TrainingDataEmbedding.TrainingDataType, Long> typeCounts,
        boolean skipped,
        String skipReason
    ) {
        public static RagContextResult skipped(RetrievalIntent retrievalIntent, String skipReason) {
            return new RagContextResult(
                "",
                new HashSet<>(),
                retrievalIntent,
                0,
                0,
                0,
                Map.of(),
                true,
                skipReason
            );
        }
    }

    private String extractBusinessRuleTeaching(String userQuestion) {
        if (userQuestion == null || userQuestion.isBlank()) {
            return "";
        }

        String trimmed = userQuestion.trim();
        if (!BUSINESS_RULE_SIGNAL_PATTERN.matcher(trimmed).find()) {
            return "";
        }

        int maxLength = 1200;
        return trimmed.length() <= maxLength ? trimmed : trimmed.substring(0, maxLength);
    }

    private void persistBusinessRuleLearning(
            String connectionId,
            String chatId,
            String userQuestion,
            String teaching,
            String userId,
            SchemaMetadata schema) {
        if (!autoLearnFeedbackEnabled || connectionId == null || teaching == null || teaching.isBlank()) {
            return;
        }

        try {
            String normalizedTeaching = teaching.trim().replaceAll("\\s+", " ").toLowerCase();
            boolean alreadyLearned = feedbackService.getLearnableFeedback(connectionId).stream()
                .limit(200)
                .map(ChatFeedback::getFeedbackText)
                .filter(Objects::nonNull)
                .map(text -> text.trim().replaceAll("\\s+", " ").toLowerCase())
                .anyMatch(normalizedTeaching::equals);

            if (alreadyLearned) {
                return;
            }

            feedbackService.recordTeaching(
                connectionId,
                chatId,
                null,
                userQuestion,
                teaching,
                null,
                null
            );

            businessRuleMemoryService.learnFromFeedback(
                connectionId,
                teaching,
                null,
                null,
                userId != null ? userId : "chat-auto-learn",
                schema
            );

            trainingService.trainWithDocumentation(
                connectionId,
                SchemaDocumentation.DocumentationType.BUSINESS_TERM,
                buildLearningObjectName(teaching),
                null,
                teaching,
                "chat-correction,sql-join-rule,business-context",
                userQuestion,
                userId != null ? userId : "chat-auto-learn"
            );
            log.info("Auto-learned business rule for connection {} from chat feedback", connectionId);
        } catch (Exception e) {
            log.warn("Failed to auto-learn business rule feedback for connection {}", connectionId, e);
        }
    }

    private String buildLearningObjectName(String teaching) {
        String normalized = teaching == null
            ? "chat-rule"
            : teaching.trim().replaceAll("\\s+", " ").toLowerCase();
        int hash = Math.abs(normalized.hashCode());
        return "chat_rule_" + Integer.toHexString(hash);
    }
}
