package com.dbaagent.controller;

import com.dbaagent.llm.LlmConfigResolver;
import com.dbaagent.llm.api.LlmCredentials;
import com.dbaagent.repository.CredentialRepository;
import com.dbaagent.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code GET /setup/status} is unauthenticated and drives the onboarding wizard, so what
 * it reports has to match what the application actually resolves. It used to derive
 * {@code hasLlmConfig} from {@code llm.openai.api-key} — a key nothing in the resolution
 * path reads — so every env-configured install reported itself unconfigured forever, and
 * the wizard's offer to fix it wrote keys that did nothing.
 */
class SetupControllerTest {

    private SystemConfigService config;
    private CredentialRepository credentials;
    private LlmConfigResolver resolver;
    private SetupController controller;

    /** What the controller wrote, so key names can be asserted rather than mocked away. */
    private final Map<String, String> written = new HashMap<>();

    @BeforeEach
    void setUp() {
        config = mock(SystemConfigService.class);
        credentials = mock(CredentialRepository.class);
        resolver = mock(LlmConfigResolver.class);
        controller = new SetupController(config, credentials, resolver);

        doAnswer(inv -> {
            written.put(inv.getArgument(0), inv.getArgument(1));
            return null;
        }).when(config).set(anyString(), anyString(), anyBoolean(), any());
        when(config.get(anyString())).thenReturn(Optional.empty());
        when(config.getOrDefault(anyString(), any()))
                .thenAnswer(inv -> written.getOrDefault(inv.getArgument(0), inv.getArgument(1)));
    }

    private static LlmCredentials someCredentials() {
        return new LlmCredentials("openai", Map.of(
                "api-key", "sk-x", "endpoint", "https://api.openai.com/v1", "model", "gpt-4o"));
    }

    @Test
    void statusReportsConfiguredWhenTheResolverCanResolveChatCredentials() {
        when(resolver.resolveChat()).thenReturn(someCredentials());

        assertThat(controller.getStatus().hasLlmConfig()).isTrue();
    }

    @Test
    void statusReportsUnconfiguredOnlyWhenTheResolverActuallyResolvesNothing() {
        when(resolver.resolveChat()).thenReturn(null);

        assertThat(controller.getStatus().hasLlmConfig()).isFalse();
    }

    /**
     * The wizard has to write the keys {@link LlmConfigResolver} reads —
     * {@code llm.<role>.provider} and {@code llm.<role>.<providerId>.<field>}. Anything
     * else stores credentials that resolution never sees and still answers
     * {@code {"success": true}}.
     */
    @Test
    void savingWritesTheProviderNamespacedKeysTheResolverReads() {
        controller.saveLlmConfig(new SetupController.LlmConfigRequest(
                "OpenAI", "sk-secret", "https://api.openai.com/v1",
                "gpt-4o", "text-embedding-3-large"));

        assertThat(written)
                .containsEntry("llm.chat.provider", "openai")
                .containsEntry("llm.chat.openai.api-key", "sk-secret")
                .containsEntry("llm.chat.openai.endpoint", "https://api.openai.com/v1")
                .containsEntry("llm.chat.openai.model", "gpt-4o")
                .containsEntry("llm.embedding.provider", "openai")
                .containsEntry("llm.embedding.openai.api-key", "sk-secret")
                .containsEntry("llm.embedding.openai.model", "text-embedding-3-large");

        // The dead pre-BYO namespace must not come back.
        assertThat(written).doesNotContainKeys(
                "llm.provider", "llm.openai.api-key", "llm.chat-model", "llm.embedding-model");
    }

    /**
     * The resolver lowercases the provider when composing key names, so a bundle stored
     * under "OpenAI" would be written to a namespace nothing ever looks in.
     */
    @Test
    void theProviderIdIsLowercasedExactlyAsTheResolverLowercasesIt() {
        controller.saveLlmConfig(new SetupController.LlmConfigRequest(
                "  AZURE  ", "k", "https://r.openai.azure.com/", "gpt-5.4", null));

        assertThat(written).containsEntry("llm.chat.provider", "azure")
                .containsKey("llm.chat.azure.api-key");
    }

    @Test
    void readingBackReturnsWhatWasWrittenAndMasksTheKey() {
        controller.saveLlmConfig(new SetupController.LlmConfigRequest(
                "openai", "sk-abcdefghijkl", "https://api.openai.com/v1",
                "gpt-4o", "text-embedding-3-large"));

        var response = controller.getLlmConfig();

        assertThat(response.provider()).isEqualTo("openai");
        assertThat(response.chatModel()).isEqualTo("gpt-4o");
        assertThat(response.embeddingModel()).isEqualTo("text-embedding-3-large");
        assertThat(response.configured()).isTrue();
        assertThat(response.apiKeyMasked()).doesNotContain("abcdefgh").contains("...");
    }

    @Test
    void aMissingApiKeyIsRejectedWithoutWritingAnything() {
        var response = controller.saveLlmConfig(new SetupController.LlmConfigRequest(
                "openai", "  ", "https://api.openai.com/v1", "gpt-4o", null));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(written).isEmpty();
    }
}
