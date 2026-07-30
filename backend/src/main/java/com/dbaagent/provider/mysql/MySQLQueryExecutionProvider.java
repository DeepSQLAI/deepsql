package com.dbaagent.provider.mysql;

import com.dbaagent.provider.api.QueryExecutionProvider;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/**
 * MySQL implementation of QueryExecutionProvider.
 * Handles MySQL-specific query execution features.
 */
@Component
public class MySQLQueryExecutionProvider implements QueryExecutionProvider {

    private static final String DATABASE_TYPE = "mysql";
    private static final Pattern LITERAL_PATTERN = Pattern.compile(
        "'[^']*'|\"[^\"]*\"|\\d+\\.?\\d*"
    );
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");

    @Override
    public String getDatabaseType() {
        return DATABASE_TYPE;
    }

    @Override
    public String applyLimit(String query, int limit) {
        if (query == null || limit <= 0) {
            return query;
        }

        String trimmedQuery = query.trim();
        String lowerQuery = trimmedQuery.toLowerCase();

        // Only add LIMIT to SELECT queries without existing LIMIT
        if (!lowerQuery.startsWith("select") || lowerQuery.contains("limit")) {
            return query;
        }

        // Remove trailing semicolon if present
        if (trimmedQuery.endsWith(";")) {
            trimmedQuery = trimmedQuery.substring(0, trimmedQuery.length() - 1);
        }

        return trimmedQuery + " LIMIT " + limit;
    }

    @Override
    public boolean isReadOnlyQuery(String query) {
        if (query == null || query.trim().isEmpty()) {
            return true;
        }

        String normalized = stripLeadingComments(query.trim()).toUpperCase();

        return normalized.isEmpty() ||
               normalized.startsWith("SELECT") ||
               normalized.startsWith("SHOW") ||
               normalized.startsWith("DESCRIBE") ||
               normalized.startsWith("DESC") ||
               normalized.startsWith("EXPLAIN") ||
               normalized.startsWith("WITH");
    }

    private static String stripLeadingComments(String sql) {
        String remaining = sql.trim();
        while (!remaining.isEmpty()) {
            if (remaining.startsWith("--") || remaining.startsWith("#")) {
                int newline = remaining.indexOf('\n');
                remaining = newline >= 0 ? remaining.substring(newline + 1).trim() : "";
                continue;
            }
            if (remaining.startsWith("/*")) {
                int end = remaining.indexOf("*/");
                remaining = end >= 0 ? remaining.substring(end + 2).trim() : "";
                continue;
            }
            break;
        }
        return remaining;
    }

    @Override
    public String getConnectionTestQuery() {
        return "SELECT 1";
    }

    @Override
    public String normalizeQuery(String query) {
        if (query == null) {
            return null;
        }

        // Replace literals with placeholders
        String normalized = LITERAL_PATTERN.matcher(query).replaceAll("?");

        // Normalize whitespace
        normalized = WHITESPACE_PATTERN.matcher(normalized).replaceAll(" ");

        // Trim and lowercase
        return normalized.trim().toLowerCase();
    }

    @Override
    public String generateQuerySignature(String query) {
        String normalized = normalizeQuery(query);
        if (normalized == null) {
            return null;
        }

        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] digest = md.digest(normalized.getBytes());
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            // Fallback to hashCode
            return String.valueOf(normalized.hashCode());
        }
    }
}
