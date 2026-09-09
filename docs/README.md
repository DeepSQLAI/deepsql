# DeepSQL Documentation

Technical documentation for DeepSQL. Start with the root [`README.md`](../README.md)
for a project overview and the fastest path to a running instance.

## Setup & Getting Started

- **[QUICKSTART.md](./QUICKSTART.md)** — Quick start guide to get up and running
- **[SETUP.md](./SETUP.md)** — Complete installation and configuration guide
- **[root/SELF_HOST_GUIDE.md](./root/SELF_HOST_GUIDE.md)** — Production self-hosting operations: sizing, environment reference, first-run access, TLS, upgrades, backups
- **[RAG_SETUP.md](./RAG_SETUP.md)** — Configure RAG for natural language SQL generation
- **[RAG_TRAINING.md](./RAG_TRAINING.md)** — Training the retrieval layer on your schema
- **[AZURE-AI-SEARCH.md](./AZURE-AI-SEARCH.md)** — Optional Azure AI Search vector backend

## Architecture & Design

- **[DESIGN.md](./DESIGN.md)** — Product and system design
- **[SERVICES.md](./SERVICES.md)** — Backend service map
- **[BRAIN-design.md](./BRAIN-design.md)** — Schema-intelligence design notes
- **[root/CLAUDE.md](./root/CLAUDE.md)** — Full architecture and development guide
- **[root/AGENTS.md](./root/AGENTS.md)** — High-level codebase map for AI agents
- **[API_CLIENT_USAGE.md](./API_CLIENT_USAGE.md)** — Centralized frontend API client

## Chat, Memory & Guardrails

- **[CHAT_MEMORY_AND_GUARDRAILS.md](./CHAT_MEMORY_AND_GUARDRAILS.md)** — How chat memory, RAG, and SQL guardrails fit together
- **[TOOL_AWARE_CHAT_MEMORY.md](./TOOL_AWARE_CHAT_MEMORY.md)** — Spring AI JDBC chat memory behavior and troubleshooting

## CLI & MCP

- **[public/cli-and-mcp.md](./public/cli-and-mcp.md)** — The `deepsql` CLI and MCP server
- **[root/MCP_PHASE1.md](./root/MCP_PHASE1.md)** — MCP server tools, configuration, and env vars

## Feature Documentation

- **[DATABASE_ADVISOR.md](./DATABASE_ADVISOR.md)** — AI-powered database optimization advisor
- **[DATABASE_ADVISOR_UI.md](./DATABASE_ADVISOR_UI.md)** — Advisor UI implementation details
- **[EXPLAIN_HISTORY_FEATURE.md](./EXPLAIN_HISTORY_FEATURE.md)** — EXPLAIN plan history tracking
- **[PERFORMANCE_UI_IMPLEMENTATION.md](./PERFORMANCE_UI_IMPLEMENTATION.md)** — Performance dashboard implementation
- **[PERFORMANCE_UI_BUGFIXES.md](./PERFORMANCE_UI_BUGFIXES.md)** — Performance UI fixes and improvements
- **[RBAC_USAGE_GUIDE.md](./RBAC_USAGE_GUIDE.md)** — Roles, permissions, and access control
- **[GOOGLE_SSO_SETUP.md](./GOOGLE_SSO_SETUP.md)** — Google Workspace SSO and password-login control

## Operations

- **[LOGGING-GUIDE.md](./LOGGING-GUIDE.md)** — Log format and what to look for

## Design Notes & Plans

Historical design documents. These record intent at a point in time and may lag
the code.

- **[BRAIN_2.0_IMPLEMENTATION_PLAN.md](./BRAIN_2.0_IMPLEMENTATION_PLAN.md)**
- **[BI_DASHBOARDS_PLAN.md](./BI_DASHBOARDS_PLAN.md)**
- **[PERFORMANCE_FEATURES_PLAN.md](./PERFORMANCE_FEATURES_PLAN.md)**
- **[SPRING_AI_MIGRATION_PLAN.md](./SPRING_AI_MIGRATION_PLAN.md)**

## Contributing

When adding new documentation:

1. Use clear, descriptive filenames in UPPERCASE with underscores
2. Include a brief summary at the top of each document
3. Update this index
4. Link to related documents where appropriate
