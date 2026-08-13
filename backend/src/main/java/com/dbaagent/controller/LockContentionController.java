package com.dbaagent.controller;

import com.dbaagent.model.LockContention;
import com.dbaagent.service.LockContentionService;
import com.dbaagent.service.security.AccessControlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/lock-contention")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class LockContentionController {

    private final LockContentionService lockContentionService;
    private final AccessControlService accessControlService;

    /**
     * Detect current lock contentions in the database
     * POST /api/lock-contention/detect/{connectionId}
     */
    @PostMapping("/detect/{connectionId}")
    public ResponseEntity<List<LockContention>> detectLockContentions(@PathVariable String connectionId) {
        log.info("API: Detecting lock contentions for connection {}", connectionId);
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            List<LockContention> contentions = lockContentionService.detectLockContentions(connectionId);
            return ResponseEntity.ok(contentions);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error detecting lock contentions", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get active lock contentions
     * GET /api/lock-contention/active/{connectionId}
     */
    @GetMapping("/active/{connectionId}")
    public ResponseEntity<List<LockContention>> getActiveContentions(@PathVariable String connectionId) {
        log.info("API: Getting active contentions for connection {}", connectionId);
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            List<LockContention> contentions = lockContentionService.getActiveContentions(connectionId);
            return ResponseEntity.ok(contentions);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting active contentions", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get all contentions (active and resolved)
     * GET /api/lock-contention/all/{connectionId}
     */
    @GetMapping("/all/{connectionId}")
    public ResponseEntity<List<LockContention>> getAllContentions(@PathVariable String connectionId) {
        log.info("API: Getting all contentions for connection {}", connectionId);
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            List<LockContention> contentions = lockContentionService.getAllContentions(connectionId);
            return ResponseEntity.ok(contentions);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting all contentions", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Get contention statistics
     * GET /api/lock-contention/stats/{connectionId}
     */
    @GetMapping("/stats/{connectionId}")
    public ResponseEntity<Map<String, Object>> getStatistics(@PathVariable String connectionId) {
        log.info("API: Getting contention statistics for connection {}", connectionId);
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            Map<String, Object> stats = lockContentionService.getContentionStatistics(connectionId);
            return ResponseEntity.ok(stats);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error getting contention statistics", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Kill a blocking session
     * POST /api/lock-contention/kill/{connectionId}/{pid}
     */
    @PostMapping("/kill/{connectionId}/{pid}")
    public ResponseEntity<Map<String, String>> killBlockingSession(
            @PathVariable String connectionId,
            @PathVariable String pid) {
        log.info("API: Killing blocking session {} on connection {}", pid, connectionId);
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            lockContentionService.killBlockingSession(connectionId, pid);
            return ResponseEntity.ok(Map.of(
                "message", "Successfully killed session " + pid,
                "pid", pid
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of(
                "error", e.getMessage(),
                "pid", pid
            ));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error killing session {}", pid, e);
            return ResponseEntity.status(500).body(Map.of(
                "error", "Failed to kill session: " + e.getMessage(),
                "pid", pid
            ));
        }
    }

    /**
     * Cleanup old resolved contentions
     * DELETE /api/lock-contention/cleanup/{connectionId}
     */
    @DeleteMapping("/cleanup/{connectionId}")
    public ResponseEntity<Map<String, Object>> cleanupOldContentions(@PathVariable String connectionId) {
        log.info("API: Cleaning up old contentions for connection {}", connectionId);
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            int deleted = lockContentionService.cleanupOldContentions(connectionId);
            return ResponseEntity.ok(Map.of(
                "message", "Cleanup completed",
                "deletedCount", deleted
            ));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error cleaning up contentions", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
