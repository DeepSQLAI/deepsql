# Enterprise DeepSQL bench (ACME ERP)

Simulates a multi-schema Postgres ERP with intentional schema ambiguity, seeded
business context, multi-user access policies, synthetic `pg_stat_statements`
workload, then scores DeepSQL on:

1. Schema ambiguity / business-rule adherence (agent answers vs ground truth)
2. Workload analysis + index/performance recommendations

## Run

```bash
# Backend + agent stack already up; CLI authenticated as admin
bash scripts/enterprise-bench/setup_and_run.sh
```

Artifacts land in `/opt/cursor/artifacts/enterprise-bench/` (`VERDICT.md` + `raw/`).

## Users created

| Email | Access | Policy intent |
|---|---|---|
| analyst@acme.example | CHAT_EDITOR | Sales/CRM/Inventory; block HR PII/salary; redact emails |
| finance@acme.example | CHAT_EDITOR | Finance + sales; block HR |
| hr@acme.example | CHAT_EDITOR | HR only |
| intern@acme.example | CHAT_EDITOR | Narrow product counts; block PII/finance/HR |

Passwords are in `setup_and_run.sh` (`*Pass!23`). Full policy enforcement needs
`SECURITY_AUTH_ENABLED=true` and per-user tokens.
