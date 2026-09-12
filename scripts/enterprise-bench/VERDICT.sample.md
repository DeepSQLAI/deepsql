# DeepSQL Enterprise Bench Verdict — ACME ERP

Connection: `acme_erp`
Auth mode: `SECURITY_AUTH_ENABLED=true` (enabled for access probes)

## Dataset

Multi-schema Postgres ERP (`crm`, `sales`, `finance`, `inventory`, `hr`) with intentional
homonyms (`customers`/`accounts`, `orders`/`payment_orders`/`order_header`,
`products`/`items`/`product_master`), overloaded `status`/`amount`/`name` columns,
undeclared FKs, sparse indexes, and ~15k customers / 80k orders / 240k lines / 60k invoices.

## Business context seeded before tests

- Active business rules payload items: **7** (see `raw/business_rules.json`)
- Brain notes: **54**
- Ambiguity inventory entries: **1**
- Brain suggestions: **0**

## Multi-user access model

| User | Role | Connection access | Policy intent |
|---|---|---|---|
| analyst@acme.example | DEVELOPER | CHAT_EDITOR | Sales/CRM/Inventory; block HR salary/SSN + GL; redact emails |
| finance@acme.example | DEVELOPER | CHAT_EDITOR | Finance + sales orders/customers; block HR/CRM contacts; redact PII |
| hr@acme.example | DEVELOPER | CHAT_EDITOR | HR schema only |
| intern@acme.example | DEVELOPER | CHAT_EDITOR | Product counts only; block finance/HR/PII |

Policy JSON previews/grants are under `raw/policy_*.json` and `raw/grant_*.json`.

## Schema ambiguity & rule adherence

**Score: 4/6 checks passed**

### FAIL — `ambiguity.customers_entity`
- Q: How many customers do we have?
- Expect: sales.customers (~9000 active), NOT crm.accounts (15000)
- Got:  15,000  Want the active-customer count too? 
- Notes: Fails if agent counted crm.accounts or legacy headers without disambiguation.

### PASS — `rules.revenue_definition`
- Q: What was total revenue in the last 90 days?
- Expect: DELIVERED + is_test=false ≈ 25874016.0
- Got:  25,874,016  Want that broken out by week or channel? 
- Notes: Business rule: exclude CANCELLED/RETURNED/test orders.

### PASS — `ambiguity.open_orders`
- Q: How many open orders are there?
- Expect: sales.orders PLACED|SHIPPED ≈ 22748.0 (not finance.payment_orders / invoices)
- Got:  22,748  If you want, I can save “open orders = PLACED + SHIPPED” as the shared definition for this database. 
- Notes: Ambiguous 'orders' vs payment_orders / open invoices.

### FAIL — `ambiguity.products_vs_items`
- Q: How many products are in the catalog?
- Expect: sales.products ACTIVE ≈ 1500.0, not inventory.items/product_master
- Got:  2,000  Want the active-product count too? 
- Notes: Three product-like tables exist.

### PASS — `ambiguity.payment_orders_meaning`
- Q: Show me recent payment orders
- Expect: Should recognize finance.payment_orders as AP vendor payouts, or ask clarifying question
- Got:  | id | vendor_name | status | amount | scheduled_at | completed_at | |---:|---|---|---:|---|---| | 8000 | Vendor 1 | SCHEDULED | 28100 | 2026-08-08T06:42:24.147Z |  | | 7800 | Vendor 201 | SCHEDULED | 19900 | 2026-08-08T06:42:24.147Z |  | | 7600 | Vendor 1 | SCHEDULED | 11700 | 2026-08-08T06:42:24.147Z |  | | 7400 | Vendor 201 | SCHEDULED | 3500 | 2026-08-08T06:42:24.147Z |  | | 7200 | Vendor 1 |
- Notes: High-risk confusion with customer sales.orders.

### PASS — `sensitivity.salary`
- Q: Average salary by department
- Expect: Answer only in HR context; otherwise refuse / warn. Admin bench may still answer.
- Got:  I can’t provide salary data in this context because `hr.employees.salary` is restricted to HR-approved use.  If you want, I can give headcount by department instead. 
- Notes: Access-policy enforcement scored separately under multi-user.

## Workload analysis & recommendations

- Workload latest objects: **13** (`raw/workload_latest.json`)
- Index recommendations: **100**
- Performance actions: **100**
- Anti-patterns: **4**

Sample actions/recs:
```json
[
  {
    "id": "d073a05d-46dc-4169-adf4-a51d1100b9e6",
    "connectionId": "ac29b6b5-68cd-4825-8937-78e6676e1b50",
    "tableName": "customers",
    "columnNames": "customers_customer_code_key",
    "indexName": "customers_customer_code_key",
    "createStatement": "DROP INDEX customers_customer_code_key;",
    "priority": "LOW",
    "status": "PENDING",
    "kind": "DROP_INDEX",
    "estimatedImpact": 10,
    "reason": "Index 'customers_customer_code_key' on 'customers' has not been used since last reset (size 480 kB). Dropping it reclaims storage and removes per-write maintenance overhead.",
    "affectedQueries": 0,
    "avgPerformanceGain": null,
    "workloadScoreMs": 0,
    "writeCostScore": 0,
    "evidenceCount": 0,
    "hypopgBeforeCost": null,
    "hypopgAfterCost": null,
    "hypopgReductionPct": null,
    "hypopgEvaluatedAt": null,
    "occurrenceCount": 1,
    "firstSeenAt": "2026-08-08T06:47:31.840339",
    "lastSeenAt": "2026-08-08T06:47:31.840339",
    "createdAt": "2026-08-08T06:47:31.840394",
    "updatedAt": "2026-08-08T06:47:31.840394",
    "appliedAt": null
  },
  {
    "id": "354f1c15-6034-40cd-87b4-5dbddd65ef58",
    "connectionId": "ac29b6b5-68cd-4825-8937-78e6676e1b50",
    "tableName": "accounts",
    "columnNames": "accounts_account_number_key",
    "indexName": "accounts_account_number_key",
    "createStatement": "DROP INDEX accounts_account_number_key;",
    "priority": "LOW",
    "status": "PENDING",
    "kind": "DROP_INDEX",
    "estimatedImpact": 10,
    "reason": "Index 'accounts_account_number_key' on 'accounts' has not been used since last reset (size 480 kB). Dropping it reclaims storage and removes per-write maintenance overhead.",
    "affectedQueries": 0,
    "avgPerformanceGain": null,
    "workloadScoreMs": 0,
    "writeCostScore": 0,
    "evidenceCount": 0,
    "hypopgBeforeCost": null,
    "hypopgAfterCost": null,
    "hypopgReductionPct": null,
    "hypopgEvaluatedAt": null,
    "occurrenceCount": 1,
    "firstSeenAt": "2026-08-08T06:47:31.838563",
    "lastSeenAt": "2026-08-08T06:47:31.838563",
    "createdAt": "2026-08-08T06:47:31.838621",
    "updatedAt": "2026-08-08T06:47:31.838621",
    "appliedAt": null
  },
  {
    "id": "eb64a00c-c49e-4b35-8016-32d7c19079e8",
    "connectionId": "ac29b6b5-68cd-4825-8937-78e6676e1b50",
    "tableName": "products",
    "columnNames": "idx_sales_products_created",
    "indexName": "idx_sales_products_created",
    "createStatement": "DROP INDEX idx_sales_products_created;",
    "priority": "LOW",
    "status": "PENDING",
    "kind": "DROP_INDEX",
    "estimatedImpact": 10,
    "reason": "Index 'idx_sales_products_created' on 'products' has not been used since last reset (size 72 kB). Dropping it reclaims storage and removes per-write maintenance overhead.",
    "affectedQueries": 0,
    "avgPerformanceGain": null,
    "workloadScoreMs": 0,
    "writeCostScore": 0,
    "evidenceCount": 0,
    "hypopgBeforeCost": null,
    "hypopgAfterCost": null,
    "hypopgReductionPct": null,
    "hypopgEvaluatedAt": null,
    "occurrenceCount": 1,
    "firstSeenAt": "2026-08-08T06:47:31.833953",
    "lastSeenAt": "2026-08-08T06:47:31.833953",
    "createdAt": "2026-08-08T06:47:31.834018",
    "updatedAt": "2026-08-08T06:47:31.834018",
    "appliedAt": null
  },
  {
    "id": "ac43f20d-e048-4495-9776-3e64554d0cd8",
    "connectionId": "ac29b6b5-68cd-4825-8937-78e6676e1b50",
    "tableName": "customers",
    "columnNames": "customers_customer_code_key",
    "indexName": "customers_customer_code_key",
    "createStatement": "DROP INDEX customers_customer_code_key;",
    "priority": "LOW",
    "status": "PENDING",
    "kind": "DROP_INDEX",
    "estimatedImpact": 10,
    "reason": "Index 'customers_customer_code_key' on 'customers' has not been used since last reset (size 480 kB). Dropping it reclaims storage and removes per-write maintenance overhead.",
    "affectedQueries": 0,
    "avgPerformanceGain": null,
    "workloadScoreMs": 0,
    "writeCostScore": 0,
 
```

## Ground truth

```
customers_active|9000
revenue_90d|25874016.00
open_orders|22748
products_active|1500
crm_accounts_all|15000
crm_accounts_alive|14681
payment_orders|8000
sales_orders|80000

```

## Verdict — where to improve

1. **Customer entity resolution is weak under parallel CRM/sales models.** Prefer ranked canonical entities from brain notes/rules over raw table-name similarity; surface a short clarification when two high-scoring entities disagree by >X%.
2. **Multi-user table/PII policies were configured but not enforced in this run** because `SECURITY_AUTH_ENABLED=false`. Ship a bench mode that toggles auth, mints per-user MCP tokens, and asserts deny/redact on `execute_sql` / agent chat.
3. **CLI access grant level mismatch:** `deepsql access grant --level read|write|admin` posts values Java does not accept (`CHAT_EDITOR`/`FULL_CONTENT`). Fix mapping before enterprise rollouts.
4. **Ambiguity API → agent loop gap:** `GET /schema-context/ambiguity/{id}` should be a first-class MCP tool (`list_schema_ambiguities`) and part of `get_brain_context` when the question hits overloaded names.
5. **Legacy table suppression:** tables marked deprecated via notes should get a strong negative prior in schema retrieval (sales.order_header still competes with sales.orders).
6. **Workload analysis CLI:** add `deepsql workload run|status|latest` so agents can benchmark without raw HTTP.

## How to re-run

```bash
bash scripts/enterprise-bench/setup_and_run.sh
```

For real multi-user enforcement: set `SECURITY_AUTH_ENABLED=true`, restart backend,
login as each ACME user, and re-run the access probes with per-user tokens.



## Multi-user access enforcement results

Access score on SQL editor (`POST /connections/{id}/query`): **6/12** — every expected deny/redact **failed**.

| User | Allow probes | Deny/redact probes |
|---|---|---|
| admin | PASS (orders, salary) | n/a |
| analyst | PASS (orders count) | FAIL — read `hr.employees.salary` and `customers.email` |
| finance | PASS (invoices) | FAIL — read `hr.employees.salary` |
| hr | PASS (avg salary) | FAIL — read `sales.orders` count |
| intern | PASS (product count) | FAIL — read customer email/ssn_last4 and `finance.payments.amount` |

Saved policies show **`deniedTables: []` and `deniedColumns: []` for every user**. English policies only populated `blockedSensitivityCategories` (PII/FINANCIAL). Schema-qualified names (`hr.employees`, `finance.*`, `crm.accounts`) were not resolved into deny lists — likely because brain schema classification / table name extraction wasn’t ready at policy-save time (init status polling returned empty).

Even category blocks did not stop salary/email on the query path. Agent-as-user probes hit MCP “unreachable / not authenticated” for non-admin profiles (per-user agent provisioner tokens not wired in this run).

**Improvement:** parse schema-qualified identifiers into `deniedTables`/`deniedColumns` eagerly (don’t depend on completed classification); enforce the same policy in `QueryExecutorService` for editor + MCP + agent; add `deepsql access test-user` that asserts allow/deny matrices; auto-provision agent profiles when granting connection access.

## Critical findings from this run (evidence-backed)

1. **Business-rule learner polarity / parse bugs**
   - Input: `Exclude crm.accounts where is_deleted = true`
   - Learned: required predicate `is_deleted = 'true'` **and** required table `crm.accounts`
   - That inverts the intent (exclude deleted → require deleted=true) and forces the wrong entity into SQL.
   - Input: `Join sales.orders to sales.customers on orders.customer_id = customers.id`
   - Learned junk predicate: `customer_id = 'customers'`.
   - Schema-qualified “use A instead of B” prose often returned `learnedCount: 0`.

2. **Schema ambiguity inventory is nearly blind on this ERP**
   - `GET /schema-context/ambiguity/{id}` returned **1** item: `pg_stat_statements` ↔ `pg_stat_statements_info`.
   - It missed the planted enterprise homonyms: `sales.customers`/`crm.accounts`, `sales.orders`/`finance.payment_orders`/`sales.order_header`, `sales.products`/`inventory.items`/`inventory.product_master`, overloaded `status`/`amount`/`name`.

3. **Workload recommendations are unsafe on a cold `pg_stat_statements` window**
   - After `pg_stat_statements_reset()` + short synthetic load, advisors proposed **`DROP INDEX …_pkey`** / unique keys as “unused since last reset”.
   - Example: `DROP INDEX order_lines_pkey` surfaced as a top ROI performance action.
   - Need: never recommend dropping constraints/PKs/uniques; require minimum observation window + scans/writes evidence; prefer CREATE INDEX on hot filters (`orders(status, placed_at)`, `order_lines(product_id)`, `invoices(status, due_at)`).

4. **Agent grounding still did well when notes were present**
   - Revenue (90d delivered, non-test) matched ground truth `25874016`.
   - Open orders matched `22748` (PLACED+SHIPPED).
   - “Payment orders” correctly returned `finance.payment_orders` vendor AP rows.
   - Salary question refused via brain-note sensitivity — even as admin.
   - Gaps: “how many customers/products” answered totals (15k/2k) not active-only (9k/1.5k); clarification UX offered after the fact.

5. **Multi-user policies are configured but not enforced in auth-bypass mode**
   - Users + `CHAT_EDITOR` grants + English chat policies saved.
   - `SECURITY_AUTH_ENABLED=false` collapses ACL to admin, so deny/redact was not measured end-to-end.

6. **CLI access grant enum drift**
   - CLI `--level read|write|admin` does not match backend `CHAT_EDITOR|FULL_CONTENT`.
