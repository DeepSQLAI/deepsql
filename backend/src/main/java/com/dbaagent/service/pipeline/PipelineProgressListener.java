package com.dbaagent.service.pipeline;

import java.util.Map;

@FunctionalInterface
public interface PipelineProgressListener {
    void onProgress(String step, String message, Map<String, Object> metadata);

    PipelineProgressListener NOOP = (step, message, metadata) -> {};
}
