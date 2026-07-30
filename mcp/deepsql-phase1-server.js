#!/usr/bin/env node

// DeepSQL Phase 1 MCP Server — stdio transport for read-only database access

const {
  TOOL_DEFINITIONS,
  DeepSqlApiError,
  buildToolError,
  createConfigFromEnv,
  handleToolCall,
} = require("./deepsql-phase1-lib");

const SERVER_INFO = {
  name: "deepsql-phase1",
  version: "0.1.0",
};

class DeepSqlPhase1McpServer {
  constructor(config) {
    this.config = config;
    this.inputBuffer = Buffer.alloc(0);
    this.shuttingDown = false;
    // "auto" until we observe the client's framing. The MCP spec mandates
    // newline-delimited JSON-RPC over stdio (Claude Code, codex-cli, etc.),
    // but older clients (legacy Claude Desktop, our own E2E driver) used
    // LSP-style Content-Length framing. Detect on first frame and respond
    // in the same format.
    this.framing = "auto";
  }

  start() {
    process.stdin.on("data", (chunk) => {
      this.inputBuffer = Buffer.concat([this.inputBuffer, chunk]);
      this.processInputBuffer();
    });

    process.stdin.on("end", () => {
      process.exit(0);
    });

    process.stdin.resume();
    this.log(
      `DeepSQL phase 1 MCP server started. Base URL=${this.config.baseUrl}, timeout=${this.config.timeoutMs}ms`,
    );
  }

  log(message) {
    process.stderr.write(`[deepsql-phase1] ${message}\n`);
  }

  detectFraming() {
    // Skip leading whitespace to find the first meaningful byte.
    let i = 0;
    while (i < this.inputBuffer.length) {
      const b = this.inputBuffer[i];
      if (b !== 0x20 && b !== 0x09 && b !== 0x0a && b !== 0x0d) break;
      i += 1;
    }
    if (i >= this.inputBuffer.length) return false;
    // A leading `{` means newline-delimited JSON-RPC (the MCP spec format).
    // Anything else (e.g. `C` from "Content-Length") is LSP framing.
    this.framing = this.inputBuffer[i] === 0x7b ? "ndjson" : "lsp";
    this.log(`Detected client framing: ${this.framing}`);
    return true;
  }

  processInputBuffer() {
    if (this.framing === "auto" && !this.detectFraming()) return;
    if (this.framing === "ndjson") return this.processNdjsonBuffer();
    return this.processLspBuffer();
  }

  processNdjsonBuffer() {
    while (true) {
      const nl = this.inputBuffer.indexOf(0x0a);
      if (nl < 0) return;
      const lineBuf = this.inputBuffer.slice(0, nl);
      this.inputBuffer = this.inputBuffer.slice(nl + 1);
      const line = lineBuf.toString("utf8").replace(/\r$/, "").trim();
      if (!line) continue;
      let message;
      try {
        message = JSON.parse(line);
      } catch (error) {
        this.log(`Failed to parse incoming JSON: ${error.message}`);
        continue;
      }
      void this.handleMessage(message);
    }
  }

  processLspBuffer() {
    while (true) {
      const crlfIndex = this.inputBuffer.indexOf("\r\n\r\n");
      const lfIndex = this.inputBuffer.indexOf("\n\n");
      let headerEnd = -1;
      let separatorLength = 0;

      if (crlfIndex >= 0 && (lfIndex < 0 || crlfIndex < lfIndex)) {
        headerEnd = crlfIndex;
        separatorLength = 4;
      } else if (lfIndex >= 0) {
        headerEnd = lfIndex;
        separatorLength = 2;
      }

      if (headerEnd < 0) {
        return;
      }

      const headerText = this.inputBuffer.slice(0, headerEnd).toString("utf8");
      const lengthMatch = headerText.match(/Content-Length:\s*(\d+)/i);
      if (!lengthMatch) {
        this.log("Received frame without Content-Length header; discarding.");
        this.inputBuffer = this.inputBuffer.slice(headerEnd + separatorLength);
        continue;
      }

      const contentLength = Number.parseInt(lengthMatch[1], 10);
      const totalLength = headerEnd + separatorLength + contentLength;
      if (this.inputBuffer.length < totalLength) {
        return;
      }

      const bodyBuffer = this.inputBuffer.slice(
        headerEnd + separatorLength,
        totalLength,
      );
      this.inputBuffer = this.inputBuffer.slice(totalLength);

      let message;
      try {
        message = JSON.parse(bodyBuffer.toString("utf8"));
      } catch (error) {
        this.log(`Failed to parse incoming JSON: ${error.message}`);
        continue;
      }

      void this.handleMessage(message);
    }
  }

  send(payload) {
    const body = JSON.stringify(payload);
    if (this.framing === "lsp") {
      const header = `Content-Length: ${Buffer.byteLength(body, "utf8")}\r\n\r\n`;
      process.stdout.write(header + body);
    } else {
      // Default to newline-delimited JSON-RPC (MCP spec). Used when the
      // framing is still "auto" (e.g. server-initiated message before any
      // client frame, which Phase 1 doesn't actually do).
      process.stdout.write(body + "\n");
    }
  }

  sendResult(id, result) {
    this.send({
      jsonrpc: "2.0",
      id,
      result,
    });
  }

  sendError(id, code, message, data) {
    this.send({
      jsonrpc: "2.0",
      id,
      error: {
        code,
        message,
        ...(data === undefined ? {} : { data }),
      },
    });
  }

  async handleMessage(message) {
    const { id, method, params } = message || {};

    if (!method) {
      if (id !== undefined) {
        this.sendError(id, -32600, "Invalid request");
      }
      return;
    }

    try {
      switch (method) {
        case "initialize": {
          this.sendResult(id, {
            protocolVersion: params?.protocolVersion || "2025-03-26",
            capabilities: {
              tools: {},
            },
            serverInfo: SERVER_INFO,
            instructions:
              "DeepSQL MCP exposes the user's database catalogs plus DeepSQL's retrieval brain (relevant tables/columns/FKs, business rules, inferred relationships, anti-patterns, slow-query analysis). Workflow: call list_connections first to get UUIDs; call get_brain_context with the user's question to ground generation in retrieved schema; call execute_sql to run the query (admins can run DDL/DML with a two-step confirmMutation flow, developers are server-enforced read-only); call analyze_query_plan for AI-enriched plan analysis with the connection's schema + business rules in scope. EXPLAIN and EXPLAIN ANALYZE are valid SQL — pass them as the query to execute_sql; use analyze_query_plan when you want the LLM-written summary, not raw plan rows. Always pass connectionId (UUID), not connection names.",
          });
          return;
        }

        case "notifications/initialized":
          return;

        case "ping":
          this.sendResult(id, {});
          return;

        case "shutdown":
          this.shuttingDown = true;
          this.sendResult(id, {});
          return;

        case "exit":
          process.exit(this.shuttingDown ? 0 : 1);
          return;

        case "tools/list":
          this.sendResult(id, {
            tools: TOOL_DEFINITIONS,
          });
          return;

        case "resources/list":
          this.sendResult(id, {
            resources: [],
          });
          return;

        case "prompts/list":
          this.sendResult(id, {
            prompts: [],
          });
          return;

        case "tools/call": {
          const toolName = params?.name;
          const toolArgs = params?.arguments || {};

          if (!toolName) {
            this.sendResult(id, buildToolError("Tool name is required."));
            return;
          }

          const result = await handleToolCall(this.config, toolName, toolArgs);
          this.sendResult(id, result);
          return;
        }

        default:
          this.sendError(id, -32601, `Method not found: ${method}`);
      }
    } catch (error) {
      if (error instanceof DeepSqlApiError) {
        this.sendResult(
          id,
          buildToolError(`DeepSQL API error (${error.status || "unknown"}): ${error.message}`, {
            status: error.status,
            payload: error.payload,
          }),
        );
        return;
      }

      this.log(error?.stack || error?.message || String(error));
      if (id !== undefined) {
        this.sendError(id, -32000, error?.message || "Internal server error");
      }
    }
  }
}

if (require.main === module) {
  const server = new DeepSqlPhase1McpServer(createConfigFromEnv());
  server.start();
}

module.exports = {
  DeepSqlPhase1McpServer,
  SERVER_INFO,
};
