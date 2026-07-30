package com.dbaagent.llm.openai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * Spring AI ChatModel implementation for OpenAI and every OpenAI-compatible endpoint:
 * api.openai.com, Azure OpenAI, and self-hosted servers such as vLLM, Ollama, LM Studio
 * and LiteLLM.
 *
 * Supports two API modes:
 *   - Responses API (for reasoning models): /responses, or /openai/responses on Azure
 *   - Chat Completions API: /chat/completions, or
 *     /openai/deployments/{model}/chat/completions on Azure
 *
 * User-visible messages say "chat provider", not "Azure OpenAI": the endpoint is whatever
 * the operator configured, and an OpenAI-only self-hoster reading "Azure OpenAI error" on
 * a 429 has been told something false about their own deployment.
 */
@Slf4j
public class ResponsesApiChatModel implements ChatModel {

    private final String apiUrl;
    private final String apiKey;
    private final String model;
    private final double temperature;
    private final boolean useResponsesApi;
    /** True when the endpoint is Azure, which authenticates with `api-key` rather than a bearer token. */
    private final boolean azureAuth;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;
    private static final int MAX_RETRIES = 3;
    private static final long INITIAL_BACKOFF_MS = 2000;

    public ResponsesApiChatModel(String endpoint, String apiKey, String model,
                                  String apiVersion, double temperature, boolean useResponsesApi) {
        this.useResponsesApi = useResponsesApi;
        this.apiUrl = resolveApiUrl(endpoint, model, apiVersion, useResponsesApi);
        this.apiKey = apiKey;
        this.model = model;
        this.temperature = temperature;
        // Resolved from the configured endpoint rather than the derived apiUrl so the
        // decision is made from what the operator actually set.
        this.azureAuth = OpenAiEndpoints.isAzure(endpoint);
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .build();
        this.objectMapper = new ObjectMapper();

        String mode = resolveModeLabel(endpoint, useResponsesApi);
        log.info("ResponsesApiChatModel initialized: model={}, mode={}, auth={}, url={}",
                model, mode, azureAuth ? "api-key" : "bearer", this.apiUrl);
    }

    /**
     * The single place every outgoing request is built, so the blocking and streaming paths
     * cannot drift apart. They previously each hard-coded {@code api-key}, which meant the
     * documented OpenAI configuration (api.openai.com + an {@code sk-} key) was rejected
     * with a 401 on both paths.
     *
     * <p>Package-private as a test seam: it lets the header choice be asserted on a real
     * {@link HttpRequest} without a live call.
     */
    HttpRequest buildHttpRequest(String body, Duration timeout) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(apiUrl))
                .header("Content-Type", "application/json")
                .timeout(timeout)
                .POST(HttpRequest.BodyPublishers.ofString(body));

        // Azure wants `api-key`; api.openai.com, vLLM, Ollama, LM Studio, LiteLLM and every
        // other OpenAI-compatible server want `Authorization: Bearer`. Sending both would
        // not be harmless — some gateways reject a request carrying two credentials.
        return (azureAuth
                ? builder.header("api-key", apiKey)
                : builder.header("Authorization", "Bearer " + apiKey))
                .build();
    }

    static String resolveApiUrl(String endpoint, String model, String apiVersion, boolean useResponsesApi) {
        String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        boolean isOpenAiCompatible = isOpenAiCompatibleEndpoint(base);

        if (useResponsesApi) {
            return isOpenAiCompatible
                    ? base + "/responses"
                    : base + "/openai/responses?api-version=" + apiVersion;
        }

        if (isOpenAiCompatible) {
            return base + "/chat/completions";
        }

        return base + "/openai/deployments/" + model + "/chat/completions?api-version=" + apiVersion;
    }

    static String resolveModeLabel(String endpoint, boolean useResponsesApi) {
        String base = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        boolean isOpenAiCompatible = isOpenAiCompatibleEndpoint(base);
        if (useResponsesApi) {
            return isOpenAiCompatible ? "openai-responses" : "azure-responses";
        }
        return isOpenAiCompatible ? "openai-completions" : "azure-deployments";
    }

    private static boolean isOpenAiCompatibleEndpoint(String base) {
        return base.contains("/v1") || base.contains("/v2");
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        String body = buildRequestBody(prompt, false);

        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpRequest request = buildHttpRequest(body, Duration.ofMinutes(8));

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (isRetryableStatus(response.statusCode()) && attempt < MAX_RETRIES) {
                    long backoff = INITIAL_BACKOFF_MS * (1L << attempt);
                    log.warn("Chat provider returned {} (attempt {}/{}), retrying in {}ms",
                            response.statusCode(), attempt + 1, MAX_RETRIES, backoff);
                    Thread.sleep(backoff);
                    continue;
                }

                if (response.statusCode() != 200) {
                    throw new RuntimeException(
                            "Chat provider error (" + response.statusCode() + "): " + response.body());
                }

                return parseResponse(response.body());

            } catch (RuntimeException e) {
                throw e;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Interrupted during retry backoff", e);
            } catch (Exception e) {
                throw new RuntimeException("Failed to call the chat provider", e);
            }
        }
        throw new RuntimeException("Chat provider failed after " + MAX_RETRIES + " retries");
    }

    /**
     * 429 plus 5xx.
     *
     * <p>5xx used to fall straight through to the error throw, and a 503 was survivable
     * only because {@code LlmErrorCategory.TRANSIENT} justified swapping the whole install
     * onto the environment credential bundle — a configuration change in response to a
     * momentary fault. Now that TRANSIENT no longer does that, retrying here is what makes
     * a provider hiccup recoverable, which is the correct place for it.
     */
    static boolean isRetryableStatus(int status) {
        return status == 429 || status >= 500;
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        String body = buildRequestBody(prompt, true);

        return Flux.<ChatResponse>create(sink -> Thread.startVirtualThread(() -> streamResponse(body, sink)))
                .timeout(Duration.ofMinutes(3));
    }

    // -- Request building --

    private String buildRequestBody(Prompt prompt, boolean stream) {
        ObjectNode body = objectMapper.createObjectNode();
        body.put("model", model);
        body.put("stream", stream);
        // Honor a per-call temperature override (e.g. low temp for deterministic
        // query rewrites); fall back to the configured default otherwise.
        double effectiveTemperature = temperature;
        if (prompt.getOptions() != null && prompt.getOptions().getTemperature() != null) {
            effectiveTemperature = prompt.getOptions().getTemperature();
        }
        body.put("temperature", effectiveTemperature);

        if (useResponsesApi) {
            buildResponsesApiBody(prompt, body);
        } else {
            buildChatCompletionsBody(prompt, body);
        }

        return body.toString();
    }

    private void buildResponsesApiBody(Prompt prompt, ObjectNode body) {
        StringBuilder instructions = new StringBuilder();
        ArrayNode input = objectMapper.createArrayNode();

        for (Message message : prompt.getInstructions()) {
            MessageType type = message.getMessageType();

            if (type == MessageType.SYSTEM) {
                if (!instructions.isEmpty()) {
                    instructions.append("\n\n");
                }
                instructions.append(message.getText());
            } else if (type == MessageType.USER) {
                ObjectNode msg = objectMapper.createObjectNode();
                msg.put("role", "user");
                msg.put("content", message.getText());
                input.add(msg);
            } else if (type == MessageType.ASSISTANT) {
                ObjectNode msg = objectMapper.createObjectNode();
                msg.put("role", "assistant");
                msg.put("content", message.getText());
                input.add(msg);
            }
        }

        if (!instructions.isEmpty()) {
            body.put("instructions", instructions.toString());
        }
        body.set("input", input);
    }

    private void buildChatCompletionsBody(Prompt prompt, ObjectNode body) {
        ArrayNode messages = objectMapper.createArrayNode();

        for (Message message : prompt.getInstructions()) {
            MessageType type = message.getMessageType();
            ObjectNode msg = objectMapper.createObjectNode();

            if (type == MessageType.SYSTEM) {
                msg.put("role", "system");
                msg.put("content", message.getText());
                messages.add(msg);
            } else if (type == MessageType.USER) {
                msg.put("role", "user");
                msg.put("content", message.getText());
                messages.add(msg);
            } else if (type == MessageType.ASSISTANT) {
                msg.put("role", "assistant");
                msg.put("content", message.getText());
                messages.add(msg);
            }
        }

        body.set("messages", messages);
    }

    // -- Sync response parsing --

    private ChatResponse parseResponse(String responseBody) {
        try {
            JsonNode root = objectMapper.readTree(responseBody);
            String text;

            if (useResponsesApi) {
                text = parseResponsesApiOutput(root);
            } else {
                text = parseChatCompletionsOutput(root);
            }

            ChatResponseMetadata metadata = buildMetadata(root.path("usage"));
            return new ChatResponse(List.of(new Generation(new AssistantMessage(text))), metadata);

        } catch (Exception e) {
            throw new RuntimeException("Failed to parse the chat provider response: " + responseBody, e);
        }
    }

    private String parseResponsesApiOutput(JsonNode root) {
        JsonNode output = root.path("output");
        if (output.isArray()) {
            // Iterate to find the message item — reasoning models may prepend a reasoning item
            for (JsonNode item : output) {
                if ("message".equals(item.path("type").asText())) {
                    JsonNode content = item.path("content");
                    if (content.isArray() && !content.isEmpty()) {
                        return content.get(0).path("text").asText("");
                    }
                }
            }
        }
        return "";
    }

    private String parseChatCompletionsOutput(JsonNode root) {
        JsonNode choices = root.path("choices");
        if (choices.isArray() && !choices.isEmpty()) {
            return choices.get(0).path("message").path("content").asText("");
        }
        return "";
    }

    private ChatResponseMetadata buildMetadata(JsonNode usage) {
        return ChatResponseMetadata.builder().build();
    }

    // -- Streaming --

    private void streamResponse(String body, FluxSink<ChatResponse> sink) {
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                HttpRequest request = buildHttpRequest(body, Duration.ofMinutes(3));

                HttpResponse<java.io.InputStream> response =
                        httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());

                if (isRetryableStatus(response.statusCode()) && attempt < MAX_RETRIES) {
                    long backoff = INITIAL_BACKOFF_MS * (1L << attempt);
                    log.warn("Chat provider stream returned {} (attempt {}/{}), retrying in {}ms",
                            response.statusCode(), attempt + 1, MAX_RETRIES, backoff);
                    response.body().close();
                    Thread.sleep(backoff);
                    continue;
                }

                if (response.statusCode() != 200) {
                    String errorBody = new String(response.body().readAllBytes(), StandardCharsets.UTF_8);
                    sink.error(new RuntimeException(
                            "Chat provider stream error (" + response.statusCode() + "): " + errorBody));
                    return;
                }

                try (BufferedReader reader = new BufferedReader(
                        new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (!line.startsWith("data: ")) {
                            continue;
                        }
                        String data = line.substring(6).trim();
                        if (data.isEmpty() || "[DONE]".equals(data)) {
                            continue;
                        }

                        String delta = useResponsesApi
                                ? extractResponsesApiDelta(data)
                                : extractChatCompletionsDelta(data);

                        if (delta != null && !delta.isEmpty()) {
                            sink.next(new ChatResponse(
                                    List.of(new Generation(new AssistantMessage(delta)))));
                        }
                    }
                }
                sink.complete();
                return;

            } catch (java.net.http.HttpTimeoutException e) {
                log.error("Chat provider streaming timed out", e);
                sink.error(new RuntimeException("AI response timed out — please try again", e));
                return;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                sink.error(new RuntimeException("Streaming interrupted", e));
                return;
            } catch (Exception e) {
                log.error("Chat provider streaming failed", e);
                sink.error(new RuntimeException("Streaming failed", e));
                return;
            }
        }
    }

    private String extractResponsesApiDelta(String data) {
        try {
            JsonNode event = objectMapper.readTree(data);
            if ("response.output_text.delta".equals(event.path("type").asText())) {
                return event.path("delta").asText("");
            }
        } catch (Exception e) {
            log.debug("Failed to parse SSE event: {}", data);
        }
        return null;
    }

    private String extractChatCompletionsDelta(String data) {
        try {
            JsonNode event = objectMapper.readTree(data);
            JsonNode choices = event.path("choices");
            if (choices.isArray() && !choices.isEmpty()) {
                return choices.get(0).path("delta").path("content").asText("");
            }
        } catch (Exception e) {
            log.debug("Failed to parse SSE event: {}", data);
        }
        return null;
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return ChatOptions.builder()
                .temperature(temperature)
                .model(model)
                .build();
    }
}
