import { useState } from 'react'
import {
  Bot,
  Brain,
  CheckCircle2,
  Copy,
  Lightbulb,
  Plug,
  ShieldCheck,
  Sparkles,
  Terminal,
  Workflow,
} from 'lucide-react'
import sectionStyles from './TopLevelSection.module.css'
import styles from './DocsSection.module.css'

/* ============================================================================
 * Data: CLI commands
 * ========================================================================== */

const CLI_COMMANDS = [
  {
    name: 'deepsql login',
    desc: 'Authorize this machine once. DeepSQL saves a local token that the CLI and MCP server both reuse, so agents never need pasted secrets.',
    example: 'deepsql login --url https://deepsql.acme.com',
  },
  {
    name: 'deepsql logout',
    desc: 'Revoke the saved token for this DeepSQL profile.',
    example: 'deepsql logout',
  },
  {
    name: 'deepsql whoami',
    desc: 'Show the user behind the saved token and which DeepSQL URL is active.',
    example: 'deepsql whoami',
  },
  {
    name: 'deepsql connections list',
    desc: 'List the databases this user can access. Start here when scripting or debugging because it confirms the exact connection names available.',
    example: 'deepsql connections list',
  },
  {
    name: 'deepsql connections use <name>',
    desc: 'Pin a connection as the default for this profile so other commands don\'t need --connection.',
    example: 'deepsql connections use prod-postgres',
  },
  {
    name: 'deepsql connections add',
    desc: 'Create a connection interactively (or pass --from-file <p> for JSON input). Use deepsql connections schema for the JSON shape.',
    example: 'deepsql connections add --from-file ./conn.json',
  },
  {
    name: 'deepsql connections init <name>',
    desc: 'Re-build the brain (schema scan, embeddings, business-rule indexing). Pass --wait to block until COMPLETED/FAILED.',
    example: 'deepsql connections init prod-postgres --wait',
  },
  {
    name: 'deepsql schema [tables|objects]',
    desc: 'Dump the connection\'s cached schema or DB objects as JSON. tables is the default (columns + FKs).',
    example: 'deepsql schema tables --connection prod-postgres',
  },
  {
    name: 'deepsql query "<sql>"',
    desc: 'Run one SQL statement through DeepSQL policy gates. Great for read-only answers and guarded admin changes; unsafe writes are blocked or require confirmation.',
    example: 'deepsql query "SELECT count(*) FROM orders" --connection prod-postgres --limit 1',
  },
  {
    name: 'deepsql analyze "<sql>"',
    desc: 'Explain a query like a DBA would: parsed plan, bottlenecks, missing indexes, and a summary grounded in schema and business rules. Add --analyze only when execution is safe.',
    example: 'deepsql analyze "SELECT * FROM orders WHERE status=\'OPEN\'" --connection prod-postgres',
  },
  {
    name: 'deepsql brain-context "<question>"',
    desc: 'Ask DeepSQL which tables, columns, relationships, rules, and anti-patterns matter for a question. Use this before generating non-trivial SQL.',
    example: 'deepsql brain-context "active subscriptions by plan and region" --connection prod-postgres',
  },
  {
    name: 'deepsql business-rules',
    desc: 'List active business rules and SQL guardrails. Pass --question "..." to scope to one ask.',
    example: 'deepsql business-rules --connection prod-postgres',
  },
  {
    name: 'deepsql relationships',
    desc: 'List inferred + validated foreign-key relationships, including confidence scores for inferred ones.',
    example: 'deepsql relationships --connection prod-postgres --json',
  },
  {
    name: 'deepsql anti-patterns',
    desc: 'Schema-level (default) or query-level (--kind query) anti-patterns flagged by the brain.',
    example: 'deepsql anti-patterns --kind query --limit 20 --connection prod-postgres',
  },
  {
    name: 'deepsql indexes <subcommand>',
    desc: 'Read-only index intelligence: missing, unused, duplicates, health, usage <table>.',
    example: 'deepsql indexes missing --connection prod-postgres',
  },
  {
    name: 'deepsql index-recommendations <subcommand>',
    desc: 'Workload-weighted index advisor with evidence. Use top/show to inspect recommendations and apply --mode dry-run to validate with HypoPG before any real DDL.',
    example: 'deepsql index-recommendations top --connection prod-postgres --limit 5',
  },
  {
    name: 'deepsql slow-queries <subcommand>',
    desc: 'Read, trigger, and stream slow-query analyses. optimize --query-id <id> streams the AI optimization step-by-step.',
    example: 'deepsql slow-queries latest --connection prod-postgres',
  },
  {
    name: 'deepsql digest [N]',
    desc: 'Show the most recent daily digest (anomalies, top movers, AI commentary). Pass a number to list the last N.',
    example: 'deepsql digest 7',
  },
  {
    name: 'deepsql users <subcommand>',
    desc: 'Admin: list/get/add/set-role/lock/unlock/disable/delete workspace users.',
    example: 'deepsql users list',
  },
  {
    name: 'deepsql access <subcommand>',
    desc: 'Admin: per-connection access grants. list, grant, revoke, policy.',
    example: 'deepsql access grant --user jane@acme.com --connection prod-postgres --level write',
  },
  {
    name: 'deepsql permissions <subcommand>',
    desc: 'Admin: global role-based permission overrides. list, override, reset.',
    example: 'deepsql permissions list --role DEVELOPER',
  },
  {
    name: 'deepsql setup',
    desc: 'Post-install wizard: SMTP/email + Slack (digests + bot), then mark setup complete.',
    example: 'deepsql setup --skip-slack',
  },
  {
    name: 'deepsql mcp',
    desc: 'Run the stdio MCP server with the saved token (used by editor configs — you rarely run this yourself).',
    example: 'deepsql mcp',
  },
  {
    name: 'deepsql mcp config --install --for <editor>',
    desc: 'Install DeepSQL into an editor\'s MCP config AND the "DBA consult" skill. <editor> is claude-code | claude-desktop | cursor | codex.',
    example: 'deepsql mcp config --install --for claude-code',
  },
]

const CLI_GLOBALS = [
  { flag: '--url <url>', desc: 'Target a non-default DeepSQL profile.' },
  { flag: '--token <tok>', desc: 'Bypass the saved profile (also: DEEPSQL_AUTH_TOKEN env).' },
  { flag: '--connection <name>', desc: 'Connection to target (also: DEEPSQL_CONNECTION env).' },
  { flag: '--caller-agent <id>', desc: 'Identify the calling agent in audit logs.' },
  { flag: '--json', desc: 'Machine-readable JSON output (where supported).' },
  { flag: '--no-color', desc: 'Disable ANSI colors in CLI output.' },
  { flag: '-h, --help', desc: 'Per-command usage. e.g. deepsql query --help' },
]

/* ============================================================================
 * Data: MCP tools (kept in lockstep with mcp/deepsql-phase1-lib.js)
 * ========================================================================== */

const MCP_TOOLS = [
  {
    name: 'list_connections',
    desc: 'List databases this user can see. Always call first — every other tool needs the UUID.',
    example: `{
  "name": "list_connections"
}`,
  },
  {
    name: 'get_schema',
    desc: 'Fetch cached schema (tables, columns, FKs, types). Fast and cheap — call freely.',
    example: `{
  "name": "get_schema",
  "arguments": { "connectionId": "<uuid>" }
}`,
  },
  {
    name: 'get_database_objects',
    desc: 'Tables, views, functions, and procedures — when you need DDL-level objects, not just columns.',
    example: `{
  "name": "get_database_objects",
  "arguments": { "connectionId": "<uuid>" }
}`,
  },
  {
    name: 'get_brain_context',
    desc: 'Primary retrieval tool. Returns the tables, columns, FKs, business rules, and anti-patterns most relevant to your question. Call before generating any non-trivial SQL or DDL.',
    example: `{
  "name": "get_brain_context",
  "arguments": {
    "connectionId": "<uuid>",
    "question": "what tables track customer cancellations?"
  }
}`,
  },
  {
    name: 'list_business_rules',
    desc: 'Active business rules and SQL guardrails. Honor these — they encode domain semantics (e.g. always_filter_cancelled).',
    example: `{
  "name": "list_business_rules",
  "arguments": {
    "connectionId": "<uuid>",
    "question": "active subscriptions"
  }
}`,
  },
  {
    name: 'get_relationships',
    desc: 'Inferred + validated foreign keys with confidence scores. Many real DBs lack declared FKs; this fills the gap.',
    example: `{
  "name": "get_relationships",
  "arguments": { "connectionId": "<uuid>" }
}`,
  },
  {
    name: 'get_anti_patterns',
    desc: 'kind="table" returns schema-level smells, kind="query" returns query-level smells (with optional limit).',
    example: `{
  "name": "get_anti_patterns",
  "arguments": {
    "connectionId": "<uuid>",
    "kind": "table"
  }
}`,
  },
  {
    name: 'get_index_recommendations',
    desc: 'Return top workload-weighted index recommendations with evidence, query fingerprints, write-cost context, and expected benefit. Use before proposing CREATE/DROP INDEX.',
    example: `{
  "name": "get_index_recommendations",
  "arguments": {
    "connectionId": "<uuid>",
    "limit": 5
  }
}`,
  },
  {
    name: 'apply_index_recommendation',
    desc: 'Dry-run or apply one recommendation. Default DRY_RUN does not write; APPLY and APPLY_AND_MEASURE require confirm:true and should only run after a human approves.',
    example: `{
  "name": "apply_index_recommendation",
  "arguments": {
    "recommendationId": "<recommendation-id>",
    "mode": "DRY_RUN"
  }
}`,
  },
  {
    name: 'analyze_slow_queries',
    desc: 'Recent slow queries (last 24h) with fingerprints, durations, and example statements. Read-only — does not trigger new work.',
    example: `{
  "name": "analyze_slow_queries",
  "arguments": {
    "connectionId": "<uuid>",
    "thresholdMs": 200,
    "limit": 10
  }
}`,
  },
  {
    name: 'execute_sql',
    desc: 'Run any single SQL statement. Same policy gate as the SQL Editor: developers get SELECT/WITH/SHOW/EXPLAIN; admins also get DML/DDL with two-step confirm (DROP is blocked). Re-send with confirmMutation:true after surfacing the warnings.',
    example: `{
  "name": "execute_sql",
  "arguments": {
    "connectionId": "<uuid>",
    "query": "SELECT count(*) FROM orders WHERE status='OPEN'",
    "limit": 1
  }
}`,
  },
  {
    name: 'analyze_query_plan',
    desc: 'AI-enriched plan analysis. Returns parsed plan tree, perf issues, index recommendations, and a summary that uses your schema + business rules. Pass useAnalyze:true to actually execute (EXPLAIN ANALYZE).',
    example: `{
  "name": "analyze_query_plan",
  "arguments": {
    "connectionId": "<uuid>",
    "query": "SELECT * FROM orders WHERE customer_id=$1",
    "useAnalyze": false
  }
}`,
  },
]

/* ============================================================================
 * Data: editor install one-liners
 * ========================================================================== */

const EDITOR_INSTALL = [
  {
    id: 'claude-code',
    label: 'Claude Code',
    icon: Bot,
    cmd: 'deepsql mcp config --install --for claude-code',
    where: 'Writes into ~/.claude.json and installs the DBA-consult skill into ~/.claude/skills/.',
  },
  {
    id: 'claude-desktop',
    label: 'Claude Desktop',
    icon: Sparkles,
    cmd: 'deepsql mcp config --install --for claude-desktop',
    where: 'Writes into Claude Desktop\'s mcp_settings.json.',
  },
  {
    id: 'cursor',
    label: 'Cursor',
    icon: Workflow,
    cmd: 'deepsql mcp config --install --for cursor',
    where: 'Writes into Cursor\'s ~/.cursor/mcp.json.',
  },
  {
    id: 'codex',
    label: 'Codex',
    icon: Terminal,
    cmd: 'deepsql mcp config --install --for codex',
    where: 'Writes into Codex\'s ~/.codex/config.toml.',
  },
]

const QUICKSTART_COMMANDS = `npm install -g @deepsql/mcp@latest
deepsql login --url https://deepsql.acme.com
deepsql connections list
deepsql connections use prod-postgres
deepsql query "SELECT 1 AS ok" --connection prod-postgres`

const AGENT_RULES = [
  {
    title: 'Start with connection identity',
    body: 'Call list_connections first, then reuse the returned UUID. Do not guess connection IDs, database names, or environments.',
  },
  {
    title: 'Ground before generating SQL',
    body: 'For real work, call get_brain_context before writing SQL or DDL, then verify exact columns with get_schema and relationships.',
  },
  {
    title: 'Respect business rules',
    body: 'Call list_business_rules for domain-sensitive questions. These rules explain semantics like active customers, cancellations, test data, and tenant filters.',
  },
  {
    title: 'Prove performance changes',
    body: 'Use analyze_query_plan and get_index_recommendations before suggesting indexes. Prefer DRY_RUN before any apply mode.',
  },
  {
    title: 'Keep mutations human-approved',
    body: 'Surface warnings clearly. Never hide requiresConfirmation, confirmMutation, --write, or confirm:true behind agent autonomy.',
  },
  {
    title: 'Use the CLI for operator workflows',
    body: 'Daily digests, slow-query optimization streams, and some admin flows are best from the terminal where output is auditable and copyable.',
  },
]

const WORKFLOW_EXAMPLES = [
  {
    title: 'First five minutes',
    audience: 'Developer setup',
    body: 'Install, authenticate, pin a connection, and prove the policy gate can run a harmless query.',
    code: QUICKSTART_COMMANDS,
  },
  {
    title: 'Answer a BI-style question from the terminal',
    audience: 'Analyst-friendly SQL',
    body: 'Ask the brain for the right tables first, then run a readable query with an explicit connection.',
    code: `deepsql brain-context "weekly EMEA pipeline coverage by segment" \\
  --connection prod-postgres

deepsql query "
  SELECT segment,
         date_trunc('week', snapshot_at) AS week,
         round(sum(pipeline_amount) / nullif(sum(quota_amount), 0), 2) AS coverage
  FROM sales_pipeline_snapshots
  WHERE region = 'EMEA'
    AND snapshot_at >= now() - interval '14 days'
  GROUP BY 1, 2
  ORDER BY 2 DESC, coverage DESC
" --connection prod-postgres --limit 50`,
  },
  {
    title: 'Find the safest index fix for checkout latency',
    audience: 'DBA / on-call',
    body: 'Move from symptom to evidence to a dry-run recommendation before creating anything in production.',
    code: `deepsql slow-queries latest --connection prod-postgres

deepsql analyze "
  SELECT *
  FROM orders
  WHERE workspace_id = $1
    AND status = 'PENDING'
  ORDER BY updated_at DESC
  LIMIT 50
" --connection prod-postgres

deepsql index-recommendations top --connection prod-postgres --limit 5
deepsql index-recommendations show <recommendation-id> --connection prod-postgres
deepsql index-recommendations apply <recommendation-id> --mode dry-run`,
  },
  {
    title: 'Let Claude or Codex consult DeepSQL before a migration',
    audience: 'Agent prompt',
    body: 'This prompt tells the agent exactly how to use MCP without overstepping into unsafe writes.',
    code: `Use DeepSQL before proposing schema changes.

1. Call list_connections and choose prod-postgres.
2. Call get_brain_context for "subscription cancellation tracking".
3. Call get_schema and get_relationships for the surfaced tables.
4. Call list_business_rules for cancellation semantics.
5. Explain what DeepSQL found before suggesting a migration.
6. Do not execute DDL. Draft the migration only.`,
  },
  {
    title: 'Run a guarded admin write',
    audience: 'Admin only',
    body: 'DeepSQL makes the human review warnings first; use --write only after approval.',
    code: `deepsql query "
  UPDATE orders
  SET status = 'CLOSED'
  WHERE id = 42
" --connection prod-postgres

# After reviewing warnings:
deepsql query "
  UPDATE orders
  SET status = 'CLOSED'
  WHERE id = 42
" --connection prod-postgres --write`,
  },
]

/* ============================================================================
 * Code block with copy button
 * ========================================================================== */

function CodeBlock({ children, copyLabel }) {
  const [copied, setCopied] = useState(false)
  const handleCopy = async () => {
    try {
      await navigator.clipboard.writeText(children)
      setCopied(true)
      setTimeout(() => setCopied(false), 1200)
    } catch {
      /* clipboard unavailable — silently no-op */
    }
  }
  return (
    <div style={{ position: 'relative' }}>
      <code className={styles.code}>{children}</code>
      <button
        type="button"
        className={styles.copyButton}
        style={{ position: 'absolute', top: 8, right: 8 }}
        onClick={handleCopy}
      >
        {copied ? <CheckCircle2 size={11} /> : <Copy size={11} />}
        {copied ? 'Copied' : (copyLabel || 'Copy')}
      </button>
    </div>
  )
}

/* ============================================================================
 * Tabs
 * ========================================================================== */

const TABS = [
  { id: 'setup', label: 'Setup', icon: Plug },
  { id: 'cli', label: 'CLI reference', icon: Terminal },
  { id: 'mcp', label: 'MCP tools', icon: Brain },
  { id: 'examples', label: 'Examples', icon: Lightbulb },
]

function SetupView() {
  return (
    <>
      <h2 className={styles.sectionTitle}>Install DeepSQL on your machine</h2>
      <p className={styles.sectionLead}>
        One npm package gives you a terminal-native database assistant and an MCP server for Claude Code, Claude Desktop,
        Cursor, and Codex. Log in once; the CLI and every editor agent reuse the same local profile.
      </p>

      <div className={styles.quickGrid}>
        <div className={styles.quickCard}>
          <Terminal size={16} />
          <div>
            <strong>CLI for humans and scripts</strong>
            <span>Run SQL, inspect schema, analyze plans, and pull daily DBA digests from the terminal.</span>
          </div>
        </div>
        <div className={styles.quickCard}>
          <Brain size={16} />
          <div>
            <strong>MCP for coding agents</strong>
            <span>Let Claude and Codex retrieve schema, rules, relationships, and performance context before they write.</span>
          </div>
        </div>
        <div className={styles.quickCard}>
          <ShieldCheck size={16} />
          <div>
            <strong>Same policy gate everywhere</strong>
            <span>Read-only queries run normally; writes require role checks, scoped statements, and explicit confirmation.</span>
          </div>
        </div>
      </div>

      <h3 className={styles.h3}>1. Install the CLI</h3>
      <CodeBlock>npm install -g @deepsql/mcp@latest</CodeBlock>
      <p className={styles.smallNote}>Requires Node ≥ 20. Run <span className={styles.inlineCode}>deepsql --version</span> to confirm.</p>

      <h3 className={styles.h3}>2. Log in</h3>
      <CodeBlock>{'deepsql login --url https://your-deepsql-host.example.com'}</CodeBlock>
      <p className={styles.smallNote}>
        On a desktop, this opens a browser for PKCE. On a headless box, add <span className={styles.inlineCode}>--device</span> for the device-code flow.
        The token lands in <span className={styles.inlineCode}>~/.config/deepsql/auth.json</span> (mode 0600).
      </p>

      <h3 className={styles.h3}>3. Verify and pin a connection</h3>
      <CodeBlock>{`deepsql whoami
deepsql connections list
deepsql connections use prod-postgres
deepsql query "SELECT 1 AS ok" --connection prod-postgres`}</CodeBlock>

      <h3 className={styles.h3}>4. Wire up your AI editor (MCP)</h3>
      <p className={styles.sectionLead}>
        Each command below writes a DeepSQL entry into your editor's MCP config and installs the “DBA consult” skill —
        a small primer that nudges the agent to consult DeepSQL's brain before generating DDL or non-trivial SQL.
        No token is embedded in the editor config; the spawned MCP server uses your saved profile.
      </p>

      <div className={styles.cardRow}>
        {EDITOR_INSTALL.map((editor) => {
          const Icon = editor.icon
          return (
            <div key={editor.id} className={styles.card}>
              <div className={styles.cardTitleRow}>
                <Icon size={16} />
                <h4 className={styles.cardTitle}>{editor.label}</h4>
              </div>
              <CodeBlock>{editor.cmd}</CodeBlock>
              <p className={styles.cardBody}>{editor.where}</p>
            </div>
          )
        })}
      </div>

      <h3 className={styles.h3}>5. Restart your editor</h3>
      <p className={styles.sectionLead}>
        Quit and reopen the editor. The agent should now have DeepSQL tools available
        (<span className={styles.inlineCode}>list_connections</span>, <span className={styles.inlineCode}>get_brain_context</span>,{' '}
        <span className={styles.inlineCode}>get_index_recommendations</span>, <span className={styles.inlineCode}>execute_sql</span>, …)
        and the DBA-consult skill loaded.
        Ask “list my DeepSQL connections” to confirm.
      </p>

      <h3 className={styles.h3}>Preview before you write</h3>
      <p className={styles.sectionLead}>
        Want to see the snippets the installer would write without touching disk? Swap <span className={styles.inlineCode}>--install</span> for{' '}
        <span className={styles.inlineCode}>--print</span>:
      </p>
      <CodeBlock>deepsql mcp config --print --for cursor</CodeBlock>

      <h3 className={styles.h3}>Copy-paste quickstart</h3>
      <p className={styles.sectionLead}>Use this when onboarding a new laptop or rebuilding an agent workstation.</p>
      <CodeBlock>{QUICKSTART_COMMANDS}</CodeBlock>
    </>
  )
}

function CliView() {
  return (
    <>
      <h2 className={styles.sectionTitle}>CLI reference</h2>
      <p className={styles.sectionLead}>
        Every CLI command is a thin shell over the same backend the web UI uses. The same RBAC and policy gates apply.
        Use <span className={styles.inlineCode}>deepsql &lt;command&gt; --help</span> for full per-command usage.
      </p>

      <h3 className={styles.h3}>Commands</h3>
      <div className={styles.tableWrap}>
        <table className={styles.table}>
          <thead>
            <tr>
              <th>Command</th>
              <th>What it does</th>
              <th>Example</th>
            </tr>
          </thead>
          <tbody>
            {CLI_COMMANDS.map((row) => (
              <tr key={row.name}>
                <td className={styles.colName}>{row.name}</td>
                <td className={styles.colDesc}>{row.desc}</td>
                <td className={styles.colExample}><pre>{row.example}</pre></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <h3 className={styles.h3}>Global flags</h3>
      <div className={styles.tableWrap}>
        <table className={styles.table}>
          <thead>
            <tr>
              <th>Flag</th>
              <th>What it does</th>
            </tr>
          </thead>
          <tbody>
            {CLI_GLOBALS.map((row) => (
              <tr key={row.flag}>
                <td className={styles.colName}>{row.flag}</td>
                <td className={styles.colDesc}>{row.desc}</td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </>
  )
}

function McpView() {
  return (
    <>
      <h2 className={styles.sectionTitle}>MCP tools your AI agent can call</h2>
      <p className={styles.sectionLead}>
        Once <span className={styles.inlineCode}>deepsql mcp config --install</span> has run, your editor's agent has the tools below.
        All take a <span className={styles.inlineCode}>connectionId</span> (UUID) you get from{' '}
        <span className={styles.inlineCode}>list_connections</span>. Every call runs through the same policy gate as the web SQL Editor
        and is audited.
      </p>

      <div className={styles.callout}>
        <div className={styles.calloutIcon}><ShieldCheck size={16} /></div>
        <div>
          <strong>Agent contract</strong>
          <span>
            DeepSQL MCP is not just a SQL runner. It is a context layer. Good agents retrieve schema, relationships,
            business rules, and plan evidence before they answer, edit migrations, or suggest indexes.
          </span>
        </div>
      </div>

      <h3 className={styles.h3}>How agents should use DeepSQL</h3>
      <div className={styles.ruleGrid}>
        {AGENT_RULES.map((rule) => (
          <div key={rule.title} className={styles.ruleCard}>
            <strong>{rule.title}</strong>
            <span>{rule.body}</span>
          </div>
        ))}
      </div>

      <div className={styles.tableWrap}>
        <table className={styles.table}>
          <thead>
            <tr>
              <th>Tool</th>
              <th>What it does</th>
              <th>Example payload</th>
            </tr>
          </thead>
          <tbody>
            {MCP_TOOLS.map((row) => (
              <tr key={row.name}>
                <td className={styles.colName}>{row.name}</td>
                <td className={styles.colDesc}>{row.desc}</td>
                <td className={styles.colExample}><pre>{row.example}</pre></td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <p className={styles.smallNote}>
        Daily digests and slow-query optimization streams are CLI-first workflows. Use{' '}
        <span className={styles.inlineCode}>deepsql digest</span> and{' '}
        <span className={styles.inlineCode}>deepsql slow-queries optimize</span> from your terminal.
      </p>
    </>
  )
}

function ExamplesView() {
  return (
    <>
      <h2 className={styles.sectionTitle}>Common workflows</h2>
      <p className={styles.sectionLead}>
        Copy-paste recipes for real jobs. They are written so a human can run them directly and an agent can follow
        the same sequence without skipping context or safety checks.
      </p>

      <div className={styles.workflowGrid}>
        {WORKFLOW_EXAMPLES.map((example) => (
          <article key={example.title} className={styles.recipe}>
            <div className={styles.recipeHeader}>
              <div>
                <span className={styles.pill}>{example.audience}</span>
                <h3>{example.title}</h3>
              </div>
            </div>
            <p>{example.body}</p>
            <CodeBlock>{example.code}</CodeBlock>
          </article>
        ))}
      </div>

      <div className={styles.callout}>
        <div className={styles.calloutIcon}><Lightbulb size={16} /></div>
        <div>
          <strong>Rule of thumb</strong>
          <span>
            If the task changes schema, affects production performance, or touches customer-visible data, use the
            DeepSQL brain first. The fastest safe path is context → plan → evidence → human approval → execution.
          </span>
        </div>
      </div>
    </>
  )
}

/* ============================================================================
 * Section shell
 * ========================================================================== */

export default function DocsSection() {
  const [activeTab, setActiveTab] = useState('setup')

  const renderActive = () => {
    switch (activeTab) {
      case 'cli':
        return <CliView />
      case 'mcp':
        return <McpView />
      case 'examples':
        return <ExamplesView />
      case 'setup':
      default:
        return <SetupView />
    }
  }

  return (
    <div className={sectionStyles.page}>
      <div className={sectionStyles.header}>
        <div className={sectionStyles.eyebrow}>Docs</div>
        <h1 className={sectionStyles.title}>DeepSQL CLI and MCP docs</h1>
        <p className={sectionStyles.subtitle}>
          Install the terminal CLI, connect Claude or Codex through MCP, and give agents the database context they need
          before they write SQL, propose indexes, or draft migrations.
        </p>
      </div>

      <div className={styles.layout}>
        <div className={styles.tabBar} role="tablist" aria-label="Docs sections">
          {TABS.map((tab) => {
            const Icon = tab.icon
            const isActive = activeTab === tab.id
            return (
              <button
                key={tab.id}
                type="button"
                role="tab"
                aria-selected={isActive}
                className={`${styles.tabButton} ${isActive ? styles.tabButtonActive : ''}`}
                onClick={() => setActiveTab(tab.id)}
              >
                <Icon size={14} />
                <span>{tab.label}</span>
              </button>
            )
          })}
        </div>

        <div className={styles.content}>
          {renderActive()}
        </div>
      </div>
    </div>
  )
}
