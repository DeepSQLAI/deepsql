# OSS launch handoff docs

These docs are the durable home for OSS go-live planning. **Do not rely on `/opt/cursor/artifacts/`** — that path is ephemeral per Cloud Agent VM and is not shared with other agents.

| Doc | Purpose |
|-----|---------|
| [`OSS_LAUNCH_USABILITY_CRITIQUE.md`](./OSS_LAUNCH_USABILITY_CRITIQUE.md) | E2E usability findings (Brain, Agent, onboarding) |
| [`E2E_FIX_PROPOSAL.md`](./E2E_FIX_PROPOSAL.md) | Product fix workstreams W1–W7 + PR order |
| [`OSS_SECURITY_REVIEW.md`](./OSS_SECURITY_REVIEW.md) | Security Criticals/Highs + S1–S10 track |
| [`E2E_RELEASE_VERIFICATION.md`](./E2E_RELEASE_VERIFICATION.md) | Pre-`v1.0.0` stack redeploy + smoke matrix results |
| [`RELEASE.md`](./RELEASE.md) | Cadence + how to cut GitHub Releases (artifacts, tags, checksums) |
| [`WEEKLY_RELEASE_AUTOMATION.md`](./WEEKLY_RELEASE_AUTOMATION.md) | Saturday 09:00 PT weekly release automation prompt |
| [`DAILY_RELEASE_AUTOMATION.md`](./DAILY_RELEASE_AUTOMATION.md) | Optional daily PR triage automation prompt |

Product release notes live under [`docs/releases/`](../releases/). Build locally with `./scripts/release/build-artifacts.sh v1.1.0` (output: `release-artifacts/v1.1.0/`).

Other Cloud Agents: read these paths from the repo (this branch or after merge to `main`).
