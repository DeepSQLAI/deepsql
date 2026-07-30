package com.dbaagent.llm;

import com.dbaagent.llm.api.*;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Registry for LLM providers. Mirrors {@code DatabaseProviderRegistry}: Spring
 * auto-discovers every implementation, ids and aliases are indexed case-insensitively,
 * and collisions fail fast at startup.
 *
 * <p>Chat and embedding are indexed separately and deliberately. Anthropic publishes no
 * embeddings API, so a single index would have to special-case it — the exact
 * if/else-on-provider-type that {@code CLAUDE.md} forbids.
 */
@Component
@Slf4j
public class LlmProviderRegistry {

    private final Map<String, LlmChatProvider> chat = new ConcurrentHashMap<>();
    private final Map<String, LlmEmbeddingProvider> embedding = new ConcurrentHashMap<>();
    private final Set<String> chatCanonical = new LinkedHashSet<>();
    private final Set<String> embeddingCanonical = new LinkedHashSet<>();

    public LlmProviderRegistry(List<LlmChatProvider> chatProviders,
                               List<LlmEmbeddingProvider> embeddingProviders) {
        for (LlmChatProvider p : chatProviders) {
            index(p.descriptor(), p, chat, chatCanonical, "chat");
        }
        for (LlmEmbeddingProvider p : embeddingProviders) {
            index(p.descriptor(), p, embedding, embeddingCanonical, "embedding");
        }
        log.info("Registered {} LLM chat providers {} and {} embedding providers {}",
                chatCanonical.size(), chatCanonical,
                embeddingCanonical.size(), embeddingCanonical);
    }

    private <T> void index(LlmProviderDescriptor d, T provider, Map<String, T> target,
                           Set<String> canonicalIds, String kind) {
        String canonical = normalize(d.id());
        put(target, canonical, provider, kind);
        canonicalIds.add(canonical);
        for (String alias : d.aliases()) {
            String normalized = normalize(alias);
            if (!normalized.equals(canonical)) {
                put(target, normalized, provider, kind);
            }
        }
    }

    private <T> void put(Map<String, T> target, String key, T provider, String kind) {
        T existing = target.putIfAbsent(key, provider);
        if (existing != null && existing != provider) {
            throw new IllegalStateException(
                "Duplicate " + kind + " LLM provider id or alias '" + key
                + "'. Each id may be claimed by exactly one provider.");
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    public LlmChatProvider chatProvider(String id) {
        return resolve(chat, id, chatCanonical);
    }

    public LlmEmbeddingProvider embeddingProvider(String id) {
        return resolve(embedding, id, embeddingCanonical);
    }

    private <T> T resolve(Map<String, T> source, String id, Set<String> canonicalIds) {
        T provider = source.get(normalize(id));
        if (provider == null) {
            throw new UnsupportedLlmProviderException(id, Set.copyOf(canonicalIds));
        }
        return provider;
    }

    public Set<String> supportedChatIds() {
        return Set.copyOf(chatCanonical);
    }

    public Set<String> supportedEmbeddingIds() {
        return Set.copyOf(embeddingCanonical);
    }

    public List<LlmProviderDescriptor> chatDescriptors() {
        return descriptors(chatCanonical, id -> chat.get(id).descriptor());
    }

    public List<LlmProviderDescriptor> embeddingDescriptors() {
        return descriptors(embeddingCanonical, id -> embedding.get(id).descriptor());
    }

    private List<LlmProviderDescriptor> descriptors(
            Set<String> ids, Function<String, LlmProviderDescriptor> lookup) {
        return ids.stream().map(lookup).toList();
    }
}
