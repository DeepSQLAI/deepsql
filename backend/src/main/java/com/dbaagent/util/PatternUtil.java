package com.dbaagent.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Unanchored regex matching over a cached pattern, on a length-bounded input.
 *
 * Replaces {@code s.matches(".*RE.*")}: String.matches anchors the whole input,
 * so the surrounding {@code .*} exists only to undo that anchoring, and find()
 * on the bare expression is equivalent.
 *
 * Some of the caller expressions have an inner {@code A.*B} gap that backtracks
 * super-linearly when B is absent (java/polynomial-redos) — measured at tens of
 * seconds on a crafted multi-thousand-token input. Rather than reshape ~90
 * classifier patterns (and risk changing what they match), the input is capped
 * to {@link #MAX_SCAN_CHARS} before matching. The gap can then backtrack only
 * within that window, which bounds every pattern to a few milliseconds. Real
 * chat messages and identifiers are far shorter, so matching is unchanged for
 * every legitimate input; only an abusive one is truncated.
 */
public final class PatternUtil {

    private PatternUtil() {}

    /**
     * Longest input scanned. Well above any real question or identifier, far
     * below the length where a backtracking gap becomes expensive.
     */
    static final int MAX_SCAN_CHARS = 4096;

    private static final int MAX_CACHED_PATTERNS = 512;
    private static final Map<String, Pattern> CACHE = new ConcurrentHashMap<>();

    public static boolean containsPattern(String input, String regex) {
        if (input == null) {
            return false;
        }
        CharSequence scanned = input.length() > MAX_SCAN_CHARS
                ? input.subSequence(0, MAX_SCAN_CHARS)
                : input;
        return cached(regex).matcher(scanned).find();
    }

    private static Pattern cached(String regex) {
        Pattern p = CACHE.get(regex);
        if (p != null) {
            return p;
        }
        Pattern compiled = Pattern.compile(regex);
        // Callers pass compile-time literals, so the key set is bounded in practice.
        // The cap only stops an unforeseen dynamic caller from growing this without limit.
        if (CACHE.size() < MAX_CACHED_PATTERNS) {
            CACHE.putIfAbsent(regex, compiled);
        }
        return compiled;
    }
}
