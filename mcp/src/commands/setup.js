"use strict";

/**
 * `deepsql setup` — post-install admin config wizard.
 *
 * Org name and LLM config are set at install time (env vars / install script /
 * bootstrap link), so the CLI wizard only covers what's left after that:
 *
 *   1. Optionally configure SMTP / email (PUT /admin/settings/email +
 *      POST /admin/settings/email/test).
 *   2. Optionally configure Slack — bot token, signing secret, optional app
 *      token for Socket Mode (PUT /admin/settings/slack). The backend masks
 *      existing tokens (GET only returns *Configured booleans), so leaving a
 *      token field blank means "keep current value."
 *   3. Mark setup complete (POST /setup/complete) so the web-UI first-run
 *      banner clears. Skipped if already complete or with --skip-complete.
 *
 * The wizard is idempotent — re-running picks up current values where the
 * backend exposes them and prefills the prompts.
 */

const { ApiError, request } = require("../api/client");
const { resolveSession } = require("./_session");
const ui = require("../ui/prompts");

async function run(opts, { stdout = process.stdout, stderr = process.stderr } = {}) {
  const session = resolveSession(opts);
  const log = (msg) => stderr.write(`[deepsql] ${msg}\n`);

  log("Checking setup status…");
  const status = await request(session.baseUrl, "/setup/status", { token: session.token });
  if (!status?.hasOrganizationInfo || !status?.hasLlmConfig) {
    stderr.write(
      "[deepsql] Note: organization or LLM config is not set. Those are configured at install time " +
        "(env vars, install script, or bootstrap link), not by this wizard.\n",
    );
  }

  await stepEmail(session, opts, log);
  await stepSlack(session, opts, log);

  if (!opts.skipComplete && !status?.setupComplete) {
    await request(session.baseUrl, "/setup/complete", {
      method: "POST",
      token: session.token,
      json: {},
    });
    log("Setup marked complete.");
  } else if (status?.setupComplete) {
    log("Setup was already complete.");
  }
  stdout.write("Done.\n");
}

// ─── email ─────────────────────────────────────────────────────────────────

async function stepEmail(session, opts, log) {
  if (opts.skipEmail) {
    log("Skipping SMTP setup (--skip-email).");
    return;
  }
  const want = await ui.confirm({
    message: "Configure SMTP / email now?",
    default: true,
  });
  if (!want) {
    log("Skipped SMTP.");
    return;
  }

  let current = {};
  try {
    current = await request(session.baseUrl, "/admin/settings/email", { token: session.token });
  } catch (err) {
    if (err instanceof ApiError && err.status === 403) {
      log("Cannot configure SMTP: requires ADMIN role on the calling token.");
      return;
    }
    throw err;
  }

  const host = await ui.input({ message: "SMTP host:", default: current?.host || "smtp.gmail.com" });
  const port = Number(
    await ui.input({ message: "SMTP port:", default: String(current?.port || 587), required: true }),
  );
  const username = await ui.input({ message: "SMTP username:", default: current?.username || "" });
  const password = await ui.password({ message: "SMTP password / app password:" });
  const fromAddress = await ui.input({
    message: "From address:",
    default: current?.fromAddress || username,
  });
  const fromName = await ui.input({
    message: "From name:",
    default: current?.fromName || "DeepSQL",
    required: false,
  });
  const startTls = await ui.confirm({ message: "Use STARTTLS?", default: current?.startTls ?? true });

  const body = { host, port, username, password, fromAddress, fromName, startTls };
  await request(session.baseUrl, "/admin/settings/email", {
    method: "PUT",
    token: session.token,
    json: body,
  });
  log("SMTP saved.");

  const sendTest = await ui.confirm({ message: "Send a test email now?", default: true });
  if (sendTest) {
    const recipient = await ui.input({
      message: "Test recipient (your email):",
      default: fromAddress,
      required: true,
    });
    try {
      await request(session.baseUrl, "/admin/settings/email/test", {
        method: "POST",
        token: session.token,
        json: { recipient },
      });
      log(`Test email sent to ${recipient}.`);
    } catch (err) {
      log(`Test send failed: ${err.message}. Settings saved anyway.`);
    }
  }
}

// ─── slack ─────────────────────────────────────────────────────────────────

async function stepSlack(session, opts, log) {
  if (opts.skipSlack) {
    log("Skipping Slack setup (--skip-slack).");
    return;
  }
  const want = await ui.confirm({
    message: "Configure Slack (digests + bot replies) now?",
    default: false,
  });
  if (!want) {
    log("Skipped Slack.");
    return;
  }

  let current = {};
  try {
    current = await request(session.baseUrl, "/admin/settings/slack", { token: session.token });
  } catch (err) {
    if (err instanceof ApiError && err.status === 403) {
      log("Cannot configure Slack: requires ADMIN role on the calling token.");
      return;
    }
    throw err;
  }

  const enabled = await ui.confirm({
    message: "Enable Slack integration?",
    default: current?.enabled ?? true,
  });
  const socketModeEnabled = await ui.confirm({
    message:
      "Use Socket Mode? (Easier for self-hosted — no public webhook needed; requires an App-level token.)",
    default: current?.socketModeEnabled ?? true,
  });
  const deepsqlBotUsername = await ui.input({
    message: "Bot display name in Slack:",
    default: current?.deepsqlBotUsername || "DeepSQL",
  });

  // The backend only echoes *Configured booleans for tokens, so we never have
  // the existing values to pre-fill. A blank entry means "keep current."
  const tokenHint = (label, configuredFlag) =>
    configuredFlag ? `${label} (leave blank to keep current):` : `${label}:`;

  const botToken = await ui.password({
    message: tokenHint("Bot token (xoxb-…)", current?.botTokenConfigured),
  });
  const signingSecret = await ui.password({
    message: tokenHint("Signing secret", current?.signingSecretConfigured),
  });
  const appToken = socketModeEnabled
    ? await ui.password({
        message: tokenHint("App-level token (xapp-…)", current?.appTokenConfigured),
      })
    : "";

  // Build the PUT body. Empty token strings are intentionally omitted so the
  // service treats them as "keep current"; non-blank values overwrite.
  const body = {
    enabled,
    socketModeEnabled,
    deepsqlBotUsername,
  };
  if (botToken && botToken.trim()) body.botToken = botToken.trim();
  if (signingSecret && signingSecret.trim()) body.signingSecret = signingSecret.trim();
  if (appToken && appToken.trim()) body.appToken = appToken.trim();

  // Reject the case where the user is enabling Slack for the first time but
  // gave us no tokens — saving would leave the integration broken.
  const noBot = !current?.botTokenConfigured && !body.botToken;
  const noSecret = !current?.signingSecretConfigured && !body.signingSecret;
  const noAppToken = socketModeEnabled && !current?.appTokenConfigured && !body.appToken;
  if (enabled && (noBot || noSecret || noAppToken)) {
    const missing = [
      noBot && "bot token",
      noSecret && "signing secret",
      noAppToken && "app-level token",
    ]
      .filter(Boolean)
      .join(", ");
    throw new Error(`Slack is enabled but missing: ${missing}. Aborting before save.`);
  }

  await request(session.baseUrl, "/admin/settings/slack", {
    method: "PUT",
    token: session.token,
    json: body,
  });
  log("Slack saved.");
}

module.exports = { run };
