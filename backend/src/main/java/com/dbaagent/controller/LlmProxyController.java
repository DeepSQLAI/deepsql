package com.dbaagent.controller;

import com.dbaagent.llm.LlmConfigResolver;
import com.dbaagent.llm.api.LlmCredentials;
import com.dbaagent.llm.openai.OpenAiEndpoints;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * OpenAI-compatible LLM proxy for the DeepSQL Agent (CLI/TUI + headless).
 *
 * <p>The agent points its model at {@code <backend>/api/llm/v1} with the user's DeepSQL
 * token as the API key, so customers need no LLM credentials of their own. This endpoint
 * authenticates via the normal DeepSQL JWT filter, then forwards the request to whichever
 * chat provider the deployment is configured with — injecting that provider's key
 * server-side — and streams the response back verbatim: SSE for {@code stream:true},
 * JSON otherwise.
 *
 * <p>Credentials come from {@link LlmConfigResolver#resolveChat()}, the same resolution the
 * rest of the application uses, and the auth header is chosen by
 * {@link OpenAiEndpoints#isAzure(String)}. This class used to bind the Azure-specific
 * endpoint and key properties directly and hardcode an {@code api-key} header, which meant
 * an OpenAI-key self-hoster got a 401 on every CLI request — and once the credential
 * defaults were removed from the properties files, an empty endpoint and a relative URI
 * that {@code WebClient} rejects outright.
 */
@RestController
@RequestMapping("/llm/v1")
public class LlmProxyController {

    private static final Logger log = LoggerFactory.getLogger(LlmProxyController.class);

    /**
     * How long to wait for the upstream <em>response head</em>. The body may stream for far
     * longer; this bounds only the window in which a failure can still be turned into an
     * honest HTTP status.
     */
    private static final Duration HEAD_TIMEOUT = Duration.ofMinutes(2);

    private static final String NOT_CONFIGURED_MESSAGE =
            "No chat provider is configured, so the DeepSQL LLM proxy has nothing to forward "
            + "to. Set DEEPSQL_CHAT_PROVIDER, DEEPSQL_CHAT_ENDPOINT and DEEPSQL_CHAT_API_KEY "
            + "(plus DEEPSQL_CHAT_MODEL), or complete setup at /onboarding.";

    private final LlmConfigResolver resolver;
    private final WebClient webClient;

    /**
     * Marked explicitly because the package-private test seam below is a second candidate
     * constructor; without this Spring finds no unambiguous one, falls back to looking for
     * a no-arg constructor, and fails context refresh with "No default constructor found".
     */
    @Autowired
    public LlmProxyController(LlmConfigResolver resolver) {
        this(resolver, WebClient.builder().build());
    }

    /** Test seam: lets the relay be exercised against a local server. */
    LlmProxyController(LlmConfigResolver resolver, WebClient webClient) {
        this.resolver = resolver;
        this.webClient = webClient;
    }

    // ── Routes ────────────────────────────────────────────────────────────────

    @PostMapping("/chat/completions")
    public ResponseEntity<StreamingResponseBody> chatCompletions(@RequestBody byte[] body) {
        MediaType ct = isStreaming(body) ? MediaType.TEXT_EVENT_STREAM : MediaType.APPLICATION_JSON;
        return relay(HttpMethod.POST, "chat/completions", body, ct);
    }

    @PostMapping("/embeddings")
    public ResponseEntity<StreamingResponseBody> embeddings(@RequestBody byte[] body) {
        return relay(HttpMethod.POST, "embeddings", body, MediaType.APPLICATION_JSON);
    }

    @GetMapping("/models")
    public ResponseEntity<StreamingResponseBody> models() {
        return relay(HttpMethod.GET, "models", null, MediaType.APPLICATION_JSON);
    }

    // ── Relay ─────────────────────────────────────────────────────────────────

    /**
     * Forwards one request upstream and streams the reply back.
     *
     * <p>The response head is awaited <em>before</em> this method returns, so the upstream
     * status is known while our own status line is still ours to choose. That ordering is
     * the whole point: the previous implementation started a 200 OK, then wrote
     * {@code {"error":...}} into its body when the upstream call failed, and the CLI saw a
     * malformed success rather than a failure.
     */
    private ResponseEntity<StreamingResponseBody> relay(
            HttpMethod method, String path, byte[] body, MediaType successContentType) {

        LlmCredentials credentials = resolveCredentials();
        if (credentials == null) {
            log.warn("LLM proxy: no usable chat credentials resolved; refusing to forward {} {}",
                    method, path);
            return errorResponse(HttpStatus.SERVICE_UNAVAILABLE, NOT_CONFIGURED_MESSAGE);
        }

        String endpoint = credentials.getOrDefault("endpoint", "");
        String apiKey = credentials.getOrDefault("api-key", "");
        String uri = OpenAiEndpoints.normalizeBaseUrl(endpoint) + path;

        ResponseEntity<Flux<DataBuffer>> upstream;
        try {
            WebClient.RequestBodySpec spec = webClient.method(method)
                    .uri(uri)
                    .header(authHeaderName(endpoint), authHeaderValue(endpoint, apiKey));
            WebClient.RequestHeadersSpec<?> request = body == null
                    ? spec
                    : spec.contentType(MediaType.APPLICATION_JSON).bodyValue(body);

            upstream = request.retrieve()
                    // Never translate an upstream status into an exception: a 401 or 429
                    // from the provider is information the CLI needs verbatim, not a 502.
                    .onStatus(status -> true, response -> Mono.empty())
                    .toEntityFlux(DataBuffer.class)
                    .block(HEAD_TIMEOUT);
        } catch (Exception e) {
            // Connect/DNS/TLS/timeout — no upstream status exists, so this really is ours.
            log.warn("LLM proxy: upstream request to {} failed: {}",
                    credentials.signature(), e.toString());
            return errorResponse(HttpStatus.BAD_GATEWAY,
                    "LLM proxy could not reach the configured chat provider: " + describe(e));
        }

        if (upstream == null) {
            log.warn("LLM proxy: upstream {} produced no response head within {}",
                    credentials.signature(), HEAD_TIMEOUT);
            return errorResponse(HttpStatus.GATEWAY_TIMEOUT,
                    "LLM proxy timed out waiting for the configured chat provider.");
        }

        HttpStatusCode status = upstream.getStatusCode();
        Flux<DataBuffer> upstreamBody =
                upstream.getBody() == null ? Flux.empty() : upstream.getBody();
        // An error body is JSON whatever the successful content type would have been —
        // an SSE-typed 429 would confuse a client parsing the stream.
        MediaType contentType =
                status.is2xxSuccessful() ? successContentType : MediaType.APPLICATION_JSON;

        if (!status.is2xxSuccessful()) {
            log.warn("LLM proxy: upstream returned {} for {}", status.value(), path);
        }

        return ResponseEntity.status(status)
                .contentType(contentType)
                .body(out -> pump(upstreamBody, out));
    }

    /** Non-null only when both an endpoint and a key resolved. */
    private LlmCredentials resolveCredentials() {
        LlmCredentials credentials;
        try {
            credentials = resolver.resolveChat();
        } catch (RuntimeException e) {
            log.warn("LLM proxy: chat credential resolution failed: {}", e.toString());
            return null;
        }
        if (credentials == null
                || !credentials.has("endpoint")
                || !credentials.has("api-key")) {
            return null;
        }
        return credentials;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Azure authenticates with {@code api-key}; api.openai.com, vLLM, Ollama, LM Studio,
     * LiteLLM and every other OpenAI-compatible server expect {@code Authorization: Bearer}.
     * Sending both is not harmless — some gateways reject a request carrying two credentials.
     */
    static String authHeaderName(String endpoint) {
        return OpenAiEndpoints.isAzure(endpoint) ? "api-key" : "Authorization";
    }

    static String authHeaderValue(String endpoint, String apiKey) {
        return OpenAiEndpoints.isAzure(endpoint) ? apiKey : "Bearer " + apiKey;
    }

    static boolean isStreaming(byte[] body) {
        if (body == null) {
            return false;
        }
        String s = new String(body, StandardCharsets.UTF_8);
        return s.matches("(?s).*\"stream\"\\s*:\\s*true.*");
    }

    /**
     * Relays upstream buffers to the servlet output, flushing per chunk so SSE stays
     * responsive.
     *
     * <p>A failure here happens after the status line is committed, so it cannot be turned
     * into an error status. Rethrowing aborts the chunked response, which a client reads as
     * a truncated/failed transfer — the honest signal. Writing an error object into a
     * 200 body instead, as this used to, produces a response that parses as success.
     */
    private void pump(Flux<DataBuffer> upstream, OutputStream out) throws IOException {
        try {
            for (DataBuffer buf : upstream.toIterable()) {
                try {
                    byte[] bytes = new byte[buf.readableByteCount()];
                    buf.read(bytes);
                    out.write(bytes);
                    out.flush();
                } finally {
                    DataBufferUtils.release(buf);
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (RuntimeException e) {
            log.error("LLM proxy: stream failed after the response was committed; "
                    + "aborting the response rather than reporting success", e);
            throw new IOException("LLM proxy stream failed mid-body", e);
        }
    }

    /** OpenAI-shaped error envelope, so the CLI's own parser can read it. */
    static ResponseEntity<StreamingResponseBody> errorResponse(HttpStatus status, String message) {
        String json = "{\"error\":{\"message\":\"" + escape(message)
                + "\",\"type\":\"deepsql_proxy_error\",\"code\":" + status.value() + "}}";
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_JSON)
                .body(out -> {
                    out.write(bytes);
                    out.flush();
                });
    }

    /** Exception text without the credential the request carried. */
    private static String describe(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static String escape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", " ").replace("\r", " ");
    }
}
