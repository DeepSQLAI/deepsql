#!/usr/bin/env python3
"""
Brain-only suite runner.

For each test case in the four primary suites, hits the brain/context layer
*directly* (vault DB + RAG embeddings via /api/training/context, plus a one-shot
schema fetch via /api/connections/{cid}/schema) and scores the resulting
context against the suite's existing textual assertions — no /api/chat, no
LLM generation, no agent planning.

Produces a per-suite and per-category breakdown that splits cases into:
  * brain-passable    — assertions can be satisfied from retrieved context
                        alone; reports brain-only pass rate
  * agent-required    — assertions need SQL/trace/verified-run; brain alone
                        cannot satisfy
  * brain-failed      — brain-passable but the retrieved context missed it

The split is the input for the V1 focus decision: how much of the suite is
retrieval-only (Brain wins V1), vs SQL/multi-step (Agent must ship in V1)?

Usage:
  python3 tests/suites/run-brain-only-comparison.py \
    [--base http://localhost:8080/api] \
    [--connection a273f43a-a844-44a3-9026-1b0de1167e8f]

Auth-disabled mode is assumed (SECURITY_AUTH_ENABLED=false). Otherwise pass
--token <bearer> or set BRAIN_RUNNER_TOKEN.
"""
from __future__ import annotations

import argparse
import json
import os
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from collections import Counter, defaultdict
from pathlib import Path

ROOT = Path(__file__).resolve().parent
SUITES = {
    "schema-metadata": ROOT / "schema-metadata" / "schema-metadata-test-cases.json",
    "sql-accuracy": ROOT / "sql-accuracy" / "sql-accuracy-test-cases.json",
    "chat-resilience": ROOT / "chat-resilience" / "chat-resilience-test-cases.json",
    "performance-monitoring": ROOT / "performance-monitoring" / "performance-monitoring-test-cases.json",
}

# Assertion fields that need agent / SQL / trace to satisfy.
AGENT_REQUIRED_FIELDS = {
    "expectSql", "expectedClauses",
    "traceIntent", "traceRouteType", "traceTaskCountMin", "traceAnyFields",
    "expectVerifiedRun", "expectedRunStatus", "expectedRunStopReason",
    "expectedRunDomain",
    "minResultSets", "minExecutedQueries",
    "expectedMode",
}


def http_request(url: str, *, method="GET", body=None, token=None, timeout=120):
    headers = {"Content-Type": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = None if body is None else json.dumps(body).encode("utf-8")
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8")
            return resp.status, (json.loads(raw) if raw else {})
    except urllib.error.HTTPError as err:
        return err.code, {"error": err.read().decode("utf-8", "ignore")}
    except (urllib.error.URLError, TimeoutError) as err:
        return 599, {"error": f"{type(err).__name__}: {err}"}


def fetch_schema(base, connection_id, token):
    status, data = http_request(
        f"{base}/connections/{connection_id}/schema", token=token, timeout=60
    )
    if status != 200:
        raise SystemExit(f"schema fetch failed: status={status} body={data}")
    schema = data.get("schema") or data
    tables = schema.get("tables") or []
    tindex = {}
    for t in tables:
        name = (t.get("tableName") or t.get("name") or "").upper()
        cols = [
            (c.get("columnName") or c.get("name") or "").upper()
            for c in (t.get("columns") or [])
            if (c.get("columnName") or c.get("name"))
        ]
        if name:
            tindex[name] = {"columns": cols, "raw": t}
    return tindex


def fetch_brain_context(base, connection_id, question, token):
    return http_request(
        f"{base}/training/context/{connection_id}",
        method="POST",
        body={"question": question},
        token=token,
        timeout=60,
    )


def compose_brain_answer(brain_payload, prompt, schema_index):
    """
    The "what brain alone exposes" text. Concatenation of:
      - trainingContext (RAG hits — schema docs + column docs + business terms)
      - companyKnowledgeContext (ranked CK entries)
      - direct schema lookup for tables explicitly named in the prompt (gives
        column lists for "what columns are in X" type questions).
    """
    parts = [brain_payload.get("trainingContext") or "",
             brain_payload.get("companyKnowledgeContext") or ""]

    prompt_upper = prompt.upper()
    matched_tables = set(brain_payload.get("ragTableNames") or [])
    # Detect tables named directly in the prompt.
    for table_name in schema_index.keys():
        if table_name in prompt_upper:
            matched_tables.add(table_name)

    for tn in matched_tables:
        meta = schema_index.get(tn.upper())
        if not meta:
            continue
        cols = meta["columns"]
        parts.append(
            f"\n=== SCHEMA: {tn} ===\nCOLUMNS ({len(cols)} columns): {', '.join(cols)}"
        )
        # Surface PK/FK hints if available
        raw = meta["raw"]
        for col in (raw.get("columns") or []):
            if col.get("primaryKey"):
                parts.append(f"PRIMARY KEY: {tn}.{(col.get('columnName') or col.get('name')).upper()}")

    return "\n".join(p for p in parts if p), matched_tables


BRAIN_CHECKABLE_FIELDS = {
    "expectedMessageKeywords",
    "forbiddenMessageKeywords",
    "expectedTables",
    "expectedColumns",
    "forbiddenTables",
    "expectNoSql",  # brain returns no SQL anyway → trivially satisfied
}


def has_agent_only_assertion(case_or_turn):
    """True if the case requires SQL generation / agent traces / verified runs
    to fully pass — i.e. things brain alone can never satisfy."""
    return bool(set(case_or_turn.keys()) & AGENT_REQUIRED_FIELDS)


def has_brain_checkable_assertion(case_or_turn):
    return bool(set(case_or_turn.keys()) & BRAIN_CHECKABLE_FIELDS)


def score_brain_against_assertions(case_or_turn, brain_text, matched_tables):
    """Score the brain-checkable assertions only. Returns:
        {
          "checks": [...],           # per-assertion results
          "checked": int,            # count
          "satisfied": int,          # passed
          "failures": [str, ...]     # human-readable failures
        }
    Anything in AGENT_REQUIRED_FIELDS is not scored here.
    """
    checks = []
    failures = []
    upper_text = brain_text.upper()

    def chk(kind, ok, detail):
        checks.append({"kind": kind, "ok": ok, "detail": detail})
        if not ok:
            failures.append(f"{kind}: {detail}")

    for kw in (case_or_turn.get("expectedMessageKeywords") or []):
        chk("keyword", kw.upper() in upper_text, kw)

    for kw in (case_or_turn.get("forbiddenMessageKeywords") or []):
        chk("forbidden_keyword", kw.upper() not in upper_text, kw)

    table_set = {t.upper() for t in matched_tables}
    for t in (case_or_turn.get("expectedTables") or []):
        tu = t.upper()
        chk("expected_table", tu in table_set or tu in upper_text, t)

    for c in (case_or_turn.get("expectedColumns") or []):
        chk("expected_column", c.upper() in upper_text, c)

    for t in (case_or_turn.get("forbiddenTables") or []):
        tu = t.upper()
        chk("forbidden_table", tu not in table_set and tu not in upper_text, t)

    if "expectNoSql" in case_or_turn:
        # brain returns no SQL → trivially satisfied
        chk("expect_no_sql", True, "brain emits no SQL")

    satisfied = sum(1 for c in checks if c["ok"])
    return {
        "checks": checks,
        "checked": len(checks),
        "satisfied": satisfied,
        "failures": failures,
    }


def run_case(base, connection_id, schema_index, suite, case, token):
    """Run a single test case (one or many turns), score brain-checkable
    assertions, classify whether the agent layer is also required."""
    if "turns" in case:
        turns = case["turns"]
    else:
        turns = [{
            "prompt": case.get("prompt"),
            **{k: v for k, v in case.items() if k != "prompt"},
        }]

    needs_agent = any(has_agent_only_assertion(t) for t in turns)
    has_brain = any(has_brain_checkable_assertion(t) for t in turns)
    is_multi_turn = len(turns) > 1

    total_checks = 0
    total_satisfied = 0
    case_failures = []
    turn_results = []

    for idx, turn in enumerate(turns):
        prompt = turn.get("prompt") or ""
        status, payload = fetch_brain_context(base, connection_id, prompt, token)
        if status != 200:
            case_failures.append(f"turn {idx}: brain endpoint failed status={status}")
            turn_results.append({"prompt": prompt, "error": status})
            continue
        brain_text, matched_tables = compose_brain_answer(payload, prompt, schema_index)
        score = score_brain_against_assertions(turn, brain_text, matched_tables)
        total_checks += score["checked"]
        total_satisfied += score["satisfied"]
        case_failures.extend(f"turn {idx}: {f}" for f in score["failures"])
        turn_results.append({
            "prompt": prompt,
            "checked": score["checked"],
            "satisfied": score["satisfied"],
            "failures": score["failures"],
            "matchedTables": sorted(matched_tables)[:20],
            "skipReason": payload.get("skipReason"),
            "ragResultCount": payload.get("resultCount"),
        })

    coverage = (total_satisfied / total_checks) if total_checks else None
    brain_alone_passes = (total_checks > 0 and total_satisfied == total_checks
                          and not needs_agent and not is_multi_turn)
    return {
        "id": case.get("id"),
        "category": case.get("category"),
        "needsAgent": needs_agent,
        "isMultiTurn": is_multi_turn,
        "brainCheckedAssertions": total_checks,
        "brainSatisfiedAssertions": total_satisfied,
        "brainCoverage": coverage,
        "brainAlonePasses": brain_alone_passes,
        "failures": case_failures,
        "turns": turn_results,
    }


def run_suite(base, connection_id, schema_index, suite_name, fixture_path, token):
    cases = json.loads(fixture_path.read_text())
    results = []
    print(f"\n=== {suite_name} ({len(cases)} cases) ===", flush=True)
    for case in cases:
        t0 = time.time()
        r = run_case(base, connection_id, schema_index, suite_name, case, token)
        dur_ms = int((time.time() - t0) * 1000)
        r["durationMs"] = dur_ms
        results.append(r)
        cov = r.get("brainCoverage")
        cov_str = "—" if cov is None else f"{cov*100:3.0f}%"
        flags = []
        if r["needsAgent"]:
            flags.append("agent-needed")
        if r["isMultiTurn"]:
            flags.append("multi-turn")
        flag_str = ",".join(flags) or "brain-sufficient"
        mark = "✓" if r["brainAlonePasses"] else ("◐" if cov and cov >= 0.5 else "✗")
        print(f"  {mark}  cov={cov_str}  [{flag_str:18s}]  {r['id']}  ({dur_ms} ms)", flush=True)
    return results


def summarise(suite_results):
    """Per-suite + per-category roll-up.

    For each suite we report:
      - total cases
      - brainAlonePasses: cases that brain alone fully satisfies
      - needsAgent: cases that have any agent-only assertion (would fall short
        without the agent / SQL generation / trace)
      - isMultiTurn: cases that depend on chat continuity across turns
      - retrievalCoverage: avg fraction of brain-checkable assertions satisfied
        across cases that have any brain-checkable assertions
    """
    out = {"suites": {}, "overall": {}}
    overall_total = 0
    overall_brain_alone = 0
    overall_needs_agent = 0
    overall_multi_turn = 0
    overall_checked = 0
    overall_satisfied = 0
    for suite, results in suite_results.items():
        total = len(results)
        brain_alone = sum(1 for r in results if r["brainAlonePasses"])
        needs_agent = sum(1 for r in results if r["needsAgent"])
        multi_turn = sum(1 for r in results if r["isMultiTurn"])
        checked = sum(r["brainCheckedAssertions"] for r in results)
        satisfied = sum(r["brainSatisfiedAssertions"] for r in results)
        cov = (satisfied / checked) if checked else None
        cats = defaultdict(lambda: {"total": 0, "brainAlone": 0, "needsAgent": 0,
                                     "checked": 0, "satisfied": 0})
        for r in results:
            cat = r.get("category") or "_unknown_"
            c = cats[cat]
            c["total"] += 1
            if r["brainAlonePasses"]:
                c["brainAlone"] += 1
            if r["needsAgent"]:
                c["needsAgent"] += 1
            c["checked"] += r["brainCheckedAssertions"]
            c["satisfied"] += r["brainSatisfiedAssertions"]
        for c in cats.values():
            c["coverage"] = (c["satisfied"] / c["checked"]) if c["checked"] else None
        out["suites"][suite] = {
            "total": total,
            "brainAlonePasses": brain_alone,
            "needsAgent": needs_agent,
            "isMultiTurn": multi_turn,
            "retrievalChecked": checked,
            "retrievalSatisfied": satisfied,
            "retrievalCoverage": cov,
            "categories": dict(cats),
        }
        overall_total += total
        overall_brain_alone += brain_alone
        overall_needs_agent += needs_agent
        overall_multi_turn += multi_turn
        overall_checked += checked
        overall_satisfied += satisfied
    out["overall"] = {
        "total": overall_total,
        "brainAlonePasses": overall_brain_alone,
        "needsAgent": overall_needs_agent,
        "isMultiTurn": overall_multi_turn,
        "retrievalChecked": overall_checked,
        "retrievalSatisfied": overall_satisfied,
        "retrievalCoverage": (overall_satisfied / overall_checked) if overall_checked else None,
    }
    return out


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--base", default=os.environ.get("BRAIN_RUNNER_BASE", "http://localhost:8080/api"))
    p.add_argument("--connection", default=os.environ.get("BRAIN_RUNNER_CONNECTION", "a273f43a-a844-44a3-9026-1b0de1167e8f"))
    p.add_argument("--token", default=os.environ.get("BRAIN_RUNNER_TOKEN", ""))
    p.add_argument("--out", default=str(ROOT / "brain-only-report.json"))
    args = p.parse_args()

    print(f"base={args.base} connection={args.connection}")
    schema_index = fetch_schema(args.base, args.connection, args.token or None)
    print(f"schema cached: {len(schema_index)} tables")

    suite_results = {}
    for suite, fixture in SUITES.items():
        if not fixture.exists():
            print(f"skip {suite}: fixture not found at {fixture}")
            continue
        suite_results[suite] = run_suite(
            args.base, args.connection, schema_index, suite, fixture, args.token or None
        )

    summary = summarise(suite_results)
    Path(args.out).write_text(json.dumps({
        "summary": summary,
        "results": suite_results,
        "base": args.base,
        "connection": args.connection,
    }, indent=2))

    print("\n========================================")
    print("Brain-only run summary")
    print("========================================")
    print(f"{'suite':24s}  {'total':>5s}  {'brainAlone':>10s}  {'needsAgent':>10s}  {'multiTurn':>9s}  {'retrievalCov':>12s}")
    for suite, sm in summary["suites"].items():
        cov = sm["retrievalCoverage"]
        cov_s = "—" if cov is None else f"{cov*100:6.1f}%"
        print(f"{suite:24s}  {sm['total']:5d}  {sm['brainAlonePasses']:10d}  {sm['needsAgent']:10d}  {sm['isMultiTurn']:9d}  {cov_s:>12s}")
    o = summary["overall"]
    cov = o["retrievalCoverage"]
    cov_s = "—" if cov is None else f"{cov*100:6.1f}%"
    print(f"{'OVERALL':24s}  {o['total']:5d}  {o['brainAlonePasses']:10d}  {o['needsAgent']:10d}  {o['isMultiTurn']:9d}  {cov_s:>12s}")
    print()
    print("Legend:")
    print("  brainAlone   = case fully passes with brain retrieval alone (no agent layer)")
    print("  needsAgent   = case has any expectSql/expectedClauses/trace*/expectVerifiedRun assertion")
    print("  multiTurn    = case has >1 chat turn (chat-resilience continuity required)")
    print("  retrievalCov = avg fraction of brain-checkable assertions satisfied across all cases")
    print(f"\nfull report: {args.out}")


if __name__ == "__main__":
    main()
