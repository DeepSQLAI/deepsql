package com.dbaagent.llm.api;

import java.util.List;
import java.util.Set;

/**
 * Static identity and shape of a provider. Mirrors the role of
 * {@code DatabaseDialect#getCanonicalName()/getAliases()/getDisplayName()}.
 *
 * @param id                   canonical id, lowercase, e.g. "openai"
 * @param aliases              alternative ids resolving to this provider
 * @param displayName          label shown in the wizard
 * @param capabilities         what this provider supports
 * @param credentialFields     inputs the wizard must collect
 * @param defaultContextLength tokens; served to agents so they stop hardcoding it
 */
public record LlmProviderDescriptor(
        String id,
        Set<String> aliases,
        String displayName,
        Set<LlmCapability> capabilities,
        List<LlmCredentialField> credentialFields,
        int defaultContextLength
) {
    public LlmProviderDescriptor {
        aliases = Set.copyOf(aliases);
        capabilities = Set.copyOf(capabilities);
        credentialFields = List.copyOf(credentialFields);
    }

    public boolean supports(LlmCapability capability) {
        return capabilities.contains(capability);
    }
}
