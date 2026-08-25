# Changelog

All notable changes to DeepSQL are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html)
for product tags (`vMAJOR.MINOR.PATCH`).

Product releases follow a **weekly cadence** (Saturday 09:00 America/Los_Angeles). See `docs/oss-ux/RELEASE.md`.

## [1.3.0] — 2026-08-24

### Added

- **DeepSQL Desktop** — first-ship Electron thin client (`desktop/`) with direct TLS and SSH-tunnel transports, connection profiles, OS keychain secrets, and native chrome (#73).
- Desktop release workflow (`.github/workflows/desktop-release.yml`) for macOS / Windows / Linux installers on `desktop-v*` tags.
- Enforceable Agent brain-note proposals with non-blocking save bubbles (#75).
- Schema documentation dedupe / `CODE_DERIVED` compatibility initializers (`V116`) (#74, #77).

### Fixed

- Cross-user MCP credential leak in the Agent tab — provisioner no longer last-writer-wins across Hermes profiles; MCP tokens bind to declared client identity (#78).
- View as Agent enforces the target user’s data policy on new Agent threads (#71).
- Chat schema allowlist enumerates the whole statement (#70).
- Brain endpoints require connection content authorization (#72).
- Review queue approvals: stale pending counts, bulk `failures[]`, approval path unwedge (#74, #77).
- Editor CSV export bounded; concurrent-run guards and cancel audit (#76).

### Changed

- Documented CORS loopback wildcards required for Desktop SSH tunnels (`CORS_ALLOWED_ORIGINS`).
- `@deepsql/mcp` → `0.27.1`.
- DeepSQL Desktop package → `1.0.0` (cut installers with `desktop-v1.0.0`).

### Notes

- Open follow-up: dashboard workspaces + custom roles (#80) intentionally not in this cut.

## [1.2.0] — 2026-08-19

### Added

- Schema-scoped chat access policies with plain-English rules (`allowedSchemas`, column/table deny lists).
- Admin profile switch (“View as”) for policy validation (`/admin/impersonate`).
- ACME ERP multi-schema Postgres fixture (`acme_erp`: `crm`, `sales`, `finance`, `inventory`, `hr`, `marts`) and `scripts/seed-acme-erp.sh`.
- Multi-schema E2E gate: `scripts/self-host/e2e-multischema-check.py`.

### Fixed

- Postgres `getTableColumns` binds the caller schema (#60).
- Dashboard loading states (#61).
- SQL editor guard bypasses closed (#63).
- `COMMENT` / `CALL` table names no longer treated as mutations (#64).
- ReDoS-safe parsing for chat-access-policy deny/allow regexes (#65).
- Hermes agent + web UI pinned to release tags (#68).

### Changed

- Schema API (`/objects`, `/schema`, table indexes/stats) filters by per-user policy.
- `@deepsql/mcp` remains `0.27.0` for this cut.

## [1.1.0] — 2026-08-15

### Added

- Progressive dashboard builds (`dashboard-shell` / `dashboard-widget` SSE chunks) with live Preview mounting.
- Dashboard organization: clone, folders, favorites, search.
- Dashboard version history (`dashboard_versions`, restore) and AI-evaluated alerts (`dashboard_alerts`).
- Dashboard refresh / auto-refresh and public TV kiosk mode.
- Centered dashboard intro and editable breadcrumb title.
- Server-owned dashboard generation persistence (`generation_status`, optimistic locking).

### Changed

- Multi-schema awareness across Editor, Brain, and Advisor UI (#55).
- Slow Queries and Workload Analysis merged into a single Performance area (#52).

### Fixed

- CI: remove CodeQL visibility guard that blocked merges (#56).
- Cloud agent ops notes for Hermes MCP restart and multi-schema fixtures (#53).

### Notes

- `@deepsql/mcp` remains `0.27.0` for this cut (no MCP API changes required).
- Hand-apply SQL changelog `V111`–`V114` when not using `ddl-auto=update`.

## [1.0.0] — 2026-08-13

First public OSS release.

### Added

- Self-hosted DeepSQL stack: Spring Boot backend, React UI, DeepSQL Agent, MCP/CLI (`@deepsql/mcp`).
- Brain initialization with multi-schema discovery, coverage gates, and terminal `NEEDS_ATTENTION` handling.
- Agent tab SSO bridge with per-user MCP tokens, token-file rotation, and fail-loud provisioning.
- Dashboard artifact generation (HTML + sandboxed `deepsql.query` bridge).
- BYO LLM via OpenAI-compatible providers (`LlmProviderRegistry` / `LlmConfigResolver`).
- Official release tooling: `scripts/release/build-artifacts.sh` and tag-driven GitHub Release workflow.

### Security

- Session kill endpoints reject non-integer PIDs (SQL injection class closed).
- Dangerous controllers enforce connection ACL; Hermes/agent provisioner bind loopback.
- Compose Valkey password + Actuator limited to health for anonymous callers.
- JWT / session auth fail-closed when secrets are missing.

### Known limitations

- Residual high-severity items tracked in `docs/oss-ux/OSS_SECURITY_REVIEW.md` (IDOR sweep, SET preamble allowlist, SSRF hardening, share-password defaults) are deferred past this cut.
- Primary distribution path remains `docker compose up --build` (no pre-built container registry in this release).

[1.3.0]: https://github.com/DeepSQLAI/deepsql/releases/tag/v1.3.0
[1.2.0]: https://github.com/DeepSQLAI/deepsql/releases/tag/v1.2.0
[1.1.0]: https://github.com/DeepSQLAI/deepsql/releases/tag/v1.1.0
[1.0.0]: https://github.com/DeepSQLAI/deepsql/releases/tag/v1.0.0
