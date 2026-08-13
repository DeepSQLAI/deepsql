package com.dbaagent.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class SessionKillSupportTest {

    @Test
    void acceptsNumericPid() {
        assertEquals(42L, SessionKillSupport.requireNumericPid("42"));
        assertEquals(0L, SessionKillSupport.requireNumericPid("0"));
    }

    @Test
    void rejectsInjectionPayloads() {
        assertThrows(IllegalArgumentException.class, () -> SessionKillSupport.requireNumericPid("1); DROP TABLE t;--"));
        assertThrows(IllegalArgumentException.class, () -> SessionKillSupport.requireNumericPid("1 OR 1=1"));
        assertThrows(IllegalArgumentException.class, () -> SessionKillSupport.requireNumericPid("-1"));
        assertThrows(IllegalArgumentException.class, () -> SessionKillSupport.requireNumericPid("0x10"));
        assertThrows(IllegalArgumentException.class, () -> SessionKillSupport.requireNumericPid(""));
        assertThrows(IllegalArgumentException.class, () -> SessionKillSupport.requireNumericPid(null));
    }
}
