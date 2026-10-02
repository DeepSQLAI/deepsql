package com.dbaagent.controller;

import com.dbaagent.model.CompanyKnowledgeEntry;
import com.dbaagent.service.CompanyKnowledgeService;
import com.dbaagent.service.security.AccessControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/company-knowledge")
@RequiredArgsConstructor
public class CompanyKnowledgeController {

    private final CompanyKnowledgeService companyKnowledgeService;
    private final AccessControlService accessControlService;

    @GetMapping("/{connectionId}")
    public ResponseEntity<List<CompanyKnowledgeEntry>> list(@PathVariable String connectionId) {
        accessControlService.assertCanManageConnectionContent(connectionId);
        return ResponseEntity.ok(companyKnowledgeService.listEntries(connectionId));
    }

    @PostMapping
    public ResponseEntity<CompanyKnowledgeEntry> create(@RequestBody CompanyKnowledgeEntry entry) {
        accessControlService.assertCanManageConnectionContent(entry.getConnectionId());
        if (entry.getCreatedBy() == null || entry.getCreatedBy().isBlank()) {
            entry.setCreatedBy(accessControlService.getCurrentUsername());
        }
        return ResponseEntity.ok(companyKnowledgeService.createEntry(entry));
    }

    @PutMapping("/{entryId}")
    public ResponseEntity<CompanyKnowledgeEntry> update(
            @PathVariable String entryId,
            @RequestBody CompanyKnowledgeEntry entry) {
        // Authorise against the stored entry's connection, unconditionally. The old check ran
        // only when the body carried a connectionId, so omitting that field skipped it and let
        // any authenticated user edit any tenant's entry. The body's connectionId is never
        // trusted here; updateEntry already refuses to change it.
        assertCanManageEntry(entryId);
        if (entry.getCreatedBy() == null || entry.getCreatedBy().isBlank()) {
            entry.setCreatedBy(accessControlService.getCurrentUsername());
        }
        return ResponseEntity.ok(companyKnowledgeService.updateEntry(entryId, entry));
    }

    @DeleteMapping("/{entryId}")
    public ResponseEntity<Void> delete(
            @PathVariable String entryId,
            @RequestParam(required = false) String connectionId) {
        // The connectionId param was never compared to the entry being deleted; authorise on
        // the entry's own connection instead. Accepted for wire compatibility, not trusted.
        assertCanManageEntry(entryId);
        companyKnowledgeService.deleteEntry(entryId);
        return ResponseEntity.ok().build();
    }
    private void assertCanManageEntry(String entryId) {
        accessControlService.assertCanManageConnectionContentOrNotFound(
            companyKnowledgeService.findConnectionIdForEntry(entryId).orElse(null), "Knowledge entry");
    }
}
