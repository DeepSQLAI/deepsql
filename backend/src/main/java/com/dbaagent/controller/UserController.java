package com.dbaagent.controller;

import com.dbaagent.model.User;
import com.dbaagent.model.UserAccountStatus;
import com.dbaagent.repository.UserRepository;
import com.dbaagent.service.SystemConfigService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/users")
@RequiredArgsConstructor
@Slf4j
public class UserController {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final SystemConfigService systemConfigService;

    @Value("${security.admin.bootstrap.enabled:false}")
    private boolean adminBootstrapEnabled;

    @Value("${security.admin.bootstrap.secret:}")
    private String adminBootstrapSecret;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listUsers() {
        List<Map<String, Object>> users = userRepository.findAll().stream()
            .map(this::toUserSummary)
            .toList();
        return ResponseEntity.ok(users);
    }

    @PostMapping("/admin/bootstrap")
    public ResponseEntity<?> bootstrapAdmin(@RequestBody Map<String, String> request, HttpServletRequest httpRequest) {
        if (!adminBootstrapEnabled) {
            return ResponseEntity.status(403).body(Map.of("message", "Admin bootstrap is disabled"));
        }
        if (!isLocalRequest(httpRequest)) {
            return ResponseEntity.status(403).body(Map.of("message", "Admin bootstrap is only allowed from localhost"));
        }
        String headerSecret = httpRequest.getHeader("X-Admin-Bootstrap-Secret");
        if (!isBootstrapSecretValid(headerSecret)) {
            return ResponseEntity.status(403).body(Map.of("message", "Invalid bootstrap secret"));
        }
        if (userRepository.findByUsername("admin").isPresent()) {
            return ResponseEntity.ok(Map.of("message", "Admin already exists"));
        }
        String password = request.get("password");
        if (password == null || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Password is required"));
        }
        User user = new User();
        user.setUsername("admin");
        user.setPassword(passwordEncoder.encode(password));
        user.setEmail(request.get("email"));
        user.setRole("ADMIN");
        user.setEmailVerifiedAt(java.time.LocalDateTime.now());
        user.setAccountStatusEnum(UserAccountStatus.ACTIVE);
        userRepository.save(user);
        systemConfigService.set("setup.complete", "true", false, "Setup wizard completion flag");
        return ResponseEntity.ok(Map.of("message", "Admin created successfully"));
    }

    @PostMapping("/admin/reset")
    public ResponseEntity<?> resetAdmin(@RequestBody Map<String, String> request, HttpServletRequest httpRequest) {
        if (!adminBootstrapEnabled) {
            return ResponseEntity.status(403).body(Map.of("message", "Admin reset is disabled"));
        }
        if (!isLocalRequest(httpRequest)) {
            return ResponseEntity.status(403).body(Map.of("message", "Admin reset is only allowed from localhost"));
        }
        String headerSecret = httpRequest.getHeader("X-Admin-Bootstrap-Secret");
        if (!isBootstrapSecretValid(headerSecret)) {
            return ResponseEntity.status(403).body(Map.of("message", "Invalid bootstrap secret"));
        }
        String password = request.get("password");
        if (password == null || password.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Password is required"));
        }
        userRepository.findByUsername("admin").ifPresent(userRepository::delete);
        User user = new User();
        user.setUsername("admin");
        user.setPassword(passwordEncoder.encode(password));
        user.setEmail(request.get("email"));
        user.setRole("ADMIN");
        user.setEmailVerifiedAt(java.time.LocalDateTime.now());
        user.setAccountStatusEnum(UserAccountStatus.ACTIVE);
        userRepository.save(user);
        systemConfigService.set("setup.complete", "true", false, "Setup wizard completion flag");
        return ResponseEntity.ok(Map.of("message", "Admin reset successfully"));
    }

    @DeleteMapping("/{username}")
    public ResponseEntity<?> deleteUser(@PathVariable String username) {
        log.info("Delete user requested: {}", username);
        if ("admin".equalsIgnoreCase(username)) {
            return ResponseEntity.status(403).body(Map.of("message", "Reserved user cannot be deleted via this endpoint"));
        }
        Optional<User> userOpt = userRepository.findByUsername(username);
        if (userOpt.isEmpty()) {
            return ResponseEntity.status(404).body(Map.of("message", "User not found"));
        }
        userRepository.delete(userOpt.get());
        return ResponseEntity.ok(Map.of("message", "User deleted successfully"));
    }

    private Map<String, Object> toUserSummary(User user) {
        Map<String, Object> summary = new java.util.LinkedHashMap<>();
        summary.put("id", user.getId());
        summary.put("username", user.getUsername());
        summary.put("email", user.getEmail());
        summary.put("role", user.getRole());
        return summary;
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
            InetAddress address = InetAddress.getByName(remoteAddr);
            return address.isLoopbackAddress();
        } catch (org.springframework.web.server.ResponseStatusException e) {
            throw e;
        } catch (Exception e) {
            return false;
        }
    }

    private boolean isBootstrapSecretValid(String headerSecret) {
        if (adminBootstrapSecret == null || adminBootstrapSecret.isBlank()) {
            return false;
        }
        if (headerSecret == null) {
            return false;
        }
        return adminBootstrapSecret.equals(headerSecret);
    }
}
