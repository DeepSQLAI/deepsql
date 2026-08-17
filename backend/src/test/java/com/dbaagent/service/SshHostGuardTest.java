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

class SshHostGuardTest {

    private SshHostGuardProperties properties;
    private SshHostGuard guard;

    @BeforeEach
    void setUp() {
        properties = new SshHostGuardProperties();
        // Ships disabled; these cases cover the behaviour once an operator turns it on.
        properties.setEnabled(true);
        guard = new SshHostGuard(properties);
    }

    @Test
    void shipsDisabledByDefault() {
        SshHostGuardProperties defaults = new SshHostGuardProperties();

        assertFalse(defaults.isEnabled());
        assertTrue(defaults.getAllowedHosts().isEmpty());
        assertDoesNotThrow(() -> new SshHostGuard(defaults).assertAllowed("169.254.169.254"));
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "127.0.0.1",
            "localhost",
            "0.0.0.0",
            "10.0.0.5",
            "172.16.4.9",
            "192.168.1.1",
            "169.254.169.254",
            "100.64.0.1",
            "[::1]",
            "[fd00::1]",
            "[::ffff:169.254.169.254]"
    })
    void blocksRestrictedTargets(String host) {
        assertThrows(IllegalArgumentException.class, () -> guard.assertAllowed(host));
    }

    @Test
    void blockedMessageNamesTheHostAndTheEscapeHatch() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> guard.assertAllowed("169.254.169.254"));

        assertTrue(e.getMessage().contains("169.254.169.254"), e.getMessage());
        assertTrue(e.getMessage().contains("allowed-hosts"), e.getMessage());
    }

    @Test
    void allowsPublicAddress() {
        assertDoesNotThrow(() -> guard.assertAllowed("93.184.216.34"));
    }

    @Test
    void rejectsBlankHost() {
        assertThrows(IllegalArgumentException.class, () -> guard.assertAllowed("  "));
        assertThrows(IllegalArgumentException.class, () -> guard.assertAllowed(null));
    }

    @Test
    void rejectsUnresolvableHost() {
        assertThrows(IllegalArgumentException.class,
                () -> guard.assertAllowed("no-such-host.invalid"));
    }

    @Test
    void allowlistExemptsExactHost() {
        properties.setAllowedHosts(List.of("10.0.0.5"));

        assertDoesNotThrow(() -> guard.assertAllowed("10.0.0.5"));
        assertThrows(IllegalArgumentException.class, () -> guard.assertAllowed("10.0.0.6"));
    }

    @Test
    void allowlistIsCaseInsensitiveAndTrimmed() {
        properties.setAllowedHosts(List.of("  LocalHost  "));

        assertDoesNotThrow(() -> guard.assertAllowed("localhost"));
    }

    @Test
    void allowlistSupportsDomainSuffix() {
        properties.setAllowedHosts(List.of(".corp.internal"));

        assertDoesNotThrow(() -> guard.assertAllowed("bastion.corp.internal"));
        assertThrows(IllegalArgumentException.class, () -> guard.assertAllowed("10.0.0.5"));
    }

    @Test
    void disabledGuardPermitsEverything() {
        properties.setEnabled(false);

        assertDoesNotThrow(() -> guard.assertAllowed("169.254.169.254"));
        assertDoesNotThrow(() -> guard.assertAllowed("no-such-host.invalid"));
    }
}
