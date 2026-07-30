package com.dbaagent.controller;

import com.dbaagent.model.ActiveQuery;
import com.dbaagent.service.ActiveQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/active-queries")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class ActiveQueryController {

    private final ActiveQueryService activeQueryService;

    /**
     * Capture current active queries from the database
     * POST /api/active-queries/capture/{connectionId}
     */
    @PostMapping("/capture/{connectionId}")
    public ResponseEntity<List<ActiveQuery>> captureActiveQueries(@PathVariable String connectionId) {
        log.info("API: Capturing active queries for connection {}", connectionId);
        try {
            List<ActiveQuery> queries = activeQueryService.captureActiveQueries(connectionId);
            return ResponseEntity.ok(queries);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error capturing active queries", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get the latest snapshot of active queries
     * GET /api/active-queries/latest/{connectionId}
     */
    @GetMapping("/latest/{connectionId}")
    public ResponseEntity<List<ActiveQuery>> getLatestQueries(@PathVariable String connectionId) {
        log.info("API: Getting latest queries for connection {}", connectionId);
        try {
            List<ActiveQuery> queries = activeQueryService.getLatestQueries(connectionId);
            return ResponseEntity.ok(queries);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting latest queries", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get queries by filter
     * GET /api/active-queries/filter/{connectionId}?type={filterType}&value={filterValue}
     */
    @GetMapping("/filter/{connectionId}")
    public ResponseEntity<List<ActiveQuery>> getQueriesByFilter(
            @PathVariable String connectionId,
            @RequestParam String type,
            @RequestParam String value) {
        log.info("API: Getting queries for connection {} filtered by {}={}", connectionId, type, value);
        try {
            List<ActiveQuery> queries = activeQueryService.getQueriesByFilter(connectionId, type, value);
            return ResponseEntity.ok(queries);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting filtered queries", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get statistics about active queries
     * GET /api/active-queries/stats/{connectionId}
     */
    @GetMapping("/stats/{connectionId}")
    public ResponseEntity<Map<String, Object>> getStatistics(@PathVariable String connectionId) {
        log.info("API: Getting query statistics for connection {}", connectionId);
        try {
            Map<String, Object> stats = activeQueryService.getStatistics(connectionId);
            return ResponseEntity.ok(stats);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting query statistics", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get available filter options
     * GET /api/active-queries/filter-options/{connectionId}
     */
    @GetMapping("/filter-options/{connectionId}")
    public ResponseEntity<Map<String, List<String>>> getFilterOptions(@PathVariable String connectionId) {
        log.info("API: Getting filter options for connection {}", connectionId);
        try {
            Map<String, List<String>> options = activeQueryService.getFilterOptions(connectionId);
            return ResponseEntity.ok(options);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting filter options", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Kill a specific query
     * POST /api/active-queries/kill/{connectionId}/{pid}
     */
    @PostMapping("/kill/{connectionId}/{pid}")
    public ResponseEntity<Map<String, String>> killQuery(
            @PathVariable String connectionId,
            @PathVariable String pid) {
        log.info("API: Killing query {} on connection {}", pid, connectionId);
        try {
            activeQueryService.killQuery(connectionId, pid);
            return ResponseEntity.ok(Map.of(
                "message", "Successfully killed query " + pid,
                "pid", pid
            ));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error killing query {}", pid, e);
            return ResponseEntity.status(500).body(Map.of(
                "error", "Failed to kill query: " + e.getMessage(),
                "pid", pid
            ));
        }
    }

    /**
     * Cleanup old query snapshots
     * DELETE /api/active-queries/cleanup/{connectionId}
     */
    @DeleteMapping("/cleanup/{connectionId}")
    public ResponseEntity<Map<String, Object>> cleanupOldSnapshots(@PathVariable String connectionId) {
        log.info("API: Cleaning up old snapshots for connection {}", connectionId);
        try {
            int deleted = activeQueryService.cleanupOldSnapshots(connectionId);
            return ResponseEntity.ok(Map.of(
                "message", "Cleanup completed",
                "deletedCount", deleted
            ));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error cleaning up snapshots", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
