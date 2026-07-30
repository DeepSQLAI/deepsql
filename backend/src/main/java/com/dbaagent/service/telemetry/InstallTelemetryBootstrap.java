package com.dbaagent.service.telemetry;

import com.dbaagent.model.InstallTelemetryIdentity;
import com.dbaagent.repository.InstallTelemetryIdentityRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class InstallTelemetryBootstrap {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int SECRET_BYTES = 32;
    private static final int TOKEN_RANDOM_BYTES = 28;
    private static final String TOKEN_PREFIX = "dt_live_";
    private static final String UNKNOWN_COMPANY = "unknown";

    /**
     * Email domains we won't infer as company names from the admin email
     * fallback. Mostly consumer freemail providers + localhost/dev domains.
     */
    static final Set<String> FREEMAIL_DOMAINS = Set.of(
            "gmail.com", "googlemail.com", "yahoo.com", "hotmail.com",
            "outlook.com", "icloud.com", "me.com", "protonmail.com",
            "aol.com", "mail.com", "localhost", "dba-agent.local");

    private final InstallTelemetryIdentityRepository repository;
    private final TelemetryClient telemetryClient;

    @PostConstruct
    @Transactional
    public void bootstrap() {
        InstallTelemetryIdentity identity = repository.findById(1).orElse(null);

        if (identity == null) {
            identity = buildNewIdentity();
            try {
                repository.save(identity);
                repository.flush();
            } catch (DataIntegrityViolationException e) {
                // Another JVM beat us to the singleton row (concurrent start,
                // blue-green deploy, rolling restart). That instance owns the
                // report — do not emit, do not throw.
                log.info("InstallTelemetryBootstrap: identity created by a peer JVM, skipping");
                return;
            }
            log.info("InstallTelemetryBootstrap: new install identity created, install_id={}",
                    identity.getInstallId());
        }

        // Report install.bootstrapped whenever it has NOT yet been reported.
        // Gating on bootstrap_reported_at (not on "the row is brand new") is the
        // fix for installs that go untracked in PostHog: an identity row created
        // while the sink was NoOp (the 2026-05-23..05-26 key-rollout window), or
        // carried forward on a reused/persistent volume, still emits the "new
        // install" signal on its next start instead of being invisible forever.
        if (identity.getBootstrapReportedAt() == null) {
            Map<String, Object> props = new HashMap<>();
            props.put("install_method", detectInstallMethod());
            props.put("os",   System.getProperty("os.name", "unknown"));
            props.put("arch", System.getProperty("os.arch", "unknown"));
            telemetryClient.capture("install.bootstrapped", props);

            identity.setBootstrapReportedAt(OffsetDateTime.now());
            repository.save(identity);
            log.info("InstallTelemetryBootstrap: install.bootstrapped reported, install_id={}",
                    identity.getInstallId());
        } else {
            log.debug("InstallTelemetryBootstrap: install.bootstrapped already reported, skipping");
        }
    }

    private InstallTelemetryIdentity buildNewIdentity() {
        byte[] secret = new byte[SECRET_BYTES];
        RANDOM.nextBytes(secret);

        byte[] tokenRandom = new byte[TOKEN_RANDOM_BYTES];
        RANDOM.nextBytes(tokenRandom);
        String token = TOKEN_PREFIX + HexFormat.of().formatHex(tokenRandom);

        String companyName = resolveCompanyName(
                System.getenv("DEEPSQL_COMPANY_NAME"),
                System.getenv("DEEPSQL_INITIAL_ADMIN_EMAIL"));

        return InstallTelemetryIdentity.builder()
                .id(1)
                .installId(UUID.randomUUID())
                .installSecret(secret)
                .installToken(token)
                .companyName(companyName)
                .createdAt(OffsetDateTime.now())
                .build();
    }

    private String detectInstallMethod() {
        String m = System.getenv("DEEPSQL_INSTALL_METHOD");
        return (m == null || m.isBlank()) ? "unknown" : m;
    }

    /**
     * Resolves the company_name attached to this install's identity.
     *
     * Precedence:
     *   1. explicit {@code DEEPSQL_COMPANY_NAME} env var (operator-supplied)
     *   2. domain part of the admin email, if not a freemail/localhost domain
     *   3. {@code "unknown"} sentinel
     *
     * Package-private so {@code InstallTelemetryBootstrapTest} can exercise
     * the precedence rules directly without touching {@code System.getenv}.
     */
    static String resolveCompanyName(String explicitEnv, String adminEmail) {
        if (explicitEnv != null && !explicitEnv.isBlank()) {
            return explicitEnv.trim();
        }
        if (adminEmail != null && adminEmail.indexOf('@') > 0) {
            String domain = adminEmail.substring(adminEmail.indexOf('@') + 1).trim().toLowerCase();
            if (!domain.isEmpty() && !FREEMAIL_DOMAINS.contains(domain)) {
                return domain;
            }
        }
        return UNKNOWN_COMPANY;
    }
}
