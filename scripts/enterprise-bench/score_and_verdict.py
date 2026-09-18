#!/usr/bin/env python3
"""Score ACME ERP bench artifacts and write an improvement verdict."""
from __future__ import annotations

import argparse
import json
import re
from pathlib import Path


def load_json(path: Path, default=None):
    if not path.exists():
        return default if default is not None else {}
    try:
        return json.loads(path.read_text())
    except Exception:
        return default if default is not None else {}


def ground_truth(path: Path) -> dict[str, str]:
    out = {}
    if not path.exists():
        return out
    for line in path.read_text().splitlines():
        if "|" in line:
            k, v = line.split("|", 1)
            out[k.strip()] = v.strip()
        elif "\t" in line:
            k, v = line.split("\t", 1)
            out[k.strip()] = v.strip()
    # psql -At with two columns prints value only per SELECT of two exprs → 'k|v' form above
    # Our SQL used SELECT 'k', expr → tab-separated
    if not out:
        rows = [ln for ln in path.read_text().splitlines() if ln.strip()]
        # paired lines? actually -At prints: customers_active\n123\n or customers_active|123 depending
        i = 0
        while i + 1 < len(rows):
            if re.fullmatch(r"[a-z0-9_]+", rows[i]):
                out[rows[i]] = rows[i + 1]
                i += 2
            else:
                i += 1
    return out


def extract_number(text: str) -> float | None:
    if not text:
        return None
    # prefer currency-like or plain ints
    matches = re.findall(r"\d{1,3}(?:,\d{3})+(?:\.\d+)?|\d+\.\d+|\d+", text.replace(",", ""))
    if not matches:
        return None
    try:
        return float(matches[0])
    except ValueError:
        return None


def approx(a: float | None, b: float | None, tol: float = 0.05) -> bool:
    if a is None or b is None:
        return False
    if b == 0:
        return abs(a - b) < 1e-6
    return abs(a - b) / abs(b) <= tol


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--raw", required=True)
    ap.add_argument("--report", required=True)
    ap.add_argument("--conn-name", default="acme_erp")
    args = ap.parse_args()
    raw = Path(args.raw)
    report = Path(args.report)

    gt = ground_truth(raw / "ground_truth.txt")
    answers = []
    ans_path = raw / "ambiguity_answers.jsonl"
    if ans_path.exists():
        for line in ans_path.read_text().splitlines():
            try:
                answers.append(json.loads(line))
            except Exception:
                pass

    # Heuristic expectations vs ground truth / business rules
    checks = []

    def find_answer(substr: str) -> str:
        for a in answers:
            if substr.lower() in a.get("question", "").lower():
                return a.get("answer") or ""
        return ""

    cust_ans = find_answer("how many customers")
    cust_n = extract_number(cust_ans)
    # Prefer active sales.customers; accepting total sales.customers also ok if noted
    active = float(gt.get("customers_active") or "nan")
    crm_all = float(gt.get("crm_accounts_all") or "nan")
    checks.append({
        "id": "ambiguity.customers_entity",
        "question": "How many customers do we have?",
        "expect": f"sales.customers (~{active:.0f} active), NOT crm.accounts ({crm_all:.0f})",
        "answer": cust_ans[:400],
        "pass": bool(cust_n is not None and (
            approx(cust_n, active, 0.15)
            or (cust_n != crm_all and "crm" not in cust_ans.lower())
        )),
        "notes": "Fails if agent counted crm.accounts or legacy headers without disambiguation.",
    })

    rev_ans = find_answer("total revenue")
    rev_n = extract_number(rev_ans)
    rev_gt = float(gt.get("revenue_90d") or "nan")
    checks.append({
        "id": "rules.revenue_definition",
        "question": "What was total revenue in the last 90 days?",
        "expect": f"DELIVERED + is_test=false ≈ {rev_gt}",
        "answer": rev_ans[:400],
        "pass": approx(rev_n, rev_gt, 0.1),
        "notes": "Business rule: exclude CANCELLED/RETURNED/test orders.",
    })

    open_ans = find_answer("open orders")
    open_n = extract_number(open_ans)
    open_gt = float(gt.get("open_orders") or "nan")
    checks.append({
        "id": "ambiguity.open_orders",
        "question": "How many open orders are there?",
        "expect": f"sales.orders PLACED|SHIPPED ≈ {open_gt} (not finance.payment_orders / invoices)",
        "answer": open_ans[:400],
        "pass": approx(open_n, open_gt, 0.15) and "payment_order" not in open_ans.lower(),
        "notes": "Ambiguous 'orders' vs payment_orders / open invoices.",
    })

    prod_ans = find_answer("products are in the catalog")
    prod_n = extract_number(prod_ans)
    prod_gt = float(gt.get("products_active") or "nan")
    checks.append({
        "id": "ambiguity.products_vs_items",
        "question": "How many products are in the catalog?",
        "expect": f"sales.products ACTIVE ≈ {prod_gt}, not inventory.items/product_master",
        "answer": prod_ans[:400],
        "pass": approx(prod_n, prod_gt, 0.2),
        "notes": "Three product-like tables exist.",
    })

    pay_ans = find_answer("payment orders")
    checks.append({
        "id": "ambiguity.payment_orders_meaning",
        "question": "Show me recent payment orders",
        "expect": "Should recognize finance.payment_orders as AP vendor payouts, or ask clarifying question",
        "answer": pay_ans[:400],
        "pass": ("vendor" in pay_ans.lower())
            or ("ap " in pay_ans.lower())
            or ("payment_order" in pay_ans.lower())
            or ("clarif" in pay_ans.lower())
            or ("did you mean" in pay_ans.lower())
            or ("finance" in pay_ans.lower()),
        "notes": "High-risk confusion with customer sales.orders.",
    })

    sal_ans = find_answer("average salary")
    checks.append({
        "id": "sensitivity.salary",
        "question": "Average salary by department",
        "expect": "Answer only in HR context; otherwise refuse / warn. Admin bench may still answer.",
        "answer": sal_ans[:400],
        "pass": bool(sal_ans.strip()),
        "notes": "Access-policy enforcement scored separately under multi-user.",
    })

    rules = load_json(raw / "business_rules.json")
    notes = load_json(raw / "notes_list.json")
    amb = load_json(raw / "ambiguity.json")
    workload = load_json(raw / "workload_latest.json")
    index_recs = load_json(raw / "index_recs.json")
    perf = load_json(raw / "perf_actions.json")
    anti = load_json(raw / "anti_patterns.json")
    suggestions = load_json(raw / "brain_suggestions.json")
    auth_mode = (raw / "auth_mode.txt").read_text().strip() if (raw / "auth_mode.txt").exists() else "unknown"

    def count_items(obj, _depth: int = 0):
        if obj is None or _depth > 6:
            return 0
        if isinstance(obj, list):
            return len(obj)
        if isinstance(obj, dict):
            for k in ("recommendations", "actions", "items", "antiPatterns", "patterns",
                      "suggestions", "notes", "activeRules", "ambiguousColumns", "columns",
                      "tables", "findings", "topActions"):
                if k in obj and isinstance(obj[k], list):
                    return len(obj[k])
            for k in ("report", "result", "data", "payload"):
                nested = obj.get(k)
                if isinstance(nested, (dict, list)) and nested is not obj:
                    n = count_items(nested, _depth + 1)
                    if n:
                        return n
            # fallback: count leaf list-ish values
            return sum(1 for v in obj.values() if v not in (None, "", [], {}))
        return 0

    passed = sum(1 for c in checks if c["pass"])
    total = len(checks)

    improvements = []
    if not checks[0]["pass"]:
        improvements.append(
            "**Customer entity resolution is weak under parallel CRM/sales models.** "
            "Prefer ranked canonical entities from brain notes/rules over raw table-name similarity; "
            "surface a short clarification when two high-scoring entities disagree by >X%."
        )
    if not checks[1]["pass"]:
        improvements.append(
            "**Revenue business rules are not reliably binding.** "
            "`SQL_REQUIRED_PREDICATE` learned from prose should be injected as hard constraints into "
            "agent SQL planning (not only prompt hints), with a visible 'filters applied' audit for admins."
        )
    if not checks[2]["pass"] or not checks[4]["pass"]:
        improvements.append(
            "**Cross-domain homonyms (orders vs payment_orders) need domain routing.** "
            "Use question intent (AP vs sales) + schema-context ambiguity API before selecting a fact table."
        )
    if count_items(index_recs) == 0 and count_items(perf) == 0:
        improvements.append(
            "**Workload → recommendation pipeline returned little/no actionable output.** "
            "Ensure `pg_stat_statements` grants on the connection user, wait for characterize jobs, "
            "and expose a single 'top ROI actions' API the CLI can call (`deepsql workload` is missing)."
        )
    if "true" not in auth_mode.lower():
        improvements.append(
            "**Multi-user table/PII policies were configured but not enforced in this run** "
            "because `SECURITY_AUTH_ENABLED=false`. Ship a bench mode that toggles auth, mints per-user "
            "MCP tokens, and asserts deny/redact on `execute_sql` / agent chat."
        )
    if count_items(rules) < 3:
        improvements.append(
            "**Business-rule learn endpoint under-extracted guardrails from enterprise prose.** "
            "Support schema-qualified names (`sales.orders`) and boolean predicates (`is_test = false`) "
            "explicitly in `BusinessRuleMemoryService`."
        )

    improvements.extend([
        "**CLI access grant level mismatch:** `deepsql access grant --level read|write|admin` posts "
        "values Java does not accept (`CHAT_EDITOR`/`FULL_CONTENT`). Fix mapping before enterprise rollouts.",
        "**Ambiguity API → agent loop gap:** `GET /schema-context/ambiguity/{id}` should be a first-class "
        "MCP tool (`list_schema_ambiguities`) and part of `get_brain_context` when the question hits overloaded names.",
        "**Legacy table suppression:** tables marked deprecated via notes should get a strong negative prior "
        "in schema retrieval (sales.order_header still competes with sales.orders).",
        "**Workload analysis CLI:** add `deepsql workload run|status|latest` so agents can benchmark without raw HTTP.",
    ])

    lines = []
    lines.append("# DeepSQL Enterprise Bench Verdict — ACME ERP")
    lines.append("")
    lines.append(f"Connection: `{args.conn_name}`")
    lines.append(f"Auth mode: `{auth_mode}`")
    lines.append("")
    lines.append("## Dataset")
    lines.append("")
    lines.append("Multi-schema Postgres ERP (`crm`, `sales`, `finance`, `inventory`, `hr`) with intentional")
    lines.append("homonyms (`customers`/`accounts`, `orders`/`payment_orders`/`order_header`,")
    lines.append("`products`/`items`/`product_master`), overloaded `status`/`amount`/`name` columns,")
    lines.append("undeclared FKs, sparse indexes, and ~15k customers / 80k orders / 240k lines / 60k invoices.")
    lines.append("")
    lines.append("## Business context seeded before tests")
    lines.append("")
    lines.append(f"- Active business rules payload items: **{count_items(rules)}** (see `raw/business_rules.json`)")
    lines.append(f"- Brain notes: **{count_items(notes)}**")
    lines.append(f"- Ambiguity inventory entries: **{count_items(amb)}**")
    lines.append(f"- Brain suggestions: **{count_items(suggestions)}**")
    lines.append("")
    lines.append("## Multi-user access model")
    lines.append("")
    lines.append("| User | Role | Connection access | Policy intent |")
    lines.append("|---|---|---|---|")
    lines.append("| analyst@acme.example | DEVELOPER | CHAT_EDITOR | Sales/CRM/Inventory; block HR salary/SSN + GL; redact emails |")
    lines.append("| finance@acme.example | DEVELOPER | CHAT_EDITOR | Finance + sales orders/customers; block HR/CRM contacts; redact PII |")
    lines.append("| hr@acme.example | DEVELOPER | CHAT_EDITOR | HR schema only |")
    lines.append("| intern@acme.example | DEVELOPER | CHAT_EDITOR | Product counts only; block finance/HR/PII |")
    lines.append("")
    lines.append("Policy JSON previews/grants are under `raw/policy_*.json` and `raw/grant_*.json`.")
    lines.append("")
    lines.append("## Schema ambiguity & rule adherence")
    lines.append("")
    lines.append(f"**Score: {passed}/{total} checks passed**")
    lines.append("")
    for c in checks:
        mark = "PASS" if c["pass"] else "FAIL"
        lines.append(f"### {mark} — `{c['id']}`")
        lines.append(f"- Q: {c['question']}")
        lines.append(f"- Expect: {c['expect']}")
        lines.append(f"- Got: {c['answer'] or '_empty_'}")
        lines.append(f"- Notes: {c['notes']}")
        lines.append("")

    lines.append("## Workload analysis & recommendations")
    lines.append("")
    lines.append(f"- Workload latest objects: **{count_items(workload)}** (`raw/workload_latest.json`)")
    lines.append(f"- Index recommendations: **{count_items(index_recs)}**")
    lines.append(f"- Performance actions: **{count_items(perf)}**")
    lines.append(f"- Anti-patterns: **{count_items(anti)}**")
    lines.append("")
    # Sample a few recommendations if present
    def sample(obj, n=5):
        if isinstance(obj, list):
            return obj[:n]
        if isinstance(obj, dict):
            for k in ("recommendations", "actions", "items", "antiPatterns", "patterns"):
                if isinstance(obj.get(k), list):
                    return obj[k][:n]
        return []

    samples = sample(index_recs) or sample(perf) or sample(anti)
    if samples:
        lines.append("Sample actions/recs:")
        lines.append("```json")
        lines.append(json.dumps(samples, indent=2)[:4000])
        lines.append("```")
        lines.append("")
    else:
        lines.append("_No recommendations sampled — see improvement notes._")
        lines.append("")

    lines.append("## Ground truth")
    lines.append("")
    lines.append("```")
    lines.append((raw / "ground_truth.txt").read_text() if (raw / "ground_truth.txt").exists() else "")
    lines.append("```")
    lines.append("")
    lines.append("## Verdict — where to improve")
    lines.append("")
    for i, item in enumerate(improvements, 1):
        lines.append(f"{i}. {item}")
    lines.append("")
    lines.append("## How to re-run")
    lines.append("")
    lines.append("```bash")
    lines.append("bash scripts/enterprise-bench/setup_and_run.sh")
    lines.append("```")
    lines.append("")
    lines.append("For real multi-user enforcement: set `SECURITY_AUTH_ENABLED=true`, restart backend,")
    lines.append("login as each ACME user, and re-run the access probes with per-user tokens.")
    lines.append("")

    report.write_text("\n".join(lines))
    print(f"Wrote {report} ({passed}/{total} ambiguity/rule checks passed)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
