package com.dbaagent.service;

import com.dbaagent.model.*;
import com.dbaagent.repository.PlaybookAlertRepository;
import com.dbaagent.repository.PlaybookRepository;
import com.dbaagent.repository.PlaybookRunRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Playbook Execution Engine
 * Orchestrates playbook execution, collects findings, generates recommendations, and creates alerts
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PlaybookExecutionService {

    private final PlaybookRepository playbookRepository;
    private final PlaybookRunRepository playbookRunRepository;
    private final PlaybookAlertRepository playbookAlertRepository;
    private final PlaybookToolsService playbookToolsService;
    private final CredentialService credentialService;

    /**
     * Execute a playbook manually
     */
    @Transactional
    public PlaybookRun executePlaybook(String playbookId, String connectionId) {
        return executePlaybook(playbookId, connectionId, PlaybookRun.TriggerType.MANUAL);
    }

    /**
     * Execute a playbook with specified trigger type
     */
    @Transactional
    public PlaybookRun executePlaybook(String playbookId, String connectionId, PlaybookRun.TriggerType triggerType) {
        log.info("Starting playbook execution: {} for connection: {}", playbookId, connectionId);

        Playbook playbook = playbookRepository.findById(playbookId)
            .orElseThrow(() -> new IllegalArgumentException("Playbook not found: " + playbookId));

        if (!playbook.getIsActive()) {
            throw new IllegalStateException("Playbook is not active: " + playbookId);
        }

        // Validate connection exists
        if (!credentialService.connectionExists(connectionId)) {
            throw new IllegalArgumentException("Connection not found: " + connectionId);
        }

        // Create playbook run record
        PlaybookRun run = PlaybookRun.builder()
            .playbookId(playbookId)
            .connectionId(connectionId)
            .status(PlaybookRun.RunStatus.RUNNING)
            .triggerType(triggerType)
            .stepsTotal(playbook.getSteps().size())
            .stepsCompleted(0)
            .findings(new ArrayList<>())
            .recommendations(new ArrayList<>())
            .build();

        run = playbookRunRepository.save(run);

        try {
            // Execute each step in the playbook
            List<PlaybookRun.StepResult> allFindings = new ArrayList<>();
            List<String> allIssues = new ArrayList<>();

            for (int i = 0; i < playbook.getSteps().size(); i++) {
                Playbook.PlaybookStep step = playbook.getSteps().get(i);

                log.info("Executing step {}/{}: {} ({})", i + 1, playbook.getSteps().size(), step.getName(), step.getTool());

                try {
                    // Execute the tool
                    Map<String, Object> params = step.getParams() != null ? step.getParams() : new HashMap<>();
                    if (step.getThresholds() != null) {
                        params.putAll(step.getThresholds());
                    }

                    PlaybookToolsService.ToolResult toolResult = playbookToolsService.executeTool(
                        connectionId,
                        step.getTool(),
                        params
                    );

                    // Create step result
                    PlaybookRun.StepResult stepResult = new PlaybookRun.StepResult();
                    stepResult.setStepName(step.getName());
                    stepResult.setTool(step.getTool());
                    stepResult.setStatus(toolResult.isSuccess() ? "success" : "error");
                    stepResult.setData(toolResult.getData());
                    stepResult.setIssues(toolResult.getIssues());
                    stepResult.setExecutionTimeMs(toolResult.getExecutionTimeMs());

                    allFindings.add(stepResult);

                    if (toolResult.getIssues() != null && !toolResult.getIssues().isEmpty()) {
                        allIssues.addAll(toolResult.getIssues());
                    }

                    // Update progress
                    run.setStepsCompleted(i + 1);
                    run.setFindings(allFindings);
                    playbookRunRepository.save(run);

                } catch (Exception e) {
                    log.error("Error executing step: {}", step.getName(), e);

                    PlaybookRun.StepResult errorResult = new PlaybookRun.StepResult();
                    errorResult.setStepName(step.getName());
                    errorResult.setTool(step.getTool());
                    errorResult.setStatus("error");
                    errorResult.setIssues(List.of("Error: " + e.getMessage()));

                    allFindings.add(errorResult);
                }
            }

            // Generate recommendations based on findings
            List<Map<String, Object>> recommendations = generateRecommendations(allFindings);
            run.setRecommendations(recommendations);

            // Determine severity and create alerts if needed
            PlaybookAlert.Severity maxSeverity = determineMaxSeverity(allIssues);

            if (shouldCreateAlert(playbook, maxSeverity)) {
                PlaybookAlert alert = createAlert(run, playbook, allIssues, recommendations, maxSeverity);
                playbookAlertRepository.save(alert);
                run.setAlertsTriggered(1);
            }

            // Mark run as completed
            run.markCompleted();
            log.info("Playbook execution completed successfully: {}", playbookId);

        } catch (Exception e) {
            log.error("Playbook execution failed: {}", playbookId, e);
            run.markFailed(e.getMessage());
        }

        return playbookRunRepository.save(run);
    }

    /**
     * Generate recommendations based on findings
     */
    private List<Map<String, Object>> generateRecommendations(List<PlaybookRun.StepResult> findings) {
        List<Map<String, Object>> recommendations = new ArrayList<>();

        for (PlaybookRun.StepResult finding : findings) {
            if (finding.getIssues() != null && !finding.getIssues().isEmpty()) {
                for (String issue : finding.getIssues()) {
                    Map<String, Object> recommendation = new HashMap<>();
                    recommendation.put("issue", issue);
                    recommendation.put("step", finding.getStepName());
                    recommendation.put("tool", finding.getTool());
                    recommendation.put("recommendation", generateRecommendationText(finding));
                    recommendation.put("priority", determinePriority(issue));

                    recommendations.add(recommendation);
                }
            }
        }

        return recommendations;
    }

    /**
     * Generate recommendation text based on the finding
     */
    private String generateRecommendationText(PlaybookRun.StepResult finding) {
        String tool = finding.getTool();

        return switch (tool) {
            case "check_connection_count" ->
                "Consider increasing max_connections or implementing connection pooling to prevent connection exhaustion.";
            case "get_long_running_queries" ->
                "Review and optimize long-running queries. Consider adding indexes or refactoring the queries.";
            case "check_index_usage" ->
                "Remove unused indexes to reduce storage overhead and improve write performance.";
            case "check_table_bloat" ->
                "Run VACUUM ANALYZE (PostgreSQL) or OPTIMIZE TABLE (MySQL) to reclaim space and update statistics.";
            case "detect_connection_leaks" ->
                "Investigate application code for unclosed database connections. Implement proper connection lifecycle management.";
            default ->
                "Review the findings and take appropriate action based on your database workload.";
        };
    }

    /**
     * Determine priority based on issue text
     */
    private String determinePriority(String issue) {
        String lowerIssue = issue.toLowerCase();

        if (lowerIssue.contains("critical")) {
            return "CRITICAL";
        } else if (lowerIssue.contains("warning")) {
            return "HIGH";
        } else {
            return "MEDIUM";
        }
    }

    /**
     * Determine maximum severity from issues
     */
    private PlaybookAlert.Severity determineMaxSeverity(List<String> issues) {
        if (issues.isEmpty()) {
            return PlaybookAlert.Severity.INFO;
        }

        boolean hasCritical = issues.stream()
            .anyMatch(issue -> issue.toLowerCase().contains("critical"));

        if (hasCritical) {
            return PlaybookAlert.Severity.CRITICAL;
        }

        boolean hasWarning = issues.stream()
            .anyMatch(issue -> issue.toLowerCase().contains("warning"));

        if (hasWarning) {
            return PlaybookAlert.Severity.WARNING;
        }

        return PlaybookAlert.Severity.INFO;
    }

    /**
     * Check if an alert should be created based on playbook configuration and severity
     */
    private boolean shouldCreateAlert(Playbook playbook, PlaybookAlert.Severity severity) {
        if (playbook.getAlertConfig() == null) {
            return false;
        }

        String severityThreshold = playbook.getAlertConfig().getSeverityThreshold();

        if (severityThreshold == null) {
            return severity == PlaybookAlert.Severity.CRITICAL || severity == PlaybookAlert.Severity.WARNING;
        }

        return switch (severityThreshold.toLowerCase()) {
            case "info" -> true; // Alert on everything
            case "warning" -> severity == PlaybookAlert.Severity.WARNING || severity == PlaybookAlert.Severity.CRITICAL;
            case "critical" -> severity == PlaybookAlert.Severity.CRITICAL;
            default -> false;
        };
    }

    /**
     * Create an alert from playbook run results
     */
    private PlaybookAlert createAlert(PlaybookRun run, Playbook playbook,
                                     List<String> issues, List<Map<String, Object>> recommendations,
                                     PlaybookAlert.Severity severity) {

        String title = playbook.getName() + " - " + issues.size() + " Issue(s) Found";
        String message = String.join("\n", issues);

        Map<String, Object> findings = new HashMap<>();
        findings.put("total_issues", issues.size());
        findings.put("issues", issues);
        findings.put("playbook_name", playbook.getName());

        List<String> channels = playbook.getAlertConfig() != null ?
            playbook.getAlertConfig().getChannels() :
            List.of("browser");

        return PlaybookAlert.builder()
            .playbookRunId(run.getId())
            .connectionId(run.getConnectionId())
            .severity(severity)
            .title(title)
            .message(message)
            .findings(findings)
            .recommendations(recommendations)
            .channelsSent(channels)
            .build();
    }

    /**
     * Get playbook run history for a connection
     */
    public List<PlaybookRun> getRunHistory(String connectionId, int limit) {
        List<PlaybookRun> allRuns = playbookRunRepository.findByConnectionIdOrderByStartedAtDesc(connectionId);
        return allRuns.stream().limit(limit).collect(Collectors.toList());
    }

    /**
     * Get recent runs since a specific time
     */
    public List<PlaybookRun> getRecentRuns(String connectionId, LocalDateTime since) {
        return playbookRunRepository.findRecentRuns(connectionId, since);
    }

    /**
     * Get run statistics for a connection
     */
    public Map<String, Object> getRunStatistics(String connectionId) {
        long totalRuns = playbookRunRepository.findByConnectionId(connectionId).size();
        long completedRuns = playbookRunRepository.countByConnectionIdAndStatus(
            connectionId, PlaybookRun.RunStatus.COMPLETED);
        long failedRuns = playbookRunRepository.countByConnectionIdAndStatus(
            connectionId, PlaybookRun.RunStatus.FAILED);

        Map<String, Object> stats = new HashMap<>();
        stats.put("total_runs", totalRuns);
        stats.put("completed_runs", completedRuns);
        stats.put("failed_runs", failedRuns);
        stats.put("success_rate", totalRuns > 0 ? (completedRuns * 100.0 / totalRuns) : 0.0);

        return stats;
    }

    /**
     * Cancel a running playbook
     */
    @Transactional
    public void cancelRun(String runId) {
        PlaybookRun run = playbookRunRepository.findById(runId)
            .orElseThrow(() -> new IllegalArgumentException("Run not found: " + runId));

        if (run.getStatus() == PlaybookRun.RunStatus.RUNNING) {
            run.setStatus(PlaybookRun.RunStatus.CANCELLED);
            run.setCompletedAt(LocalDateTime.now());
            playbookRunRepository.save(run);
        }
    }
}
