package com.dbaagent.provider.mysql;

import com.dbaagent.provider.api.*;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * MySQL dialect implementation.
 * Provides all MySQL-specific providers through a single component.
 */
@Component
@RequiredArgsConstructor
public class MySQLDialect implements DatabaseDialect {

    private static final String CANONICAL_NAME = "mysql";
    private static final Set<String> ALIASES = Set.of("mysql", "mariadb", "aurora-mysql", "amazon-aurora-mysql");
    private static final String DISPLAY_NAME = "MySQL";

    private final MySQLConnectionProvider connectionProvider;
    private final MySQLIntrospectionProvider introspectionProvider;
    private final MySQLSlowQueryProvider slowQueryProvider;
    private final MySQLPerformanceMetricsProvider metricsProvider;
    private final MySQLLockProvider lockProvider;
    private final MySQLExplainPlanProvider explainPlanProvider;
    private final MySQLConfigurationProvider configurationProvider;
    private final MySQLQueryExecutionProvider queryExecutionProvider;
    private final MySQLPrivilegeCheckProvider privilegeCheckProvider;
    private final MySQLSamplingProvider samplingProvider;

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
}
