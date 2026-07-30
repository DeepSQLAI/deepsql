package com.dbaagent.service.pipeline;

import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.service.ConnectionService;
import com.dbaagent.util.QueryNormalizer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

public class SqlValidator {

    private static final Logger log = LoggerFactory.getLogger(SqlValidator.class);

    private final ConnectionService connectionService;
    private final DatabaseProviderRegistry providerRegistry;
    private final int queryTimeoutSeconds;

    public SqlValidator(ConnectionService connectionService,
                        DatabaseProviderRegistry providerRegistry) {
        this(connectionService, providerRegistry, 5);
    }

    public SqlValidator(ConnectionService connectionService,
                        DatabaseProviderRegistry providerRegistry,
                        int queryTimeoutSeconds) {
        this.connectionService = connectionService;
        this.providerRegistry = providerRegistry;
        this.queryTimeoutSeconds = queryTimeoutSeconds;
    }

    public ValidationResult validate(String connectionId, String sql, String dbType) {
        if (sql == null || sql.isBlank()) {
            return ValidationResult.invalid("SQL is null or blank");
        }

        ValidationResult safetyCheck = validateSafety(sql);
        if (!safetyCheck.valid()) {
            return safetyCheck;
        }

        try {
            var jdbc = connectionService.getJdbcTemplateForBackgroundJob(connectionId);
            jdbc.setQueryTimeout(queryTimeoutSeconds);

            var dialect = providerRegistry.getDialect(dbType);
            var explainProvider = dialect.explainPlan();
            var dataSource = jdbc.getDataSource();
            try (var conn = dataSource.getConnection()) {
                var results = explainProvider.executeExplain(conn, sql, false);
                String plan = results.toString();
                return ValidationResult.valid(plan);
            }
        } catch (Exception e) {
            String error = extractErrorMessage(e);
            log.debug("EXPLAIN validation failed for connection {}: {}", connectionId, error);
            return ValidationResult.invalid(error);
        }
    }

    private ValidationResult validateSafety(String sql) {
        String raw = stripLeadingComments(sql).trim();
        if (raw.isBlank()) {
            return ValidationResult.invalid("SQL is null or blank");
        }

        String upperRaw = raw.toUpperCase(Locale.ROOT);
        if (upperRaw.startsWith("EXPLAIN ANALYZE") || upperRaw.startsWith("EXPLAIN ANALYSE")) {
            return ValidationResult.invalid("EXPLAIN ANALYZE is not allowed for validation");
        }

        String sanitized = QueryNormalizer.sanitize(raw).trim();
        if (sanitized.isBlank()) {
            return ValidationResult.invalid("SQL is null or blank");
        }

        String upper = sanitized.toUpperCase(Locale.ROOT);

        if (upper.startsWith("EXPLAIN ANALYZE") || upper.startsWith("EXPLAIN ANALYSE")) {
            return ValidationResult.invalid("EXPLAIN ANALYZE is not allowed for validation");
        }

        String normalizedWithoutTrailingSemicolons = sanitized.replaceAll(";\\s*$", "");
        if (normalizedWithoutTrailingSemicolons.contains(";")) {
            return ValidationResult.invalid("Multiple SQL statements are not allowed");
        }

        boolean readOnly = upper.startsWith("SELECT")
            || upper.startsWith("WITH")
            || upper.startsWith("SHOW")
            || upper.startsWith("DESCRIBE")
            || upper.startsWith("DESC")
            || upper.startsWith("EXPLAIN");
        if (!readOnly) {
            return ValidationResult.invalid("Only read-only SQL is allowed");
        }

        return ValidationResult.valid(null);
    }

    private String stripLeadingComments(String sql) {
        String remaining = sql == null ? "" : sql;

        boolean changed;
        do {
            changed = false;
            String trimmed = remaining.stripLeading();

            if (trimmed.startsWith("--")) {
                int newlineIndex = trimmed.indexOf('\n');
                remaining = newlineIndex >= 0 ? trimmed.substring(newlineIndex + 1) : "";
                changed = true;
                continue;
            }

            if (trimmed.startsWith("/*")) {
                int commentEnd = trimmed.indexOf("*/");
                remaining = commentEnd >= 0 ? trimmed.substring(commentEnd + 2) : "";
                changed = true;
            }
        } while (changed);

        return remaining;
    }

    private String extractErrorMessage(Exception e) {
        Throwable cause = e;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause.getMessage();
    }
}
