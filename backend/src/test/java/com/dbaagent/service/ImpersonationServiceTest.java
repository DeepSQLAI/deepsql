package com.dbaagent.service;

import com.dbaagent.model.SecurityEventOutcome;
import com.dbaagent.model.SecurityEventType;
import com.dbaagent.model.User;
import com.dbaagent.model.UserAccountStatus;
import com.dbaagent.repository.UserRepository;
import com.dbaagent.security.CustomUserDetailsService;
import com.dbaagent.security.ImpersonationContext;
import com.dbaagent.security.JwtUtil;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ImpersonationServiceTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private CustomUserDetailsService userDetailsService;

    @Mock
    private AuthSessionService authSessionService;

    @Mock
    private SecurityEventService securityEventService;

    @Mock
    private JwtUtil jwtUtil;

    @InjectMocks
    private ImpersonationService impersonationService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(impersonationService, "authEnabled", true);
        ReflectionTestUtils.setField(impersonationService, "impersonateCookieName", "impersonate_user");
        ReflectionTestUtils.setField(impersonationService, "accessCookieName", "auth_token");
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
        ImpersonationContext.clear();
    }

    @Test
    void startWritesCookieAndAudits() {
        User admin = user(1L, "admin", "ADMIN");
        User editor = user(2L, "marts-editor", "DEVELOPER");
        when(userRepository.findById(2L)).thenReturn(Optional.of(editor));

        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        ImpersonationContext.State state = impersonationService.start(admin, 2L, request, response);

        assertEquals("marts-editor", state.targetUsername());
        verify(authSessionService).writeImpersonationCookie(response, "impersonate_user", 2L);
        verify(authSessionService, never()).reissueAccessToken(any(), any(), any(), any());
        ArgumentCaptor<SecurityEventService.EventRequest> captor =
            ArgumentCaptor.forClass(SecurityEventService.EventRequest.class);
        verify(securityEventService).log(captor.capture());
        assertEquals(SecurityEventType.IMPERSONATION_STARTED, captor.getValue().eventType());
        assertEquals(SecurityEventOutcome.SUCCESS, captor.getValue().outcome());
        assertEquals(1L, captor.getValue().actorUserId());
        assertEquals(2L, captor.getValue().userId());
    }

    @Test
    void startRejectsSelf() {
        User admin = user(1L, "admin", "ADMIN");
        when(userRepository.findById(1L)).thenReturn(Optional.of(admin));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> impersonationService.start(admin, 1L, new MockHttpServletRequest(), new MockHttpServletResponse()));
        assertEquals(400, ex.getStatusCode().value());
        verify(authSessionService, never()).writeImpersonationCookie(any(), any(), eq(1L));
    }

    @Test
    void startRejectsAnotherAdmin() {
        User admin = user(1L, "admin", "ADMIN");
        User otherAdmin = user(3L, "ops-admin", "ADMIN");
        when(userRepository.findById(3L)).thenReturn(Optional.of(otherAdmin));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> impersonationService.start(admin, 3L, new MockHttpServletRequest(), new MockHttpServletResponse()));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void startRejectsLockedUser() {
        User admin = user(1L, "admin", "ADMIN");
        User locked = user(4L, "locked-editor", "DEVELOPER");
        locked.setAccountStatus(UserAccountStatus.LOCKED.name());
        when(userRepository.findById(4L)).thenReturn(Optional.of(locked));

        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> impersonationService.start(admin, 4L, new MockHttpServletRequest(), new MockHttpServletResponse()));
        assertEquals(400, ex.getStatusCode().value());
    }

    @Test
    void startRejectsNonAdminActor() {
        User editor = user(2L, "marts-editor", "DEVELOPER");
        ResponseStatusException ex = assertThrows(ResponseStatusException.class,
            () -> impersonationService.start(editor, 5L, new MockHttpServletRequest(), new MockHttpServletResponse()));
        assertEquals(403, ex.getStatusCode().value());
    }

    @Test
    void startRewritesAccessTokenWithImpersonationClaim() {
        User admin = user(1L, "admin", "ADMIN");
        User editor = user(2L, "marts-editor", "DEVELOPER");
        when(userRepository.findById(2L)).thenReturn(Optional.of(editor));

        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute("auth.sessionId", "sess-1");
        MockHttpServletResponse response = new MockHttpServletResponse();

        impersonationService.start(admin, 2L, request, response);

        verify(authSessionService).reissueAccessToken(response, "sess-1", admin, 2L);
    }

    @Test
    void applySwapsPrincipalFromAccessTokenClaimWithoutCookie() {
        User admin = user(1L, "admin", "ADMIN");
        User editor = user(2L, "marts-editor", "DEVELOPER");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(userRepository.findById(2L)).thenReturn(Optional.of(editor));
        when(jwtUtil.extractImpersonateUserId("admin.jwt.with.imp")).thenReturn(2L);
        UserDetails editorDetails = new org.springframework.security.core.userdetails.User(
            "marts-editor",
            "x",
            List.of(new SimpleGrantedAuthority("ROLE_DEVELOPER"), new SimpleGrantedAuthority("USE_CHAT"))
        );
        when(userDetailsService.loadUserByUsername("marts-editor")).thenReturn(editorDetails);

        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "admin",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
            )
        );

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/connections/abc/query");
        request.setServletPath("/connections/abc/query");
        request.addHeader("Authorization", "Bearer admin.jwt.with.imp");

        impersonationService.applyToRequest(request);

        assertEquals("marts-editor", SecurityContextHolder.getContext().getAuthentication().getName());
        assertTrue(ImpersonationContext.isActive());
        assertEquals("marts-editor", ImpersonationContext.current().orElseThrow().targetUsername());
    }

    @Test
    void applySwapsPrincipalToTargetUser() {
        User admin = user(1L, "admin", "ADMIN");
        User editor = user(2L, "marts-editor", "DEVELOPER");
        when(userRepository.findByUsername("admin")).thenReturn(Optional.of(admin));
        when(userRepository.findById(2L)).thenReturn(Optional.of(editor));
        UserDetails editorDetails = new org.springframework.security.core.userdetails.User(
            "marts-editor",
            "x",
            List.of(new SimpleGrantedAuthority("ROLE_DEVELOPER"), new SimpleGrantedAuthority("USE_CHAT"))
        );
        when(userDetailsService.loadUserByUsername("marts-editor")).thenReturn(editorDetails);

        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "admin",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
            )
        );

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/schema/objects");
        request.setServletPath("/schema/objects");
        request.setCookies(new Cookie("impersonate_user", "2"));

        impersonationService.applyToRequest(request);

        assertEquals("marts-editor", SecurityContextHolder.getContext().getAuthentication().getName());
        assertTrue(ImpersonationContext.isActive());
        assertEquals("admin", ImpersonationContext.current().orElseThrow().impersonatorUsername());
    }

    @Test
    void applySkipsImpersonationControlPlane() {
        authenticateAdmin();
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/admin/impersonate");
        request.setServletPath("/admin/impersonate");
        request.setCookies(new Cookie("impersonate_user", "2"));

        impersonationService.applyToRequest(request);

        assertEquals("admin", SecurityContextHolder.getContext().getAuthentication().getName());
        assertFalse(ImpersonationContext.isActive());
        verify(userRepository, never()).findById(2L);
    }

    @Test
    void applySkipsLogoutAndRefresh() {
        authenticateAdmin();
        MockHttpServletRequest logout = new MockHttpServletRequest("POST", "/api/auth/logout");
        logout.setServletPath("/auth/logout");
        logout.setCookies(new Cookie("impersonate_user", "2"));
        impersonationService.applyToRequest(logout);
        assertEquals("admin", SecurityContextHolder.getContext().getAuthentication().getName());

        MockHttpServletRequest refresh = new MockHttpServletRequest("POST", "/api/auth/refresh");
        refresh.setServletPath("/auth/refresh");
        refresh.setCookies(new Cookie("impersonate_user", "2"));
        impersonationService.applyToRequest(refresh);
        assertEquals("admin", SecurityContextHolder.getContext().getAuthentication().getName());
        verify(userDetailsService, never()).loadUserByUsername(any());
    }

    @Test
    void applySkipsMcpBearerTokens() {
        authenticateAdmin();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/connections");
        request.setServletPath("/connections");
        request.addHeader("Authorization", "Bearer dsql_mcp_abc.secret");
        request.setCookies(new Cookie("impersonate_user", "2"));

        impersonationService.applyToRequest(request);

        assertEquals("admin", SecurityContextHolder.getContext().getAuthentication().getName());
        assertFalse(ImpersonationContext.isActive());
    }

    @Test
    void applyDoesNotSwapForNonAdminSession() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "marts-editor",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_DEVELOPER"))
            )
        );
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/schema/objects");
        request.setServletPath("/schema/objects");
        request.setCookies(new Cookie("impersonate_user", "9"));

        impersonationService.applyToRequest(request);

        assertEquals("marts-editor", SecurityContextHolder.getContext().getAuthentication().getName());
        verify(userRepository, never()).findById(9L);
    }

    @Test
    void decorateAuthPayloadUsesActiveContext() {
        User admin = user(1L, "admin", "ADMIN");
        User editor = user(2L, "marts-editor", "DEVELOPER");
        ImpersonationContext.enter(new ImpersonationContext.State(admin, editor));

        Map<String, Object> payload = new java.util.LinkedHashMap<>();
        payload.put("username", "marts-editor");
        impersonationService.decorateAuthPayload(new MockHttpServletRequest(), editor, payload);

        assertEquals(Boolean.TRUE, payload.get("impersonating"));
        assertEquals("admin", payload.get("impersonatorUsername"));
    }

    @Test
    void listCandidatesExcludesAdminsSelfAndInactive() {
        User admin = user(1L, "admin", "ADMIN");
        User editor = user(2L, "marts-editor", "DEVELOPER");
        User otherAdmin = user(3L, "ops", "ADMIN");
        User locked = user(4L, "locked", "DEVELOPER");
        locked.setAccountStatus(UserAccountStatus.LOCKED.name());
        when(userRepository.findAll()).thenReturn(List.of(admin, editor, otherAdmin, locked));

        List<Map<String, Object>> candidates = impersonationService.listCandidates(admin);

        assertEquals(1, candidates.size());
        assertEquals("marts-editor", candidates.get(0).get("username"));
    }

    private void authenticateAdmin() {
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(
                "admin",
                null,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
            )
        );
    }

    private static User user(Long id, String username, String role) {
        User user = new User();
        user.setId(id);
        user.setUsername(username);
        user.setEmail(username + "@demo.local");
        user.setRole(role);
        user.setAccountStatus(UserAccountStatus.ACTIVE.name());
        user.setPassword("hashed");
        return user;
    }
}
