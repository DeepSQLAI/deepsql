package com.dbaagent.service;

import com.dbaagent.model.SystemConfig;
import com.dbaagent.repository.SystemConfigRepository;
import com.dbaagent.security.EncryptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.Optional;

/**
 * Runtime key-value configuration store backed by the vault database.
 *
 * <p>Sensitive values (API keys, secrets) are AES-GCM encrypted before
 * persistence. Non-sensitive values are stored as-is.
 *
 * <p>Used by the onboarding wizard and by dynamic config beans (e.g.
 * {@code RefreshableChatModel}) that read LLM credentials at call-time
 * rather than at startup, enabling zero-restart key rotation.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SystemConfigService {

    private final SystemConfigRepository repository;
    private final EncryptionService encryptionService;

    // ── Write ─────────────────────────────────────────────────────────────────

    /**
     * Persist a configuration value.
     *
     * @param key         configuration key (e.g. {@code "llm.openai.api-key"})
     * @param value       plaintext value (will be encrypted when {@code sensitive=true})
     * @param sensitive   encrypt the value using AES-GCM before storing
     * @param description human-readable description (optional, ignored on update)
     */
    @Transactional
    public void set(String key, String value, boolean sensitive, String description) {
        String stored = sensitive ? encrypt(value, key) : value;

        SystemConfig config = repository.findById(key).orElse(new SystemConfig());
        config.setKey(key);
        config.setValueData(stored);
        config.setSensitive(sensitive);
        if (description != null && config.getDescription() == null) {
            config.setDescription(description);
        }
        repository.save(config);
        log.debug("SystemConfig: set key='{}' sensitive={}", key, sensitive);
    }

    @Transactional
    public void set(String key, String value, boolean sensitive) {
        set(key, value, sensitive, null);
    }

    // ── Read ──────────────────────────────────────────────────────────────────

    /** Returns the value for {@code key}, decrypting it if sensitive. */
    public Optional<String> get(String key) {
        return repository.findById(key)
                .map(c -> {
                    if (c.getValueData() == null) return null;
                    return c.isSensitive() ? decrypt(c.getValueData(), key) : c.getValueData();
                });
    }

    /** Returns the value or {@code defaultValue} when the key is absent or blank. */
    public String getOrDefault(String key, String defaultValue) {
        return get(key).filter(v -> v != null && !v.isBlank()).orElse(defaultValue);
    }

    /** Returns {@code true} if the key is present and its value is {@code "true"}. */
    public boolean getBoolean(String key) {
        return "true".equalsIgnoreCase(getOrDefault(key, "false"));
    }

    /**
     * Returns the first non-blank value from:
     *   1. The DB config (key), decrypted if sensitive
     *   2. The environment variable fallback
     */
    public String getOrEnv(String key, String envFallback) {
        String dbValue = getOrDefault(key, null);
        if (dbValue != null && !dbValue.isBlank()) {
            return dbValue;
        }
        return envFallback != null ? envFallback : "";
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    private String encrypt(String plaintext, String key) {
        try {
            if (plaintext == null || plaintext.isBlank()) return plaintext;
            String aad = "dba-agent:config:" + key;
            byte[] cipherBytes = encryptionService.encrypt(plaintext, aad);
            return Base64.getEncoder().encodeToString(cipherBytes);
        } catch (Exception e) {
            log.error("SystemConfig: encryption failed for key '{}'", key, e);
            throw new RuntimeException("Failed to encrypt config value", e);
        }
    }

    private String decrypt(String base64Cipher, String key) {
        try {
            if (base64Cipher == null || base64Cipher.isBlank()) return base64Cipher;
            String aad = "dba-agent:config:" + key;
            byte[] cipherBytes = Base64.getDecoder().decode(base64Cipher);
            return encryptionService.decrypt(cipherBytes, aad);
        } catch (Exception e) {
            log.warn("SystemConfig: decryption failed for key '{}' — returning empty string", key);
            return "";
        }
    }
}
