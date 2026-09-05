"use strict";

/**
 * Dispatcher + lightweight argv parser for the `deepsql` CLI.
 *
 * Avoids a heavy CLI framework on purpose — keeps the npm install footprint
 * small for the self-hosted distribution and makes it trivial to embed in
 * scripts. Supports:
 *   - boolean flags:  --json, --device, --browser, --no-browser
 *   - value flags:    --url <url>, --token <t>, --connection <name>, --limit 50
 *   - subcommands:    deepsql connections list, deepsql config show
 *   - positional:     deepsql brain-context "which tables hold orders?"
 *   - per-command -h/--help: prints that command's usage block.
 */

const COMMANDS = {
  agent: () => require("./commands/agent"),
  login: () => require("./commands/login"),
  logout: () => require("./commands/logout"),
  whoami: () => require("./commands/whoami"),
  config: () => require("./commands/config"),
  mcp: () => require("./commands/mcp"),
  connections: () => require("./commands/connections"),
  query: () => require("./commands/query"),
  analyze: () => require("./commands/analyze"),
  explain: () => require("./commands/explain"), // deprecated alias for `analyze`, removed in 0.14.0
  migration: () => require("./commands/migration"),
  schema: () => require("./commands/schema"),
  // Brain tools — give a coding agent the same retrieval context the chat
  // pipeline uses, then let the agent generate SQL/answers itself.
  "brain-context": () => require("./commands/brain-context"),
  brain: () => require("./commands/brain"),
  "business-rules": () => require("./commands/business-rules"),
  relationships: () => require("./commands/relationships"),
  "anti-patterns": () => require("./commands/anti-patterns"),
  digest: () => require("./commands/digest"),
  growth: () => require("./commands/growth"),
  indexes: () => require("./commands/indexes"),
  users: () => require("./commands/users"),
  access: () => require("./commands/access"),
  permissions: () => require("./commands/permissions"),
  "slow-queries": () => require("./commands/slow-queries"),
  setup: () => require("./commands/setup"),
};

// ─── Top-level command catalog (rendered into the root help) ────────────────
//
// `sub: true` marks commands that have nested subcommands. They get a `*`
// suffix in the listing and reveal their full usage via `<command> --help`.

const COMMAND_LIST = [
  ["agent",          false, "Launch the DeepSQL Agent — interactive chat TUI (also: bare `deepsql`)"],
  ["login",          false, "Authorize this CLI with a DeepSQL instance"],
  ["logout",         false, "Revoke and forget the saved token"],
  ["whoami",         false, "Show the user behind the saved token"],
  ["config",         true,  "Manage saved CLI profiles"],
  ["mcp",            true,  "Run the MCP server or install it into an editor config"],
  ["connections",    true,  "Manage database connections"],
  ["query",          false, "Execute a SQL statement (admin: CREATE/ALTER/DML with --write; DROP/TRUNCATE blocked)"],
  ["analyze",        false, "AI-enriched query plan analysis (use --analyze for EXPLAIN ANALYZE)"],
  ["migration",      true,  "Check whether a DDL statement is safe to run before running it"],
  ["schema",         false, "Dump connection schema or DB objects as JSON"],
  ["digest",         true,  "Show DeepSQL daily digests"],
  ["growth",         true,  "Table growth analytics — trends, history, anomalies, alert config"],
  ["brain-context",  false, "Retrieve embedding-ranked context for a question"],
  ["brain",          true,  "Review brain recommendations and remember facts (admin)"],
  ["business-rules", false, "List active business rules and SQL guardrails"],
  ["relationships",  false, "List inferred and validated FK relationships"],
  ["anti-patterns",  false, "List schema- or query-level anti-patterns"],
  ["indexes",        true,  "Workload-weighted index advisor + catalog diagnostics (top, apply, list, missing, health, unused, duplicates, usage)"],
  ["users",          true,  "Manage workspace users (admin)"],
  ["access",         true,  "Manage per-connection access grants (admin)"],
  ["permissions",    true,  "Manage role-based permission overrides (admin)"],
  ["slow-queries",   true,  "Read, trigger, and stream slow-query analyses (admin)"],
  ["setup",          false, "Post-install wizard for SMTP/email and Slack"],
];

const GLOBAL_OPTIONS = [
  ["--url <url>",          "Override the DeepSQL base URL"],
  ["--token <tok>",        "Override the auth token (also: DEEPSQL_AUTH_TOKEN)"],
  ["--connection <name>",  "Override the active connection (also: DEEPSQL_CONNECTION)"],
  ["--caller-agent <id>",  "Identify the calling agent in audit logs (also: DEEPSQL_CALLER_AGENT)"],
  ["--no-color",           "Disable ANSI colors"],
  ["-h, --help",           "Display help for command"],
  ["-v, --version",        "Show version"],
];

// ─── Per-command help blocks ────────────────────────────────────────────────
//
// Each entry is { description, usage, subcommands?, options?, notes? }.
// Subcommands and options render as two-column tables (name | description).
// Centralized here so individual command modules don't each carry a HELP
// constant; the dispatcher renders them on `<command> --help`.

const COMMAND_HELP = {
  agent: {
    description: "Chat with the DeepSQL Agent — DBA work, BI questions, and database guardrails. A thin client: it connects to your server's agent (the same one behind the web Agent tab and Slack), nothing installs locally. Bare `deepsql` in a terminal opens interactive chat; pass a question for a one-shot.",
    usage: 'deepsql agent ["<question>"] [options]   (or just: deepsql)',
    options: [
      ["--url <url>",          "DeepSQL instance to use (default: saved login)"],
      ["--connection <name>",  "Connection to ground answers on (default: saved active connection)"],
    ],
    notes: "No local runtime or LLM key needed — the server runs the agent and proxies the model. Interactive chat resumes your most recent conversation (shared with the web Agent tab).",
  },

  login: {
    description: "Authorize this CLI with a DeepSQL instance.",
    usage: "deepsql login [options]",
    options: [
      ["--url <url>",        "DeepSQL base URL to authorize against"],
      ["--browser",          "Use browser callback (PKCE) — default on desktops"],
      ["--device",           "Use device-code flow — default on headless boxes"],
      ["--password",         "Direct email+password login (fresh self-host VMs)"],
      ["--no-browser",       "Force device-code even on a desktop"],
      ["--email <email>",    "Email for --password flow"],
      ["--password-stdin",   "Read password from stdin (non-interactive)"],
      ["--label <name>",     "Label the saved token for `whoami`"],
    ],
  },

  logout: {
    description: "Revoke and forget the saved token.",
    usage: "deepsql logout [--url <url>]",
    options: [
      ["--url <url>", "Profile to log out of (default: current default profile)"],
    ],
  },

  whoami: {
    description: "Show the user behind the saved token.",
    usage: "deepsql whoami [--url <url>]",
  },

  mcp: {
    description: "Run the stdio MCP server, or install DeepSQL into an editor's MCP config (+ the DBA-consult skill).",
    usage: "deepsql mcp [--url <url>]\n       deepsql mcp config (--install | --print) --for <editor> [--force] [--no-skill] [--path <p>]",
    subcommands: [
      ["(no args)",                                    "Run the stdio MCP server with the saved token"],
      ["config --install --for <editor>",              "Write a DeepSQL entry into the editor's MCP config AND install the DBA-consult skill (backs up on overwrite)"],
      ["config --print --for <editor>",                "Print the snippets (config + skill) without touching disk"],
    ],
    options: [
      ["--for <editor>",   "claude-code, claude-desktop, cursor, or codex"],
      ["--install",        "Write the entry to the editor's default config path AND install the skill"],
      ["--print",          "Emit the snippets only (no filesystem writes)"],
      ["--force",          "Overwrite an existing DeepSQL entry/skill"],
      ["--no-skill",       "Skip the DBA-consult skill install (MCP server config only)"],
      ["--path <p>",       "Override the default MCP config path (advanced; doesn't affect the skill path)"],
      ["--url <url>",      "Profile to bind the spawned MCP server to"],
    ],
    notes: "The installed entry runs `deepsql mcp`, which uses the saved profile — no token is embedded in the editor config. The skill teaches the agent to consult DeepSQL BEFORE generating DDL or non-trivial SQL.",
  },

  config: {
    description: "Manage saved CLI profiles (one per DeepSQL URL).",
    usage: "deepsql config <subcommand> [options]",
    subcommands: [
      ["show",                "List saved profiles (default)"],
      ["set-default <url>",   "Set the default profile"],
      ["remove <url>",        "Forget a saved profile (e.g. a host you no longer run)"],
      ["path",                "Print the auth file path"],
    ],
  },

  connections: {
    description: "Manage database connections — list, pin, full CRUD.",
    usage: "deepsql connections <subcommand> [options]",
    subcommands: [
      ["list [--json] [--refresh]",                  "List database connections (cached ~60s; --refresh forces live)"],
      ["use <name>",                                 "Pin <name> as the active default for this profile"],
      ["current",                                    "Print the active default (exit 1 if none)"],
      ["unset",                                      "Clear the active default for this profile"],
      ["schema [--json]",                            "Print the JSON Schema for the connection config"],
      ["add [options]",                              "Create a connection (interactive or from JSON)"],
      ["update <name> --from-file <p>",              "PATCH-style update; omitted secrets preserved"],
      ["remove <name> [--yes]",                      "Delete a connection (DELETE /connections)"],
      ["test [<name> | --from-file <p>]",            "Validate a connection without saving"],
      ["show <name> [--json]",                       "Show a connection's config (secrets masked)"],
      ["init <name> [--force] [--wait]",             "Trigger brain re-initialization"],
    ],
    options: [
      ["--refresh",                  "Bypass the connections cache and fetch live (list)"],
      ["--from-file <p>",            "Read connection JSON from file"],
      ["--from-stdin",               "Read connection JSON from stdin"],
      ["--upsert",                   "PUT instead of POST on add if name collision"],
      ["--no-test",                  "Skip pre-save POST /connections/test"],
      ["--wait",                     "Poll brain init to COMPLETED/FAILED"],
      ["--delete-after",             "rm the --from-file path on success"],
      ["--cloud",                    "Prompt for cloud/instance metadata"],
      ["--allow-plaintext-secrets",  "Allow plaintext secrets in JSON input"],
      ["--json",                     "Print JSON output where supported"],
      ["--yes",                      "Assume yes for confirmations"],
    ],
  },

  query: {
    description: "Execute a SQL statement against a connection. Same policy gate as the web SQL Editor: developers can run SELECT/WITH/SHOW/EXPLAIN; admins can additionally run DML and non-destructive DDL (CREATE/ALTER) with a two-step confirm. DROP and TRUNCATE are blocked on this surface.",
    usage: 'deepsql query "<sql>" --connection <name> [options]',
    options: [
      ["--connection <name>",    "Connection to run against"],
      ["--limit <n>",            "Row limit (1–1000, default 100)"],
      ["--timeout-seconds <n>",  "Statement timeout in seconds (1–60)"],
      ["--file <path>",          "Read SQL from a file instead of argv"],
      ["--write",                "Confirm a mutation upfront (skips interactive prompt; scripts/CI)"],
      ["--json",                 "Raw JSON output"],
    ],
    notes: "EXPLAIN and EXPLAIN ANALYZE are valid SQL — type them directly. DROP and TRUNCATE are blocked even for admins (use database admin tooling). For the AI-enriched plan analysis, use `deepsql analyze`.",
  },

  analyze: {
    description: "AI-enriched query plan analysis. Returns the parsed plan tree, performance issues, index recommendations, and a written summary that takes the connection's schema and business rules into account.",
    usage: 'deepsql analyze "<sql>" --connection <name> [options]',
    options: [
      ["--connection <name>",    "Connection to run against"],
      ["--analyze",              "Use EXPLAIN ANALYZE (actually executes the query)"],
      ["--write",                "Confirm an ANALYZE-of-mutation upfront (skips interactive prompt)"],
      ["--file <path>",          "Read SQL from a file instead of argv"],
      ["--json",                 "Raw JSON output"],
    ],
    notes: "Pass the underlying SQL, not `EXPLAIN <sql>` — the server wraps it. With --analyze on a mutation, the same admin role + WHERE + confirmation gates as `query` apply.",
  },

  migration: {
    description: "Analyse a DDL statement for lock and rewrite risk before running it. Returns a deterministic verdict (SAFE/CAUTION/DANGER/FAILS/UNKNOWN) verified against a real PostgreSQL — trust it over your own recollection of lock semantics. PostgreSQL only; MySQL returns UNKNOWN. Read-only, never executes the statement.",
    usage: 'deepsql migration analyze --connection <name> --sql "<ddl>"',
    subcommands: [
      ["analyze", "Check whether a migration is safe to run (default)."],
    ],
    options: [
      ["--connection <name>", "Connection to check against"],
      ["--sql <ddl>",         "The DDL statement to analyse"],
      ["--json",              "Raw JSON output"],
    ],
  },

  schema: {
    description: "Dump connection schema or database objects as JSON.",
    usage: "deepsql schema [tables|objects] --connection <name>",
    subcommands: [
      ["tables",   "Tables, columns, FKs (default)"],
      ["objects",  "Database objects (views, procedures, etc.)"],
    ],
  },

  digest: {
    description: "Surface the daily DeepSQL digest from the terminal.",
    usage: "deepsql digest [N|<subcommand>] [options]",
    subcommands: [
      ["(no args)",         "Show the most recent digest, full body"],
      ["<N>",               "List the last N digests, compact"],
      ["list [--count N]",  "Explicit list form (same as digest <N>)"],
      ["show <id>",         "Show one digest by id"],
    ],
    options: [
      ["--connection <name>", "Filter to one connection"],
      ["--json",              "Raw JSON output"],
    ],
  },

  growth: {
    description: "Table growth analytics — size/row trends, detected anomalies, alert thresholds.",
    usage: "deepsql growth <subcommand> [options]",
    subcommands: [
      ["trends    [--table <t>] [--days N=30]",     "Per-table growth headline over the last N days (default)"],
      ["history   [--table <t>] [--days N=7]",      "Raw snapshot history rows"],
      ["anomalies [--table <t>] [--unack] [--days N=30]", "Sudden growth spikes flagged by severity"],
      ["ack       <anomalyId>",                     "Acknowledge an anomaly"],
      ["capture",                                   "Trigger a fresh stats snapshot (admin; async)"],
      ["config    show [--table <t>]",              "Show alert thresholds for the connection"],
      ["config    set --file <path>",               "POST a GrowthAlertConfiguration JSON (admin)"],
    ],
    options: [
      ["--connection <name>",  "Connection to inspect"],
      ["--table <name>",       "Filter to one table"],
      ["--days <N>",           "Lookback window in days (max 365)"],
      ["--unack",              "anomalies: only unacknowledged ones"],
      ["--file <path>",        "config set: path to GrowthAlertConfiguration JSON"],
      ["--json",               "Raw JSON output"],
    ],
    notes: "`capture` and `config set` are the only mutations; both are admin-level but neither writes user data. Trends + anomalies are the agent-facing subcommands and are also exposed via the MCP tools `get_table_growth` and `get_growth_anomalies`.",
  },

  brain: {
    description: "Review what DeepSQL has learned about a connection and teach it more (admin).",
    usage: "deepsql brain <subcommand> [options]",
    subcommands: [
      ["recommendations [--connection <name>] [--limit <n>]",   "AI-proposed things to document/investigate (alias: recs)"],
      ["notes [--connection <name>] [--table <t>] [--column <c>]", "Knowledge already saved to the brain (filterable)"],
      ['remember "<note>" --table <t> [--column <c>]',          "Save a fact to the brain (alias: save)"],
    ],
    options: [
      ["--connection <name>", "Connection to act on (default: active connection)"],
      ["--table <t>",         "Scope notes/remember to a table"],
      ["--column <c>",        "Scope to a column (with --table)"],
      ["--limit <n>",         "Cap results"],
      ["--json",              "Raw JSON output"],
    ],
    notes: "`recommendations` reads /brain/notes/suggestions; `remember` writes a brain note (requires manage-content permission on the connection). The same recommendations surface backs the web UI.",
  },

  "brain-context": {
    description: "Retrieve embedding-ranked tables/columns/FKs, training docs, and business rules for a question.",
    usage: 'deepsql brain-context "<question>" --connection <name> [options]',
    options: [
      ["--connection <name>", "Connection to retrieve from"],
      ["--top-k <n>",         "Return ranked diagnostic snippets (else: rich training payload)"],
      ["--json",              "Raw JSON output"],
    ],
  },

  "business-rules": {
    description: "List active business rules and SQL guardrails for a connection.",
    usage: "deepsql business-rules --connection <name> [options]",
    options: [
      ["--connection <name>", "Connection to inspect"],
      ['--question "..."',    "Scope rules to a specific question"],
      ["--json",              "Raw JSON output"],
    ],
  },

  relationships: {
    description: "Inferred and validated foreign-key relationships with confidence scores.",
    usage: "deepsql relationships --connection <name> [--json]",
    options: [
      ["--connection <name>", "Connection to inspect"],
      ["--json",              "Raw JSON output"],
    ],
  },

  "anti-patterns": {
    description: "Schema-level (table) or query-level anti-patterns detected by the brain.",
    usage: "deepsql anti-patterns --connection <name> [options]",
    options: [
      ["--connection <name>",   "Connection to inspect"],
      ["--kind table|query",    "Anti-pattern flavor (default: table)"],
      ["--limit <n>",           "Limit results (query mode)"],
      ["--json",                "Raw JSON output"],
    ],
  },

  indexes: {
    description:
      "Unified index advisor — workload-weighted recommendations + catalog diagnostics. " +
      "`apply` is the one mutation; dry-run is the default and uses HypoPG on Postgres " +
      "to estimate cost-delta without writes. Write modes (apply / apply-and-measure) " +
      "require --confirm.",
    usage: "deepsql indexes <subcommand> [options]",
    subcommands: [
      // Advisor (workload-weighted)
      ["top    [--limit N]",                                  "Pre-computed top-N pending recommendations (default 5, max 50) — ranked by net benefit, with HypoPG cost-delta + contributing-query evidence"],
      ["list   [--all] [--status PENDING|APPLIED|DISMISSED]", "All recommendations (defaults to PENDING) — simpler view than `top`"],
      ["show   <id> --connection <name>",                     "Full detail: workload, write-cost, HypoPG cost, contributing queries"],
      ["refresh",                                              "Force a fresh accumulation cycle (POST /generate)"],
      ["apply  <id> [--mode <m>] [--confirm] [--no-concurrent]", "Run or dry-run a recommendation with before/after measurement"],
      ["dismiss <id>",                                         "Mark a recommendation as DISMISSED"],
      // Catalog diagnostics
      ["missing",                                              "Missing-index suggestions from the catalog advisor"],
      ["health",                                               "Comprehensive index health report"],
      ["unused",                                               "Indexes the engine has not used (live pg_stat_user_indexes / sys.* probe)"],
      ["duplicates",                                           "Duplicate or redundant indexes"],
      ["usage  <table>",                                       "Per-table index usage statistics"],
    ],
    options: [
      ["--connection <name>",                       "Connection to inspect"],
      ["--limit <n>",                               "top: number of recommendations (default 5, max 50)"],
      ["--all",                                     "list: include APPLIED and DISMISSED rows"],
      ["--status PENDING|APPLIED|DISMISSED",        "list: filter by status"],
      ["--mode dry-run|apply|apply-and-measure",    "apply: defaults to dry-run (HypoPG; no writes)"],
      ["--confirm",                                 "apply: required for apply / apply-and-measure (write modes)"],
      ["--no-concurrent",                           "apply: skip CREATE/DROP INDEX CONCURRENTLY (small dev tables)"],
      ["--json",                                    "Raw backend JSON instead of the terminal-friendly table"],
    ],
    notes:
      "Mirrors the MCP `get_index_recommendations` + `apply_index_recommendation` tools — " +
      "use this when you're at a terminal, the MCP tools when you're inside an AI client. " +
      "Same backend, same data, same safety contract.",
  },

  users: {
    description: "Manage workspace users (admin).",
    usage: "deepsql users <subcommand> [options]",
    subcommands: [
      ["list [--json]",                   "List all users"],
      ["get <ref>",                       "Show one user by email or id"],
      ["add [<email>] [options]",         "Invite or create a user"],
      ["set-role <ref> <role>",           "Change a user's role"],
      ["lock <ref>",                      "Lock a user account"],
      ["unlock <ref>",                    "Unlock a user account"],
      ["disable <ref>",                   "Disable a user account"],
      ["resend-invite <ref>",             "Resend an invitation email"],
      ["reset-password <ref>",            "Reset a user's password"],
      ["delete <ref> [--yes]",            "Permanently delete a user"],
    ],
    options: [
      ["--role <r>",         "Role for `add` / `set-role`"],
      ["--name <n>",         "Display name for `add`"],
      ["--password-stdin",   "Read password from stdin"],
      ["--json",             "Raw JSON output (list)"],
      ["--yes",              "Assume yes for confirmations"],
    ],
    notes: "All endpoints under /admin/users/** require ROLE_ADMIN.",
  },

  access: {
    description: "Per-connection access grants and chat policies (admin).",
    usage: "deepsql access <subcommand> [options]",
    subcommands: [
      ["list --user <ref>",                                          "Connections this user can see"],
      ["list --connection <name>",                                   "Users who can see this connection"],
      ["grant --user <ref> --connection <name> --level <lvl>",       "Grant access (read|write|admin)"],
      ["revoke --user <ref> --connection <name>",                    "Revoke access"],
      ["policy <user> <connection>",                                 "Edit chat policy in $EDITOR"],
    ],
  },

  permissions: {
    description: "Global role-based permission overrides (admin).",
    usage: "deepsql permissions <subcommand> [options]",
    subcommands: [
      ["list [--role <r>] [--json]",                                                        "List permissions, optionally filtered by role"],
      ["override --role <r> --permission <p> --grant|--revoke [--reason \"...\"]",  "Override a role's permission"],
      ["reset --role <r> --permission <p>",                                                 "Remove an override (revert to default)"],
    ],
  },

  "slow-queries": {
    description: "Read, trigger, and stream slow-query analyses (admin).",
    usage: "deepsql slow-queries <subcommand> [options]",
    subcommands: [
      ["latest --connection <name>",                                  "Most recent analysis for the connection"],
      ["history --connection <name> [N]",                             "Last N analyses"],
      ["analyze --connection <name> [options]",                       "Trigger a new analysis"],
      ["optimize --connection <name> --query-id <id>",                "Stream AI optimization steps live (SSE)"],
      ["delete (--history-id <id> | --connection <name>) [--yes]",    "Clean up history"],
      ["trends --connection <name>",                                  "30-day tracked queries with delta metrics"],
      ["regressions --connection <name> [--min-factor <n>]",          "Queries that got slower on the latest run"],
      ["timeline <fingerprint> --connection <name>",                  "Day-by-day metrics for one fingerprint"],
      ["customers --connection <name>",                               "Tenants ranked by total slow-query load"],
      ["samples <fingerprint> --connection <name> [--limit <n>]",     "Literal SQL samples (with bind values) for a fingerprint"],
      ["insights --connection <name> [--kind <k>] [--window <w>]",    "AI insights: hotspots, remediation, tail-risk, plan-drift, skew"],
      ["trigger --connection <name>",                                 "Trigger an immediate analysis run"],
    ],
    options: [
      ["--time-range LAST_24_HOURS|LAST_HOUR",                "Window for analyze"],
      ["--threshold-ms <n>",                                  "Min duration to consider"],
      ["--limit <n>",                                         "Cap results"],
      ["--min-factor <n>",                                    "Min slowdown multiple for regressions (default 1.5)"],
      ["--kind hotspots|remediation|tail-risk|plan-drift|skew", "Insight category (default: all)"],
      ["--window LAST_24_HOURS|LAST_7_DAYS|LAST_30_DAYS",     "Insights window (default LAST_7_DAYS)"],
      ["--json",                                              "Raw JSON output"],
    ],
    notes: "`optimize` follows the SSE protocol from /slow-queries/optimize/stream — step events go to stderr, the final result to stdout. Honors SIGINT.",
  },

  setup: {
    description: "Post-install wizard: SMTP/email, Slack (digests + bot), then mark setup complete.",
    usage: "deepsql setup [options]",
    options: [
      ["--skip-email",     "Skip the SMTP/email step"],
      ["--skip-slack",     "Skip the Slack step"],
      ["--skip-complete",  "Don't mark setup complete at the end"],
    ],
    notes: "Org and LLM config are set at install time and are NOT touched by this wizard.",
  },

};

// ─── Color helpers ──────────────────────────────────────────────────────────

function colorEnabled(stream, rawArgv) {
  if (process.env.NO_COLOR) return false;
  if (rawArgv && (rawArgv.includes("--no-color") || rawArgv.includes("--no-colors"))) return false;
  if (process.env.FORCE_COLOR === "1") return true;
  return !!(stream && stream.isTTY);
}

function paint(useColor) {
  if (!useColor) {
    const id = (s) => s;
    return { brand: id, accent: id, bold: id, dim: id, reset: "" };
  }
  return {
    brand:  (s) => `\x1b[1;38;5;75m${s}\x1b[0m`,   // bold sky-blue
    accent: (s) => `\x1b[38;5;75m${s}\x1b[0m`,     // sky-blue
    bold:   (s) => `\x1b[1m${s}\x1b[0m`,
    dim:    (s) => `\x1b[2m${s}\x1b[0m`,
    reset:  "\x1b[0m",
  };
}

function pad(str, width) {
  return str + " ".repeat(Math.max(0, width - str.length));
}

function renderTable(rows, c, { nameMin = 16, gap = 2 } = {}) {
  const nameWidth = Math.max(nameMin, ...rows.map((r) => r[0].length));
  return rows
    .map(([name, desc]) => `  ${c.accent(pad(name, nameWidth))}${" ".repeat(gap)}${desc}`)
    .join("\n");
}

// ─── Renderers ──────────────────────────────────────────────────────────────

function renderRootHelp(useColor) {
  const c = paint(useColor);
  const pkg = require("../package.json");

  const header =
    `${c.brand("🐬 DeepSQL")} ${c.accent(pkg.version)} ` +
    c.dim("— ask the database what's wrong. It already knows.");

  const usage = `${c.bold("Usage:")} deepsql [options] [command]`;

  const optionsBlock =
    `${c.bold("Options:")}\n` + renderTable(GLOBAL_OPTIONS, c, { nameMin: 22 });

  const commandRows = COMMAND_LIST.map(([name, hasSub, desc]) => [
    hasSub ? `${name} *` : name,
    desc,
  ]);
  const commandsBlock =
    `${c.bold("Commands:")}\n` +
    `  ${c.dim("Hint: commands suffixed with * have subcommands. Run <command> --help for details.")}\n` +
    renderTable(commandRows, c, { nameMin: 16 });

  return [header, "", usage, "", optionsBlock, "", commandsBlock, ""].join("\n");
}

function renderCommandHelp(name, useColor) {
  const c = paint(useColor);
  const help = COMMAND_HELP[name];
  if (!help) {
    return `${c.bold("deepsql " + name)}\n  No detailed help available — run \`deepsql --help\` for the command list.\n`;
  }

  const lines = [];
  lines.push(`${c.brand("deepsql " + name)} ${c.dim("— " + help.description)}`);
  lines.push("");
  lines.push(`${c.bold("Usage:")} ${help.usage}`);
  if (help.subcommands && help.subcommands.length > 0) {
    lines.push("");
    lines.push(`${c.bold("Subcommands:")}`);
    lines.push(renderTable(help.subcommands, c, { nameMin: 24 }));
  }
  if (help.options && help.options.length > 0) {
    lines.push("");
    lines.push(`${c.bold("Options:")}`);
    lines.push(renderTable(help.options, c, { nameMin: 24 }));
  }
  if (help.notes) {
    lines.push("");
    lines.push(c.dim(help.notes));
  }
  lines.push("");
  return lines.join("\n");
}

// ─── Argv parser ────────────────────────────────────────────────────────────

function parseArgs(argv) {
  const result = { positional: [], flags: {} };
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === "--") {
      result.positional.push(...argv.slice(i + 1));
      break;
    }
    if (arg.startsWith("--")) {
      const eq = arg.indexOf("=");
      const key = (eq === -1 ? arg.slice(2) : arg.slice(2, eq));
      let value;
      if (eq !== -1) {
        value = arg.slice(eq + 1);
      } else if (i + 1 < argv.length && !argv[i + 1].startsWith("-")) {
        value = argv[++i];
      } else {
        value = true;
      }
      result.flags[normalizeKey(key)] = coerce(value);
    } else if (arg.startsWith("-") && arg.length > 1) {
      const key = arg.slice(1);
      result.flags[normalizeKey(key)] = true;
    } else {
      result.positional.push(arg);
    }
  }
  return result;
}

function normalizeKey(key) {
  // --no-browser → noBrowser, --timeout-seconds → timeoutSeconds
  return key.replace(/-([a-z])/g, (_, c) => c.toUpperCase());
}

function coerce(value) {
  if (value === "true") return true;
  if (value === "false") return false;
  return value;
}

function buildOpts(parsed) {
  const f = parsed.flags;
  return {
    positional: parsed.positional,
    url: f.url || null,
    token: f.token || null,
    json: !!f.json,
    // Login-flow selectors
    device: !!f.device,
    browser: !!f.browser,
    noBrowser: !!f.noBrowser,
    // password may be `true` (login flow flag) OR a string value (`users add
    // --password secret`, `users add --password=secret`). Each command
    // interprets whichever shape it expects.
    password: f.password ?? null,
    passwordStdin: !!f.passwordStdin,
    email: f.email || null,
    label: f.label || null,
    // Connection / users / chat
    connection: f.connection || f.c || null,
    // Brain notes scoping
    table: f.table || null,
    column: f.column || null,
    chat: f.chat || null,
    user: f.user || null,
    project: f.project || null,
    name: f.name || null,
    username: f.username || null,
    role: f.role || null,
    // List / pagination
    limit: f.limit,
    count: f.count || f.n || null,
    timeoutSeconds: f.timeoutSeconds,
    file: f.file || null,
    // RBAC / access
    level: f.level || null,
    permission: f.permission || null,
    grant: !!f.grant,
    revoke: !!f.revoke,
    reason: f.reason || null,
    // Brain tools
    topK: f.topK ?? null,
    kind: f.kind || null,
    question: f.question || null,
    // Slow queries
    timeRange: f.timeRange || null,
    thresholdMs: f.thresholdMs || null,
    queryId: f.queryId || null,
    queryText: f.queryText || null,
    sampleQuery: f.sampleQuery || null,
    historyId: f.historyId || null,
    // Indexes
    all: !!f.all,
    status: f.status || null,
    // Growth analytics (also re-used wherever a per-table / lookback filter
    // is useful — kept generic on purpose)
    table: f.table || null,
    days: f.days || null,
    unack: !!f.unack,
    // mcp config installer
    install: !!f.install,
    print: !!f.print,
    for: f.for || null,
    path: f.path || null,
    noSkill: !!f.noSkill,
    // SQL execution
    write: !!f.write,
    analyze: !!f.analyze,
    sql: f.sql || null,
    // Origin tagging for audit
    callerAgent: f.callerAgent || null,
    // Setup wizard
    force: !!f.force,
    skipEmail: !!f.skipEmail,
    skipSlack: !!f.skipSlack,
    skipComplete: !!f.skipComplete,
    // Confirmations
    yes: !!f.yes || !!f.y,
    // Index recommendations (apply tool)
    mode: f.mode || null,
    confirm: !!f.confirm,
    noConcurrent: !!f.noConcurrent,
    // Connection management
    fromFile: f.fromFile || null,
    fromStdin: !!f.fromStdin,
    upsert: !!f.upsert,
    noTest: !!f.noTest,
    wait: !!f.wait,
    deleteAfter: !!f.deleteAfter,
    cloud: !!f.cloud,
    allowPlaintextSecrets: !!f.allowPlaintextSecrets,
    // Display
    noColor: !!f.noColor,
  };
}

function isHelpFlag(arg) {
  return arg === "--help" || arg === "-h";
}

async function main(rawArgv = process.argv.slice(2), io = {}) {
  const stderr = io.stderr || process.stderr;
  const stdout = io.stdout || process.stdout;
  const useColor = colorEnabled(stdout, rawArgv);

  if (isHelpFlag(rawArgv[0])) {
    stdout.write(`${renderRootHelp(useColor)}\n`);
    return 0;
  }
  if (rawArgv[0] === "--version" || rawArgv[0] === "-v") {
    const pkg = require("../package.json");
    stdout.write(`${pkg.version}\n`);
    return 0;
  }
  // No subcommand: launch the DeepSQL Agent TUI in an interactive terminal;
  // fall back to the root help when stdout is piped/redirected (scripts, CI).
  if (rawArgv.length === 0 && !stdout.isTTY) {
    stdout.write(`${renderRootHelp(useColor)}\n`);
    return 0;
  }

  const command = rawArgv.length === 0 ? "agent" : rawArgv[0];
  const loader = COMMANDS[command];
  if (!loader) {
    stderr.write(`Unknown command: ${command}\n\n${renderRootHelp(colorEnabled(stderr, rawArgv))}\n`);
    return 2;
  }

  // Per-command help: `deepsql <command> --help` / `-h` (anywhere in args).
  const restArgs = rawArgv.length === 0 ? [] : rawArgv.slice(1);
  if (restArgs.some(isHelpFlag)) {
    stdout.write(`${renderCommandHelp(command, useColor)}\n`);
    return 0;
  }

  const parsed = parseArgs(restArgs);
  const opts = buildOpts(parsed);

  // Stamp origin headers on every backend request the command will make.
  // `clientType` is fixed for CLI traffic; `clientAgent` defaults to
  // "terminal" but agents that shell out can override via --caller-agent or
  // the DEEPSQL_CALLER_AGENT env var, so audit rows distinguish "human in a
  // terminal" from "Claude Code invoked deepsql query."
  const { setClientContext } = require("./api/client");
  const pkg = require("../package.json");
  setClientContext({
    type: "cli",
    agent: opts.callerAgent || process.env.DEEPSQL_CALLER_AGENT || "terminal",
    version: pkg.version,
  });

  const previousExitCode = process.exitCode;
  process.exitCode = undefined;
  try {
    const mod = loader();
    const commandCode = await mod.run(opts, { stdout, stderr });
    if (typeof commandCode === "number") {
      return commandCode;
    }
    if (typeof process.exitCode === "number" && process.exitCode !== 0) {
      return process.exitCode;
    }
    return 0;
  } catch (err) {
    stderr.write(`Error: ${err.message}\n`);
    if (process.env.DEEPSQL_DEBUG === "1" && err.stack) {
      stderr.write(`${err.stack}\n`);
    }
    return 1;
  } finally {
    if (previousExitCode == null) {
      process.exitCode = undefined;
    } else {
      process.exitCode = previousExitCode;
    }
  }
}

module.exports = { main, parseArgs, buildOpts, renderRootHelp, renderCommandHelp, COMMAND_HELP };
