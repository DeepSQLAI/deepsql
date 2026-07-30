package com.dbaagent.service.telemetry;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Regression guard for the self-host telemetry outage: new one-liner installs
 * never appeared in PostHog because the PostHog project key was not binding
 * into {@link TelemetryProperties}.
 *
 * <p>Root cause: the field {@code posthogProjectKey} has canonical name
 * {@code deepsql.telemetry.posthog-project-key}. Spring Boot's environment-variable
 * relaxed binding for that name is {@code DEEPSQL_TELEMETRY_POSTHOGPROJECTKEY}
 * (dots &rarr; underscores, <em>dashes removed</em>, uppercased). But the installer
 * and {@code .env.example} ship {@code DEEPSQL_TELEMETRY_POSTHOG_PROJECT_KEY}
 * (underscores <em>between</em> posthog/project/key), which Spring reads as the
 * unrelated property {@code deepsql.telemetry.posthog.project.key}. The two never
 * match, so the key stayed {@code ""} and {@code TelemetryConfig} selected the
 * {@code NoOpTelemetrySink} &mdash; every {@code install.bootstrapped} event was
 * silently dropped.
 *
 * <p>The fix wires an explicit {@code ${DEEPSQL_TELEMETRY_POSTHOG_PROJECT_KEY:}}
 * placeholder in {@code application.properties}, matching how every other
 * multi-word env var (e.g. {@code azure.openai.chat-deployment}) is bound.
 *
 * <p>This test loads the REAL {@code application.properties} (via
 * {@link ConfigDataApplicationContextInitializer}) and injects ONLY the env var
 * the installer sets &mdash; using a {@link SystemEnvironmentPropertySource} so
 * Spring's env-var name mapping is exercised exactly as it is inside the
 * container. It therefore verifies the shipped wiring, not a value the test
 * supplies itself.
 */
class TelemetryPropertiesEnvBindingTest {

    /** The exact env var name install.sh / .env.example ship to the backend container. */
    private static final String INSTALL_ENV_VAR = "DEEPSQL_TELEMETRY_POSTHOG_PROJECT_KEY";
    private static final String SAMPLE_KEY = "phc_env_binding_sample";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            // Load the real application.properties so we assert on the shipped placeholder.
            .withInitializer(new ConfigDataApplicationContextInitializer())
            // Simulate the container's process environment with the single installer var.
            .withInitializer(ctx -> ctx.getEnvironment().getPropertySources().addFirst(
                    new SystemEnvironmentPropertySource(
                            StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                            Map.of(INSTALL_ENV_VAR, SAMPLE_KEY))))
            .withUserConfiguration(Config.class);

    @EnableConfigurationProperties(TelemetryProperties.class)
    static class Config {
    }

    @Test
    void bindsPosthogKeyFromInstallerEnvVarName() {
        runner.run(ctx -> assertEquals(
                SAMPLE_KEY,
                ctx.getBean(TelemetryProperties.class).getPosthogProjectKey(),
                "posthog project key must bind from the " + INSTALL_ENV_VAR
                        + " env var the installer ships; otherwise TelemetryConfig falls back "
                        + "to NoOpTelemetrySink and install.bootstrapped never reaches PostHog"));
    }
}
