package com.dbaagent.controller;

import com.dbaagent.model.AgentConversation;
import com.dbaagent.service.AgentConversationService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Per-user DeepSQL Agent conversation index. Lets the Agent tab list + resume a
 * user's chats from any device (server-side, keyed by DeepSQL identity). All
 * endpoints are scoped to the authenticated user by {@link AgentConversationService}.
 */
@RestController
@RequestMapping("/agent/conversations")
@RequiredArgsConstructor
public class AgentConversationController {

    private final AgentConversationService service;
    private final ObjectMapper objectMapper;

    /** List the current user's conversations for a connection (metadata only). */
    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> list(
            @RequestParam(value = "connectionId", required = false) String connectionId) {
        List<Map<String, Object>> rows = service.list(connectionId).stream()
            .map(c -> toDto(c, false))
            .toList();
        return ResponseEntity.ok(rows);
    }

    /** Fetch one conversation with its full transcript. */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> get(@PathVariable String id) {
        return ResponseEntity.ok(toDto(service.get(id), true));
    }

    /** Create a conversation bound to a freshly created agent session. */
    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> body) {
        AgentConversation c = service.create(
            asString(body.get("connectionId")),
            asString(body.get("agentSessionId")),
            asString(body.get("title")));
        return ResponseEntity.ok(toDto(c, true));
    }

    /** Update transcript/title and optionally re-point the agent session. */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> update(
            @PathVariable String id,
            @RequestBody Map<String, Object> body) {
        JsonNode transcript = body.containsKey("transcript")
            ? objectMapper.valueToTree(body.get("transcript"))
            : null;
        AgentConversation c = service.update(
            id, transcript, asString(body.get("title")), asString(body.get("agentSessionId")));
        return ResponseEntity.ok(toDto(c, false));
    }

    /** Archive (soft-delete) a conversation. */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> archive(@PathVariable String id) {
        service.archive(id);
        return ResponseEntity.ok(Map.of("archived", true));
    }

    private Map<String, Object> toDto(AgentConversation c, boolean includeTranscript) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.getId());
        m.put("connectionId", c.getConnectionId());
        m.put("agentSessionId", c.getAgentSessionId());
        m.put("title", c.getTitle());
        m.put("lastMessageAt", c.getLastMessageAt());
        m.put("archived", c.isArchived());
        if (includeTranscript) {
            // Convert the stored JsonNode to plain objects so it serializes as the
            // original JSON array — returning a raw JsonNode makes Jackson emit its
            // bean getters ({"array":true,...}) instead of the value.
            JsonNode t = c.getTranscript();
            m.put("transcript", t == null || t.isNull() ? null : objectMapper.convertValue(t, Object.class));
        }
        return m;
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
