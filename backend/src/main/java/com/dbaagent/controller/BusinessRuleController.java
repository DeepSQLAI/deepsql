package com.dbaagent.controller;

import com.dbaagent.model.brain.BrainRule;
import com.dbaagent.service.BusinessRuleMemoryService;
import com.dbaagent.service.security.AccessControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * API endpoints for connection-scoped learned SQL business rules.
 *
 * <p><b>Authorization:</b> every endpoint here takes a caller-supplied connection id, so
 * each one asserts access itself ({@code assertCanReadConnectionContent} for reads,
 * {@code assertCanManageConnectionContent} for writes). {@code SecurityConfig} only
 * requires an authenticated principal — nothing upstream inspects a connection id. See
 * {@code ConnectionScopedAuthorizationSafetyTest}.
 */
@RestController
@RequestMapping("/business-rules")
@RequiredArgsConstructor
public class BusinessRuleController {

    private final BusinessRuleMemoryService businessRuleMemoryService;
    private final AccessControlService accessControlService;

    /**
     * Returns all active rules for the connection plus the subset applicable to an optional question.
     */
    @GetMapping("/connection/{connectionId}")
    public ResponseEntity<Map<String, Object>> getRules(
            @PathVariable String connectionId,
            @RequestParam(required = false) String question) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        List<BrainRule> activeRules = businessRuleMemoryService.getActiveRules(connectionId);
        List<BusinessRuleMemoryService.SqlGuardrail> applicable = businessRuleMemoryService
            .resolveApplicableGuardrails(connectionId, question, null);

        return ResponseEntity.ok(Map.of(
            "connectionId", connectionId,
            "activeRuleCount", activeRules.size(),
            "activeRules", activeRules,
            "applicableGuardrailCount", applicable.size(),
            "applicableGuardrails", applicable,
            "guardrailContext", businessRuleMemoryService.buildGuardrailContext(applicable)
        ));
    }

    /**
     * Manual rule ingestion endpoint for operational/debug use.
     *
     * The learned rules are stored as connection-scoped SQL_* brain rules.
     */
    @PostMapping("/connection/{connectionId}/learn")
    public ResponseEntity<Map<String, Object>> learn(
            @PathVariable String connectionId,
            @RequestBody LearnRuleRequest request) {
        accessControlService.assertCanManageConnectionContent(connectionId);
        int learned = businessRuleMemoryService.learnFromFeedback(
            connectionId,
            request.text(),
            request.tableName(),
            request.columnName(),
            request.createdBy(),
            null
        );

        return ResponseEntity.ok(Map.of(
            "connectionId", connectionId,
            "learnedCount", learned,
            "activeRuleCount", businessRuleMemoryService.getActiveRules(connectionId).size()
        ));
    }

    /**
     * Deactivate a single rule by ID.
     */
    @DeleteMapping("/rule/{ruleId}")
    public ResponseEntity<Map<String, Object>> deactivateRule(@PathVariable String ruleId) {
        String connectionId = businessRuleMemoryService.findConnectionIdForRule(ruleId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Rule not found"));
        accessControlService.assertCanManageConnectionContent(connectionId);
        boolean deactivated = businessRuleMemoryService.deactivateRule(ruleId);
        return ResponseEntity.ok(Map.of(
            "ruleId", ruleId,
            "deactivated", deactivated
        ));
    }

    public record LearnRuleRequest(
        String text,
        String tableName,
        String columnName,
        String createdBy
    ) {
    }
}
