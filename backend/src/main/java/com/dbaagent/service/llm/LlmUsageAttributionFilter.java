package com.dbaagent.service.llm;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Labels every request with the feature it belongs to, so usage rows written deep inside
 * the provider funnels can say which product surface spent the money.
 *
 * <p>Doing this in one filter rather than annotating call sites is deliberate. The
 * alternative — wrapping each of the dozen services that reach a model — would have to be
 * repeated by whoever adds the thirteenth, and a missed one is invisible: it records
 * {@code unknown} and nobody notices until the breakdown is asked a question it cannot
 * answer. A URI is a fact the request already carries.
 *
 * <p>Background work (scheduled alerts, boot-time indexing) has no request and so is not
 * covered here; those paths declare themselves with {@link LlmUsageContext#with} directly.
 */
@Component
public class LlmUsageAttributionFilter extends OncePerRequestFilter {

    /**
     * Ordered longest-prefix-first, because {@code /api/dashboards/generate} must not be
     * claimed by {@code /api/dashboards}. A {@link LinkedHashMap} preserves that order;
     * a plain map would make the winner depend on hash iteration.
     */
    private static final Map<String, String> FEATURE_BY_PREFIX = new LinkedHashMap<>();

    static {
        FEATURE_BY_PREFIX.put("/api/dashboards/generate", "dashboard-generate");
        FEATURE_BY_PREFIX.put("/api/dashboards/query", "dashboard-query");
        FEATURE_BY_PREFIX.put("/api/saved-dashboards", "dashboard");
        FEATURE_BY_PREFIX.put("/api/dashboards", "dashboard");
        FEATURE_BY_PREFIX.put("/api/agent", "agent");
        FEATURE_BY_PREFIX.put("/api/chat", "chat");
        FEATURE_BY_PREFIX.put("/api/brain", "brain");
        FEATURE_BY_PREFIX.put("/api/code-scan", "code-scan");
        FEATURE_BY_PREFIX.put("/api/training", "training");
        FEATURE_BY_PREFIX.put("/api/explain", "explain");
        FEATURE_BY_PREFIX.put("/api/index-advisor", "index-advisor");
        FEATURE_BY_PREFIX.put("/api/index-recommendations", "index-advisor");
        FEATURE_BY_PREFIX.put("/api/slow-quer", "slow-query");
        FEATURE_BY_PREFIX.put("/api/playbooks", "playbook");
        FEATURE_BY_PREFIX.put("/api/company-knowledge", "company-knowledge");
        FEATURE_BY_PREFIX.put("/api/schema", "schema");
        FEATURE_BY_PREFIX.put("/api/llm/v1", "llm-proxy");
        FEATURE_BY_PREFIX.put("/api/mcp", "mcp");
    }

    /** {@code /connection/{id}} and {@code /connections/{id}} path forms. */
    private static final Pattern CONNECTION_IN_PATH =
            Pattern.compile("/connections?/([^/?]+)");

    /**
     * Segments that follow {@code /connections/} but name an action rather than a
     * connection. Without this, {@code POST /api/connections/test} would attribute usage
     * to a connection called "test" — a plausible-looking id that belongs to nothing, and
     * therefore worse than recording no connection at all.
     */
    private static final Set<String> NOT_A_CONNECTION_ID =
            Set.of("test", "new", "create", "search", "all", "list");

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String feature = featureFor(request.getRequestURI());
        String connectionId = connectionFor(request);
        try {
            LlmUsageContext.with(feature, connectionId, () -> {
                try {
                    chain.doFilter(request, response);
                } catch (IOException | ServletException e) {
                    throw new FilterFailure(e);
                }
                return null;
            });
        } catch (FilterFailure wrapper) {
            // Unwrap so the container sees the exception the chain actually threw; a
            // wrapped one would break error handling further up.
            Throwable cause = wrapper.getCause();
            if (cause instanceof IOException io) {
                throw io;
            }
            throw (ServletException) cause;
        }
    }

    /** Lets a checked exception cross the {@code Supplier} boundary unchanged. */
    private static final class FilterFailure extends RuntimeException {
        FilterFailure(Throwable cause) {
            super(cause);
        }
    }

    private static String featureFor(String uri) {
        if (uri == null) {
            return LlmUsageContext.UNKNOWN_FEATURE;
        }
        for (Map.Entry<String, String> entry : FEATURE_BY_PREFIX.entrySet()) {
            if (uri.startsWith(entry.getKey())) {
                return entry.getValue();
            }
        }
        return LlmUsageContext.UNKNOWN_FEATURE;
    }

    /**
     * Best-effort connection id, from the path or a query parameter. The request body is
     * deliberately not read: it is consumable once, and draining it here to label a usage
     * row would break every controller that expects to parse it.
     */
    private static String connectionFor(HttpServletRequest request) {
        String param = request.getParameter("connectionId");
        if (param != null && !param.isBlank()) {
            return param;
        }
        String uri = request.getRequestURI();
        if (uri == null) {
            return null;
        }
        Matcher matcher = CONNECTION_IN_PATH.matcher(uri);
        if (!matcher.find()) {
            return null;
        }
        String candidate = matcher.group(1);
        return NOT_A_CONNECTION_ID.contains(candidate.toLowerCase()) ? null : candidate;
    }
}
