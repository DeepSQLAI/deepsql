package com.dbaagent.security;

import com.dbaagent.model.Permission;
import com.dbaagent.model.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.Duration;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class JwtUtilImpersonationClaimTest {

    private JwtUtil jwtUtil;

    @BeforeEach
    void setUp() {
        jwtUtil = new JwtUtil();
        ReflectionTestUtils.setField(jwtUtil, "jwtSecret", "x".repeat(32));
        ReflectionTestUtils.setField(jwtUtil, "authEnabled", true);
        ReflectionTestUtils.setField(jwtUtil, "activeProfiles", "dev");
        ReflectionTestUtils.setField(jwtUtil, "accessTokenMinutes", 15L);
        jwtUtil.initialize();
    }

    @Test
    void accessTokenRoundTripsImpersonateUserId() {
        String token = jwtUtil.generateAccessToken(
            "admin",
            "sess-1",
            Role.ADMIN,
            Set.of(Permission.MANAGE_USERS),
            Duration.ofMinutes(15),
            2L
        );

        assertEquals("admin", jwtUtil.extractUsername(token));
        assertEquals("sess-1", jwtUtil.extractSessionId(token));
        assertEquals(2L, jwtUtil.extractImpersonateUserId(token));
        assertEquals("ADMIN", jwtUtil.extractRole(token));
    }

    @Test
    void accessTokenWithoutClaimHasNoImpersonateUserId() {
        String token = jwtUtil.generateAccessToken(
            "admin",
            "sess-1",
            Role.ADMIN,
            Set.of(Permission.MANAGE_USERS),
            Duration.ofMinutes(15)
        );

        assertNull(jwtUtil.extractImpersonateUserId(token));
    }
}
