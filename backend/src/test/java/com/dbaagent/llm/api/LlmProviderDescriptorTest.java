package com.dbaagent.llm.api;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.ArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LlmProviderDescriptorTest {

    private LlmProviderDescriptor descriptor(Set<String> aliases, Set<LlmCapability> capabilities,
                                              List<LlmCredentialField> fields) {
        return new LlmProviderDescriptor("openai", aliases, "OpenAI", capabilities, fields, 128_000);
    }

    @Test
    void supportsIsTrueForACapabilityInTheSet() {
        LlmProviderDescriptor descriptor = descriptor(Set.of("gpt"), Set.of(LlmCapability.CHAT), List.of());
        assertTrue(descriptor.supports(LlmCapability.CHAT));
    }

    @Test
    void supportsIsFalseForACapabilityNotInTheSet() {
        LlmProviderDescriptor descriptor = descriptor(Set.of("gpt"), Set.of(LlmCapability.CHAT), List.of());
        assertFalse(descriptor.supports(LlmCapability.EMBEDDING));
    }

    @Test
    void compactConstructorDefensivelyCopiesAliases() {
        Set<String> mutableAliases = new HashSet<>();
        mutableAliases.add("gpt");
        LlmProviderDescriptor descriptor = descriptor(mutableAliases, Set.of(LlmCapability.CHAT), List.of());

        mutableAliases.add("chatgpt");

        assertEquals(Set.of("gpt"), descriptor.aliases());
    }

    @Test
    void compactConstructorDefensivelyCopiesCapabilities() {
        Set<LlmCapability> mutableCapabilities = new HashSet<>();
        mutableCapabilities.add(LlmCapability.CHAT);
        LlmProviderDescriptor descriptor = descriptor(Set.of("gpt"), mutableCapabilities, List.of());

        mutableCapabilities.add(LlmCapability.EMBEDDING);

        assertEquals(Set.of(LlmCapability.CHAT), descriptor.capabilities());
        assertFalse(descriptor.supports(LlmCapability.EMBEDDING));
    }

    @Test
    void compactConstructorDefensivelyCopiesCredentialFields() {
        List<LlmCredentialField> mutableFields = new ArrayList<>();
        mutableFields.add(LlmCredentialField.secret("api-key", "API key"));
        LlmProviderDescriptor descriptor = descriptor(Set.of("gpt"), Set.of(LlmCapability.CHAT), mutableFields);

        mutableFields.add(LlmCredentialField.optional("region", "Region", "eastus2"));

        assertEquals(1, descriptor.credentialFields().size());
    }
}
