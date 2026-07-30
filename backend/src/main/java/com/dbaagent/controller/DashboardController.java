package com.dbaagent.controller;

import com.dbaagent.service.DashboardService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for performance dashboard
 */
@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
@Slf4j
public class DashboardController {

    private final DashboardService dashboardService;

    /**
     * Get performance dashboard data for a connection
     */
    @GetMapping("/performance/{connectionId}")
    public ResponseEntity<DashboardService.DashboardData> getPerformanceDashboard(
        @PathVariable String connectionId,
        @RequestParam(required = false, defaultValue = "30") Integer days
    ) {
        try {
            log.info("Fetching performance dashboard for connection: {}, days: {}", connectionId, days);

            DashboardService.DashboardData data = dashboardService.getDashboardData(connectionId, days);

            return ResponseEntity.ok(data);

        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error fetching performance dashboard", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
