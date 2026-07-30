"use strict";

// Branded one-time intro for the interactive DeepSQL Agent REPL.
//
// Compact by design (the REPL is a scrolling buffer, not a full-screen TUI):
// wordmark + tagline + a rotating sample prompt per category + how to drive it.
//
// Colors use 256-color codes (\x1b[38;5;Nm) — macOS Terminal.app does NOT
// support 24-bit truecolor and garbles \x1b[38;2;r;g;bm into wrong colors, so
// we stick to the 256 palette which renders correctly everywhere. Body text is
// left at the terminal's default foreground so it's readable on light AND dark
// backgrounds. Suppress the whole banner with DEEPSQL_NO_BANNER=1.

const https = require("node:https");

// Installed package version (this file ships inside the package).
let PKG_VERSION = "";
try {
  PKG_VERSION = require("../../package.json").version || "";
} catch {
  /* ignore */
}

// Solid-block "DEEPSQL" wordmark.
const LOGO = [
  "████   ████   ████   ████    ████    ███    █     ",
  "█   █  █      █      █   █   █       █   █   █     ",
  "█   █  ███    ███    ████     ███    █   █   █     ",
  "█   █  █      █      █            █  █  ██   █     ",
  "████   ████   ████   █        ████    ███   █████ ",
];

// Welcoming purple palette (256-color cube). Light→deep down the wordmark; one
// clean hue, no grey, so it reads as a single wordmark.
const PURPLE_LIGHT = 141; // #af87ff
const PURPLE = 99; //        #875fff
const PURPLE_DEEP = 98; //   #875fd7
const AMBER = 178; //        #d7af00 — "needs attention" marker, readable on light & dark
const LOGO_ROWS = [PURPLE_LIGHT, PURPLE_LIGHT, PURPLE, PURPLE, PURPLE_DEEP];

function c(n, s, on) {
  return on ? `\x1b[38;5;${n}m${s}\x1b[0m` : s;
}
function dim(s, on) {
  return on ? `\x1b[2m${s}\x1b[0m` : s;
}

// Several sample prompts per category; one is picked at random each launch.
const PROMPTS = {
  DBA: [
    "why is the orders query slow?",
    "what indexes should I add?",
    "is this query using the right index?",
    "what's my slowest query today?",
  ],
  BI: [
    "revenue by city this month",
    "how many bookings last week?",
    "top 10 customers by spend",
    "signups per day this month",
  ],
  Guardian: [
    "is this migration safe?",
    "who’s driving DB load?",
    "any tables bloating?",
    "what schema changed recently?",
  ],
};

function pick(arr) {
  return arr[Math.floor(Math.random() * arr.length)];
}

// Prompt label for the readline input line (purple, brand-forward).
function promptLabel(useColor) {
  return "\n" + c(PURPLE, "deepsql ›", useColor) + " ";
}

function renderIntro(
  stdout,
  {
    useColor = true,
    connectionLabel = null,
    versionLine = null,
    connections = [],
    suggestions = [],
    recommendationCount = 0,
  } = {}
) {
  const out = [""];
  LOGO.forEach((row, i) => out.push("  " + c(LOGO_ROWS[i], row, useColor)));
  out.push("");
  out.push("  " + dim("I am your DBA and Data agent. DeepSQL is the brain for your database", useColor));
  out.push("");
  out.push("  " + c(PURPLE, "Try these prompts", useColor));
  for (const cat of ["DBA", "BI", "Guardian"]) {
    // Label reads "<cat> prompt" (not a bare "DBA" that looks like a command);
    // purple label, prompt text stays default-fg so it's always readable.
    const label = `${cat} prompt`;
    out.push("    " + c(PURPLE_LIGHT, label.padEnd(16), useColor) + `"${pick(PROMPTS[cat])}"`);
  }

  // Connections the token can see (frictionless onboarding: guide to add one if none).
  out.push("");
  if (connections.length === 0) {
    out.push("  " + c(PURPLE, "No databases connected yet", useColor));
    out.push("    " + dim("→ ", useColor) + "deepsql connections add");
  } else {
    out.push("  " + c(PURPLE, `Connections (${connections.length})`, useColor));
    const width = Math.min(24, Math.max(...connections.map((x) => x.name.length)));
    for (const conn of connections.slice(0, 8)) {
      out.push("    " + conn.name.padEnd(width + 2) + dim(conn.dbType, useColor));
    }
    if (connections.length > 8) out.push("    " + dim(`… +${connections.length - 8} more`, useColor));
  }

  // Admin "needs attention" — config suggestions for connections the user manages.
  if (suggestions.length) {
    out.push("");
    out.push("  " + c(AMBER, "Needs attention", useColor));
    for (const s of suggestions.slice(0, 6)) {
      out.push(
        "    " + c(AMBER, "⚠ ", useColor) + s.conn + dim(" · ", useColor) + s.text
      );
      if (s.fix) out.push("        " + dim("→ ", useColor) + dim(s.fix, useColor));
    }
    if (suggestions.length > 6) out.push("    " + dim(`… +${suggestions.length - 6} more`, useColor));
  }

  // Brain learning loop: prompt admins to review what DeepSQL wants to remember.
  if (recommendationCount > 0) {
    out.push("");
    out.push(
      "  " +
        c(PURPLE, `${recommendationCount} brain recommendation${recommendationCount === 1 ? "" : "s"} to review`, useColor) +
        dim("  → deepsql brain recs", useColor)
    );
  }

  out.push("");
  if (connectionLabel) {
    out.push("  " + dim("Grounded on ", useColor) + connectionLabel);
  }
  out.push(
    "  " +
      dim("Ask a question · ", useColor) +
      "exit" +
      dim(" to quit · ", useColor) +
      "deepsql --help" +
      dim(" for CLI commands", useColor)
  );
  if (versionLine) out.push("  " + versionLine);
  out.push("");
  stdout.write(out.join("\n") + "\n");
}

function compareSemver(a, b) {
  const pa = String(a).split(".").map((n) => parseInt(n, 10) || 0);
  const pb = String(b).split(".").map((n) => parseInt(n, 10) || 0);
  for (let i = 0; i < 3; i++) {
    if ((pa[i] || 0) > (pb[i] || 0)) return 1;
    if ((pa[i] || 0) < (pb[i] || 0)) return -1;
  }
  return 0;
}

// Best-effort latest-version lookup from the npm registry, with a tight timeout
// so it never noticeably delays startup. Resolves null on any failure.
function fetchLatest(timeoutMs = 1200) {
  return new Promise((resolve) => {
    let done = false;
    const finish = (v) => {
      if (!done) {
        done = true;
        resolve(v);
      }
    };
    try {
      const req = https.get(
        "https://registry.npmjs.org/@deepsql/mcp/latest",
        { timeout: timeoutMs, headers: { accept: "application/json" } },
        (res) => {
          if (res.statusCode !== 200) {
            res.resume();
            return finish(null);
          }
          let d = "";
          res.on("data", (chunk) => (d += chunk));
          res.on("end", () => {
            try {
              finish(JSON.parse(d).version || null);
            } catch {
              finish(null);
            }
          });
        }
      );
      req.on("timeout", () => {
        req.destroy();
        finish(null);
      });
      req.on("error", () => finish(null));
    } catch {
      finish(null);
    }
  });
}

// "v0.22.0 · latest" / "v0.22.0 · update available → v0.23.0  npm i -g …".
// Falls back to a plain dim version when the registry can't be reached.
async function getVersionLine(useColor) {
  const cur = PKG_VERSION;
  if (!cur) return null;
  const latest = await fetchLatest();
  if (!latest) return dim(`v${cur}`, useColor);
  if (compareSemver(latest, cur) > 0) {
    return (
      c(PURPLE_LIGHT, `v${cur}`, useColor) +
      dim(" · update available → ", useColor) +
      c(PURPLE, `v${latest}`, useColor) +
      dim("   npm i -g @deepsql/mcp@latest", useColor)
    );
  }
  return dim(`v${cur} · latest`, useColor);
}

module.exports = { renderIntro, getVersionLine, promptLabel };
