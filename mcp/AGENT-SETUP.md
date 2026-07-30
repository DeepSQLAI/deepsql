# DeepSQL — agent-driven setup

> Paste this file's content into Claude Code, Codex CLI, Cursor, or any
> agent capable of running shell commands. The agent will install, log in,
> register your databases, and wire DeepSQL into your editor — typically in
> under 5 minutes. Each step has a clear "exit on failure" so the agent
> won't paper over real problems.

You are setting up DeepSQL for the user. DeepSQL is a self-hosted AI database
performance assistant. The goal of this conversation is to install the CLI,
authorize it against the user's DeepSQL host, register one or more database
connections, and wire the MCP integration into the user's editor.

**Throughout setup, never echo passwords back to the user, never write
secrets to a tracked file, and clean up tempfiles you create.** When you
need credentials, ask one question at a time and stop typing them in
clear text once they're in your head.

---

## Step 1 — Install the CLI

```bash
npm install -g @deepsql/mcp@latest
deepsql --version
```

Confirm Node ≥ 20. If the install fails, ask the user about their npm
permissions and recommend `npm config set prefix '~/.npm-global'` if it's
an EACCES on `/usr/local`.

---

## Step 2 — Log in to DeepSQL

Ask the user for their DeepSQL host URL (e.g., `https://deepsql.example.com`).
Then:

```bash
deepsql login --url <host>
```

This opens a browser tab against the user's DeepSQL host. The user clicks
**Approve** on the device-authorization page; the CLI receives a token and
saves it to `~/.config/deepsql/auth.json` (mode 0600).

If the user is on a remote/SSH box without a browser, fall back to:

```bash
deepsql login --url <host> --device
```

The CLI prints a code; the user opens the URL on their laptop and approves.

Verify:

```bash
deepsql whoami
```

Should print the username, role, URL. If it doesn't, stop and surface the
error to the user — don't proceed with setup.

---

## Step 3 — Inspect the connection config schema

```bash
deepsql connections schema --json
```

This prints the JSON Schema for the connection config. Use it as the
contract for the next step. **Required fields:** `connectionName`, `dbType`
(`postgres` | `mysql`), `host`, `port`, `database`, `username`, `password`.
**Conditional fields:** `sshEnabled` triggers `sshHost`/`sshUsername` and
either `sshPassword` or `sshPrivateKey`. `sslMode` (`server-only` |
`server-client`) triggers SSL-cert fields.

---

## Step 4 — Gather credentials

Ask the user about each database they want to connect to. For each one:

1. **Friendly name** (will be shown in the CLI everywhere)
2. **Database engine** — `postgres` or `mysql`
3. **Host** and **port** (defaults: 5432 for postgres, 3306 for mysql)
4. **Database name** and **username**
5. **Password** — collect securely; do not paste it back into chat
6. **SSH bastion?** If yes: SSH host, port (22), user, key path or password
7. **SSL?** If yes: which mode, and the cert paths if `server-client`
8. **Cloud metadata** (optional but improves DeepSQL's tuning advice):
   AWS / Azure / GCP / self-hosted, managed service (RDS / Aurora / etc.),
   instance class, vCPU / memory / storage type / IOPS

For each connection, write a tempfile with `mktemp`, set mode 0600, and
keep it short-lived. **Never write the JSON to a path the user has open in
their editor or that lives inside a git repo.** Use this exact pattern:

```bash
tmp=$(mktemp /tmp/deepsql-conn-XXXXXX.json)
chmod 600 "$tmp"
cat > "$tmp" <<'EOF'
{
  "connectionName": "prod-mysql",
  "dbType": "mysql",
  "host": "db.example.com",
  "port": 3306,
  "database": "myapp",
  "username": "deepsql_reader",
  "password": "REPLACE_BEFORE_RUNNING",

  "sshEnabled": true,
  "sshAuthType": "PRIVATE_KEY",
  "sshHost": "bastion.example.com",
  "sshPort": 22,
  "sshUsername": "ec2-user",
  "sshPrivateKey": "@file:~/.ssh/bastion_ed25519",

  "sslMode": "server-only",

  "cloudProvider": "aws",
  "managedService": "rds",
  "instanceClass": "db.r6g.xlarge",
  "instanceVcpus": 4,
  "instanceMemoryGb": 32.0,
  "storageType": "gp3"
}
EOF
```

Notes on the secret refs supported in any string field:

- `"$VAR_NAME"` — pulled from `process.env` at CLI runtime, never persisted.
- `"@file:<path>"` — file contents read at runtime; mode 0600 enforced.
- Plaintext is allowed but warns if the JSON file is in a git tree.

The `mktemp` path is guaranteed to be outside any tracked directory, so the
plaintext warning won't fire — and the file gets deleted next.

---

## Step 5 — Test, save, wait

Always test before saving:

```bash
deepsql connections test --from-file "$tmp"
```

The output is a privilege report: `✓` for granted privileges, `✗` for
missing ones, plus `connectionSuccessful` and `sshTunnelSuccessful` flags.
**Stop if `connectionSuccessful=false`.** Common causes:

- Wrong host / port → user fixes the JSON
- Bad SSH key path → check `@file:~/.ssh/...` permissions (must be 0600)
- Bad password → user re-enters
- Missing privileges → DeepSQL still saves (read-only privileges are
  enough), but flag the warning to the user so they know which features
  may be limited

If the test passes, save it:

```bash
deepsql connections add --from-file "$tmp" --delete-after --wait
```

`--delete-after` removes the tempfile on success. `--wait` polls
`GET /connections/{id}/init-status` until brain initialization completes
(or fails). This can take a few minutes on large databases — DeepSQL is
ingesting the schema and indexing it for retrieval. Don't proceed until
this finishes successfully.

---

## Step 6 — Pin the primary connection

```bash
deepsql connections use <connectionName>
```

This sets the active default for the profile. Every subsequent command
(`deepsql brain-context`, `deepsql query`, `deepsql digest`, etc.) uses it
automatically — the user no longer has to pass `--connection`.

If the user has more than one connection, ask which they want pinned. They
can switch later with another `connections use`.

---

## Step 7 — Wire the MCP integration + the DBA-consult skill into the editor

Pick the user's editor and run one of:

```bash
deepsql mcp config --install --for claude-code      # MCP: `claude mcp add --scope user`
                                                    #      (falls back to ~/.claude.json if claude CLI missing)
                                                    # Skill: ~/.claude/skills/deepsql/SKILL.md
deepsql mcp config --install --for claude-desktop   # MCP: ~/Library/Application Support/Claude/...
                                                    # (no skill — Claude Desktop has no skills surface)
deepsql mcp config --install --for cursor           # MCP: ~/.cursor/mcp.json
                                                    # Skill: ~/.cursor/rules/deepsql.mdc
deepsql mcp config --install --for codex            # MCP: ~/.codex/config.toml
                                                    # Skill: ~/.codex/AGENTS.md (guarded section appended)
```

Each invocation does **two** things in one shot:

1. **MCP server config** — writes a `deepsql` entry into the editor's MCP
   config file. The entry is intentionally tiny:

   ```json
   { "command": "deepsql", "args": ["mcp"] }
   ```

   The spawned `deepsql mcp` process reads the saved auth token from
   `~/.config/deepsql/auth.json` (mode 0600), so **no token is embedded
   in the editor config**.

2. **DBA-consult skill** — installs an editor-native skill file that
   auto-triggers when the user does database work. The skill encodes the
   "consult DeepSQL before generating DDL or non-trivial SQL" pattern, so
   the agent reaches for `get_brain_context` / `get_schema` /
   `list_business_rules` reflexively rather than inventing schema in a
   vacuum. Trigger phrases the skill recognizes: "add a table", "write a
   migration", "create a column", "design a model", "schema change",
   "query the database", "SQL", "ORM model", "foreign key", "index".

The installer:

- For **Claude Code**, delegates to `claude mcp add --scope user deepsql deepsql mcp`
  (the official CLI that handles user-vs-project-vs-local scope correctly).
  Falls back to writing `~/.claude.json` directly if the `claude` CLI isn't
  on PATH (e.g. SSH boxes, CI). The older installer wrote to
  `~/.claude/settings.json` which is the wrong file — that's for
  permissions/hooks, not MCP. If a stale entry exists from a manual setup
  or older installer, use `--force` to remove + re-add it.
- For the other editors, creates each target file + parent directory if
  missing, merges into the existing MCP server list without touching
  siblings, refuses to overwrite an existing `deepsql` entry unless
  `--force` is set, and backs up the existing file to
  `<path>.bak.<timestamp>` before any change.
- For Codex's `AGENTS.md`, wraps the skill in guarded
  `<!-- BEGIN DEEPSQL ... -->` / `<!-- END DEEPSQL ... -->` markers and
  preserves the user's surrounding content,
- emits the destination path and any backup path so the user can revert.

If the user wants to see the snippets before installing, swap `--install`
for `--print`. If they want the MCP server config but NOT the skill (e.g.,
they have their own DBA prompt), pass `--no-skill`. For a non-default
MCP config path, pass `--path <p>`.

Restart the editor for the entries to load.

---

## Step 8 — Validate end-to-end

Two quick checks — one for retrieval, one for execution:

```bash
deepsql brain-context "list a few tables on this database" --top-k 5 --json
deepsql query "SELECT 1 AS deepsql_ok"
```

The first should return 5 ranked results pointing at real tables. If the
response is empty, re-run with a more semantic question
(`"what is the primary fact table for orders"`).

The second runs through the canonical Editor SQL endpoint — same policy
gate, audit log, and RBAC the web UI uses. A successful row confirms the
auth token, the connection, and the policy pipeline are all wired up.

If both work, setup is complete. Tell the user that:

- They can now use DeepSQL from their editor (the MCP tools
  `list_connections`, `get_brain_context`, `execute_sql`, `analyze_query_plan`,
  `analyze_slow_queries`, etc. are all available there).
- **From now on, when the user asks the agent to add a table, add a
  column, write a migration, or design a new query against this database,
  the agent will consult DeepSQL first** — checking existing schema,
  business rules, and inferred relationships before generating any DDL.
  This is the explicit pattern in `CLAUDE.md` ("Treat DeepSQL like your
  DBA"). Users should expect the agent to narrate what DeepSQL found
  ("there's already a `customers` table — I'll extend that instead of
  adding `users`") before proposing schema. If the agent skips that step,
  it's a bug in the agent's prompt handling, not in DeepSQL.
- For mutations (DDL/DML), admins can pass `--write` to `deepsql query` to
  skip the interactive confirmation, or just run without it and confirm
  when prompted.
- The companion file `CLAUDE.md` (in this same npm package, at
  `node_modules/@deepsql/mcp/CLAUDE.md`) covers the day-to-day usage
  patterns and common mistakes for editor agents.

---

## Troubleshooting

| Symptom | Likely cause | Diagnose with |
|---|---|---|
| `deepsql login` opens browser but never returns | User closed the tab without clicking Approve | Re-run `deepsql login`. Tell the user to click **Approve** on the page. |
| `whoami` shows wrong user | Stale cached profile from a prior install | `deepsql logout` then `deepsql login --url <host>` |
| `connections test` fails: "DNS resolution" | Host typo or VPC-local hostname not reachable | Verify the host with `dig`/`nslookup`, or set up SSH tunnel |
| `connections test` fails: "SSH tunnel connection failed" | Wrong SSH host, wrong key file path, or key file mode > 600 | `ssh -i ~/.ssh/<key> ec2-user@<host>` to validate the bastion manually |
| `connections add` returns "Missing privileges: SELECT, ..." | DB user has insufficient grants | Save still succeeds; ask the user to grant the missing privileges |
| `connections add --wait` polls forever | Brain init is genuinely running on a huge DB | Default cap is 30 min. Pass `--wait-timeout 60m` or just `Ctrl-C` (init keeps running on the backend) |
| `brain-context` returns `skipped: simple_schema_question` | The question was too short / too schema-y | Ask a more semantic question, or pass `--top-k 5` to force ranked retrieval |
| `connections add` errors before contacting the backend | JSON validation failed | Read the `path: message` lines in the error; fix the JSON; re-run |

---

## Idempotency / re-runs

This entire flow is safe to re-run. `connections use` is idempotent. To
update an existing connection:

```bash
deepsql connections add --from-file "$tmp" --upsert --delete-after --wait
```

`--upsert` does PUT instead of POST when a name collision exists; the
backend's PATCH-style merge preserves any secrets you don't include in
the new JSON.

To remove a connection:

```bash
deepsql connections remove <connectionName> --yes
```

If it was the active default, the pin is cleared automatically.

---

## Reference

- `deepsql --help` — full command list
- `deepsql connections schema [--json]` — full input contract
- `node_modules/@deepsql/mcp/CLAUDE.md` — runtime guidance for AI agents
  using DeepSQL's MCP tools
- `https://github.com/DeepSQLAI/dba-agent` — source repo (private)
