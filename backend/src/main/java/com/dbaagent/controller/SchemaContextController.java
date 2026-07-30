package com.dbaagent.controller;

import com.dbaagent.service.codescan.SchemaAmbiguityService;
import com.dbaagent.service.security.AccessControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/schema-context")
@RequiredArgsConstructor
public class SchemaContextController {

    private final SchemaAmbiguityService ambiguityService;
    private final AccessControlService accessControlService;

    /**
     * Schema-ambiguity inventory: tables/columns the brain can't disambiguate
     * yet. Used by the Unresolved panel + as the seed list for code-scan focus.
     */
    @GetMapping("/ambiguity/{connectionId}")
    public ResponseEntity<Map<String, Object>> getAmbiguity(@PathVariable String connectionId) {
        accessControlService.assertCanManageConnectionContent(connectionId);
        List<SchemaAmbiguityService.AmbiguityItem> items = ambiguityService.compute(connectionId);
        return ResponseEntity.ok(Map.of("items", items, "count", items.size()));
    }
}
