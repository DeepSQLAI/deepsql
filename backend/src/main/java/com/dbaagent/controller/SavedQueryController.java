package com.dbaagent.controller;

import com.dbaagent.model.SavedQuery;
import com.dbaagent.service.SavedQueryService;
import com.dbaagent.service.security.AccessControlService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/saved-queries")
@Slf4j
public class SavedQueryController {

    @Autowired
    private SavedQueryService savedQueryService;

    @Autowired
    private AccessControlService accessControlService;

    /**
     * Create a new saved query
     */
    @PostMapping
    public ResponseEntity<Map<String, Object>> createQuery(@RequestBody SavedQuery savedQuery) {
        try {
            log.info("Creating saved query: {} for connection: {}", savedQuery.getName(), savedQuery.getConnectionId());
            accessControlService.assertCanManageConnectionContent(savedQuery.getConnectionId());

            SavedQuery created = savedQueryService.saveQuery(savedQuery);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("savedQuery", created);
            response.put("message", "Query saved successfully");

            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error creating saved query", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to save query: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Get all saved queries for a connection
     */
    @GetMapping("/connection/{connectionId}")
    public ResponseEntity<Map<String, Object>> getQueriesByConnection(@PathVariable String connectionId) {
        try {
            log.info("Fetching saved queries for connection: {}", connectionId);
            accessControlService.assertCanReadConnectionContent(connectionId);

            List<SavedQuery> queries = savedQueryService.getQueriesByConnection(connectionId);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("queries", queries);
            response.put("count", queries.size());

            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching saved queries", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to fetch queries: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Get a specific saved query by ID
     */
    @GetMapping("/{id}")
    public ResponseEntity<Map<String, Object>> getQueryById(@PathVariable UUID id) {
        try {
            log.info("Fetching saved query: {}", id);

            return savedQueryService.getQueryById(id)
                    .map(query -> {
                        accessControlService.assertCanReadConnectionContent(query.getConnectionId());
                        Map<String, Object> response = new HashMap<>();
                        response.put("success", true);
                        response.put("savedQuery", query);
                        return ResponseEntity.ok(response);
                    })
                    .orElseGet(() -> {
                        Map<String, Object> errorResponse = new HashMap<>();
                        errorResponse.put("success", false);
                        errorResponse.put("message", "Query not found");
                        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
                    });
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching saved query", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to fetch query: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Update a saved query
     */
    @PutMapping("/{id}")
    public ResponseEntity<Map<String, Object>> updateQuery(@PathVariable UUID id, @RequestBody SavedQuery updates) {
        try {
            log.info("Updating saved query: {}", id);
            SavedQuery existing = savedQueryService.getQueryById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Query not found: " + id));
            accessControlService.assertCanManageConnectionContent(existing.getConnectionId());

            SavedQuery updated = savedQueryService.updateQuery(id, updates);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("savedQuery", updated);
            response.put("message", "Query updated successfully");

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.error("Query not found: {}", id);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error updating saved query", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to update query: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Delete a saved query
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteQuery(@PathVariable UUID id) {
        try {
            log.info("Deleting saved query: {}", id);
            SavedQuery existing = savedQueryService.getQueryById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Query not found: " + id));
            accessControlService.assertCanManageConnectionContent(existing.getConnectionId());

            savedQueryService.deleteQuery(id);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Query deleted successfully");

            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error deleting saved query", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to delete query: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Toggle favorite status
     */
    @PostMapping("/{id}/favorite")
    public ResponseEntity<Map<String, Object>> toggleFavorite(@PathVariable UUID id) {
        try {
            log.info("Toggling favorite for query: {}", id);
            SavedQuery existing = savedQueryService.getQueryById(id)
                    .orElseThrow(() -> new IllegalArgumentException("Query not found: " + id));
            accessControlService.assertCanManageConnectionContent(existing.getConnectionId());

            SavedQuery updated = savedQueryService.toggleFavorite(id);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("savedQuery", updated);
            response.put("message", "Favorite status updated");

            return ResponseEntity.ok(response);
        } catch (IllegalArgumentException e) {
            log.error("Query not found: {}", id);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", e.getMessage());
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(errorResponse);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error toggling favorite", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to update favorite status: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Get favorite queries for a connection
     */
    @GetMapping("/connection/{connectionId}/favorites")
    public ResponseEntity<Map<String, Object>> getFavoriteQueries(@PathVariable String connectionId) {
        try {
            log.info("Fetching favorite queries for connection: {}", connectionId);
            accessControlService.assertCanReadConnectionContent(connectionId);

            List<SavedQuery> queries = savedQueryService.getFavoriteQueries(connectionId);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("queries", queries);
            response.put("count", queries.size());

            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching favorite queries", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to fetch favorite queries: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Get queries by folder
     */
    @GetMapping("/connection/{connectionId}/folder/{folder}")
    public ResponseEntity<Map<String, Object>> getQueriesByFolder(
            @PathVariable String connectionId,
            @PathVariable String folder) {
        try {
            log.info("Fetching queries in folder: {} for connection: {}", folder, connectionId);
            accessControlService.assertCanReadConnectionContent(connectionId);

            List<SavedQuery> queries = savedQueryService.getQueriesByFolder(connectionId, folder);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("queries", queries);
            response.put("count", queries.size());

            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching queries by folder", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to fetch queries: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Search queries
     */
    @GetMapping("/connection/{connectionId}/search")
    public ResponseEntity<Map<String, Object>> searchQueries(
            @PathVariable String connectionId,
            @RequestParam String q) {
        try {
            log.info("Searching queries for connection: {} with term: {}", connectionId, q);
            accessControlService.assertCanReadConnectionContent(connectionId);

            List<SavedQuery> queries = savedQueryService.searchQueries(connectionId, q);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("queries", queries);
            response.put("count", queries.size());

            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error searching queries", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to search queries: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }

    /**
     * Get distinct folders for a connection
     */
    @GetMapping("/connection/{connectionId}/folders")
    public ResponseEntity<Map<String, Object>> getFolders(@PathVariable String connectionId) {
        try {
            log.info("Fetching folders for connection: {}", connectionId);
            accessControlService.assertCanReadConnectionContent(connectionId);

            List<String> folders = savedQueryService.getFolders(connectionId);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("folders", folders);
            response.put("count", folders.size());

            return ResponseEntity.ok(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching folders", e);
            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("message", "Failed to fetch folders: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(errorResponse);
        }
    }
}
