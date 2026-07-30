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
        if (entry.getConnectionId() != null && !entry.getConnectionId().isBlank()) {
            accessControlService.assertCanManageConnectionContent(entry.getConnectionId());
        }
        if (entry.getCreatedBy() == null || entry.getCreatedBy().isBlank()) {
            entry.setCreatedBy(accessControlService.getCurrentUsername());
        }
        return ResponseEntity.ok(companyKnowledgeService.updateEntry(entryId, entry));
    }

    @DeleteMapping("/{entryId}")
    public ResponseEntity<Void> delete(
            @PathVariable String entryId,
            @RequestParam String connectionId) {
        accessControlService.assertCanManageConnectionContent(connectionId);
        companyKnowledgeService.deleteEntry(entryId);
        return ResponseEntity.ok().build();
    }
}
