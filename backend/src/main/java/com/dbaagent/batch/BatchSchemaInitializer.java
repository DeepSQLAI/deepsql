package com.dbaagent.batch;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.jdbc.datasource.init.DataSourceInitializer;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import java.util.List;

/**
 * Initializes Spring Batch tables with custom prefix DBA_BATCH_.
 * Uses DataSourceInitializer with high priority to ensure tables are created
 * BEFORE Spring Batch beans try to use them.
 *
 * Note: The bean name "dbaBatchSchemaInitializer" allows SlowLogIngestionBatchConfig
 * to depend on it via @DependsOn("dbaBatchSchemaInitializer").
 */
@Configuration
@Slf4j
public class BatchSchemaInitializer {

    private static final String SCHEMA_SQL = """
        -- Create Spring Batch tables with DBA_BATCH_ prefix

        CREATE TABLE IF NOT EXISTS DBA_BATCH_JOB_INSTANCE (
            JOB_INSTANCE_ID BIGINT NOT NULL PRIMARY KEY,
            VERSION BIGINT,
            JOB_NAME VARCHAR(100) NOT NULL,
            JOB_KEY VARCHAR(32) NOT NULL,
            CONSTRAINT DBA_BATCH_JOB_INST_UN UNIQUE (JOB_NAME, JOB_KEY)
        );

        CREATE TABLE IF NOT EXISTS DBA_BATCH_JOB_EXECUTION (
            JOB_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
            VERSION BIGINT,
            JOB_INSTANCE_ID BIGINT NOT NULL,
            CREATE_TIME TIMESTAMP NOT NULL,
            START_TIME TIMESTAMP DEFAULT NULL,
            END_TIME TIMESTAMP DEFAULT NULL,
            STATUS VARCHAR(10),
            EXIT_CODE VARCHAR(2500),
            EXIT_MESSAGE VARCHAR(2500),
            LAST_UPDATED TIMESTAMP,
            CONSTRAINT DBA_BATCH_JOB_INST_EXEC_FK FOREIGN KEY (JOB_INSTANCE_ID)
                REFERENCES DBA_BATCH_JOB_INSTANCE(JOB_INSTANCE_ID)
        );

        CREATE TABLE IF NOT EXISTS DBA_BATCH_JOB_EXECUTION_PARAMS (
            JOB_EXECUTION_ID BIGINT NOT NULL,
            PARAMETER_NAME VARCHAR(100) NOT NULL,
            PARAMETER_TYPE VARCHAR(100) NOT NULL,
            PARAMETER_VALUE VARCHAR(2500),
            IDENTIFYING CHAR(1) NOT NULL,
            CONSTRAINT DBA_BATCH_JOB_EXEC_PARAMS_FK FOREIGN KEY (JOB_EXECUTION_ID)
                REFERENCES DBA_BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
        );

        CREATE TABLE IF NOT EXISTS DBA_BATCH_STEP_EXECUTION (
            STEP_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
            VERSION BIGINT NOT NULL,
            STEP_NAME VARCHAR(100) NOT NULL,
            JOB_EXECUTION_ID BIGINT NOT NULL,
            CREATE_TIME TIMESTAMP NOT NULL,
            START_TIME TIMESTAMP DEFAULT NULL,
            END_TIME TIMESTAMP DEFAULT NULL,
            STATUS VARCHAR(10),
            COMMIT_COUNT BIGINT,
            READ_COUNT BIGINT,
            FILTER_COUNT BIGINT,
            WRITE_COUNT BIGINT,
            READ_SKIP_COUNT BIGINT,
            WRITE_SKIP_COUNT BIGINT,
            PROCESS_SKIP_COUNT BIGINT,
            ROLLBACK_COUNT BIGINT,
            EXIT_CODE VARCHAR(2500),
            EXIT_MESSAGE VARCHAR(2500),
            LAST_UPDATED TIMESTAMP,
            CONSTRAINT DBA_BATCH_JOB_EXEC_STEP_FK FOREIGN KEY (JOB_EXECUTION_ID)
                REFERENCES DBA_BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
        );

        CREATE TABLE IF NOT EXISTS DBA_BATCH_STEP_EXECUTION_CONTEXT (
            STEP_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
            SHORT_CONTEXT VARCHAR(2500) NOT NULL,
            SERIALIZED_CONTEXT TEXT,
            CONSTRAINT DBA_BATCH_STEP_EXEC_CTX_FK FOREIGN KEY (STEP_EXECUTION_ID)
                REFERENCES DBA_BATCH_STEP_EXECUTION(STEP_EXECUTION_ID)
        );

        CREATE TABLE IF NOT EXISTS DBA_BATCH_JOB_EXECUTION_CONTEXT (
            JOB_EXECUTION_ID BIGINT NOT NULL PRIMARY KEY,
            SHORT_CONTEXT VARCHAR(2500) NOT NULL,
            SERIALIZED_CONTEXT TEXT,
            CONSTRAINT DBA_BATCH_JOB_EXEC_CTX_FK FOREIGN KEY (JOB_EXECUTION_ID)
                REFERENCES DBA_BATCH_JOB_EXECUTION(JOB_EXECUTION_ID)
        );

        CREATE SEQUENCE IF NOT EXISTS DBA_BATCH_STEP_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
        CREATE SEQUENCE IF NOT EXISTS DBA_BATCH_JOB_EXECUTION_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
        CREATE SEQUENCE IF NOT EXISTS DBA_BATCH_JOB_INSTANCE_SEQ MAXVALUE 9223372036854775807 NO CYCLE;
        """;

    private static final List<String> REQUIRED_TABLES = List.of(
        "dba_batch_job_instance",
        "dba_batch_job_execution",
        "dba_batch_job_execution_params",
        "dba_batch_step_execution",
        "dba_batch_step_execution_context",
        "dba_batch_job_execution_context"
    );

    @Bean("dbaBatchSchemaInitializer")
    @Order(Ordered.HIGHEST_PRECEDENCE)
    public DataSourceInitializer dbaBatchSchemaInitializer(DataSource dataSource) {
        log.info("Initializing Spring Batch schema with DBA_BATCH_ prefix");

        ResourceDatabasePopulator populator = new ResourceDatabasePopulator();
        populator.addScript(new ByteArrayResource(SCHEMA_SQL.getBytes()));
        populator.setContinueOnError(true);  // Continue if tables already exist
        populator.setIgnoreFailedDrops(true);

        DataSourceInitializer initializer = new DataSourceInitializer();
        initializer.setDataSource(dataSource);
        initializer.setDatabasePopulator(populator);
        return initializer;
    }

    /**
     * Validates that all required Spring Batch tables exist after schema initialization.
     * Fails fast if any table is missing (e.g., due to permission errors during DDL).
     */
    @Bean("dbaBatchSchemaValidator")
    @DependsOn("dbaBatchSchemaInitializer")
    public Object dbaBatchSchemaValidator(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        for (String table : REQUIRED_TABLES) {
            try {
                // Use a lightweight query to check table existence
                jdbc.queryForObject(
                    "SELECT 1 FROM information_schema.tables WHERE table_name = ?",
                    Integer.class,
                    table
                );
                log.debug("Validated batch table exists: {}", table);
            } catch (Exception e) {
                String msg = "Spring Batch table '" + table + "' does not exist. " +
                    "Check database permissions and DDL execution. Error: " + e.getMessage();
                log.error(msg);
                throw new IllegalStateException(msg, e);
            }
        }

        log.info("All Spring Batch tables validated successfully");
        return new Object(); // Return dummy bean
    }
}
