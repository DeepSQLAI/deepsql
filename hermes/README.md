# DeepSQL Agent customization

Source of truth for the DeepSQL Agent customization layer. This directory contains
the DBA persona, skills, and deployment configuration for the self-hosted agent.

## Contents

- `SOUL.md` — the DBA persona / always-on system prompt. Encodes the DeepSQL grounding discipline (ground with `get_brain_context` before generating SQL, table-qualified columns, honor business rules + anti-patterns, read-only by default, two-step mutation confirm).
- `skills/<name>/SKILL.md` — procedural skills (agentskills.io format) loaded on demand:
  - `bi-query` — answer a data question with grounded, read-only SQL
  - `schema-exploration` — map/describe a database
  - `index-advisor` — recommend (and dry-run/apply) indexes via the workload-weighted advisor
  - `slow-query-optimize` — diagnose + rewrite one slow query
  - `workload-analysis` — hotspots, regressions, per-customer load, growth

These mirror the workflows the in-house `AgentOrchestrator` performed, re-expressed as agent persona + skills.

## Install (local / self-host)

Run the idempotent installer (requires the agent runtime already installed at `~/.hermes/hermes-agent`, and `AZURE_OPENAI_KEY` in the environment or the repo `.env`):

```bash
hermes/install.sh
```

It configures `~/.hermes/config.yaml` reproducibly from this repo:
- **model** — Azure OpenAI gpt-5.4 via its OpenAI-compatible `…/openai/v1` endpoint (key read from env/.env, never committed)
- **mcp_servers.deepsql** — this repo's `mcp/deepsql-phase1-server.js`
- **skills.external_dirs** — this repo's `hermes/skills` (source of truth; must be a YAML list)
- **approvals.mode: smart**, **SOUL.md** persona, and disables host-affecting toolsets (terminal/file/code/browser/computer_use) → a read-only `deepsql:*` + memory/todo/skills sandbox

Verify:
```bash
cd ~/.hermes/hermes-agent && uv run hermes mcp test deepsql   # → ✓ Connected, 41 tools
```

The DeepSQL MCP server and the Spring backend remain the DBA brain; the agent consumes them. Web UI skin: see [`webui/`](webui/).

### Approval UX (operator note)

Because every exposed tool is read-only (`deepsql:*` reads; the one write tool, `apply_index_recommendation`, is server-side `confirm`-gated; host toolsets disabled), the webui per-call approval prompts add friction without adding safety for this deployment. Operators can enable the webui's session auto-approve (or per-tool "Always allow") so DBA turns flow without clicking. This is intentionally left as a deliberate operator action, not a baked-in default.
