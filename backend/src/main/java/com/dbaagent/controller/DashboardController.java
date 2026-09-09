package com.dbaagent.controller;

import com.dbaagent.service.DashboardService;
import com.dbaagent.service.security.AccessControlService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * REST API for performance dashboard
 *
 * <p><b>Authorization:</b> every endpoint here takes a caller-supplied connection id, so
 * each one asserts access itself ({@code assertCanReadConnectionContent} for reads,
 * {@code assertCanManageConnectionContent} for writes). {@code SecurityConfig} only
 * requires an authenticated principal — nothing upstream inspects a connection id. See
 * {@code ConnectionScopedAuthorizationSafetyTest}.
 */
@RestController
@RequestMapping("/dashboard")
@RequiredArgsConstructor
@Slf4j
public class DashboardController {

    private final DashboardService dashboardService;
    private final AccessControlService accessControlService;

    /**
     * Get performance dashboard data for a connection
     */
    @GetMapping("/performance/{connectionId}")
    public ResponseEntity<DashboardService.DashboardData> getPerformanceDashboard(
        @PathVariable String connectionId,
        @RequestParam(required = false, defaultValue = "30") Integer days
    ) {
        try {
            accessControlService.assertCanReadConnectionContent(connectionId);
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
