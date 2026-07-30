package com.dbaagent.batch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.JobRegistry;
import org.springframework.batch.core.configuration.annotation.EnableBatchProcessing;
import org.springframework.batch.core.configuration.annotation.EnableJdbcJobRepository;
import org.springframework.batch.core.configuration.support.MapJobRegistry;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.launch.support.TaskExecutorJobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.Primary;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Spring Batch 6.0.2 configuration for slow query log ingestion.
 *
 * Two-step job:
 * 1. fetchLogsStep - Downloads logs from CloudWatch/S3 into temp file
 * 2. parseAndPersistStep - Parses logs and saves analysis to database
 *
 * Spring Batch 6.0.2 annotations:
 * - @EnableBatchProcessing: Registers step/job scopes, JobRegistry, and Batch infrastructure
 * - @EnableJdbcJobRepository: Configures JDBC-backed JobRepository with custom table prefix
 *
 * The combination ensures:
 * - @StepScope works for job parameter injection
 * - Jobs are registered with JobRegistry for restart() support
 * - TaskExecutorJobOperator provides async execution
 */
@Configuration
@EnableBatchProcessing
@EnableJdbcJobRepository(tablePrefix = "DBA_BATCH_")
@DependsOn("dbaBatchSchemaValidator")
@Slf4j
public class SlowLogIngestionBatchConfig {

    @Bean
    public Job slowLogIngestionJob(JobRepository jobRepository,
                                    Step fetchLogsStep,
                                    Step parseAndPersistStep) {
        return new JobBuilder("slowLogIngestionJob", jobRepository)
            .start(fetchLogsStep)
            .next(parseAndPersistStep)
            .build();
    }

    @Bean
    public Step fetchLogsStep(JobRepository jobRepository,
                              PlatformTransactionManager transactionManager,
                              FetchLogsTasklet fetchLogsTasklet) {
        return new StepBuilder("fetchLogsStep", jobRepository)
            .tasklet(fetchLogsTasklet, transactionManager)
            .build();
    }

    @Bean
    public Step parseAndPersistStep(JobRepository jobRepository,
                                    PlatformTransactionManager transactionManager,
                                    ParseLogsTasklet parseLogsTasklet) {
        return new StepBuilder("parseAndPersistStep", jobRepository)
            .tasklet(parseLogsTasklet, transactionManager)
            .build();
    }

    /**
     * TaskExecutorJobOperator for async job execution.
     * Jobs run in a thread pool so API calls return immediately.
     *
     * Named "asyncJobOperator" to avoid potential bean name clash with @EnableBatchProcessing.
     * @Primary ensures this bean is used when JobOperator is autowired.
     */
    @Bean("asyncJobOperator")
    @Primary
    public JobOperator asyncJobOperator(JobRepository jobRepository, JobRegistry jobRegistry) {
        TaskExecutorJobOperator operator = new TaskExecutorJobOperator();
        operator.setJobRepository(jobRepository);
        operator.setJobRegistry(jobRegistry);
        operator.setTaskExecutor(batchTaskExecutor());
        return operator;
    }

    @Bean
    public TaskExecutor batchTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(4);
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("batch-ingestion-");
        executor.initialize();
        return executor;
    }

    /**
     * JobRegistry for storing and retrieving Job instances.
     * Required for JobOperator.restart() to find jobs by name.
     *
     * @EnableBatchProcessing auto-provides a JobRegistrySmartInitializingSingleton
     * that registers all Job beans with this registry.
     */
    @Bean
    public JobRegistry jobRegistry() {
        return new MapJobRegistry();
    }

}
