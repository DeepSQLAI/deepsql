package com.dbaagent.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EncryptionServiceTest {

    private static final String LEGACY_KEY_ID = "local-2025-01";
    private static final String LEGACY_KEY_HEX = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
    private static final String ACTIVE_KEY_ID = "self-hosted-key-1";
    private static final String ACTIVE_KEY_HEX = "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100";

    @Test
    void decryptsLegacyCiphertextWhenNewActiveKeyIsConfigured() {
        EncryptionService legacyService = new EncryptionService("", LEGACY_KEY_HEX, LEGACY_KEY_ID);
        byte[] ciphertext = legacyService.encrypt("aws_sf_rds");

        EncryptionService mixedService = new EncryptionService(
            LEGACY_KEY_ID + ":" + LEGACY_KEY_HEX,
            ACTIVE_KEY_HEX,
            ACTIVE_KEY_ID
        );

        assertEquals("aws_sf_rds", mixedService.decrypt(ciphertext));
    }

    @Test
    void usesExplicitActiveKeyForNewEncryptionWhileKeepingLegacyKeyAvailable() {
        EncryptionService service = new EncryptionService(
            LEGACY_KEY_ID + ":" + LEGACY_KEY_HEX,
            ACTIVE_KEY_HEX,
            ACTIVE_KEY_ID
        );

        byte[] ciphertext = service.encrypt("fresh-value");

        assertEquals("fresh-value", service.decrypt(ciphertext));
        assertEquals('D', ciphertext[0]);
        assertEquals('B', ciphertext[1]);
        assertEquals('A', ciphertext[2]);
        assertEquals('1', ciphertext[3]);

        int keyIdLength = ciphertext[4] & 0xFF;
        byte[] keyIdBytes = new byte[keyIdLength];
        System.arraycopy(ciphertext, 5, keyIdBytes, 0, keyIdLength);
        assertArrayEquals(ACTIVE_KEY_ID.getBytes(), keyIdBytes);
    }

    @Test
    void rejectsConflictingDuplicateKeyIds() {
        assertThrows(
            IllegalStateException.class,
            () -> new EncryptionService(
                LEGACY_KEY_ID + ":" + LEGACY_KEY_HEX,
                ACTIVE_KEY_HEX,
                LEGACY_KEY_ID
            )
        );
    }
}
