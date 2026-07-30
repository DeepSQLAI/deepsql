package com.dbaagent.service;

import com.dbaagent.model.CliAuthorization;
import com.dbaagent.model.CliDeviceCode;
import com.dbaagent.model.McpToken;
import com.dbaagent.model.User;
import com.dbaagent.repository.CliAuthorizationRepository;
import com.dbaagent.repository.CliDeviceCodeRepository;
import com.dbaagent.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("CliAuthorizationService unit tests")
class CliAuthorizationServiceTest {

    @Mock
    private CliAuthorizationRepository cliAuthorizationRepository;
    @Mock
    private CliDeviceCodeRepository cliDeviceCodeRepository;
    @Mock
    private McpTokenService mcpTokenService;
    @Mock
    private UserRepository userRepository;

    private CliAuthorizationService service;

    @BeforeEach
    void setUp() {
        service = new CliAuthorizationService(
            cliAuthorizationRepository, cliDeviceCodeRepository, mcpTokenService, userRepository
        );
        ReflectionTestUtils.setField(service, "appBaseUrl", "http://localhost:3000");
        ReflectionTestUtils.setField(service, "authEnabled", true);
    }

    @Nested
    @DisplayName("Browser flow")
    class BrowserFlow {

        @Test
        @DisplayName("rejects non-loopback redirect URIs")
        void rejectsNonLoopbackRedirect() {
            assertThatThrownBy(() -> service.startBrowserAuthorization(
                "https://evil.example.com/cb", "challenge", "state", "host", "label"
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects localhost host (RFC 8252 §7.3 — must be literal IP)")
        void rejectsLocalhostHost() {
            assertThatThrownBy(() -> service.startBrowserAuthorization(
                "http://localhost:54321/cb", "challenge", "state", "host", "label"
            )).isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        @DisplayName("rejects exchange when PKCE verifier doesn't match challenge")
        void rejectsBadVerifier() {
            String verifier = "a-good-verifier";
            String wrongVerifier = "a-bad-verifier";
            String challenge = pkceChallenge(verifier);

            String code = "the-code";
            String codeHash = sha256(code);

            CliAuthorization auth = approvedAuth(challenge, codeHash);
            when(cliAuthorizationRepository.findByAuthorizationId("auth-123"))
                .thenReturn(Optional.of(auth));

            assertThatThrownBy(() -> service.exchange("auth-123", code, wrongVerifier))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PKCE");

            verify(mcpTokenService, never()).createTokenForUser(anyString(), anyString(), any());
        }

        @Test
        @DisplayName("issues a token on a successful exchange and marks consumed")
        void exchangeIssuesToken() {
            String verifier = "verifier-rfc7636";
            String challenge = pkceChallenge(verifier);
            String code = "code-good";

            CliAuthorization auth = approvedAuth(challenge, sha256(code));
            auth.setApprovedByUserId(7L);
            when(cliAuthorizationRepository.findByAuthorizationId("auth-123"))
                .thenReturn(Optional.of(auth));

            User approver = new User();
            approver.setId(7L);
            approver.setUsername("alice");
            when(userRepository.findById(7L)).thenReturn(Optional.of(approver));

            McpToken token = new McpToken();
            token.setId(42L);
            when(mcpTokenService.createTokenForUser(eq("alice"), anyString(), eq(null)))
                .thenReturn(new McpTokenService.CreatedToken(token, "dsql_mcp_x.y"));

            CliAuthorizationService.IssuedToken issued = service.exchange("auth-123", code, verifier);

            assertThat(issued.token()).isEqualTo("dsql_mcp_x.y");
            assertThat(issued.username()).isEqualTo("alice");
            assertThat(auth.getStatus()).isEqualTo(CliAuthorization.Status.CONSUMED);
        }

        @Test
        @DisplayName("rejects exchange replay after consumption")
        void rejectsReplay() {
            String verifier = "verifier-replay";
            String challenge = pkceChallenge(verifier);
            String code = "code-replay";

            CliAuthorization auth = approvedAuth(challenge, sha256(code));
            auth.setStatus(CliAuthorization.Status.CONSUMED);
            when(cliAuthorizationRepository.findByAuthorizationId("auth-123"))
                .thenReturn(Optional.of(auth));

            assertThatThrownBy(() -> service.exchange("auth-123", code, verifier))
                .isInstanceOf(IllegalStateException.class);
        }
    }

    @Nested
    @DisplayName("Verification URLs honor app.base-url")
    class VerificationUrls {

        @Test
        @DisplayName("device flow uses configured appBaseUrl for verification_uri")
        void deviceVerificationUriUsesConfiguredHost() {
            ReflectionTestUtils.setField(service, "appBaseUrl", "https://deepsql.stayflexi.com");

            CliAuthorizationService.StartedDeviceCode started =
                service.startDeviceFlow("host", "label");

            assertThat(started.verificationUri())
                .isEqualTo("https://deepsql.stayflexi.com/cli-authorize/device");
        }

        @Test
        @DisplayName("device flow strips trailing slashes from appBaseUrl")
        void deviceVerificationUriStripsTrailingSlash() {
            ReflectionTestUtils.setField(service, "appBaseUrl", "https://deepsql.acme-customer.example//");

            CliAuthorizationService.StartedDeviceCode started =
                service.startDeviceFlow("host", "label");

            assertThat(started.verificationUri())
                .isEqualTo("https://deepsql.acme-customer.example/cli-authorize/device");
        }

        @Test
        @DisplayName("browser flow uses configured appBaseUrl for authorize_url")
        void browserAuthorizeUrlUsesConfiguredHost() {
            // Use a customer-shaped URL, not our hosted demo — this assertion
            // is the contract that any host configured via `app.base-url`
            // (or APP_BASE_URL env var) flows through to authorize_url
            // verbatim. The CLI then opens whatever this returns, so any
            // leak between configuration and output is a security bug.
            ReflectionTestUtils.setField(service, "appBaseUrl", "https://deepsql.acme-customer.example");
            String challenge = pkceChallenge("verifier-abc");

            CliAuthorizationService.StartedAuthorization started = service.startBrowserAuthorization(
                "http://127.0.0.1:54321/cb", challenge, "state-1", "host", "label"
            );

            assertThat(started.authorizeUrl())
                .startsWith("https://deepsql.acme-customer.example/cli-authorize?id=");
        }

        @Test
        @DisplayName("self-host safety: authorize_url honors a localhost / port-forwarded base URL")
        void browserAuthorizeUrlHonorsLocalhostBase() {
            // The reported regression: customer's CLI on a laptop port-
            // forwarded to a VM, base-url was http://localhost:8081 but the
            // CLI opened https://deepsql.stayflexi.com. Root cause was a
            // hardcoded prod-properties default, but lock the service-level
            // contract here too: anything passed in via `app.base-url`
            // must be exactly what comes out in authorize_url, including
            // localhost-shaped URLs that a self-host customer would use
            // before they put a real DNS name in front of DeepSQL.
            ReflectionTestUtils.setField(service, "appBaseUrl", "http://localhost:8081");
            String challenge = pkceChallenge("verifier-localhost");

            CliAuthorizationService.StartedAuthorization started = service.startBrowserAuthorization(
                "http://127.0.0.1:55555/cb", challenge, "state-localhost", "host", "label"
            );

            assertThat(started.authorizeUrl())
                .startsWith("http://localhost:8081/cli-authorize?id=")
                .doesNotContain("stayflexi");
        }
    }

    @Nested
    @DisplayName("Device flow")
    class DeviceFlow {

        @Test
        @DisplayName("returns AUTHORIZATION_PENDING when not yet approved")
        void returnsPendingWhenNotApproved() {
            CliDeviceCode row = buildPendingDevice();
            when(cliDeviceCodeRepository.findAll()).thenReturn(List.of(row));

            CliAuthorizationService.DevicePollResult result = service.pollDevice("device-x");

            assertThat(result.outcome())
                .isEqualTo(CliAuthorizationService.DevicePollOutcome.AUTHORIZATION_PENDING);
            assertThat(result.issuedToken()).isNull();
        }

        @Test
        @DisplayName("returns SLOW_DOWN when polled inside the interval window")
        void slowDownEnforced() {
            CliDeviceCode row = buildPendingDevice();
            row.setLastPolledAt(LocalDateTime.now().minusSeconds(1));
            when(cliDeviceCodeRepository.findAll()).thenReturn(List.of(row));

            CliAuthorizationService.DevicePollResult result = service.pollDevice("device-x");

            assertThat(result.outcome())
                .isEqualTo(CliAuthorizationService.DevicePollOutcome.SLOW_DOWN);
        }

        @Test
        @DisplayName("issues a token when status flips to APPROVED")
        void approvedIssuesToken() {
            CliDeviceCode row = buildPendingDevice();
            row.setStatus(CliDeviceCode.Status.APPROVED);
            row.setApprovedByUserId(11L);
            when(cliDeviceCodeRepository.findAll()).thenReturn(List.of(row));

            User user = new User();
            user.setId(11L);
            user.setUsername("bob");
            when(userRepository.findById(11L)).thenReturn(Optional.of(user));

            McpToken token = new McpToken();
            token.setId(99L);
            when(mcpTokenService.createTokenForUser(eq("bob"), anyString(), eq(null)))
                .thenReturn(new McpTokenService.CreatedToken(token, "dsql_mcp_a.b"));

            CliAuthorizationService.DevicePollResult result = service.pollDevice("device-x");

            assertThat(result.outcome())
                .isEqualTo(CliAuthorizationService.DevicePollOutcome.APPROVED);
            assertThat(result.issuedToken().token()).isEqualTo("dsql_mcp_a.b");
            assertThat(row.getStatus()).isEqualTo(CliDeviceCode.Status.CONSUMED);
        }
    }

    private CliAuthorization approvedAuth(String challenge, String codeHash) {
        CliAuthorization auth = new CliAuthorization();
        auth.setAuthorizationId("auth-123");
        auth.setStatus(CliAuthorization.Status.APPROVED);
        auth.setCodeChallengeHash(challenge);
        auth.setCodeHash(codeHash);
        auth.setExpiresAt(LocalDateTime.now().plusMinutes(5));
        auth.setRedirectUri("http://127.0.0.1:54321/cb");
        return auth;
    }

    private CliDeviceCode buildPendingDevice() {
        CliDeviceCode row = new CliDeviceCode();
        row.setDeviceCodeHash(sha256("device-x"));
        row.setUserCodeHash(sha256("ABCD1234"));
        row.setUserCodePrefix("ABCD");
        row.setIntervalSeconds(5);
        row.setStatus(CliDeviceCode.Status.PENDING);
        row.setExpiresAt(LocalDateTime.now().plusMinutes(15));
        return row;
    }

    private static String pkceChallenge(String verifier) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return Base64.getUrlEncoder().withoutPadding()
                .encodeToString(md.digest(verifier.getBytes()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String sha256(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return Base64.getUrlEncoder().withoutPadding().encodeToString(md.digest(input.getBytes()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
