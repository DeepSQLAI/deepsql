package com.dbaagent.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Enables scheduled jobs for non-test profiles only.
 *
 * Integration tests use the "test" profile and must remain deterministic,
 * so background scheduled jobs are disabled there.
 */
@Configuration
@EnableScheduling
@Profile("!test")
public class SchedulingConfig {
}
