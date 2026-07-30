package com.dbaagent.service.agent;

import java.util.Map;

public record AgentObservation(String type, String summary, Map<String, Object> data) {
}
