package com.dbaagent.util;

import lombok.extern.slf4j.Slf4j;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.regex.Pattern;

/**
 * Utility for normalizing SQL queries to create query fingerprints
 * Used for deduplication and grouping of similar queries
 */
@Slf4j
public class QueryNormalizer {

    private static final Pattern NUMBER_PATTERN = Pattern.compile("\\b\\d+\\b");
    private static final Pattern STRING_PATTERN = Pattern.compile("'[^']*'");
    // PostgreSQL positional bind placeholders ($1, $2, ...). MUST be collapsed
    // to "?" BEFORE the numeric rule, otherwise "\\b\\d+\\b" eats the digit
    // inside "$1" and leaves "$?", which never matches the "?" produced from a
    // literal slow-log execution — the root of the "Postgres samples empty,
    // MySQL fine" report (MySQL digests already use "?").
    private static final Pattern DOLLAR_PARAM_PATTERN = Pattern.compile("\\$\\d+");
    // Boolean literals. Postgres logs render booleans as true/false, while
    // MySQL stores them as 1/0 (already handled by NUMBER_PATTERN); without
    // this rule a Postgres literal "= true" never matches the digest's "= ?".
    private static final Pattern BOOLEAN_PATTERN = Pattern.compile("\\b(?:true|false)\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern WHITESPACE_PATTERN = Pattern.compile("\\s+");
    private static final Pattern IN_LIST_PATTERN = Pattern.compile("IN\\s*\\([^)]+\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern VALUES_PATTERN = Pattern.compile("VALUES\\s*\\([^)]+\\)", Pattern.CASE_INSENSITIVE);

    // DML/DDL keywords that indicate the start of the actual query
    private static final String[] DML_KEYWORDS = {
        "SELECT", "INSERT", "UPDATE", "DELETE", "MERGE", "CALL",
        "CREATE", "ALTER", "DROP", "TRUNCATE", "REPLACE", "WITH"
    };

    /**
     * Sanitize query text by removing common prefixes like "use database;", "set timestamp=?;", etc.
     * Returns only the actual DML/DDL statement (SELECT, INSERT, UPDATE, DELETE, etc.)
     *
     * Example: "use analytics_db; set timestamp=123; SELECT * FROM users" -> "SELECT * FROM users"
     */
    public static String sanitize(String query) {
        if (query == null || query.trim().isEmpty()) {
            return query;
        }

        String sanitized = query.trim();
        String upperSanitized = sanitized.toUpperCase();

        // Find the first occurrence of any DML/DDL keyword
        int firstKeywordIndex = -1;
        for (String keyword : DML_KEYWORDS) {
            int index = upperSanitized.indexOf(keyword);
            if (index != -1 && (firstKeywordIndex == -1 || index < firstKeywordIndex)) {
                // Verify it's at a word boundary (not part of another word)
                boolean isWordBoundary = (index == 0 || !Character.isLetterOrDigit(sanitized.charAt(index - 1)));
                if (isWordBoundary) {
                    firstKeywordIndex = index;
                }
            }
        }

        if (firstKeywordIndex > 0) {
            sanitized = sanitized.substring(firstKeywordIndex);
        }

        return sanitized;
    }

    /**
     * Normalize a SQL query by replacing literals with placeholders
     * Also sanitizes by removing prefixes like "use database;", "set timestamp=?;"
     * Example: "SELECT * FROM users WHERE id = 123" -> "SELECT * FROM users WHERE id = ?"
     */
    public static String normalize(String query) {
        if (query == null || query.trim().isEmpty()) {
            return "";
        }

        // First, sanitize to remove prefixes
        String normalized = sanitize(query.trim());

        // Replace string literals with ?
        normalized = STRING_PATTERN.matcher(normalized).replaceAll("?");

        // Replace PostgreSQL positional placeholders ($1, $2, ...) with ?.
        // Runs BEFORE the numeric rule so "$1" becomes "?" rather than "$?",
        // letting a pg_stat_statements query and its literal-bearing slow-log
        // execution fingerprint identically.
        normalized = DOLLAR_PARAM_PATTERN.matcher(normalized).replaceAll("?");

        // Replace boolean literals (true/false) with ? so Postgres literal
        // executions match the placeholder form from pg_stat_statements.
        normalized = BOOLEAN_PATTERN.matcher(normalized).replaceAll("?");

        // Replace numeric literals with ?
        normalized = NUMBER_PATTERN.matcher(normalized).replaceAll("?");

        // Replace IN lists: IN (1,2,3) -> IN (?)
        normalized = IN_LIST_PATTERN.matcher(normalized).replaceAll("IN (?)");

        // Replace VALUES lists: VALUES (1,'a',2) -> VALUES (?)
        normalized = VALUES_PATTERN.matcher(normalized).replaceAll("VALUES (?)");

        // Normalize whitespace
        normalized = WHITESPACE_PATTERN.matcher(normalized).replaceAll(" ");

        // Convert to lowercase for consistency
        normalized = normalized.toLowerCase().trim();

        return normalized;
    }

    /**
     * Generate a unique fingerprint (hash) for a normalized query
     * Used as query ID for deduplication
     */
    public static String generateFingerprint(String query) {
        String normalized = normalize(query);
        return generateMD5Hash(normalized);
    }

    /**
     * Generate MD5 hash of a string
     */
    public static String generateMD5Hash(String input) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            byte[] hashBytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hashBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            log.error("MD5 algorithm not available", e);
            return String.valueOf(input.hashCode());
        }
    }

    /**
     * Extract table names from a SQL query (simple extraction)
     */
    public static String[] extractTableNames(String query) {
        if (query == null) {
            return new String[0];
        }

        String upperQuery = query.toUpperCase();
        Pattern tablePattern = Pattern.compile(
            "(?:FROM|JOIN|INTO|UPDATE)\\s+([\\w.`\"]+)",
            Pattern.CASE_INSENSITIVE
        );

        java.util.List<String> tables = new java.util.ArrayList<>();
        java.util.regex.Matcher matcher = tablePattern.matcher(upperQuery);

        while (matcher.find()) {
            String table = matcher.group(1)
                .replaceAll("[`\"]", "")  // Remove quotes
                .trim();
            if (!table.isEmpty() && !tables.contains(table)) {
                tables.add(table);
            }
        }

        return tables.toArray(new String[0]);
    }

    /**
     * Check if query uses specific keywords (for pattern detection)
     */
    public static boolean hasKeyword(String query, String keyword) {
        if (query == null || keyword == null) {
            return false;
        }
        Pattern pattern = Pattern.compile("\\b" + keyword + "\\b", Pattern.CASE_INSENSITIVE);
        return pattern.matcher(query).find();
    }

    /**
     * Detect query type (SELECT, INSERT, UPDATE, DELETE, etc.)
     * Handles queries with prefixes like "use database; set timestamp=?;"
     */
    public static String detectQueryType(String query) {
        if (query == null || query.trim().isEmpty()) {
            return "UNKNOWN";
        }

        // Sanitize first to handle prefixes
        String sanitized = sanitize(query.trim()).toUpperCase();

        if (sanitized.startsWith("SELECT")) return "SELECT";
        if (sanitized.startsWith("INSERT")) return "INSERT";
        if (sanitized.startsWith("UPDATE")) return "UPDATE";
        if (sanitized.startsWith("DELETE")) return "DELETE";
        if (sanitized.startsWith("CREATE")) return "CREATE";
        if (sanitized.startsWith("ALTER")) return "ALTER";
        if (sanitized.startsWith("DROP")) return "DROP";
        if (sanitized.startsWith("TRUNCATE")) return "TRUNCATE";
        if (sanitized.startsWith("REPLACE")) return "REPLACE";
        if (sanitized.startsWith("WITH")) return "SELECT"; // WITH usually is CTE followed by SELECT

        return "OTHER";
    }

    /**
     * Clean query text for display (remove extra whitespace, limit length)
     * Also sanitizes by removing prefixes like "use database;", "set timestamp=?;"
     */
    public static String cleanForDisplay(String query, int maxLength) {
        if (query == null) {
            return "";
        }

        // Sanitize first to remove prefixes, then clean whitespace
        String sanitized = sanitize(query.trim());
        String cleaned = WHITESPACE_PATTERN.matcher(sanitized).replaceAll(" ");

        if (cleaned.length() > maxLength) {
            return cleaned.substring(0, maxLength - 3) + "...";
        }

        return cleaned;
    }

    /**
     * Clean query text for display without sanitization (preserves original query structure)
     */
    public static String cleanForDisplayRaw(String query, int maxLength) {
        if (query == null) {
            return "";
        }

        String cleaned = WHITESPACE_PATTERN.matcher(query.trim()).replaceAll(" ");

        if (cleaned.length() > maxLength) {
            return cleaned.substring(0, maxLength - 3) + "...";
        }

        return cleaned;
    }
}
