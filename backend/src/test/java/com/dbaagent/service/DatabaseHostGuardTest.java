package com.dbaagent.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DatabaseHostGuardTest {

    private DatabaseHostGuardProperties properties;
    private DatabaseHostGuard guard;

    @BeforeEach
    void setUp() {
        properties = new DatabaseHostGuardProperties();
        properties.setEnabled(true);
        guard = new DatabaseHostGuard(properties);
    }

    @Test
    void shipsDisabledByDefault() {
        DatabaseHostGuardProperties defaults = new DatabaseHostGuardProperties();

        assertFalse(defaults.isEnabled());
        assertDoesNotThrow(() -> new DatabaseHostGuard(defaults).assertAllowed("169.254.169.254"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"127.0.0.1", "10.0.0.5", "192.168.1.1", "169.254.169.254", "[::1]"})
    void blocksRestrictedTargets(String host) {
        assertThrows(IllegalArgumentException.class, () -> guard.assertAllowed(host));
    }

    @Test
    void allowsPublicAddress() {
        assertDoesNotThrow(() -> guard.assertAllowed("93.184.216.34"));
    }

    @Test
    void allowlistExemptsTheOperatorsOwnDatabase() {
        properties.setAllowedHosts(List.of("10.0.0.5", ".db.internal"));

        assertDoesNotThrow(() -> guard.assertAllowed("10.0.0.5"));
        assertDoesNotThrow(() -> guard.assertAllowed("primary.db.internal"));
        assertThrows(IllegalArgumentException.class, () -> guard.assertAllowed("10.0.0.6"));
    }

    @Test
    void blankHostIsSkippedRatherThanRejected() {
        // A tunnelled connection targets the local forwarded port; the caller
        // passes no host and that must not be an error.
        assertDoesNotThrow(() -> guard.assertAllowed(null));
        assertDoesNotThrow(() -> guard.assertAllowed("  "));
    }

    @Test
    void messageNamesTheHostAndTheEscapeHatch() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> guard.assertAllowed("169.254.169.254"));

        assertTrue(e.getMessage().contains("169.254.169.254"), e.getMessage());
        assertTrue(e.getMessage().contains("allowed-hosts"), e.getMessage());
    }
}
