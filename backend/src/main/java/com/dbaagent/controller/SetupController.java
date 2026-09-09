package com.dbaagent.controller;

import com.dbaagent.llm.LlmConfigResolver;
import com.dbaagent.llm.openai.OpenAiEndpoints;
import com.dbaagent.repository.CredentialRepository;
import com.dbaagent.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;

import java.util.Locale;
import java.util.Map;

/**
 * REST API for the first-run onboarding wizard.
 *
 * <p>The {@code GET /setup/status} endpoint is publicly accessible (no auth required)
 * so the frontend can detect first-run before login. All other endpoints require
 * an authenticated user.
 */
@RestController
@RequestMapping("/setup")
@RequiredArgsConstructor
@Slf4j
public class SetupController {

    /** Provider id used when the caller does not name one. */
    private static final String DEFAULT_PROVIDER = "openai";
    private static final String DEFAULT_ENDPOINT = "https://api.openai.com/v1";

    private final SystemConfigService systemConfigService;
    private final CredentialRepository credentialRepository;
    private final LlmConfigResolver llmConfigResolver;

    /**
     * Mirrors {@code security.google.enabled}. Surfaced on the public status
     * endpoint so the login page can decide whether to offer Google sign-in —
     * it is otherwise unauthenticated and has no way to know the server was
     * configured for SSO. Deliberately NOT final: {@code @RequiredArgsConstructor}
     * would pull a final field into the constructor and Spring has no bean to
     * satisfy it.
     */
    @Value("${security.google.enabled:false}")
    private boolean googleEnabled;

    /**
     * Mirrors {@code security.password.enabled}. Lets the login page hide the
     * email/password form on SSO-only installs instead of rendering a form that
     * always fails. Same non-final reasoning as above.
     */
    @Value("${security.password.enabled:true}")
    private boolean passwordLoginEnabled;

    // ── GET /setup/status ─────────────────────────────────────────────────────

    /** Returns setup completion state. Public endpoint — no auth required. */
    @GetMapping("/status")
    public SetupStatusResponse getStatus() {
        boolean hasOrgInfo     = systemConfigService.get("setup.org.name")
                                    .filter(v -> !v.isBlank()).isPresent();
        // Asked of the resolver, not of a config key. This used to read
        // llm.openai.api-key — a key nothing in the resolution path reads — so every
        // env-configured install (the only working path) reported itself unconfigured
        // forever and the wizard offered to "fix" it.
        boolean hasLlmConfig   = llmConfigResolver.resolveChat() != null;
        boolean hasConnections = credentialRepository.count() > 0;
        boolean setupComplete  = systemConfigService.getBoolean("setup.complete");

        return new SetupStatusResponse(
                setupComplete,
                hasOrgInfo,
                hasConnections,
                hasLlmConfig,
                googleEnabled,
                passwordLoginEnabled
        );
    }

    // ── POST /setup/initialize ────────────────────────────────────────────────

    /**
     * First-run initialization — public endpoint, no auth required.
     * Creates the first admin user, saves org name, marks setup complete,
     * and returns a JWT so the caller is immediately logged in.
     * Returns 409 if any user already exists (setup already done).
     */
    @PostMapping("/initialize")
    public ResponseEntity<Map<String, Object>> initialize(
            @RequestBody InitializeRequest request) {
        return ResponseEntity.status(410).body(Map.of(
            "error", "Direct setup initialization is disabled. Use the secure bootstrap link flow instead."
        ));
    }

    // ── POST /setup/organization ──────────────────────────────────────────────

    @PostMapping("/organization")
    public ResponseEntity<Map<String, Object>> saveOrganization(
            @RequestBody OrgInfoRequest request) {

        if (request.orgName() != null && !request.orgName().isBlank()) {
            systemConfigService.set("setup.org.name", request.orgName().trim(), false,
                    "Organization name");
        }
        if (request.teamSize() != null && !request.teamSize().isBlank()) {
            systemConfigService.set("setup.org.team-size", request.teamSize().trim(), false,
                    "Team size band");
        }
        log.info("Setup: organization info saved — org='{}'", request.orgName());
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ── GET /setup/llm-config ─────────────────────────────────────────────────

    @GetMapping("/llm-config")
    public LlmConfigResponse getLlmConfig() {
        String provider = providerId(systemConfigService.getOrDefault("llm.chat.provider", DEFAULT_PROVIDER));
        String apiKey   = systemConfigService.getOrDefault(chatKey(provider, "api-key"), "");
        String endpoint = systemConfigService.getOrDefault(chatKey(provider, "endpoint"), DEFAULT_ENDPOINT);
        String chatModel = systemConfigService.getOrDefault(chatKey(provider, "model"), "gpt-4o");

        String embProvider = providerId(
                systemConfigService.getOrDefault("llm.embedding.provider", provider));
        String embModel = systemConfigService.getOrDefault(
                embeddingKey(embProvider, "model"), "text-embedding-3-large");

        return new LlmConfigResponse(
                provider, maskKey(apiKey), endpoint, chatModel, embModel, !apiKey.isBlank());
    }

    // ── POST /setup/llm-config ────────────────────────────────────────────────

    /**
     * Writes the provider-namespaced keys {@link LlmConfigResolver} actually reads —
     * {@code llm.<role>.provider} and {@code llm.<role>.<providerId>.<field>}.
     *
     * <p>This used to write a flat {@code llm.provider} / {@code llm.openai.api-key}
     * namespace that intersected nothing in the resolution path, so the wizard stored keys
     * that did nothing and still answered {@code {"success": true}}. Pointing it at the
     * real keys was chosen over disabling the step: the wizard is the documented
     * first-run flow, and making the DB tier reachable is what the two-tier resolver was
     * built for.
     *
     * <p>Both roles are written from the one credential the wizard collects. An operator
     * who fills in this form expects RAG to work too, and a chat-only write would leave
     * embeddings resolving to nothing with no visible reason.
     */
    @PostMapping("/llm-config")
    public ResponseEntity<Map<String, Object>> saveLlmConfig(
            @RequestBody LlmConfigRequest request) {

        if (request.apiKey() == null || request.apiKey().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "API key is required"));
        }

        String provider = providerId(provider(request));
        String apiKey = request.apiKey().trim();
        String endpoint = request.endpoint() != null && !request.endpoint().isBlank()
                ? request.endpoint().trim()
                : DEFAULT_ENDPOINT;

        systemConfigService.set("llm.chat.provider", provider, false, "Chat LLM provider");
        systemConfigService.set(chatKey(provider, "api-key"), apiKey, true,
                "Chat LLM API key (encrypted)");
        systemConfigService.set(chatKey(provider, "endpoint"), endpoint, false,
                "Chat LLM endpoint");

        systemConfigService.set("llm.embedding.provider", provider, false,
                "Embedding LLM provider");
        systemConfigService.set(embeddingKey(provider, "api-key"), apiKey, true,
                "Embedding LLM API key (encrypted)");
        systemConfigService.set(embeddingKey(provider, "endpoint"), endpoint, false,
                "Embedding LLM endpoint");

        if (request.chatModel() != null && !request.chatModel().isBlank()) {
            systemConfigService.set(chatKey(provider, "model"), request.chatModel().trim(),
                    false, "Chat model");
        }
        if (request.embeddingModel() != null && !request.embeddingModel().isBlank()) {
            systemConfigService.set(embeddingKey(provider, "model"),
                    request.embeddingModel().trim(), false, "Embedding model");
        }

        log.info("Setup: LLM config saved — provider={} model={}", provider, request.chatModel());
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ── POST /setup/llm-config/test ───────────────────────────────────────────

    /** Test an API key by calling the provider's models endpoint. */
    @PostMapping("/llm-config/test")
    public ResponseEntity<Map<String, Object>> testLlmConfig(
            @RequestBody LlmTestRequest request) {

        if (request.apiKey() == null || request.apiKey().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("valid", false, "error", "API key required"));
        }

        try {
            // The canonical predicate, not a third local copy. The copy this replaces
            // matched only *.cognitiveservices.azure.com and so missed the canonical
            // *.openai.azure.com shape entirely — a correctly configured Azure endpoint
            // was tested with a Bearer token and reported as an invalid key.
            boolean isAzure = OpenAiEndpoints.isAzure(request.endpoint())
                    || "azure".equalsIgnoreCase(request.provider());

            RestClient client;
            String testUri;

            if (isAzure) {
                // Azure OpenAI: uses api-key header and its own deployments endpoint
                String baseUrl = request.endpoint() != null && !request.endpoint().isBlank()
                        ? request.endpoint().trim().replaceAll("/+$", "")
                        : "";
                if (baseUrl.isBlank()) {
                    return ResponseEntity.badRequest()
                            .body(Map.of("valid", false, "error", "Azure endpoint URL is required"));
                }
                client = RestClient.builder()
                        .baseUrl(baseUrl)
                        .defaultHeader("api-key", request.apiKey())
                        .build();
                // List models — lightweight Azure-specific ping (works on AI Foundry endpoints)
                testUri = "/openai/models?api-version=2024-10-21";
            } else {
                // Standard OpenAI (and compatible): Bearer token, /v1/models
                String baseUrl = request.endpoint() != null && !request.endpoint().isBlank()
                        ? request.endpoint().trim().replaceAll("/+$", "")
                        : "https://api.openai.com/v1";
                client = RestClient.builder()
                        .baseUrl(baseUrl)
                        .defaultHeader("Authorization", "Bearer " + request.apiKey())
                        .build();
                testUri = "/models";
            }

            client.get().uri(testUri).retrieve().toBodilessEntity();
            return ResponseEntity.ok(Map.of("valid", true));
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            String msg = e.getMessage();
            boolean unauthorized = msg != null && (msg.contains("401") || msg.contains("403")
                    || msg.contains("Unauthorized") || msg.contains("invalid_api_key")
                    || msg.contains("AuthenticationFailed") || msg.contains("Access denied"));
            log.warn("Setup: LLM key test failed — {}", msg);
            return ResponseEntity.ok(Map.of(
                    "valid", false,
                    "error", unauthorized ? "Invalid API key" : "Could not reach AI provider: " + msg));
        }
    }

    // ── POST /setup/complete ──────────────────────────────────────────────────

    @PostMapping("/complete")
    public ResponseEntity<Map<String, Object>> markComplete() {
        systemConfigService.set("setup.complete", "true", false, "Setup wizard completion flag");
        log.info("Setup: onboarding marked complete");
        return ResponseEntity.ok(Map.of("success", true));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static String maskKey(String key) {
        if (key == null || key.length() < 8) return key == null ? "" : "****";
        return key.substring(0, 4) + "..." + key.substring(key.length() - 4);
    }

    private static String provider(LlmConfigRequest req) {
        return (req.provider() != null && !req.provider().isBlank())
                ? req.provider() : DEFAULT_PROVIDER;
    }

    /**
     * Lowercased exactly as {@link LlmConfigResolver} lowercases it when composing key
     * names. A provider written "OpenAI" here and looked up as "openai" there would store
     * a bundle the resolver can never find.
     */
    private static String providerId(String provider) {
        return (provider == null || provider.isBlank())
                ? DEFAULT_PROVIDER
                : provider.trim().toLowerCase(Locale.ROOT);
    }

    private static String chatKey(String providerId, String field) {
        return "llm.chat." + providerId + "." + field;
    }

    private static String embeddingKey(String providerId, String field) {
        return "llm.embedding." + providerId + "." + field;
    }

    // ── DTOs ──────────────────────────────────────────────────────────────────

    public record SetupStatusResponse(
            boolean setupComplete,
            boolean hasOrganizationInfo,
            boolean hasConnections,
            boolean hasLlmConfig,
            /** Whether Google Workspace SSO is configured; drives the login page's SSO button. */
            boolean googleEnabled,
            /** Whether email+password sign-in is accepted; false hides the password form. */
            boolean passwordLoginEnabled
    ) {}

    public record InitializeRequest(String orgName, String adminUsername, String adminEmail, String adminPassword) {}

    public record OrgInfoRequest(String orgName, String teamSize) {}

    public record LlmConfigRequest(
            String provider,
            String apiKey,
            String endpoint,
            String chatModel,
            String embeddingModel
    ) {}

    public record LlmConfigResponse(
            String provider,
            String apiKeyMasked,
            String endpoint,
            String chatModel,
            String embeddingModel,
            boolean configured
    ) {}

    public record LlmTestRequest(String provider, String apiKey, String endpoint) {}
}
