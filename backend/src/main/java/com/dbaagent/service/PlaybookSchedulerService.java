package com.dbaagent.service;

import com.dbaagent.model.Playbook;
import com.dbaagent.model.PlaybookRun;
import com.dbaagent.repository.PlaybookRepository;
import com.dbaagent.repository.PlaybookRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Playbook Scheduler - Automatically executes playbooks based on their cron schedules
 * Runs every 5 minutes to check for scheduled playbooks
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlaybookSchedulerService {

    private final PlaybookRepository playbookRepository;
    private final PlaybookExecutionService playbookExecutionService;
    private final PlaybookRunRepository playbookRunRepository;
    private final CredentialService credentialService;

    /**
     * Check and execute scheduled playbooks every 5 minutes
     * This is a simple implementation - in production, use a more sophisticated scheduler
     * like Quartz or Spring Cloud Task
     */
    public void checkScheduledPlaybooks() {
        log.debug("Checking for scheduled playbooks...");

        try {
            // Get all playbooks with schedules
            List<Playbook> scheduledPlaybooks = playbookRepository.findByScheduleCronIsNotNull();

            for (Playbook playbook : scheduledPlaybooks) {
                if (!playbook.getIsActive()) {
                    continue;
                }

                // For simplicity, we'll execute scheduled playbooks on all connections
                // In production, you'd want connection-specific schedules
                executeScheduledPlaybook(playbook);
            }

        } catch (Exception e) {
            log.error("Error checking scheduled playbooks", e);
        }
    }

    /**
     * Execute a scheduled playbook on all applicable connections
     */
    private void executeScheduledPlaybook(Playbook playbook) {
        try {
            // Get all connections that match the playbook's db type
            List<com.dbaagent.model.DatabaseConnection> connections = credentialService.getAllConnections();

            for (com.dbaagent.model.DatabaseConnection connection : connections) {
                // Check if playbook is compatible with this database type
                if (isCompatible(playbook, connection)) {
                    // Check if we should run this playbook now
                    if (shouldRunNow(playbook, connection.getId())) {
                        log.info("Executing scheduled playbook: {} on connection: {}",
                            playbook.getName(), connection.getConnectionName());

                        try {
                            playbookExecutionService.executePlaybook(
                                playbook.getId(),
                                connection.getId(),
                                PlaybookRun.TriggerType.SCHEDULED
                            );
                        } catch (Exception e) {
                            log.error("Failed to execute scheduled playbook: {} on connection: {}",
                                playbook.getName(), connection.getConnectionName(), e);
                        }
                    }
                }
            }

        } catch (Exception e) {
            log.error("Error executing scheduled playbook: {}", playbook.getName(), e);
        }
    }

    /**
     * Check if playbook is compatible with the database type
     */
    private boolean isCompatible(Playbook playbook, com.dbaagent.model.DatabaseConnection connection) {
        String playbookDbType = playbook.getDbType();

        if (playbookDbType == null || "all".equalsIgnoreCase(playbookDbType)) {
            return true;
        }

        return playbookDbType.equalsIgnoreCase(connection.getDbType());
    }

    /**
     * Simple scheduler - checks if we should run this playbook now
     * This is a basic implementation. In production, use a proper cron parser
     */
    private boolean shouldRunNow(Playbook playbook, String connectionId) {
        String cronExpression = playbook.getScheduleCron();

        if (cronExpression == null) {
            return false;
        }

        // Simple implementation: Check the last run time
        // For now, we'll use a simple time-based check

        // Examples of cron expressions in our system:
        // "0 9 * * *" - Daily at 9 AM
        // "*/30 * * * *" - Every 30 minutes
        // "0 2 * * 0" - Weekly on Sunday at 2 AM

        // Get the last run for this playbook on this connection
        List<PlaybookRun> runs = playbookRunRepository.findByConnectionId(connectionId);

        PlaybookRun lastRun = runs.stream()
            .filter(r -> r.getPlaybookId().equals(playbook.getId()))
            .filter(r -> r.getTriggerType() == PlaybookRun.TriggerType.SCHEDULED)
            .findFirst()
            .orElse(null);

        // Simple logic: If no last run, or last run was more than the schedule interval ago, run it
        // This is a simplified implementation - in production, use a proper cron library

        if (cronExpression.contains("*/30")) {
            // Every 30 minutes
            if (lastRun == null) return true;
            java.time.Duration timeSinceLastRun = java.time.Duration.between(
                lastRun.getStartedAt(), java.time.LocalDateTime.now());
            return timeSinceLastRun.toMinutes() >= 30;

        } else if (cronExpression.contains("0 9 * * *")) {
            // Daily at 9 AM
            if (lastRun == null) {
                // Check if current time is around 9 AM
                int currentHour = java.time.LocalTime.now().getHour();
                return currentHour == 9;
            }

            // Check if last run was yesterday
            java.time.Duration timeSinceLastRun = java.time.Duration.between(
                lastRun.getStartedAt(), java.time.LocalDateTime.now());
            return timeSinceLastRun.toHours() >= 24 &&
                   java.time.LocalTime.now().getHour() == 9;

        } else if (cronExpression.contains("0 2 * * 0")) {
            // Weekly on Sunday at 2 AM
            if (lastRun == null) {
                java.time.DayOfWeek today = java.time.LocalDate.now().getDayOfWeek();
                int currentHour = java.time.LocalTime.now().getHour();
                return today == java.time.DayOfWeek.SUNDAY && currentHour == 2;
            }

            java.time.Duration timeSinceLastRun = java.time.Duration.between(
                lastRun.getStartedAt(), java.time.LocalDateTime.now());
            java.time.DayOfWeek today = java.time.LocalDate.now().getDayOfWeek();
            int currentHour = java.time.LocalTime.now().getHour();

            return timeSinceLastRun.toDays() >= 7 &&
                   today == java.time.DayOfWeek.SUNDAY &&
                   currentHour == 2;
        }

        // For other cron expressions, run if no recent run in last hour
        if (lastRun == null) return true;

        java.time.Duration timeSinceLastRun = java.time.Duration.between(
            lastRun.getStartedAt(), java.time.LocalDateTime.now());
        return timeSinceLastRun.toHours() >= 1;
    }

    /**
     * Manually trigger all scheduled playbooks (for testing)
     */
    public void triggerAllScheduledPlaybooks() {
        log.info("Manually triggering all scheduled playbooks");
        checkScheduledPlaybooks();
    }
}
