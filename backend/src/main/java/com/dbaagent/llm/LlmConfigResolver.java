package com.dbaagent.llm;

import com.dbaagent.llm.api.LlmCredentials;
import com.dbaagent.service.SystemConfigService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.UnaryOperator;

/**
 * Resolves which provider is active and its credentials.
 *
 * <p>Two tiers only: database (encrypted, set by the wizard) then environment. There is
 * deliberately no property-default tier — a credential default in a properties file is
 * how the production Azure key reached git history.
 *
 * <p>Settings are namespaced per provider ({@code llm.<role>.<provider>.<field>}) because
 * model ids are not portable between providers, and so that evaluating a provider and
 * reverting does not discard the original credentials.
 */
@Component
@Slf4j
public class LlmConfigResolver {

    /** Fields collected for every provider. Unset ones are simply absent. */
    private static final List<String> FIELDS =
            List.of("api-key", "endpoint", "model", "region", "access-key-id",
                    "secret-access-key", "api-version", "use-responses-api", "temperature");

    private final SystemConfigService config;
    private final UnaryOperator<String> env;

    /**
     * Set when the DB bundle has failed; resolution then prefers the env bundle.
     *
     * <p>Compared with {@link LlmCredentials#equals}, which is secret-inclusive (unlike
     * {@link LlmCredentials#signature()}). Correcting just the secret in the DB — the
     * common rotation case — must produce a different value here so resolution stops
     * skipping the corrected bundle without requiring a restart.
     */
    private volatile LlmCredentials invalidChatCredentials;

    /**
     * Marked explicitly because the package-private test seam below is a second candidate
     * constructor; without this Spring finds no unambiguous one and falls back to looking
     * for a no-arg constructor, which does not exist.
     */
    @Autowired
    public LlmConfigResolver(SystemConfigService config) {
        this(config, System::getenv);
    }

    LlmConfigResolver(SystemConfigService config, UnaryOperator<String> env) {
        this.config = config;
        this.env = env;
    }

    public LlmCredentials resolveChat() {
        LlmCredentials db = fromDatabase("chat");
        if (db != null && !db.equals(invalidChatCredentials)) {
            return db;
        }
        LlmCredentials fromEnv = fromEnvironment("CHAT");
        if (fromEnv != null) {
            return fromEnv;
        }
        return db;   // env unusable — return the DB bundle so the real error surfaces
    }

    public LlmCredentials resolveEmbedding() {
        LlmCredentials db = fromDatabase("embedding");
        return db != null ? db : fromEnvironment("EMBEDDING");
    }

    /**
     * Records that {@code failed} does not work. Returns true only when a different,
     * usable environment bundle exists to fall back to.
     */
    public boolean markChatConfigInvalid(LlmCredentials failed) {
        if (failed == null) {
            return false;
        }
        LlmCredentials fromEnv = fromEnvironment("CHAT");
        if (fromEnv == null || fromEnv.equals(failed)) {
            return false;
        }
        invalidChatCredentials = failed;
        return true;
    }

    private LlmCredentials fromDatabase(String role) {
        String provider = config.getOrDefault("llm." + role + ".provider", null);
        if (provider == null || provider.isBlank()) {
            return null;
        }
        String id = provider.trim().toLowerCase(Locale.ROOT);
        Map<String, String> values = new HashMap<>();
        for (String field : FIELDS) {
            String value = config.getOrDefault("llm." + role + "." + id + "." + field, null);
            if (value != null && !value.isBlank()) {
                values.put(field, value);
            }
        }
        return values.isEmpty() ? null : new LlmCredentials(id, values);
    }

    private LlmCredentials fromEnvironment(String envRole) {
        String provider = env.apply("DEEPSQL_" + envRole + "_PROVIDER");
        if (provider == null || provider.isBlank()) {
            return null;
        }
        Map<String, String> values = new HashMap<>();
        for (String field : FIELDS) {
            String name = "DEEPSQL_" + envRole + "_"
                    + field.toUpperCase(Locale.ROOT).replace('-', '_');
            String value = env.apply(name);
            if (value != null && !value.isBlank()) {
                values.put(field, value);
            }
        }
        return values.isEmpty()
                ? null
                : new LlmCredentials(provider.trim().toLowerCase(Locale.ROOT), values);
    }
}
