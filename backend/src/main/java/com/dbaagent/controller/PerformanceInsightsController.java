package com.dbaagent.controller;

import com.dbaagent.dto.TableUsageDTO;
import com.dbaagent.model.DatabaseConnection;
import com.dbaagent.model.PerformanceSnapshot;
import com.dbaagent.service.CredentialService;
import com.dbaagent.service.PerformanceInsightsService;
import com.dbaagent.service.security.AccessControlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * Performance Insights Controller
 * Provides AWS RDS Performance Insights-style APIs
 *
 * <p><b>Authorization:</b> every endpoint here takes a caller-supplied connection id, so
 * each one asserts access itself ({@code assertCanReadConnectionContent} for reads,
 * {@code assertCanManageConnectionContent} for writes). {@code SecurityConfig} only
 * requires an authenticated principal — nothing upstream inspects a connection id. See
 * {@code ConnectionScopedAuthorizationSafetyTest}.
 */
@RestController
@RequestMapping("/performance-insights")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class PerformanceInsightsController {

    private final PerformanceInsightsService performanceInsightsService;
    private final CredentialService credentialService;
    private final AccessControlService accessControlService;

    /**
     * GET /api/performance-insights/{connectionId}
     * Get performance snapshots for a time range
     */
    @GetMapping("/{connectionId}")
    public ResponseEntity<?> getSnapshots(
            @PathVariable String connectionId,
            @RequestParam(defaultValue = "1") int hours) {
        accessControlService.assertCanReadConnectionContent(connectionId);

        try {
            log.info("Fetching performance snapshots for connection: {}, hours: {}", connectionId, hours);

            LocalDateTime endTime = LocalDateTime.now();
            LocalDateTime startTime = endTime.minusHours(hours);

            List<PerformanceSnapshot> snapshots = performanceInsightsService.getSnapshots(
                    connectionId, startTime, endTime);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "snapshots", snapshots,
                    "count", snapshots.size(),
                    "startTime", startTime.toString(),
                    "endTime", endTime.toString()
            ));

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to fetch performance snapshots for connection {}: {}",
                    connectionId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", "Failed to fetch performance snapshots",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * GET /api/performance-insights/{connectionId}/recent
     * Get recent snapshots
     */
    @GetMapping("/{connectionId}/recent")
    public ResponseEntity<?> getRecentSnapshots(
            @PathVariable String connectionId,
            @RequestParam(defaultValue = "12") int limit) {
        accessControlService.assertCanReadConnectionContent(connectionId);

        try {
            log.info("Fetching {} recent performance snapshots for connection: {}", limit, connectionId);

            List<PerformanceSnapshot> snapshots = performanceInsightsService.getRecentSnapshots(
                    connectionId, limit);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "snapshots", snapshots,
                    "count", snapshots.size()
            ));

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to fetch recent performance snapshots for connection {}: {}",
                    connectionId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", "Failed to fetch recent snapshots",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * POST /api/performance-insights/{connectionId}/collect
     * Manually trigger snapshot collection
     */
    @PostMapping("/{connectionId}/collect")
    public ResponseEntity<?> collectSnapshot(@PathVariable String connectionId) {
        accessControlService.assertCanManageConnectionContent(connectionId);

        try {
            log.info("Manual snapshot collection triggered for connection: {}", connectionId);

            DatabaseConnection connection = credentialService.getAllConnections().stream()
                    .filter(c -> c.getId().equals(connectionId))
                    .findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Connection not found"));

            PerformanceSnapshot snapshot = performanceInsightsService.collectSnapshot(connection);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Snapshot collected successfully",
                    "snapshot", snapshot
            ));

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to collect snapshot for connection {}: {}",
                    connectionId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", "Failed to collect snapshot",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * GET /api/performance-insights/{connectionId}/summary
     * Get aggregated performance summary
     */
    @GetMapping("/{connectionId}/summary")
    public ResponseEntity<?> getSummary(
            @PathVariable String connectionId,
            @RequestParam(defaultValue = "1") int hours) {
        accessControlService.assertCanReadConnectionContent(connectionId);

        try {
            log.info("Fetching performance summary for connection: {}, hours: {}", connectionId, hours);

            LocalDateTime endTime = LocalDateTime.now();
            LocalDateTime startTime = endTime.minusHours(hours);

            List<PerformanceSnapshot> snapshots = performanceInsightsService.getSnapshots(
                    connectionId, startTime, endTime);

            if (snapshots.isEmpty()) {
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "No snapshots available for this time range",
                        "summary", Map.of()
                ));
            }

            // Calculate summary statistics
            Map<String, Object> summary = new HashMap<>();

            // Average DB time
            double avgDbTime = snapshots.stream()
                    .mapToDouble(s -> s.getTotalDbTimeMs() != null ? s.getTotalDbTimeMs() : 0.0)
                    .average()
                    .orElse(0.0);
            summary.put("avgDbTimeMs", avgDbTime);

            // Peak DB time
            double peakDbTime = snapshots.stream()
                    .mapToDouble(s -> s.getTotalDbTimeMs() != null ? s.getTotalDbTimeMs() : 0.0)
                    .max()
                    .orElse(0.0);
            summary.put("peakDbTimeMs", peakDbTime);

            // Average connections
            double avgConnections = snapshots.stream()
                    .mapToDouble(s -> s.getActiveConnections() != null ? s.getActiveConnections() : 0.0)
                    .average()
                    .orElse(0.0);
            summary.put("avgConnections", avgConnections);

            // Peak connections
            int peakConnections = snapshots.stream()
                    .mapToInt(s -> s.getActiveConnections() != null ? s.getActiveConnections() : 0)
                    .max()
                    .orElse(0);
            summary.put("peakConnections", peakConnections);

            // Total query count
            long totalQueries = snapshots.stream()
                    .mapToLong(s -> s.getTotalQueryCount() != null ? s.getTotalQueryCount() : 0)
                    .sum();
            summary.put("totalQueries", totalQueries);

            // Average CPU (if available)
            double avgCpu = snapshots.stream()
                    .filter(s -> s.getCpuPercent() != null)
                    .mapToDouble(PerformanceSnapshot::getCpuPercent)
                    .average()
                    .orElse(0.0);
            summary.put("avgCpuPercent", avgCpu);

            // Time range
            summary.put("startTime", startTime.toString());
            summary.put("endTime", endTime.toString());
            summary.put("snapshotCount", snapshots.size());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "summary", summary
            ));

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to fetch performance summary for connection {}: {}",
                    connectionId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", "Failed to fetch performance summary",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * GET /api/performance-insights/table-usage/{connectionId}
     * Get table usage statistics for heatmap visualization
     */
    @GetMapping("/table-usage/{connectionId}")
    public ResponseEntity<?> getTableUsage(@PathVariable String connectionId) {
        accessControlService.assertCanReadConnectionContent(connectionId);

        try {
            log.info("Fetching table usage for connection: {}", connectionId);

            List<TableUsageDTO> tableUsage = performanceInsightsService.getTableUsage(connectionId);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "tables", tableUsage,
                    "count", tableUsage.size()
            ));

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to fetch table usage for connection {}: {}",
                    connectionId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", "Failed to fetch table usage",
                    "message", e.getMessage()
            ));
        }
    }
}
