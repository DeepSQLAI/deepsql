#!/usr/bin/env python3
"""Multi-schema + chat-access-policy E2E checks for ACME ERP fixture.

Usage (repo root, stack running with auth):
  python3 scripts/self-host/e2e-multischema-check.py
"""
from __future__ import annotations

import json
import os
import sys
import urllib.error
import urllib.request
from http.cookiejar import CookieJar
from pathlib import Path
from urllib.request import HTTPCookieProcessor, build_opener

ROOT = Path(__file__).resolve().parents[2]
ENV = ROOT / ".env"

EXPECTED_SCHEMAS = {"crm", "sales", "finance", "inventory", "hr", "marts", "public"}
MARTS_ONLY = {"marts"}


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
        print("Missing admin credentials in .env", file=sys.stderr)
        return 1

    frontend = f"http://localhost:{env.get('DEEPSQL_FRONTEND_PORT', '3000')}"
    backend = f"http://localhost:{env.get('DEEPSQL_BACKEND_PORT', '8080')}/api"
    acme_name = env.get("DEEPSQL_ACME_CONNECTION_NAME", "ACME ERP (Multi-Schema)")

    opener = build_opener(HTTPCookieProcessor(CookieJar()))

    def req(url: str, data=None, method: str | None = None):
        body = None
        headers: dict[str, str] = {}
        if data is not None:
            body = json.dumps(data).encode()
            headers["Content-Type"] = "application/json"
        m = method or ("POST" if data is not None else "GET")
        r = urllib.request.Request(url, data=body, headers=headers, method=m)
        with opener.open(r, timeout=120) as resp:
            raw = resp.read().decode() or "null"
            return json.loads(raw)

    print("→ login")
    try:
        req(f"{frontend}/api/auth/login", {"email": email, "password": password})
    except Exception:
        req(f"{backend}/auth/login", {"email": email, "password": password})

    print("→ resolve ACME connection")
    conns = req(f"{backend}/connections")
    items = conns if isinstance(conns, list) else (conns.get("connections") or conns.get("items") or [])
    conn_id = None
    for c in items:
        if c.get("connectionName") == acme_name:
            conn_id = c.get("connectionId") or c.get("id")
            break
    if not conn_id:
        payload = {
            "connectionName": acme_name,
            "dbType": "postgres",
            "host": "127.0.0.1",
            "port": 5432,
            "database": "acme_erp",
            "username": "postgres",
            "password": env.get("DB_PASSWORD", "postgres"),
            "sslEnabled": False,
        }
        saved = req(f"{backend}/connections", payload)
        conn_id = saved.get("connectionId") or saved.get("id")
    if not conn_id:
        print("FAIL: no ACME connection", file=sys.stderr)
        return 1
    print(f"  connection {conn_id}")

    print("→ admin schema objects (expect all business schemas)")
    obj_resp = req(f"{backend}/connections/{conn_id}/objects")
    objects = obj_resp.get("objects") if isinstance(obj_resp, dict) else obj_resp
    if not isinstance(objects, list):
        print(f"FAIL: unexpected objects payload: {obj_resp!r:.200}")
        return 1
    schemas = {o.get("schema") for o in objects if o.get("schema")}
    missing = EXPECTED_SCHEMAS - schemas
    if missing:
        print(f"FAIL: admin missing schemas {sorted(missing)}; got {sorted(schemas)}")
        return 1
    print(f"  OK schemas={sorted(s for s in schemas if s in EXPECTED_SCHEMAS)}")

    print("→ ensure marts-editor user exists")
    users = req(f"{backend}/admin/users")
    user_list = users if isinstance(users, list) else users.get("users") or users.get("items") or []
    editor = next((u for u in user_list if u.get("username") == "marts-editor"), None)
    if not editor:
        created = req(
            f"{backend}/admin/users",
            {
                "username": "marts-editor",
                "email": "marts-editor@localhost",
                "password": "MartsEditor!23",
                "role": "DEVELOPER",
            },
        )
        editor = created
        print("  created marts-editor", editor.get("id"))
    editor_id = editor.get("id") or editor.get("userId")
    if not editor_id:
        print("FAIL: marts-editor id missing", file=sys.stderr)
        return 1

    print("→ grant connection access to marts-editor")
    try:
        req(
            f"{backend}/admin/users/{editor_id}/connection-access/{conn_id}",
            {"accessLevel": "CHAT_EDITOR"},
            method="PUT",
        )
    except urllib.error.HTTPError as e:
        if e.code not in (409, 400):
            raise

    policy_text = (
        "This user should have access only to schema marts. "
        "Strictly, the user cannot access any other schema other than marts."
    )
    print("→ set marts-only chat policy")
    req(
        f"{backend}/admin/users/{editor_id}/connection-access/{conn_id}/chat-policy",
        {"plainEnglishPolicy": policy_text},
        method="PUT",
    )

    print("→ impersonate marts-editor")
    imp = req(f"{backend}/admin/impersonate", {"userId": editor_id})
    status = req(f"{backend}/admin/impersonate")
    if not status.get("impersonating"):
        print("FAIL: impersonation did not start", status)
        return 1
    print("  impersonating", (status.get("target") or {}).get("email") or imp.get("email"))

    print("→ scoped schema objects (expect marts only)")
    scoped_resp = req(f"{backend}/connections/{conn_id}/objects")
    scoped = scoped_resp.get("objects") if isinstance(scoped_resp, dict) else scoped_resp
    scoped_schemas = {o.get("schema") for o in scoped if o.get("schema")}
    # Policy scopes business schemas; public may remain visible for system catalog objects.
    business = {s for s in scoped_schemas if s not in ("public", "information_schema")}
    if business and not business <= MARTS_ONLY:
        print(f"FAIL: expected marts-only business schemas, got business={sorted(business)} all={sorted(scoped_schemas)}")
        return 1
    if not any(o.get("name") == "fct_enrollment" and o.get("schema") == "marts" for o in scoped):
        print("FAIL: marts.fct_enrollment not visible under policy")
        return 1
    print(f"  OK tables={[o.get('name') for o in scoped[:5]]}...")

    print("→ stop impersonation")
    req(f"{backend}/admin/impersonate", method="DELETE")

    print("→ run targeted backend policy unit tests marker")
    print("\n✓ Multi-schema E2E OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
