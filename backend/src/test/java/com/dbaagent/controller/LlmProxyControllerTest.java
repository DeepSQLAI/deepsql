package com.dbaagent.controller;

import com.dbaagent.llm.LlmConfigResolver;
import com.dbaagent.llm.api.LlmCredentials;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The proxy is a live, JWT-authenticated OpenAI-compatible gateway used by the
 * {@code deepsql} CLI agent, and until now it had no test at all. It carried the exact
 * defect Task 10 fixed in {@code ResponsesApiChatModel}: a hardcoded {@code api-key}
 * header, so an OpenAI-key self-hoster got a 401 on every request.
 */
class LlmProxyControllerTest {

    private HttpServer server;
    private final List<Map<String, String>> receivedHeaders = new CopyOnWriteArrayList<>();
    private final List<String> receivedPaths = new CopyOnWriteArrayList<>();

    private int status = 200;
    private String responseBody = "{\"ok\":true}";

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop(0);
    }

    private void handle(HttpExchange exchange) throws IOException {
        receivedPaths.add(exchange.getRequestURI().getPath());
        receivedHeaders.add(exchange.getRequestHeaders().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        e -> e.getKey().toLowerCase(java.util.Locale.ROOT),
                        e -> String.join(",", e.getValue()))));
        byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        try (var out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private String localEndpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    private LlmProxyController controllerFor(LlmCredentials credentials) {
        LlmConfigResolver resolver = mock(LlmConfigResolver.class);
        when(resolver.resolveChat()).thenReturn(credentials);
        return new LlmProxyController(resolver, WebClient.builder().build());
    }

    private static LlmCredentials creds(String endpoint, String apiKey) {
        return new LlmCredentials("openai", Map.of("endpoint", endpoint, "api-key", apiKey));
    }

    private static String drain(ResponseEntity<StreamingResponseBody> response) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        response.getBody().writeTo(out);
        return out.toString(StandardCharsets.UTF_8);
    }

    // ── Auth-header selection: the defect this class shipped ───────────────────

    @Test
    void nonAzureEndpointsGetABearerTokenAndAzureGetsApiKey() {
        assertThat(LlmProxyController.authHeaderName("https://api.openai.com/v1"))
                .isEqualTo("Authorization");
        assertThat(LlmProxyController.authHeaderValue("https://api.openai.com/v1", "sk-x"))
                .isEqualTo("Bearer sk-x");

        assertThat(LlmProxyController.authHeaderName("https://r.openai.azure.com/"))
                .isEqualTo("api-key");
        assertThat(LlmProxyController.authHeaderValue("https://r.openai.azure.com/", "k"))
                .isEqualTo("k");
        assertThat(LlmProxyController.authHeaderName("https://r.cognitiveservices.azure.com/"))
                .isEqualTo("api-key");
    }

    /**
     * The header choice has to survive a real request, not just the static helper — the
     * previous implementation's helper was fine in isolation and the header was hardcoded
     * at the call site.
     */
    @Test
    void aRealForwardedRequestCarriesBearerForAnOpenAiCompatibleEndpoint() throws Exception {
        var controller = controllerFor(creds(localEndpoint(), "sk-secret"));

        var response = controller.chatCompletions("{\"model\":\"gpt-4o\"}".getBytes(StandardCharsets.UTF_8));
        drain(response);

        assertThat(receivedHeaders).hasSize(1);
        assertThat(receivedHeaders.getFirst()).containsEntry("authorization", "Bearer sk-secret");
        assertThat(receivedHeaders.getFirst()).doesNotContainKey("api-key");
        assertThat(receivedPaths).containsExactly("/v1/chat/completions");
    }

    @Test
    void theAzureOpenAiV1PrefixIsOnlyAppliedToAzureEndpoints() {
        // Regression guard for the hardcoded "openai/v1/" the old proxy appended to
        // everything, which turned api.openai.com/v1 into a 404.
        var controller = controllerFor(creds(localEndpoint(), "sk-secret"));
        controller.models();
        assertThat(receivedPaths).containsExactly("/v1/models");
    }

    // ── Failures must be failures ─────────────────────────────────────────────

    @Test
    void anUpstreamErrorStatusIsPassedThroughRatherThanReportedAs200() throws Exception {
        status = 429;
        responseBody = "{\"error\":{\"message\":\"rate limited\"}}";
        var controller = controllerFor(creds(localEndpoint(), "sk-secret"));

        var response = controller.chatCompletions("{}".getBytes(StandardCharsets.UTF_8));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.APPLICATION_JSON);
        assertThat(drain(response)).contains("rate limited");
    }

    @Test
    void anUnreachableUpstreamBecomesA502NotA200WithAnErrorBody() throws Exception {
        // Port 1 on loopback refuses connections immediately.
        var controller = controllerFor(creds("http://127.0.0.1:1/v1", "sk-secret"));

        var response = controller.chatCompletions("{}".getBytes(StandardCharsets.UTF_8));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(drain(response)).contains("could not reach the configured chat provider");
    }

    /**
     * A relative URI is exactly what an empty endpoint produced once the credential
     * defaults left the properties files, and {@code WebClient} throws on it. That must
     * not reach the wire at all.
     */
    @Test
    void unresolvableCredentialsReturn503NamingTheChatEnvironmentVariables() throws Exception {
        var controller = controllerFor(null);

        var response = controller.chatCompletions("{}".getBytes(StandardCharsets.UTF_8));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        String body = drain(response);
        assertThat(body).contains("DEEPSQL_CHAT_PROVIDER")
                .contains("DEEPSQL_CHAT_ENDPOINT")
                .contains("DEEPSQL_CHAT_API_KEY");
        assertThat(receivedPaths).isEmpty();
    }

    @Test
    void credentialsMissingTheKeyAreTreatedAsUnconfigured() throws Exception {
        var controller = controllerFor(
                new LlmCredentials("openai", Map.of("endpoint", localEndpoint())));

        var response = controller.embeddings("{}".getBytes(StandardCharsets.UTF_8));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(drain(response)).contains("DEEPSQL_CHAT_API_KEY");
        assertThat(receivedPaths).isEmpty();
    }

    @Test
    void aResolverFailureIsReportedAsUnconfiguredRatherThanSwallowedIntoA200() throws Exception {
        LlmConfigResolver resolver = mock(LlmConfigResolver.class);
        when(resolver.resolveChat()).thenThrow(new IllegalStateException("vault unavailable"));
        var controller = new LlmProxyController(resolver, WebClient.builder().build());

        var response = controller.models();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(drain(response)).doesNotContain("vault unavailable");
    }

    /**
     * Once the status line is committed only an aborted transfer is honest. The old
     * {@code pump()} caught everything and wrote {@code {"error":...}} into a 200 body,
     * so the CLI parsed a malformed success.
     */
    @Test
    void aMidStreamFailureAbortsTheResponseInsteadOfWritingAnErrorIntoASuccessBody() {
        var controller = controllerFor(creds(localEndpoint(), "sk-secret"));
        var response = controller.chatCompletions("{}".getBytes(StandardCharsets.UTF_8));

        assertThatThrownBy(() -> response.getBody().writeTo(new ByteArrayOutputStream() {
            @Override
            public synchronized void write(byte[] b, int off, int len) {
                throw new IllegalStateException("client went away");
            }
        })).isInstanceOf(IOException.class);
    }

    // ── Happy path ────────────────────────────────────────────────────────────

    @Test
    void aSuccessfulResponseIsRelayedVerbatim() throws Exception {
        responseBody = "{\"choices\":[{\"message\":{\"content\":\"hi\"}}]}";
        var controller = controllerFor(creds(localEndpoint(), "sk-secret"));

        var response = controller.chatCompletions("{}".getBytes(StandardCharsets.UTF_8));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(drain(response)).isEqualTo(responseBody);
    }

    @Test
    void aStreamingRequestIsAnnouncedAsServerSentEvents() {
        var controller = controllerFor(creds(localEndpoint(), "sk-secret"));

        var response = controller.chatCompletions(
                "{\"model\":\"gpt-4o\",\"stream\": true}".getBytes(StandardCharsets.UTF_8));

        assertThat(response.getHeaders().getContentType()).isEqualTo(MediaType.TEXT_EVENT_STREAM);
        assertThat(LlmProxyController.isStreaming(null)).isFalse();
    }
}
