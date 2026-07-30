package com.dbaagent.llm;

import com.dbaagent.llm.api.LlmCredentials;
import com.dbaagent.service.SystemConfigService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LlmConfigResolverTest {

    private SystemConfigService config;
    private Map<String, String> db;
    private Map<String, String> env;

    @BeforeEach
    void setUp() {
        db = new HashMap<>();
        env = new HashMap<>();
        config = mock(SystemConfigService.class);
        when(config.getOrDefault(anyString(), any()))
                .thenAnswer(i -> db.getOrDefault(i.getArgument(0), i.getArgument(1)));
    }

    private LlmConfigResolver resolver() {
        return new LlmConfigResolver(config, env::get);
    }

    @Test
    void returnsNullWhenNothingIsConfigured() {
        assertThat(resolver().resolveChat()).isNull();
    }

    @Test
    void readsProviderNamespacedKeysFromTheDatabase() {
        db.put("llm.chat.provider", "openai");
        db.put("llm.chat.openai.api-key", "sk-test");
        db.put("llm.chat.openai.endpoint", "https://api.openai.com/v1");
        db.put("llm.chat.openai.model", "gpt-4o");

        LlmCredentials c = resolver().resolveChat();

        assertThat(c.providerId()).isEqualTo("openai");
        assertThat(c.get("api-key")).isEqualTo("sk-test");
        assertThat(c.get("model")).isEqualTo("gpt-4o");
    }

    @Test
    void databaseWinsOverEnvironment() {
        env.put("DEEPSQL_CHAT_PROVIDER", "openai");
        env.put("DEEPSQL_CHAT_API_KEY", "from-env");
        db.put("llm.chat.provider", "openai");
        db.put("llm.chat.openai.api-key", "from-db");
        db.put("llm.chat.openai.model", "gpt-4o");

        assertThat(resolver().resolveChat().get("api-key")).isEqualTo("from-db");
    }

    @Test
    void fallsBackToEnvironmentForHeadlessInstalls() {
        env.put("DEEPSQL_CHAT_PROVIDER", "openai");
        env.put("DEEPSQL_CHAT_API_KEY", "sk-env");
        env.put("DEEPSQL_CHAT_ENDPOINT", "https://api.openai.com/v1");
        env.put("DEEPSQL_CHAT_MODEL", "gpt-4o");

        LlmCredentials c = resolver().resolveChat();

        assertThat(c.providerId()).isEqualTo("openai");
        assertThat(c.get("api-key")).isEqualTo("sk-env");
    }

    @Test
    void chatAndEmbeddingResolveIndependently() {
        db.put("llm.chat.provider", "anthropic");
        db.put("llm.chat.anthropic.api-key", "sk-ant");
        db.put("llm.chat.anthropic.model", "claude-x");
        db.put("llm.embedding.provider", "openai");
        db.put("llm.embedding.openai.api-key", "sk-oai");
        db.put("llm.embedding.openai.model", "text-embedding-3-large");

        assertThat(resolver().resolveChat().providerId()).isEqualTo("anthropic");
        assertThat(resolver().resolveEmbedding().providerId()).isEqualTo("openai");
    }

    @Test
    void switchingProviderDoesNotDestroyTheOtherProvidersStoredSettings() {
        db.put("llm.chat.provider", "openai");
        db.put("llm.chat.openai.api-key", "sk-oai");
        db.put("llm.chat.openai.model", "gpt-4o");
        db.put("llm.chat.bedrock.region", "us-east-1");   // retained while inactive

        assertThat(resolver().resolveChat().providerId()).isEqualTo("openai");

        db.put("llm.chat.provider", "bedrock");
        assertThat(resolver().resolveChat().get("region")).isEqualTo("us-east-1");
    }

    @Test
    void legacyAzureEnvironmentVariablesAreIgnored() {
        // D5: no backward compatibility. A stale AZURE_OPENAI_KEY must not silently
        // override or resurrect configuration.
        env.put("AZURE_OPENAI_KEY", "stale");
        env.put("AZURE_OPENAI_ENDPOINT", "https://old.invalid/");

        assertThat(resolver().resolveChat()).isNull();
    }

    @Test
    void markingConfigInvalidSwitchesToEnvironmentWhenItIsUsableAndDifferent() {
        // Endpoint/model/region are identical between DB and env on purpose — this must
        // still switch on the secret difference alone. Varying the endpoint here would
        // mask a bug where invalidation keys off signature() (which excludes secrets).
        db.put("llm.chat.provider", "openai");
        db.put("llm.chat.openai.api-key", "bad");
        db.put("llm.chat.openai.model", "gpt-4o");
        db.put("llm.chat.openai.endpoint", "https://api.openai.com/v1");
        env.put("DEEPSQL_CHAT_PROVIDER", "openai");
        env.put("DEEPSQL_CHAT_API_KEY", "good");
        env.put("DEEPSQL_CHAT_MODEL", "gpt-4o");
        env.put("DEEPSQL_CHAT_ENDPOINT", "https://api.openai.com/v1");

        LlmConfigResolver r = resolver();
        LlmCredentials broken = r.resolveChat();

        assertThat(r.markChatConfigInvalid(broken)).isTrue();
        assertThat(r.resolveChat().get("api-key")).isEqualTo("good");
    }

    @Test
    void markingConfigInvalidIsANoOpWhenThereIsNoEnvironmentBundle() {
        db.put("llm.chat.provider", "openai");
        db.put("llm.chat.openai.api-key", "bad");
        db.put("llm.chat.openai.model", "gpt-4o");

        LlmConfigResolver r = resolver();
        assertThat(r.markChatConfigInvalid(r.resolveChat())).isFalse();
    }

    @Test
    void markingConfigInvalidReturnsTrueWhenBundlesDifferOnlyBySecret() {
        // Regression: signature() deliberately excludes api-key, so keying invalidation
        // off signature() would report "no usable fallback" here even though the env
        // bundle differs from the DB bundle in exactly the field that matters — the key.
        db.put("llm.chat.provider", "openai");
        db.put("llm.chat.openai.api-key", "bad");
        db.put("llm.chat.openai.model", "gpt-4o");
        db.put("llm.chat.openai.endpoint", "https://api.openai.com/v1");
        env.put("DEEPSQL_CHAT_PROVIDER", "openai");
        env.put("DEEPSQL_CHAT_API_KEY", "good");
        env.put("DEEPSQL_CHAT_MODEL", "gpt-4o");
        env.put("DEEPSQL_CHAT_ENDPOINT", "https://api.openai.com/v1");

        LlmConfigResolver r = resolver();
        LlmCredentials broken = r.resolveChat();

        assertThat(r.markChatConfigInvalid(broken)).isTrue();
    }

    @Test
    void markingInvalidThenCorrectingTheDatabaseKeyResolvesToTheCorrectedBundleNotEnv() {
        // Regression: once a DB bundle is marked invalid, correcting the key in the DB
        // itself must stop the skip. Keying invalidation off signature() (endpoint/model/
        // region only) would keep matching the corrected bundle forever, since only the
        // secret changed — permanently stuck on the env fallback until process restart.
        db.put("llm.chat.provider", "openai");
        db.put("llm.chat.openai.api-key", "bad");
        db.put("llm.chat.openai.model", "gpt-4o");
        db.put("llm.chat.openai.endpoint", "https://api.openai.com/v1");
        env.put("DEEPSQL_CHAT_PROVIDER", "openai");
        env.put("DEEPSQL_CHAT_API_KEY", "env-fallback");
        env.put("DEEPSQL_CHAT_MODEL", "gpt-4o");
        env.put("DEEPSQL_CHAT_ENDPOINT", "https://api.openai.com/v1");

        LlmConfigResolver r = resolver();
        LlmCredentials broken = r.resolveChat();
        assertThat(r.markChatConfigInvalid(broken)).isTrue();
        assertThat(r.resolveChat().get("api-key")).isEqualTo("env-fallback");

        db.put("llm.chat.openai.api-key", "fixed-key");

        assertThat(r.resolveChat().get("api-key")).isEqualTo("fixed-key");
    }
}
