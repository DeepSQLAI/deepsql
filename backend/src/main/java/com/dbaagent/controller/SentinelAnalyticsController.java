package com.dbaagent.controller;

import com.dbaagent.model.CapacityForecast;
import com.dbaagent.model.DatabaseEvent;
import com.dbaagent.model.SentinelRecommendation;
import com.dbaagent.service.EventCorrelationService;
import com.dbaagent.service.SentinelAnalyticsService;
import com.dbaagent.service.security.AccessControlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Sentinel-DBA Analytics REST API
 * Provides predictive capacity forecasting, event correlation, and AI-powered recommendations
 *
 * Implements the Sentinel-DBA framework:
 * - Death Clock: Resource exhaustion forecasting
 * - Contextual Attribution: Event correlation
 * - Velocity & Acceleration: Growth derivatives
 * - AI Recommendations: Actionable insights
 *
 * <p><b>Authorization:</b> every endpoint here takes a caller-supplied connection id, so
 * each one asserts access itself ({@code assertCanReadConnectionContent} for reads,
 * {@code assertCanManageConnectionContent} for writes). {@code SecurityConfig} only
 * requires an authenticated principal — nothing upstream inspects a connection id. See
 * {@code ConnectionScopedAuthorizationSafetyTest}.
 */
@RestController
@RequestMapping("/sentinel")
@RequiredArgsConstructor
@Slf4j
public class SentinelAnalyticsController {

    private final SentinelAnalyticsService sentinelAnalytics;
    private final EventCorrelationService eventCorrelation;
    private final AccessControlService accessControlService;

    /**
     * GET /api/sentinel/death-clock/{connectionId}
     * Get "Death Clock" - critical resource exhaustion forecasts
     * Returns tables/resources that are predicted to run out of capacity
     */
    @GetMapping("/death-clock/{connectionId}")
    public ResponseEntity<Map<String, Object>> getDeathClock(@PathVariable String connectionId) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        try {
            log.info("Fetching Death Clock for connection: {}", connectionId);

            List<CapacityForecast> criticalForecasts = sentinelAnalytics.getCriticalForecasts(connectionId);

            Map<String, Object> response = new HashMap<>();
            response.put("connectionId", connectionId);
            response.put("totalCritical", criticalForecasts.size());
            response.put("forecasts", criticalForecasts.stream().map(this::formatDeathClockEntry).toList());

            return ResponseEntity.ok(response);

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to fetch Death Clock for connection {}: {}", connectionId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to fetch Death Clock",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * GET /api/sentinel/forecasts/{connectionId}
     * Get all capacity forecasts for a connection
     */
    @GetMapping("/forecasts/{connectionId}")
    public ResponseEntity<Map<String, Object>> getForecasts(
            @PathVariable String connectionId,
            @RequestParam(required = false) String tableName) {
        accessControlService.assertCanReadConnectionContent(connectionId);

        try {
            log.info("Fetching forecasts for connection: {}, table: {}", connectionId, tableName);

            List<CapacityForecast> forecasts = sentinelAnalytics.getAllForecasts(connectionId);

            // Filter by table if specified
            if (tableName != null && !tableName.isEmpty()) {
                forecasts = forecasts.stream()
                        .filter(f -> tableName.equals(f.getTableName()))
                        .toList();
            }

            Map<String, Object> response = new HashMap<>();
            response.put("connectionId", connectionId);
            response.put("tableName", tableName);
            response.put("totalForecasts", forecasts.size());
            response.put("forecasts", forecasts);

            return ResponseEntity.ok(response);

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to fetch forecasts for connection {}: {}", connectionId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to fetch forecasts",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * POST /api/sentinel/forecasts/{connectionId}
     * Generate new forecast for a specific table
     */
    @PostMapping("/forecasts/{connectionId}")
    public ResponseEntity<Map<String, Object>> generateForecast(
            @PathVariable String connectionId,
            @RequestParam String tableName) {
        accessControlService.assertCanManageConnectionContent(connectionId);

        try {
            log.info("Generating forecast for connection: {}, table: {}", connectionId, tableName);

            CapacityForecast forecast = sentinelAnalytics.forecastResourceExhaustion(connectionId, tableName);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("forecast", forecast);
            response.put("message", "Forecast generated successfully");

            return ResponseEntity.ok(response);

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to generate forecast for table {}: {}", tableName, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to generate forecast",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * GET /api/sentinel/velocity/{connectionId}
     * Calculate velocity and acceleration for a table
     */
    @GetMapping("/velocity/{connectionId}")
    public ResponseEntity<Map<String, Object>> getVelocityAndAcceleration(
            @PathVariable String connectionId,
            @RequestParam String tableName,
            @RequestParam(defaultValue = "30") int historicalDays) {
        accessControlService.assertCanReadConnectionContent(connectionId);

        try {
            log.info("Calculating velocity/acceleration for table: {}", tableName);

            Map<String, Double> velocityData = sentinelAnalytics.calculateVelocityAndAcceleration(
                    connectionId, tableName, historicalDays);

            Map<String, Object> response = new HashMap<>();
            response.put("connectionId", connectionId);
            response.put("tableName", tableName);
            response.put("historicalDays", historicalDays);
            response.put("velocity", velocityData.get("velocity"));  // %/day
            response.put("acceleration", velocityData.get("acceleration"));  // %/day²
            response.put("interpretation", interpretVelocity(velocityData));

            return ResponseEntity.ok(response);

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to calculate velocity for table {}: {}", tableName, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to calculate velocity",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * GET /api/sentinel/events/{connectionId}
     * Get database events (deployments, schema changes, etc.)
     */
    @GetMapping("/events/{connectionId}")
    public ResponseEntity<Map<String, Object>> getEvents(
            @PathVariable String connectionId,
            @RequestParam(required = false) Integer days) {
        accessControlService.assertCanReadConnectionContent(connectionId);

        try {
            int daysToFetch = days != null ? days : 30;
            log.info("Fetching events for connection: {} (last {} days)", connectionId, daysToFetch);

            List<DatabaseEvent> events = sentinelAnalytics.getRecentEvents(connectionId, daysToFetch);

            Map<String, Object> response = new HashMap<>();
            response.put("connectionId", connectionId);
            response.put("days", daysToFetch);
            response.put("totalEvents", events.size());
            response.put("events", events);

            return ResponseEntity.ok(response);

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to fetch events for connection {}: {}", connectionId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to fetch events",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * POST /api/sentinel/events/deployment
     * Manually log a deployment event
     */
    @PostMapping("/events/deployment")
    public ResponseEntity<Map<String, Object>> logDeploymentEvent(@RequestBody Map<String, Object> eventData) {
        try {
            String connectionId = (String) eventData.get("connectionId");
            accessControlService.assertCanManageConnectionContent(connectionId);
            String deploymentVersion = (String) eventData.get("deploymentVersion");
            String deploymentTag = (String) eventData.get("deploymentTag");
            @SuppressWarnings("unchecked")
            List<String> affectedTables = (List<String>) eventData.get("affectedTables");
            // The actor is the authenticated caller; an "initiatedBy" in the body
            // would let anyone attribute a deployment event to a colleague.
            String initiatedBy = accessControlService.requireCurrentUsername();

            log.info("Logging deployment event: {} for connection {}", deploymentVersion, connectionId);

            DatabaseEvent event = eventCorrelation.logDeploymentEvent(
                    connectionId,
                    deploymentVersion,
                    deploymentTag,
                    affectedTables,
                    initiatedBy
            );

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "event", event,
                    "message", "Deployment event logged successfully"
            ));

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to log deployment event: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to log deployment event",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * POST /api/sentinel/events/schema-change
     * Manually log a schema change event
     */
    @PostMapping("/events/schema-change")
    public ResponseEntity<Map<String, Object>> logSchemaChangeEvent(@RequestBody Map<String, Object> eventData) {
        try {
            String connectionId = (String) eventData.get("connectionId");
            accessControlService.assertCanManageConnectionContent(connectionId);
            String tableName = (String) eventData.get("tableName");
            String changeType = (String) eventData.get("changeType");
            String description = (String) eventData.get("description");
            // The actor is the authenticated caller; an "initiatedBy" in the body
            // would let anyone attribute a deployment event to a colleague.
            String initiatedBy = accessControlService.requireCurrentUsername();

            log.info("Logging schema change event: {} on table {}", changeType, tableName);

            DatabaseEvent event = eventCorrelation.logSchemaChangeEvent(
                    connectionId,
                    tableName,
                    changeType,
                    description,
                    initiatedBy
            );

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "event", event,
                    "message", "Schema change event logged successfully"
            ));

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to log schema change event: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to log schema change event",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * GET /api/sentinel/recommendations/{connectionId}
     * Get AI-generated recommendations
     */
    @GetMapping("/recommendations/{connectionId}")
    public ResponseEntity<Map<String, Object>> getRecommendations(
            @PathVariable String connectionId,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String priority) {
        accessControlService.assertCanReadConnectionContent(connectionId);

        try {
            log.info("Fetching recommendations for connection: {} (status: {}, priority: {})",
                    connectionId, status, priority);

            List<SentinelRecommendation> recommendations;

            if (status != null && !status.isEmpty()) {
                SentinelRecommendation.Status statusEnum = SentinelRecommendation.Status.valueOf(status.toUpperCase());
                recommendations = sentinelAnalytics.getRecommendationsByStatus(connectionId, statusEnum);
            } else if (priority != null && !priority.isEmpty()) {
                SentinelRecommendation.Priority priorityEnum = SentinelRecommendation.Priority.valueOf(priority.toUpperCase());
                recommendations = sentinelAnalytics.getRecommendationsByPriority(connectionId, priorityEnum);
            } else {
                recommendations = sentinelAnalytics.getAllRecommendations(connectionId);
            }

            Map<String, Object> response = new HashMap<>();
            response.put("connectionId", connectionId);
            response.put("totalRecommendations", recommendations.size());
            response.put("recommendations", recommendations);

            return ResponseEntity.ok(response);

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to fetch recommendations for connection {}: {}", connectionId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to fetch recommendations",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * POST /api/sentinel/recommendations/{recommendationId}/status
     * Update recommendation status (mark as completed, dismissed, in_progress)
     */
    @PostMapping("/recommendations/{recommendationId}/status")
    public ResponseEntity<Map<String, Object>> updateRecommendationStatus(
            @PathVariable String recommendationId,
            @RequestBody Map<String, String> statusData) {

        try {
            assertCanManageRecommendation(recommendationId);
            String status = statusData.get("status");
            String updatedBy = accessControlService.requireCurrentUsername();

            log.info("Updating recommendation {} status to: {}", recommendationId, status);

            SentinelRecommendation updated = sentinelAnalytics.updateRecommendationStatus(
                    recommendationId,
                    SentinelRecommendation.Status.valueOf(status.toUpperCase()),
                    updatedBy
            );

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "recommendation", updated,
                    "message", "Recommendation status updated successfully"
            ));

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to update recommendation status: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to update recommendation status",
                    "message", e.getMessage()
            ));
        }
    }

    /**
     * GET /api/sentinel/summary/{connectionId}
     * Get comprehensive Sentinel-DBA executive summary
     */
    @GetMapping("/summary/{connectionId}")
    public ResponseEntity<Map<String, Object>> getSentinelSummary(@PathVariable String connectionId) {
        accessControlService.assertCanReadConnectionContent(connectionId);
        try {
            log.info("Generating Sentinel-DBA summary for connection: {}", connectionId);

            Map<String, Object> summary = new HashMap<>();

            // Death Clock: Critical forecasts
            List<CapacityForecast> criticalForecasts = sentinelAnalytics.getCriticalForecasts(connectionId);
            summary.put("deathClock", Map.of(
                    "criticalCount", criticalForecasts.size(),
                    "forecasts", criticalForecasts.stream().limit(5).map(this::formatDeathClockEntry).toList()
            ));

            // Resource Headroom
            Map<String, Object> headroom = sentinelAnalytics.analyzeResourceHeadroom(connectionId);
            summary.put("resourceHeadroom", headroom);

            // Recommendations
            List<SentinelRecommendation> pendingRecs = sentinelAnalytics.getRecommendationsByStatus(
                    connectionId, SentinelRecommendation.Status.PENDING);
            summary.put("recommendations", Map.of(
                    "pending", pendingRecs.size(),
                    "immediate", pendingRecs.stream()
                            .filter(r -> r.getPriority() == SentinelRecommendation.Priority.IMMEDIATE)
                            .count()
            ));

            // Recent Events
            List<DatabaseEvent> recentEvents = sentinelAnalytics.getRecentEvents(connectionId, 7);
            summary.put("recentEvents", Map.of(
                    "count", recentEvents.size(),
                    "events", recentEvents.stream().limit(5).toList()
            ));

            // Executive Summary Text
            summary.put("executiveSummary", generateExecutiveSummary(criticalForecasts, pendingRecs, headroom));

            return ResponseEntity.ok(summary);

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Failed to generate Sentinel summary for connection {}: {}", connectionId, e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                    "error", "Failed to generate Sentinel summary",
                    "message", e.getMessage()
            ));
        }
    }

    // ========== HELPER METHODS ==========

    /**
     * Format a Death Clock entry for API response
     */
    private Map<String, Object> formatDeathClockEntry(CapacityForecast forecast) {
        Map<String, Object> entry = new HashMap<>();
        entry.put("id", forecast.getId());
        entry.put("tableName", forecast.getTableName());
        entry.put("resource", "Storage");  // TODO: Expand for IOPS, CPU, Connections
        entry.put("currentPercent", forecast.getCurrentStoragePercent());
        entry.put("exhaustionDate", forecast.getStorageExhaustionDate());
        entry.put("daysRemaining", forecast.getDaysToStorageExhaustion());
        entry.put("velocity", forecast.getDailyStorageGrowthRate());
        entry.put("acceleration", forecast.getStorageAcceleration());
        entry.put("pattern", forecast.getGrowthPattern());
        entry.put("riskScore", forecast.getRiskScore());
        entry.put("confidenceScore", forecast.getConfidenceScore());
        return entry;
    }

    /**
     * Interpret velocity data for human consumption
     */
    private Map<String, String> interpretVelocity(Map<String, Double> velocityData) {
        double velocity = velocityData.get("velocity");
        double acceleration = velocityData.get("acceleration");

        String velocityInterpretation;
        if (Math.abs(velocity) < 1.0) {
            velocityInterpretation = "STABLE - Growth is minimal (<1%/day)";
        } else if (Math.abs(velocity) < 5.0) {
            velocityInterpretation = "MODERATE - Growth is steady (1-5%/day)";
        } else if (Math.abs(velocity) < 10.0) {
            velocityInterpretation = "HIGH - Growth is significant (5-10%/day)";
        } else {
            velocityInterpretation = "CRITICAL - Growth is very high (>10%/day)";
        }

        String accelerationInterpretation;
        if (Math.abs(acceleration) < 1.0) {
            accelerationInterpretation = "STABLE - Growth rate is consistent";
        } else if (acceleration > 1.0) {
            accelerationInterpretation = "ACCELERATING - Growth rate is increasing";
        } else {
            accelerationInterpretation = "DECELERATING - Growth rate is decreasing";
        }

        return Map.of(
                "velocity", velocityInterpretation,
                "acceleration", accelerationInterpretation
        );
    }

    /**
     * Generate executive summary text
     */
    private String generateExecutiveSummary(
            List<CapacityForecast> criticalForecasts,
            List<SentinelRecommendation> pendingRecs,
            Map<String, Object> headroom) {

        if (criticalForecasts.isEmpty()) {
            return "All resources are operating within normal capacity. No critical issues detected.";
        }

        CapacityForecast mostUrgent = criticalForecasts.get(0);
        long daysRemaining = mostUrgent.getDaysToStorageExhaustion();

        return String.format(
                "⚠️ %d critical capacity forecast%s detected. Most urgent: %s will exhaust storage in %d days (Risk: %d/10, Confidence: %d/10). %d pending recommendations require action.",
                criticalForecasts.size(),
                criticalForecasts.size() > 1 ? "s" : "",
                mostUrgent.getTableName(),
                daysRemaining,
                mostUrgent.getRiskScore(),
                mostUrgent.getConfidenceScore(),
                pendingRecs.size()
        );
    }

    /**
     * Authorize a write keyed only on a recommendation id. The recommendation
     * carries its own connectionId, so resolve that and assert against it — a
     * recommendation id is not a capability. An unknown id and one on a connection the
     * caller cannot manage both report 404, so the two are indistinguishable.
     */
    private void assertCanManageRecommendation(String recommendationId) {
        String connectionId = sentinelAnalytics.findConnectionIdForRecommendation(recommendationId)
                .orElseThrow(() -> new org.springframework.web.server.ResponseStatusException(
                        org.springframework.http.HttpStatus.NOT_FOUND, "Recommendation not found"));
        accessControlService.assertCanManageConnectionContentOrNotFound(connectionId, "Recommendation");
    }
}
