import { useCallback, useEffect, useRef, useState } from 'react'
import { useParams } from 'react-router-dom'
import { Lock, Loader2, LineChart } from 'lucide-react'
import { publicDashboardAPI } from '@/lib/api/client'
import DashboardViewer from '@/components/DashboardViewer'

// Public, no-login dashboard view. The token is the authorization; if the owner
// set a password, we gate on it here and carry it on every data request.
export default function PublicDashboardPage() {
  const { token } = useParams()
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
      if (e.requiresPassword) { setPhase('gate'); if (pw) setPwError('Incorrect password') }
      else { setError(e.message || 'Unable to load this dashboard.'); setPhase('error') }
    }
  }, [token])

  useEffect(() => { attempt('') }, [attempt])

  // Stable callbacks so DashboardViewer's load effect doesn't loop.
  const load = useCallback(() => Promise.resolve(dataRef.current), [])
  const queryFn = useCallback((sql, limit, signal) => publicDashboardAPI.query(token, sql, limit, signal, pwRef.current), [token])

  if (phase === 'ready') return <DashboardViewer load={load} queryFn={queryFn} />

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
