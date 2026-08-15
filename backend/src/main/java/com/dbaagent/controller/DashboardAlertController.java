package com.dbaagent.controller;

import com.dbaagent.model.DashboardAlert;
import com.dbaagent.model.SavedDashboard;
import com.dbaagent.service.DashboardAlertService;
import com.dbaagent.service.SavedDashboardService;
import com.dbaagent.service.security.AccessControlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** CRUD for per-dashboard natural-language alerts — see DashboardAlertService for evaluation. */
@RestController
@RequestMapping("/saved-dashboards/{dashboardId}/alerts")
@RequiredArgsConstructor
@Slf4j
public class DashboardAlertController {

    private final DashboardAlertService alertService;
    private final SavedDashboardService savedDashboardService;
    private final AccessControlService accessControlService;

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@PathVariable UUID dashboardId, @RequestBody DashboardAlert draft) {
        try {
            SavedDashboard dashboard = requireDashboard(dashboardId);
            accessControlService.assertCanManageConnectionContent(dashboard.getConnectionId());
            String username = accessControlService.requireCurrentUsername();
            DashboardAlert created = alertService.createAlert(dashboardId, username, draft);
            return ResponseEntity.status(HttpStatus.CREATED).body(Map.of("success", true, "alert", created));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("success", false, "message", e.getMessage()));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error creating dashboard alert", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("success", false, "message", "Failed to create alert"));
        }
    }

    @GetMapping
    public ResponseEntity<Map<String, Object>> list(@PathVariable UUID dashboardId) {
        try {
            SavedDashboard dashboard = requireDashboard(dashboardId);
            accessControlService.assertCanReadConnectionContent(dashboard.getConnectionId());
            List<DashboardAlert> alerts = alertService.getAlertsForDashboard(dashboardId);
            return ResponseEntity.ok(Map.of("success", true, "alerts", alerts));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("success", false, "message", e.getMessage()));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error listing dashboard alerts", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("success", false, "message", "Failed to fetch alerts"));
        }
    }

    @PutMapping("/{alertId}")
    public ResponseEntity<Map<String, Object>> update(@PathVariable UUID dashboardId, @PathVariable UUID alertId, @RequestBody DashboardAlert updates) {
        try {
            SavedDashboard dashboard = requireDashboard(dashboardId);
            accessControlService.assertCanManageConnectionContent(dashboard.getConnectionId());
            DashboardAlert updated = alertService.updateAlert(alertId, updates);
            return ResponseEntity.ok(Map.of("success", true, "alert", updated));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("success", false, "message", e.getMessage()));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error updating dashboard alert", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("success", false, "message", "Failed to update alert"));
        }
    }

    @DeleteMapping("/{alertId}")
    public ResponseEntity<Map<String, Object>> delete(@PathVariable UUID dashboardId, @PathVariable UUID alertId) {
        try {
            SavedDashboard dashboard = requireDashboard(dashboardId);
            accessControlService.assertCanManageConnectionContent(dashboard.getConnectionId());
            alertService.deleteAlert(alertId);
            return ResponseEntity.ok(Map.of("success", true));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error deleting dashboard alert", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("success", false, "message", "Failed to delete alert"));
        }
    }

    private SavedDashboard requireDashboard(UUID dashboardId) {
        return savedDashboardService.getDashboardById(dashboardId)
            .orElseThrow(() -> new IllegalArgumentException("Dashboard not found"));
    }
}
