package com.dbaagent.controller;

import com.dbaagent.model.InviteType;
import com.dbaagent.model.Role;
import com.dbaagent.repository.UserRepository;
import com.dbaagent.service.SystemConfigService;
import com.dbaagent.service.UserInviteService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.InetAddress;
import java.util.Map;

@RestController
@RequestMapping("/admin/bootstrap")
@RequiredArgsConstructor
public class BootstrapController {
    private final UserRepository userRepository;
    private final UserInviteService userInviteService;
    private final SystemConfigService systemConfigService;

    @PostMapping("/link")
    public ResponseEntity<?> createBootstrapLink(@RequestBody Map<String, String> request, HttpServletRequest httpRequest) {
        if (!isLocalRequest(httpRequest)) {
            return ResponseEntity.status(403).body(Map.of("message", "Bootstrap link can only be created from localhost"));
        }
        if (userRepository.count() > 0 || systemConfigService.getBoolean("setup.complete")) {
            return ResponseEntity.status(409).body(Map.of("message", "Bootstrap is no longer available"));
        }
        String email = request.get("email");
        String username = request.get("username");
        String orgName = request.get("orgName");
        if (orgName != null && !orgName.isBlank()) {
            systemConfigService.set("setup.org.name", orgName.trim(), false, "Organization name");
        }
        try {
            var link = userInviteService.createInvite(
                email,
                username,
                Role.ADMIN,
                "bootstrap",
                InviteType.BOOTSTRAP,
                httpRequest.getRemoteAddr(),
                httpRequest.getHeader("User-Agent")
            );
            return ResponseEntity.ok(Map.of(
                "activationUrl", link.activationUrl(),
                "email", link.user().getEmail()
            ));
        } catch (IllegalArgumentException | IllegalStateException e) {
            return ResponseEntity.badRequest().body(Map.of("message", e.getMessage()));
        }
    }

    private boolean isLocalRequest(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (remoteAddr == null || remoteAddr.isBlank()) {
            return false;
        }
        if ("127.0.0.1".equals(remoteAddr) || "::1".equals(remoteAddr) || "0:0:0:0:0:0:0:1".equals(remoteAddr)) {
            return true;
        }
        try {
            return InetAddress.getByName(remoteAddr).isLoopbackAddress();
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            return false;
        }
    }
}
