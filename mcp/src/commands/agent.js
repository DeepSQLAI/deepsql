"use strict";

// `deepsql agent` (and bare `deepsql` in a terminal): the DeepSQL Agent, as a
// THIN client. It does not run an agent runtime locally — it talks to the
// server-side agent through the backend's brokered, authenticated
// `/agent/chat` endpoint (the same agent that powers the web Agent tab and
// Slack). Identity, profile, connection scope, and conversation history are all
// resolved server-side from your saved `deepsql login` — nothing heavy installs,
// and your terminal shares the same conversations as the web app.
//
//   deepsql agent "how many active hotels?"   one-shot
//   deepsql                                    interactive chat (resumes latest)

const readline = require("node:readline");
const { request } = require("../api/client");
const { resolveSession } = require("./_session");
const { resolveConnectionId } = require("./_connections");
const { renderIntro, getVersionLine, promptLabel } = require("./_agent_intro");
const { loadIntroData } = require("./_agent_status");

// The brokered turn can run a full multi-tool agent loop server-side; allow well
// past the backend's own per-turn ceiling so we don't abandon a valid answer.
const TURN_TIMEOUT_MS = 320000;

async function runTurn(session, connectionId, message, conversationId) {
  return request(session.baseUrl, "/agent/chat", {
    method: "POST",
    token: session.token,
    json: { message, connectionId, conversationId },
    timeoutMs: TURN_TIMEOUT_MS,
  });
}

// A server-side turn can run a multi-tool agent loop for a minute or more with
// no streamed output, so show a live spinner + elapsed timer instead of a
// static "…thinking" that reads as frozen. No-op (returns the promise) when not
// a TTY, so piped/one-shot output stays clean.
function awaitWithSpinner(stdout, promise) {
  if (!stdout.isTTY) return promise;
  const frames = ["⠋", "⠙", "⠹", "⠸", "⠼", "⠴", "⠦", "⠧", "⠇", "⠏"];
  const start = Date.now();
  let i = 0;
  const tick = () => {
    const s = Math.floor((Date.now() - start) / 1000);
    stdout.write(`\r\x1b[2m${frames[i++ % frames.length]} thinking… ${s}s\x1b[0m\x1b[K`);
  };
  tick();
  const timer = setInterval(tick, 120);
  const clear = () => {
    clearInterval(timer);
    stdout.write("\r\x1b[K");
  };
  return promise.then(
    (v) => { clear(); return v; },
    (e) => { clear(); throw e; }
  );
}

function renderReply(stdout, resp) {
  if (resp && resp.ok && resp.answer && String(resp.answer).trim()) {
    stdout.write(`\n${String(resp.answer).trim()}\n`);
  } else {
    stdout.write(`\n⚠ ${(resp && resp.error) || "the agent run ended early"}\n`);
  }
}

async function run(opts, io = {}) {
  const stdout = io.stdout || process.stdout;
  const stderr = io.stderr || process.stderr;

  let session;
  try {
    session = resolveSession(opts);
  } catch (e) {
    stderr.write(`${e.message}\n`);
    return 1;
  }

  // Connection is optional — the agent can chat without one and will ask if a
  // question needs a database. Resolve the active default when present.
  let connectionId = null;
  try {
    connectionId = await resolveConnectionId(session, opts.connection);
  } catch {
    /* no default connection set — fine */
  }

  const message = (opts.positional || []).join(" ").trim();

  // One-shot: a question on the command line.
  if (message) {
    try {
      const resp = await awaitWithSpinner(stdout, runTurn(session, connectionId, message, null));
      renderReply(stdout, resp);
      return resp && resp.ok ? 0 : 1;
    } catch (e) {
      stderr.write(`Agent request failed: ${e.message}\n`);
      return 1;
    }
  }

  // Interactive: a line REPL against the server agent. Requires a TTY.
  if (!stdout.isTTY) {
    stderr.write('Usage: deepsql agent "<question>"   (or run in a terminal for interactive chat)\n');
    return 2;
  }

  // Branded one-time intro (compact). Suppress with DEEPSQL_NO_BANNER=1; colors
  // honor --no-color / NO_COLOR (this branch is already TTY-only).
  const useColor = !opts.noColor && !process.env.NO_COLOR;
  if (process.env.DEEPSQL_NO_BANNER) {
    stdout.write("DeepSQL Agent — connected to your server agent. Type a question; `exit` or Ctrl-C to quit.\n");
  } else {
    // Only surface a connection when one was explicitly named; a default/absent
    // connection shows nothing (the agent asks if a question needs one).
    const connectionLabel = opts.connection || null;
    // Version check + connections/suggestions run in parallel (both best-effort,
    // tight timeouts) so startup waits on the slower one, not the sum.
    const [versionLine, introData] = await Promise.all([
      getVersionLine(useColor),
      loadIntroData(session),
    ]);
    renderIntro(stdout, {
      useColor,
      connectionLabel,
      versionLine,
      connections: introData.connections,
      suggestions: introData.suggestions,
      recommendationCount: introData.recommendationCount,
    });
  }
  // conversationId stays null on the first turn so the server resumes your most
  // recent conversation for this connection (shared with the web Agent tab);
  // subsequent turns reuse the id it returns.
  let conversationId = null;
  const rl = readline.createInterface({ input: process.stdin, output: stdout, prompt: promptLabel(useColor) });
  rl.prompt();

  return await new Promise((resolve) => {
    rl.on("line", async (line) => {
      const text = line.trim();
      if (!text) {
        rl.prompt();
        return;
      }
      if (text === "exit" || text === "quit") {
        rl.close();
        return;
      }
      rl.pause();
      try {
        const resp = await awaitWithSpinner(stdout, runTurn(session, connectionId, text, conversationId));
        if (resp && resp.conversationId) conversationId = resp.conversationId;
        renderReply(stdout, resp);
      } catch (e) {
        stdout.write(`\n⚠ ${e.message}\n`);
      }
      rl.resume();
      rl.prompt();
    });
    rl.on("close", () => {
      stdout.write("\nBye.\n");
      resolve(0);
    });
  });
}

module.exports = { run };
