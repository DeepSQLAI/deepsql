package com.dbaagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.kagkarlsson.scheduler.boot.config.DbSchedulerCustomizer;
import com.github.kagkarlsson.scheduler.serializer.JacksonSerializer;
import com.github.kagkarlsson.scheduler.serializer.Serializer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Optional;

/**
 * Configures db-scheduler to use Jackson JSON serialization for task data
 * instead of Java serialization. This makes task data human-readable in
 * the database and avoids class compatibility issues across deployments.
 */
@Configuration
public class DbSchedulerConfig {

    @Bean
    DbSchedulerCustomizer dbSchedulerCustomizer(ObjectMapper objectMapper) {
        return new DbSchedulerCustomizer() {
            @Override
            public Optional<Serializer> serializer() {
                return Optional.of(new JacksonSerializer(objectMapper));
            }
        };
    }
}
