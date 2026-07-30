package com.dbaagent.llm;

import com.dbaagent.llm.api.*;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LlmProviderRegistryTest {

    /** Minimal stub — behaviour is irrelevant; identity is what the registry indexes. */
    private static LlmChatProvider chat(String id, Set<String> aliases,
                                        LlmCapability... caps) {
        LlmProviderDescriptor d = new LlmProviderDescriptor(
                id, aliases, id, Set.of(caps), List.of(), 128_000);
        return new LlmChatProvider() {
            @Override public LlmProviderDescriptor descriptor() { return d; }
            @Override public ChatModel delegate(LlmCredentials c) { return null; }
            @Override public LlmErrorCategory classify(Throwable t) {
                return LlmErrorCategory.UNKNOWN;
            }
        };
    }

    private static LlmEmbeddingProvider embedding(String id) {
        LlmProviderDescriptor d = new LlmProviderDescriptor(
                id, Set.of(), id, Set.of(LlmCapability.EMBEDDING), List.of(), 8192);
        return new LlmEmbeddingProvider() {
            @Override public LlmProviderDescriptor descriptor() { return d; }
            @Override public List<Double> embed(String t, LlmCredentials c) { return List.of(); }
            @Override public List<List<Double>> embedBatch(List<String> t, LlmCredentials c) {
                return List.of();
            }
            @Override public int dimensions(LlmCredentials c) { return 0; }
            @Override public LlmErrorCategory classify(Throwable t) {
                return LlmErrorCategory.UNKNOWN;
            }
        };
    }

    @Test
    void resolvesByCanonicalId() {
        var registry = new LlmProviderRegistry(
                List.of(chat("openai", Set.of(), LlmCapability.CHAT)), List.of());

        assertThat(registry.chatProvider("openai").descriptor().id()).isEqualTo("openai");
    }

    @Test
    void resolvesByAliasAndIsCaseInsensitive() {
        var registry = new LlmProviderRegistry(
                List.of(chat("azure", Set.of("azure-openai"), LlmCapability.CHAT)), List.of());

        assertThat(registry.chatProvider("AZURE-OpenAI").descriptor().id()).isEqualTo("azure");
    }

    @Test
    void unknownProviderThrowsAndNamesWhatIsSupported() {
        var registry = new LlmProviderRegistry(
                List.of(chat("openai", Set.of(), LlmCapability.CHAT)), List.of());

        assertThatThrownBy(() -> registry.chatProvider("gemini"))
                .isInstanceOf(UnsupportedLlmProviderException.class)
                .hasMessageContaining("gemini")
                .hasMessageContaining("openai");
    }

    @Test
    void duplicateCanonicalIdIsRejectedAtStartup() {
        assertThatThrownBy(() -> new LlmProviderRegistry(
                List.of(chat("openai", Set.of(), LlmCapability.CHAT),
                        chat("openai", Set.of(), LlmCapability.CHAT)),
                List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("openai");
    }

    @Test
    void aliasCollidingWithAnotherCanonicalIdIsRejected() {
        assertThatThrownBy(() -> new LlmProviderRegistry(
                List.of(chat("openai", Set.of(), LlmCapability.CHAT),
                        chat("azure", Set.of("openai"), LlmCapability.CHAT)),
                List.of()))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aliasCollidingWithAnotherAliasIsRejected() {
        assertThatThrownBy(() -> new LlmProviderRegistry(
                List.of(chat("openai", Set.of("gpt"), LlmCapability.CHAT),
                        chat("azure", Set.of("gpt"), LlmCapability.CHAT)),
                List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("gpt");
    }

    @Test
    void duplicateEmbeddingCanonicalIdIsRejectedAtStartup() {
        assertThatThrownBy(() -> new LlmProviderRegistry(
                List.of(),
                List.of(embedding("openai"), embedding("openai"))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("openai");
    }

    @Test
    void chatAndEmbeddingRegistriesAreIndependent() {
        var registry = new LlmProviderRegistry(
                List.of(chat("anthropic", Set.of(), LlmCapability.CHAT)),
                List.of(embedding("openai")));

        assertThat(registry.supportedChatIds()).containsExactly("anthropic");
        assertThat(registry.supportedEmbeddingIds()).containsExactly("openai");
        // Anthropic publishes no embeddings API — it must not resolve as one.
        assertThatThrownBy(() -> registry.embeddingProvider("anthropic"))
                .isInstanceOf(UnsupportedLlmProviderException.class);
    }

    @Test
    void descriptorsAreExposedForWizardRendering() {
        var registry = new LlmProviderRegistry(
                List.of(chat("openai", Set.of(), LlmCapability.CHAT, LlmCapability.STREAMING)),
                List.of(embedding("openai")));

        assertThat(registry.chatDescriptors()).hasSize(1);
        assertThat(registry.chatDescriptors().getFirst().capabilities())
                .containsExactlyInAnyOrder(LlmCapability.CHAT, LlmCapability.STREAMING);
    }
}
