package com.dbaagent.provider.postgres;

import com.dbaagent.provider.api.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * PostgreSQL dialect implementation.
 * Provides all PostgreSQL-specific providers through a single component.
 */
@Component
@RequiredArgsConstructor
public class PostgresDialect implements DatabaseDialect {

    private static final String CANONICAL_NAME = "postgres";
    private static final Set<String> ALIASES = Set.of("postgres", "postgresql", "pg", "aurora-postgresql", "amazon-aurora-postgresql");
    private static final String DISPLAY_NAME = "PostgreSQL";

    private final PostgresConnectionProvider connectionProvider;
    private final PostgresIntrospectionProvider introspectionProvider;
    private final PostgresSlowQueryProvider slowQueryProvider;
    private final PostgresPerformanceMetricsProvider metricsProvider;
    private final PostgresLockProvider lockProvider;
    private final PostgresExplainPlanProvider explainPlanProvider;
    private final PostgresConfigurationProvider configurationProvider;
    private final PostgresQueryExecutionProvider queryExecutionProvider;
    private final PostgresPrivilegeCheckProvider privilegeCheckProvider;
    private final PostgresSamplingProvider samplingProvider;
    private final PostgresMigrationRiskProvider migrationRiskProvider;

    @Override
    public String getCanonicalName() {
        return CANONICAL_NAME;
    }

    @Override
    public Set<String> getAliases() {
        return ALIASES;
    }

    @Override
    public String getDisplayName() {
        return DISPLAY_NAME;
    }

    @Override
    public ConnectionProvider connection() {
        return connectionProvider;
    }

    @Override
    public IntrospectionProvider introspection() {
        return introspectionProvider;
    }

    @Override
    public SlowQueryProvider slowQueries() {
        return slowQueryProvider;
    }

    @Override
    public PerformanceMetricsProvider metrics() {
        return metricsProvider;
    }

    @Override
    public LockProvider locks() {
        return lockProvider;
    }

    @Override
    public ExplainPlanProvider explainPlan() {
        return explainPlanProvider;
    }

    @Override
    public ConfigurationProvider configuration() {
        return configurationProvider;
    }

    @Override
    public QueryExecutionProvider queryExecution() {
        return queryExecutionProvider;
    }

    @Override
    public PrivilegeCheckProvider privileges() {
        return privilegeCheckProvider;
    }

    @Override
    public SamplingProvider sampling() {
        return samplingProvider;
    }

    @Override
    public MigrationRiskProvider migrationRisk() {
        return migrationRiskProvider;
    }
}
