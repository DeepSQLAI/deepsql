"use strict";

/**
 * `deepsql brain-context "<question>" --connection <name> [--top-k N] [--json]`
 *
 * Returns DeepSQL's retrieval context for a question — relevant tables,
 * columns, FK relationships, training docs, business rules, and embedding-
 * ranked snippets — without invoking the chat agent. Coding agents (Claude
 * Code, Cursor, Codex) feed this into their own LLM to generate SQL or prose.
 *
 *   - Without --top-k → POST /training/context/{cid}  (rich payload)
 *   - With --top-k    → GET  /training/retrieve/{cid}?q=&topK= (ranked snippets)
 */

const { request } = require("../api/client");
const { resolveSession } = require("./_session");
const { resolveConnectionId } = require("./_connections");

async function run(opts, { stdout = process.stdout } = {}) {
  const question = opts.positional.join(" ").trim();
  if (!question) {
    throw new Error(
      'Pass a question: `deepsql brain-context "which tables hold customer orders?" --connection <name>`.',
    );
  }

  const session = resolveSession(opts);
  const connectionId = await resolveConnectionId(session, opts.connection);

  const topK = opts.topK == null ? null : Number.parseInt(opts.topK, 10);
  let response;
  if (topK != null && Number.isFinite(topK)) {
    response = await request(
      session.baseUrl,
      `/training/retrieve/${encodeURIComponent(connectionId)}`,
      { token: session.token, query: { q: question, topK } },
    );
  } else {
    response = await request(
      session.baseUrl,
      `/training/context/${encodeURIComponent(connectionId)}`,
      { method: "POST", token: session.token, json: { question } },
    );
  }

  if (opts.json) {
    stdout.write(`${JSON.stringify(response, null, 2)}\n`);
    return;
  }

  // Default: print the most useful fields directly so output can be piped
  // into a coding agent. /context returns one or more of:
  //   - trainingContext (the rich, prompt-ready RAG block)
  //   - companyKnowledgeContext (workspace hints — populated even when the
  //     pipeline detects a "simple_schema_question" and skips RAG)
  //   - ragTableNames, retrievalIntent, resultCount, etc. (diagnostic)
  // /retrieve (--top-k) returns ranked snippets — fall back to JSON for that.
  const isContextPayload =
    response &&
    typeof response === "object" &&
    ("trainingContext" in response ||
      "companyKnowledgeContext" in response ||
      "skipped" in response);

  if (isContextPayload) {
    if (response.skipped) {
      stdout.write(`# (retrieval skipped: ${response.skipReason || "n/a"})\n\n`);
    }
    if (response.trainingContext) {
      stdout.write(`${response.trainingContext}\n`);
    }
    if (response.companyKnowledgeContext) {
      stdout.write(
        `${response.trainingContext ? "\n" : ""}${response.companyKnowledgeContext}\n`,
      );
    }
    if (!response.trainingContext && !response.companyKnowledgeContext) {
      stdout.write(
        "(no retrieval results — pass --top-k <n> to fetch ranked snippets, or `--json` for the full payload)\n",
      );
    }
    return;
  }
  stdout.write(`${JSON.stringify(response, null, 2)}\n`);
}

module.exports = { run };
