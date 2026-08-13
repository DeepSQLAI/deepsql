package com.dbaagent.controller;

import com.dbaagent.model.Playbook;
import com.dbaagent.model.PlaybookAlert;
import com.dbaagent.model.PlaybookRun;
import com.dbaagent.service.PlaybookExecutionService;
import com.dbaagent.service.PlaybookService;
import com.dbaagent.service.security.AccessControlService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/playbooks")
@RequiredArgsConstructor
@CrossOrigin(origins = "*")
public class PlaybookController {

    private final PlaybookService playbookService;
    private final PlaybookExecutionService playbookExecutionService;
    private final AccessControlService accessControlService;

    // ========== PLAYBOOK MANAGEMENT ==========

    @GetMapping
    public ResponseEntity<Map<String, Object>> getAllPlaybooks(
            @RequestParam(required = false) String dbType,
            @RequestParam(required = false) String category) {

        Map<String, Object> response = new HashMap<>();

        try {
            List<Playbook> playbooks;

            if (dbType != null) {
                playbooks = playbookService.getPlaybooksForDbType(dbType);
            } else if (category != null) {
                playbooks = playbookService.getPlaybooksByCategory(category);
            } else {
                playbooks = playbookService.getAllActivePlaybooks();
            }

            response.put("success", true);
            response.put("playbooks", playbooks);
            response.put("count", playbooks.size());

            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to fetch playbooks: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @GetMapping("/{playbookId}")
    public ResponseEntity<Map<String, Object>> getPlaybook(@PathVariable String playbookId) {
        Map<String, Object> response = new HashMap<>();

        try {
            Playbook playbook = playbookService.getPlaybook(playbookId);
            response.put("success", true);
            response.put("playbook", playbook);
            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to fetch playbook: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createPlaybook(@RequestBody Playbook playbook) {
        Map<String, Object> response = new HashMap<>();

        try {
            Playbook created = playbookService.createPlaybook(playbook);
            response.put("success", true);
            response.put("playbook", created);
            response.put("message", "Playbook created successfully");
            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to create playbook: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @PutMapping("/{playbookId}")
    public ResponseEntity<Map<String, Object>> updatePlaybook(
            @PathVariable String playbookId,
            @RequestBody Playbook playbook) {

        Map<String, Object> response = new HashMap<>();

        try {
            Playbook updated = playbookService.updatePlaybook(playbookId, playbook);
            response.put("success", true);
            response.put("playbook", updated);
            response.put("message", "Playbook updated successfully");
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to update playbook: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @DeleteMapping("/{playbookId}")
    public ResponseEntity<Map<String, Object>> deletePlaybook(@PathVariable String playbookId) {
        Map<String, Object> response = new HashMap<>();

        try {
            playbookService.deletePlaybook(playbookId);
            response.put("success", true);
            response.put("message", "Playbook deleted successfully");
            return ResponseEntity.ok(response);
        } catch (IllegalStateException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to delete playbook: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @PostMapping("/{playbookId}/toggle")
    public ResponseEntity<Map<String, Object>> togglePlaybook(@PathVariable String playbookId) {
        Map<String, Object> response = new HashMap<>();

        try {
            Playbook playbook = playbookService.toggleActive(playbookId);
            response.put("success", true);
            response.put("playbook", playbook);
            response.put("message", "Playbook " + (playbook.getIsActive() ? "activated" : "deactivated"));
            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to toggle playbook: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    // ========== PLAYBOOK EXECUTION ==========

    @PostMapping("/{playbookId}/execute")
    public ResponseEntity<Map<String, Object>> executePlaybook(
            @PathVariable String playbookId,
            @RequestBody ExecuteRequest request) {

        Map<String, Object> response = new HashMap<>();

        try {
            accessControlService.assertCanManageConnectionContent(request.getConnectionId());
            PlaybookRun run = playbookExecutionService.executePlaybook(
                playbookId,
                request.getConnectionId()
            );

            response.put("success", true);
            response.put("run", run);
            response.put("message", "Playbook execution completed");

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            response.put("success", false);
            response.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Playbook execution failed: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @GetMapping("/runs/{connectionId}")
    public ResponseEntity<Map<String, Object>> getRunHistory(
            @PathVariable String connectionId,
            @RequestParam(defaultValue = "50") int limit) {

        Map<String, Object> response = new HashMap<>();

        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            List<PlaybookRun> runs = playbookExecutionService.getRunHistory(connectionId, limit);
            Map<String, Object> stats = playbookExecutionService.getRunStatistics(connectionId);

            response.put("success", true);
            response.put("runs", runs);
            response.put("statistics", stats);

            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to fetch run history: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @PostMapping("/runs/{runId}/cancel")
    public ResponseEntity<Map<String, Object>> cancelRun(@PathVariable String runId) {
        Map<String, Object> response = new HashMap<>();

        try {
            PlaybookRun run = playbookExecutionService.requireRunById(runId);
            accessControlService.assertCanManageConnectionContent(run.getConnectionId());
            playbookExecutionService.cancelRun(runId);
            response.put("success", true);
            response.put("message", "Run cancelled successfully");
            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to cancel run: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    // ========== ALERTS ==========

    @GetMapping("/alerts/{connectionId}")
    public ResponseEntity<Map<String, Object>> getAlerts(
            @PathVariable String connectionId,
            @RequestParam(defaultValue = "false") boolean unacknowledgedOnly) {

        Map<String, Object> response = new HashMap<>();

        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            List<PlaybookAlert> alerts = unacknowledgedOnly ?
                playbookService.getUnacknowledgedAlerts(connectionId) :
                playbookService.getAlertsForConnection(connectionId);

            Map<String, Object> stats = playbookService.getAlertStatistics(connectionId);

            response.put("success", true);
            response.put("alerts", alerts);
            response.put("statistics", stats);

            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to fetch alerts: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    @PostMapping("/alerts/{alertId}/acknowledge")
    public ResponseEntity<Map<String, Object>> acknowledgeAlert(
            @PathVariable String alertId,
            @RequestBody(required = false) AcknowledgeRequest request) {

        Map<String, Object> response = new HashMap<>();

        try {
            PlaybookAlert existing = playbookService.requireAlertById(alertId);
            accessControlService.assertCanManageConnectionContent(existing.getConnectionId());
            String acknowledgedBy = request != null ? request.getAcknowledgedBy() : "user";
            PlaybookAlert alert = playbookService.acknowledgeAlert(alertId, acknowledgedBy);

            response.put("success", true);
            response.put("alert", alert);
            response.put("message", "Alert acknowledged");

            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to acknowledge alert: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    // ========== REQUEST DTOs ==========

    @Data
    public static class ExecuteRequest {
        private String connectionId;
    }

    @Data
    public static class AcknowledgeRequest {
        private String acknowledgedBy;
    }
}
