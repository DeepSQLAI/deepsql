package com.dbaagent.controller;

import com.dbaagent.dto.AgentRunTraceResponse;
import com.dbaagent.service.agent.AgentRunService;
import com.dbaagent.service.security.AccessControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/agent-runs")
@RequiredArgsConstructor
public class AgentRunController {

    private final AgentRunService agentRunService;
    private final AccessControlService accessControlService;

    @GetMapping("/{runId}")
    public ResponseEntity<AgentRunTraceResponse> getRunTrace(@PathVariable String runId) {
        return agentRunService.getRun(runId)
            .map(run -> {
                accessControlService.assertCanUseChatEditor(run.getConnectionId());
                return agentRunService.getTrace(runId)
                    .map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.notFound().build());
            })
            .orElseGet(() -> ResponseEntity.notFound().build());
    }
}
