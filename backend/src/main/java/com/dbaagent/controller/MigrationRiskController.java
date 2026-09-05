package com.dbaagent.controller;

import com.dbaagent.dto.MigrationRiskReport;
import com.dbaagent.service.migration.MigrationRiskService;
import com.dbaagent.service.security.AccessControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestController
@RequestMapping("/migrations")
@RequiredArgsConstructor
public class MigrationRiskController {

    private final MigrationRiskService migrationRiskService;
    private final AccessControlService accessControlService;

    @PostMapping("/analyze")
    public ResponseEntity<?> analyze(@RequestBody Map<String, String> body) {
        String connectionId = body.get("connectionId");
        String sql = body.get("sql");
        if (connectionId == null || connectionId.isBlank() || sql == null || sql.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "connectionId and sql are required"));
        }
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            MigrationRiskReport report = migrationRiskService.analyze(connectionId, sql);
            return ResponseEntity.ok(report);
        } catch (ResponseStatusException e) {
            throw e;   // preserve 403/404 — a catch-all below would report it as a 500
        } catch (Exception e) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("message", "Migration analysis failed: " + e.getMessage()));
        }
    }
}
