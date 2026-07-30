package com.dbaagent.service;

import com.dbaagent.model.*;
import com.dbaagent.repository.UserInviteRepository;
import com.dbaagent.repository.UserRepository;
import com.dbaagent.util.SecurityHashUtil;
import jakarta.mail.MessagingException;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserInviteService {
    private final UserInviteRepository userInviteRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;
    private final SecurityEventService securityEventService;
    private final SystemConfigService systemConfigService;

    @Value("${app.public-url:http://localhost:3000}")
    private String publicUrl;

    @Value("${security.invite.ttl-hours:48}")
    private long inviteTtlHours;

    @Value("${security.bootstrap.ttl-minutes:30}")
    private long bootstrapTtlMinutes;

    @Transactional
    public InviteLink createInvite(
        String email,
        String requestedUsername,
        Role role,
        String invitedBy,
        InviteType inviteType,
        String clientIp,
        String userAgent
    ) {
        String normalizedEmail = normalizeEmail(email);
        if (normalizedEmail == null) {
            throw new IllegalArgumentException("Email is required");
        }

        Optional<User> existingUser = userRepository.findByEmailIgnoreCase(normalizedEmail);
        if (existingUser.isPresent() && existingUser.get().getAccountStatusEnum() == UserAccountStatus.ACTIVE && inviteType == InviteType.STANDARD) {
            throw new IllegalArgumentException("A user with this email already exists");
        }

        User user = existingUser.orElseGet(User::new);
        if (user.getId() == null) {
            user.setEmail(normalizedEmail);
            user.setUsername(resolveUsername(requestedUsername, normalizedEmail));
            user.setPassword(passwordEncoder.encode(UUID.randomUUID().toString()));
        } else if (requestedUsername != null && !requestedUsername.isBlank() && !matchesUsername(user.getUsername(), requestedUsername)) {
            user.setUsername(resolveUsername(requestedUsername, normalizedEmail));
        }
        user.setRole(role.name());
        user.setAccountStatusEnum(UserAccountStatus.PENDING_INVITE);
        user.setEmailVerifiedAt(null);
        user.setInvitedAt(LocalDateTime.now());
        userRepository.save(user);

        userInviteRepository.findTopByUserIdAndAcceptedAtIsNullAndRevokedAtIsNullOrderByCreatedAtDesc(user.getId())
            .ifPresent(existing -> {
                existing.setRevokedAt(LocalDateTime.now());
                userInviteRepository.save(existing);
            });

        String rawToken = UUID.randomUUID() + "." + UUID.randomUUID();
        UserInvite invite = new UserInvite();
        invite.setEmail(normalizedEmail);
        invite.setUsername(user.getUsername());
        invite.setRole(role.name());
        invite.setUserId(user.getId());
        invite.setTokenHash(SecurityHashUtil.sha256Hex(rawToken));
        invite.setInvitedBy(invitedBy);
        invite.setInviteTypeEnum(inviteType);
        invite.setExpiresAt(LocalDateTime.now().plus(inviteType == InviteType.BOOTSTRAP
            ? java.time.Duration.ofMinutes(bootstrapTtlMinutes)
            : java.time.Duration.ofHours(inviteTtlHours)));
        userInviteRepository.save(invite);

        String activationUrl = buildActivationUrl(rawToken);
        if (inviteType == InviteType.STANDARD) {
            try {
                emailService.sendInviteEmail(normalizedEmail, activationUrl, role.name(), false);
            } catch (MessagingException e) {
                throw new IllegalStateException("Failed to send invite email", e);
            }
        }

        securityEventService.log(SecurityEventService.EventRequest.builder()
            .eventType(inviteType == InviteType.BOOTSTRAP ? SecurityEventType.BOOTSTRAP_LINK_CREATED : SecurityEventType.INVITE_CREATED)
            .outcome(SecurityEventOutcome.SUCCESS)
            .userId(user.getId())
            .email(normalizedEmail)
            .targetResource("user:" + user.getId())
            .clientIp(clientIp)
            .userAgent(userAgent)
            .metadata(Map.of("inviteType", inviteType.name(), "role", role.name()))
            .build());

        return InviteLink.builder()
            .user(user)
            .invite(invite)
            .rawToken(rawToken)
            .activationUrl(activationUrl)
            .build();
    }

    @Transactional(readOnly = true)
    public Optional<UserInvite> findUsableInviteByRawToken(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) {
            return Optional.empty();
        }
        String tokenHash = SecurityHashUtil.sha256Hex(rawToken);
        return userInviteRepository.findByTokenHash(tokenHash)
            .filter(UserInvite::isUsable);
    }

    @Transactional
    public ActivatedInvite acceptInvite(
        String rawToken,
        String requestedUsername,
        String clientIp,
        String userAgent
    ) {
        UserInvite invite = findUsableInviteByRawToken(rawToken)
            .orElseThrow(() -> new IllegalArgumentException("Invalid or expired invite"));
        User user = userRepository.findById(invite.getUserId())
            .orElseThrow(() -> new IllegalArgumentException("User not found"));

        if (requestedUsername != null && !requestedUsername.isBlank() && !matchesUsername(user.getUsername(), requestedUsername)) {
            user.setUsername(resolveUsername(requestedUsername, user.getEmail()));
        }
        user.setRole(invite.getRole());
        user.setAccountStatusEnum(UserAccountStatus.ACTIVE);
        user.setEmailVerifiedAt(LocalDateTime.now());
        userRepository.save(user);

        invite.setAcceptedAt(LocalDateTime.now());
        userInviteRepository.save(invite);
        if (invite.getInviteTypeEnum() == InviteType.BOOTSTRAP) {
            systemConfigService.set("setup.complete", "true", false, "Setup wizard completion flag");
        }

        securityEventService.log(SecurityEventService.EventRequest.builder()
            .eventType(invite.getInviteTypeEnum() == InviteType.BOOTSTRAP ? SecurityEventType.BOOTSTRAP_COMPLETED : SecurityEventType.INVITE_ACCEPTED)
            .outcome(SecurityEventOutcome.SUCCESS)
            .userId(user.getId())
            .email(user.getEmail())
            .targetResource("user:" + user.getId())
            .clientIp(clientIp)
            .userAgent(userAgent)
            .metadata(Map.of("inviteType", invite.getInviteType(), "role", user.getRole()))
            .build());

        return ActivatedInvite.builder()
            .user(user)
            .invite(invite)
            .build();
    }

    private String buildActivationUrl(String rawToken) {
        return publicUrl.replaceAll("/+$", "") + "/activate?token=" + rawToken;
    }

    private String resolveUsername(String requestedUsername, String email) {
        String base = requestedUsername != null && !requestedUsername.isBlank()
            ? requestedUsername.trim()
            : email.substring(0, email.indexOf('@'));
        String normalized = base.replaceAll("[^A-Za-z0-9._-]", "").trim();
        if (normalized.isBlank()) {
            normalized = "user";
        }
        String candidate = normalized;
        int suffix = 1;
        while (userRepository.findByUsername(candidate).isPresent()) {
            candidate = normalized + suffix++;
        }
        return candidate;
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private boolean matchesUsername(String left, String right) {
        if (left == null || right == null) {
            return false;
        }
        return left.trim().equalsIgnoreCase(right.trim());
    }

    @Builder
    public record InviteLink(
        User user,
        UserInvite invite,
        String rawToken,
        String activationUrl
    ) {
    }

    @Builder
    public record ActivatedInvite(
        User user,
        UserInvite invite
    ) {
    }
}
