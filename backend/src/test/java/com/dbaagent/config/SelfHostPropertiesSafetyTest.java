package com.dbaagent.config;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Self-host safety net: catches application-prod.properties (the file a
 * self-hosted customer runs with) silently shipping our hosted demo
 * domains as defaults.
 *
 * Story: a customer doing a fresh self-host on a VM, with a port-forward
 * to their laptop and `--url http://localhost:8081` on the CLI, found
 * their `deepsql login` browser flow opening
 * `https://deepsql.example.com/cli-authorize?id=...`. Root cause was
 * `app.base-url=${APP_BASE_URL:https://deepsql.example.com}` in
 * application-prod.properties — when APP_BASE_URL wasn't exported, the
 * prod profile silently fell back to our hosted demo. The backend then
 * returned that URL to the CLI as `authorize_url`, and the CLI opened
 * it. Customer's authentication state landed on OUR domain.
 *
 * This test reads application-prod.properties verbatim and asserts that
 * none of the *.url-shaped properties leak our domains as defaults. If
 * someone reverts the fix or adds a new property that ships our URL,
 * this fails before CI lets the PR land.
 *
 * Note: {@link CorsAllowlistSafetyTest} enforces the same rule across
 * every shipped properties file AND the Java @Value defaults this test
 * cannot see, since it reads properties through java.util.Properties.
 */
class SelfHostPropertiesSafetyTest {

    private Properties loadProd() throws Exception {
        Properties p = new Properties();
        try (InputStream in = new ClassPathResource("application-prod.properties").getInputStream()) {
            p.load(in);
        }
        return p;
    }

    @Test
    void prodProfile_appBaseUrlDoesNotLeakStayflexiAsDefault() throws Exception {
        Properties p = loadProd();
        String value = p.getProperty("app.base-url");
        assertThat(value)
            .as("application-prod.properties#app.base-url must not default to our hosted demo domain. "
                + "Use APP_BASE_URL with a safe localhost fallback, never an external hostname.")
            .isNotNull()
            .doesNotContain("stayflexi")
            .doesNotContain("deepsql.ai");
    }

    @Test
    void prodProfile_appPublicUrlDoesNotLeakStayflexiAsDefault() throws Exception {
        Properties p = loadProd();
        String value = p.getProperty("app.public-url");
        assertThat(value)
            .as("application-prod.properties#app.public-url must not default to our hosted demo domain. "
                + "Customer's invitation/signup links would otherwise point at our infra.")
            .isNotNull()
            .doesNotContain("stayflexi")
            .doesNotContain("deepsql.ai");
    }

    @Test
    void prodProfile_corsAllowedOriginsDoesNotShipOurDomains() throws Exception {
        // Our domains in the customer's CORS allowlist isn't immediately
        // dangerous (CORS is browser-enforced and the customer doesn't
        // own our domain anyway), but it's still a smell — it suggests
        // the customer might be expected to be a frontend on our infra,
        // which they're not. Keep it clean.
        Properties p = loadProd();
        String value = p.getProperty("cors.allowed.origins");
        assertThat(value)
            .as("application-prod.properties#cors.allowed.origins should be overridable via "
                + "CORS_ALLOWED_ORIGINS with a localhost-only fallback. Don't bake our domains "
                + "into the default — that file is read by every self-hosted prod deployment.")
            .isNotNull()
            .doesNotContain("stayflexi")
            .doesNotContain("deepsql.ai");
    }

    @Test
    void prodProfile_appBaseUrlIsOverridableViaEnvVar() throws Exception {
        // The whole point: customers set APP_BASE_URL=https://their-host
        // before starting the backend, and that flows through. The
        // application-prod.properties value MUST be the `${APP_BASE_URL:...}`
        // form — not a literal — so the env var actually overrides.
        Properties p = loadProd();
        String value = p.getProperty("app.base-url");
        assertThat(value)
            .as("app.base-url must use ${APP_BASE_URL:default} placeholder syntax so customers "
                + "can override via env var without editing the properties file.")
            .startsWith("${APP_BASE_URL")
            .contains(":")
            .endsWith("}");
    }

    private static final List<String> SCANNED_FILES = List.of(
        "application.properties",
        "application-prod.properties",
        "application-test.properties"
    );

    /**
     * Known non-credential test fixtures that would otherwise be flagged. Kept
     * intentionally tiny — every entry must be justified in a comment, and this list
     * is the ONLY sanctioned way to silence an offender; nobody should ever "fix" a
     * real finding by adding to it without that justification.
     */
    private static final List<String> KNOWN_TEST_FIXTURE_EXEMPTIONS = List.of(
        // Hardcoded, human-readable, and committed on purpose so the "test" Spring
        // profile signs JWTs deterministically in CI without any env var. It is never
        // loaded outside @ActiveProfiles("test") and cannot reach a real deployment.
        "application-test.properties#security.jwt.secret"
        // NOTE: test.connection.id used to be exempted here because it shipped a real
        // connection UUID as its default, whose hex-and-hyphen shape tripped the
        // value-shape backstop. It now defaults to empty (${TEST_CONNECTION_ID:}), so
        // it no longer offends and the exemption is deliberately gone. If a default is
        // ever re-added, this test SHOULD fail — that is the point.
    );

    /**
     * Credential words, matched against the FINAL dot-segment of a property name —
     * not as substrings anywhere. A substring match also flags
     * brain.key-columns.weight.join (a scoring weight),
     * management.endpoint.health.show-details, and app.chat.max-system-prompt-tokens,
     * none of which is a credential.
     *
     * <p>"endpoint" is deliberately absent: an endpoint is deployment configuration,
     * not key material. Endpoints are still blanked by hand in Step 3 of this task and
     * removed outright in Task 10.
     *
     * <p>Matches a trailing "-word" or "_word" so both kebab-case
     * (spring.data.redis.password) and SCREAMING_SNAKE_CASE (ENCRYPTION_KEY) names are
     * covered.
     */
    private static final List<String> CREDENTIAL_SUFFIXES =
        List.of("key", "secret", "password", "token", "credential");

    private static boolean isCredentialShaped(String name) {
        String last = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        return CREDENTIAL_SUFFIXES.stream()
                .anyMatch(s -> last.equals(s) || last.endsWith("-" + s) || last.endsWith("_" + s));
    }

    /**
     * Value-shape backstop: catches key material under a name the suffix rule misses,
     * such as ENCRYPTION_KEYS. Splits the value on ':', ';', and ',' — the structural
     * separators used by "keyId:secretMaterial" and multi-key "id:key,id:key" formats —
     * and shape-checks each segment independently, so a short, innocuous label (a key
     * id) cannot hide real key material behind it in the same value. Deliberately
     * conservative — each segment must be long, unbroken, and base64/hex shaped, and
     * the whole value is skipped outright when it's a URL, so `health,info,prometheus`
     * and `http://localhost:4318/v1/traces` do not trip it.
     */
    private static boolean looksLikeKeyMaterial(String value) {
        if (value == null || value.isBlank()) return false;
        if (value.startsWith("http://") || value.startsWith("https://")) return false;
        for (String segment : value.split("[:;,]")) {
            if (looksLikeKeyMaterialSegment(segment)) {
                return true;
            }
        }
        return false;
    }

    private static boolean looksLikeKeyMaterialSegment(String segment) {
        if (segment == null || segment.length() < 32) return false;
        return segment.matches("[A-Za-z0-9+/=_-]+")
                && segment.matches(".*[A-Za-z].*")
                && segment.matches(".*[0-9].*");
    }

    private Properties load(String file) throws Exception {
        Properties p = new Properties();
        try (InputStream in = new ClassPathResource(file).getInputStream()) {
            p.load(in);
        }
        return p;
    }

    /**
     * A Spring placeholder is safe only when its default is empty: ${FOO:} .
     * ${FOO:some-literal} bakes a value into the repo, which is how the production
     * Azure OpenAI key reached git history.
     */
    private static boolean hasNonEmptyDefault(String raw) {
        if (raw == null) return false;
        String v = raw.trim();
        if (!v.startsWith("${") || !v.endsWith("}")) {
            return !v.isEmpty();          // a bare literal, not a placeholder
        }
        int colon = v.indexOf(':');
        if (colon < 0) return false;      // ${FOO} — no default at all
        return !v.substring(colon + 1, v.length() - 1).trim().isEmpty();
    }

    /**
     * The literal a property would fall back to: the text after the first colon in a
     * ${VAR:default} placeholder, or the whole value when it is a bare literal.
     */
    private static String defaultValueOf(String raw) {
        if (raw == null) return "";
        String v = raw.trim();
        if (!v.startsWith("${") || !v.endsWith("}")) {
            return v;
        }
        int colon = v.indexOf(':');
        return colon < 0 ? "" : v.substring(colon + 1, v.length() - 1).trim();
    }

    @Test
    void noPropertiesFileShipsACredentialDefault() throws Exception {
        List<String> offenders = new ArrayList<>();
        for (String file : SCANNED_FILES) {
            Properties p = load(file);
            for (String name : p.stringPropertyNames()) {
                String raw = p.getProperty(name);
                boolean offends =
                    (isCredentialShaped(name) && hasNonEmptyDefault(raw))
                    || looksLikeKeyMaterial(defaultValueOf(raw));
                if (offends && !KNOWN_TEST_FIXTURE_EXEMPTIONS.contains(file + "#" + name)) {
                    offenders.add(file + "#" + name);
                }
            }
        }
        assertThat(offenders)
            .as("Credential-shaped properties must never carry a baked-in default. "
                + "Use ${VAR:} and supply the value via environment or the setup wizard. "
                + "A literal default here is exactly how the production Azure OpenAI key "
                + "was committed to git history.")
            .isEmpty();
    }

    /**
     * Regression guard for the gap a review caught: the composite
     * "keyId:secretMaterial" shape (exactly how ENCRYPTION_KEYS was leaked) must be
     * caught even though neither segment alone looks credential-shaped by name, and
     * even though the value as a whole isn't a single unbroken base64 blob.
     */
    @Test
    void looksLikeKeyMaterialFlagsKeyIdSecretCompositeValue() {
        assertThat(looksLikeKeyMaterial("local-2025-01:EXAMPLEexample0000111122223333444455556666aa="))
            .as("a keyId:base64 composite must not be able to hide real key material "
                + "behind a short, innocuous label")
            .isTrue();
    }

    @Test
    void looksLikeKeyMaterialFlagsMultiKeyCommaSeparatedValue() {
        assertThat(looksLikeKeyMaterial(
                "local-2025-01:EXAMPLEexample0000111122223333444455556666aa=,"
                    + "self-hosted-key-1:SAMPLEsample1111222233334444555566667777bbb="))
            .as("a comma-separated list of keyId:base64 composites must still be flagged "
                + "segment-by-segment")
            .isTrue();
    }

    @Test
    void isCredentialShapedRecognisesUnderscoreBeforeCredentialWord() {
        assertThat(isCredentialShaped("ENCRYPTION_KEY"))
            .as("SCREAMING_SNAKE_CASE names must be recognised the same as kebab-case "
                + "ones — an underscore before the credential word counts just like a hyphen")
            .isTrue();
    }

    @Test
    void looksLikeKeyMaterialDoesNotFlagAnOtlpUrl() {
        assertThat(looksLikeKeyMaterial("http://localhost:4318/v1/traces"))
            .as("a URL must never be treated as key material, even though splitting on "
                + "':' would otherwise expose a long-ish trailing segment")
            .isFalse();
    }
}
