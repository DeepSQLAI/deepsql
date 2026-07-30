"use strict";

/**
 * `deepsql mcp` — two modes:
 *
 *   1. Bare invocation (`deepsql mcp`) runs the stdio MCP server using the
 *      saved auth token. Editors point at this command so the token never
 *      has to be embedded in their config files.
 *
 *   2. `deepsql mcp config --install --for <editor>` writes a DeepSQL entry
 *      into the editor's MCP config file (JSON or TOML, with a `.bak.<ts>`
 *      backup of the previous contents) AND installs a "DBA consult" skill
 *      so the agent auto-triggers on database work without having to be
 *      told. `--print` emits the snippet only. `--force` overwrites an
 *      existing entry without complaint. `--no-skill` skips the skill
 *      install (config only).
 *
 * Supported editors and skill install paths:
 *   claude-code    →  ~/.claude/settings.json     +  ~/.claude/skills/deepsql/SKILL.md
 *   claude-desktop →  ~/Library/.../Claude/...    +  (no skill — Desktop has no skills surface)
 *   cursor         →  ~/.cursor/mcp.json          +  ~/.cursor/rules/deepsql.mdc
 *   codex          →  ~/.codex/config.toml        +  ~/.codex/AGENTS.md (user-global, append-merge)
 */

const fs = require("node:fs");
const path = require("node:path");
const { spawn, spawnSync } = require("node:child_process");

const { resolveSession } = require("./_session");
const { userHome } = require("../user-home");

const SKILL_HEADER_MARKER = "<!-- BEGIN DEEPSQL DBA CONSULT SKILL -->";
const SKILL_FOOTER_MARKER = "<!-- END DEEPSQL DBA CONSULT SKILL -->";

const EDITORS = {
  "claude-code": {
    format: "json",
    // Claude Code's user-scope MCP server list lives at the top of
    // ~/.claude.json under `mcpServers`. The older ~/.claude/settings.json
    // path that this installer used to target is for permissions/hooks/
    // model settings — Claude Code never reads MCP entries from there.
    //
    // Preferred install path: shell out to `claude mcp add --scope user`
    // (the official CLI handles future storage changes for us). If
    // `claude` isn't on PATH (e.g. CI, SSH boxes), we fall back to
    // writing ~/.claude.json directly via the standard JSON merge.
    path: () => path.join(userHome(), ".claude.json"),
    key: "mcpServers",
    cli: {
      binary: "claude",
      // `claude mcp list` is the smoke test: exit 0 means we have a
      // working Claude Code CLI we can delegate to. We resolve the
      // command/args via builder fns so SERVER_ENTRY_NAME isn't
      // forward-referenced.
      detect: () => ["mcp", "list"],
      // `claude mcp add --scope user <name> <cmd> [args…]`
      add: () => ["mcp", "add", "--scope", "user", SERVER_ENTRY_NAME, SERVER_ENTRY.command, ...SERVER_ENTRY.args],
      remove: () => ["mcp", "remove", SERVER_ENTRY_NAME, "--scope", "user"],
    },
    skill: {
      kind: "file",
      path: () => path.join(userHome(), ".claude", "skills", "deepsql", "SKILL.md"),
      frontmatter: () => [
        "---",
        "name: deepsql",
        "description: Use DeepSQL MCP tools whenever the user is doing database work — adding tables, writing migrations, designing schema, modeling new entities, or running SQL queries. Encodes the \"DBA consult\" pattern: get brain context, schema, business rules, and anti-patterns BEFORE generating any DDL or non-trivial SQL. Triggers on phrases like \"add a table\", \"create a column\", \"write a migration\", \"schema change\", \"design a model\", \"query the database\", \"SQL\", \"ORM model\", \"foreign key\", \"index\".",
        "---",
        "",
      ].join("\n"),
    },
  },
  "claude-desktop": {
    format: "json",
    path: () => claudeDesktopPath(),
    key: "mcpServers",
    skill: null, // Claude Desktop has no skills surface today
  },
  "cursor": {
    format: "json",
    path: () => path.join(userHome(), ".cursor", "mcp.json"),
    key: "mcpServers",
    skill: {
      kind: "file",
      path: () => path.join(userHome(), ".cursor", "rules", "deepsql.mdc"),
      frontmatter: () => [
        "---",
        "description: DeepSQL DBA consult — call DeepSQL's MCP tools BEFORE generating any DDL, migration, or non-trivial SQL. Get brain context, schema, business rules, and anti-patterns first; then narrate findings to the user before proposing schema.",
        "alwaysApply: false",
        "globs:",
        "  - \"**/*.sql\"",
        "  - \"**/migrations/**\"",
        "  - \"**/schema/**\"",
        "  - \"**/models/**\"",
        "  - \"**/*.prisma\"",
        "  - \"**/entities/**\"",
        "---",
        "",
      ].join("\n"),
    },
  },
  "codex": {
    format: "toml",
    path: () => path.join(userHome(), ".codex", "config.toml"),
    key: "mcp_servers",
    skill: {
      kind: "agents-append", // Codex reads AGENTS.md; we append a guarded section
      path: () => path.join(userHome(), ".codex", "AGENTS.md"),
      frontmatter: () => "",
    },
  },
};

const SERVER_ENTRY_NAME = "deepsql";
const SERVER_ENTRY = { command: "deepsql", args: ["mcp"] };

/** Read the shared skill body from disk. Bundled in the npm tarball. */
function loadSkillBody() {
  const bodyPath = path.resolve(__dirname, "..", "..", "skills", "SKILL_BODY.md");
  try {
    return fs.readFileSync(bodyPath, "utf8");
  } catch (err) {
    throw new Error(
      `Could not read the bundled skill body at ${bodyPath}: ${err.message}. `
      + "Is this an incomplete install of @deepsql/mcp?",
    );
  }
}

async function run(opts, io = {}) {
  const sub = opts.positional[0];
  if (!sub) return runServer(opts, io);
  if (sub === "config") {
    return runConfig({ ...opts, positional: opts.positional.slice(1) }, io);
  }
  throw new Error(
    `Unknown mcp subcommand: ${sub}. Try \`deepsql mcp\` (run server) or \`deepsql mcp config --install --for <editor>\`.`,
  );
}

// ─── server (bare `deepsql mcp`) ────────────────────────────────────────────

function runServer(opts) {
  const session = resolveSession(opts);
  const serverPath = path.resolve(__dirname, "..", "..", "deepsql-phase1-server.js");
  const env = {
    ...process.env,
    DEEPSQL_API_BASE_URL: `${session.baseUrl}/api/`,
    DEEPSQL_AUTH_TOKEN: session.token,
  };
  const child = spawn(process.execPath, [serverPath], {
    stdio: ["inherit", "inherit", "inherit"],
    env,
  });
  child.on("exit", (code, signal) => {
    if (signal) process.kill(process.pid, signal);
    else process.exit(code ?? 0);
  });
}

// ─── `deepsql mcp config` ───────────────────────────────────────────────────

async function runConfig(opts, { stdout = process.stdout, stderr = process.stderr } = {}) {
  const editor = opts.for;
  if (!editor) {
    throw new Error(
      `Pass --for <editor>. Supported: ${Object.keys(EDITORS).join(", ")}.`,
    );
  }
  const target = EDITORS[editor];
  if (!target) {
    throw new Error(
      `Unknown editor "${editor}". Supported: ${Object.keys(EDITORS).join(", ")}.`,
    );
  }
  if (!opts.install && !opts.print) {
    throw new Error("Pass --install (write the config) or --print (emit the snippet only).");
  }

  if (opts.print) {
    stdout.write(`# MCP server config snippet\n`);
    stdout.write(`${renderSnippet(target.format, target.key)}\n\n`);
    if (target.skill && !opts.noSkill) {
      stdout.write(`# Skill — installed to ${target.skill.path()}\n`);
      stdout.write(`${renderSkill(target.skill)}\n`);
    } else if (!target.skill) {
      stdout.write(`# (no skill — ${editor} has no skills surface)\n`);
    }
    return;
  }

  // Editor-CLI delegation: if the editor ships its own MCP CLI (Claude
  // Code's `claude mcp add`) AND it's on PATH, use it. Falls back to
  // direct file write if the CLI isn't available — useful for CI and
  // SSH boxes that have @deepsql/mcp installed but no editor.
  const configPath = opts.path || target.path();
  let configResult;
  let writtenVia = configPath;
  const usingCli = target.cli && !opts.path && hasEditorCli(target.cli);
  if (usingCli) {
    try {
      configResult = installViaCli({ cli: target.cli, force: !!opts.force });
      writtenVia = `\`${target.cli.binary} mcp add --scope user\` (user-scope in ~/.claude.json)`;
    } catch (err) {
      stderr.write(
        `\`${target.cli.binary} mcp add\` failed: ${err.message}\nFalling back to direct file write at ${configPath}.\n`,
      );
      configResult = mergeConfig({
        format: target.format,
        key: target.key,
        configPath,
        force: !!opts.force,
      });
    }
  } else {
    configResult = mergeConfig({
      format: target.format,
      key: target.key,
      configPath,
      force: !!opts.force,
    });
  }

  if (configResult.skipped) {
    stderr.write(
      `DeepSQL MCP entry already present (${writtenVia}). Re-run with --force to overwrite, or --print to see the snippet.\n`,
    );
  } else {
    stdout.write(`Installed DeepSQL MCP entry: ${writtenVia}.\n`);
    if (configResult.backupPath) {
      stdout.write(`  backup: ${configResult.backupPath}\n`);
    }
  }

  // Skill install: default-on. Users can opt out with --no-skill if they
  // only want the MCP server config wired up.
  if (target.skill && !opts.noSkill) {
    const skillResult = installSkill({
      skill: target.skill,
      force: !!opts.force,
    });
    if (skillResult.skipped) {
      stderr.write(
        `DeepSQL skill already installed at ${target.skill.path()}. Re-run with --force to overwrite.\n`,
      );
    } else {
      stdout.write(`Installed DeepSQL DBA-consult skill at ${target.skill.path()}.\n`);
      if (skillResult.backupPath) {
        stdout.write(`  backup: ${skillResult.backupPath}\n`);
      }
    }
  } else if (!target.skill && !opts.noSkill) {
    stderr.write(
      `Note: ${editor} has no skills surface, so no skill was installed. The MCP server config still works.\n`,
    );
  }

  stdout.write("Restart the editor for the changes to take effect.\n");
}

// ─── skill install ──────────────────────────────────────────────────────────

/**
 * Render the skill content (frontmatter + shared body). Used by --print and
 * by installSkill().
 */
function renderSkill(skill) {
  const body = loadSkillBody();
  return `${skill.frontmatter()}${body}`;
}

/**
 * Install the skill file for an editor.
 *
 * Two flavors:
 *
 *   - `kind: "file"`  (Claude Code, Cursor)
 *     Writes a standalone skill file at `skill.path()`. If a file is
 *     already there and the content differs, back up before overwriting.
 *     If a file is there and matches, skip silently.
 *
 *   - `kind: "agents-append"`  (Codex CLI's AGENTS.md)
 *     Appends our skill content to the user's AGENTS.md as a guarded
 *     section (between `<!-- BEGIN DEEPSQL ... -->` markers) so the user's
 *     own instructions are preserved. Re-running replaces only the guarded
 *     section.
 *
 * Returns:
 *   { written: true,  backupPath?: "...bak.<ts>" }  on success
 *   { skipped: true }                               on no-op (content matches)
 */
function installSkill({ skill, force }) {
  const skillPath = skill.path();
  ensureParentDir(skillPath);
  const desiredContent = renderSkill(skill);

  if (skill.kind === "agents-append") {
    return upsertGuardedSection({
      filePath: skillPath,
      content: desiredContent,
      force,
    });
  }

  // Standalone-file flavor.
  const exists = fs.existsSync(skillPath);
  const original = exists ? fs.readFileSync(skillPath, "utf8") : null;
  if (exists && original === desiredContent && !force) {
    return { skipped: true };
  }
  let backupPath = null;
  if (exists && original !== desiredContent) {
    backupPath = `${skillPath}.bak.${Date.now()}`;
    fs.writeFileSync(backupPath, original, { mode: 0o600 });
  }
  fs.writeFileSync(skillPath, desiredContent, { mode: 0o600 });
  return { written: true, backupPath };
}

/**
 * Append-or-replace a guarded section in a Markdown file. Preserves the
 * user's content around the section. Re-running rewrites only the section
 * between our markers.
 */
function upsertGuardedSection({ filePath, content, force }) {
  const exists = fs.existsSync(filePath);
  const original = exists ? fs.readFileSync(filePath, "utf8") : "";

  const guarded = `${SKILL_HEADER_MARKER}\n${content}\n${SKILL_FOOTER_MARKER}`;
  const startIdx = original.indexOf(SKILL_HEADER_MARKER);

  let next;
  if (startIdx === -1) {
    // First install: append (with a blank line before if there's existing
    // content).
    const trimmed = original.replace(/\s+$/, "");
    next = trimmed ? `${trimmed}\n\n${guarded}\n` : `${guarded}\n`;
  } else {
    const endIdx = original.indexOf(SKILL_FOOTER_MARKER, startIdx);
    if (endIdx === -1) {
      throw new Error(
        `Found ${SKILL_HEADER_MARKER} in ${filePath} but no matching footer. `
        + "The file is in a half-edited state; fix it by hand and re-run.",
      );
    }
    const before = original.slice(0, startIdx).replace(/\s+$/, "");
    const after = original.slice(endIdx + SKILL_FOOTER_MARKER.length).replace(/^\s+/, "");
    if (!force) {
      // Compare the existing section to the new one.
      const existingSection = original.slice(startIdx, endIdx + SKILL_FOOTER_MARKER.length);
      if (existingSection === guarded) {
        return { skipped: true };
      }
    }
    next = [before, guarded, after].filter(Boolean).join("\n\n").replace(/\s+$/, "") + "\n";
  }

  let backupPath = null;
  if (exists && original !== next) {
    backupPath = `${filePath}.bak.${Date.now()}`;
    fs.writeFileSync(backupPath, original, { mode: 0o600 });
  }
  fs.writeFileSync(filePath, next, { mode: 0o600 });
  return { written: true, backupPath };
}

// ─── snippet rendering ──────────────────────────────────────────────────────

function renderSnippet(format, key) {
  if (format === "toml") {
    return [
      `[${key}.${SERVER_ENTRY_NAME}]`,
      `command = "${SERVER_ENTRY.command}"`,
      `args = ${JSON.stringify(SERVER_ENTRY.args)}`,
    ].join("\n");
  }
  return JSON.stringify(
    { [key]: { [SERVER_ENTRY_NAME]: SERVER_ENTRY } },
    null,
    2,
  );
}

// ─── merge logic ────────────────────────────────────────────────────────────

/**
 * Merge a DeepSQL entry into the editor's config file in-place. JSON files
 * are parsed, mutated, and re-serialized. TOML files use a minimal "find or
 * append" pass on the `[<key>.deepsql]` section header — we don't ship a
 * TOML parser dep just for two lines of config.
 *
 * Returns:
 *   { written: true,  backupPath: "...bak.<ts>" }  on success
 *   { skipped: true }                              if an entry already exists and --force was not set
 */
function mergeConfig({ format, key, configPath, force }) {
  ensureParentDir(configPath);

  const exists = fs.existsSync(configPath);
  const original = exists ? fs.readFileSync(configPath, "utf8") : null;

  let next;
  if (format === "json") {
    next = mergeJson(original, key, force);
  } else if (format === "toml") {
    next = mergeToml(original, key, force);
  } else {
    throw new Error(`Unsupported config format: ${format}`);
  }

  if (next.skipped) return { skipped: true };

  let backupPath = null;
  if (exists && original !== next.text) {
    backupPath = `${configPath}.bak.${Date.now()}`;
    fs.writeFileSync(backupPath, original, { mode: 0o600 });
  }
  fs.writeFileSync(configPath, next.text, { mode: 0o600 });
  return { written: true, backupPath };
}

// ─── Editor-CLI delegation (Claude Code) ───────────────────────────────────
//
// For editors that ship their own MCP-config CLI (currently just Claude
// Code via `claude mcp add`), we prefer delegating instead of writing
// config files ourselves. Three reasons:
//
//   1. The on-disk format is documented as private and has changed once
//      already (settings.json → .claude.json at the top level under
//      mcpServers, vs. local-scope's keyed-by-project layout).
//   2. The CLI handles scopes (user / project / local) correctly. Writing
//      to top-level mcpServers in ~/.claude.json mostly works for user
//      scope but it's brittle; the CLI is the contract.
//   3. If Anthropic changes the layout again, the CLI keeps working
//      without a deepsql release.
//
// We only delegate when `claude mcp list` actually exits 0 — that's our
// proof of life. Otherwise we fall back to direct JSON write.

/**
 * Return true if `claude mcp list` works on this machine. Tested by
 * spawning `claude` with `mcp list` and a 5-second timeout — fast enough
 * to keep the installer snappy, slow enough that a normal cold start
 * (loading config, listing servers) reliably completes.
 *
 * `spawnFn` is overridable for tests.
 */
function hasEditorCli({ binary, detect }, { spawnFn = spawnSync } = {}) {
  if (!binary) return false;
  try {
    const result = spawnFn(binary, detect(), {
      stdio: ["ignore", "pipe", "pipe"],
      timeout: 5000,
    });
    return result && result.status === 0;
  } catch {
    return false;
  }
}

/**
 * Run `claude mcp list` and check whether `deepsql` is already in the
 * output. Used to decide between "no-op", "remove + add" (force), and
 * "add" paths.
 */
function isAlreadyConfiguredViaCli({ binary, detect }, { spawnFn = spawnSync } = {}) {
  try {
    const result = spawnFn(binary, detect(), {
      stdio: ["ignore", "pipe", "pipe"],
      timeout: 5000,
      encoding: "utf8",
    });
    if (!result || result.status !== 0) return false;
    const stdout = String(result.stdout || "");
    // `claude mcp list` lines look like:
    //   deepsql: deepsql mcp - ✓ Connected
    //   deepsql: /path/... - ✗ Failed to connect
    // We match the entry name at the start of a line, followed by `:`,
    // which is stable across both states.
    return new RegExp(`(?:^|\\n)\\s*${SERVER_ENTRY_NAME}\\s*:`).test(stdout);
  } catch {
    return false;
  }
}

/**
 * Install the DeepSQL MCP entry by delegating to the editor's CLI.
 *
 * Returns:
 *   { written: true, viaCli: "<binary>" }    on success
 *   { skipped: true }                         when already present and !force
 *
 * On --force: remove first (best-effort; ignore "not found" exits), then
 * add. The CLI handles backup/atomicity for us.
 */
function installViaCli({ cli, force }, { spawnFn = spawnSync } = {}) {
  if (isAlreadyConfiguredViaCli(cli, { spawnFn }) && !force) {
    return { skipped: true };
  }

  if (force && isAlreadyConfiguredViaCli(cli, { spawnFn })) {
    // Best-effort remove — if the entry was in a different scope, the
    // remove may fail, but that's the user's problem to disambiguate.
    spawnFn(cli.binary, cli.remove(), {
      stdio: ["ignore", "pipe", "pipe"],
      timeout: 5000,
    });
  }

  const addResult = spawnFn(cli.binary, cli.add(), {
    stdio: ["ignore", "pipe", "pipe"],
    timeout: 10000,
    encoding: "utf8",
  });
  if (!addResult || addResult.status !== 0) {
    const stderr = String(addResult && addResult.stderr ? addResult.stderr : "").trim();
    throw new Error(
      `\`${cli.binary} ${cli.add().join(" ")}\` exited with status ${addResult ? addResult.status : "?"}.${
        stderr ? ` stderr: ${stderr}` : ""
      }`,
    );
  }

  return { written: true, viaCli: cli.binary };
}

function mergeJson(originalText, key, force) {
  let parsed = {};
  if (originalText && originalText.trim()) {
    try {
      parsed = JSON.parse(originalText);
    } catch (err) {
      throw new Error(
        `Refusing to overwrite ${err.message ? "malformed JSON" : "config"}. Fix the file by hand first or pass --path <other>.`,
      );
    }
    if (parsed == null || typeof parsed !== "object" || Array.isArray(parsed)) {
      throw new Error("Top-level value must be a JSON object.");
    }
  }
  if (!parsed[key] || typeof parsed[key] !== "object" || Array.isArray(parsed[key])) {
    parsed[key] = {};
  }
  if (parsed[key][SERVER_ENTRY_NAME] && !force) {
    return { skipped: true };
  }
  parsed[key][SERVER_ENTRY_NAME] = SERVER_ENTRY;
  return { text: `${JSON.stringify(parsed, null, 2)}\n` };
}

function mergeToml(originalText, key, force) {
  const text = originalText || "";
  const header = `[${key}.${SERVER_ENTRY_NAME}]`;
  const block = renderSnippet("toml", key);

  // Section already present?
  const lines = text.split(/\r?\n/);
  const startIdx = lines.findIndex((l) => l.trim() === header);
  if (startIdx >= 0) {
    if (!force) return { skipped: true };
    // Replace [header ... up-to-next-header).
    let endIdx = lines.length;
    for (let i = startIdx + 1; i < lines.length; i++) {
      if (/^\s*\[/.test(lines[i])) {
        endIdx = i;
        break;
      }
    }
    const before = lines.slice(0, startIdx).join("\n").replace(/\s+$/, "");
    const after = lines.slice(endIdx).join("\n").replace(/^\s+/, "");
    const joined = [before, block, after].filter(Boolean).join("\n\n");
    return { text: `${joined}\n` };
  }

  // Append. Keep a blank line between any existing content and our block.
  const trimmed = text.replace(/\s+$/, "");
  const joined = trimmed ? `${trimmed}\n\n${block}\n` : `${block}\n`;
  return { text: joined };
}

function ensureParentDir(filePath) {
  const dir = path.dirname(filePath);
  if (!fs.existsSync(dir)) fs.mkdirSync(dir, { recursive: true, mode: 0o700 });
}

function claudeDesktopPath() {
  // macOS: ~/Library/Application Support/Claude/claude_desktop_config.json
  // Windows: %APPDATA%/Claude/claude_desktop_config.json
  // Linux: ~/.config/Claude/claude_desktop_config.json (best-effort; Claude
  //        Desktop isn't officially shipped on Linux yet but the pattern
  //        matches the XDG default).
  if (process.platform === "darwin") {
    return path.join(userHome(), "Library", "Application Support", "Claude", "claude_desktop_config.json");
  }
  if (process.platform === "win32" && process.env.APPDATA) {
    return path.join(process.env.APPDATA, "Claude", "claude_desktop_config.json");
  }
  return path.join(userHome(), ".config", "Claude", "claude_desktop_config.json");
}

module.exports = {
  run,
  // Exported for tests — small surface, all pure-ish.
  mergeJson,
  mergeToml,
  renderSnippet,
  mergeConfig,
  installSkill,
  upsertGuardedSection,
  renderSkill,
  loadSkillBody,
  hasEditorCli,
  isAlreadyConfiguredViaCli,
  installViaCli,
  EDITORS,
};
