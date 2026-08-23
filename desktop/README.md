# DeepSQL Desktop

A cross-platform desktop client for a self-hosted DeepSQL server. It connects to
the VM (or bare metal) running the DeepSQL stack either **directly over TLS** or
through an **SSH tunnel**, and presents the DeepSQL UI in a native window with
connection management, health, and transport status built into the chrome.

```
┌────────────────── DeepSQL.app ──────────────────┐
│  Launcher            Workspace window            │
│  (connections)   ┌──────────────────────────┐    │
│                  │ native chrome  ● 170 ms  │    │
│                  ├──────────────────────────┤    │
│                  │  the DeepSQL web UI      │    │
│                  │  (WebContentsView)       │    │
│                  └──────────────────────────┘    │
└──────────┬───────────────────────┬───────────────┘
           │ TLS                   │ SSH tunnel
           ▼                       ▼
  https://deepsql.example.com   127.0.0.1:PORT ⇢ ssh ⇢ VM:3000
```

## Design: a thin client, not a second frontend

The client **does not bundle a copy of the DeepSQL web app**. It navigates an
embedded `WebContentsView` at the real DeepSQL origin. Consequences worth
knowing:

- The UI is always the exact version the VM is running. There is no
  bundle/backend skew and no second copy of 40+ tabs to keep in step.
- Cookies and SSE behave exactly as in a browser, because the app is served
  from one origin — `docker/nginx/default.conf` already fronts `/api` and
  `/agent-api` behind the frontend container.
- **CORS is the one exception, and the one backend setting you must get right.**
  Over a tunnel that origin is `http://127.0.0.1:<port>`, so the VM's
  `CORS_ALLOWED_ORIGINS` has to allow it. See
  [CORS on the VM](#cors-on-the-vm-the-403-nobody-can-read).
- Everything the client adds is what a browser tab cannot show: which VM you
  are on, how you are reaching it, and whether that path is healthy.

Both transports resolve to the same thing — an origin — so nothing downstream
of the transport layer knows or cares which one is in use.

## Install

### From a release build

| Platform | Artifact |
| --- | --- |
| macOS (Apple Silicon / Intel) | `DeepSQL-<version>-arm64.dmg`, `-x64.dmg` |
| Windows | `DeepSQL Setup <version>.exe` (NSIS), portable `.exe` |
| Linux | `.AppImage`, `.deb`, `.rpm` |

macOS builds are unsigned unless you supply signing credentials, so the first
launch needs **right-click → Open** (or `xattr -dr com.apple.quarantine
/Applications/DeepSQL.app`).

### From source

**Requires Node.js 22+** (matches the Desktop release CI). GUI needs a display; on
headless Linux use `xvfb-run` for the packaged app or the selftests.

```bash
cd desktop
npm install
npm start            # run the app
npm run dev          # run with DevTools open (unpackaged only — see "DevTools")
npm test             # drift guard for the DevTools kill switch
```

## Building installers

```bash
npm run icons        # regenerate build/icon.png from build/icon.svg
npm run dist:mac     # dmg + zip, arm64 and x64
npm run dist:win     # nsis + portable, x64 and arm64
npm run dist:linux   # AppImage + deb + rpm
npm run dist         # every target this host can build
```

Artifacts land in `desktop/release/`. Cross-building is limited by the host:
Windows targets need Wine on macOS/Linux, and Linux targets are most reliable in
the `electron-builder` Docker image. Building each platform on its own CI runner
is the low-friction option.

**Code signing** is off by default. Set the standard electron-builder
environment variables to enable it:

- macOS: `CSC_LINK`, `CSC_KEY_PASSWORD`, plus `APPLE_ID`, `APPLE_APP_SPECIFIC_PASSWORD`, `APPLE_TEAM_ID` for notarisation.
- Windows: `CSC_LINK`, `CSC_KEY_PASSWORD` (or an Azure Trusted Signing config).

**Auto-update** is opt-in. `package.json` sets `"publish": null`, so no update
feed is baked in and the updater no-ops. To enable it, either set a `publish`
target (GitHub Releases, S3, generic) before building, or point
`DEEPSQL_UPDATE_FEED` at a generic feed URL at runtime.

## Connecting

### Direct over TLS

Enter the origin that serves the DeepSQL UI — the same URL you would open in a
browser. Certificate verification has four modes:

| Mode | Use when |
| --- | --- |
| **Publicly trusted** | The VM has a real hostname and a Let's Encrypt / commercial certificate. Default. |
| **Pinned certificate** | Self-signed certificate, or the server is only reachable by IP. Leave the fingerprint blank to pin whatever is presented on the first connection. |
| **Private CA bundle** | Corporate internal PKI. Point it at the CA `.pem`. |
| **Trust on first use** | Bootstrapping only. Accepts any certificate once, then pins it. Shown in red, and the workspace chrome badges the connection `UNVERIFIED`. |

The pin applies to both the health probe (Node) and the embedded browser
(Chromium), so the UI is held to exactly the same rule as the connection check.

### SSH tunnel

Equivalent to `ssh -i key.pem -L <local>:127.0.0.1:3000 ubuntu@vm`, but run
in-process — no `ssh` binary required, which matters on Windows.

Use it when the DeepSQL ports are closed to the internet. The Compose stack only
publishes to the VM's own host, so a tunnel reaches them without exposing
anything publicly.

- **Remote port** is DeepSQL *as seen from inside the VM*. Use `3000`, the
  frontend container. Pointing it at a host reverse proxy on `:80` usually
  returns 404: that proxy matches on `server_name`, and a tunnel arrives with
  `Host: 127.0.0.1:<port>`, which matches no vhost. The container's own nginx
  uses `server_name _` and answers whatever Host it is given.
- **Local port** `0` picks a free port and then reuses it on later launches, so
  the origin (and therefore the web app's `localStorage`) stays stable.
- The listener binds `127.0.0.1` only — never the LAN.
- Host keys are **trust on first use, then strict**. A changed host key aborts
  the connection with both fingerprints shown rather than offering a dialog to
  click through. If the VM was genuinely rebuilt, clear the pinned key in the
  connection settings.

`http://127.0.0.1:<port>` is a secure context in Chromium, so the backend's
`Secure` session cookies are still accepted over the tunnel.

### CORS on the VM (the 403 nobody can read)

The tunnel gives the web app the origin `http://127.0.0.1:<port>`, not your VM's
hostname. If `CORS_ALLOWED_ORIGINS` on the VM names only the hostname, the
backend rejects that origin — and it does so in the most confusing way
available:

- Chromium omits `Origin` on same-origin **GET**s, so the health probe, the SPA
  and every read work. The connection tests green.
- Chromium *does* send `Origin` on same-origin **POST/PUT/DELETE**, and Spring
  treats any request carrying `Origin` as cross-origin (the same-origin
  short-circuit was removed in Spring 5.3). So the first POST — the login —
  returns `403` with the plain-text body `Invalid CORS request`.
- That body has no `message` field, so the web app's axios interceptor falls
  back to axios's own wording: **"Request failed with status code 403"**, naming
  neither CORS nor the origin.

Fix it on the VM by keeping loopback patterns in the allowlist alongside your
hostname, then restarting the backend:

```bash
# /home/<user>/deepsql-self-host/.env
CORS_ALLOWED_ORIGINS=https://deepsql.example.com,http://127.0.0.1:*,http://localhost:*

docker compose up -d backend
```

The `*` is a **port** wildcard, which matters because the tunnel's local port is
chosen at runtime; enumerating ports means re-editing the VM whenever it
changes. It is legal only because `SecurityConfig` uses
`setAllowedOriginPatterns` — plain `setAllowedOrigins` rejects `*` in
combination with `allowCredentials(true)`. Note that `CORS_ALLOWED_ORIGINS`
*replaces* the built-in list rather than extending it, which is how the loopback
entries usually go missing.

Verify without opening the app:

```bash
curl -s -o /dev/null -w '%{http_code}\n' \
  -H "Origin: http://127.0.0.1:<port>" http://127.0.0.1:<port>/api/actuator/health
# 200 = allowed · 403 = still missing from CORS_ALLOWED_ORIGINS
```

Since the probe now sends an `Origin` header, the launcher catches this at
connect time and says so, instead of letting it surface as a failed login.

## Security model

- **Secrets** (key passphrases, SSH passwords) are encrypted with Electron
  `safeStorage`, backed by Keychain / DPAPI / libsecret. On a machine with no
  usable secret service, nothing is written to disk and the secret is kept in
  memory for the session only — the launcher says so rather than pretending.
- **Session isolation**: each connection gets its own persistent partition
  (`persist:deepsql-<profileId>`), so signing into two DeepSQL servers never
  crosses cookies or cached state.
- **Renderer hardening**: `contextIsolation` on, `nodeIntegration` off, the
  content view sandboxed, `webview` attachment blocked, and permissions denied
  by default except sanitised clipboard writes.
- **Navigation confinement**: the embedded view cannot leave the DeepSQL origin.
  Same-origin popups (shared dashboards) open in a window on the same session;
  everything else goes to the OS browser.
- **No credential proxying**: the client never sees database credentials. It
  speaks to DeepSQL's own API surface exactly as a browser does.
- **DevTools are off in packaged builds** — see below.

## DevTools

A packaged build cannot open DevTools. Every window sets
`webPreferences.devTools: DEVTOOLS_ENABLED`, and `config.js` defines that as
`!app.isPackaged` — nothing else. Chromium then refuses to attach DevTools at
all, so `openDevTools()` is a no-op and the shortcuts do nothing; the View menu
omits **Toggle Developer Tools** (and with it the `Alt+Cmd+I` / `Ctrl+Shift+I`
binding, which that item owned, since the app installs its own menu and so gets
no `toggleDevTools` role from Electron).

`DEVTOOLS_ENABLED` is deliberately **not** `IS_DEV`. `IS_DEV` is true whenever
`DEEPSQL_DESKTOP_DEV=1`, and any user can set that on the shipped app —
`DEEPSQL_DESKTOP_DEV=1 open -a DeepSQL` used to open DevTools automatically on
both windows, no menu involved. Gating on `app.isPackaged` alone is what makes
the switch unreachable from outside the build.

The app also refuses to start when passed `--remote-debugging-port`,
`--remote-debugging-pipe`, `--remote-allow-origins`, or `--inspect*`. Those open
a DevTools *protocol* endpoint, a separate door that `devTools: false` does not
close, and Chromium parses them before any app code runs — so the only remedy is
to exit immediately, before a window opens or a tunnel comes up.

Development is unaffected: `npm start` and `npm run dev` are unpackaged, so
DevTools work as before.

**Verifying the block** (source assertions cannot prove runtime behaviour):

```bash
npm test                          # drift guard: every window sets devTools, gated correctly
npm run dist:mac                  # then, in the installed app:
#   View menu has no "Toggle Developer Tools"; Alt+Cmd+I does nothing
DEEPSQL_DESKTOP_DEV=1 open -a DeepSQL     # no DevTools — the closed hole
/Applications/DeepSQL.app/Contents/MacOS/DeepSQL --remote-debugging-port=9222
#   exits 1, logging "refusing to start with remote debugging enabled"
```

Known limits, stated plainly: `ELECTRON_RUN_AS_NODE=1` turns the binary into a
plain Node process that never loads the app, and anyone able to modify the app
bundle can undo any of this. These controls stop a curious user poking at the
shipped client; they are not a defence against someone who controls the machine.
Treat the backend's authorization as the real boundary.

## Editing a connected profile

Saving a profile applies it. If the connection is live and the edit changes what
the transport does, the connection is rebuilt onto the new settings and the open
window follows it to the new origin — a rebuilt tunnel binds a different local
port, so the origin changes with it.

The comparison is `profiles.transportFingerprint()`, over the fields that decide
what the connection *is*: transport, URL, every TLS field, and the SSH host,
port, username, auth method, key path, remote host/port/scheme, pinned local
port, and pinned host key. Renaming a connection is not in it, so a rename never
costs you a working tunnel. `stickyLocalPort` is not in it either — we choose
that, not the user, and including it would make every connection differ from
itself on the next connect.

This is all-or-nothing per save. If the rebuild fails, the old connection is
**not** kept: it was built from settings that no longer exist, so it is closed
and the failure is reported, naming the fact that the previous session used the
settings you replaced. A window that looks connected while serving settings you
have changed is the state this design removes.

Before this, `connect()` reused any live connection unconditionally. The launcher
persists the form before every Connect, so the stored profile was always correct
and the *store* was never the problem — the reused connection simply kept running
the old settings, and Test reported a confident pass for settings that had been
replaced. Changing a tunnel's remote port and pressing Connect did nothing at
all.

```bash
npm run selftest:settings   # two fake servers; proves an edit moves the connection
```

## Deep links

`deepsql://connect?url=https://deepsql.example.com&name=Production` opens the
launcher with a connection pre-filled — a one-click onboarding link for an admin
to paste into internal docs. `transport=tunnel`, `sshHost` and `sshUser` are also
accepted.

## Diagnostics

```bash
# Check a server the same way the app does, without opening a window
npm run smoke -- --url https://deepsql.example.com
npm run smoke -- --ssh-host 20.29.48.144 --ssh-user ubuntu --key ~/keys/vm.pem

# Exercise the tunnel end to end against a throwaway in-process SSH server
npm run selftest:tunnel

# Prove an edited setting reaches the live connection (two fake DeepSQL servers)
npm run selftest:settings

# Drift guard for the DevTools kill switch
npm test
```

Both self-tests redirect `userData` to a temp directory, so they never touch your
real `profiles.json`.

Logs are at `<userData>/logs/desktop.log`; the launcher footer has an **Open log
file** link. Connection profiles live in `<userData>/profiles.json` (mode 0600,
secrets stored only as `safeStorage` ciphertext).

| Platform | userData |
| --- | --- |
| macOS | `~/Library/Application Support/DeepSQL` |
| Windows | `%APPDATA%\DeepSQL` |
| Linux | `~/.config/DeepSQL` |

## Layout

```
src/
  main/
    index.js          app lifecycle, single instance, deep links
    config.js         shared constants
    profiles.js       connection profiles + persistence
    secrets.js        safeStorage wrapper
    transport.js      transport manager (connect/disconnect/health)
    tunnel.js         SSH local forward (ssh2)
    tls.js            certificate policy for Node and Chromium
    probe.js          /api/actuator/health reachability check
    ipc.js            every renderer→main entry point
    menu.js, updater.js, logger.js
    windows/
      launcher.js     connection manager window
      workspace.js    frameless shell: native chrome + DeepSQL view
  preload/            narrow contextBridge APIs
  renderer/
    shared/theme.css  DeepSQL design tokens (mirrors src/index.css)
    launcher/         connection manager UI
    chrome/           workspace top chrome
scripts/
  generate-icons.js   SVG → build/icon.png via Electron
  smoke.js            headless connection check
  tunnel-selftest.js  end-to-end SSH tunnel test
  settings-selftest.js proves an edited setting reaches the live connection
```

The renderers are plain HTML/CSS/JS with no build step: they are chrome around
an IPC surface, and the DeepSQL React app they wrap is loaded from the server,
not reimplemented. `renderer/shared/theme.css` mirrors the tokens in the web
app's `src/index.css` — keep the two in step if the palette moves.
