package com.dbaagent.controller;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every {@code /brain/**} endpoint must authorize the caller against the connection it
 * touches, not merely require a logged-in user.
 *
 * <p>This shipped with 93 of 116 endpoints unguarded. {@code SecurityConfig} only asserts
 * {@code .anyRequest().authenticated()} and {@code JwtAuthenticationFilter} only resolves a
 * principal — neither inspects a {@code connectionId}. Connections are private per user
 * ({@code ConnectionAccessService.resolveAccess} keys on {@code ownerUsername} plus an
 * explicit grant table), so an authenticated user who passed somebody else's connection id
 * to {@code /brain/health-scores/{id}}, {@code /brain/data-sensitivity/{id}} (which names
 * the PII columns), {@code /brain/cost-attribution/{id}} and ~90 others got that user's
 * database intelligence back.
 *
 * <p>Resource-level authorization here is opt-in per method, and the misses clustered by
 * when a section was written rather than by read/write semantics — the first ~15 endpoints
 * had it and every later "Phase" block did not. That is a defect a reviewer catches once
 * and a test catches forever, so it is asserted structurally: scanned as text, because the
 * property under test is that no endpoint method body lacks the call, whatever it does.
 */
class BrainControllerAuthorizationSafetyTest {

    private static final Path CONTROLLER =
        Path.of("src/main/java/com/dbaagent/controller/BrainController.java");

    private static final Pattern MAPPING =
        Pattern.compile("^\\s*@(Get|Post|Delete|Put|Patch)Mapping\\b");

    /** A method is authorized by a per-connection assert, or by being admin-only. */
    private static final Pattern AUTHORIZED = Pattern.compile(
        "accessControlService\\.assertCan(Read|Manage)ConnectionContent\\(|@PreAuthorize");

    private record Endpoint(int line, String mapping, String body) {}

    /**
     * Slices the controller into one entry per handler: from its mapping annotation to the
     * method's closing brace, which at this nesting level is a line that is exactly
     * {@code "    }"}.
     */
    private static List<Endpoint> endpoints() throws IOException {
        List<String> lines = Files.readAllLines(CONTROLLER);
        List<Endpoint> endpoints = new ArrayList<>();

        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = MAPPING.matcher(lines.get(i));
            if (!matcher.find()) {
                continue;
            }
            StringBuilder body = new StringBuilder();
            int end = i;
            while (end < lines.size()) {
                body.append(lines.get(end)).append('\n');
                if (end > i && lines.get(end).equals("    }")) {
                    break;
                }
                end++;
            }
            endpoints.add(new Endpoint(i + 1, lines.get(i).trim(), body.toString()));
        }
        return endpoints;
    }

    @Test
    void everyBrainEndpointAuthorizesTheCallerAgainstTheConnection() throws IOException {
        List<String> offenders = new ArrayList<>();

        for (Endpoint endpoint : endpoints()) {
            if (!AUTHORIZED.matcher(endpoint.body()).find()) {
                offenders.add(CONTROLLER + ":" + endpoint.line() + " " + endpoint.mapping());
            }
        }

        assertThat(offenders)
            .as("Each of these BrainController endpoints takes a caller-supplied id and "
                + "never authorizes it. Authentication is not authorization: connections "
                + "are private per user, so this hands one user another user's schema, "
                + "sensitivity, cost and workload intelligence. Add "
                + "accessControlService.assertCanReadConnectionContent(connectionId) to "
                + "reads and assertCanManageConnectionContent(connectionId) to writes, "
                + "resolving the connection id first when the path carries some other id. "
                + "An endpoint with no connection scope at all is admin-only (@PreAuthorize).")
            .isEmpty();
    }

    /**
     * The asserts live inside each handler's {@code try}, and every handler ends with a
     * {@code catch (Exception)} that returns 500. Without an earlier
     * {@code catch (ResponseStatusException e) { throw e; }} the 403 would be swallowed and
     * reported as a server error — the denial would still hold, but it would look like a
     * bug in the feature rather than a permission boundary, and a client could not tell
     * "not yours" from "broken".
     */
    @Test
    void authorizationFailuresPropagateAsForbiddenRatherThanServerError() throws IOException {
        List<String> offenders = new ArrayList<>();

        for (Endpoint endpoint : endpoints()) {
            String body = endpoint.body();
            boolean guardedInline = body.contains("accessControlService.assertCan");
            if (guardedInline && !body.contains("catch (ResponseStatusException e)")) {
                offenders.add(CONTROLLER + ":" + endpoint.line() + " " + endpoint.mapping());
            }
        }

        assertThat(offenders)
            .as("These endpoints assert access inside a try whose catch-all converts the "
                + "403 into a 500. Rethrow it first: "
                + "catch (ResponseStatusException e) { throw e; }")
            .isEmpty();
    }
}
