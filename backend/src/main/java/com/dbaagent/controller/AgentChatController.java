package com.dbaagent.controller;

import com.dbaagent.service.AgentTurnService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Brokered DeepSQL Agent chat for non-browser clients (the thin `deepsql` CLI).
 * The client talks only to this authenticated backend surface; the backend runs
 * the turn against the container agent internally and scopes everything to the
 * caller's identity — the client never names a profile or session.
 */
@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class AgentChatController {

    private final AgentTurnService agentTurnService;

    /** Run one agent turn and return the final answer (resumes the user's conversation). */
    @PostMapping("/chat")
    public ResponseEntity<Map<String, Object>> chat(@RequestBody Map<String, Object> body) {
        String message = asString(body.get("message"));
        if (message == null || message.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "message is required"));
        }
        AgentTurnService.TurnResult r = agentTurnService.runTurn(
            asString(body.get("connectionId")),
            asString(body.get("conversationId")),
            message);

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("ok", r.ok());
        resp.put("answer", r.answer());
        resp.put("toolSteps", r.toolSteps());
        resp.put("conversationId", r.conversationId());
        if (r.error() != null) {
            resp.put("error", r.error());
        }
        return ResponseEntity.ok(resp);
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
