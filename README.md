# DeepSQL

AI-powered Database Performance Assistant.

---

## Self-Hosted Deployment (Docker)

The full stack runs as four Docker containers: PostgreSQL (vault DB), Valkey (cache), backend (Spring Boot), and frontend (nginx).

### Prerequisites

- Docker Engine 24+ with `docker compose` v2
- `curl`
- An API key from an LLM provider. DeepSQL ships no model credentials — you bring
  your own. Any of these work:
  - **OpenAI** — an `sk-…` key
  - **Azure OpenAI** — key, endpoint, and deployment name
  - **Any OpenAI-compatible server** — vLLM, Ollama, LM Studio, TGI, via a custom endpoint

  Credentials go in `.env` and are read from the environment. They are never sent
  anywhere but the provider you point them at.

### Quick Start

```bash
# 1. Authenticate with the DeepSQL image registry
echo "<YOUR_GHCR_TOKEN>" | docker login ghcr.io -u <YOUR_GITHUB_USERNAME> --password-stdin

# 2. Clone the distribution repo (or unpack the offline bundle)
git clone https://github.com/DeepSQLAI/deepsql.git
cd deepsql

# 3. Configure
cp .env.example .env
# Edit .env — see "Configuring the LLM" below

# 4. Install (auto-generates JWT secret, encryption key, DB password)
./scripts/self-host/install.sh

# 5. Open http://localhost:3000
```

The install script will prompt for any required values that are still placeholders.

### Configuring the LLM

Chat and embeddings are configured separately, so they can use different providers,
keys, or endpoints. Set these in `.env`:

```env
DEEPSQL_CHAT_PROVIDER=openai
DEEPSQL_CHAT_API_KEY=sk-your-key-here
DEEPSQL_CHAT_ENDPOINT=https://api.openai.com/v1
DEEPSQL_CHAT_MODEL=gpt-4o

DEEPSQL_EMBEDDING_PROVIDER=openai
DEEPSQL_EMBEDDING_API_KEY=sk-your-key-here
DEEPSQL_EMBEDDING_ENDPOINT=https://api.openai.com/v1
DEEPSQL_EMBEDDING_MODEL=text-embedding-3-large
```

`openai` is the only provider id in this release, and it is the only one you need:
it speaks OpenAI, Azure OpenAI, and any OpenAI-compatible server. Point
`DEEPSQL_CHAT_ENDPOINT` at the server you want.

`DEEPSQL_CHAT_PROVIDER` gates the rest — with it unset, no other `DEEPSQL_CHAT_*`
value is read. `DEEPSQL_CHAT_ENDPOINT` has no working fallback, so set it explicitly
even for OpenAI. The embedding variables are optional in the sense that the app still
starts without them, but RAG retrieval stays keyword-only until they are set.

**Azure OpenAI** — an `.azure.com` endpoint switches to `api-key` header auth
automatically, and `MODEL` is your *deployment* name:

```env
DEEPSQL_CHAT_PROVIDER=openai
DEEPSQL_CHAT_API_KEY=your-azure-openai-key
DEEPSQL_CHAT_ENDPOINT=https://your-resource.cognitiveservices.azure.com/
DEEPSQL_CHAT_MODEL=your-deployment-name
```

**Ollama or another local server**:

```env
DEEPSQL_CHAT_PROVIDER=openai
DEEPSQL_CHAT_API_KEY=ollama
DEEPSQL_CHAT_ENDPOINT=http://host.docker.internal:11434/v1
DEEPSQL_CHAT_MODEL=llama3.1
```

Environment variables are the supported way to configure this. The onboarding wizard
in the UI writes an older set of config keys that the resolver does not read, so it
does not currently configure the LLM — use `.env`.

`.env` is the only tier above the environment: values may also be stored in the
vault database under `llm.<role>.provider` and `llm.<role>.<provider>.<field>`, which
take precedence, but nothing writes those rows for you today.

### First-Run Admin Bootstrap (optional)

To have `install.sh` create the first admin account automatically, add to `.env` before running:

```env
SECURITY_ADMIN_BOOTSTRAP_ENABLED=true
ADMIN_BOOTSTRAP_SECRET=choose-a-one-time-secret
DEEPSQL_INITIAL_ADMIN_EMAIL=admin@yourcompany.com
DEEPSQL_INITIAL_ADMIN_PASSWORD=choose-a-strong-password
```

After first login, set `SECURITY_ADMIN_BOOTSTRAP_ENABLED=false` and restart:

```bash
docker compose up -d backend
```

### Useful Commands

```bash
./scripts/self-host/status.sh          # Health check all services
./scripts/self-host/smoke-test.sh      # Post-install validation
./scripts/self-host/uninstall.sh       # Stop and remove containers (data preserved)
./scripts/self-host/uninstall.sh --purge-data   # Remove containers AND volumes
```

### Default Ports

| Service  | Port |
|----------|------|
| Frontend | 3000 |
| Backend  | 8080 |
| Postgres | 5432 |
| Valkey   | 6379 |

Override any port in `.env` (e.g. `DEEPSQL_FRONTEND_PORT=13000`).

---

## Documentation

- **Self-host guide**: [`docs/root/SELF_HOST_GUIDE.md`](docs/root/SELF_HOST_GUIDE.md)
- **MCP server**: [`docs/root/MCP_PHASE1.md`](docs/root/MCP_PHASE1.md)
- **Architecture & dev guide**: [`docs/root/CLAUDE.md`](docs/root/CLAUDE.md)
- **Full docs index**: [`docs/README.md`](docs/README.md)

---

## Development

```bash
# Backend (auth disabled in dev mode)
cd backend && mvn spring-boot:run

# Frontend
npm install && npm run dev
```

Visit http://localhost:3000 — login with `admin` / `admin` in dev mode.
