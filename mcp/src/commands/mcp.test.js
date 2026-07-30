"use strict";

const test = require("node:test");
const assert = require("node:assert/strict");
const fs = require("node:fs");
const os = require("node:os");
const path = require("node:path");

const {
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
} = require("./mcp");

function withTempDir(fn) {
  return async () => {
    const dir = fs.mkdtempSync(path.join(os.tmpdir(), "deepsql-mcp-skill-test-"));
    try {
      await fn(dir);
    } finally {
      fs.rmSync(dir, { recursive: true, force: true });
    }
  };
}

// ─── snippet rendering ──────────────────────────────────────────────────────

test("renderSnippet emits valid JSON for the JSON editors", () => {
  const text = renderSnippet("json", "mcpServers");
  const parsed = JSON.parse(text);
  assert.deepEqual(parsed, {
    mcpServers: { deepsql: { command: "deepsql", args: ["mcp"] } },
  });
});

test("renderSnippet emits a parseable [mcp_servers.deepsql] section for codex", () => {
  const text = renderSnippet("toml", "mcp_servers");
  assert.match(text, /^\[mcp_servers\.deepsql\]/m);
  assert.match(text, /command = "deepsql"/);
  assert.match(text, /args = \["mcp"\]/);
});

// ─── JSON merge ─────────────────────────────────────────────────────────────

test("mergeJson creates the file when nothing exists", () => {
  const r = mergeJson(null, "mcpServers", false);
  const parsed = JSON.parse(r.text);
  assert.equal(parsed.mcpServers.deepsql.command, "deepsql");
});

test("mergeJson preserves siblings under mcpServers", () => {
  const original = JSON.stringify({
    mcpServers: {
      "other-tool": { command: "node", args: ["server.js"] },
    },
  });
  const r = mergeJson(original, "mcpServers", false);
  const parsed = JSON.parse(r.text);
  assert.equal(parsed.mcpServers["other-tool"].command, "node");
  assert.equal(parsed.mcpServers.deepsql.command, "deepsql");
});

test("mergeJson preserves top-level siblings (e.g. permissions, env)", () => {
  const original = JSON.stringify({ env: { FOO: "bar" }, permissions: ["x"] });
  const r = mergeJson(original, "mcpServers", false);
  const parsed = JSON.parse(r.text);
  assert.deepEqual(parsed.env, { FOO: "bar" });
  assert.deepEqual(parsed.permissions, ["x"]);
  assert.ok(parsed.mcpServers.deepsql);
});

test("mergeJson skips when an existing deepsql entry exists and --force is off", () => {
  const original = JSON.stringify({
    mcpServers: { deepsql: { command: "stale", args: [] } },
  });
  const r = mergeJson(original, "mcpServers", false);
  assert.equal(r.skipped, true);
});

test("mergeJson overwrites with --force", () => {
  const original = JSON.stringify({
    mcpServers: { deepsql: { command: "stale", args: [] } },
  });
  const r = mergeJson(original, "mcpServers", true);
  const parsed = JSON.parse(r.text);
  assert.equal(parsed.mcpServers.deepsql.command, "deepsql");
});

test("mergeJson refuses to silently overwrite malformed JSON", () => {
  assert.throws(() => mergeJson("{this is not json", "mcpServers", false), /malformed JSON|config/);
});

test("mergeJson rejects a top-level array", () => {
  assert.throws(() => mergeJson("[1,2,3]", "mcpServers", false), /must be a JSON object/);
});

// ─── TOML merge ─────────────────────────────────────────────────────────────

test("mergeToml appends when no section exists", () => {
  const r = mergeToml("", "mcp_servers", false);
  assert.match(r.text, /^\[mcp_servers\.deepsql\]/m);
});

test("mergeToml preserves unrelated sections above and below", () => {
  const original = [
    "[runtime]",
    "verbose = true",
    "",
    "[mcp_servers.other]",
    'command = "elsewhere"',
    "",
  ].join("\n");
  const r = mergeToml(original, "mcp_servers", false);
  assert.match(r.text, /\[runtime\]/);
  assert.match(r.text, /verbose = true/);
  assert.match(r.text, /\[mcp_servers\.other\]/);
  assert.match(r.text, /\[mcp_servers\.deepsql\]/);
});

test("mergeToml skips an existing deepsql section without --force", () => {
  const original = '[mcp_servers.deepsql]\ncommand = "stale"\nargs = []\n';
  const r = mergeToml(original, "mcp_servers", false);
  assert.equal(r.skipped, true);
});

test("mergeToml replaces an existing deepsql section with --force, leaving neighbors alone", () => {
  const original = [
    "[mcp_servers.deepsql]",
    'command = "stale"',
    "args = []",
    "",
    "[mcp_servers.other]",
    'command = "elsewhere"',
    "",
  ].join("\n");
  const r = mergeToml(original, "mcp_servers", true);
  assert.match(r.text, /command = "deepsql"/);
  assert.equal(/command = "stale"/.test(r.text), false);
  assert.match(r.text, /\[mcp_servers\.other\]/, "neighbor section must survive");
  assert.match(r.text, /command = "elsewhere"/);
});

// ─── filesystem integration ────────────────────────────────────────────────

test("mergeConfig writes the JSON file (no original, no backup)", withTempDir((dir) => {
  const configPath = path.join(dir, "mcp.json");
  const result = mergeConfig({
    format: "json", key: "mcpServers", configPath, force: false,
  });
  assert.equal(result.written, true);
  assert.equal(result.backupPath, null);
  const parsed = JSON.parse(fs.readFileSync(configPath, "utf8"));
  assert.equal(parsed.mcpServers.deepsql.command, "deepsql");
}));

test("mergeConfig backs up the existing file when it actually changes", withTempDir((dir) => {
  const configPath = path.join(dir, "mcp.json");
  fs.writeFileSync(configPath, JSON.stringify({ mcpServers: { other: { command: "x" } } }));
  const result = mergeConfig({
    format: "json", key: "mcpServers", configPath, force: false,
  });
  assert.equal(result.written, true);
  assert.ok(result.backupPath, "backup path must be set when content changed");
  assert.ok(fs.existsSync(result.backupPath));
  const restored = JSON.parse(fs.readFileSync(result.backupPath, "utf8"));
  assert.equal(restored.mcpServers.other.command, "x", "backup must contain pre-merge content");
}));

test("mergeConfig returns skipped=true when deepsql already there and --force off", withTempDir((dir) => {
  const configPath = path.join(dir, "mcp.json");
  fs.writeFileSync(configPath, JSON.stringify({
    mcpServers: { deepsql: { command: "deepsql", args: ["mcp"] } },
  }));
  const result = mergeConfig({
    format: "json", key: "mcpServers", configPath, force: false,
  });
  assert.equal(result.skipped, true);
}));

test("mergeConfig writes TOML to ~/.codex/config.toml layout", withTempDir((dir) => {
  const configPath = path.join(dir, "config.toml");
  fs.writeFileSync(configPath, "[runtime]\nverbose = true\n");
  const result = mergeConfig({
    format: "toml", key: "mcp_servers", configPath, force: false,
  });
  assert.equal(result.written, true);
  const text = fs.readFileSync(configPath, "utf8");
  assert.match(text, /\[runtime\]/);
  assert.match(text, /\[mcp_servers\.deepsql\]/);
}));

test("mergeConfig creates parent directories that don't exist yet", withTempDir((dir) => {
  const configPath = path.join(dir, "deep", "nested", "mcp.json");
  const result = mergeConfig({
    format: "json", key: "mcpServers", configPath, force: false,
  });
  assert.equal(result.written, true);
  assert.ok(fs.existsSync(configPath));
}));

// ─── editor catalog ────────────────────────────────────────────────────────

test("EDITORS catalog exposes the four supported targets with sensible defaults", () => {
  for (const editor of ["claude-code", "claude-desktop", "cursor", "codex"]) {
    const e = EDITORS[editor];
    assert.ok(e, `missing editor: ${editor}`);
    assert.match(e.format, /^(json|toml)$/);
    assert.ok(typeof e.path === "function");
    const p = e.path();
    assert.ok(p && typeof p === "string" && p.length > 0);
  }
  assert.equal(EDITORS.codex.format, "toml");
  for (const editor of ["claude-code", "claude-desktop", "cursor"]) {
    assert.equal(EDITORS[editor].format, "json");
  }
});

test("claude-code falls back to ~/.claude.json (not ~/.claude/settings.json) for user-scope MCP", () => {
  // ~/.claude/settings.json is for permissions/hooks/model settings;
  // Claude Code reads MCP from ~/.claude.json at the top level. Older
  // installer versions had this wrong, which surfaced as
  // "deepsql_* tools aren't loaded in this session" reports from users.
  const p = EDITORS["claude-code"].path();
  assert.ok(p.endsWith(".claude.json"), `unexpected claude-code fallback path: ${p}`);
  assert.equal(/\.claude\/settings\.json$/.test(p), false);
});

test("claude-code prefers CLI delegation (cli descriptor present)", () => {
  const cli = EDITORS["claude-code"].cli;
  assert.ok(cli, "claude-code must carry a CLI delegation descriptor");
  assert.equal(cli.binary, "claude");
  assert.deepEqual(cli.detect(), ["mcp", "list"]);
  // `claude mcp add --scope user deepsql deepsql mcp`
  assert.deepEqual(cli.add(), ["mcp", "add", "--scope", "user", "deepsql", "deepsql", "mcp"]);
  assert.deepEqual(cli.remove(), ["mcp", "remove", "deepsql", "--scope", "user"]);
});

test("cursor and codex do not carry a CLI delegation descriptor (no equivalent tooling)", () => {
  assert.equal(EDITORS["cursor"].cli, undefined);
  assert.equal(EDITORS["codex"].cli, undefined);
  assert.equal(EDITORS["claude-desktop"].cli, undefined);
});

// ─── CLI delegation helpers ────────────────────────────────────────────────

function fakeSpawn(table) {
  // table is an array of { match: { args: [...] }, result: { status, stdout?, stderr? } }
  // First match wins; unmatched calls fail loudly so tests catch silent fallthroughs.
  return (binary, args) => {
    for (const entry of table) {
      if (!entry.match) return entry.result;
      const a = entry.match.args;
      if (a.length === args.length && a.every((v, i) => v === args[i])) {
        return entry.result;
      }
    }
    throw new Error(`fakeSpawn: no match for ${binary} ${args.join(" ")}`);
  };
}

test("hasEditorCli returns true when the detect command exits 0", () => {
  const spawnFn = fakeSpawn([{ match: { args: ["mcp", "list"] }, result: { status: 0, stdout: "" } }]);
  assert.equal(hasEditorCli(EDITORS["claude-code"].cli, { spawnFn }), true);
});

test("hasEditorCli returns false when the binary errors / isn't found", () => {
  const spawnFn = () => { throw Object.assign(new Error("ENOENT"), { code: "ENOENT" }); };
  assert.equal(hasEditorCli(EDITORS["claude-code"].cli, { spawnFn }), false);
});

test("hasEditorCli returns false when the binary exits non-zero", () => {
  const spawnFn = fakeSpawn([{ match: { args: ["mcp", "list"] }, result: { status: 1 } }]);
  assert.equal(hasEditorCli(EDITORS["claude-code"].cli, { spawnFn }), false);
});

test("isAlreadyConfiguredViaCli matches 'deepsql:' line in claude mcp list output", () => {
  const stdout = [
    "claude.ai Gmail: https://gmailmcp.googleapis.com/mcp/v1 - ✓ Connected",
    "claude.ai Slack: https://mcp.slack.com/mcp - ✓ Connected",
    "deepsql: deepsql mcp - ✓ Connected",
    "",
  ].join("\n");
  const spawnFn = fakeSpawn([{ match: { args: ["mcp", "list"] }, result: { status: 0, stdout } }]);
  assert.equal(isAlreadyConfiguredViaCli(EDITORS["claude-code"].cli, { spawnFn }), true);
});

test("isAlreadyConfiguredViaCli also matches a failed-to-connect deepsql entry (the bug we hit)", () => {
  // The user's actual diagnostic showed this exact form. The detector
  // must still classify it as "already configured" so --force can clean
  // it up before re-adding.
  const stdout = "deepsql: node /old/stale/path/server.js - ✗ Failed to connect\n";
  const spawnFn = fakeSpawn([{ match: { args: ["mcp", "list"] }, result: { status: 0, stdout } }]);
  assert.equal(isAlreadyConfiguredViaCli(EDITORS["claude-code"].cli, { spawnFn }), true);
});

test("isAlreadyConfiguredViaCli returns false when deepsql isn't in the list", () => {
  const stdout = "claude.ai Gmail: ... - ✓ Connected\n";
  const spawnFn = fakeSpawn([{ match: { args: ["mcp", "list"] }, result: { status: 0, stdout } }]);
  assert.equal(isAlreadyConfiguredViaCli(EDITORS["claude-code"].cli, { spawnFn }), false);
});

test("installViaCli runs `claude mcp add --scope user` when not already present", () => {
  const calls = [];
  const spawnFn = (binary, args) => {
    calls.push({ binary, args });
    if (args[0] === "mcp" && args[1] === "list") return { status: 0, stdout: "" };
    if (args[0] === "mcp" && args[1] === "add") return { status: 0, stdout: "Added MCP server deepsql" };
    throw new Error(`unexpected: ${args.join(" ")}`);
  };
  const result = installViaCli({ cli: EDITORS["claude-code"].cli, force: false }, { spawnFn });
  assert.equal(result.written, true);
  assert.equal(result.viaCli, "claude");
  // First call: list (the isAlreadyConfigured check). Second: add.
  assert.equal(calls.length, 2);
  assert.deepEqual(calls[1].args, ["mcp", "add", "--scope", "user", "deepsql", "deepsql", "mcp"]);
});

test("installViaCli skips when deepsql is already configured and --force is off", () => {
  const calls = [];
  const spawnFn = (binary, args) => {
    calls.push(args);
    return { status: 0, stdout: "deepsql: deepsql mcp - ✓ Connected\n" };
  };
  const result = installViaCli({ cli: EDITORS["claude-code"].cli, force: false }, { spawnFn });
  assert.equal(result.skipped, true);
  // We should not have attempted `mcp add` after detecting the entry.
  assert.equal(calls.some((a) => a[0] === "mcp" && a[1] === "add"), false);
});

test("installViaCli with --force removes the existing entry then re-adds", () => {
  const calls = [];
  const spawnFn = (binary, args) => {
    calls.push(args);
    if (args[0] === "mcp" && args[1] === "list") return { status: 0, stdout: "deepsql: foo - ✗ Failed\n" };
    if (args[0] === "mcp" && args[1] === "remove") return { status: 0 };
    if (args[0] === "mcp" && args[1] === "add") return { status: 0 };
    throw new Error(`unexpected: ${args.join(" ")}`);
  };
  installViaCli({ cli: EDITORS["claude-code"].cli, force: true }, { spawnFn });
  // Sequence: list (skip check) → list (force check) → remove → add.
  const ops = calls.map((a) => `${a[0]} ${a[1]}`);
  assert.ok(ops.includes("mcp remove"), `expected mcp remove, got: ${ops.join(", ")}`);
  assert.ok(ops.includes("mcp add"));
  // Remove must come before add.
  assert.ok(ops.indexOf("mcp remove") < ops.indexOf("mcp add"));
});

test("installViaCli throws a clean error when `claude mcp add` exits non-zero (surfaces stderr)", () => {
  const spawnFn = (binary, args) => {
    if (args[1] === "list") return { status: 0, stdout: "" };
    if (args[1] === "add") return { status: 1, stderr: "permission denied: ~/.claude.json" };
    throw new Error("unexpected");
  };
  assert.throws(
    () => installViaCli({ cli: EDITORS["claude-code"].cli, force: false }, { spawnFn }),
    /exited with status 1.*permission denied/,
  );
});

// ─── skill body + per-editor metadata ──────────────────────────────────────

test("loadSkillBody returns the bundled SKILL_BODY.md content", () => {
  const body = loadSkillBody();
  assert.ok(body.length > 500, "skill body should not be empty");
  assert.match(body, /DBA consult/i, "body should mention the DBA consult framing");
  assert.match(body, /get_brain_context/, "body should reference the brain-context tool call");
  assert.match(body, /confirmMutation/, "body should reference the mutation confirm flow");
});

test("each supported editor either has a skill descriptor or explicitly opts out", () => {
  for (const [name, editor] of Object.entries(EDITORS)) {
    if (editor.skill === null) {
      // Explicit opt-out (claude-desktop today).
      assert.equal(name, "claude-desktop", `unexpected null skill on ${name}`);
      continue;
    }
    assert.ok(editor.skill, `${name} needs a skill descriptor or explicit null`);
    assert.match(editor.skill.kind, /^(file|agents-append)$/);
    assert.ok(typeof editor.skill.path === "function");
    assert.ok(typeof editor.skill.frontmatter === "function");
  }
});

test("renderSkill prepends Claude-Code-style YAML frontmatter naming the skill", () => {
  const text = renderSkill(EDITORS["claude-code"].skill);
  assert.match(text, /^---\nname: deepsql\n/);
  assert.match(text, /description:.*DBA consult/i);
  assert.match(text, /\n---\n\n#/, "frontmatter must be terminated before the body");
});

test("renderSkill prepends Cursor-style frontmatter with globs and alwaysApply=false", () => {
  const text = renderSkill(EDITORS["cursor"].skill);
  assert.match(text, /^---\n/);
  assert.match(text, /alwaysApply: false/);
  assert.match(text, /globs:/);
  assert.match(text, /migrations/);
  assert.match(text, /\.prisma/);
});

test("renderSkill for codex emits the bare body (the AGENTS.md wrapper adds guards)", () => {
  const text = renderSkill(EDITORS["codex"].skill);
  // No frontmatter for codex — AGENTS.md is plain markdown, the
  // upsertGuardedSection wrapper adds the BEGIN/END markers.
  assert.equal(/^---/.test(text), false);
  assert.match(text, /^# DeepSQL/);
});

// ─── installSkill (standalone-file flavor: claude-code, cursor) ────────────

test("installSkill writes Claude Code SKILL.md at the configured path", withTempDir(async (dir) => {
  const skillPath = path.join(dir, "claude", "skills", "deepsql", "SKILL.md");
  const skill = {
    kind: "file",
    path: () => skillPath,
    frontmatter: EDITORS["claude-code"].skill.frontmatter,
  };
  const r = installSkill({ skill, force: false });
  assert.equal(r.written, true);
  assert.equal(r.backupPath, null, "no backup when file didn't exist before");
  const written = fs.readFileSync(skillPath, "utf8");
  assert.match(written, /^---\nname: deepsql\n/);
  assert.match(written, /DBA consult/);
}));

test("installSkill is idempotent — second call without --force is a no-op", withTempDir(async (dir) => {
  const skillPath = path.join(dir, "SKILL.md");
  const skill = {
    kind: "file",
    path: () => skillPath,
    frontmatter: EDITORS["claude-code"].skill.frontmatter,
  };
  const r1 = installSkill({ skill, force: false });
  assert.equal(r1.written, true);
  const r2 = installSkill({ skill, force: false });
  assert.equal(r2.skipped, true, "should not rewrite identical content");
}));

test("installSkill backs up a divergent skill file before overwriting with --force", withTempDir(async (dir) => {
  const skillPath = path.join(dir, "SKILL.md");
  fs.writeFileSync(skillPath, "stale content the user wrote by hand\n");
  const skill = {
    kind: "file",
    path: () => skillPath,
    frontmatter: EDITORS["claude-code"].skill.frontmatter,
  };
  const r = installSkill({ skill, force: true });
  assert.equal(r.written, true);
  assert.ok(r.backupPath, "must back up the file when content differs");
  const backup = fs.readFileSync(r.backupPath, "utf8");
  assert.match(backup, /stale content/);
}));

test("installSkill writes Cursor .mdc with globs", withTempDir(async (dir) => {
  const rulePath = path.join(dir, "rules", "deepsql.mdc");
  const skill = {
    kind: "file",
    path: () => rulePath,
    frontmatter: EDITORS["cursor"].skill.frontmatter,
  };
  installSkill({ skill, force: false });
  const written = fs.readFileSync(rulePath, "utf8");
  assert.match(written, /alwaysApply: false/);
  assert.match(written, /migrations/);
}));

// ─── upsertGuardedSection (codex AGENTS.md flavor) ─────────────────────────

test("upsertGuardedSection appends a guarded section to a new AGENTS.md", withTempDir(async (dir) => {
  const file = path.join(dir, "AGENTS.md");
  const r = upsertGuardedSection({ filePath: file, content: "hello world", force: false });
  assert.equal(r.written, true);
  const text = fs.readFileSync(file, "utf8");
  assert.match(text, /<!-- BEGIN DEEPSQL DBA CONSULT SKILL -->/);
  assert.match(text, /<!-- END DEEPSQL DBA CONSULT SKILL -->/);
  assert.match(text, /hello world/);
}));

test("upsertGuardedSection preserves the user's existing AGENTS.md content", withTempDir(async (dir) => {
  const file = path.join(dir, "AGENTS.md");
  fs.writeFileSync(file, "# My agent instructions\n\nDon't bug me on weekends.\n");
  upsertGuardedSection({ filePath: file, content: "deepsql skill content", force: false });
  const text = fs.readFileSync(file, "utf8");
  assert.match(text, /# My agent instructions/, "user's pre-existing content must be preserved");
  assert.match(text, /Don't bug me on weekends/);
  assert.match(text, /deepsql skill content/);
}));

test("upsertGuardedSection replaces only the guarded section on re-install", withTempDir(async (dir) => {
  const file = path.join(dir, "AGENTS.md");
  fs.writeFileSync(file, "# Top of file\n\nUser stuff.\n");
  upsertGuardedSection({ filePath: file, content: "v1 content", force: false });
  upsertGuardedSection({ filePath: file, content: "v2 content", force: true });
  const text = fs.readFileSync(file, "utf8");
  assert.equal(/v1 content/.test(text), false, "old guarded content must be replaced");
  assert.match(text, /v2 content/);
  assert.match(text, /# Top of file/, "user content above the guarded section stays");
  assert.match(text, /User stuff\./);
}));

test("upsertGuardedSection re-installs the same content as a no-op (skipped)", withTempDir(async (dir) => {
  const file = path.join(dir, "AGENTS.md");
  upsertGuardedSection({ filePath: file, content: "stable content", force: false });
  const r = upsertGuardedSection({ filePath: file, content: "stable content", force: false });
  assert.equal(r.skipped, true);
}));

test("upsertGuardedSection refuses to write if a BEGIN marker exists without a matching END", withTempDir(async (dir) => {
  const file = path.join(dir, "AGENTS.md");
  fs.writeFileSync(file, "# Header\n\n<!-- BEGIN DEEPSQL DBA CONSULT SKILL -->\nhalf-edited\n");
  assert.throws(
    () => upsertGuardedSection({ filePath: file, content: "anything", force: false }),
    /half-edited state/,
  );
}));

// ─── per-editor skill_off opt-out via skill === null ───────────────────────

test("claude-desktop intentionally has no skill descriptor (no skills surface)", () => {
  assert.equal(EDITORS["claude-desktop"].skill, null);
});
