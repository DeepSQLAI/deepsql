package com.dbaagent.controller;

import com.dbaagent.service.CliAuthorizationService;
import com.dbaagent.service.security.AccessControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * REST surface for the {@code deepsql} CLI's first-time authorization flows.
 *
 * <p>Endpoint summary:
 * <pre>
 *   POST /auth/cli/authorize        — start PKCE flow (public)
 *   POST /auth/cli/exchange         — exchange code+verifier for token (public)
 *   POST /auth/cli/device/code      — start device flow (public)
 *   POST /auth/cli/device/token     — poll for device token (public)
 *   GET  /auth/cli/pending/{id}     — fetch pending authorization (auth)
 *   POST /auth/cli/approve          — approve a pending authorization (auth)
 *   POST /auth/cli/deny             — deny a pending authorization (auth)
 *   GET  /auth/cli/device/pending   — look up device code by user_code (auth)
 *   POST /auth/cli/device/approve   — approve device code (auth)
 *   POST /auth/cli/device/deny      — deny device code (auth)
 * </pre>
 */
@RestController
@RequestMapping("/auth/cli")
@RequiredArgsConstructor
public class AuthCliController {

    private final CliAuthorizationService cliAuthorizationService;
    private final AccessControlService accessControlService;

    // -------- Browser / PKCE flow --------

    @PostMapping("/authorize")
    public ResponseEntity<Map<String, Object>> authorize(@RequestBody AuthorizeRequest request) {
        try {
            CliAuthorizationService.StartedAuthorization started =
                cliAuthorizationService.startBrowserAuthorization(
                    request.redirectUri(),
                    request.codeChallenge(),
                    request.state(),
                    request.hostname(),
                    request.clientLabel()
                );
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("authorization_id", started.authorizationId());
            body.put("authorize_url", started.authorizeUrl());
            body.put("expires_at", started.expiresAt());
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        }
    }

    @PostMapping("/exchange")
    public ResponseEntity<Map<String, Object>> exchange(@RequestBody ExchangeRequest request) {
        try {
            CliAuthorizationService.IssuedToken issued = cliAuthorizationService.exchange(
                request.authorizationId(), request.code(), request.codeVerifier()
            );
            return ResponseEntity.ok(tokenResponse(issued));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, e.getMessage());
        }
    }

    @GetMapping("/pending/{authorizationId}")
    public ResponseEntity<Map<String, Object>> getPending(@PathVariable String authorizationId) {
        requireAuthenticatedUser();
        Optional<CliAuthorizationService.PendingAuthorization> pending =
            cliAuthorizationService.lookupPending(authorizationId);
        if (pending.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Authorization not found or expired");
        }
        CliAuthorizationService.PendingAuthorization p = pending.get();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("authorization_id", p.authorizationId());
        body.put("hostname", p.hostname());
        body.put("client_label", p.clientLabel());
        body.put("expires_at", p.expiresAt());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/approve")
    public ResponseEntity<Map<String, Object>> approve(@RequestBody ApproveRequest request) {
        String username = requireAuthenticatedUser();
        try {
            CliAuthorizationService.ApprovedAuthorization approved =
                cliAuthorizationService.approve(request.authorizationId(), username);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("authorization_id", approved.authorizationId());
            body.put("code", approved.code());
            body.put("redirect_uri", approved.redirectUri());
            body.put("state", approved.state());
            return ResponseEntity.ok(body);
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, e.getMessage());
        }
    }

    @PostMapping("/deny")
    public ResponseEntity<Map<String, Object>> deny(@RequestBody DenyRequest request) {
        requireAuthenticatedUser();
        cliAuthorizationService.deny(request.authorizationId());
        return ResponseEntity.ok(Map.of("status", "denied"));
    }

    // -------- Device flow --------

    @PostMapping("/device/code")
    public ResponseEntity<Map<String, Object>> deviceCode(@RequestBody DeviceCodeRequest request) {
        CliAuthorizationService.StartedDeviceCode started =
            cliAuthorizationService.startDeviceFlow(request.hostname(), request.clientLabel());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("device_code", started.deviceCode());
        body.put("user_code", started.userCode());
        body.put("verification_uri", started.verificationUri());
        body.put("interval", started.intervalSeconds());
        body.put("expires_in", started.expiresInSeconds());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/device/token")
    public ResponseEntity<Map<String, Object>> deviceToken(@RequestBody DeviceTokenRequest request) {
        CliAuthorizationService.DevicePollResult result;
        try {
            result = cliAuthorizationService.pollDevice(request.deviceCode());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, e.getMessage());
        } catch (IllegalStateException e) {
            throw new ResponseStatusException(HttpStatus.GONE, e.getMessage());
        }

        return switch (result.outcome()) {
            case AUTHORIZATION_PENDING -> ResponseEntity.status(HttpStatus.PRECONDITION_REQUIRED)
                .body(Map.of("error", "authorization_pending"));
            case SLOW_DOWN -> ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .body(Map.of("error", "slow_down"));
            case DENIED -> ResponseEntity.status(HttpStatus.FORBIDDEN)
                .body(Map.of("error", "access_denied"));
            case EXPIRED -> ResponseEntity.status(HttpStatus.GONE)
                .body(Map.of("error", "expired_token"));
            case APPROVED -> ResponseEntity.ok(tokenResponse(result.issuedToken()));
        };
    }

    @GetMapping("/device/pending")
    public ResponseEntity<Map<String, Object>> deviceLookup(@RequestParam("user_code") String userCode) {
        requireAuthenticatedUser();
        Optional<CliAuthorizationService.PendingDeviceCode> pending =
            cliAuthorizationService.lookupPendingDevice(userCode);
        if (pending.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Device code not found or expired");
        }
        CliAuthorizationService.PendingDeviceCode p = pending.get();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", p.id());
        body.put("hostname", p.hostname());
        body.put("client_label", p.clientLabel());
        body.put("expires_at", p.expiresAt());
        return ResponseEntity.ok(body);
    }

    @PostMapping("/device/approve")
    public ResponseEntity<Map<String, Object>> deviceApprove(@RequestBody DeviceApproveRequest request) {
        String username = requireAuthenticatedUser();
        try {
            cliAuthorizationService.approveDevice(request.userCode(), username);
            return ResponseEntity.ok(Map.of("status", "approved"));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, e.getMessage());
        }
    }

    @PostMapping("/device/deny")
    public ResponseEntity<Map<String, Object>> deviceDeny(@RequestBody DeviceApproveRequest request) {
        requireAuthenticatedUser();
        cliAuthorizationService.denyDevice(request.userCode());
        return ResponseEntity.ok(Map.of("status", "denied"));
    }

    // -------- Helpers --------

    private String requireAuthenticatedUser() {
        String username = accessControlService.getCurrentUsername();
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Login required to approve CLI authorization");
        }
        return username;
    }

    private Map<String, Object> tokenResponse(CliAuthorizationService.IssuedToken issued) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("token", issued.token());
        body.put("token_id", issued.tokenId());
        body.put("username", issued.username());
        body.put("expires_at", issued.expiresAt() == null ? null : issued.expiresAt().toString());
        body.put("issued_at", LocalDateTime.now().toString());
        return body;
    }

    public record AuthorizeRequest(
        String redirectUri,
        String codeChallenge,
        String state,
        String hostname,
        String clientLabel
    ) {}

    public record ExchangeRequest(
        String authorizationId,
        String code,
        String codeVerifier
    ) {}

    public record ApproveRequest(String authorizationId) {}

    public record DenyRequest(String authorizationId) {}

    public record DeviceCodeRequest(String hostname, String clientLabel) {}

    public record DeviceTokenRequest(String deviceCode) {}

    public record DeviceApproveRequest(String userCode) {}
}
