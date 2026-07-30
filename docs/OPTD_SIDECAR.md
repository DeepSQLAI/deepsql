# OPTD Sidecar Integration (DBA Agent)

This document explains how the optd sidecar is wired into DBA Agent, what it does, and how to run it locally and in production.

## Purpose

The optd sidecar provides **fast, offline plan estimation** (plan text, signature, estimated cost, estimated rows) for
candidate query rewrites. DBA Agent uses these signals to score and compare candidates without running them.

## Source & Repository Layout

| Repo | URL | Description |
|------|-----|-------------|
| **dba-agent** | (this repo) | Contains `optd-sidecar/` source |
| **optd (private mirror)** | `git@github.com:geekypunk/optd.git` | Private mirror of `cmu-db/optd` with DBA Agent extensions |
| **optd (upstream)** | `https://github.com/cmu-db/optd` | Public upstream — read-only reference |

The sidecar source lives at `optd-sidecar/` in this repo. It depends on `optd-core` and `optd-datafusion` from the private mirror, which contains custom extensions (planner, value conversions, metadata) not present in the upstream.

## Local Development Setup

### Prerequisites

- [Rust toolchain](https://rustup.rs/) (edition 2024 — requires Rust 1.85+)
- Local clone of the private optd mirror (sibling to dba-agent)

### 1. Clone the repositories

```bash
# If starting fresh:
mkdir -p ~/sasank/stayflexi && cd ~/sasank/stayflexi
git clone git@github.com:geekypunk/optd.git optd
git clone <dba-agent-repo-url> dba-agent
```

Expected directory layout:
```
stayflexi/
  dba-agent/            # this repo
    optd-sidecar/       # sidecar Rust source (Cargo.toml, src/main.rs)
    backend/            # Spring Boot backend
  optd/                 # private mirror of cmu-db/optd
    optd/core/          # optd-core crate
    connectors/datafusion/  # optd-datafusion crate
```

The sidecar's `Cargo.toml` uses path dependencies:
```toml
optd-core = { path = "../../optd/optd/core" }
optd-datafusion = { path = "../../optd/connectors/datafusion" }
```

### 2. Build the sidecar

```bash
cd dba-agent/optd-sidecar
cargo build
```

First build downloads and compiles ~300 crates (DataFusion, Arrow, etc.) — takes ~5 minutes. Subsequent builds are fast (~3 seconds).

### 3. Start services

```bash
# Terminal 1: Start the sidecar
cd dba-agent/optd-sidecar
RUST_LOG=info cargo run
# => optd sidecar listening on 0.0.0.0:8088

# Terminal 2: Start the backend
cd dba-agent/backend
mvn -q -DskipTests spring-boot:run
# => Started DbaAgentApplication on port 8080
```

### 4. Verify

```bash
# Sidecar health check
curl http://localhost:8088/health
# => 200 OK

# Test optimization
curl -X POST http://localhost:8088/v1/optimize \
  -H "Content-Type: application/json" \
  -d '{
    "db_type": "mysql",
    "query": "SELECT u.id FROM user_bookings u WHERE u.hotel_id = 123",
    "schema": {
      "tables": [{
        "name": "user_bookings",
        "row_count": 100000,
        "columns": [
          {"name": "id", "data_type": "int", "nullable": false},
          {"name": "hotel_id", "data_type": "int", "nullable": true}
        ]
      }]
    }
  }'
# => {"plan_text":"...","plan_signature":"...","estimated_cost":1.0,...}
```

## Production Setup

### Option A: Build on server (simplest)

#### 1. Install Rust on the production server

```bash
curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh
source $HOME/.cargo/env
rustc --version   # must be 1.85+
```

#### 2. Clone both repos

```bash
mkdir -p /opt/deepsql && cd /opt/deepsql
git clone git@github.com:geekypunk/optd.git optd
# Copy or clone dba-agent (only optd-sidecar/ directory is needed)
```

Expected layout on server:
```
/opt/deepsql/
  optd/                 # private mirror
  dba-agent/
    optd-sidecar/       # sidecar source
```

#### 3. Build release binary

```bash
cd /opt/deepsql/dba-agent/optd-sidecar
cargo build --release
```

The optimized binary is at `target/release/optd-sidecar` (~15-25 MB).

#### 4. Run as a service

```bash
# Direct run:
RUST_LOG=info /opt/deepsql/dba-agent/optd-sidecar/target/release/optd-sidecar

# Or with systemd (recommended):
```

Create `/etc/systemd/system/optd-sidecar.service`:
```ini
[Unit]
Description=OPTD Sidecar - Query Plan Estimation Service
After=network.target

[Service]
Type=simple
User=deepsql
WorkingDirectory=/opt/deepsql
ExecStart=/opt/deepsql/dba-agent/optd-sidecar/target/release/optd-sidecar
Environment=RUST_LOG=info
Restart=on-failure
RestartSec=5

[Install]
WantedBy=multi-user.target
```

```bash
sudo systemctl daemon-reload
sudo systemctl enable optd-sidecar
sudo systemctl start optd-sidecar
sudo systemctl status optd-sidecar
# => Active: active (running)

# View logs
sudo journalctl -u optd-sidecar -f
```

#### 5. Configure the backend

In the backend's `application.properties` or environment variables:
```properties
optd.enabled=true
optd.sidecar.base-url=http://localhost:8088
optd.request-timeout-ms=8000
```

If the sidecar runs on a different host:
```properties
optd.sidecar.base-url=http://<sidecar-host>:8088
```

### Option B: Build locally, deploy binary

Since the sidecar compiles to a single binary, you can cross-compile or build on a matching architecture and just copy the binary:

```bash
# Build release on local/CI machine (must match target OS/arch)
cd dba-agent/optd-sidecar
cargo build --release

# Copy binary to server
scp target/release/optd-sidecar user@prod-server:/opt/deepsql/optd-sidecar

# On server: run directly
ssh user@prod-server
RUST_LOG=info /opt/deepsql/optd-sidecar
```

No Rust toolchain or source code needed on the server — just the binary.

### Option C: Docker

```dockerfile
FROM rust:1.85 AS builder
WORKDIR /build

# Copy optd workspace first (dependency layer — cached unless optd changes)
COPY optd/ /build/optd/

# Copy sidecar source
COPY dba-agent/optd-sidecar/ /build/dba-agent/optd-sidecar/

WORKDIR /build/dba-agent/optd-sidecar
RUN cargo build --release

FROM debian:bookworm-slim
RUN apt-get update && apt-get install -y ca-certificates && rm -rf /var/lib/apt/lists/*
COPY --from=builder /build/dba-agent/optd-sidecar/target/release/optd-sidecar /usr/local/bin/
EXPOSE 8088
ENV RUST_LOG=info
CMD ["optd-sidecar"]
```

Build and run:
```bash
# From the parent directory containing both optd/ and dba-agent/
docker build -f dba-agent/optd-sidecar/Dockerfile -t optd-sidecar .
docker run -d -p 8088:8088 --name optd-sidecar optd-sidecar
```

## Updating the Sidecar

### Updating sidecar code (in dba-agent)

```bash
cd dba-agent
# Make changes to optd-sidecar/src/main.rs
# Test locally, then on production:
cd optd-sidecar
cargo build --release
sudo systemctl restart optd-sidecar
```

### Syncing upstream optd changes

```bash
cd /path/to/optd
git fetch upstream
git merge upstream/main
# Resolve any conflicts — custom changes are in these files:
#   connectors/datafusion/src/planner.rs   (OptdQueryPlanner: new(), take_warnings(), Default)
#   connectors/datafusion/src/value.rs     (value conversion additions)
#   optd/core/src/ir/column/metadata.rs    (metadata extensions)
#   optd/core/src/ir/scalar/function.rs    (function additions)
#   Cargo.toml                             (optd-sidecar added to workspace members)
git push origin main

# Then rebuild sidecar
cd /path/to/dba-agent/optd-sidecar
cargo build --release
sudo systemctl restart optd-sidecar
```

## Sidecar API Reference

### Health
```
GET /health  →  200 OK
```

### Optimize (single query)
```
POST /v1/optimize
{
  "db_type": "mysql" | "postgres" | "postgresql",
  "query": "SELECT ...",
  "schema": {
    "tables": [
      {
        "name": "table_name",
        "row_count": 12345,
        "columns": [
          { "name": "id", "data_type": "int", "nullable": true }
        ]
      }
    ]
  }
}
```

Response:
```json
{
  "plan_text": "...",
  "plan_signature": "sha256-hash",
  "estimated_cost": 2.0,
  "estimated_rows": 0.0,
  "warnings": []
}
```

### Optimize (batch)
```
POST /v1/optimize/batch
{
  "db_type": "mysql",
  "schema": { ... },
  "queries": [
    { "id": "ORIGINAL", "query": "SELECT ..." },
    { "id": "AI_REWRITE", "query": "SELECT ..." }
  ]
}
```

Response:
```json
{ "results": [ { "id": "...", "plan_text": "...", ... } ] }
```

Note: the backend currently calls **only** `/v1/optimize`.

## Backend Integration Points

### Configuration
From `backend/src/main/resources/application.properties`:
```properties
optd.enabled=true
optd.sidecar.base-url=http://localhost:8088
optd.request-timeout-ms=8000
```

### Call Sites
- `QueryOptimizationService#recordOptimizationCandidates` → `upsertCandidateWithOptd(...)`
- `ChatService#tryQueryOptimizationFastPath` (uses best candidate + optd signals)
- `SlowQueryController`:
  - `/optimize/candidates/{connectionId}/{queryFingerprint}`
  - `/optimize/benchmark/{connectionId}/{queryFingerprint}`
  - `/optimize/batch/{connectionId}`

## Architecture Details

### Data Flow
```
SlowQueryController / ChatService
        |
QueryOptimizationService
        |
OptdOptimizationService  ---> SchemaScannerService
        |
OptdClient (POST /v1/optimize)
        |
optd-sidecar (DataFusion + optd)
        |
QueryOptimizationCandidateRun
```

### Sidecar Planning Pipeline
1. Parse SQL using DataFusion's SQL parser (tries db-specific dialect, falls back to Generic)
2. Register schema as in-memory tables (MemTable with empty data)
3. Convert DataFusion logical plan → optd IR via `OptdQueryPlanner`
4. Run Cascades optimizer with rules: table scan, filter, project, hash aggregate, hash join, nested loop join, join commutation, join association
5. Compute `plan_text`, `plan_signature` (SHA-256), `estimated_cost` (MagicCostModel), `estimated_rows`

### Error Recovery (apply_planning_fix)
The sidecar retries up to 20 times, auto-fixing errors:
- Register placeholder UDF for unknown functions
- Add placeholder tables/columns to schema
- Qualify ambiguous columns using alias map
- Rewrite `SUM(bool)` → `SUM(1)`
- Replace unknown variables with literals

### Query Normalization (Backend, MySQL only)
1. Sanitize via `QueryNormalizer.sanitize(...)`
2. Replace `?` / `$1` params → `1`; subquery placeholders → `optd_placeholder`
3. JSQLParser rewrite: strip schema prefixes, `IFNULL` → `COALESCE`, coerce GROUP BY
4. Qualify unqualified columns using schema metadata
5. Aggressive fallback: remove ORDER/LIMIT/OFFSET, reduce SELECT list
6. Regex fallback if parsing fails

## Troubleshooting

| Symptom | Cause | Fix |
|---------|-------|-----|
| Sidecar returns 400 | SQL parse failure or missing schema | Check logs (`RUST_LOG=debug`). Derived tables/CTEs are the most common failure point. |
| Sidecar not reachable | Process not running or wrong URL | Verify `optd.sidecar.base-url`. Backend degrades gracefully when sidecar is down. |
| Build fails on optd deps | optd checkout missing or wrong branch | Ensure `optd/` is cloned from `geekypunk/optd.git` and is on `main`. |
| `cargo build` OOMs | Server has < 2GB RAM | Use `cargo build --release -j 2` to limit parallelism. |
| Slow first build | Normal — ~300 crates to compile | Subsequent builds are ~3 seconds. `cargo build --release` takes longer than debug. |

## Known Limitations

- Cost/rows are heuristic (empty MemTables + MagicCostModel) — not actual execution stats
- Complex derived tables or vendor-specific SQL can still fail to parse
- Backend currently uses single-query endpoint only; batch endpoint exists but is unused
