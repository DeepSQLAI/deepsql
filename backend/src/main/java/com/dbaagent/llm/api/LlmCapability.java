package com.dbaagent.llm.api;

/** What a provider can do. Drives wizard filtering — see LlmProviderDescriptor. */
public enum LlmCapability {
    CHAT,
    STREAMING,
    EMBEDDING
}
