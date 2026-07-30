package com.dbaagent.service.telemetry;

import com.dbaagent.model.InstallTelemetryIdentity;
import com.dbaagent.repository.InstallTelemetryIdentityRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InstallTelemetryBootstrapTest {

    @Mock private InstallTelemetryIdentityRepository repository;
    @Mock private TelemetryClient telemetryClient;
    @InjectMocks private InstallTelemetryBootstrap bootstrap;

    @Test
    void generatesNewIdentityWhenNoneExists() {
        when(repository.findById(1)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        bootstrap.bootstrap();

        // save() is called to create the row and again to stamp bootstrap_reported_at;
        // both act on the same identity object, so the last capture reflects it.
        ArgumentCaptor<InstallTelemetryIdentity> captor =
                ArgumentCaptor.forClass(InstallTelemetryIdentity.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        InstallTelemetryIdentity saved = captor.getValue();
        assertEquals(1, saved.getId());
        assertNotNull(saved.getInstallId());
        assertEquals(32, saved.getInstallSecret().length);
        assertTrue(saved.getInstallToken().startsWith("dt_live_"));
        assertEquals(64, saved.getInstallToken().length());
        assertNotNull(saved.getCreatedAt());
    }

    @Test
    void emitsInstallBootstrappedEventOnNewIdentity() {
        when(repository.findById(1)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        bootstrap.bootstrap();

        verify(telemetryClient).capture(eq("install.bootstrapped"), any());
    }

    @Test
    void stampsBootstrapReportedAtAfterEmittingOnNewIdentity() {
        when(repository.findById(1)).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        bootstrap.bootstrap();

        ArgumentCaptor<InstallTelemetryIdentity> captor =
                ArgumentCaptor.forClass(InstallTelemetryIdentity.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        assertNotNull(captor.getValue().getBootstrapReportedAt(),
                "bootstrap_reported_at must be stamped once install.bootstrapped is reported");
    }

    @Test
    void reEmitsBootstrappedWhenExistingIdentityWasNeverReported() {
        // Identity row exists but was never reported — e.g. created while the sink
        // was NoOp during the 2026-05-23..05-26 key-rollout window, or carried
        // forward on a reused/persistent volume. Bootstrap must re-emit + stamp.
        InstallTelemetryIdentity existing = InstallTelemetryIdentity.builder()
                .id(1)
                .installId(UUID.randomUUID())
                .bootstrapReportedAt(null)
                .build();
        when(repository.findById(1)).thenReturn(Optional.of(existing));
        when(repository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        bootstrap.bootstrap();

        verify(telemetryClient).capture(eq("install.bootstrapped"), any());
        ArgumentCaptor<InstallTelemetryIdentity> captor =
                ArgumentCaptor.forClass(InstallTelemetryIdentity.class);
        verify(repository).save(captor.capture());
        assertNotNull(captor.getValue().getBootstrapReportedAt(),
                "re-emit must stamp bootstrap_reported_at so it does not fire again");
    }

    @Test
    void doesNotReEmitWhenAlreadyReported() {
        InstallTelemetryIdentity reported = InstallTelemetryIdentity.builder()
                .id(1)
                .installId(UUID.randomUUID())
                .bootstrapReportedAt(OffsetDateTime.now())
                .build();
        when(repository.findById(1)).thenReturn(Optional.of(reported));

        bootstrap.bootstrap();

        verify(repository, never()).save(any());
        verifyNoInteractions(telemetryClient);
    }

    @Test
    void swallowsPkCollisionFromConcurrentStartAndDoesNotEmit() {
        when(repository.findById(1)).thenReturn(Optional.empty());
        when(repository.save(any())).thenThrow(new DataIntegrityViolationException("dup PK"));

        // Must not propagate — propagation would kill the application context boot.
        bootstrap.bootstrap();

        verifyNoInteractions(telemetryClient);
    }

    // ─── resolveCompanyName precedence ──────────────────────────────────────

    @Test
    void resolveCompanyName_explicitEnvWins() {
        assertEquals("Acme Corp",
                InstallTelemetryBootstrap.resolveCompanyName("Acme Corp", "admin@gmail.com"));
    }

    @Test
    void resolveCompanyName_explicitEnvIsTrimmed() {
        assertEquals("Acme",
                InstallTelemetryBootstrap.resolveCompanyName("   Acme   ", null));
    }

    @Test
    void resolveCompanyName_fallsBackToEmailDomain() {
        assertEquals("acme.com",
                InstallTelemetryBootstrap.resolveCompanyName(null, "admin@Acme.com"));
    }

    @Test
    void resolveCompanyName_skipsFreemailDomains() {
        for (String freemail : new String[]{"gmail.com", "yahoo.com", "outlook.com",
                "icloud.com", "protonmail.com", "aol.com", "localhost"}) {
            assertEquals("unknown",
                    InstallTelemetryBootstrap.resolveCompanyName(null, "admin@" + freemail),
                    "freemail domain should not be inferred: " + freemail);
        }
    }

    @Test
    void resolveCompanyName_returnsUnknownWhenBothMissing() {
        assertEquals("unknown",
                InstallTelemetryBootstrap.resolveCompanyName(null, null));
        assertEquals("unknown",
                InstallTelemetryBootstrap.resolveCompanyName("", ""));
        assertEquals("unknown",
                InstallTelemetryBootstrap.resolveCompanyName("   ", "noatsymbol"));
    }
}
