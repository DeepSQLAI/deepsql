# DeepSQL Agent

Source of truth for the **DeepSQL Agent** — DeepSQL’s DBA-specialized agent layer:
persona, skills, skins, and install overlays.

## Upstream disclosure

The runtime is a **heavily customized [Nous Hermes Agent](https://hermes-agent.nousresearch.com/)**
(plus its optional webui/TUI). DeepSQL does not ship a fork of that runtime in this
tree. Instead this directory is the product surface we own:

| DeepSQL customization | Purpose |
|-----------------------|---------|
| `SOUL.md` | DBA persona / always-on system prompt (grounding, RBAC, read-only defaults) |
| `skills/*/SKILL.md` | DBA procedures (BI query, schema, indexes, slow queries, workload, dashboards) |
| `skins/deepsql.yaml` | CLI/TUI skin |
| `webui/` | Optional overlay that rebrands a Hermes webui checkout to DeepSQL |
| `tui/` | Optional overlay that rebrands the Hermes TUI checkout to DeepSQL |
| `install.sh` / `distribution.yaml` | Idempotent install + enterprise profile distribution |

Upstream install paths and CLI names (`~/.hermes`, `hermes` binary, `HERMES_*` env
vars, `hermes_profile` cookie, skill frontmatter `metadata.hermes`) are **unchanged** —
those are contracts of the upstream engine. Product UI and docs refer to the
**DeepSQL Agent**, not Hermes.

The in-app Agent tab is DeepSQL’s own React (`AgentChatPanel`); it consumes the
agent HTTP API (`/agent-api/*`), not the Hermes webui skin.

## Contents

- `SOUL.md` — DBA persona. Encodes DeepSQL grounding discipline (`get_brain_context`
  before SQL, table-qualified columns, business rules + anti-patterns, read-only by
  default, two-step mutation confirm).
- `skills/<name>/SKILL.md` — procedural skills (agentskills.io format):
  - `bi-query` — answer a data question with grounded, read-only SQL
  - `schema-exploration` — map/describe a database
  - `index-advisor` — recommend (and dry-run/apply) indexes via the workload-weighted advisor
  - `slow-query-optimize` — diagnose + rewrite one slow query
  - `workload-analysis` — hotspots, regressions, per-customer load, growth
  - `dashboard-design` — HTML dashboard artifact contract for the coding-agent path
- `distribution.yaml` — profile distribution for `hermes profile install/update`
- `webui/`, `tui/`, `skins/` — optional branding overlays for upstream UIs

These mirror workflows the in-house `AgentOrchestrator` performed, re-expressed as
agent persona + skills over the DeepSQL MCP tools.

## Install (self-host / enterprise)

**Preferred:** the `deepsql-agent` Compose service. `./scripts/self-host/install.sh`
builds `agent/Dockerfile` and starts the Agent API (:8787) + profile provisioner
(:8788) on the compose network. No host-side agent install is required.

```bash
./scripts/self-host/install.sh
# or
docker compose up -d --build deepsql-agent
```

### Native / local development (optional)

1. Install the upstream agent runtime (see [AGENTS.md](../AGENTS.md) Cursor Cloud notes).
2. Apply DeepSQL customization (requires `DEEPSQL_CHAT_API_KEY` in the environment or
   the repo `.env`):

```bash
bash agent/install.sh
```

It configures the agent home from this repo:

- **model** — via OpenAI-compatible endpoint (key from env/.env, never committed)
- **mcp_servers.deepsql** — this repo’s `mcp/deepsql-phase1-server.js`
- **skills.external_dirs** — this repo’s `agent/skills` (source of truth; must be a YAML list)
- **approvals.mode: smart**, **SOUL.md** persona, and disables host-affecting toolsets
  (terminal/file/code/browser/computer_use) → a read-only `deepsql:*` + memory/todo/skills sandbox

Verify:

```bash
# Compose
curl -fsS http://localhost:8788/health

# Native (after setup-agent.sh)
./scripts/self-host/setup-agent.sh
```

The DeepSQL MCP server and the Spring backend remain the DBA brain; the agent consumes them.
Optional upstream UI skin: see [`webui/`](webui/).

### Approval UX (operator note)

Because every exposed tool is read-only (`deepsql:*` reads; the one write tool,
`apply_index_recommendation`, is server-side `confirm`-gated; host toolsets disabled),
webui per-call approval prompts add friction without adding safety for this deployment.
Operators can enable session auto-approve (or per-tool “Always allow”) so DBA turns
flow without clicking. Left as a deliberate operator action, not a baked-in default.
