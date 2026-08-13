package com.dbaagent.security;

import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class JwtUtilFailClosedTest {

    @Test
    void blankSecretFailsUnderProd() {
        JwtUtil jwt = new JwtUtil();
        ReflectionTestUtils.setField(jwt, "jwtSecret", "");
        ReflectionTestUtils.setField(jwt, "authEnabled", false);
        ReflectionTestUtils.setField(jwt, "activeProfiles", "prod");
        assertThrows(IllegalStateException.class, jwt::initialize);
    }

    @Test
    void blankSecretFailsWhenAuthEnabled() {
        JwtUtil jwt = new JwtUtil();
        ReflectionTestUtils.setField(jwt, "jwtSecret", "short");
        ReflectionTestUtils.setField(jwt, "authEnabled", true);
        ReflectionTestUtils.setField(jwt, "activeProfiles", "dev");
        assertThrows(IllegalStateException.class, jwt::initialize);
    }

    @Test
    void strongSecretAccepted() {
        JwtUtil jwt = new JwtUtil();
        ReflectionTestUtils.setField(jwt, "jwtSecret", "x".repeat(32));
        ReflectionTestUtils.setField(jwt, "authEnabled", true);
        ReflectionTestUtils.setField(jwt, "activeProfiles", "prod");
        assertDoesNotThrow(jwt::initialize);
    }

    @Test
    void ephemeralAllowedOnlyWhenAuthOffAndNotProd() {
        JwtUtil jwt = new JwtUtil();
        ReflectionTestUtils.setField(jwt, "jwtSecret", "");
        ReflectionTestUtils.setField(jwt, "authEnabled", false);
        ReflectionTestUtils.setField(jwt, "activeProfiles", "dev");
        assertDoesNotThrow(jwt::initialize);
    }
}
