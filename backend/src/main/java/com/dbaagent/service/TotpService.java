package com.dbaagent.service;

import com.dbaagent.util.Base32Codec;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;

@Service
public class TotpService {
    private static final SecureRandom RANDOM = new SecureRandom();

    public byte[] generateSecretBytes() {
        byte[] secret = new byte[20];
        RANDOM.nextBytes(secret);
        return secret;
    }

    public String toBase32(byte[] secretBytes) {
        return Base32Codec.encode(secretBytes);
    }

    public boolean verifyCode(byte[] secretBytes, String code) {
        if (secretBytes == null || code == null || !code.matches("\\d{6}")) {
            return false;
        }
        long currentWindow = Instant.now().getEpochSecond() / 30L;
        for (long offset = -1; offset <= 1; offset++) {
            if (generateCode(secretBytes, currentWindow + offset).equals(code)) {
                return true;
            }
        }
        return false;
    }

    public String otpauthUrl(String issuer, String email, byte[] secretBytes) {
        String secret = toBase32(secretBytes);
        String normalizedIssuer = issuer == null || issuer.isBlank() ? "DeepSQL" : issuer.trim();
        String account = email == null ? "user" : email.trim().toLowerCase(Locale.ROOT);
        return "otpauth://totp/" + normalizedIssuer + ":" + account
            + "?secret=" + secret
            + "&issuer=" + normalizedIssuer
            + "&algorithm=SHA1&digits=6&period=30";
    }

    private String generateCode(byte[] secretBytes, long timestep) {
        try {
            byte[] data = ByteBuffer.allocate(8).putLong(timestep).array();
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secretBytes, "HmacSHA1"));
            byte[] hash = mac.doFinal(data);
            int offset = hash[hash.length - 1] & 0xf;
            int binary =
                ((hash[offset] & 0x7f) << 24)
                    | ((hash[offset + 1] & 0xff) << 16)
                    | ((hash[offset + 2] & 0xff) << 8)
                    | (hash[offset + 3] & 0xff);
            int otp = binary % 1_000_000;
            return String.format("%06d", otp);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate TOTP", e);
        }
    }
}
