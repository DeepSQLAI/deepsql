import { useEffect, useState } from 'react'
import { useNavigate, useSearchParams, useLocation } from 'react-router-dom'
import { CheckCircle2, Loader2, ShieldCheck, Terminal, AlertTriangle } from 'lucide-react'
import { cliAuthAPI } from '@/lib/api/client'
import { useAuth } from '@/hooks/useAuth'

export default function CliAuthorize() {
  const location = useLocation()
  const isDeviceFlow = location.pathname.endsWith('/device')
  return isDeviceFlow ? <DeviceFlow /> : <BrowserFlow />
}

function BrowserFlow() {
  const navigate = useNavigate()
  const { isAuthenticated, isChecking } = useAuth()
  const [searchParams] = useSearchParams()
  const authorizationId = searchParams.get('id') || ''

  const [pending, setPending] = useState(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [done, setDone] = useState(false)

  useEffect(() => {
    if (isChecking) return
    if (!isAuthenticated) {
      const next = encodeURIComponent(`/cli-authorize?id=${authorizationId}`)
      navigate(`/login?next=${next}`, { replace: true })
      return
    }
    if (!authorizationId) {
      setError('Authorization ID is missing.')
      setLoading(false)
      return
    }

    let cancelled = false
    cliAuthAPI
      .getPending(authorizationId)
      .then((data) => {
        if (cancelled) return
        setPending(data)
      })
      .catch((err) => {
        if (cancelled) return
        setError(err.message || 'This CLI authorization link is invalid or expired.')
      })
      .finally(() => {
        if (!cancelled) setLoading(false)
      })
    return () => {
      cancelled = true
    }
  }, [isAuthenticated, isChecking, authorizationId, navigate])

  const onApprove = async () => {
    setSubmitting(true)
    setError('')
    try {
      const result = await cliAuthAPI.approve(authorizationId)
      const url = new URL(result.redirect_uri)
      url.searchParams.set('code', result.code)
      if (result.state) url.searchParams.set('state', result.state)
      // Redirect into the loopback URL — the CLI is listening on that port.
      window.location.replace(url.toString())
      setDone(true)
    } catch (err) {
      setError(err.message || 'Failed to approve CLI authorization.')
      setSubmitting(false)
    }
  }

  const onDeny = async () => {
    setSubmitting(true)
    try {
      await cliAuthAPI.deny(authorizationId)
    } catch {
      // Best effort — denial is local even if the server roundtrip fails.
    }
    navigate('/dashboard', { replace: true })
  }

  return (
    <ApprovalChrome>
      {loading ? (
        <Loading />
      ) : error ? (
        <ErrorPanel message={error} />
      ) : done ? (
        <SuccessPanel message="Returning you to your CLI…" />
      ) : (
        <ApprovalCard
          title="Authorize CLI access"
          subtitle={`A DeepSQL CLI is requesting access to mint a personal token.`}
          rows={[
            ['Hostname', pending?.hostname || 'unknown'],
            ['Client', pending?.client_label || 'deepsql'],
          ]}
          onApprove={onApprove}
          onDeny={onDeny}
          submitting={submitting}
        />
      )}
    </ApprovalChrome>
  )
}

function DeviceFlow() {
  const navigate = useNavigate()
  const { isAuthenticated, isChecking } = useAuth()
  const [userCode, setUserCode] = useState('')
  const [pending, setPending] = useState(null)
  const [loading, setLoading] = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')
  const [done, setDone] = useState(false)

  useEffect(() => {
    if (isChecking) return
    if (!isAuthenticated) {
      navigate(`/login?next=${encodeURIComponent('/cli-authorize/device')}`, { replace: true })
    }
  }, [isAuthenticated, isChecking, navigate])

  const onLookup = async (event) => {
    event.preventDefault()
    setLoading(true)
    setError('')
    setPending(null)
    try {
      const data = await cliAuthAPI.lookupDevice(userCode)
      setPending(data)
    } catch (err) {
      setError(err.message || 'Code not found or expired.')
    } finally {
      setLoading(false)
    }
  }

  const onApprove = async () => {
    setSubmitting(true)
    setError('')
    try {
      await cliAuthAPI.approveDevice(userCode)
      setDone(true)
    } catch (err) {
      setError(err.message || 'Failed to approve.')
      setSubmitting(false)
    }
  }

  const onDeny = async () => {
    setSubmitting(true)
    try {
      await cliAuthAPI.denyDevice(userCode)
    } catch {
      // Best effort.
    }
    navigate('/dashboard', { replace: true })
  }

  return (
    <ApprovalChrome>
      {done ? (
        <SuccessPanel message="The CLI on the other device has been authorized." />
      ) : pending ? (
        <ApprovalCard
          title="Authorize CLI access"
          subtitle={`Confirm this DeepSQL CLI request from another device.`}
          rows={[
            ['Hostname', pending.hostname || 'unknown'],
            ['Client', pending.client_label || 'deepsql'],
            ['Code', userCode.toUpperCase()],
          ]}
          onApprove={onApprove}
          onDeny={onDeny}
          submitting={submitting}
        />
      ) : (
        <form onSubmit={onLookup} className="space-y-4">
          <div>
            <h2 className="text-2xl font-bold text-gray-900 mb-1.5">Activate a DeepSQL CLI</h2>
            <p className="text-gray-600 text-base">
              Enter the code displayed by your terminal. Codes look like{' '}
              <code className="font-mono text-sm">XXXX-YYYY</code> and expire after 15 minutes.
            </p>
          </div>
          <input
            type="text"
            required
            value={userCode}
            onChange={(e) => setUserCode(e.target.value)}
            placeholder="XXXX-YYYY"
            className="w-full bg-white border border-gray-300 rounded-xl py-3 px-4 text-gray-900 font-mono uppercase tracking-wider focus:outline-none focus:ring-2 focus:ring-gray-300"
            autoFocus
          />
          {error && <ErrorPanel message={error} inline />}
          <button
            type="submit"
            disabled={loading || !userCode.trim()}
            className="w-full min-h-[48px] bg-gray-900 hover:bg-gray-800 text-white font-semibold py-3 rounded-full disabled:opacity-50"
          >
            {loading ? 'Looking up…' : 'Continue'}
          </button>
        </form>
      )}
    </ApprovalChrome>
  )
}

function ApprovalChrome({ children }) {
  return (
    <div className="flex min-h-screen w-full bg-white text-gray-900 items-center justify-center px-6">
      <div className="w-full max-w-md">
        <div className="flex items-center gap-3 mb-8">
          <div className="p-2 bg-gray-900 rounded-lg">
            <Terminal className="w-5 h-5 text-white" />
          </div>
          <span className="text-xl font-bold">DeepSQL CLI</span>
        </div>
        <div className="inline-flex items-center gap-2 px-3 py-1.5 bg-gray-100 border border-gray-200 rounded-full text-xs text-gray-600 mb-6">
          <ShieldCheck className="w-3.5 h-3.5" />
          <span>Verify before approving — anyone could send you this link.</span>
        </div>
        {children}
      </div>
    </div>
  )
}

function ApprovalCard({ title, subtitle, rows, onApprove, onDeny, submitting }) {
  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-2xl font-bold text-gray-900 mb-1.5">{title}</h2>
        <p className="text-gray-600 text-base">{subtitle}</p>
      </div>
      <div className="rounded-2xl border border-gray-200 bg-gray-50 p-4 space-y-2 text-sm">
        {rows.map(([label, value]) => (
          <div key={label} className="flex items-center justify-between gap-4">
            <span className="text-gray-500">{label}</span>
            <span className="font-medium text-gray-900 text-right truncate max-w-[60%]">{value}</span>
          </div>
        ))}
      </div>
      <div className="flex gap-3">
        <button
          type="button"
          onClick={onDeny}
          disabled={submitting}
          className="flex-1 min-h-[48px] bg-white border border-gray-300 hover:bg-gray-50 text-gray-900 font-semibold py-3 rounded-full disabled:opacity-50"
        >
          Deny
        </button>
        <button
          type="button"
          onClick={onApprove}
          disabled={submitting}
          className="flex-1 min-h-[48px] bg-gray-900 hover:bg-gray-800 text-white font-semibold py-3 rounded-full disabled:opacity-50"
        >
          {submitting ? 'Approving…' : 'Approve'}
        </button>
      </div>
      <div className="flex items-center justify-center gap-2 text-xs text-gray-500">
        <CheckCircle2 className="w-4 h-4" />
        <span>Approving issues a personal access token bound to your account.</span>
      </div>
    </div>
  )
}

function Loading() {
  return (
    <div className="rounded-2xl border border-gray-200 p-10 text-center">
      <Loader2 className="w-8 h-8 animate-spin text-gray-500 mx-auto mb-4" />
      <p className="text-sm text-gray-500">Loading authorization request…</p>
    </div>
  )
}

function ErrorPanel({ message, inline }) {
  return (
    <div
      className={
        (inline ? '' : 'rounded-2xl border border-red-200 ') +
        'p-3 rounded-xl bg-red-50 text-red-700 text-sm flex items-start gap-2'
      }
    >
      <AlertTriangle className="w-4 h-4 mt-0.5 flex-shrink-0" />
      <span>{message}</span>
    </div>
  )
}

function SuccessPanel({ message }) {
  return (
    <div className="rounded-2xl border border-gray-200 p-10 text-center space-y-3">
      <CheckCircle2 className="w-10 h-10 text-emerald-500 mx-auto" />
      <h2 className="text-xl font-semibold text-gray-900">Approved</h2>
      <p className="text-sm text-gray-500">{message}</p>
    </div>
  )
}
