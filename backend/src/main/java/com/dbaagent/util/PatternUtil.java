package com.dbaagent.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Unanchored regex matching over a cached pattern.
 *
 * Replaces {@code s.matches(".*RE.*")}. String.matches anchors the whole input,
 * so the surrounding {@code .*} only exists to undo that anchoring — and the
 * combination of those wrappers with an alternation is what drives the
 * polynomial backtracking CodeQL reports as java/polynomial-redos. find() on
 * the bare expression is equivalent and linear.
 */
public final class PatternUtil {

    private PatternUtil() {}

    private static final int MAX_CACHED_PATTERNS = 512;
    private static final Map<String, Pattern> CACHE = new ConcurrentHashMap<>();

    public static boolean containsPattern(String input, String regex) {
        if (input == null) {
            return false;
        }
        return cached(regex).matcher(input).find();
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
