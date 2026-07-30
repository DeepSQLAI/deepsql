package com.dbaagent.service.scheduler;

import com.dbaagent.model.InitStage;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.TaskDescriptor;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Profile;

import java.time.Instant;

@Configuration
@Profile("!test")
@Slf4j
public class BrainInitTaskConfig {

    public static final TaskDescriptor<BrainInitTaskData> BRAIN_INIT_STAGE =
        TaskDescriptor.of("brain-init-stage", BrainInitTaskData.class);

    @Bean
    Task<BrainInitTaskData> brainInitStageTask(
            BrainInitStageExecutor stageExecutor,
            @Lazy SchedulerClient schedulerClient) {

        return Tasks.oneTime(BRAIN_INIT_STAGE)
            .execute((taskInstance, ctx) -> {
                BrainInitTaskData data = taskInstance.getData();
                log.info("Executing brain init stage {} for connection {}",
                    data.currentStage(), data.connectionId());

                InitStage nextStage = stageExecutor.executeStage(data);

                if (nextStage != null) {
                    BrainInitTaskData nextData = data.withStage(nextStage);
                    String instanceId = data.runId() + "-" + nextStage.name();
                    schedulerClient.scheduleIfNotExists(
                        BRAIN_INIT_STAGE.instance(instanceId)
                            .data(nextData)
                            .scheduledTo(Instant.now())
                    );
                    log.info("Scheduled next stage {} for connection {}",
                        nextStage, data.connectionId());
                }
            });
    }
}
