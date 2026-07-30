package com.dbaagent.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Closes the gap {@link SelfHostPropertiesSafetyTest} structurally cannot cover: it reads
 * {@code application*.properties} through {@code java.util.Properties}, so a property
 * default written as a Java {@code @Value} annotation is invisible to it.
 *
 * <p>That is exactly where the operating company's production hostnames were living —
 * {@code SecurityConfig}'s CORS allowlist default — and this repository is published under
 * Apache-2.0 as {@code github.com/DeepSQLAI/deepsql}. A hostname baked into a default is a
 * hostname every reader inherits, and a deployment that forgets to set the env var runs
 * with somebody else's allowlist rather than failing loudly.
 *
 * <p>Scanned as text on purpose: the point is that no configuration <em>default</em>
 * anywhere in the shipped backend names an operating-company host, whatever mechanism
 * expresses it.
 */
class CorsAllowlistSafetyTest {

    /**
     * Hosts belonging to whoever operates the hosted product. Deliberately matched as bare
     * substrings — subdomains, wildcards and scheme prefixes all have to be caught.
     */
    private static final List<String> OPERATOR_DOMAINS = List.of("stayflexi.com", "deepsql.ai");

    private static final Path MAIN_JAVA = Path.of("src/main/java");
    private static final Path MAIN_RESOURCES = Path.of("src/main/resources");

    private static List<Path> scannedFiles() throws IOException {
        List<Path> files = new ArrayList<>();
        try (Stream<Path> java = Files.walk(MAIN_JAVA)) {
            java.filter(Files::isRegularFile)
                .filter(p -> p.toString().endsWith(".java"))
                .forEach(files::add);
        }
        try (Stream<Path> resources = Files.list(MAIN_RESOURCES)) {
            resources.filter(Files::isRegularFile)
                     .filter(p -> p.getFileName().toString().matches("application.*\\.properties"))
                     .forEach(files::add);
        }
        return files;
    }

    @Test
    void noShippedBackendSourceOrPropertiesFileNamesAnOperatorDomain() throws IOException {
        List<String> offenders = new ArrayList<>();

        for (Path file : scannedFiles()) {
            List<String> lines = Files.readAllLines(file);
            for (int i = 0; i < lines.size(); i++) {
                String line = lines.get(i).toLowerCase(Locale.ROOT);
                for (String domain : OPERATOR_DOMAINS) {
                    if (line.contains(domain)) {
                        offenders.add(file + ":" + (i + 1));
                    }
                }
            }
        }

        assertThat(offenders)
            .as("An operating-company hostname must never be a shipped default. Take it "
                + "from the environment (CORS_ALLOWED_ORIGINS, APP_BASE_URL, "
                + "APP_PUBLIC_URL) and leave a localhost-only fallback behind. This "
                + "repository is public: a baked-in host is one every reader inherits, "
                + "and a deployment that forgets the env var silently runs with our "
                + "allowlist instead of failing.")
            .isEmpty();
    }

    /**
     * The default must still be a working localhost allowlist, not empty — an empty
     * allowlist turns every local dev browser request into an opaque CORS failure, which
     * is the failure mode most likely to get this "fixed" by pasting the domains back.
     */
    @Test
    void theShippedCorsDefaultStillCoversLocalDevelopment() throws IOException {
        String securityConfig = Files.readString(
                MAIN_JAVA.resolve("com/dbaagent/config/SecurityConfig.java"));
        String applicationProperties = Files.readString(
                MAIN_RESOURCES.resolve("application.properties"));

        assertThat(securityConfig).contains("${cors.allowed.origins:http://localhost:3000");
        assertThat(applicationProperties)
                .contains("cors.allowed.origins=${CORS_ALLOWED_ORIGINS:http://localhost:3000");
    }
}
