import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router-dom'
import {
  AlertTriangle,
  Apple,
  ArrowLeft,
  Download as DownloadIcon,
  Loader2,
  Monitor,
  Package,
  Terminal,
} from 'lucide-react'

/**
 * Public download page for the DeepSQL desktop client.
 *
 * Asset list comes straight from the GitHub Releases API. This is the one place
 * that deliberately does NOT go through lib/api/client.js: that module is the
 * DeepSQL backend's axios layer (auth headers, refresh, error envelope), and
 * none of it applies to a third-party public API. A plain fetch keeps the page
 * working before a user has logged in — or on a box whose backend is down.
 *
 * The repo publishes two unrelated release series from the same tags list:
 * `v1.3.0` (the DeepSQL app) and `desktop-v*` (this client). /releases/latest
 * returns the newest of *either*, so it hands back the app release and would
 * point every download button at the wrong artifact. Filter by tag prefix.
 */

const REPO = 'DeepSQLAI/deepsql'
const TAG_PREFIX = 'desktop-v'

const PLATFORMS = {
  mac: { label: 'macOS', icon: Apple },
  windows: { label: 'Windows', icon: Monitor },
  linux: { label: 'Linux', icon: Terminal },
}

/** Classify a release asset by filename, not by position in the list. */
function classify(asset) {
  const name = asset.name.toLowerCase()
  const arch = name.includes('arm64')
    ? 'Apple Silicon'
    : name.includes('x64') || name.includes('amd64') || name.includes('x86_64')
      ? 'Intel / AMD64'
      : null

  if (name.endsWith('.dmg')) return { platform: 'mac', kind: 'Disk image', arch, rank: 0 }
  if (name.endsWith('.zip')) return { platform: 'mac', kind: 'Zip archive', arch, rank: 1 }
  if (name.endsWith('.exe'))
    return {
      platform: 'windows',
      kind: name.includes('setup') ? 'Installer' : 'Portable',
      arch,
      rank: name.includes('setup') ? 0 : 1,
    }
  if (name.endsWith('.appimage')) return { platform: 'linux', kind: 'AppImage', arch, rank: 1 }
  if (name.endsWith('.deb')) return { platform: 'linux', kind: 'Debian package', arch, rank: 0 }
  if (name.endsWith('.rpm')) return { platform: 'linux', kind: 'RPM package', arch, rank: 2 }
  return null
}

/** Best-effort guess so the primary button matches the visitor's machine. */
function detectPlatform() {
  const ua = navigator.userAgent || ''
  if (/Mac|iPhone|iPad/i.test(ua)) return 'mac'
  if (/Win/i.test(ua)) return 'windows'
  if (/Linux|X11/i.test(ua)) return 'linux'
  return null
}

/** Prefer Apple Silicon builds on arm64 Macs when both exist. */
function detectMacArchPreference() {
  const ua = navigator.userAgent || ''
  if (/arm64|aarch64/i.test(ua)) return 'Apple Silicon'
  return 'Intel / AMD64'
}

function formatSize(bytes) {
  if (!bytes) return ''
  const mb = bytes / (1024 * 1024)
  return `${mb.toFixed(1)} MB`
}

function pickPrimaryAsset(grouped, platform) {
  const assets = grouped[platform] || []
  if (!assets.length) return null

  const archPref = platform === 'mac' ? detectMacArchPreference() : null
  const sorted = [...assets].sort((a, b) => {
    if (archPref) {
      const aMatch = a.arch === archPref ? 0 : 1
      const bMatch = b.arch === archPref ? 0 : 1
      if (aMatch !== bMatch) return aMatch - bMatch
    }
    return (a.rank ?? 9) - (b.rank ?? 9)
  })
  return sorted[0]
}

function latestDesktopRelease(releases) {
  return releases
    .filter((r) => r.tag_name?.startsWith(TAG_PREFIX) && !r.draft)
    .sort(
      (a, b) =>
        new Date(b.published_at).getTime() - new Date(a.published_at).getTime(),
    )[0]
}

export default function Download() {
  const [state, setState] = useState({ status: 'loading' })
  const detected = useMemo(() => detectPlatform(), [])

  useEffect(() => {
    let cancelled = false

    fetch(`https://api.github.com/repos/${REPO}/releases?per_page=30`, {
      headers: { Accept: 'application/vnd.github+json' },
    })
      .then((res) => {
        if (!res.ok) throw new Error(`GitHub returned ${res.status}`)
        return res.json()
      })
      .then((releases) => {
        if (cancelled) return
        const release = latestDesktopRelease(releases)
        if (!release) return setState({ status: 'none' })
        setState({ status: 'ready', release })
      })
      .catch((err) => {
        if (cancelled) return
        setState({ status: 'error', message: err.message })
      })

    return () => {
      cancelled = true
    }
  }, [])

  const grouped = useMemo(() => {
    if (state.status !== 'ready') return {}
    const out = { mac: [], windows: [], linux: [] }
    for (const asset of state.release.assets || []) {
      const meta = classify(asset)
      if (meta) out[meta.platform].push({ ...asset, ...meta })
    }
    for (const key of Object.keys(out)) {
      out[key].sort((a, b) => (a.rank ?? 9) - (b.rank ?? 9))
    }
    return out
  }, [state])

  const primary = useMemo(
    () => (detected ? pickPrimaryAsset(grouped, detected) : null),
    [grouped, detected],
  )

  const versionLabel =
    state.status === 'ready'
      ? state.release.tag_name.replace(TAG_PREFIX, 'Version ')
      : null

  return (
    <div className="min-h-screen bg-white text-gray-900">
      <div className="max-w-3xl mx-auto px-6 py-16">
        <Link
          to="/login"
          className="inline-flex items-center gap-1.5 text-sm text-gray-500 hover:text-gray-800 transition-colors mb-8"
        >
          <ArrowLeft className="h-4 w-4" />
          Back to sign in
        </Link>

        <header className="mb-12">
          <div className="flex items-center gap-3 mb-4">
            <div className="h-10 w-10 rounded-xl bg-gray-900 flex items-center justify-center">
              <DownloadIcon className="h-5 w-5 text-white" />
            </div>
            <h1 className="text-3xl font-semibold tracking-tight">DeepSQL Desktop</h1>
          </div>
          <p className="text-gray-500 leading-relaxed">
            A native client for your self-hosted DeepSQL server. Connects directly over
            TLS or through an SSH tunnel, with connection health and transport status
            built into the window chrome.
          </p>
        </header>

        {state.status === 'loading' && (
          <div className="flex items-center gap-3 text-gray-500 py-12">
            <Loader2 className="h-5 w-5 animate-spin" />
            <span>Looking up the latest release…</span>
          </div>
        )}

        {state.status === 'error' && (
          <Notice
            tone="error"
            title="Could not reach GitHub"
            body={`The download list could not be loaded (${state.message}). This is a problem fetching the release list, not a sign that no build exists — you can browse releases directly on GitHub.`}
            action={{
              href: `https://github.com/${REPO}/releases`,
              label: 'Open releases on GitHub',
            }}
          />
        )}

        {state.status === 'none' && (
          <Notice
            tone="info"
            title="No desktop build published yet"
            body="The release list loaded fine — there is simply no desktop-v* release with attached installers. Builds are produced by the desktop-release workflow when a desktop-v* tag is pushed."
            action={{
              href: `https://github.com/${REPO}/releases`,
              label: 'Open releases on GitHub',
            }}
          />
        )}

        {state.status === 'ready' && (
          <>
            <div className="flex flex-wrap items-baseline gap-3 mb-6 pb-4 border-b border-gray-200">
              <span className="text-sm font-semibold text-gray-900">{versionLabel}</span>
              <span className="text-sm text-gray-400">
                released {new Date(state.release.published_at).toLocaleDateString()}
              </span>
            </div>

            {primary && (
              <div className="mb-10">
                <a
                  href={primary.browser_download_url}
                  className="inline-flex w-full sm:w-auto items-center justify-center gap-2 rounded-full bg-gray-900 px-6 py-3.5 text-sm font-semibold text-white shadow-lg transition-all hover:bg-gray-800 active:scale-[0.98]"
                >
                  <DownloadIcon className="h-4 w-4" />
                  Download for {PLATFORMS[detected].label}
                  {primary.arch ? ` (${primary.arch})` : ''}
                </a>
                <p className="mt-2 text-xs text-gray-400">
                  {primary.kind} · {primary.name} · {formatSize(primary.size)}
                </p>
              </div>
            )}

            {Object.entries(PLATFORMS).map(([key, meta]) => {
              const assets = grouped[key] || []
              if (!assets.length) return null
              return (
                <PlatformSection
                  key={key}
                  platform={meta}
                  assets={assets}
                  highlight={detected === key}
                  showMacNote={key === 'mac'}
                />
              )
            })}

            <p className="mt-12 text-sm text-gray-400">
              Source and build instructions live in{' '}
              <a
                className="underline hover:text-gray-600 transition-colors"
                href={`https://github.com/${REPO}/blob/main/desktop/README.md`}
              >
                desktop/README.md
              </a>
              . All installers for this release are also on{' '}
              <a
                className="underline hover:text-gray-600 transition-colors"
                href={state.release.html_url}
              >
                GitHub
              </a>
              .
            </p>
          </>
        )}
      </div>
    </div>
  )
}

function PlatformSection({ platform, assets, highlight, showMacNote }) {
  const Icon = platform.icon
  return (
    <section className="mb-10">
      <div className="flex items-center gap-2 mb-3">
        <Icon className="h-4 w-4 text-gray-500" />
        <h2 className="text-sm font-semibold uppercase tracking-wider text-gray-500">
          {platform.label}
        </h2>
        {highlight && (
          <span className="text-xs font-medium text-gray-900 bg-gray-100 px-2 py-0.5 rounded-full">
            Detected
          </span>
        )}
      </div>

      <div className="space-y-2">
        {assets.map((asset) => (
          <a
            key={asset.id}
            href={asset.browser_download_url}
            className={`flex items-center justify-between gap-4 rounded-xl border px-4 py-3 transition-all ${
              highlight
                ? 'border-gray-900 bg-gray-900 text-white hover:bg-gray-800'
                : 'border-gray-200 bg-white text-gray-900 hover:border-gray-400'
            }`}
          >
            <span className="flex items-center gap-3 min-w-0">
              <Package
                className={`h-4 w-4 shrink-0 ${highlight ? 'text-gray-300' : 'text-gray-400'}`}
              />
              <span className="min-w-0">
                <span className="block text-sm font-medium truncate">
                  {asset.kind}
                  {asset.arch ? ` · ${asset.arch}` : ''}
                </span>
                <span
                  className={`block text-xs truncate ${highlight ? 'text-gray-400' : 'text-gray-400'}`}
                >
                  {asset.name}
                </span>
              </span>
            </span>
            <span className={`text-xs shrink-0 ${highlight ? 'text-gray-300' : 'text-gray-400'}`}>
              {formatSize(asset.size)}
            </span>
          </a>
        ))}
      </div>

      {showMacNote && (
        <p className="mt-3 text-xs text-gray-400 leading-relaxed">
          Builds are unsigned unless signing credentials are configured, so the first
          launch needs <span className="text-gray-600">right-click → Open</span> (or{' '}
          <code className="text-gray-600">
            xattr -dr com.apple.quarantine /Applications/DeepSQL.app
          </code>
          ).
        </p>
      )}
    </section>
  )
}

function Notice({ tone, title, body, action }) {
  return (
    <div
      className={`rounded-xl border px-5 py-4 ${
        tone === 'error' ? 'border-gray-300 bg-gray-50' : 'border-gray-200 bg-gray-50'
      }`}
    >
      <div className="flex items-start gap-3">
        <AlertTriangle className="h-4 w-4 text-gray-500 mt-0.5 shrink-0" />
        <div>
          <p className="text-sm font-semibold text-gray-900 mb-1">{title}</p>
          <p className="text-sm text-gray-500 leading-relaxed">{body}</p>
          {action && (
            <a
              href={action.href}
              className="inline-block mt-3 text-sm font-medium text-gray-900 underline hover:text-gray-600 transition-colors"
            >
              {action.label}
            </a>
          )}
        </div>
      </div>
    </div>
  )
}
