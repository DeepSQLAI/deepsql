# SDD ledger — plan: docs/superpowers/plans/2026-09-04-preflight-migration-review.md

Spec: docs/superpowers/specs/2026-09-04-preflight-migration-review-design.md (read — binding authority)
Branch: feat/preflight-migration-review (off main; isolated, no nested worktree)

## Pre-flight conflict scan

### Cross-task rows (pairs sharing a file or interface)

| Pair | Produces → Consumes | Finding |
|---|---|---|
| T1 → T2 | `DdlFacts` (12-field positional record), `DdlOperation` enum → T2 test code constructs `DdlFacts` positionally | **RISK**: field order must match exactly. T2's tests build DdlFacts by position in 6 places. A reorder in T1 breaks T2 as a silent rule failure. Ruling below. |
| T1 → T3 | `DdlStatementParser.parse` → T3 verification harness | Consistent. T3 parses the same CsvSource SQL it executes. |
| T2 → T3 | `PostgresMigrationRiskProvider.classify`, `TableFacts`, `LockRef` → T3 asserts rules vs engine | Consistent. T3 uses no-arg constructor; T2 declares `@Component` with no deps. OK. |
| T2 → T4 | `MigrationRiskProvider` interface → `DatabaseDialect.migrationRisk()` | Consistent. T4 adds accessor; T2 creates interface. Order correct. |
| T1+T2 → T4 | parser + provider → `MigrationRiskService` | Consistent. |
| T4 → T5 | `POST /migrations/analyze` + `MigrationRiskReport` JSON → MCP tool/CLI/summarizer | Consistent. Field names in T5's summarizer (`verdict`, `locks[].table/.mode/.blocks`, `rewritesTable`, `tableRows`, `estimatedDuration`, `reason`, `saferAlternative`) all exist in T2's record. |
| T4 → T6 | endpoint → curl verification | Consistent. |
| T4 ↔ existing `ConnectionScopedAuthorizationSafetyTest` | new `*Controller.java` → test walks ALL controllers (verified: line 112 `endsWith("Controller.java")`) | **Confirmed dependency, already handled**: T4 Step 6 runs that test. New controller must call `assertCanReadConnectionContent` or the build fails. |

### Per-task internal consistency rows

| Task | Finding |
|---|---|
| T1 | Self-consistent. 11 tests match the parser's branches. Shim verified working during planning. |
| T2 | Self-consistent. 11 tests map to the 10 rule branches + UNKNOWN. `report(...)` helper used uniformly. |
| T3 | Self-consistent. **Note**: `ALTER COLUMN TYPE varchar(50)` CsvSource expects `rewrites=true`; T2's `alterType` hardcodes `rewrites=true` for all type changes, so widening (varchar(50)→varchar(100), measured as NO rewrite) would fail if added. Not in the CsvSource, so no conflict today. Flagged as a known limitation in the spec. |
| T4 | **Plan defect found**: `tableFacts` casts `(ConnectionRequest) request` from an `Object` param. Verified: `CredentialService.getDecryptedConnection` already returns `com.dbaagent.model.ConnectionRequest`. Ruling below. |
| T5 | Self-consistent. Covers all 6 doc surfaces the MCP release rules require. |
| T6 | Self-consistent. |

### Rulings (pre-flight)

Ruling: T4's `tableFacts(String, Object, String)` signature and its `(ConnectionRequest)` cast are a plan defect — type the parameter as `com.dbaagent.model.ConnectionRequest` and drop the cast. Why: verified `CredentialService.getDecryptedConnection` returns exactly that type, so the cast is dead weight that would suppress a real compile-time check. Cost if wrong: none — a compile error surfaces immediately in T4's own test step.

Ruling: T1 implementer must treat the `DdlFacts` field order in the plan as fixed and non-negotiable, since T2's tests construct it positionally. Why: a 12-field positional record is the most likely silent cross-task break in this plan. Cost if wrong: T2's tests fail with confusing rule-level messages instead of a signature error, costing a fix round.

Ruling: no nested worktree — work proceeds on `feat/preflight-migration-review`, already isolated off main. Why: the branch was created for this feature at the user's instruction and contains only its spec/plan commits. Cost if wrong: none; branch is separable and unpushed.

## Task log

### Task 1

Task 1: implemented (commit 66540a9, 11/11 tests passing). Review: spec ✅, quality NOT APPROVED — 1 Critical, 3 Important, 2 Minor. Critical + Important #2/#3 are plan-mandated (the brief's own code); ruled on below.

Ruling: Finding #1 (multi-clause ALTER silently judged on `exprs.get(0)`) — FIX, overriding the plan text. Why: the spec's binding constraint is fail-closed, and this fails OPEN in the worst possible way for this feature — `ALTER TABLE t ADD COLUMN a text, ADD COLUMN b uuid DEFAULT gen_random_uuid()` returns facts describing only the harmless first clause, so Task 2 would rule the whole statement SAFE while a table-rewriting clause is invisible. Verified by the reviewer by execution, not inference. Fix is minimal: return `Optional.empty()` when `getAlterExpressions().size() > 1`. Cost if wrong: multi-clause ALTERs report UNKNOWN instead of being analyzed — a usability loss, never a safety loss, and the honest answer given a single-fact record cannot represent them.

Ruling: Finding #2 (`ADD_CHECK` via `rawSql.contains("CHECK")`) — FIX, overriding the plan text. Why: proven false positive on `ADD COLUMN check_flag boolean DEFAULT true`; `check_flag`/`checked_at`/`checkout_id` are ordinary column names, and misclassification discards the real column/type/default facts, so an ADD_COLUMN with a volatile default named e.g. `checkout_id` would be analyzed as a CHECK constraint. Fix: detect ADD CHECK from the parse tree / a constraint-anchored pattern, not a bare substring of the whole statement. Cost if wrong: a genuine ADD CHECK could fall through to UNKNOWN — fails closed, acceptable.

Ruling: Finding #3 (`REFERENCES` regex matches inside string literals) — FIX (cheap), though currently latent. Why: only read on the ADD_FOREIGN_KEY path today, so it is a landmine rather than a live bug, but gating extraction on `operation == ADD_FOREIGN_KEY` is a two-line change and removes the trap before Task 2 consumes the record. Cost if wrong: negligible.

Ruling: Finding #4 (no DROP_COLUMN / RENAME_COLUMN tests) — FIX by adding the two tests. Why: Task 2 consumes `DdlFacts` for both operations; their field shape is currently asserted by nothing. Cheap to close now, expensive to debug later as a cross-task mismatch. Cost if wrong: none.

Ruling: Findings #5 (clause-scoped regex extraction) and #6 (NOT VALID end-anchored) — DEFER as minors. Why: #5 is subsumed by the #1 fix (rejecting multi-clause statements removes the cross-clause attribution path entirely); #6 fails closed by the reviewer's own analysis. Cost if wrong: low; both recorded for the final whole-branch review.

Task 1: minor (deferred): DEFAULT_FN/REFERENCES extract from whole rawSql rather than the clause substring — subsumed by the multi-clause rejection, revisit if multi-clause support is ever added.
Task 1: minor (deferred): NOT_VALID regex is end-anchored; mid-statement NOT VALID fails closed rather than being flagged.
Task 1: fix round 1/5 (4 addressed, 0 open — multi-clause fail-open, ADD_CHECK substring misclassification, REFERENCES gating (test-only, claim verified), DROP/RENAME coverage; commits 66540a9..ea32660)
Task 1: complete (commits ccc55f2..ea32660, review clean, 16/16 tests)

Note for Task 2: parser now returns Optional.empty() for ANY multi-clause ALTER. Task 2's classify() never sees them; the service layer (T4) surfaces them as UNKNOWN/safeToRun=false.

### Task 2
Task 2: implemented (commit 70d5271, 10/10 tests). Review: spec ✅, quality NOT APPROVED — 1 Critical, 1 Important, 2 Minor.

Ruling: Critical (`isVolatile` fails OPEN for any function outside the 4-name hardcoded set) — FIX in Task 2. Why: `ADD COLUMN c uuid DEFAULT my_custom_uuid()` — a user-defined VOLATILE function — is not in the set, so it reaches the final SAFE branch and is reported metadata-only while it actually rewrites the whole table under ACCESS EXCLUSIVE. Same failure class as Task 1's multi-clause bug: confidently SAFE for something not positively recognised, which the spec's fail-closed rule forbids. The reviewer also correctly notes my design named `pg_proc.provolatile` as the primary source but NO task wires it up — the "fallback" is the only source. Fix: an unrecognised default function yields CAUTION with an explicit "cannot verify volatility" reason, never SAFE. Cost if wrong: an exotic-but-stable default is reported CAUTION instead of SAFE — an over-warning, which is the correct direction to err.

Ruling: Important (5 of 10 branches untested — ALTER_COLUMN_TYPE, SET_NOT_NULL, RENAME_COLUMN, ADD_CHECK, VALIDATE_CONSTRAINT) — FIX by adding one test per branch. Why: ADD_CHECK and SET_NOT_NULL encode real conditional logic (notValid split, size threshold). Task 3 verifies rewrite/lock behaviour against a live DB but does NOT assert verdict strings or safeToRun, so these branches are currently asserted by nothing. Cost if wrong: none.

Ruling: Minor #1 (alterType reason text describes a widening exception the code cannot detect) — FIX the wording in the same pass. Why: cheap, and the text currently implies a check that does not exist, which is exactly the false-confidence problem this feature targets. Cost if wrong: none.

Ruling: Minor #2 (addForeignKey NOT VALID hardcodes "seconds" rather than bucket(t)) — ACCEPT as-is, plan-mandated. Why: NOT VALID skips the validating scan, so its cost is lock-acquisition-bound rather than table-size-bound; the hardcoded bucket is semantically right, not an oversight. Cost if wrong: a coarse duration label is slightly off for a huge table; no safety impact.

Ruling: brief's "11 tests" vs 10 supplied — plan defect, not a code defect. The implementer correctly refused to invent a test to match a count. Resolved by the 5 added branch tests above.
Task 2: fix round 1/5 (3 addressed, 0 open — volatility fail-open, 5 untested branches, alterType wording; commits 70d5271..3b506f5)
Task 2: complete (commits ea32660..3b506f5, review clean, 18/18 tests)
Task 2: minor (deferred, accepted): addForeignKey NOT VALID hardcodes "seconds" rather than bucket(t) — semantically correct (skips the validating scan).

### Task 3
Task 3: implemented (commit ccaf725). Review: spec ✅, quality APPROVED — 0 Critical, 0 Important, 1 Minor. Verification runs against real Testcontainers postgres:18; 13/13 verification + 18/18 rules, no regression. Reviewer independently re-verified the physical claims (filenode, pg_locks) against a live PG18 and confirmed the harness is NOT vacuous: a constant-returning classify() would fail 5 of 9 CSV rows.

Ruling: the widening-varchar rule gap (alterType always claims rewritesTable=true; varchar(50)->varchar(100) measurably does not rewrite) — ACCEPT as documented, do not fix. Why: structurally unfixable where it sits — DdlFacts carries no old type and the parser has no catalog access, so detecting it needs a schema lookup that belongs in a later task, not the verification harness. It over-warns, which is the correct direction under fail-closed: it never calls a risky operation safe. The implementer made the gap explicit as a passing test that asserts BOTH the engine truth (no rewrite) and the rule's current claim (rewrite), so it is tracked rather than silent. Cost if wrong: false-DANGER on a common low-risk operation (bumping a varchar length) — alert fatigue, no safety impact. Recorded below as a deferred minor for the final review.

Task 3: minor (deferred): alterType over-warns on same-family varchar widening — real UX cost (false DANGER on a routine operation), needs old-type info in DdlFacts via a catalog lookup to fix properly.
Task 3: minor (deferred): volatilityClassificationMatchesCatalog checks pg_proc directly but never feeds the result back through classify(), so it cannot catch VOLATILE_FALLBACK/STABLE_FALLBACK drifting from the catalog for functions not also covered by a CSV row.
Task 3: minor (deferred): Testcontainers Ryuk reaper disabled for the sibling-Docker-socket setup — a hard-killed JVM would leak a Postgres container. Not observed; jtest.sh is not used by CI (verified).
Task 3: minor (deferred): `-o` (offline Maven) removed from jtest.sh, so local runs need network egress. Zero CI blast radius — CI uses ./mvnw directly (verified).

### Task 4
Task 4: implemented (commit 5dff288). 49/49 on *MigrationRisk*/*DdlStatement*, ConnectionScopedAuthorizationSafetyTest 7/7. Review: spec ✅, quality NOT APPROVED — 0 Critical, 1 Important, 2 Minor.

Ruling: Important (duplicate assertCanReadConnectionContent in BOTH controller and service) — FIX by removing the controller-level call and using the existing AUTHORIZED_ELSEWHERE mechanism. Why: I verified the reviewer's claim directly — ConnectionScopedAuthorizationSafetyTest lines 52-77 already provide AUTHORIZED_ELSEWHERE + DELEGATED_CHECKS + everyDelegatedCheckStillExists(), with DashboardWorkspaceController as the documented precedent, and the doc comment says in as many words "Asserting again in the controller would duplicate a check that could then drift." My instruction not to add an exemption entry meant do not add a BOGUS entry hiding a real gap; the implementer reasonably read it as absolute. This is not defence-in-depth: real defence-in-depth layers DIFFERENT mechanisms (as this codebase does pairing SQL classification with connection.setReadOnly(true)); duplicating the identical call with the identical failure mode adds no protection, only drift surface. The service check is verified load-bearing and fires first (MigrationRiskServiceTest.authorizationIsAssertedBeforeAnyWork), and adding MigrationRiskService.java to DELEGATED_CHECKS keeps the build failing if it is ever removed. Cost if wrong: if both the controller call is removed AND the DELEGATED_CHECKS entry is wrong, the scanner could pass an unguarded endpoint — mitigated by the re-derivation test, and the service unit test independently proves the assert fires first.

Ruling: Minor (no MockMvc/controller-level integration test) — ACCEPT for this task, covered by Task 6. Why: Task 6 exercises the endpoint live over HTTP with curl including a 403 check from an unauthorized user, which is stronger evidence than a MockMvc test. Cost if wrong: a controller wiring bug would surface in Task 6 rather than in unit tests — later, but still before merge.

Ruling: Minor (unused import risk after removing the controller assert) — folded into the fix instruction.
Task 4: fix round 1/5 (1 addressed, 0 open — controller/service duplicate assert replaced with AUTHORIZED_ELSEWHERE + DELEGATED_CHECKS; commits 5dff288..cf700f0)
Task 4: complete (commits ccaf725..cf700f0, review clean, 49/49 + safety 7/7)

Ruling: the substring-matching weakness in everyDelegatedCheckStillExists (a COMMENTED-OUT assert still passes; only outright DELETION fails) — ACCEPT and defer, do not fix here. Why: verified pre-existing and codebase-wide, NOT introduced by this task — the reviewer traced everyConnectionScopedEndpointAuthorizesTheCaller and confirmed the ordinary AUTHORIZED scanner has the identical weakness, so a commented-out assert in ANY controller passes silently. Hardening it means editing a shared, already-audited safety test that DashboardWorkspaceService also depends on — out of scope for this feature and exactly the "improving adjacent code" this plan should not do. Realistic threat model favours deletion (which does fail loudly) over deliberate commenting; Task 6's live HTTP 403 check is black-box and would catch a neutralised assert however it was neutralised. Cost if wrong: a regression introduced by commenting out an assert would be caught later (code review, Task 6-style live check) rather than by the build. FLAG THIS TO THE USER — it is a pre-existing codebase-wide gap they may want ticketed separately.

Task 4: minor (deferred): everyDelegatedCheckStillExists and the AUTHORIZED scanner both use plain substring/regex matching over raw file text with no comment stripping — a commented-out authorization assert satisfies both. Pre-existing, codebase-wide, affects DashboardWorkspaceService too.

### Task 5
Task 5: implemented (commit 4740333, npm test 270/270, all six doc surfaces verified touched, DTO field names verified matching). Review: spec ✅, quality NOT APPROVED — 2 Critical-leaning, 2 Minor.

Ruling: both presentation bugs — FIX. Why: both occur ONLY on the failure paths, which is precisely where a safety tool must read clearly. (a) Task 4's Long.MAX_VALUE sentinel means "could not measure this table, assuming the worst"; rendering it as "~9,223,372,036,854,776,000 rows" makes a deliberate fail-closed signal look like corrupted data, so a user dismisses the warning as a bug rather than heeding it — the fail-closed design is defeated at the presentation layer. (b) The CLI printing "UNKNOWN  UNKNOWN on null" is the unparseable-DDL path, the exact moment the user most needs a clear "I could not analyze this." Cost if wrong: none, presentation-only changes behind existing verdict logic.

Ruling: DANGER/FAILS not visually distinguished — FIX (cheap). Why: the verdict is the single most important token in the output and currently reads with the same weight as SAFE. Cost if wrong: none.

Ruling: missing ApiError/403-404 handling in migration.js — FIX. Why: 12+ sibling command files follow that pattern; an unauthorized user would otherwise get a raw error rather than the friendly message every other command gives. Consistency here is a real usability property, not style. Cost if wrong: none.

Ruling: no unit tests for analyze_migration — FIX by adding them. Why: the closest sibling (analyze.js) has tests, and a fully green 270-test suite missed BOTH confirmed bugs — that is the argument for the tests, not against them. Cost if wrong: none.
Task 5: fix round 1/5 (4 addressed + tests added + a 5th self-found bug fixed, 0 open; commits 4740333..fd57d37, npm test 291/291)
Task 5: complete (commits cf700f0..fd57d37, review clean)

Note: writing the required tests exposed a 5th bug nobody had caught — buildOpts() in mcp/src/cli.js never wired --sql into opts, so the CLI command was NON-FUNCTIONAL end to end regardless of the presentation fixes. Two review passes and a green 270-test suite had missed it. Reviewer verified the fix through the real argv parser, confirmed blast radius is clean (`sql` is a new key with no other reader), and confirmed the new tests build opts via the real parser so a regression would fail.

Task 5: minor (deferred): sentinel floor 9e18 is a heuristic — a legitimate (physically impossible) row count between 9e18 and Long.MAX_VALUE would be reported as unknown.
Task 5: minor (deferred): verdictMarker/isRowCountUnknown duplicated between CLI and MCP lib rather than shared — matches the codebase's existing per-file formatting-helper convention.

### Task 6
Task 6: implemented (commit 81b13c0). All 5 live checks PASS against a freshly rebuilt backend, real Postgres 18, real HTTP. Review: spec ✅, quality APPROVED — 0 Critical, 1 Important (CLAUDE.md overclaim), 0 Minor.

Live results: (1) DEFAULT now() -> SAFE, rewritesTable=false. (2) DEFAULT gen_random_uuid() on 1.2M rows -> DANGER, rewritesTable=true. (3) ADD FOREIGN KEY -> locks array with TWO entries (child + t), both ShareRowExclusiveLock. (4) "not sql" -> UNKNOWN, safeToRun=false. (5) analyst2 (DEVELOPER, zero grants, verified by both GET /connections returning [] and a direct DB query) -> HTTP 403.

The reviewer independently RE-RAN check 5 with controls the original lacked: valid session -> 200 on /auth/me, no cookie -> distinctly-shaped 401, unknown connectionId -> 404. That rules out 401-misread, routing-404, and swallowed-500 as explanations for the 403. Strongest evidence in the feature.

Ruling: Important (CLAUDE.md lock-ranking bullet describes test-only logic as if it were production behaviour) — FIX. Why: the explicit LOCK_STRENGTH ordering lives only in PostgresMigrationRiskVerificationTest; the shipped provider hardcodes a lock mode per rule and ranks nothing. CLAUDE.md is the file future agents trust most and it is not covered by tests, so an inaccuracy there is worse than one in code. Cost if wrong: none, documentation-only.

Process trap worth keeping: the implementer's first backend build was silently killed and produced no new image, yet `docker compose ps` reported "healthy" against a STALE 2-day-old image. Caught only via `docker inspect ... Created`. A live check against a stale image would have verified nothing while reporting success — exactly the false-green class this repo's Verification Anti-Patterns section catalogues.
Task 6: fix round 1/5 (1 addressed, 0 open — CLAUDE.md lock-ranking overclaim corrected and attributed to the test; commits 81b13c0..c288004). Controller verified the diff directly rather than spending a review seat on a single-purpose doc reword.
Task 6: complete (commits fd57d37..c288004, review clean)

## All tasks complete. Dispatching final whole-branch review (merge base 819c286).

## FINAL WHOLE-BRANCH REVIEW: DON'T SHIP — 2 Critical fail-open defects

C1: CCJSqlParserUtil.parse() silently returns only the FIRST statement. "ALTER TABLE t ADD COLUMN a text; DROP TABLE users;" -> verdict SAFE, safeToRun true. Reviewer confirmed live over HTTP AND confirmed Postgres actually dropped the table. Task 1 guarded the multi-CLAUSE boundary; nobody guarded the multi-STATEMENT boundary.
C2: ALTER TABLE ... DROP CONSTRAINT is misclassified as DROP_COLUMN -> SAFE, with a false reason ("Dropping a column is metadata-only") and a single-table locks array. Reviewer measured that it actually takes AccessExclusiveLock on the REFERENCED table too — the inverse of the FK finding the spec calls the least-expected hazard. DROP CONSTRAINT appears in ~7 of this repo's own migration files.
I1: ALTER COLUMN ... DROP NOT NULL misclassified as ALTER_COLUMN_TYPE -> DANGER + rewritesTable=true. Over-warns (not a safety hole) but is a wrong claim on a common operation, same weak "ALTER".equals(op) branch as C2.

Reviewer independently re-confirmed: Testcontainers verification is genuinely non-vacuous (measured 4 true / 4 false rewrite split across the CSV rows; a constant-returning classify() fails at least 4 of 9); authorization is sound and load-bearing (assert is the FIRST statement, before credential decryption and session opening; AUTHORIZED_ELSEWHERE entry is line-anchored so moving the handler fails the build); all six MCP surfaces updated, tool count 45 matches docs.

Ruling: dispatch ONE fix wave for C1 + C2 + I1 (the skill mandates one fix dispatch, not one per finding). All three live in DdlStatementParser and share the same root cause — classification branches that assume rather than verify what the parse tree contains. Cost if wrong: rework in one file, visible in the re-review.
Ruling: ALTER COLUMN TYPE over-warning — SHIP as documented (reviewer concurs: errs toward DANGER, never SAFE, documented in three places).
Ruling: hardcoded volatility sets without the live pg_proc lookup — SHIP (reviewer concurs: CAUTION-on-unknown is honest and fails safe; the missing lookup is precision, not safety).
Ruling: all 8 deferred minors — DEFER per the reviewer's triage; none merge-blocking.
Final fix wave: complete (commit a214a9d). C1/C2/I1 all ADDRESSED, independently re-verified against real jsqlparser-5.2. Trailing-semicolon behaviour confirmed (count=1, no regression). I1 blast radius = exactly DROP NOT NULL. No existing assertion weakened (16 original tests byte-identical). Tests 22/22, 55/55, 7/7. Re-review verdict: SHIP.
