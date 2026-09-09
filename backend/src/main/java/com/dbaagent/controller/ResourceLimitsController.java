package com.dbaagent.controller;

import com.dbaagent.model.ResourceLimits;
import com.dbaagent.repository.ResourceLimitsRepository;
import com.dbaagent.service.security.AccessControlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

/**
 * Resource Limits Configuration Controller
 * Manages capacity limits for Sentinel-DBA analytics
 *
 * <p><b>Authorization:</b> every endpoint here takes a caller-supplied connection id, so
 * each one asserts access itself ({@code assertCanReadConnectionContent} for reads,
 * {@code assertCanManageConnectionContent} for writes). {@code SecurityConfig} only
 * requires an authenticated principal — nothing upstream inspects a connection id. See
 * {@code ConnectionScopedAuthorizationSafetyTest}.
 */
@RestController
@RequestMapping("/resource-limits")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "*")
public class ResourceLimitsController {

    private final ResourceLimitsRepository resourceLimitsRepository;
    private final AccessControlService accessControlService;

    /**
     * GET /api/resource-limits/{connectionId}
     * Get resource limits for a connection
     */
    @GetMapping("/{connectionId}")
    public ResponseEntity<?> getResourceLimits(@PathVariable String connectionId) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
            log.info("Fetching resource limits for connection: {}", connectionId);

            var limits = resourceLimitsRepository.findByConnectionId(connectionId);
            if (limits.isPresent()) {
                return ResponseEntity.ok(limits.get());
            } else {
                return ResponseEntity.ok(Map.of(
                        "exists", false,
                        "message", "No resource limits configured for this connection"
                ));
            }

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to fetch resource limits for connection {}: {}", connectionId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to fetch resource limits",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * POST /api/resource-limits
     * Create or update resource limits
     */
    @PostMapping
    public ResponseEntity<?> saveResourceLimits(@RequestBody ResourceLimitsRequest request) {
        try {
            accessControlService.assertCanManageConnectionContent(request.getConnectionId());
            log.info("Saving resource limits for connection: {}", request.getConnectionId());

            // Check if limits already exist
            ResourceLimits limits = resourceLimitsRepository
                    .findByConnectionId(request.getConnectionId())
                    .orElse(new ResourceLimits());

            // Set or update fields
            if (limits.getId() == null) {
                limits.setId(UUID.randomUUID().toString());
                limits.setConnectionId(request.getConnectionId());
                limits.setCreatedAt(LocalDateTime.now());
            }

            limits.setUpdatedAt(LocalDateTime.now());
            limits.setMaxStorageBytes(request.getMaxStorageBytes());
            limits.setStorageWarningPercent(request.getStorageWarningPercent());
            limits.setStorageCriticalPercent(request.getStorageCriticalPercent());
            limits.setMaxIops(request.getMaxIops());
            limits.setIopsWarningPercent(request.getIopsWarningPercent());
            limits.setIopsCriticalPercent(request.getIopsCriticalPercent());
            limits.setMaxConnections(request.getMaxConnections());
            limits.setConnectionWarningPercent(request.getConnectionWarningPercent());
            limits.setConnectionCriticalPercent(request.getConnectionCriticalPercent());
            limits.setMaxCpuPercent(request.getMaxCpuPercent());
            limits.setCpuWarningPercent(request.getCpuWarningPercent());
            limits.setCpuCriticalPercent(request.getCpuCriticalPercent());
            limits.setIsEnabled(request.getIsEnabled() != null ? request.getIsEnabled() : true);
            limits.setCollectionCadence(request.getCollectionCadence() != null ? request.getCollectionCadence() : "HOUR");

            ResourceLimits saved = resourceLimitsRepository.save(limits);

            log.info("Successfully saved resource limits for connection: {}", request.getConnectionId());

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Resource limits saved successfully",
                    "data", saved
            ));

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to save resource limits: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", "Failed to save resource limits",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * DELETE /api/resource-limits/{connectionId}
     * Delete resource limits
     */
    @DeleteMapping("/{connectionId}")
    public ResponseEntity<?> deleteResourceLimits(@PathVariable String connectionId) {
        try {
            accessControlService.assertCanManageConnectionContent(connectionId);
            log.info("Deleting resource limits for connection: {}", connectionId);

            resourceLimitsRepository.findByConnectionId(connectionId)
                    .ifPresent(resourceLimitsRepository::delete);

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "message", "Resource limits deleted successfully"
            ));

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to delete resource limits for connection {}: {}", connectionId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "error", "Failed to delete resource limits",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * Request DTO for creating/updating resource limits
     */
    @lombok.Data
    public static class ResourceLimitsRequest {
        private String connectionId;
        private Long maxStorageBytes;
        private Double storageWarningPercent;
        private Double storageCriticalPercent;
        private Integer maxIops;
        private Double iopsWarningPercent;
        private Double iopsCriticalPercent;
        private Integer maxConnections;
        private Double connectionWarningPercent;
        private Double connectionCriticalPercent;
        private Integer maxCpuPercent;
        private Double cpuWarningPercent;
        private Double cpuCriticalPercent;
        private Boolean isEnabled;
        private String collectionCadence;
    }
}
