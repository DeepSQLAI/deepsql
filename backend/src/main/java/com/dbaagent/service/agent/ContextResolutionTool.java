package com.dbaagent.service.agent;

import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.service.ChatRetrievalContextService;
import com.dbaagent.service.RetrievedContextResult;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ContextResolutionTool implements AgentTool {

    private final ChatRetrievalContextService chatRetrievalContextService;

    public ContextResolutionTool(ChatRetrievalContextService chatRetrievalContextService) {
        this.chatRetrievalContextService = chatRetrievalContextService;
    }

    @Override
    public String name() {
        return "context_resolution_tool";
    }

    @Override
    public AgentToolResult execute(AgentPlanStep step, AgentExecutionContext context) {
        SchemaMetadata schema = context.schema();
        if (schema == null) {
            return new AgentToolResult(
                new AgentObservation(
                    "context_resolution",
                    "Schema context is missing, so shared retrieval context could not be resolved",
                    Map.of("requiresSchema", true)
                ),
                null,
                null,
                List.of(),
                List.of(),
                List.of(),
                0.25
            );
        }

        String question = context.effectiveQuestion() != null && !context.effectiveQuestion().isBlank()
            ? context.effectiveQuestion()
            : context.question();
        MetadataRequestScope requestScope = context.getMemory("metadataRequestScope");
        RetrievedContextResult retrievedContext = shouldUseScopedSchemaRetrieval(context.promptIntent(), requestScope)
            ? chatRetrievalContextService.buildScopedContext(context.connectionId(), question, schema, requestScope.requestedTables())
            : chatRetrievalContextService.buildContext(context.connectionId(), question, schema);

        context.putMemory("sharedRetrievedContext", retrievedContext);

        Map<String, Object> artifactPayload = new LinkedHashMap<>();
        if (retrievedContext.retrievalIntent() != null) {
            artifactPayload.put("retrievalIntent", retrievedContext.retrievalIntent().name());
        }
        artifactPayload.put("ragTableNames", retrievedContext.ragTableNames());
        artifactPayload.put("typeCounts", retrievedContext.typeCounts());
        artifactPayload.put("durationMs", retrievedContext.durationMs());

        return new AgentToolResult(
            new AgentObservation(
                "context_resolution",
                "Resolved shared retrieval context for " + step.params().getOrDefault("taskCount", 0) + " planned task(s)",
                artifactPayload
            ),
            null,
            null,
            List.of(),
            List.of(),
            List.of(new AgentToolArtifact("retrieval_context", "shared-context", artifactPayload)),
            0.84
        );
    }

    private boolean shouldUseScopedSchemaRetrieval(PromptIntent promptIntent, MetadataRequestScope requestScope) {
        return promptIntent != null
            && promptIntent.domain() == PromptIntent.Domain.SCHEMA
            && promptIntent.isRelationshipFocused()
            && requestScope != null
            && requestScope.pairScoped()
            && requestScope.requestedTables().size() >= 2;
    }
}
