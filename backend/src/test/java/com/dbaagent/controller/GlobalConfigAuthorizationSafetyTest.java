package com.dbaagent.controller;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Closes the gap {@link ConnectionScopedAuthorizationSafetyTest} structurally cannot cover.
 *
 * <p>That scanner only inspects handlers carrying a {@code connectionId} or a
 * {@code @PathVariable …Id}, because its job is per-connection tenancy. A controller that
 * writes <em>global</em> configuration has neither, so it is invisible to it — and the suite
 * still reports green.
 *
 * <p>{@code SetupController} lived in exactly that blind spot. It had no authorization of any
 * kind, so any authenticated user — the lowest role included — could POST
 * {@code /setup/llm-config} and repoint the organization's LLM endpoint and API key.
 * {@code LlmConfigResolver} reads the database tier before the environment tier, so that write
 * silently overrode a correctly configured install with no restart, sending every chat turn,
 * schema and query result to an attacker-chosen host. {@code /setup/llm-config/test} took the
 * same unvalidated URL and issued a server-side request to it.
 *
 * <p>Its class javadoc read "All other endpoints require an authenticated user" — true, and
 * precisely the trap: authentication is not authorization. This test encodes the rule CLAUDE.md
 * already states in prose, that an endpoint with no connection scope at all is admin-only.
 *
 * <p>Scanned as source text on purpose, matching {@link CorsAllowlistSafetyTest}: the guarantee
 * wanted is that no <em>future</em> global-config controller ships unguarded, whatever it is
 * named, and a text scan needs no database, Redis or LLM credentials to run.
 */
class GlobalConfigAuthorizationSafetyTest {

    private static final Path CONTROLLERS = Path.of("src/main/java/com/dbaagent/controller");

    /**
     * Controllers that write installation-wide configuration — settings that belong to no single
     * connection and therefore cannot be authorized against one. Every handler in these, apart
     * from the explicitly public ones below, must be admin-gated.
     */
    private static final Set<String> GLOBAL_CONFIG_CONTROLLERS = Set.of("SetupController.java");

    /**
     * Endpoints that must stay reachable without authentication, with the reason each is safe.
     *
     * <p>Both are genuinely pre-login: the frontend calls {@code /setup/status} to detect a
     * first run before any account exists, and {@code /setup/initialize} performs that first
     * run. They are also listed in {@code SecurityConfig}'s permitAll set, so gating them here
     * would break the onboarding flow rather than harden it.
     */
    private static final Set<String> PUBLIC_BY_DESIGN = Set.of(
        "/status",      // pre-login first-run probe; returns no secret (api keys are masked)
        "/initialize"   // performs the first run itself, before any user exists to authorize
    );

    /**
     * Handler mappings only. {@code @RequestMapping} is deliberately excluded: on these
     * controllers it is the class-level base path, not an endpoint, and counting it produced a
     * phantom offender ({@code "/setup"}) on the first run of this test.
     */
    private static final Pattern MAPPING = Pattern.compile(
        "@(?:Get|Post|Put|Delete|Patch)Mapping\\s*\\(\\s*(?:value\\s*=\\s*)?\"([^\"]*)\"");

    private static final Pattern CLASS_DECLARATION = Pattern.compile("\\bpublic\\s+class\\b");

    /**
     * A {@code @PreAuthorize} annotation in annotation position: at the start of a line,
     * indentation aside. Excludes the {@code import} line and any javadoc mention, both of
     * which precede the class declaration and would otherwise read as a gate.
     */
    private static final Pattern CLASS_LEVEL_PREAUTHORIZE = Pattern.compile(
        "(?m)^[ \\t]*@PreAuthorize\\s*\\(");

    private static List<Path> globalConfigControllers() throws IOException {
        try (Stream<Path> files = Files.walk(CONTROLLERS)) {
            return files.filter(Files::isRegularFile)
                        .filter(p -> GLOBAL_CONFIG_CONTROLLERS.contains(p.getFileName().toString()))
                        .toList();
        }
    }

    /**
     * True when a real {@code @PreAuthorize} annotation guards the class itself.
     *
     * <p>Matched at the start of a line, and the {@code import} line is excluded explicitly.
     * A plain {@code source.indexOf("@PreAuthorize")} is not good enough and was wrong here on
     * the first attempt: the import and a javadoc mention of the annotation both sit above the
     * class declaration, so deleting the actual gate still left the check returning true. That
     * was caught by removing the annotation and watching this test stay green — the same
     * false-negative the existing ConnectionScopedAuthorizationSafetyTest is noted to have.
     */
    private static boolean hasClassLevelPreAuthorize(String source) {
        Matcher declaration = CLASS_DECLARATION.matcher(source);
        if (!declaration.find()) {
            return false;
        }
        String beforeClass = source.substring(0, declaration.start());
        return CLASS_LEVEL_PREAUTHORIZE.matcher(beforeClass).find();
    }

    /** The body of one handler: from its mapping annotation to the start of the next one. */
    private static String handlerBody(String source, int mappingStart) {
        Matcher next = MAPPING.matcher(source);
        int end = source.length();
        if (next.find(mappingStart + 1)) {
            end = next.start();
        }
        return source.substring(mappingStart, end);
    }

    @Test
    void everyGlobalConfigEndpointIsAdminGatedUnlessPublicByDesign() throws IOException {
        List<String> offenders = new ArrayList<>();

        for (Path file : globalConfigControllers()) {
            String source = Files.readString(file);
            boolean classGated = hasClassLevelPreAuthorize(source);

            Matcher mappings = MAPPING.matcher(source);
            while (mappings.find()) {
                String route = mappings.group(1);
                if (PUBLIC_BY_DESIGN.contains(route)) {
                    continue;
                }
                boolean methodGated = handlerBody(source, mappings.start()).contains("@PreAuthorize");
                if (!classGated && !methodGated) {
                    offenders.add(file.getFileName() + " \"" + route + "\"");
                }
            }
        }

        assertThat(offenders)
            .as("""
                Global-configuration endpoints reachable by any authenticated user.

                These write installation-wide settings, so they cannot be authorized against a \
                connection and ConnectionScopedAuthorizationSafetyTest cannot see them. Guard the \
                controller with @PreAuthorize("hasRole('ADMIN')") and keep genuinely pre-login \
                routes in PUBLIC_BY_DESIGN with a reason.

                Unguarded: %s""".formatted(offenders))
            .isEmpty();
    }

    /**
     * The public exemptions are only safe while they stay pre-login. If {@code /status} or
     * {@code /initialize} ever stops being listed in {@code SecurityConfig}'s permitAll set, the
     * exemption above is masking a real gate rather than reflecting one, so fail and force a
     * re-read of both files together.
     */
    @Test
    void publicByDesignRoutesAreStillPermitAllInSecurityConfig() throws IOException {
        String securityConfig = Files.readString(
            Path.of("src/main/java/com/dbaagent/config/SecurityConfig.java"));

        Set<String> missing = new LinkedHashSet<>();
        for (String route : PUBLIC_BY_DESIGN) {
            if (!securityConfig.contains("\"/setup" + route + "\"")) {
                missing.add("/setup" + route);
            }
        }

        assertThat(missing)
            .as("""
                PUBLIC_BY_DESIGN exempts these from the admin gate because SecurityConfig \
                permits them without authentication. They are no longer in that permitAll list, \
                so the exemption no longer describes reality — either restore them there or \
                drop them from PUBLIC_BY_DESIGN so they get gated.

                No longer permitAll: %s""".formatted(missing))
            .isEmpty();
    }
}
