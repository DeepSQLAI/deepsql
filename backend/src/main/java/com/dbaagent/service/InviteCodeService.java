package com.dbaagent.service;

import com.dbaagent.model.InviteCode;
import com.dbaagent.repository.InviteCodeRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class InviteCodeService {

    private static final String ALLOWED_CHARS = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 8;

    private final SecureRandom random = new SecureRandom();

    @Autowired
    private InviteCodeRepository inviteCodeRepository;

    /**
     * Generate a new invite code.
     * Format: 8 uppercase alphanumeric characters, excluding confusing characters (0, O, 1, I, L).
     */
    @Transactional
    public InviteCode generateCode(String createdBy, String note, Integer maxUses, LocalDateTime expiresAt) {
        String code = generateUniqueCode();

        InviteCode inviteCode = new InviteCode();
        inviteCode.setCode(code);
        inviteCode.setCreatedBy(createdBy);
        inviteCode.setNote(note);
        inviteCode.setMaxUses(maxUses != null ? maxUses : 1);
        inviteCode.setCurrentUses(0);
        inviteCode.setActive(true);
        inviteCode.setExpiresAt(expiresAt);
        inviteCode.setCreatedAt(LocalDateTime.now());

        return inviteCodeRepository.save(inviteCode);
    }

    /**
     * Generate a unique 8-character code.
     */
    private String generateUniqueCode() {
        String code;
        int attempts = 0;
        do {
            code = generateRandomCode();
            attempts++;
            if (attempts > 100) {
                throw new RuntimeException("Failed to generate unique invite code after 100 attempts");
            }
        } while (inviteCodeRepository.existsByCode(code));
        return code;
    }

    /**
     * Generate a random code string.
     */
    private String generateRandomCode() {
        StringBuilder sb = new StringBuilder(CODE_LENGTH);
        for (int i = 0; i < CODE_LENGTH; i++) {
            sb.append(ALLOWED_CHARS.charAt(random.nextInt(ALLOWED_CHARS.length())));
        }
        return sb.toString();
    }

    /**
     * Validate an invite code for signup.
     * Returns the InviteCode if valid, empty otherwise.
     */
    public Optional<InviteCode> validateCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }

        String normalizedCode = code.trim().toUpperCase();
        Optional<InviteCode> inviteCodeOpt = inviteCodeRepository.findByCode(normalizedCode);

        if (inviteCodeOpt.isEmpty()) {
            return Optional.empty();
        }

        InviteCode inviteCode = inviteCodeOpt.get();
        if (!inviteCode.isValid()) {
            return Optional.empty();
        }

        return Optional.of(inviteCode);
    }

    /**
     * Use an invite code (increment usage count).
     * Returns the updated InviteCode if successful, empty if the code is invalid or exhausted.
     */
    @Transactional
    public Optional<InviteCode> useCode(String code) {
        Optional<InviteCode> inviteCodeOpt = validateCode(code);
        if (inviteCodeOpt.isEmpty()) {
            return Optional.empty();
        }

        InviteCode inviteCode = inviteCodeOpt.get();
        if (!inviteCode.use()) {
            return Optional.empty();
        }

        return Optional.of(inviteCodeRepository.save(inviteCode));
    }

    /**
     * Deactivate an invite code.
     */
    @Transactional
    public Optional<InviteCode> deactivateCode(Long id) {
        Optional<InviteCode> inviteCodeOpt = inviteCodeRepository.findById(id);
        if (inviteCodeOpt.isEmpty()) {
            return Optional.empty();
        }

        InviteCode inviteCode = inviteCodeOpt.get();
        inviteCode.setActive(false);
        return Optional.of(inviteCodeRepository.save(inviteCode));
    }

    /**
     * Deactivate an invite code by code string.
     */
    @Transactional
    public Optional<InviteCode> deactivateCodeByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }

        String normalizedCode = code.trim().toUpperCase();
        Optional<InviteCode> inviteCodeOpt = inviteCodeRepository.findByCode(normalizedCode);
        if (inviteCodeOpt.isEmpty()) {
            return Optional.empty();
        }

        InviteCode inviteCode = inviteCodeOpt.get();
        inviteCode.setActive(false);
        return Optional.of(inviteCodeRepository.save(inviteCode));
    }

    /**
     * Get all invite codes, ordered by creation date (newest first).
     */
    public List<InviteCode> listAllCodes() {
        return inviteCodeRepository.findAllOrderByCreatedAtDesc();
    }

    /**
     * Get all currently valid invite codes.
     */
    public List<InviteCode> listValidCodes() {
        return inviteCodeRepository.findAllValid();
    }

    /**
     * Get an invite code by ID.
     */
    public Optional<InviteCode> getById(Long id) {
        return inviteCodeRepository.findById(id);
    }

    /**
     * Get an invite code by code string.
     */
    public Optional<InviteCode> getByCode(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        return inviteCodeRepository.findByCode(code.trim().toUpperCase());
    }
}
