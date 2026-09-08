# Per-User Digest Preferences

This document describes the per-user digest preference system introduced to support role-aware, personalized database digests.

## Overview

DeepSQL's daily digest feature now supports **per-user preferences**, allowing each user to:

- Choose their preferred **delivery method** (Slack DM, Slack channel, or email)
- Set a **persona tag** for content prioritization (DBA, App Engineer, Data Engineer, Executive)
- Configure a **custom schedule** (cron expression) per subscription
- Subscribe to **specific connections** or all connections they have access to

## Architecture

### Data Model

```
┌─────────────────────────┐      ┌────────────────────────┐
│   SlackDigestConfig     │      │  UserDigestPreference  │
│   (singleton, id=1)     │      │  (per-user, per-conn)  │
├─────────────────────────┤      ├────────────────────────┤
│ cronExpression          │◄─────│ username               │
│ updatedAt               │      │ connectionId (nullable)│
│ isGlobalDefault = true  │      │ enabled                │
└─────────────────────────┘      │ personaTag             │
         │                       │ cronExpression         │
         │                       │ deliveryMethod         │
         │ fallback when no      │ timezone               │
         │ preferences exist     │ createdAt / updatedAt  │
         ▼                       └────────────────────────┘
```

### Resolution Order

When delivering a digest, the system resolves preferences in this order:

1. **User + Connection specific** — `UserDigestPreference` where `connectionId` matches
2. **User-wide** — `UserDigestPreference` where `connectionId IS NULL`
3. **Global fallback** — `SlackDigestConfig` singleton (legacy behavior)

## Persona Tags

Persona tags influence how digest content is prioritized:

| Tag | Display Name | Focus Areas |
|-----|--------------|-------------|
| `DBA` | DBA | Performance tuning, index recommendations, operational health |
| `APP_ENG` | App Engineer | Query patterns, ORM issues, schema usage |
| `DATA_ENG` | Data Engineer | ETL patterns, data quality, pipeline health |
| `EXEC` | Executive | High-level summaries, costs, capacity forecasts |

A user's **RBAC role** determines what they can access; the **persona tag** influences how content is ranked and presented.

## Delivery Methods

| Method | Requires |
|--------|----------|
| `SLACK_DM` | User must be linked via SlackUserLink |
| `SLACK_CHANNEL` | Connection must have a SlackChannelBinding |
| `EMAIL` | User's registered email address |

## API Endpoints

### User Self-Service (`/digest/preferences`)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/digest/preferences` | List my preferences |
| `POST` | `/digest/preferences` | Create a new preference |
| `PUT` | `/digest/preferences/{id}` | Update a preference |
| `PATCH` | `/digest/preferences/{id}/enabled` | Enable/disable |
| `DELETE` | `/digest/preferences/{id}` | Delete a preference |
| `GET` | `/digest/preferences/persona-tags` | List available persona tags |
| `GET` | `/digest/preferences/delivery-methods` | List available delivery methods |

### Admin Management (`/admin/slack/digest/preferences`)

| Method | Endpoint | Description |
|--------|----------|-------------|
| `GET` | `/admin/slack/digest/preferences/{username}` | Get user's preferences |
| `GET` | `/admin/slack/digest/preferences/users` | List users with preferences |
| `GET` | `/admin/slack/digest/preferences/stats` | Get preference statistics |
| `POST` | `/admin/slack/digest/preferences/{username}` | Create preference for user |
| `PUT` | `/admin/slack/digest/preferences/id/{id}` | Update any preference |
| `DELETE` | `/admin/slack/digest/preferences/id/{id}` | Delete any preference |
| `DELETE` | `/admin/slack/digest/preferences/{username}` | Delete all user's preferences |

## Creating a Preference

### Request Body

```json
{
  "connectionId": "uuid-of-connection",  // null for all connections
  "deliveryMethod": "SLACK_DM",          // SLACK_DM, SLACK_CHANNEL, EMAIL
  "personaTag": "DBA",                   // DBA, APP_ENG, DATA_ENG, EXEC
  "cronExpression": "0 0 8 * * *",       // optional custom schedule
  "timezone": "America/New_York"         // optional IANA timezone
}
```

### Example: Create a DBA preference for all connections via Slack DM

```bash
curl -X POST http://localhost:8080/api/digest/preferences \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "deliveryMethod": "SLACK_DM",
    "personaTag": "DBA"
  }'
```

### Example: Create an executive preference for a specific connection via email at 8 AM EST

```bash
curl -X POST http://localhost:8080/api/digest/preferences \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{
    "connectionId": "abc-123-def-456",
    "deliveryMethod": "EMAIL",
    "personaTag": "EXEC",
    "cronExpression": "0 0 8 * * *",
    "timezone": "America/New_York"
  }'
```

## Migration from Singleton

The singleton `SlackDigestConfig` (id=1) remains active and serves as the global default:

- **No preferences exist** → Digest uses legacy channel-broadcast behavior
- **Preferences exist** → Per-user delivery is used; users without preferences still get the legacy broadcast

This ensures backward compatibility: existing deployments continue working without any configuration changes.

## Audit Trail

The `SlackDigestLog` table now tracks per-recipient delivery:

| Field | Description |
|-------|-------------|
| `recipient_username` | Username of the recipient (null for legacy broadcast) |
| `recipient_role` | RBAC role at delivery time |
| `persona_tag` | Persona used for content prioritization |
| `delivery_method` | How the digest was delivered |
| `preference_id` | FK to the preference that triggered delivery |
| `personalized` | Whether content was role-personalized |

## Future Work (PR2)

This PR (PR1) establishes the **data model and migration only**. The following features are planned for PR2:

- **Role-aware content ranking** — Different insight prioritization per persona
- **Per-user delivery execution** — Actually sending personalized digests
- **Scheduled task per-user** — Respecting individual cron expressions
- **Email delivery implementation** — Currently only Slack is implemented
