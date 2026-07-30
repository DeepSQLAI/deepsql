package com.dbaagent.llm.openai;

import java.util.Locale;

/**
 * Endpoint-shape questions shared by the OpenAI-compatible chat and embedding providers.
 *
 * <p>This exists so there is exactly <em>one</em> definition of "is this Azure?". The chat
 * model, the embedding provider and the {@code /api/llm/v1} proxy all have to answer it to
 * pick an authentication scheme, and two subtly different tests would be its own bug: a host
 * that one classified as Azure and the other did not would authenticate correctly in one
 * role and 401 in the other, with nothing in the configuration to explain why.
 *
 * <p>Public rather than package-private precisely so callers outside this package have no
 * excuse to write their own copy — every copy that has existed in this codebase has been
 * subtly wrong in a different way.
 */
public final class OpenAiEndpoints {

    private OpenAiEndpoints() {
    }

    /**
     * Whether {@code url} addresses Azure OpenAI (directly or via API Management).
     *
     * <p>Deliberately a <em>host</em> test, not a path test. It must not be confused with
     * {@code ResponsesApiChatModel.isOpenAiCompatibleEndpoint}, which asks whether the URL
     * uses the {@code /v1}-style API shape — a different question with a different answer:
     * {@code https://x.openai.azure.com/openai/v1} is both v1-shaped <em>and</em> Azure, so
     * it takes the v1 request paths while still authenticating with {@code api-key}.
     *
     * <p>Azure authenticates with an {@code api-key} header; every other OpenAI-compatible
     * endpoint — api.openai.com, vLLM, Ollama, LM Studio, LiteLLM, TGI — expects
     * {@code Authorization: Bearer}. Dispatching on endpoint shape rather than on a
     * provider id is what lets one provider serve both without a provider-type switch.
     */
    public static boolean isAzure(String url) {
        if (url == null) {
            return false;
        }
        String u = url.toLowerCase(Locale.ROOT);
        // .azure.us and .azure.cn are Azure Government and Azure China. They speak the
        // same api-key protocol as .azure.com; without them a sovereign-cloud endpoint
        // took the Bearer branch and 401'd with no way for the operator to override.
        return u.contains(".azure.com") || u.contains(".azure.us") || u.contains(".azure.cn")
                || u.contains(".azure-api.net");
    }

    /**
     * The configured endpoint turned into a base URL that a {@code v1}-style path can be
     * appended to directly: always trailing-slash terminated, and carrying Azure's
     * {@code openai/v1/} prefix when the operator gave only the resource root.
     *
     * <p>Shared for the same reason {@link #isAzure} is: the embedding provider and the
     * {@code /api/llm/v1} proxy both need it, and a proxy that built
     * {@code https://x.openai.azure.com/chat/completions} while the provider built
     * {@code .../openai/v1/chat/completions} would 404 in one role only.
     */
    public static String normalizeBaseUrl(String configured) {
        String baseUrl = configured == null ? "" : configured.trim();
        if (!baseUrl.endsWith("/")) {
            baseUrl += "/";
        }
        if (isAzure(baseUrl) && !baseUrl.contains("/openai/")) {
            baseUrl += "openai/v1/";
        }
        return baseUrl;
    }
}
