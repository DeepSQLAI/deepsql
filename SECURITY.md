# Security Policy

DeepSQL stores database credentials in an encrypted vault, holds an AES-GCM key
whose loss is unrecoverable, and enforces read-only SQL execution as a guardrail.
We treat reports against those paths as our highest priority.

## Reporting a vulnerability

Report privately via **Security → Report a vulnerability** on this repository.
Do not open a public issue, and do not describe the problem in a pull request.

We acknowledge reports within **48 hours**, provide an assessment within
**5 business days**, and aim to ship a fix within **90 days**. We credit
reporters in the published advisory unless you prefer otherwise.

If a report is time-critical and you have had no acknowledgement within 48 hours,
open a public issue containing no technical detail — just a request that a
maintainer check private reports — and we will pick it up.

## Supported versions

The latest tagged release receives security fixes. Older tags do not.

## In scope

- Credential-vault encryption and key handling
- Read-only SQL execution enforcement, and any bypass of it
- Authentication, JWT handling, and MCP token authorisation
- The admin bootstrap endpoint
- The dashboard sandbox iframe and its read-only query bridge, including the
  public share path
- SSH tunnelling
- Reachable dependency vulnerabilities

## Out of scope

These are by design, and reporting them will get a courteous decline:

- Behaviour when `SECURITY_AUTH_ENABLED=false`. This is a development-only
  shortcut and is documented as such.
- The hand-written SQL editor's ability to mutate data for a confirming admin.
  A DBA tool that cannot run `UPDATE` is not a DBA tool; the guardrail governs
  *generated* and *agent-issued* SQL, not a human who has explicitly confirmed.
- The localhost-only bootstrap endpoint when deliberately enabled.
- Anything requiring prior host compromise.
- Missing hardening headers with no demonstrated impact.

## How fixes are handled

Fixes are developed in a private fork through GitHub Security Advisories. The
advisory and the patched release are published simultaneously. A vulnerability is
never fixed in a normal public pull request: on a repository anyone can watch,
that commit is a roadmap to the bug for everyone still running the old version.

## A note on review requirements

`.github/CODEOWNERS` routes changes under the vault, authentication and
SQL-execution paths to the security owners, so the right people are *required*
reviewers. It cannot, however, require a larger *number* of approvals on those
paths specifically — GitHub carries a single repo-wide approval count. The
two-approval rule on security-critical paths is therefore a maintainer
convention, enforced by reviewers rather than by the platform. Treat a
security-path pull request carrying only one approval as not yet ready.

## Please do not

- Test against infrastructure you do not own.
- Include real credentials, API keys, or `ENCRYPTION_KEY` values in a report.
  Redact them; we can reproduce from a description.
