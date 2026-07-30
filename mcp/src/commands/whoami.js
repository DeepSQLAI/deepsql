"use strict";

const { request } = require("../api/client");
const { resolveSession } = require("./_session");

async function run(opts, { stdout = process.stdout } = {}) {
  const session = resolveSession(opts);
  try {
    const me = await request(session.baseUrl, "/auth/me", { token: session.token });
    stdout.write(`Username:   ${me.username || me.email || session.profile?.username || "(unknown)"}\n`);
    if (me.role) stdout.write(`Role:       ${me.role}\n`);
    stdout.write(`URL:        ${session.baseUrl}\n`);
    if (session.profile?.tokenId) stdout.write(`Token id:   ${session.profile.tokenId}\n`);
    stdout.write(
      `Connection: ${
        session.defaultConnection
          ? session.defaultConnection
          : "(none — pin one with `deepsql connections use <name>`)"
      }\n`,
    );
  } catch (err) {
    if (err.status === 401 || err.status === 403) {
      throw new Error("Saved token is no longer valid. Run `deepsql login` again.");
    }
    throw err;
  }
}

module.exports = { run };
