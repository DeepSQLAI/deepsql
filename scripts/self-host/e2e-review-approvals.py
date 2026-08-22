#!/usr/bin/env python3
"""E2E edge-case suite for Company Knowledge Review approvals.

Requires a running stack + seeded PENDING suggestions:
  python3 scripts/self-host/seed-review-suggestions.py --count 20
  python3 scripts/self-host/e2e-review-approvals.py [connectionId]
"""
from __future__ import annotations

import json
import os
import subprocess
import sys
import urllib.error
from http.cookiejar import CookieJar
from pathlib import Path
from urllib.request import HTTPCookieProcessor, Request, build_opener

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
    constraint = subprocess.check_output(
        [
            "sudo", "-u", "postgres", "psql", "-d", "dba_agent", "-At", "-c",
            "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
            "WHERE conname='schema_documentation_source_check'",
        ],
        text=True,
    ).strip()
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
    # Existing CODE_DERIVED rows block shrinking the CHECK, so park them first.
    subprocess.check_call([
        "sudo", "-u", "postgres", "psql", "-d", "dba_agent", "-c",
        "UPDATE schema_documentation SET source='USER' WHERE source='CODE_DERIVED'; "
        "ALTER TABLE schema_documentation DROP CONSTRAINT IF EXISTS schema_documentation_source_check; "
        "ALTER TABLE schema_documentation ADD CONSTRAINT schema_documentation_source_check "
        "CHECK (source::text = ANY (ARRAY['USER','AI_GENERATED','CSV_IMPORT']::text[]));",
    ])
    broken = subprocess.check_output(
        [
            "sudo", "-u", "postgres", "psql", "-d", "dba_agent", "-At", "-c",
            "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
            "WHERE conname='schema_documentation_source_check'",
        ],
        text=True,
    ).strip()
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

    # Repair CHECK the same way the startup initializer does (without restart).
    subprocess.check_call([
        "sudo", "-u", "postgres", "psql", "-d", "dba_agent", "-c",
        "ALTER TABLE schema_documentation DROP CONSTRAINT IF EXISTS schema_documentation_source_check; "
        "ALTER TABLE schema_documentation ADD CONSTRAINT schema_documentation_source_check "
        "CHECK (source::text = ANY (ARRAY['USER','AI_GENERATED','CSV_IMPORT','CODE_DERIVED']::text[]));",
    ])
    repaired = subprocess.check_output(
        [
            "sudo", "-u", "postgres", "psql", "-d", "dba_agent", "-At", "-c",
            "SELECT pg_get_constraintdef(oid) FROM pg_constraint "
            "WHERE conname='schema_documentation_source_check'",
        ],
        text=True,
    ).strip()
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

    print()
    if failures:
        print(f"✗ {len(failures)} failure(s): {failures}", file=sys.stderr)
        return 1
    print("✓ All review-approval edge cases passed")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
