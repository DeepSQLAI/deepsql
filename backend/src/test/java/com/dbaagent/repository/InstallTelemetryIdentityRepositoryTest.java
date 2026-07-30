package com.dbaagent.repository;

import com.dbaagent.model.InstallTelemetryIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class InstallTelemetryIdentityRepositoryTest {

    @Autowired
    private InstallTelemetryIdentityRepository repository;

    @Test
    void persistsAndReadsBackTheSingletonRow() {
        InstallTelemetryIdentity identity = InstallTelemetryIdentity.builder()
                .id(1)
                .installId(UUID.randomUUID())
                .installSecret(new byte[]{1, 2, 3, 4})
                .installToken("dt_test_" + UUID.randomUUID())
                .companyName("acme.com")
                .createdAt(OffsetDateTime.now())
                .build();

        repository.save(identity);

        Optional<InstallTelemetryIdentity> loaded = repository.findById(1);
        assertTrue(loaded.isPresent());
        assertEquals(identity.getInstallId(), loaded.get().getInstallId());
        assertArrayEquals(identity.getInstallSecret(), loaded.get().getInstallSecret());
        assertEquals(identity.getInstallToken(), loaded.get().getInstallToken());
        assertEquals("acme.com", loaded.get().getCompanyName());
        assertEquals(identity.getCreatedAt(), loaded.get().getCreatedAt());
    }
}
