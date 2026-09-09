package com.dbaagent.service.llm;

import com.dbaagent.model.LlmUsage;
import com.dbaagent.repository.LlmUsageRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The transactional half of usage recording, kept in its own bean deliberately.
 *
 * <p>{@code REQUIRES_NEW} is applied by a Spring proxy, and a proxy is bypassed on
 * self-invocation — so annotating a private method that {@link LlmUsageRecorder} calls
 * through {@code this} would silently join the caller's transaction instead of starting
 * its own, and the row would roll back with a failed chat turn. That is exactly the
 * failure this propagation exists to prevent, and it would leave no trace.
 */
@Component
public class LlmUsageWriter {

    private final LlmUsageRepository repository;

    public LlmUsageWriter(LlmUsageRepository repository) {
        this.repository = repository;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void write(LlmUsage row) {
        repository.save(row);
    }
}
