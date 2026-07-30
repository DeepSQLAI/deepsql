"use strict";

const { request } = require("../api/client");
const { resolveSession } = require("./_session");
const { resolveConnectionId } = require("./_connections");

async function run(opts, { stdout = process.stdout } = {}) {
  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, opts.connection);
  const sub = opts.positional[0] || "tables";
  const path =
    sub === "objects"
      ? `/connections/${encodeURIComponent(connectionId)}/objects`
      : `/connections/${encodeURIComponent(connectionId)}/schema`;
  const response = await request(session.baseUrl, path, { token: session.token });
  stdout.write(`${JSON.stringify(response, null, 2)}\n`);
}

module.exports = { run };
