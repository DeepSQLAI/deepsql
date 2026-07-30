package com.dbaagent.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class EncryptionService {
    private static final String ALGORITHM = "AES";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int GCM_IV_LENGTH = 12;
    private static final int GCM_TAG_LENGTH_BYTES = 16;
    private static final int GCM_TAG_LENGTH_BITS = GCM_TAG_LENGTH_BYTES * 8;
    private static final int KEY_LENGTH_BYTES = 32;
    private static final int KEY_ID_MAX_LENGTH = 255;
    private static final byte[] FORMAT_PREFIX = new byte[] { 'D', 'B', 'A', '1' };
    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final Map<String, SecretKey> keysById;
    private final String activeKeyId;
    private final SecretKey activeKey;
    public EncryptionService(
        @Value("${ENCRYPTION_KEYS:}") String encryptionKeys,
        @Value("${ENCRYPTION_KEY:}") String encryptionKey,
        @Value("${ENCRYPTION_KEY_ID:}") String encryptionKeyId
    ) {
        KeyConfig keyConfig = loadKeyConfig(encryptionKeys, encryptionKey, encryptionKeyId);
        this.keysById = keyConfig.keysById;
        this.activeKeyId = keyConfig.activeKeyId;
        this.activeKey = keyConfig.keysById.get(keyConfig.activeKeyId);
    }

    public byte[] encrypt(String plaintext) {
        return encrypt(plaintext, null);
    }

    public byte[] encrypt(String plaintext, String aad) {
        try {
            if (plaintext == null) {
                return null;
            }

            byte[] plaintextBytes = plaintext.getBytes(StandardCharsets.UTF_8);
            
            // Generate IV
            byte[] iv = new byte[GCM_IV_LENGTH];
            SECURE_RANDOM.nextBytes(iv);

            // Initialize cipher
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
            cipher.init(Cipher.ENCRYPT_MODE, activeKey, parameterSpec);
            if (aad != null && !aad.isBlank()) {
                cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
            }

            // Encrypt
            byte[] ciphertext = cipher.doFinal(plaintextBytes);

            byte[] keyIdBytes = activeKeyId.getBytes(StandardCharsets.UTF_8);
            if (keyIdBytes.length == 0 || keyIdBytes.length > KEY_ID_MAX_LENGTH) {
                throw new IllegalStateException("Encryption key id is invalid");
            }

            // Combine prefix, key id, IV, and ciphertext
            ByteBuffer byteBuffer = ByteBuffer.allocate(
                FORMAT_PREFIX.length + 1 + keyIdBytes.length + iv.length + ciphertext.length
            );
            byteBuffer.put(FORMAT_PREFIX);
            byteBuffer.put((byte) keyIdBytes.length);
            byteBuffer.put(keyIdBytes);
            byteBuffer.put(iv);
            byteBuffer.put(ciphertext);

            return byteBuffer.array();
        } catch (Exception e) {
            throw new RuntimeException("Encryption failed", e);
        }
    }

    public byte[] encryptIfPresent(String plaintext, String aad) {
        if (plaintext == null || plaintext.isBlank()) {
            return null;
        }
        return encrypt(plaintext, aad);
    }

    public String decrypt(byte[] ciphertext) {
        return decrypt(ciphertext, null);
    }

    public String decrypt(byte[] ciphertext, String aad) {
        try {
            if (ciphertext == null || ciphertext.length == 0) {
                return null;
            }

            if (!isNewFormat(ciphertext)) {
                throw new IllegalStateException(
                    "Unsupported ciphertext format. The connection credentials were encrypted with an older format. " +
                    "Please delete and re-add the connection using the Settings panel to encrypt with the current format."
                );
            }

            return decryptNewFormat(ciphertext, aad);
        } catch (IllegalStateException e) {
            // Re-throw IllegalStateException as-is (contains helpful message)
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Decryption failed: " + e.getMessage(), e);
        }
    }

    public String decryptIfPresent(byte[] ciphertext, String aad) {
        if (ciphertext == null || ciphertext.length == 0) {
            return null;
        }
        return decrypt(ciphertext, aad);
    }

    private String decryptNewFormat(byte[] ciphertext, String aad) throws Exception {
        ByteBuffer byteBuffer = ByteBuffer.wrap(ciphertext);
        byte[] prefix = new byte[FORMAT_PREFIX.length];
        byteBuffer.get(prefix);

        int keyIdLength = byteBuffer.get() & 0xFF;
        if (keyIdLength == 0 || keyIdLength > KEY_ID_MAX_LENGTH) {
            throw new IllegalStateException("Invalid key id length");
        }

        if (byteBuffer.remaining() < keyIdLength + GCM_IV_LENGTH + GCM_TAG_LENGTH_BYTES) {
            throw new IllegalStateException("Ciphertext truncated");
        }

        byte[] keyIdBytes = new byte[keyIdLength];
        byteBuffer.get(keyIdBytes);
        String keyId = new String(keyIdBytes, StandardCharsets.UTF_8);

        SecretKey key = keysById.get(keyId);
        if (key == null) {
            throw new IllegalStateException("No encryption key configured for id: " + keyId);
        }

        byte[] iv = new byte[GCM_IV_LENGTH];
        byteBuffer.get(iv);

        byte[] encryptedData = new byte[byteBuffer.remaining()];
        byteBuffer.get(encryptedData);

        Cipher cipher = Cipher.getInstance(TRANSFORMATION);
        GCMParameterSpec parameterSpec = new GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv);
        cipher.init(Cipher.DECRYPT_MODE, key, parameterSpec);
        if (aad != null && !aad.isBlank()) {
            cipher.updateAAD(aad.getBytes(StandardCharsets.UTF_8));
        }
        byte[] plaintext = cipher.doFinal(encryptedData);
        return new String(plaintext, StandardCharsets.UTF_8);
    }

    private boolean isNewFormat(byte[] ciphertext) {
        if (ciphertext.length < FORMAT_PREFIX.length + 1 + GCM_IV_LENGTH + GCM_TAG_LENGTH_BYTES) {
            return false;
        }
        for (int i = 0; i < FORMAT_PREFIX.length; i++) {
            if (ciphertext[i] != FORMAT_PREFIX[i]) {
                return false;
            }
        }
        return true;
    }

    private KeyConfig loadKeyConfig(String encryptionKeys, String encryptionKey, String encryptionKeyId) {
        Map<String, SecretKey> parsedKeys = new LinkedHashMap<>();
        if (!isBlank(encryptionKeys)) {
            String[] entries = encryptionKeys.split(",");
            for (String entry : entries) {
                String trimmed = entry.trim();
                if (trimmed.isEmpty()) {
                    continue;
                }
                int separatorIndex = trimmed.indexOf(':');
                if (separatorIndex <= 0 || separatorIndex == trimmed.length() - 1) {
                    throw new IllegalStateException("Invalid ENCRYPTION_KEYS entry: " + trimmed);
                }
                String keyId = trimmed.substring(0, separatorIndex);
                String keyValue = trimmed.substring(separatorIndex + 1);
                addKey(parsedKeys, keyId, keyValue, "ENCRYPTION_KEYS");
            }
        }
        if (!isBlank(encryptionKey)) {
            String keyId = isBlank(encryptionKeyId) ? "default" : encryptionKeyId.trim();
            addKey(parsedKeys, keyId, encryptionKey, "ENCRYPTION_KEY");
        }

        if (parsedKeys.isEmpty()) {
            throw new IllegalStateException("Missing encryption key; set ENCRYPTION_KEY or ENCRYPTION_KEYS");
        }

        String activeId;
        if (!isBlank(encryptionKeyId)) {
            activeId = encryptionKeyId.trim();
        } else if (parsedKeys.size() == 1) {
            activeId = parsedKeys.keySet().iterator().next();
        } else {
            throw new IllegalStateException("ENCRYPTION_KEY_ID is required when multiple keys are configured");
        }

        if (!parsedKeys.containsKey(activeId)) {
            throw new IllegalStateException("Active key id not found: " + activeId);
        }

        return new KeyConfig(Collections.unmodifiableMap(parsedKeys), activeId);
    }

    private void addKey(Map<String, SecretKey> parsedKeys, String keyId, String keyValue, String sourceLabel) {
        SecretKey parsedKey = parseKeyMaterial(keyValue, sourceLabel);
        SecretKey existing = parsedKeys.get(keyId);
        if (existing == null) {
            parsedKeys.put(keyId, parsedKey);
            return;
        }
        if (!Arrays.equals(existing.getEncoded(), parsedKey.getEncoded())) {
            throw new IllegalStateException("Duplicate key id with conflicting material: " + keyId);
        }
    }

    private SecretKey parseKeyMaterial(String keyValue, String sourceLabel) {
        String trimmed = keyValue.trim();
        byte[] decoded = tryBase64Decode(trimmed);
        if (decoded == null || decoded.length != KEY_LENGTH_BYTES) {
            if (isHex(trimmed)) {
                decoded = hexToBytes(trimmed);
            }
        }

        if (decoded == null || decoded.length != KEY_LENGTH_BYTES) {
            throw new IllegalStateException(
                sourceLabel + " must be a base64 or hex encoded 32-byte key"
            );
        }

        return new SecretKeySpec(decoded, ALGORITHM);
    }

    private byte[] tryBase64Decode(String value) {
        try {
            return Base64.getDecoder().decode(value);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private boolean isHex(String value) {
        if (value.length() != KEY_LENGTH_BYTES * 2) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            boolean isDigit = c >= '0' && c <= '9';
            boolean isLower = c >= 'a' && c <= 'f';
            boolean isUpper = c >= 'A' && c <= 'F';
            if (!(isDigit || isLower || isUpper)) {
                return false;
            }
        }
        return true;
    }

    private byte[] hexToBytes(String value) {
        int length = value.length();
        byte[] bytes = new byte[length / 2];
        for (int i = 0; i < length; i += 2) {
            int high = Character.digit(value.charAt(i), 16);
            int low = Character.digit(value.charAt(i + 1), 16);
            if (high < 0 || low < 0) {
                throw new IllegalStateException("Invalid hex key material");
            }
            bytes[i / 2] = (byte) ((high << 4) + low);
        }
        return bytes;
    }

    private boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static class KeyConfig {
        private final Map<String, SecretKey> keysById;
        private final String activeKeyId;

        private KeyConfig(Map<String, SecretKey> keysById, String activeKeyId) {
            this.keysById = keysById;
            this.activeKeyId = activeKeyId;
        }
    }
}
