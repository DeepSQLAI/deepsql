#!/usr/bin/env python3
"""E2E edge-case suite for Company Knowledge Review approvals.

Requires a running stack + seeded PENDING suggestions:
  python3 scripts/self-host/seed-review-suggestions.py --count 20
  python3 scripts/self-host/e2e-review-approvals.py [connectionId]
"""
from __future__ import annotations

import json
import os
import sys
import urllib.error
from http.cookiejar import CookieJar
from pathlib import Path
from concurrent.futures import ThreadPoolExecutor
from urllib.request import HTTPCookieProcessor, Request, build_opener

sys.path.insert(0, str(Path(__file__).resolve().parent))
import vaultdb  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
ENV = ROOT / ".env"


def load_env(path: Path) -> dict[str, str]:
    out: dict[str, str] = {}
    if not path.exists():
        return out
    for line in path.read_text().splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        k, v = line.split("=", 1)
        out[k.strip()] = v.strip().strip('"').strip("'")
    return out


def main() -> int:
    env = {**load_env(ENV), **os.environ}
    email = env.get("DEEPSQL_INITIAL_ADMIN_EMAIL") or env.get("DEEPSQL_SMOKE_EMAIL")
    password = env.get("DEEPSQL_INITIAL_ADMIN_PASSWORD") or env.get("DEEPSQL_SMOKE_PASSWORD")
    if not email or not password:
        print("Missing admin credentials", file=sys.stderr)
        return 1

    backend = f"http://localhost:{env.get('DEEPSQL_BACKEND_PORT', '8080')}/api"
    opener = build_opener(HTTPCookieProcessor(CookieJar()))
    failures: list[str] = []

    def req(url: str, data=None, method: str | None = None):
        body = None
        headers: dict[str, str] = {}
        if data is not None:
            body = json.dumps(data).encode()
            headers["Content-Type"] = "application/json"
        m = method or ("POST" if data is not None else "GET")
        r = Request(url, data=body, headers=headers, method=m)
        with opener.open(r, timeout=180) as resp:
            return json.loads(resp.read().decode() or "null")

    def check(name: str, ok: bool, detail: str = ""):
        status = "PASS" if ok else "FAIL"
        print(f"[{status}] {name}{(': ' + detail) if detail else ''}")
        if not ok:
            failures.append(name)

    print("→ login")
    req(f"{backend}/auth/login", {"email": email, "password": password})

    conn = sys.argv[1] if len(sys.argv) > 1 else None
    if not conn:
        conns = req(f"{backend}/connections")
        items = conns if isinstance(conns, list) else (conns.get("connections") or [])
        conn = (items[0].get("connectionId") or items[0].get("id")) if items else None
    if not conn:
        print("No connection", file=sys.stderr)
        return 1
    print(f"→ connection {conn}")

    # ── 1. Count consistency: badge probe vs full page ─────────────────────
    probe = req(f"{backend}/code-scan/suggestions?connectionId={conn}&status=PENDING&page=0&size=1")
    page = req(f"{backend}/code-scan/suggestions?connectionId={conn}&status=PENDING&page=0&size=200")
    total = probe.get("totalElements")
    content_len = len(page.get("content") or [])
    check(
        "pending count consistency",
        total == content_len or (total > 200 and content_len == 200),
        f"totalElements={total} content={content_len}",
    )

    pending = [s for s in (page.get("content") or []) if s.get("status") == "PENDING"]
    schema_docs = [s for s in pending if s.get("targetKind") == "SCHEMA_DOC"]
    knowledge = [s for s in pending if s.get("targetKind") == "KNOWLEDGE_ENTRY"]
    check("has SCHEMA_DOC pending", len(schema_docs) >= 1, f"n={len(schema_docs)}")
    check("has KNOWLEDGE_ENTRY pending", len(knowledge) >= 1, f"n={len(knowledge)}")

    # ── 2. Constraint bootstrap: CODE_DERIVED must be allowed ──────────────
    constraint = vaultdb.query(
        "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
        "WHERE conname='schema_documentation_source_check'"
    )
    check("CODE_DERIVED in source CHECK", "CODE_DERIVED" in constraint, constraint)

    # ── 3. Approve SCHEMA_DOC (the customer failure path) ──────────────────
    target = schema_docs[0]
    approved = req(
        f"{backend}/code-scan/suggestions/{target['id']}/decide?connectionId={conn}",
        {"decision": "APPROVED"},
    )
    check(
        "approve SCHEMA_DOC",
        approved.get("status") == "APPROVED" and bool(approved.get("appliedDocId")),
        f"status={approved.get('status')} appliedDocId={approved.get('appliedDocId')}",
    )

    # ── 4. Idempotent re-approve ───────────────────────────────────────────
    again = req(
        f"{backend}/code-scan/suggestions/{target['id']}/decide?connectionId={conn}",
        {"decision": "APPROVED"},
    )
    check("re-approve is idempotent", again.get("status") == "APPROVED")

    # ── 5. Reject cannot follow approve ────────────────────────────────────
    try:
        req(
            f"{backend}/code-scan/suggestions/{target['id']}/decide?connectionId={conn}",
            {"decision": "REJECTED"},
        )
        check("reject after approve blocked", False, "expected 400")
    except urllib.error.HTTPError as e:
        check("reject after approve blocked", e.code == 400, f"http={e.code}")

    # ── 6. Reject a fresh pending item ─────────────────────────────────────
    page2 = req(f"{backend}/code-scan/suggestions?connectionId={conn}&status=PENDING&page=0&size=50")
    pending2 = page2.get("content") or []
    to_reject = next((s for s in pending2 if s.get("targetKind") == "SCHEMA_DOC"), None)
    if to_reject:
        rejected = req(
            f"{backend}/code-scan/suggestions/{to_reject['id']}/decide?connectionId={conn}",
            {"decision": "REJECTED", "note": "not useful"},
        )
        check("reject SCHEMA_DOC", rejected.get("status") == "REJECTED")
    else:
        check("reject SCHEMA_DOC", False, "no pending SCHEMA_DOC left")

    # ── 7. Bulk approve mix (schema + knowledge) reports per-id failures ───
    page3 = req(f"{backend}/code-scan/suggestions?connectionId={conn}&status=PENDING&page=0&size=50")
    mix = []
    for kind in ("SCHEMA_DOC", "KNOWLEDGE_ENTRY"):
        hit = next((s for s in (page3.get("content") or []) if s.get("targetKind") == kind), None)
        if hit:
            mix.append(hit["id"])
    if len(mix) >= 2:
        bulk = req(
            f"{backend}/code-scan/suggestions/bulk-decide?connectionId={conn}",
            {"ids": mix, "decision": "APPROVED"},
        )
        check(
            "bulk approve reports counts",
            bulk.get("requested") == len(mix)
            and bulk.get("succeeded") == len(mix)
            and bulk.get("failed") == 0
            and isinstance(bulk.get("failures"), list),
            json.dumps(bulk),
        )
    else:
        check("bulk approve reports counts", False, f"need 2 pending kinds, got {len(mix)}")

    # ── 8. Bulk with unknown id surfaces failure entry ─────────────────────
    page4 = req(f"{backend}/code-scan/suggestions?connectionId={conn}&status=PENDING&page=0&size=1")
    one = (page4.get("content") or [None])[0]
    if one:
        bulk_bad = req(
            f"{backend}/code-scan/suggestions/bulk-decide?connectionId={conn}",
            {"ids": [one["id"], "00000000-0000-0000-0000-000000000000"], "decision": "APPROVED"},
        )
        check(
            "bulk partial failure surfaces failures[]",
            bulk_bad.get("succeeded") == 1
            and bulk_bad.get("failed") == 1
            and len(bulk_bad.get("failures") or []) == 1,
            json.dumps(bulk_bad),
        )
    else:
        check("bulk partial failure surfaces failures[]", False, "no pending left")

    # ── 9. Knowledge entry approve lands in company knowledge ──────────────
    page5 = req(f"{backend}/code-scan/suggestions?connectionId={conn}&status=APPROVED&page=0&size=50")
    knowledge_ok = [
        s for s in (page5.get("content") or [])
        if s.get("targetKind") == "KNOWLEDGE_ENTRY" and s.get("appliedEntryId")
    ]
    check("knowledge approvals applied", len(knowledge_ok) >= 1, f"n={len(knowledge_ok)}")

    # ── 10. Simulate missing CODE_DERIVED then ensure repair path works ────
    # Existing CODE_DERIVED rows block shrinking the CHECK, so park them first —
    # in a scratch table, not by rewriting source in place. The original version
    # flipped every real CODE_DERIVED row to USER and never restored it, so a run
    # against a live install silently relabelled the user's approved code-derived
    # docs; it would also collide with V116's unique index the moment a user-written
    # doc existed for the same column.
    vaultdb.execute(
        "DROP TABLE IF EXISTS e2e_parked_code_derived; "
        "CREATE TABLE e2e_parked_code_derived AS "
        "  SELECT * FROM schema_documentation WHERE source='CODE_DERIVED'; "
        "DELETE FROM schema_documentation WHERE source='CODE_DERIVED'; "
        "ALTER TABLE schema_documentation DROP CONSTRAINT IF EXISTS schema_documentation_source_check; "
        "ALTER TABLE schema_documentation ADD CONSTRAINT schema_documentation_source_check "
        "CHECK (source::text = ANY (ARRAY['USER','AI_GENERATED','CSV_IMPORT']::text[]));",
    )
    broken = vaultdb.query(
        "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
        "WHERE conname='schema_documentation_source_check'"
    )
    check("can break CHECK for simulation", "CODE_DERIVED" not in broken)

    page6 = req(f"{backend}/code-scan/suggestions?connectionId={conn}&status=PENDING&page=0&size=20")
    schema_pending = next(
        (s for s in (page6.get("content") or []) if s.get("targetKind") == "SCHEMA_DOC"),
        None,
    )
    if schema_pending:
        bulk_broken = req(
            f"{backend}/code-scan/suggestions/bulk-decide?connectionId={conn}",
            {"ids": [schema_pending["id"]], "decision": "APPROVED"},
        )
        check(
            "broken CHECK yields failed bulk with error detail",
            bulk_broken.get("succeeded") == 0
            and bulk_broken.get("failed") == 1
            and "schema_documentation_source_check" in json.dumps(bulk_broken.get("failures")),
            json.dumps(bulk_broken),
        )
    else:
        check("broken CHECK yields failed bulk with error detail", False, "no pending SCHEMA_DOC")

    # Repair CHECK the same way the startup initializer does (without restart),
    # then un-park every row the simulation removed. The NOT EXISTS guard skips a
    # row the post-repair retry has meanwhile recreated under the same key, which
    # the unique index would otherwise reject.
    vaultdb.execute(
        "ALTER TABLE schema_documentation DROP CONSTRAINT IF EXISTS schema_documentation_source_check; "
        "ALTER TABLE schema_documentation ADD CONSTRAINT schema_documentation_source_check "
        "CHECK (source::text = ANY (ARRAY['USER','AI_GENERATED','CSV_IMPORT','CODE_DERIVED']::text[]));",
    )
    parked_count = vaultdb.query("SELECT count(*) FROM e2e_parked_code_derived")
    vaultdb.execute(
        "INSERT INTO schema_documentation SELECT p.* FROM e2e_parked_code_derived p "
        "WHERE NOT EXISTS (SELECT 1 FROM schema_documentation d WHERE d.id = p.id) "
        "  AND NOT EXISTS (SELECT 1 FROM schema_documentation d "
        "                  WHERE d.connection_id = p.connection_id "
        "                    AND d.object_type = p.object_type "
        "                    AND d.object_name = p.object_name "
        "                    AND coalesce(d.parent_object,'') = coalesce(p.parent_object,'') "
        "                    AND d.source = p.source); "
        "DROP TABLE e2e_parked_code_derived;",
    )
    restored = vaultdb.query("SELECT count(*) FROM schema_documentation WHERE source='CODE_DERIVED'")
    check(
        "parked CODE_DERIVED rows restored",
        int(restored) >= int(parked_count),
        f"parked={parked_count} now={restored}",
    )
    repaired = vaultdb.query(
        "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
        "WHERE conname='schema_documentation_source_check'"
    )
    check("CHECK repaired with CODE_DERIVED", "CODE_DERIVED" in repaired)

    if schema_pending:
        retry = req(
            f"{backend}/code-scan/suggestions/{schema_pending['id']}/decide?connectionId={conn}",
            {"decision": "APPROVED"},
        )
        check(
            "approve SCHEMA_DOC after CHECK repair",
            retry.get("status") == "APPROVED" and bool(retry.get("appliedDocId")),
            f"status={retry.get('status')}",
        )

    # ── 11. Duplicate schema_documentation rows must not wedge approve ─────
    # The customer's "APPROVED 0 OF 2" was two IncorrectResultSizeDataAccessException
    # ("Query did not return a unique result: 2 results were returned") thrown by the
    # upsert lookup in CodeSuggestionApplier.applySchemaDoc against duplicate rows a
    # double-submitted bulk approve had left behind.
    #
    # Approve once to learn the exact key the applier writes (it database-qualifies
    # parent_object via resolveDatabaseName, so guessing it from existing rows picks
    # the wrong prefix and the test silently exercises nothing), then reset the
    # suggestion, plant an older duplicate under that key, and approve again.
    page7 = req(f"{backend}/code-scan/suggestions?connectionId={conn}&status=PENDING&page=0&size=50")
    dup_target = next(
        (s for s in (page7.get("content") or [])
         if s.get("targetKind") == "SCHEMA_DOC" and "." in (s.get("targetObject") or "")),
        None,
    )
    if not dup_target:
        check("duplicate rows collapse on approve", False, "no pending column SCHEMA_DOC")
    else:
        first = req(
            f"{backend}/code-scan/suggestions/{dup_target['id']}/decide?connectionId={conn}",
            {"decision": "APPROVED"},
        )
        doc_id = first.get("appliedDocId")
        check("duplicate-test baseline approve", bool(doc_id), json.dumps(first)[:120])

        key = vaultdb.query(
            "SELECT object_name || '|' || coalesce(parent_object,'') "
            f"FROM schema_documentation WHERE id='{doc_id}'"
        )
        column, _, qualified = key.partition("|")
        # Anchor the planted row's timestamp to the real one: approve UPDATES the
        # row an earlier scan wrote, so its created_at is historical, and a
        # hardcoded "old" date can easily be the newer of the two.
        baseline_created = vaultdb.query(
            f"SELECT created_at FROM schema_documentation WHERE id='{doc_id}'"
        )
        where = (
            f"connection_id='{conn}' AND object_type='COLUMN' AND object_name='{column}' "
            f"AND coalesce(parent_object,'')='{qualified}' AND source='CODE_DERIVED'"
        )

        # The unique index is what stops this happening for real, so drop it for the
        # simulation. restore_unique_index() runs even if an assertion below throws —
        # leaving the install without the constraint would be worse than a failed test.
        vaultdb.execute("DROP INDEX IF EXISTS ux_schema_doc_target")
        try:
            vaultdb.execute(
                "INSERT INTO schema_documentation "
                "(id, connection_id, object_type, object_name, parent_object, description, "
                " source, created_at) "
                f"VALUES ('e2e-dup-older', '{conn}', 'COLUMN', '{column}', '{qualified}', "
                f"'stale duplicate', 'CODE_DERIVED', TIMESTAMP '{baseline_created}' - INTERVAL '1 hour')"
            )
            vaultdb.execute(
                "UPDATE code_knowledge_suggestion SET status='PENDING', applied_doc_id=NULL, "
                f"decided_at=NULL, decided_by=NULL WHERE id='{dup_target['id']}'"
            )
            # A second, already-approved suggestion pointing at the row that is about
            # to be deleted — its reference must follow the survivor, not dangle.
            vaultdb.execute(
                "UPDATE code_knowledge_suggestion SET applied_doc_id='e2e-dup-older' "
                f"WHERE id = (SELECT id FROM code_knowledge_suggestion WHERE connection_id='{conn}' "
                f"  AND status='APPROVED' AND id <> '{dup_target['id']}' LIMIT 1)"
            )
            before = vaultdb.query(f"SELECT count(*) FROM schema_documentation WHERE {where}")
            check("duplicate pair seeded", before == "2", f"rows={before}")

            dup_bulk = req(
                f"{backend}/code-scan/suggestions/bulk-decide?connectionId={conn}",
                {"ids": [dup_target["id"]], "decision": "APPROVED"},
            )
            check(
                "duplicate rows collapse on approve",
                dup_bulk.get("succeeded") == 1 and dup_bulk.get("failed") == 0,
                json.dumps(dup_bulk),
            )
            after = vaultdb.query(f"SELECT count(*) FROM schema_documentation WHERE {where}")
            check("collapsed to a single row", after == "1", f"rows={after}")
            survivor = vaultdb.query(f"SELECT id FROM schema_documentation WHERE {where}")
            # Newest wins — the row applied_doc_id already pointed at.
            check("newest duplicate survived", survivor == doc_id, f"{survivor} vs {doc_id}")
            content = vaultdb.query(
                f"SELECT description FROM schema_documentation WHERE id='{survivor}'"
            )
            check("survivor holds approved content", "stale duplicate" not in content, content[:60])
            gone = vaultdb.query(
                "SELECT count(*) FROM schema_documentation WHERE id='e2e-dup-older'"
            )
            check("older duplicate deleted", gone == "0", gone)
            embedding = vaultdb.query("SELECT count(*) FROM rag_documents WHERE id='e2e-dup-older'")
            check("loser embedding removed", embedding == "0", embedding)
            dangling = vaultdb.query(
                "SELECT count(*) FROM code_knowledge_suggestion WHERE applied_doc_id='e2e-dup-older'"
            )
            check("applied_doc_id repointed off the deleted row", dangling == "0", dangling)
            orphans = vaultdb.query(
                "SELECT count(*) FROM code_knowledge_suggestion s WHERE s.applied_doc_id IS NOT NULL "
                "AND NOT EXISTS (SELECT 1 FROM schema_documentation d WHERE d.id = s.applied_doc_id)"
            )
            check("no dangling applied_doc_id anywhere", orphans == "0", orphans)
        finally:
            # Only ever remove the planted row, and only while nothing points at it.
            # An earlier version deleted it unconditionally, which destroyed real
            # approved content on the run where collapse had chosen it as survivor.
            vaultdb.execute(
                "DELETE FROM schema_documentation d WHERE d.id='e2e-dup-older' "
                "AND NOT EXISTS (SELECT 1 FROM code_knowledge_suggestion s "
                "                WHERE s.applied_doc_id = d.id)"
            )
            vaultdb.execute(
                "CREATE UNIQUE INDEX IF NOT EXISTS ux_schema_doc_target ON schema_documentation "
                "(connection_id, object_type, object_name, coalesce(parent_object, ''), source)"
            )

    # ── 12. The unique index refuses a second row for the same key ─────────
    idx = vaultdb.query(
        "SELECT count(*) FROM pg_indexes WHERE indexname='ux_schema_doc_target'"
    )
    check("unique index present", idx == "1", idx)
    try:
        vaultdb.execute(
            "INSERT INTO schema_documentation "
            "(id, connection_id, object_type, object_name, parent_object, description, source, created_at) "
            "SELECT 'e2e-dup-clash', connection_id, object_type, object_name, parent_object, "
            "       description, source, now() "
            "FROM schema_documentation LIMIT 1"
        )
        vaultdb.execute("DELETE FROM schema_documentation WHERE id='e2e-dup-clash'")
        check("unique index rejects a duplicate key", False, "insert succeeded")
    except Exception as e:  # noqa: BLE001 - psql exiting non-zero IS the assertion
        check("unique index rejects a duplicate key", True, type(e).__name__)

    # ── 13. Concurrent approve of one suggestion writes exactly one doc row ─
    # Two bulk approves racing on the same PENDING row is what created the
    # duplicates in the first place; approve now loads the suggestion FOR UPDATE.
    page8 = req(f"{backend}/code-scan/suggestions?connectionId={conn}&status=PENDING&page=0&size=50")
    race_target = next(
        (s for s in (page8.get("content") or [])
         if s.get("targetKind") == "SCHEMA_DOC" and "." in (s.get("targetObject") or "")),
        None,
    )
    if not race_target:
        check("concurrent approve writes one row", False, "no pending column SCHEMA_DOC")
    else:
        url = f"{backend}/code-scan/suggestions/bulk-decide?connectionId={conn}"
        payload = {"ids": [race_target["id"]], "decision": "APPROVED"}
        with ThreadPoolExecutor(max_workers=2) as pool:
            results = [f.result() for f in
                       [pool.submit(req, url, payload), pool.submit(req, url, payload)]]
        parent, _, column = race_target["targetObject"].partition(".")
        rows = vaultdb.query(
            f"SELECT count(*) FROM schema_documentation WHERE connection_id='{conn}' "
            f"AND object_type='COLUMN' AND object_name='{column}' "
            f"AND parent_object LIKE '%{parent}' AND source='CODE_DERIVED'"
        )
        check("concurrent approve writes one row", rows == "1", f"rows={rows}")
        check(
            "neither concurrent approve reported an error",
            all(r.get("failed") == 0 for r in results),
            json.dumps(results),
        )

    print()
    if failures:
        print(f"✗ {len(failures)} failure(s): {failures}", file=sys.stderr)
        return 1
    print("✓ All review-approval edge cases passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
