#!/usr/bin/env node
"use strict";

// Postinstall: intentionally a no-op heavy-wise.
//
// The DeepSQL Agent is now a THIN client — `deepsql` / `deepsql agent` talk to
// the server-side agent over the backend's brokered /agent/chat endpoint, so
// there is NO local agent runtime to download. `npm install -g @deepsql/mcp` is
// fast and quiet; nothing to pre-build.
//
// We only print a short next-steps hint on a global install (and stay silent for
// CI / local dependency installs so we never clutter other projects' logs).

try {
  if (process.env.CI) process.exit(0);
  const isGlobal = String(process.env.npm_config_global || "") === "true";
  if (!isGlobal) process.exit(0);

  process.stdout.write(
    "\nDeepSQL installed. Next:\n" +
    "  deepsql login --url <your-deepsql-url>\n" +
    "  deepsql                      # chat with the DeepSQL Agent (connects to your server)\n\n"
  );
} catch {
  // Never let a cosmetic hint fail the install.
}
process.exit(0);
