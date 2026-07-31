package com.dbaagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.scheduling.config.TaskManagementConfigUtils;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulingConfigProfileTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(SchedulingConfig.class);

    @Test
    void schedulingIsEnabledForDefaultLocalProfile() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(SchedulingConfig.class);
            assertThat(context.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME))
                .isTrue();
        });
    }

    @Test
    void schedulingIsEnabledForProdProfile() {
        contextRunner
            .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("prod"))
            .run(context -> {
                assertThat(context).hasSingleBean(SchedulingConfig.class);
                assertThat(context.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME))
                    .isTrue();
            });
    }

    @Test
    void schedulingIsDisabledForTestProfile() {
        contextRunner
            .withInitializer(ctx -> ctx.getEnvironment().setActiveProfiles("test"))
            .run(context -> {
                assertThat(context).doesNotHaveBean(SchedulingConfig.class);
                assertThat(context.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME))
                    .isFalse();
            });
    }
}
