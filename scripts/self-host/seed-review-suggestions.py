#!/usr/bin/env python3
"""Seed code-scan review suggestions for E2E testing (no LLM required).

Creates a code_scan_source + completed job + N PENDING suggestions
(SCHEMA_DOC + KNOWLEDGE_ENTRY) against an existing DeepSQL connection.

Usage (repo root, backend running with auth):
  python3 scripts/self-host/seed-review-suggestions.py [connectionId] [--count 50]
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import uuid
from datetime import datetime, timezone
from http.cookiejar import CookieJar
from pathlib import Path
from urllib.request import HTTPCookieProcessor, Request, build_opener
import urllib.error

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
    parser = argparse.ArgumentParser()
    parser.add_argument("connection_id", nargs="?")
    parser.add_argument("--count", type=int, default=50)
    parser.add_argument("--name", default="E2E seeded app code")
    args = parser.parse_args()

    env = {**load_env(ENV), **os.environ}
    email = env.get("DEEPSQL_INITIAL_ADMIN_EMAIL") or env.get("DEEPSQL_SMOKE_EMAIL")
    password = env.get("DEEPSQL_INITIAL_ADMIN_PASSWORD") or env.get("DEEPSQL_SMOKE_PASSWORD")
    if not email or not password:
        print("Missing admin credentials in .env", file=sys.stderr)
        return 1

    frontend = f"http://localhost:{env.get('DEEPSQL_FRONTEND_PORT', '3000')}"
    backend = f"http://localhost:{env.get('DEEPSQL_BACKEND_PORT', '8080')}/api"
    opener = build_opener(HTTPCookieProcessor(CookieJar()))

    def req(url: str, data=None, method: str | None = None):
        body = None
        headers: dict[str, str] = {}
        if data is not None:
            body = json.dumps(data).encode()
            headers["Content-Type"] = "application/json"
        m = method or ("POST" if data is not None else "GET")
        r = Request(url, data=body, headers=headers, method=m)
        with opener.open(r, timeout=60) as resp:
            raw = resp.read().decode() or "null"
            return json.loads(raw)

    print("→ login")
    try:
        req(f"{frontend}/api/auth/login", {"email": email, "password": password})
    except Exception:
        req(f"{backend}/auth/login", {"email": email, "password": password})

    conn_id = args.connection_id
    if not conn_id:
        conns = req(f"{backend}/connections")
        items = conns if isinstance(conns, list) else (conns.get("connections") or [])
        for c in items:
            name = (c.get("connectionName") or "").lower()
            if "acme" in name or "multi" in name:
                conn_id = c.get("connectionId") or c.get("id")
                break
        if not conn_id and items:
            conn_id = items[0].get("connectionId") or items[0].get("id")
    if not conn_id:
        print("FAIL: no connectionId", file=sys.stderr)
        return 1
    print(f"→ connection {conn_id}")

    # Prefer SQL seed via vault DB for speed/reliability (no archive upload).
    # Falls back to printing SQL if psql unavailable.
    source_id = str(uuid.uuid4())
    job_id = str(uuid.uuid4())
    now = datetime.now(timezone.utc).replace(tzinfo=None).isoformat(sep=" ", timespec="seconds")

    suggestions_sql = []
    # Mix of SCHEMA_DOC (columns + tables) and KNOWLEDGE_ENTRY to cover both apply paths.
    fixtures = [
        ("SCHEMA_DOC", "rpt_campaign_performance.refund_rate_pct", "Refund rate percentage",
         "Percentage of refunded revenue for the campaign.", {"objectKind": "COLUMN", "businessTerms": ["refund rate"]}),
        ("SCHEMA_DOC", "fct_ashram_visit.party_size", "Visit party size",
         "Number of people in the visiting party.", {"objectKind": "COLUMN", "businessTerms": ["party size"]}),
        ("SCHEMA_DOC", "crm.customers", "CRM customers table",
         "Master customer records for CRM.", {"objectKind": "TABLE", "businessTerms": ["customer"]}),
        ("KNOWLEDGE_ENTRY", None, "Refunds exclude gift cards",
         "Business rule: refund_rate_pct must exclude gift-card redemptions.",
         {"entryType": "BUSINESS_RULE"}),
    ]
    # Pad to requested count by cloning column docs with unique suffixes.
    while len(fixtures) < args.count:
        i = len(fixtures)
        fixtures.append((
            "SCHEMA_DOC",
            f"seed_table_{i // 10}.col_{i}",
            f"Seeded column {i}",
            f"Auto-seeded documentation for col_{i}.",
            {"objectKind": "COLUMN", "businessTerms": [f"term_{i}"]},
        ))

    for kind, target, title, content, payload in fixtures[: args.count]:
        sid = str(uuid.uuid4())
        conf = 0.99 if "refund" in (title or "").lower() or "party" in (title or "").lower() else 0.75
        linked_tables = json.dumps([target.split(".")[0]] if target and "." in target else ([target] if target else []))
        linked_cols = json.dumps([target] if target and "." in target else [])
        source_files = json.dumps([{"path": "src/models/Seed.java", "startLine": 10, "endLine": 40, "rationale": "seed"}])
        payload_json = json.dumps(payload).replace("'", "''")
        target_sql = "NULL" if not target else f"'{target}'"
        suggestions_sql.append(
            f"""INSERT INTO code_knowledge_suggestion
            (id, job_id, connection_id, target_kind, target_object, title, content, payload,
             linked_tables, linked_columns, source_files, confidence, status, created_at)
            VALUES
            ('{sid}', '{job_id}', '{conn_id}', '{kind}', {target_sql},
             '{title.replace("'", "''")}', '{content.replace("'", "''")}',
             '{payload_json}'::jsonb, '{linked_tables}'::jsonb, '{linked_cols}'::jsonb,
             '{source_files}'::jsonb, {conf}, 'PENDING', TIMESTAMP '{now}');"""
        )

    sql = f"""
INSERT INTO code_scan_source (id, connection_id, name, kind, archive_sha256, total_bytes, file_count, active, created_by, created_at)
VALUES ('{source_id}', '{conn_id}', '{args.name}', 'UPLOAD', 'seed', 1024, 12, TRUE, 'seed', TIMESTAMP '{now}')
ON CONFLICT (id) DO NOTHING;

INSERT INTO code_scan_job
(id, source_id, connection_id, status, progress, current_step, files_total, files_parsed, chunks_sent,
 suggestions_emitted, started_at, completed_at, message, triggered_by, created_at)
VALUES
('{job_id}', '{source_id}', '{conn_id}', 'COMPLETED', 100, 'done', 12, 12, 12,
 {len(fixtures[:args.count])}, TIMESTAMP '{now}', TIMESTAMP '{now}', 'seeded', 'seed', TIMESTAMP '{now}');

{chr(10).join(suggestions_sql)}
"""
    sql_path = Path("/tmp/seed-review-suggestions.sql")
    sql_path.write_text(sql)
    print(f"→ writing {sql_path} ({args.count} suggestions)")
    rc = os.system(f"sudo -u postgres psql -d dba_agent -v ON_ERROR_STOP=1 -f {sql_path} >/tmp/seed-review.out 2>&1")
    if rc != 0:
        print(open("/tmp/seed-review.out").read(), file=sys.stderr)
        return 1

    page = req(f"{backend}/code-scan/suggestions?connectionId={conn_id}&status=PENDING&page=0&size=1")
    total = page.get("totalElements")
    print(f"→ PENDING totalElements={total}")
    print(f"✓ Seeded source={source_id} job={job_id} suggestions={args.count}")
    print(conn_id)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
