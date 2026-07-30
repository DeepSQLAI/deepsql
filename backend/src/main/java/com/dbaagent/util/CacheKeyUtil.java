package com.dbaagent.util;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class CacheKeyUtil {
    private CacheKeyUtil() {}

    public static String ragKey(String connectionId, String question, int topK, String source) {
        String normalized = normalizeText(question);
        String hash = sha256Hex(normalized);
        return String.format("%s::%s::%d::%s", connectionId, hash, topK, source);
    }

    public static String explainKey(String connectionId, String query, boolean useAnalyze) {
        String normalized = normalizeQuery(query);
        String hash = sha256Hex(normalized);
        String mode = useAnalyze ? "analyze" : "explain";
        return String.format("%s::%s::%s", connectionId, hash, mode);
    }

    private static String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.replaceAll("\\s+", " ").trim().toLowerCase();
    }

    private static String normalizeQuery(String query) {
        if (query == null) {
            return "";
        }
        String normalized = query.replaceAll("\\s+", " ").trim();
        normalized = normalized.replaceAll("'[^']*'", "?");
        normalized = normalized.replaceAll("\\d+", "?");
        return normalized;
    }

    private static String sha256Hex(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            return value;
        }
    }
}
