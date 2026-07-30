package com.dbaagent.controller;

import com.dbaagent.model.DbaStats;
import com.dbaagent.service.CredentialService;
import com.dbaagent.service.StatsCollectorService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/connections/{connectionId}/stats")
@RequiredArgsConstructor
public class StatsController {
    private final StatsCollectorService statsCollectorService;
    private final CredentialService credentialService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> getStats(@PathVariable String connectionId) {
        Map<String, Object> response = new HashMap<>();
        try {
            if (!credentialService.connectionExists(connectionId)) {
                response.put("success", false);
                response.put("message", "Connection not found");
                return ResponseEntity.status(HttpStatus.NOT_FOUND).body(response);
            }

            DbaStats stats = statsCollectorService.collectStats(connectionId);
            response.put("success", true);
            response.put("stats", stats);
            return ResponseEntity.ok(response);
        } catch (SQLException e) {
            response.put("success", false);
            response.put("message", "Failed to collect stats: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            response.put("success", false);
            response.put("message", "Failed to collect stats: " + e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }
}



