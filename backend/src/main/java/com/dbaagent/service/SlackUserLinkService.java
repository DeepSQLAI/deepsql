package com.dbaagent.service;

import com.dbaagent.dto.SlackLinkCodeResponse;
import com.dbaagent.dto.SlackLinkedConnectionResponse;
import com.dbaagent.model.ConnectionOwnershipType;
import com.dbaagent.model.DatabaseConnection;
import com.dbaagent.model.SecurityEventOutcome;
import com.dbaagent.model.SecurityEventType;
import com.dbaagent.model.SlackLinkCode;
import com.dbaagent.model.SlackUserConnectionBinding;
import com.dbaagent.model.SlackUserLink;
import com.dbaagent.model.SlackUserLinkStatus;
import com.dbaagent.model.User;
import com.dbaagent.repository.SlackLinkCodeRepository;
import com.dbaagent.repository.SlackThreadSessionRepository;
import com.dbaagent.repository.SlackUserConnectionBindingRepository;
import com.dbaagent.repository.SlackUserLinkRepository;
import com.dbaagent.repository.UserRepository;
import com.dbaagent.security.EncryptionService;
import com.dbaagent.service.security.ConnectionAccessService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class SlackUserLinkService {

    private static final String LINKED = SlackUserLinkStatus.LINKED.name();
    private static final int CODE_LENGTH = 8;

    private final SlackLinkCodeRepository slackLinkCodeRepository;
    private final SlackUserLinkRepository slackUserLinkRepository;
    private final SlackUserConnectionBindingRepository slackUserConnectionBindingRepository;
    private final SlackThreadSessionRepository slackThreadSessionRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final EncryptionService encryptionService;
    private final CredentialService credentialService;
    private final ConnectionAccessService connectionAccessService;
    private final SecurityEventService securityEventService;

    @Transactional
    public SlackLinkCodeResponse createLinkCode(String username) {
        User user = userRepository.findByUsernameIgnoreCase(username)
            .orElseThrow(() -> new IllegalArgumentException("User not found"));
        String rawCode = generateCode();
        SlackLinkCode code = slackLinkCodeRepository.findFirstByDeepsqlUsernameOrderByCreatedAtDesc(user.getUsername())
            .orElseGet(SlackLinkCode::new);
        code.setDeepsqlUsername(user.getUsername());
        code.setCodeHash(passwordEncoder.encode(rawCode));
        code.setEncryptedCode(encryptionService.encrypt(rawCode, slackCodeAad(user.getUsername())));
        code.setExpiresAt(null);
        code.setConsumedAt(null);
        code.setCreatedAt(LocalDateTime.now());
        slackLinkCodeRepository.save(code);
        return toResponse(code, rawCode);
    }

    @Transactional(readOnly = true)
    public SlackLinkCodeResponse getCurrentLinkCode(String username) {
        User user = userRepository.findByUsernameIgnoreCase(username)
            .orElseThrow(() -> new IllegalArgumentException("User not found"));
        return slackLinkCodeRepository.findFirstByDeepsqlUsernameOrderByCreatedAtDesc(user.getUsername())
            .map(code -> toResponse(code, decryptCode(code)))
            .orElse(SlackLinkCodeResponse.builder()
                .code(null)
                .createdAt(null)
                .expiresAt(null)
                .build());
    }

    @Transactional
    public LinkedUser consumeLinkCode(String teamId, String slackUserId, String slackDisplayName, String code) {
        if (code == null || code.isBlank()) {
            throw new IllegalArgumentException("Slack access code is required");
        }
        SlackLinkCode linkCode = slackLinkCodeRepository.findAll().stream()
            .filter(candidate -> candidate.getEncryptedCode() != null && candidate.getEncryptedCode().length > 0)
            .filter(candidate -> candidate.getExpiresAt() == null || candidate.getExpiresAt().isAfter(LocalDateTime.now()))
            .filter(candidate -> passwordEncoder.matches(code.trim(), candidate.getCodeHash()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Slack access code is invalid or expired"));

        User user = userRepository.findByUsernameIgnoreCase(linkCode.getDeepsqlUsername())
            .orElseThrow(() -> new IllegalArgumentException("Linked DeepSQL user no longer exists"));

        SlackUserLink link = slackUserLinkRepository.findByTeamIdAndSlackUserId(teamId, slackUserId)
            .orElseGet(SlackUserLink::new);
        link.setTeamId(teamId);
        link.setSlackUserId(slackUserId);
        link.setSlackDisplayName(slackDisplayName);
        link.setDeepsqlUsername(user.getUsername());
        link.setLinkStatusEnum(SlackUserLinkStatus.LINKED);
        link.setLinkedAt(LocalDateTime.now());
        link.setLastVerifiedAt(LocalDateTime.now());
        link.setRevokedAt(null);
        slackUserLinkRepository.save(link);

        securityEventService.log(SecurityEventService.EventRequest.builder()
            .eventType(SecurityEventType.SLACK_ACCOUNT_LINKED)
            .outcome(SecurityEventOutcome.SUCCESS)
            .userId(user.getId())
            .email(user.getEmail())
            .targetResource("slack:" + teamId + ":" + slackUserId)
            .metadata(Map.of("slackDisplayName", slackDisplayName == null ? "" : slackDisplayName))
            .build());

        return new LinkedUser(user.getUsername(), user.isAdmin());
    }

    @Transactional(readOnly = true)
    public Optional<LinkedUser> resolveLinkedUser(String teamId, String slackUserId) {
        return slackUserLinkRepository.findByTeamIdAndSlackUserIdAndLinkStatus(teamId, slackUserId, LINKED)
            .flatMap(link -> userRepository.findByUsernameIgnoreCase(link.getDeepsqlUsername())
                .map(user -> new LinkedUser(user.getUsername(), user.isAdmin())));
    }

    @Transactional
    public void unlink(String teamId, String slackUserId) {
        slackUserLinkRepository.findByTeamIdAndSlackUserId(teamId, slackUserId).ifPresent(link -> {
            link.setLinkStatusEnum(SlackUserLinkStatus.REVOKED);
            link.setRevokedAt(LocalDateTime.now());
            slackUserLinkRepository.save(link);
            slackUserConnectionBindingRepository.deleteByTeamIdAndSlackUserId(teamId, slackUserId);
            slackThreadSessionRepository.deleteByTeamIdAndSlackUserId(teamId, slackUserId);
            securityEventService.log(SecurityEventService.EventRequest.builder()
                .eventType(SecurityEventType.SLACK_ACCOUNT_UNLINKED)
                .outcome(SecurityEventOutcome.SUCCESS)
                .email(link.getDeepsqlUsername())
                .targetResource("slack:" + teamId + ":" + slackUserId)
                .build());
        });
    }

    @Transactional(readOnly = true)
    public List<SlackLinkedConnectionResponse> listVisibleConnections(String username, boolean isAdmin) {
        return connectionAccessService.getVisibleConnections(username, isAdmin).stream()
            .map(connection -> {
                ConnectionAccessService.ResolvedConnectionAccess access = connectionAccessService.resolveAccess(connection, username, isAdmin);
                return SlackLinkedConnectionResponse.builder()
                    .connectionId(connection.getId())
                    .connectionName(connection.getConnectionName())
                    .accessLevel(access.getEffectiveAccess().name())
                    .ownershipType(access.getOwnershipType() == null ? ConnectionOwnershipType.ASSIGNED.name() : access.getOwnershipType().name())
                    .build();
            })
            .toList();
    }

    @Transactional
    public DatabaseConnection bindDefaultConnection(String teamId, String slackUserId, String username, boolean isAdmin, String reference) {
        DatabaseConnection connection = connectionAccessService.getVisibleConnections(username, isAdmin).stream()
            .filter(candidate -> reference != null && (
                reference.equalsIgnoreCase(candidate.getId()) || reference.equalsIgnoreCase(candidate.getConnectionName())
            ))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown connection for this Slack user"));

        SlackUserConnectionBinding binding = slackUserConnectionBindingRepository.findByTeamIdAndSlackUserId(teamId, slackUserId)
            .orElseGet(SlackUserConnectionBinding::new);
        binding.setTeamId(teamId);
        binding.setSlackUserId(slackUserId);
        binding.setDefaultConnectionId(connection.getId());
        slackUserConnectionBindingRepository.save(binding);

        securityEventService.log(SecurityEventService.EventRequest.builder()
            .eventType(SecurityEventType.SLACK_CONNECTION_BOUND)
            .outcome(SecurityEventOutcome.SUCCESS)
            .email(username)
            .targetResource("connection:" + connection.getId())
            .metadata(Map.of("teamId", teamId, "slackUserId", slackUserId))
            .build());
        return connection;
    }

    @Transactional(readOnly = true)
    public Optional<String> getDefaultConnectionId(String teamId, String slackUserId) {
        return slackUserConnectionBindingRepository.findByTeamIdAndSlackUserId(teamId, slackUserId)
            .map(SlackUserConnectionBinding::getDefaultConnectionId);
    }

    @Transactional
    public void clearDefaultConnection(String teamId, String slackUserId) {
        slackUserConnectionBindingRepository.deleteByTeamIdAndSlackUserId(teamId, slackUserId);
    }

    private String generateCode() {
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        SecureRandom secureRandom = new SecureRandom();
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            code.append(alphabet.charAt(secureRandom.nextInt(alphabet.length())));
        }
        return code.toString();
    }

    private SlackLinkCodeResponse toResponse(SlackLinkCode code, String rawCode) {
        return SlackLinkCodeResponse.builder()
            .code(rawCode)
            .createdAt(code.getCreatedAt())
            .expiresAt(code.getExpiresAt())
            .build();
    }

    private String decryptCode(SlackLinkCode code) {
        if (code == null || code.getEncryptedCode() == null || code.getEncryptedCode().length == 0) {
            return null;
        }
        return encryptionService.decrypt(code.getEncryptedCode(), slackCodeAad(code.getDeepsqlUsername()));
    }

    private String slackCodeAad(String username) {
        return "dba-agent:slack-link-code:" + (username == null ? "" : username.toLowerCase(Locale.ROOT));
    }

    public record LinkedUser(String username, boolean admin) {
    }
}
