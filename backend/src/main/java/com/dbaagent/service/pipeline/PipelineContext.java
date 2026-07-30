package com.dbaagent.service.pipeline;

import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.service.RetrievedContextResult;
import org.springframework.ai.chat.messages.Message;
import java.util.*;

public record PipelineContext(
    String connectionId,
    String userQuestion,
    String dbType,
    String schemaContext,
    SchemaMetadata schemaMetadata,
    RetrievedContextResult ragContext,
    String feedbackContext,
    String classificationContext,
    String performanceContext,
    String brainContext,
    String dbSpecificRules,
    List<Message> conversationHistory,
    PipelineProgressListener progressListener
) {
    public PipelineContext {
        Objects.requireNonNull(connectionId, "connectionId required");
        Objects.requireNonNull(userQuestion, "userQuestion required");
        Objects.requireNonNull(dbType, "dbType required");
        if (progressListener == null) {
            progressListener = PipelineProgressListener.NOOP;
        }
    }
}
