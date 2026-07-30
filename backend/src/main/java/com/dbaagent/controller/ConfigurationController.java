package com.dbaagent.controller;

import com.dbaagent.model.ConfigurationRecommendation;
import com.dbaagent.service.DatabaseConfigurationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * REST controller for database configuration tuning endpoints
 */
@RestController
@RequestMapping("/configuration")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = "http://localhost:3000")
public class ConfigurationController {

    private final DatabaseConfigurationService configurationService;

    /**
     * Analyze database configuration and generate tuning recommendations
     */
    @PostMapping("/analyze/{connectionId}")
    public ResponseEntity<AnalyzeResponse> analyzeConfiguration(@PathVariable String connectionId) {
        log.info("Analyzing configuration for connection: {}", connectionId);

        try {
            List<ConfigurationRecommendation> recommendations = configurationService.analyzeConfiguration(connectionId);

            AnalyzeResponse response = new AnalyzeResponse();
            response.setSuccess(true);
            response.setMessage("Generated " + recommendations.size() + " configuration recommendations");
            response.setCount(recommendations.size());
            response.setRecommendations(recommendations);

            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error analyzing configuration: {}", e.getMessage(), e);

            AnalyzeResponse response = new AnalyzeResponse();
            response.setSuccess(false);
            response.setMessage("Error analyzing configuration: " + e.getMessage());
            response.setCount(0);

            return ResponseEntity.internalServerError().body(response);
        }
    }

    /**
     * Get all recommendations for a connection (latest analysis)
     */
    @GetMapping("/{connectionId}")
    public ResponseEntity<List<ConfigurationRecommendation>> getRecommendations(@PathVariable String connectionId) {
        log.info("Fetching configuration recommendations for connection: {}", connectionId);

        List<ConfigurationRecommendation> recommendations = configurationService.getRecommendations(connectionId);
        return ResponseEntity.ok(recommendations);
    }

    /**
     * Get pending recommendations for a connection
     */
    @GetMapping("/pending/{connectionId}")
    public ResponseEntity<List<ConfigurationRecommendation>> getPendingRecommendations(@PathVariable String connectionId) {
        log.info("Fetching pending configuration recommendations for connection: {}", connectionId);

        List<ConfigurationRecommendation> recommendations = configurationService.getPendingRecommendations(connectionId);
        return ResponseEntity.ok(recommendations);
    }

    /**
     * Mark recommendation as applied
     */
    @PutMapping("/{id}/apply")
    public ResponseEntity<ConfigurationRecommendation> markAsApplied(@PathVariable String id) {
        log.info("Marking configuration recommendation as applied: {}", id);

        try {
            ConfigurationRecommendation recommendation = configurationService.markAsApplied(id);
            return ResponseEntity.ok(recommendation);
        } catch (IllegalArgumentException e) {
            log.error("Recommendation not found: {}", id);
            return ResponseEntity.notFound().build();
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error marking recommendation as applied: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Dismiss recommendation
     */
    @PutMapping("/{id}/dismiss")
    public ResponseEntity<ConfigurationRecommendation> dismissRecommendation(@PathVariable String id) {
        log.info("Dismissing configuration recommendation: {}", id);

        try {
            ConfigurationRecommendation recommendation = configurationService.dismissRecommendation(id);
            return ResponseEntity.ok(recommendation);
        } catch (IllegalArgumentException e) {
            log.error("Recommendation not found: {}", id);
            return ResponseEntity.notFound().build();
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error dismissing recommendation: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Delete recommendation
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteRecommendation(@PathVariable String id) {
        log.info("Deleting configuration recommendation: {}", id);

        try {
            configurationService.deleteRecommendation(id);
            return ResponseEntity.ok(Map.of(
                "success", "true",
                "message", "Recommendation deleted successfully"
            ));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error deleting recommendation: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().body(Map.of(
                "success", "false",
                "message", "Error deleting recommendation: " + e.getMessage()
            ));
        }
    }

    // DTOs
    public static class AnalyzeResponse {
        private boolean success;
        private String message;
        private int count;
        private List<ConfigurationRecommendation> recommendations;

        public boolean isSuccess() {
            return success;
        }

        public void setSuccess(boolean success) {
            this.success = success;
        }

        public String getMessage() {
            return message;
        }

        public void setMessage(String message) {
            this.message = message;
        }

        public int getCount() {
            return count;
        }

        public void setCount(int count) {
            this.count = count;
        }

        public List<ConfigurationRecommendation> getRecommendations() {
            return recommendations;
        }

        public void setRecommendations(List<ConfigurationRecommendation> recommendations) {
            this.recommendations = recommendations;
        }
    }
}
