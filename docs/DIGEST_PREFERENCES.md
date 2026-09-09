# Digest Preferences & Per-Recipient Delivery

> Same Brain, different lens per role.

This document describes the per-user digest preference system and personalized Slack delivery introduced in PR3.

## Overview

The digest system now supports **per-recipient personalization**: two users with different personas on the same connection get different Slack digests for the same time window. This is built on top of:

- **PR1**: Per-user digest preferences (`UserDigestPreference` model)
- **PR2**: Role-aware insight assembler (`DigestInsightAssemblerService`)
- **PR3**: Slack delivery per recipient + UI prefs (this PR)

## Architecture

```
┌─────────────────────────────────────────────────────────────────────┐
│              Minute tick (slack.daily-digest.tick-cron)             │
│                     SlackDailyDigestTaskConfig                      │
│                        processDigestTick()                          │
└─────────────────────────────────────────────────────────────────────┘
                                   │
                 ┌─────────────────┴─────────────────┐
                 ▼                                   ▼
┌────────────────────────────┐     ┌────────────────────────────────┐
│ No enabled preferences     │     │ Enabled preferences exist      │
│ Global cron due (UTC)?     │     │ For each SLACK_DM preference:  │
│  yes → legacy broadcast    │     │  • effective cron (pref or     │
│  no  → skip                │     │    global default)             │
└────────────────────────────┘     │  • evaluate in pref timezone   │
                                   │  • skip if already logged this │
                                   │    fire window (SlackDigestLog)│
                                   │  • else per-recipient delivery │
                                   └────────────────────────────────┘
                                                   │
                                                   ▼
                                   ┌────────────────────────────────┐
                                   │ assembleDigest → format → DM   │
                                   │ → SlackDigestLog (personalized)│
                                   └────────────────────────────────┘

Manual/admin trigger still uses sendDailyDigestHybrid() (force all recipients).
```

## Persona Tags

Users can set a persona tag to influence how insights are prioritized in their digest:

| Persona | Display Name | Focus Areas |
|---------|--------------|-------------|
| `DBA` | Database Administrator | Lock contention, query performance, config tuning |
| `APP_ENG` | App Engineer | Schema changes (DDL risk), query patterns, locks during deploys |
| `DATA_ENG` | Data Engineer | Documentation gaps, schema changes, growth anomalies |
| `EXEC` | Executive | Cost/capacity, risk summary, 3 bullets max |

### EXEC Payload

EXEC persona digests are intentionally tight:
- **Maximum 3 bullets** in the executive summary
- **One decision ask** for the top actionable item
- Example:
  ```
  🧭 EXECUTIVE SUMMARY
  • ⚠️ 1 critical issue requires attention
  • 📉 Top regression: 3x slowdown on orders query
  • 💰 Storage cost up 15% this month

  📋 ACTION NEEDED
  Review and approve index recommendation for orders table
  ```

## Delivery Methods

| Method | Status | Description |
|--------|--------|-------------|
| `SLACK_DM` | ✅ Implemented | Direct message to linked Slack account |
| `SLACK_CHANNEL` | ✅ Legacy | Post to configured channel (legacy broadcast) |
| `EMAIL` | ⏳ Future (PR4) | Email delivery (not yet implemented) |

Note: Do not claim EMAIL or WhatsApp delivery is available. These are planned for PR4.

## API Endpoints

### User Preferences (Any authenticated user)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/digest/preferences` | GET | Get current user's preferences |
| `/api/digest/preferences` | POST | Create a new preference |
| `/api/digest/preferences/{id}` | PUT | Update a preference |
| `/api/digest/preferences/{id}/enabled` | PATCH | Enable/disable |
| `/api/digest/preferences/{id}` | DELETE | Delete a preference |
| `/api/digest/preferences/persona-tags` | GET | List available personas |
| `/api/digest/preferences/delivery-methods` | GET | List delivery methods |
| `/api/digest/preferences/status` | GET | Get digest mode info |
| `/api/digest/preferences/seed/me` | POST | Seed preferences for current user |

### Admin Seed (Admin only)

| Endpoint | Method | Description |
|----------|--------|-------------|
| `/api/digest/preferences/admin/seed/preview` | GET | Preview what would be seeded |
| `/api/digest/preferences/admin/seed` | POST | Execute seed for all linked users |

## Seeding Preferences

To migrate from legacy singleton mode to per-user mode:

1. **Admin Preview**: `GET /api/digest/preferences/admin/seed/preview`
   - Shows what preferences would be created
   - No changes are made

2. **Admin Execute**: `POST /api/digest/preferences/admin/seed`
   - Creates SLACK_DM preferences for all Slack-linked users
   - One preference per connection they can access
   - Persona is inferred from their RBAC role
   - Idempotent: skips existing preferences

3. **User Self-Seed**: `POST /api/digest/preferences/seed/me`
   - User creates their own preferences
   - Useful after linking Slack account

### Persona Inference from Role

| RBAC Role | Inferred Persona |
|-----------|------------------|
| `DBA` | `PersonaTag.DBA` |
| `DATA_ENGINEER` | `PersonaTag.DATA_ENG` |
| `DEVELOPER` | `PersonaTag.APP_ENG` |
| `ADMIN` | `null` (let them choose) |

## UI Integration

The digest preferences are accessible from:

1. **Digest Section** (sidebar): Click the bell icon (🔔) to open preferences panel
2. **Preferences Panel**: Create, edit, enable/disable, delete preferences

### Preferences Panel Features

- View all your digest subscriptions
- Toggle digests on/off per connection
- Change persona without recreating
- Quick schedule presets (8 AM, 9 AM, Noon, etc.) — stored on the preference and honored by the minute-tick scheduler
- Seed for all your connections at once

## Database Schema

### user_digest_preference

```sql
CREATE TABLE user_digest_preference (
    id BIGSERIAL PRIMARY KEY,
    username VARCHAR(255) NOT NULL,
    connection_id VARCHAR(36),
    enabled BOOLEAN NOT NULL DEFAULT true,
    persona_tag VARCHAR(32),
    cron_expression VARCHAR(100),
    delivery_method VARCHAR(32) NOT NULL DEFAULT 'SLACK_DM',
    timezone VARCHAR(64),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    CONSTRAINT uk_user_digest_pref_user_conn_method 
        UNIQUE (username, connection_id, delivery_method)
);
```

### slack_digest_log (Extended)

New columns for per-recipient tracking:

```sql
ALTER TABLE slack_digest_log ADD COLUMN recipient_username VARCHAR(255);
ALTER TABLE slack_digest_log ADD COLUMN recipient_role VARCHAR(64);
ALTER TABLE slack_digest_log ADD COLUMN persona_tag VARCHAR(32);
ALTER TABLE slack_digest_log ADD COLUMN delivery_method VARCHAR(32);
ALTER TABLE slack_digest_log ADD COLUMN preference_id BIGINT;
ALTER TABLE slack_digest_log ADD COLUMN personalized BOOLEAN NOT NULL DEFAULT false;
```

## Configuration

| Property | Default | Description |
|----------|---------|-------------|
| `slack.daily-digest.tick-cron` | `0 * * * * *` | Minute tick that evaluates due preferences |
| `slack.daily-digest.cron` | `0 0 9 * * *` | Global/legacy schedule (UTC); also default when a preference leaves `cronExpression` blank |
| `slack.digest.admins-only` | `true` | Restrict legacy broadcast to admin channels |

### Per-user schedules

Each `UserDigestPreference` may set `cronExpression` (Spring 6-field) and an IANA `timezone`.
The minute tick uses Spring's `CronExpression` API to decide who is due. Delivery is
idempotent per preference + connection for that fire window via `SlackDigestLog`.
UI schedule presets (8 AM / 9 AM / Noon / …) write these fields — they are honored by
the scheduler, not display-only.

## Out of Scope (Future PRs)

1. **Idle-in-transaction insight** (state/duration) when Brain has data
2. **join_collapse_limit cliffs** under CONFIG_TUNING when plan/config stores can detect
3. **DOCUMENTATION_GAPS miner** (COMMENT ON / Schema Guardian)
4. **COST_CAPACITY dedicated cost miner** for EXEC 💰 bullet
5. **Hermes/WhatsApp delivery** (PR4)

## Testing

Run the tests:

```bash
cd backend
./mvnw test -Dtest=PerRecipientDigestDeliveryTest,PerRecipientDigestCronSchedulingTest,DigestCronMatcherTest,DigestPreferenceSeedServiceTest,SlackDailyDigestServiceTest
```

Key test scenarios:
- Two users with different personas get different digests
- Different crons → only the due user is delivered on a tick
- Timezone: `0 0 9 * * *` in `America/New_York` fires at 13:00 UTC (EDT)
- Idempotent: already-logged fire window is skipped
- EXEC gets tight 3-bullet summary
- Seed skips existing preferences (idempotent)
- Dry run mode for preview
- Legacy fallback when no preferences exist (gated by global cron)

## GTM Line

> Same Brain, different lens per role
