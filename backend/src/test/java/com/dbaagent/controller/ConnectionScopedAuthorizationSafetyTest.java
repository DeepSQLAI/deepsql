package com.dbaagent.controller;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every endpoint that takes a caller-supplied connection id must authorize it.
 *
 * <p>{@link BrainControllerAuthorizationSafetyTest} asserts this for one file, and that is
 * exactly why the same defect shipped again: 116 endpoints across 12 other controllers
 * ({@code SlowQueryController}, {@code SlowQueryAnalyticsController}, {@code SentinelAnalyticsController},
 * {@code SchemaChangeController}, the four Performance controllers, {@code IndexAdvisorController},
 * {@code AdvisorController}, {@code ResourceLimitsController}, {@code BusinessRuleController})
 * had no authorization at all. A user with no grant on any connection could read
 * literal-bearing slow-query SQL with real customer ids and names, enumerate another
 * tenant's schema, and permanently delete their analysis history — verified against a
 * running install, not inferred.
 *
 * <p>So this test scans <em>every</em> controller rather than a named list. A new
 * controller is covered the day it is written, which a per-file test can never promise.
 */
class ConnectionScopedAuthorizationSafetyTest {

    private static final Path CONTROLLER_DIR = Path.of("src/main/java/com/dbaagent/controller");

    private static final Pattern MAPPING = Pattern.compile(
        "^\\s*@(?:[\\w.]*\\.)?(Get|Post|Delete|Put|Patch)Mapping\\b");

    /**
     * A handler is authorized by a per-connection assert, by resolving some other id to its
     * owning connection through a local helper, or by being admin-only. The helper form is
     * matched by name because the resolution happens one call away — an
     * {@code assertCanManageAlert(alertId)} that looks up the alert's connection and
     * asserts on it is the correct shape, and inlining it in every handler would be worse.
     */
    private static final Pattern AUTHORIZED = Pattern.compile(
        "accessControlService\\.assertCan\\w+\\(|@PreAuthorize|assertCan(Read|Manage)\\w+\\(");

    /**
     * Handlers whose authorization correctly lives one layer down, named as
     * {@code Controller:line}. Two distinct reasons, and both matter:
     *
     * <ul>
     *   <li>{@code DashboardWorkspaceController} delegates to
     *       {@code DashboardWorkspaceService}, which calls
     *       {@code assertCanReadConnectionContent} itself. Asserting again in the
     *       controller would duplicate a check that could then drift.
     *   <li>The Agent controllers scope by <em>user</em>, not by connection ACL:
     *       {@code AgentConversationService} keys every query on {@code currentUserId()},
     *       so the {@code connectionId} there is a label on the caller's own conversation
     *       rather than a reference to someone else's data. There is no connection
     *       authorization to perform.
     * </ul>
     *
     * {@link #everyDelegatedCheckStillExists()} re-derives the first group, so removing the
     * service-layer assert fails the build instead of silently widening access.
     */
    private static final Set<String> AUTHORIZED_ELSEWHERE = Set.of(
        "DashboardWorkspaceController.java:47",
        "DashboardWorkspaceController.java:60",
        "AgentChatController.java:25",
        "AgentConversationController.java:29",
        "AgentConversationController.java:45"
    );

    /** Service methods that own a delegated connection check. */
    private static final List<String> DELEGATED_CHECKS = List.of(
        "src/main/java/com/dbaagent/service/DashboardWorkspaceService.java"
    );

    /**
     * Controllers that legitimately have no connection to authorize against. Each is here
     * for a stated reason, not because it was inconvenient — an entry is a claim that the
     * endpoints hold no caller-supplied connection id, which {@link #everyExemptControllerIsActuallyConnectionFree()}
     * re-checks so this list cannot rot into a way of hiding a real gap.
     */
    private static final Set<String> NOT_CONNECTION_SCOPED = Set.of(
        "AuthController",            // login / refresh / logout — pre-authentication by definition
        "AuthCliController",         // device-code pairing, same
        "AuthInternalController",    // nginx auth_request subrequest
        "BootstrapController",       // first-admin creation, gated by a shared secret + localhost
        "SetupController",           // install wizard
        "InviteCodeController",      // invite redemption, keyed on a code
        "UserController",            // user administration, role-gated elsewhere
        "AdminController",           // admin surface, @PreAuthorize at class level
        "ImpersonationController",   // admin surface, @PreAuthorize at class level
        "McpTokenController",        // per-caller tokens, scoped to the authenticated user
        "SlackLinkController",       // Slack workspace binding
        "LlmProxyController",        // OpenAI-shaped gateway, no connection in the contract
        "PublicDashboardController", // permitAll by design; scoped by share token
        // Provisions the agent profile for whoever is calling: the username comes from
        // requireCurrentUsername() and any connectionId in the body only selects which of
        // the caller's own connections to preload.
        "AgentBridgeController"
    );

    private record Endpoint(String file, int line, String mapping, String body) {}

    private static List<Path> controllers() throws IOException {
        try (Stream<Path> paths = Files.list(CONTROLLER_DIR)) {
            return paths.filter(p -> p.getFileName().toString().endsWith("Controller.java")).sorted().toList();
        }
    }

    /**
     * Slices one controller into one entry per handler, mapping annotation to closing brace.
     *
     * <p>The slice starts one line <em>above</em> the mapping when that line is another
     * annotation, because {@code @PreAuthorize} is conventionally written above
     * {@code @PostMapping}. Starting at the mapping itself put the authorization outside the
     * captured body and reported {@code POST /training/reindex-all} — which is admin-only —
     * as unguarded.
     */
    private static List<Endpoint> endpoints(Path controller) throws IOException {
        List<String> lines = Files.readAllLines(controller);
        List<Endpoint> endpoints = new ArrayList<>();
        String name = controller.getFileName().toString();

        for (int i = 0; i < lines.size(); i++) {
            Matcher matcher = MAPPING.matcher(lines.get(i));
            if (!matcher.find()) {
                continue;
            }
            int start = i;
            while (start > 0 && lines.get(start - 1).trim().startsWith("@")) {
                start--;
            }
            StringBuilder body = new StringBuilder();
            int end = start;
            while (end < lines.size()) {
                body.append(lines.get(end)).append('\n');
                if (end > i && lines.get(end).equals("    }")) {
                    break;
                }
                end++;
            }
            endpoints.add(new Endpoint(name, i + 1, lines.get(i).trim(), body.toString()));
        }
        return endpoints;
    }

    /**
     * True when the handler receives a connection id, or an id that identifies a
     * connection-owned row. Both forms need authorization: the second is the trap, since
     * {@code PUT /performance-actions/{actionId}/status} carries no {@code connectionId}
     * yet mutates a row that belongs to one.
     */
    private static boolean touchesAConnection(String body) {
        return body.contains("connectionId")
            || Pattern.compile("@PathVariable[^)]*\\)?\\s*(?:Long|String)\\s+"
                + "(alertId|actionId|regressionId|recommendationId|fingerprintId|planId|ruleId"
                + "|snapshotId|historyId|changeId|comparisonId)").matcher(body).find()
            || body.contains("historyId1")
            || body.contains("snapshotId1");
    }

    @Test
    void everyConnectionScopedEndpointAuthorizesTheCaller() throws IOException {
        List<String> offenders = new ArrayList<>();

        for (Path controller : controllers()) {
            String name = controller.getFileName().toString().replace(".java", "");
            if (NOT_CONNECTION_SCOPED.contains(name)) {
                continue;
            }
            String source = Files.readString(controller);
            boolean classLevelAdminOnly = source.contains("@PreAuthorize")
                && source.indexOf("@PreAuthorize") < source.indexOf("public class");
            if (classLevelAdminOnly) {
                continue;
            }
            for (Endpoint endpoint : endpoints(controller)) {
                if (!touchesAConnection(endpoint.body())) {
                    continue;
                }
                if (AUTHORIZED_ELSEWHERE.contains(endpoint.file() + ":" + endpoint.line())) {
                    continue;
                }
                if (!AUTHORIZED.matcher(endpoint.body()).find()) {
                    offenders.add(endpoint.file() + ":" + endpoint.line() + " " + endpoint.mapping());
                }
            }
        }

        assertThat(offenders)
            .as("These endpoints take a caller-supplied connection id (or an id owned by a "
                + "connection) and never authorize it. Authentication is not authorization: "
                + "SecurityConfig only asserts .anyRequest().authenticated() and no filter, "
                + "interceptor or aspect inspects a connectionId. Add "
                + "accessControlService.assertCanReadConnectionContent(connectionId) to reads "
                + "and assertCanManageConnectionContent(connectionId) to writes. When the path "
                + "carries some other id, resolve its owning connection first and assert on "
                + "that — an id is not a capability. An endpoint with no connection scope at "
                + "all is admin-only (@PreAuthorize).")
            .isEmpty();
    }

    /**
     * Ids that arrive in the request <em>body</em> are not constrained by a path-variable
     * check. {@code POST /schema-changes/{connectionId}/changes/acknowledge} authorizes the
     * path connection and then acknowledges whatever change ids the body names, so a caller
     * authorized on their own connection could acknowledge another tenant's changes. Each
     * such handler must additionally verify the collection belongs to the scope.
     */
    @Test
    void collectionIdsFromTheRequestBodyAreCheckedAgainstTheScope() throws IOException {
        List<String> offenders = new ArrayList<>();

        for (Path controller : controllers()) {
            for (Endpoint endpoint : endpoints(controller)) {
                String body = endpoint.body();
                boolean takesIdCollection = Pattern
                    .compile("@RequestBody[^;]*List<String>\\s+(\\w*[Ii]ds)").matcher(body).find()
                    || body.contains("getActionIds()")
                    || body.contains("getChangeIds()");
                if (!takesIdCollection) {
                    continue;
                }
                boolean scoped = body.contains("BelongTo")
                    || body.contains("forEach(this::assertCan")
                    || body.contains("stream().forEach");
                if (!scoped) {
                    offenders.add(endpoint.file() + ":" + endpoint.line() + " " + endpoint.mapping());
                }
            }
        }

        assertThat(offenders)
            .as("These endpoints accept a list of ids in the request body. A path-variable "
                + "authorization check does not constrain them, so verify every id belongs "
                + "to the authorized scope (or authorize each id individually) before acting.")
            .isEmpty();
    }

    /**
     * An assert placed inside a {@code try} whose catch-all returns 500 turns a 403 into a
     * server error: the denial holds, but the client cannot tell "not yours" from "broken".
     */
    @Test
    void authorizationFailuresPropagateAsForbiddenRatherThanServerError() throws IOException {
        List<String> offenders = new ArrayList<>();

        for (Path controller : controllers()) {
            for (Endpoint endpoint : endpoints(controller)) {
                String body = endpoint.body();
                int assertAt = body.indexOf("accessControlService.assertCan");
                if (assertAt < 0) {
                    continue;
                }
                int tryAt = body.indexOf("try {");
                boolean assertInsideTry = tryAt >= 0 && tryAt < assertAt;
                boolean hasCatchAll = body.contains("catch (Exception");
                if (assertInsideTry && hasCatchAll
                        && !body.contains("catch (org.springframework.web.server.ResponseStatusException e)")
                        && !body.contains("catch (ResponseStatusException e)")) {
                    offenders.add(endpoint.file() + ":" + endpoint.line() + " " + endpoint.mapping());
                }
            }
        }

        assertThat(offenders)
            .as("These endpoints assert access inside a try whose catch-all converts the 403 "
                + "into a 500. Rethrow it first: catch (ResponseStatusException e) { throw e; } "
                + "— or move the assert above the try.")
            .isEmpty();
    }

    /**
     * A {@code @ControllerAdvice} with a catch-all {@code @ExceptionHandler(Exception.class)}
     * swallows authorization denials the same way an in-method catch-all does, and it is
     * easier to miss because it lives in a different file from the endpoint.
     *
     * <p>{@code IndexAdvisorExceptionHandler} did exactly this: a non-granted caller hitting
     * {@code /index-advisor/{id}/health-report} got {@code 500 "Index operation failed"}
     * whose body carried the 403's text. The guard held, but the response blamed the index
     * store. Any advice with a catch-all must also handle {@code ResponseStatusException}.
     */
    @Test
    void controllerAdvicesDoNotSwallowAuthorizationDenials() throws IOException {
        List<String> offenders = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(Path.of("src/main/java/com/dbaagent"))) {
            for (Path file : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                String source = Files.readString(file);
                if (!source.contains("@RestControllerAdvice") && !source.contains("@ControllerAdvice")) {
                    continue;
                }
                if (source.contains("@ExceptionHandler(Exception.class)")
                        && !source.contains("ResponseStatusException.class")) {
                    offenders.add(file.getFileName().toString());
                }
            }
        }

        assertThat(offenders)
            .as("These @ControllerAdvice classes catch Exception without handling "
                + "ResponseStatusException first, so a 403 from an authorization check is "
                + "reported as a 500 attributed to the feature. Add an "
                + "@ExceptionHandler(ResponseStatusException.class) that preserves the status.")
            .isEmpty();
    }

    /**
     * A handler exempted because its check lives in the service layer stays exempt only
     * while that check is actually there. Without this, deleting the service-layer assert
     * would widen access and the exemption would quietly cover for it.
     */
    @Test
    void everyDelegatedCheckStillExists() throws IOException {
        List<String> missing = new ArrayList<>();

        for (String service : DELEGATED_CHECKS) {
            String source = Files.readString(Path.of(service));
            if (!source.contains("accessControlService.assertCanReadConnectionContent(")
                    && !source.contains("accessControlService.assertCanManageConnectionContent(")) {
                missing.add(service);
            }
        }

        assertThat(missing)
            .as("A controller endpoint is exempted from the authorization sweep because this "
                + "service performs the connection check on its behalf, and that check is now "
                + "gone. Either restore it or drop the controller's AUTHORIZED_ELSEWHERE entry "
                + "and assert in the controller.")
            .isEmpty();
    }

    /**
     * Guards the exemption list. If an exempt controller grows an endpoint that does take a
     * connection id, the entry is no longer true and the controller must be authorized
     * rather than skipped.
     */
    @Test
    void everyExemptControllerIsActuallyConnectionFree() throws IOException {
        List<String> offenders = new ArrayList<>();

        for (Path controller : controllers()) {
            String name = controller.getFileName().toString().replace(".java", "");
            if (!NOT_CONNECTION_SCOPED.contains(name)) {
                continue;
            }
            String source = Files.readString(controller);
            // A class-level @PreAuthorize already authorizes every handler in the file, so a
            // connectionId appearing inside one is not evidence of a gap.
            if (source.contains("@PreAuthorize")
                    && source.indexOf("@PreAuthorize") < source.indexOf("public class")) {
                continue;
            }
            for (Endpoint endpoint : endpoints(controller)) {
                String body = endpoint.body();
                if (!body.contains("connectionId")) {
                    continue;
                }
                // Acting on the caller's own identity is its own scope: the row is selected
                // by the authenticated username, so a connectionId in the body only picks
                // among things that caller already owns.
                boolean scopedToCaller = body.contains("requireCurrentUsername()")
                    || body.contains("getCurrentUsername()");
                if (!scopedToCaller && !AUTHORIZED.matcher(body).find()) {
                    offenders.add(endpoint.file() + ":" + endpoint.line() + " " + endpoint.mapping());
                }
            }
        }

        assertThat(offenders)
            .as("These endpoints live in a controller exempted as 'not connection scoped', "
                + "but they reference a connectionId and neither authorize it nor scope the "
                + "work to the authenticated caller. Either authorize them or remove the "
                + "controller from NOT_CONNECTION_SCOPED — the exemption list must stay true.")
            .isEmpty();
    }
}
