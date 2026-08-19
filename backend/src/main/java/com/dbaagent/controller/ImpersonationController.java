package com.dbaagent.controller;

import com.dbaagent.model.Role;
import com.dbaagent.model.User;
import com.dbaagent.repository.UserRepository;
import com.dbaagent.security.ImpersonationContext;
import com.dbaagent.service.ImpersonationService;
import com.dbaagent.service.PermissionService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Admin-only profile switch. These paths are excluded from the impersonation
 * overlay so the caller stays the real administrator while starting, listing,
 * or stopping a switch.
 */
@RestController
@RequestMapping("/admin/impersonate")
@PreAuthorize("hasRole('ADMIN')")
@RequiredArgsConstructor
public class ImpersonationController {

    private final ImpersonationService impersonationService;
    private final UserRepository userRepository;
    private final PermissionService permissionService;

    @GetMapping
    public ResponseEntity<Map<String, Object>> status(HttpServletRequest request) {
        User actor = currentAdmin();
        ImpersonationContext.State state = impersonationService.resolveFromCookie(request, actor).orElse(null);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("impersonating", state != null);
        body.put("impersonator", Map.of(
            "id", actor.getId(),
            "username", actor.getUsername(),
            "email", actor.getEmail()
        ));
        body.put("target", state == null ? null : candidateView(state.target()));
        body.put("candidates", impersonationService.listCandidates(actor));
        return ResponseEntity.ok(body);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> start(
        @RequestBody Map<String, Object> requestBody,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        User actor = currentAdmin();
        Long userId = readUserId(requestBody);
        ImpersonationContext.State state = impersonationService.start(actor, userId, request, response);
        return ResponseEntity.ok(toAuthPayload(state.target(), actor));
    }

    @DeleteMapping
    public ResponseEntity<Map<String, Object>> stop(
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        User actor = currentAdmin();
        User restored = impersonationService.stop(actor, request, response);
        Map<String, Object> payload = toAuthPayload(restored, null);
        payload.put("impersonating", false);
        return ResponseEntity.ok(payload);
    }

    private Map<String, Object> toAuthPayload(User user, User impersonator) {
        Role role = user.getRoleEnum();
        Set<String> permissions = permissionService.getEffectivePermissionCodes(role);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("username", user.getUsername());
        payload.put("email", user.getEmail());
        payload.put("role", role.name());
        payload.put("permissions", permissions);
        payload.put("emailVerified", user.isEmailVerified());
        payload.put("accountStatus", user.getAccountStatus());
        if (impersonator != null) {
            payload.put("impersonating", true);
            payload.put("impersonatorUsername", impersonator.getUsername());
            payload.put("impersonatorEmail", impersonator.getEmail());
        } else {
            payload.put("impersonating", false);
        }
        return payload;
    }

    private Map<String, Object> candidateView(User user) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", user.getId());
        dto.put("username", user.getUsername());
        dto.put("email", user.getEmail());
        dto.put("role", user.getRole());
        dto.put("accountStatus", user.getAccountStatus());
        return dto;
    }

    private Long readUserId(Map<String, Object> requestBody) {
        if (requestBody == null || requestBody.get("userId") == null) {
            return null;
        }
        Object raw = requestBody.get("userId");
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(raw).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private User currentAdmin() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "Not authenticated");
        }
        return userRepository.findByUsername(auth.getName())
            .orElseThrow(() -> new ResponseStatusException(org.springframework.http.HttpStatus.UNAUTHORIZED, "User not found"));
    }
}
