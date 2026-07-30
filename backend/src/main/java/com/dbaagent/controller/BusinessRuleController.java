package com.dbaagent.controller;

import com.dbaagent.model.brain.BrainRule;
import com.dbaagent.service.BusinessRuleMemoryService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * API endpoints for connection-scoped learned SQL business rules.
 */
@RestController
@RequestMapping("/business-rules")
@RequiredArgsConstructor
public class BusinessRuleController {

    private final BusinessRuleMemoryService businessRuleMemoryService;

    /**
     * Returns all active rules for the connection plus the subset applicable to an optional question.
     */
    @GetMapping("/connection/{connectionId}")
    public ResponseEntity<Map<String, Object>> getRules(
            @PathVariable String connectionId,
            @RequestParam(required = false) String question) {
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
