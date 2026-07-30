#!/usr/bin/env bash
# Brain Retrieval Test Runner
#
# Tests the brain's retrieval paths directly (no chat agent):
#   - POST /api/training/context/{connectionId}         -> RetrievedContextResult (ragTableNames)
#   - GET  /api/training/retrieve/{connectionId}?q=&topK -> ranked snippets (tablesCovered)
#   - GET  /api/business-rules/connection/{connectionId}?question -> applicable guardrails
#   - GET  /api/brain/inferred-relationships/{connectionId}        -> FK inferences (smoke)
#
# Each fixture case picks one endpoint via the "probe" field and asserts on
# the retrieved structure (table names, snippet coverage, rule keywords, etc).
#
# Usage:
#   ./run-brain-retrieval-tests.sh [base_url] [connection_id]
#
# Env overrides:
#   BRAIN_RETRIEVAL_USER
#   BRAIN_RETRIEVAL_PASSWORD
#   BRAIN_RETRIEVAL_AUTH_TOKEN     pre-issued JWT; skips login when set
#   BRAIN_RETRIEVAL_FIXTURE_FILE
#   BRAIN_RETRIEVAL_REPORT_FILE

set -euo pipefail

DEFAULT_CONNECTION_ID="a273f43a-a844-44a3-9026-1b0de1167e8f"
BASE_URL="${1:-http://localhost:8080/api}"
CONN_ID="${2:-${BRAIN_RETRIEVAL_CONNECTION_ID:-$DEFAULT_CONNECTION_ID}}"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
FIXTURE_FILE="${BRAIN_RETRIEVAL_FIXTURE_FILE:-${SCRIPT_DIR}/brain-retrieval-test-cases.json}"
REPORT_FILE="${BRAIN_RETRIEVAL_REPORT_FILE:-${SCRIPT_DIR}/brain-retrieval-report.json}"
USERNAME="${BRAIN_RETRIEVAL_USER:-admin}"
PASSWORD="${BRAIN_RETRIEVAL_PASSWORD:-admin}"

echo "========================================"
echo "  Brain Retrieval Test Suite"
echo "========================================"
echo "Base URL:     $BASE_URL"
echo "Connection:   $CONN_ID"
echo "Fixture:      $FIXTURE_FILE"
echo "User:         $USERNAME"
echo ""

python3 - "$BASE_URL" "$CONN_ID" "$FIXTURE_FILE" "$REPORT_FILE" "$USERNAME" "$PASSWORD" <<'PY'
import json
import os
import sys
import time
import http.client
import http.cookiejar
import urllib.request
import urllib.error
import urllib.parse
from pathlib import Path

base_url, connection_id, fixture_path, report_path, username, password = sys.argv[1:]
fixture_file = Path(fixture_path)
report_file = Path(report_path)

if not fixture_file.exists():
    raise SystemExit(f"ERROR: Fixture file not found: {fixture_file}")

cookie_jar = http.cookiejar.CookieJar()
opener = urllib.request.build_opener(urllib.request.HTTPCookieProcessor(cookie_jar))


def request_json(method, path, payload=None, token=None, timeout=180):
    body = None if payload is None else json.dumps(payload).encode("utf-8")
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    req = urllib.request.Request(f"{base_url}{path}", data=body, headers=headers, method=method)
    try:
        with opener.open(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as err:
        raw = err.read().decode("utf-8")
        try:
            parsed = json.loads(raw) if raw else {}
        except json.JSONDecodeError:
            parsed = {"raw": raw}
        return err.code, parsed
    except urllib.error.URLError as err:
        return 599, {"transportError": str(getattr(err, "reason", err))}
    except http.client.RemoteDisconnected as err:
        return 599, {"transportError": str(err)}
    except TimeoutError as err:
        return 599, {"transportError": str(err)}
    except Exception as err:
        return 599, {"transportError": f"{type(err).__name__}: {err}"}


def login():
    preauth = os.environ.get("BRAIN_RETRIEVAL_AUTH_TOKEN", "").strip()
    if preauth:
        return preauth
    status, payload = request_json("POST", "/auth/login", {
        "email": username,
        "username": username,
        "password": password,
    }, timeout=60)
    has_session_cookie = any(cookie.name for cookie in cookie_jar)
    if status != 200 or (not payload.get("token") and not has_session_cookie):
        raise SystemExit(f"ERROR: Login failed ({status}): {payload}")
    return payload.get("token")


def upper(value):
    return (value or "").upper()


def collect_strings(node, bag):
    if node is None:
        return
    if isinstance(node, str):
        bag.append(node.upper())
    elif isinstance(node, dict):
        for v in node.values():
            collect_strings(v, bag)
    elif isinstance(node, list):
        for v in node:
            collect_strings(v, bag)


# ---------------- Probe handlers ----------------

def probe_training_context(token, case):
    question = case["question"]
    status, payload = request_json("POST", f"/training/context/{connection_id}", {"question": question}, token=token, timeout=120)
    return status, payload, {
        "endpoint": f"POST /training/context/{connection_id}",
        "question": question,
    }


def probe_training_retrieve(token, case):
    question = case["question"]
    top_k = case.get("topK", 20)
    qs = urllib.parse.urlencode({"q": question, "topK": top_k})
    status, payload = request_json("GET", f"/training/retrieve/{connection_id}?{qs}", token=token, timeout=120)
    return status, payload, {
        "endpoint": f"GET /training/retrieve/{connection_id}",
        "question": question,
        "topK": top_k,
    }


def probe_business_rules(token, case):
    question = case.get("question")
    qs = f"?{urllib.parse.urlencode({'question': question})}" if question else ""
    status, payload = request_json("GET", f"/business-rules/connection/{connection_id}{qs}", token=token, timeout=60)
    return status, payload, {
        "endpoint": f"GET /business-rules/connection/{connection_id}",
        "question": question,
    }


def probe_inferred_relationships(token, case):
    status, payload = request_json("GET", f"/brain/inferred-relationships/{connection_id}", token=token, timeout=60)
    return status, payload, {
        "endpoint": f"GET /brain/inferred-relationships/{connection_id}",
    }


PROBE_HANDLERS = {
    "training_context": probe_training_context,
    "training_retrieve": probe_training_retrieve,
    "business_rules": probe_business_rules,
    "inferred_relationships": probe_inferred_relationships,
}

# ---------------- Evaluators ----------------

def evaluate_training_context(case, payload):
    failures = []
    if not isinstance(payload, dict):
        return ["context response was not a JSON object"]
    if payload.get("skipped"):
        failures.append(f"retrieval was skipped: {payload.get('skipReason')}")

    rag_tables = {str(t).upper() for t in (payload.get("ragTableNames") or [])}
    for expected in case.get("expectedRagTables", []):
        if expected.upper() not in rag_tables:
            failures.append(f"missing rag table {expected}")
    for forbidden in case.get("forbiddenRagTables", []):
        if forbidden.upper() in rag_tables:
            failures.append(f"forbidden rag table {forbidden} present")

    min_results = case.get("minResultCount")
    if min_results is not None and (payload.get("resultCount") or 0) < min_results:
        failures.append(f"resultCount {payload.get('resultCount')} below min {min_results}")

    expected_intent = case.get("expectedRetrievalIntent")
    if expected_intent and str(payload.get("retrievalIntent")) != expected_intent:
        failures.append(f"retrievalIntent {payload.get('retrievalIntent')} != expected {expected_intent}")

    haystack = []
    collect_strings(payload.get("trainingContext"), haystack)
    collect_strings(payload.get("companyKnowledgeContext"), haystack)
    blob = "\n".join(haystack)
    for keyword in case.get("expectedContextKeywords", []):
        if keyword.upper() not in blob:
            failures.append(f"missing context keyword {keyword}")
    for keyword in case.get("forbiddenContextKeywords", []):
        if keyword.upper() in blob:
            failures.append(f"forbidden context keyword {keyword} present")
    return failures


def evaluate_training_retrieve(case, payload):
    failures = []
    if not isinstance(payload, dict):
        return ["retrieve response was not a JSON object"]
    tables_covered = {str(t).upper() for t in (payload.get("tablesCovered") or [])}
    for expected in case.get("expectedTablesCovered", []):
        if expected.upper() not in tables_covered:
            failures.append(f"missing table coverage for {expected}")
    for forbidden in case.get("forbiddenTablesCovered", []):
        if forbidden.upper() in tables_covered:
            failures.append(f"forbidden table coverage for {forbidden}")

    total = payload.get("totalResults") or 0
    min_total = case.get("minTotalResults")
    if min_total is not None and total < min_total:
        failures.append(f"totalResults {total} below min {min_total}")

    results = payload.get("results") or []
    snippet_blob_parts = []
    collect_strings(results, snippet_blob_parts)
    blob = "\n".join(snippet_blob_parts)
    for keyword in case.get("expectedSnippetKeywords", []):
        if keyword.upper() not in blob:
            failures.append(f"missing snippet keyword {keyword}")
    for keyword in case.get("forbiddenSnippetKeywords", []):
        if keyword.upper() in blob:
            failures.append(f"forbidden snippet keyword {keyword} present")
    return failures


def evaluate_business_rules(case, payload):
    failures = []
    if not isinstance(payload, dict):
        return ["business rules response was not a JSON object"]
    active_count = payload.get("activeRuleCount") or 0
    applicable_count = payload.get("applicableGuardrailCount") or 0
    min_active = case.get("minActiveRules")
    if min_active is not None and active_count < min_active:
        failures.append(f"activeRuleCount {active_count} below min {min_active}")
    min_applicable = case.get("minApplicableGuardrails")
    if min_applicable is not None and applicable_count < min_applicable:
        failures.append(f"applicableGuardrailCount {applicable_count} below min {min_applicable}")

    guardrail_context = payload.get("guardrailContext") or ""
    for keyword in case.get("expectedGuardrailKeywords", []):
        if keyword.upper() not in upper(guardrail_context):
            failures.append(f"missing guardrail keyword {keyword}")
    return failures


def evaluate_inferred_relationships(case, payload):
    failures = []
    if not isinstance(payload, list):
        return ["inferred relationships response was not a JSON array"]
    min_total = case.get("minRelationships", 0)
    if len(payload) < min_total:
        failures.append(f"relationship count {len(payload)} below min {min_total}")
    if case.get("expectedPairs"):
        pairs_seen = set()
        for rel in payload:
            if not isinstance(rel, dict):
                continue
            from_t = upper(rel.get("fromTable") or rel.get("sourceTable"))
            to_t = upper(rel.get("toTable") or rel.get("targetTable") or rel.get("referencedTable"))
            if from_t and to_t:
                pairs_seen.add((from_t, to_t))
                pairs_seen.add((to_t, from_t))
        for pair in case["expectedPairs"]:
            key = (pair[0].upper(), pair[1].upper())
            if key not in pairs_seen:
                failures.append(f"missing inferred relationship {pair[0]} <-> {pair[1]}")
    return failures


EVALUATORS = {
    "training_context": evaluate_training_context,
    "training_retrieve": evaluate_training_retrieve,
    "business_rules": evaluate_business_rules,
    "inferred_relationships": evaluate_inferred_relationships,
}

# ---------------- Run ----------------

token = login()
cases = json.loads(fixture_file.read_text())
results = []
passed = failed = errors = 0
total_duration = 0

print("Backend login OK")
print(f"Running {len(cases)} brain retrieval cases...")
print("")

for index, case in enumerate(cases, start=1):
    probe = case.get("probe")
    handler = PROBE_HANDLERS.get(probe)
    evaluator = EVALUATORS.get(probe)
    print(f"[{index}/{len(cases)}] [{case['id']}] probe={probe} ::: {case.get('description', '')[:80]}")
    started = time.time()

    if not handler or not evaluator:
        errors += 1
        record = {
            "id": case.get("id"),
            "probe": probe,
            "category": case.get("category"),
            "status": "ERROR",
            "durationMs": 0,
            "failures": [f"unknown probe '{probe}'"],
            "meta": {},
        }
        results.append(record)
        print(f"  ERROR: unknown probe '{probe}'")
        print("")
        continue

    try:
        status, payload, meta = handler(token, case)
    except Exception as err:
        errors += 1
        record = {
            "id": case.get("id"),
            "probe": probe,
            "category": case.get("category"),
            "status": "ERROR",
            "durationMs": int((time.time() - started) * 1000),
            "failures": [f"runner error: {type(err).__name__}: {err}"],
            "meta": {},
        }
        results.append(record)
        print(f"  ERROR: {type(err).__name__}: {err}")
        print("")
        continue

    duration = int((time.time() - started) * 1000)
    total_duration += duration

    if status >= 400 or (isinstance(payload, dict) and payload.get("transportError")):
        errors += 1
        snippet = json.dumps(payload)[:280] if not isinstance(payload, str) else payload[:280]
        record = {
            "id": case.get("id"),
            "probe": probe,
            "category": case.get("category"),
            "status": "ERROR",
            "httpStatus": status,
            "durationMs": duration,
            "failures": [f"http {status}: {snippet}"],
            "meta": meta,
        }
        results.append(record)
        print(f"  ERROR http {status}: {snippet[:160]}")
        print("")
        continue

    failures = evaluator(case, payload)
    status_str = "PASS" if not failures else "FAIL"
    if failures:
        failed += 1
    else:
        passed += 1

    record = {
        "id": case.get("id"),
        "probe": probe,
        "category": case.get("category"),
        "description": case.get("description"),
        "status": status_str,
        "httpStatus": status,
        "durationMs": duration,
        "failures": failures,
        "meta": meta,
    }
    if case.get("includeResponseSnippet"):
        record["responseSnippet"] = json.dumps(payload)[:1200]
    results.append(record)
    if failures:
        print(f"  FAIL ({duration}ms): {'; '.join(failures[:4])}")
    else:
        print(f"  PASS ({duration}ms)")
    print("")

report = {
    "baseUrl": base_url,
    "connectionId": connection_id,
    "total": len(cases),
    "passed": passed,
    "failed": failed,
    "errors": errors,
    "durationMs": total_duration,
    "results": results,
}
report_file.write_text(json.dumps(report, indent=2))

print("========================================")
print(f"RESULTS: {passed}/{len(cases)} passed ({failed} failed, {errors} errors)")
if cases:
    print(f"ACCURACY: {(passed / len(cases)) * 100:.1f}%")
print(f"Report:   {report_file}")
print("========================================")

if failed or errors:
    sys.exit(1)
PY
