package com.dbaagent.llm.openai;

import com.dbaagent.llm.api.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.channels.UnresolvedAddressException;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Chat provider for OpenAI and every OpenAI-compatible endpoint: Azure OpenAI, and
 * self-hosted servers such as vLLM, Ollama, LM Studio and TGI, reached by pointing
 * {@code endpoint} at their base URL.
 */
@Slf4j
@Component
public class OpenAiCompatibleChatProvider implements LlmChatProvider {

    private static final LlmProviderDescriptor DESCRIPTOR = new LlmProviderDescriptor(
            "openai",
            Set.of("azure", "azure-openai", "openai-compatible", "self-hosted"),
            "OpenAI / Azure OpenAI / OpenAI-compatible",
            Set.of(LlmCapability.CHAT, LlmCapability.STREAMING),
            List.of(
                LlmCredentialField.secret("api-key", "API key"),
                LlmCredentialField.required("endpoint", "Endpoint base URL",
                        "https://api.openai.com/v1"),
                LlmCredentialField.required("model", "Model or deployment name", "gpt-4o"),
                LlmCredentialField.optional("api-version", "Azure API version",
                        "2025-03-01-preview"),
                LlmCredentialField.optional("use-responses-api",
                        "Use the Responses API", "auto"),
                LlmCredentialField.optional("temperature", "Temperature", "1.0")
            ),
            128_000);

    @Override
    public LlmProviderDescriptor descriptor() {
        return DESCRIPTOR;
    }

    @Override
    public ChatModel delegate(LlmCredentials c) {
        String model = c.getOrDefault("model", "gpt-4o");
        return new ResponsesApiChatModel(
                c.getOrDefault("endpoint", ""),
                c.getOrDefault("api-key", ""),
                model,
                c.getOrDefault("api-version", "2025-03-01-preview"),
                resolveTemperature(c),
                resolveUseResponsesApi(c, model));
    }

    /** Mirrors RefreshableChatModel.resolveTemperature: never let a bad wizard value crash startup. */
    private double resolveTemperature(LlmCredentials c) {
        String configured = c.getOrDefault("temperature", "1.0");
        try {
            return Double.parseDouble(configured);
        } catch (NumberFormatException e) {
            log.warn("OpenAiCompatibleChatProvider: invalid temperature '{}', falling back to 1.0",
                    configured);
            return 1.0;
        }
    }

    /**
     * The Responses API is required for reasoning models and unsupported by older ones.
     * "auto" preserves the model-prefix heuristic that lived in RefreshableChatModel.
     */
    private boolean resolveUseResponsesApi(LlmCredentials c, String model) {
        String configured = c.getOrDefault("use-responses-api", "auto");
        if (!"auto".equalsIgnoreCase(configured)) {
            return Boolean.parseBoolean(configured);
        }
        String m = model == null ? "" : model.trim().toLowerCase(Locale.ROOT);
        if (m.startsWith("gpt-4") || m.startsWith("gpt-3")) {
            return false;
        }
        return m.isEmpty() || m.startsWith("gpt-5") || m.startsWith("o1")
                || m.startsWith("o3") || m.startsWith("o4") || m.startsWith("codex");
    }

    @Override
    public LlmErrorCategory classify(Throwable throwable) {
        for (Throwable t = throwable; t != null; t = t.getCause()) {
            // IOException covers ConnectException, UnknownHostException,
            // java.net.http.HttpTimeoutException and any other network failure that
            // ResponsesApiChatModel.call()'s generic catch(Exception e) wraps as
            // "Failed to call the chat provider" before rethrowing — classification here must
            // be by cause type, not message text, since that wrapper message is constant
            // regardless of the underlying failure.
            //
            // JsonProcessingException is excluded even though it extends IOException:
            // ResponsesApiChatModel.parseResponse() wraps it as its own RuntimeException
            // ("Failed to parse the chat provider response: ..."), which is already a
            // RuntimeException and so is rethrown verbatim by call()'s first catch
            // clause — it never passes through the generic network-failure wrapper above.
            // The old string-matching never recognised that message as recoverable, so a
            // parse failure must still resolve to UNKNOWN, not TRANSIENT.
            if ((t instanceof IOException && !(t instanceof JsonProcessingException))
                    || t instanceof UnresolvedAddressException) {
                return LlmErrorCategory.TRANSIENT;
            }
            String message = t.getMessage();
            if (message == null) {
                continue;
            }
            String m = message.toLowerCase(Locale.ROOT);
            if (m.contains("deploymentnotfound") || m.contains("(404)")) {
                return LlmErrorCategory.MODEL_NOT_FOUND;
            }
            if (m.contains("(401)") || m.contains("(403)")) {
                return LlmErrorCategory.AUTH;
            }
            if (m.contains("(429)")) {
                return LlmErrorCategory.RATE_LIMIT;
            }
            if (m.contains("maximum context length") || m.contains("context_length_exceeded")) {
                return LlmErrorCategory.CONTEXT_LENGTH;
            }
            if (m.contains("(500)") || m.contains("(502)") || m.contains("(503)")
                    || m.contains("(504)")) {
                return LlmErrorCategory.TRANSIENT;
            }
        }
        return LlmErrorCategory.UNKNOWN;
    }
}
