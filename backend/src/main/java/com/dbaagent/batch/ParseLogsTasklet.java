package com.dbaagent.batch;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.SlowLogSourceConfig;
import com.dbaagent.model.SlowQueryAnalysis;
import com.dbaagent.model.SlowQueryHistory;
import com.dbaagent.repository.SlowLogSourceConfigRepository;
import com.dbaagent.service.CredentialService;
import com.dbaagent.service.SlowLogIngestionService;
import com.dbaagent.service.SlowQueryHistoryService;
import com.dbaagent.service.SlowQueryLogParserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Optional;

/**
 * Second step of ingestion job: Parses logs and persists analysis.
 *
 * Reads from previous step's ExecutionContext:
 * - tempFilePath: Path to downloaded temp file
 * - bytesProcessed: Size of downloaded data
 * - connectionId: Database connection ID
 *
 * Actions:
 * - Parses temp file using SlowQueryLogParserService
 * - Saves analysis via SlowQueryHistoryService
 * - Updates SlowLogSourceConfig.lastProcessedAt
 * - Deletes temp file
 *
 * Stores in job ExecutionContext (for status API):
 * - historyId: ID of saved SlowQueryHistory
 * - slowQueriesFound: Number of slow queries found
 */
@Component
@StepScope
@RequiredArgsConstructor
@Slf4j
public class ParseLogsTasklet implements Tasklet {

    private final SlowQueryLogParserService slowQueryLogParserService;
    private final SlowQueryHistoryService slowQueryHistoryService;
    private final SlowLogSourceConfigRepository configRepository;
    private final CredentialService credentialService;
    private final SlowLogIngestionService slowLogIngestionService;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        // Check for termination request before starting
        if (contribution.getStepExecution().isTerminateOnly()) {
            log.info("Job termination requested before parse step");
            return RepeatStatus.FINISHED;
        }

        // Get step execution context from previous step
        ExecutionContext stepContext = chunkContext.getStepContext().getStepExecution().getExecutionContext();
        ExecutionContext jobContext = chunkContext.getStepContext().getStepExecution().getJobExecution().getExecutionContext();

        // Also check the previous step's context
        ExecutionContext fetchStepContext = chunkContext.getStepContext()
            .getStepExecution()
            .getJobExecution()
            .getStepExecutions()
            .stream()
            .filter(se -> "fetchLogsStep".equals(se.getStepName()))
            .findFirst()
            .map(se -> se.getExecutionContext())
            .orElse(stepContext);

        String tempFilePath = fetchStepContext.getString("tempFilePath");
        String connectionId = fetchStepContext.getString("connectionId");
        long bytesProcessed = fetchStepContext.getLong("bytesProcessed", 0L);

        if (tempFilePath == null || tempFilePath.isBlank()) {
            throw new IllegalStateException("No temp file path found from fetch step. " +
                "If this job was interrupted by a server restart, please start a new ingestion job.");
        }

        Path tempPath = Path.of(tempFilePath);
        if (!Files.exists(tempPath)) {
            // Temp file was lost (e.g., server restart cleared /tmp)
            // User must start a fresh job - the fetch step needs to re-run
            throw new IllegalStateException("Temp file not found: " + tempFilePath + ". " +
                "This can happen after a server restart. Please start a new ingestion job.");
        }

        try {
            log.info("Parsing log file: {} ({} bytes)", tempFilePath, bytesProcessed);

            // Check for termination before expensive parse operation
            if (contribution.getStepExecution().isTerminateOnly()) {
                log.info("Job termination requested before parsing for connection: {}", connectionId);
                jobContext.putString("message", "Job cancelled before parsing");
                return RepeatStatus.FINISHED;
            }

            // Update progress
            jobContext.putString("currentStep", "Parsing slow queries...");
            jobContext.putInt("progressPct", 60);

            // Resolve database type
            String dbType = resolveDatabaseType(connectionId);

            // Parse the log file
            SlowQueryAnalysis analysis = slowQueryLogParserService.parseAndAnalyze(
                tempPath,
                dbType,
                connectionId
            );

            if (analysis == null || analysis.getTopSlowQueries() == null || analysis.getTopSlowQueries().isEmpty()) {
                log.warn("Log data fetched but no slow queries matched the parser pattern for connection: {}", connectionId);
                updateLastProcessed(connectionId);
                jobContext.putInt("slowQueriesFound", 0);
                jobContext.putString("currentStep", "Completed");
                jobContext.putInt("progressPct", 100);
                jobContext.putString("message", "Logs ingested but no slow queries were found in the selected time window.");
                return RepeatStatus.FINISHED;
            }

            int slowQueriesFound = analysis.getTopSlowQueries().size();
            log.info("Found {} slow queries", slowQueriesFound);

            // Update progress
            jobContext.putString("currentStep", "Saving analysis results...");
            jobContext.putInt("progressPct", 80);

            // Save to history
            SlowQueryHistory history = slowQueryHistoryService.saveAnalysis(
                connectionId,
                analysis,
                null
            );

            // Update last processed timestamp
            updateLastProcessed(connectionId);

            // Post-process: fingerprints, regression, alerts, slow_query_run
            // decomposition, customer attribution, retention. Single owner.
            try {
                slowLogIngestionService.postProcessAnalysis(connectionId, analysis, history.getId());
            } catch (Exception e) {
                log.warn("Post-processing partially failed for connection {}: {}", connectionId, e.getMessage());
            }

            // Store results in job context for status API
            jobContext.putString("historyId", history.getId());
            jobContext.putInt("slowQueriesFound", slowQueriesFound);
            jobContext.putString("currentStep", "Completed");
            jobContext.putInt("progressPct", 100);
            jobContext.putString("message", String.format(
                "Successfully ingested %d slow query patterns",
                slowQueriesFound
            ));

            log.info("Ingestion completed: {} slow queries saved to history {}",
                slowQueriesFound, history.getId());

            return RepeatStatus.FINISHED;

        } finally {
            // Always clean up temp file
            try {
                Files.deleteIfExists(tempPath);
                log.debug("Deleted temp file: {}", tempFilePath);
            } catch (Exception e) {
                log.warn("Failed to delete temp file: {}", tempFilePath, e);
            }
        }
    }

    private String resolveDatabaseType(String connectionId) {
        try {
            ConnectionRequest connection = credentialService.getDecryptedConnection(connectionId);
            if (connection != null && connection.getDbType() != null && !connection.getDbType().isBlank()) {
                return connection.getDbType().toLowerCase(Locale.ROOT);
            }
        } catch (Exception e) {
            log.warn("Failed to resolve database type, defaulting to MySQL", e);
        }
        return "mysql";
    }

    private void updateLastProcessed(String connectionId) {
        Optional<SlowLogSourceConfig> configOpt = configRepository.findByConnectionId(connectionId);
        if (configOpt.isPresent()) {
            SlowLogSourceConfig config = configOpt.get();
            config.setLastProcessedAt(LocalDateTime.now());
            config.setUpdatedAt(LocalDateTime.now());
            configRepository.save(config);
        }
    }
}
