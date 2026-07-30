package com.dbaagent.service.agent;

import com.dbaagent.service.ChatQuestionRoutingService;
import org.springframework.stereotype.Service;

import java.util.Locale;

@Service
public class AgentPromptClassifier {

    public AgentDecision classify(String question, ChatQuestionRoutingService.QuestionRoute route) {
        if (question == null || question.isBlank()) {
            return AgentDecision.none();
        }

        String normalized = question.toLowerCase(Locale.ROOT).trim();
        if (normalized.isEmpty()) {
            return AgentDecision.none();
        }

        if (route != null && route.isBrainMetadata()) {
            return new AgentDecision(true, AgentIntent.METADATA_ANALYSIS, route.brainTopic().name());
        }

        String routeReason = route != null ? route.type().name() : ChatQuestionRoutingService.RouteType.GENERAL.name();
        return new AgentDecision(true, AgentIntent.UNIVERSAL_CHAT, routeReason);
    }
}
