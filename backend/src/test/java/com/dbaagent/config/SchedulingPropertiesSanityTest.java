package com.dbaagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulingPropertiesSanityTest {

    @Test
    void localApplicationPropertiesDoNotDisableScheduling() throws IOException {
        Properties props = load("application.properties");
        assertThat(props.getProperty("spring.task.scheduling.enabled")).isNotEqualTo("false");
    }

    @Test
    void prodApplicationPropertiesDoNotDisableScheduling() throws IOException {
        Properties props = load("application-prod.properties");
        assertThat(props.getProperty("spring.task.scheduling.enabled")).isNotEqualTo("false");
    }

    private Properties load(String classpathFile) throws IOException {
        Properties props = new Properties();
        ClassPathResource resource = new ClassPathResource(classpathFile);
        try (InputStream input = resource.getInputStream()) {
            props.load(input);
        }
        return props;
    }
}
