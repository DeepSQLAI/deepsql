# Quick Start Guide

## One-liner install

```bash
curl -fsSL https://deepsql.ai/install.sh | bash
```

This runs the complete install: clones the repo, builds the stack from source, and
starts all services. The only input needed is an LLM API key — or skip it and
configure later in Settings → AI Provider.

## What you need

- **Docker** with Compose v2 and buildx >= 0.17.0
- **~4 GB of memory** available to Docker
- **An LLM API key** (optional — can configure later)

On a fresh Ubuntu/Debian server:

```bash
curl -fsSL https://raw.githubusercontent.com/DeepSQLAI/deepsql/main/scripts/self-host/bootstrap-server.sh | sudo bash
```

## With an LLM key

```bash
DEEPSQL_CHAT_API_KEY=sk-your-key curl -fsSL https://deepsql.ai/install.sh | bash
```

## Without an LLM key (keyless start)

```bash
DEEPSQL_INITIAL_ADMIN_EMAIL=admin@example.com curl -fsSL https://deepsql.ai/install.sh | bash
```

Chat and AI features are disabled until you configure a key in Settings → AI Provider.

## After install

1. **Open** http://localhost:3000
2. **Log in** with the email and password from `~/deepsql/.env`
3. **Connect a database** (Postgres or MySQL)
4. **Start asking questions** in the Agent tab

## Verify the install

```bash
curl -fsS http://localhost:8080/api/actuator/health
```

## Options

- `--non-interactive` — never prompt (use with env vars)
- `--fresh` — remove existing volumes before install
- `--no-seed-demo` — skip demo database seeding
- `--ref v1.3.0` — install a specific version

Example:

```bash
curl -fsSL https://deepsql.ai/install.sh | bash -s -- --fresh --ref v1.3.0
```

## Ports

| Service  | Port | Override                |
|----------|------|-------------------------|
| Frontend | 3000 | `DEEPSQL_FRONTEND_PORT` |
| Backend  | 8080 | `DEEPSQL_BACKEND_PORT`  |
| Postgres | 5432 | `DEEPSQL_POSTGRES_PORT` |
| Valkey   | 6379 | `DEEPSQL_VALKEY_PORT`   |

## Troubleshooting

**Docker permission denied:**
```bash
sudo usermod -aG docker $USER
newgrp docker
```

**buildx too old:**
```bash
curl -fsSL https://raw.githubusercontent.com/DeepSQLAI/deepsql/main/scripts/self-host/bootstrap-server.sh | sudo bash
```

**Backend won't start:**
Check `docker compose logs backend` — usually a missing secret or port conflict.

## Development

For local development without Docker:

```bash
docker compose up -d postgres valkey
cd backend && ./mvnw spring-boot:run    # http://localhost:8080/api
npm install && npm run dev              # http://localhost:3000
```

Requires **JDK 25** and **Node 22**.

## Next steps

- [README.md](../README.md) — full documentation
- [docs/llms-full.txt](llms-full.txt) — AI agent setup runbook
- [mcp/README.md](../mcp/README.md) — CLI and MCP server
