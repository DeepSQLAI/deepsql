package com.dbaagent.service.agent;

import com.dbaagent.service.ChatQuestionRoutingService;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class MetadataContextResolutionTool implements AgentTool {

    private final MetadataRequestScopeResolver metadataRequestScopeResolver;

    public MetadataContextResolutionTool(MetadataRequestScopeResolver metadataRequestScopeResolver) {
        this.metadataRequestScopeResolver = metadataRequestScopeResolver;
    }

    @Override
    public String name() {
        return "metadata_context_resolution_tool";
    }

    @Override
    public AgentToolResult execute(AgentPlanStep step, AgentExecutionContext context) {
        String brainTopic = String.valueOf(step.params().getOrDefault("brainTopic", "GENERAL"));
        ChatQuestionRoutingService.QuestionRoute route = new ChatQuestionRoutingService.QuestionRoute(
            ChatQuestionRoutingService.RouteType.BRAIN_METADATA,
            parseBrainTopic(brainTopic)
        );
        MetadataRequestScope requestScope = metadataRequestScopeResolver.resolve(
            context.effectiveQuestion(),
            context.schema(),
            route,
            context.promptIntent()
        );
        context.putMemory("metadataRequestScope", requestScope);

        Map<String, Object> data = new LinkedHashMap<>(requestScope.toMap());
        data.put("routeType", route.type().name());
        data.put("brainTopic", route.brainTopic().name());

        return new AgentToolResult(
            new AgentObservation(
                "metadata_request_scope",
                "Resolved metadata request scope for " + requestScope.factType().name().toLowerCase(),
                Map.copyOf(data)
            ),
            null,
            null,
            0.98
        );
    }

    private ChatQuestionRoutingService.BrainTopic parseBrainTopic(String value) {
        try {
            return ChatQuestionRoutingService.BrainTopic.valueOf(value);
        } catch (Exception e) {
            return ChatQuestionRoutingService.BrainTopic.GENERAL;
        }
    }
}
