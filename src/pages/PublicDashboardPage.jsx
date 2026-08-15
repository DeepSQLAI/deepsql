import { useCallback, useEffect, useRef, useState } from 'react'
import { useParams, useSearchParams } from 'react-router-dom'
import { Lock, Loader2, LineChart } from 'lucide-react'
import { publicDashboardAPI } from '@/lib/api/client'
import DashboardViewer from '@/components/DashboardViewer'

const MIN_KIOSK_REFRESH_MS = 10_000 // a wall display refreshing faster than this just hammers the backend for no visible benefit
const DEFAULT_KIOSK_REFRESH_MS = 60_000
const DEFAULT_ADVANCE_MS = 30_000

// Public, no-login dashboard view. The token is the authorization; if the owner
// set a password, we gate on it here and carry it on every data request.
//
// Kiosk mode (for a wall-mounted display cycling a team's dashboards, unattended):
//   ?kiosk=1                    strip all chrome (header, password-gate styling)
//   &refresh=<seconds>          re-run queries on this cadence (default 60s, floor 10s)
//   &tokens=tokA,tokB,tokC       cycle through these share tokens (this page's own
//                                 :token param is treated as the first slide)
//   &advance=<seconds>          dwell time per dashboard when cycling (default 30s)
// A password-protected dashboard can't be included in a token cycle — kiosk mode
// has no UI to type one in, so it skips straight to the error state for that slide
// rather than hanging on a gate no one is there to answer.
export default function PublicDashboardPage() {
  const { token: routeToken } = useParams()
  const [searchParams] = useSearchParams()
  const kiosk = searchParams.get('kiosk') === '1'
  const cycleTokens = (searchParams.get('tokens') || '').split(',').map((t) => t.trim()).filter(Boolean)
  const tokens = cycleTokens.length ? [routeToken, ...cycleTokens.filter((t) => t !== routeToken)] : [routeToken]
  const advanceMs = Math.max(5_000, (Number(searchParams.get('advance')) || DEFAULT_ADVANCE_MS / 1000) * 1000)
  const refreshMs = kiosk
    ? Math.max(MIN_KIOSK_REFRESH_MS, (Number(searchParams.get('refresh')) || DEFAULT_KIOSK_REFRESH_MS / 1000) * 1000)
    : undefined

  const [slideIdx, setSlideIdx] = useState(0)
  const token = tokens[slideIdx % tokens.length]

  const [phase, setPhase] = useState('loading') // loading | gate | ready | error
  const [error, setError] = useState(null)
  const [pwError, setPwError] = useState(null)
  const [pwInput, setPwInput] = useState('')
  const dataRef = useRef(null)
  const pwRef = useRef('')

  const attempt = useCallback(async (pw) => {
    setPhase('loading'); setPwError(null)
    try {
      const d = await publicDashboardAPI.get(token, pw)
      dataRef.current = d; pwRef.current = pw || ''
      setPhase('ready')
    } catch (e) {
      if (e.requiresPassword) {
        // Kiosk mode has no one present to type a password — advance past this
        // slide instead of parking on a gate that will never be answered.
        if (kiosk && tokens.length > 1) { setError('This dashboard is password protected — skipped.'); setPhase('error'); return }
        setPhase('gate'); if (pw) setPwError('Incorrect password')
      } else { setError(e.message || 'Unable to load this dashboard.'); setPhase('error') }
    }
  }, [token, kiosk, tokens.length])

  useEffect(() => { attempt('') }, [attempt])

  // Auto-advance to the next token after its dwell time — only when actually
  // cycling multiple dashboards, and paused while a slide is still loading/gated
  // so a slow or password-blocked one doesn't get cut off mid-attempt.
  useEffect(() => {
    if (tokens.length <= 1) return undefined
    if (phase !== 'ready' && phase !== 'error') return undefined
    const id = setTimeout(() => setSlideIdx((i) => (i + 1) % tokens.length), advanceMs)
    return () => clearTimeout(id)
  }, [tokens.length, advanceMs, phase, slideIdx])

  // Stable callbacks so DashboardViewer's load effect doesn't loop.
  const load = useCallback(() => Promise.resolve(dataRef.current), [])
  const queryFn = useCallback((sql, limit, signal) => publicDashboardAPI.query(token, sql, limit, signal, pwRef.current), [token])

  if (phase === 'ready') return <DashboardViewer load={load} queryFn={queryFn} autoRefreshMs={refreshMs} hideChrome={kiosk} />

  return (
    <div style={{ minHeight: '100vh', background: '#f8fafc', display: 'flex', alignItems: 'center', justifyContent: 'center', fontFamily: "'Maven Pro', system-ui, sans-serif" }}>
      {phase === 'loading' && (
        <div style={{ color: '#5b616e', display: 'flex', alignItems: 'center', gap: 8 }}>
          <Loader2 size={16} style={{ animation: 'spin 1s linear infinite' }} /> Loading…
          <style>{'@keyframes spin{to{transform:rotate(360deg)}}'}</style>
        </div>
      )}
      {phase === 'error' && <div style={{ color: '#6b7280', fontSize: 15 }}>{error}</div>}
      {phase === 'gate' && (
        <form
          onSubmit={(e) => { e.preventDefault(); if (pwInput) attempt(pwInput) }}
          style={{ background: '#fff', border: '1px solid #e7e9ee', borderRadius: 14, padding: 28, width: 340, boxShadow: '0 8px 30px rgba(17,19,24,.08)' }}
        >
          <div style={{ display: 'inline-flex', width: 40, height: 40, borderRadius: 10, background: '#f3f4f6', alignItems: 'center', justifyContent: 'center', marginBottom: 14 }}>
            <Lock size={18} color="#111318" />
          </div>
          <div style={{ fontWeight: 700, fontSize: 17, color: '#111318', marginBottom: 4 }}>Password required</div>
          <div style={{ fontSize: 13, color: '#8b909b', marginBottom: 16 }}>This dashboard is password protected.</div>
          <input
            type="password"
            autoFocus
            value={pwInput}
            onChange={(e) => setPwInput(e.target.value)}
            placeholder="Enter password"
            style={{ width: '100%', padding: '10px 12px', border: `1px solid ${pwError ? '#f0c8c8' : '#e5e7eb'}`, borderRadius: 9, fontSize: 14, marginBottom: pwError ? 6 : 14, boxSizing: 'border-box' }}
          />
          {pwError && <div style={{ color: '#b91c1c', fontSize: 12, marginBottom: 14 }}>{pwError}</div>}
          <button type="submit" disabled={!pwInput} style={{ width: '100%', padding: '10px', border: 'none', borderRadius: 9, background: '#111318', color: '#fff', fontSize: 14, fontWeight: 600, cursor: pwInput ? 'pointer' : 'default', opacity: pwInput ? 1 : 0.5 }}>
            View dashboard
          </button>
          <div style={{ display: 'flex', alignItems: 'center', gap: 6, justifyContent: 'center', marginTop: 16, color: '#c3c6cd', fontSize: 12 }}>
            <LineChart size={12} /> Powered by DeepSQL
          </div>
        </form>
      )}
    </div>
  )
}
