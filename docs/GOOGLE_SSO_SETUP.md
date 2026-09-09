# Google Workspace SSO

Let your team sign in to DeepSQL with their work Google accounts, and
optionally turn off password sign-in entirely.

DeepSQL verifies Google's `hd` (hosted domain) claim against an allowlist you
control, so only domains you name can get in — a personal Gmail account cannot
sign in even if someone types a matching email address.

**Two things surprise most people, so they are called out up front:**

1. The allowlist is a **database table**. Configuring the environment variables
   alone leaves SSO rejecting everyone. See [step 4](#4-allowlist-your-domain).
2. SSO does **not** create accounts. A user who has never been added to DeepSQL
   gets `403 This account is not provisioned in DeepSQL`, even with a valid
   Workspace login. That is deliberate — see [Provisioning](#provisioning-users).

---

## 1. Create the OAuth client

OAuth clients are created in **Google Cloud Console** (`console.cloud.google.com`),
*not* in the Workspace Admin console (`admin.google.com`). There is no `gcloud`
command for this — the IAP OAuth Admin API was the closest thing and was shut
down in March 2026, and it never supported custom redirect URIs anyway.

In the Cloud Console, the relevant area is **Google Auth Platform**. It was
previously a single "OAuth consent screen" page and is now split:

| You want | Where it lives now |
| --- | --- |
| Internal / External | **Audience** |
| Create an OAuth client | **Clients** |
| App name, logo, support email | **Branding** |
| Scopes | **Data Access** |

Steps:

1. Pick or create a project. It must belong to your Google Workspace
   **organization**, or the Internal option in step 2 is not offered.
2. **Branding** — app name, user support email, developer contact.
3. **Audience** — choose **Internal**.
4. **Clients → Create client**
   - Application type: **Web application**
   - Authorized redirect URI, exactly:
     `https://deepsql.example.com/api/auth/google/callback`
   - Leave *Authorized JavaScript origins* empty — this is a server-side flow.
5. Copy the **Client ID** and **Client secret**.

### Choose Internal if you can

**Internal** means Google itself refuses to issue a token to anyone outside your
organization — the request never reaches DeepSQL. That sits on top of DeepSQL's
own domain allowlist, so a mistake in one does not open the door. It also avoids
the "unverified app" warning that External shows.

> **Do not flip an existing project from External to Internal without checking
> what already uses it.** The Audience setting is project-wide. If another
> application in that project serves users outside your organization, switching
> to Internal cuts them off immediately. Check **Overview → Metrics** for live
> OAuth traffic first; if in doubt, create a separate project.

### One project can serve many apps

`Audience` and `Branding` are per-project; **Clients** are not. Add one client
per application and they share the Internal gate with no extra setup.

Because Branding is shared, name it for the organization rather than one app —
`Acme Internal` rather than `DeepSQL` — since every app in the project shows that
same name on its consent screen.

---

## 2. Configure DeepSQL

```bash
SECURITY_GOOGLE_ENABLED=true
SECURITY_GOOGLE_CLIENT_ID=<client id>
SECURITY_GOOGLE_CLIENT_SECRET=<client secret>
SECURITY_GOOGLE_REDIRECT_URI=https://deepsql.example.com/api/auth/google/callback
```

`SECURITY_GOOGLE_REDIRECT_URI` must match the value registered in the Cloud
Console **character for character**, trailing slash included, or Google rejects
the callback.

---

## 3. Set your public URL

```bash
APP_PUBLIC_URL=https://deepsql.example.com
```

**This one catches people.** There are two similarly named settings and they do
different jobs:

| Variable | Property | Used for |
| --- | --- | --- |
| `APP_PUBLIC_URL` | `app.public-url` | **every auth redirect**, including the Google callback, and invite emails |
| `APP_BASE_URL` | `app.base-url` | the `deepsql login` CLI device-flow authorize URL |

Both default to `http://localhost:3000`. Leave `APP_PUBLIC_URL` unset and Google
sign-in appears to work, then dumps the user on `localhost:3000/dashboard` — the
session cookie was written correctly, but the browser is sent nowhere useful.
Set both on any real deployment.

---

## 4. Allowlist your domain

**SSO rejects every sign-in until you do this.** There is no environment
variable for it; the allowlist lives in the `google_workspace_domain` table so
it can be changed without a restart.

```sql
INSERT INTO google_workspace_domain (domain, enabled, created_at, updated_at)
VALUES ('example.com', true, NOW(), NOW());
```

A sign-in is accepted only when the domain is present **and** enabled **and**
Google's `hd` claim for that account matches it. Checking `hd` rather than the
email suffix is what stops a personal account from impersonating a Workspace
one.

To revoke a domain later, set `enabled = false` — it takes effect immediately.

---

## 5. Restart and verify

Configuration is read at startup, and `docker restart` does **not** re-read
`.env`:

```bash
docker compose up -d --force-recreate backend
```

Check the flag is live:

```bash
curl -s https://deepsql.example.com/api/setup/status
# {"setupComplete":true, ... ,"googleEnabled":true,"passwordLoginEnabled":true}
```

`googleEnabled: true` makes the **Sign in with Google** button appear on the
login page.

---

## Provisioning users

Google proves *who someone is*. It does not decide *whether they may use
DeepSQL*. A user who has never been added gets:

```
403 This account is not provisioned in DeepSQL
```

This is intentional. Without it, anyone in your Workspace — contractors,
interns, every department — could grant themselves access to a tool holding
production database credentials.

Add users through the admin UI (or `POST /admin/users`) **before** they sign in.
On first successful SSO login DeepSQL links their Google identity to the
existing account automatically; there is no separate linking step.

---

## Optional: disable password sign-in

Enabling SSO does **not** close the password path. `/auth/login` keeps working
for every local account, so seed or demo credentials remain usable on an
internet-facing install. To make SSO exclusive:

```bash
SECURITY_PASSWORD_ENABLED=false
```

The login page then hides the password form and offers only Google. The backend
refuses password logins before any credential comparison and records a
`PASSWORD_LOGIN_FAILURE` audit event with `reason=password_login_disabled`.

Defaults to `true`, so existing installs are unaffected.

> **Every user must be able to sign in with Google before you set this.** Anyone
> who has only ever used a password is locked out until they use the Google
> button once. Setting this while `SECURITY_GOOGLE_ENABLED=false` leaves nobody
> able to sign in at all; DeepSQL logs an ERROR at startup if you do.

---

## Troubleshooting

| Symptom | Cause |
| --- | --- |
| No **Sign in with Google** button | `SECURITY_GOOGLE_ENABLED` is not `true`, or the backend was not recreated. Check `/api/setup/status`. |
| `redirect_uri_mismatch` from Google | `SECURITY_GOOGLE_REDIRECT_URI` differs from the Cloud Console entry. Compare exactly — a trailing slash counts. |
| `403 This Google Workspace domain is not allowed` | No enabled row in `google_workspace_domain`, or the account's `hd` claim does not match. Personal Gmail accounts have no `hd` and are always rejected. |
| `403 This account is not provisioned in DeepSQL` | Authentication worked; the user does not exist in DeepSQL. Create them first. |
| Lands on `localhost:3000` after sign-in | `APP_PUBLIC_URL` is unset. See [step 3](#3-set-your-public-url). |
| Everyone locked out after `SECURITY_PASSWORD_ENABLED=false` | Set it back to `true`, recreate the backend, and confirm SSO works before disabling again. |

To see what actually happened, the `security_event` table records every attempt
with its outcome and reason:

```sql
SELECT created_at, event_type, outcome, email, event_metadata
FROM security_event ORDER BY id DESC LIMIT 20;
```
