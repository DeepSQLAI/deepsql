package com.dbaagent.service.agent;

@FunctionalInterface
public interface AgentProgressListener {
    void onEvent(AgentProgressEvent event);

    static AgentProgressListener noop() {
        return event -> { };
    }
}
