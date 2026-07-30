import { useEffect, useMemo, useState } from 'react'
import { useNavigate, useSearchParams } from 'react-router-dom'
import { CheckCircle2, Database, Loader2, ShieldCheck } from 'lucide-react'
import { authAPI } from '@/lib/api/client'
import { useAuth } from '@/hooks/useAuth'

export default function ActivateInvite() {
  const navigate = useNavigate()
  const { login } = useAuth()
  const [searchParams] = useSearchParams()
  const token = searchParams.get('token') || ''

  const [preview, setPreview] = useState(null)
  const [loadingPreview, setLoadingPreview] = useState(true)
  const [activating, setActivating] = useState(false)
  const [error, setError] = useState('')
  const [username, setUsername] = useState('')

  useEffect(() => {
    let cancelled = false

    const loadPreview = async () => {
      if (!token) {
        setError('Activation token is missing or invalid.')
        setLoadingPreview(false)
        return
      }

      try {
        const response = await authAPI.previewInvite(token)
        if (cancelled) return
        setPreview(response)
        setUsername(response.username || '')
      } catch (err) {
        if (cancelled) return
        setError(err.message || 'This activation link is invalid or has expired.')
      } finally {
        if (!cancelled) {
          setLoadingPreview(false)
        }
      }
    }

    loadPreview()

    return () => {
      cancelled = true
    }
  }, [token])

  const subtitle = useMemo(() => {
    if (!preview) return 'Secure activation verifies your email ownership before DeepSQL grants access.'
    if (preview.inviteType === 'BOOTSTRAP') {
      return 'You are activating the first administrator for this DeepSQL instance.'
    }
    return `You were invited as ${preview.role?.toLowerCase() || 'a user'} to this DeepSQL workspace.`
  }, [preview])

  const completeActivation = async (event) => {
    event.preventDefault()
    setActivating(true)
    setError('')

    try {
      const response = await authAPI.acceptInvite({
        token,
        username: username.trim(),
      })

      if (response.mfaRequired) {
        const params = new URLSearchParams({
          challengeId: response.challengeId,
          mfaRequired: 'true',
          mfaSetupRequired: String(response.mfaSetupRequired),
          email: response.email || preview?.email || '',
        })
        navigate(`/login?${params.toString()}`, { replace: true })
        return
      }

      if (response.username && response.role) {
        login(response)
        return
      }

      navigate('/login', { replace: true })
    } catch (err) {
      setError(err.message || 'Could not activate this invite.')
    } finally {
      setActivating(false)
    }
  }

  return (
    <div className="flex min-h-screen w-full bg-white text-gray-900 overflow-x-hidden">
      <div className="fixed inset-0 bg-[url('/grid.svg')] opacity-[0.02] pointer-events-none" />

      <div className="hidden lg:flex lg:w-[45%] xl:w-1/2 relative items-center justify-center border-r border-gray-200 bg-gray-50/50">
        <div className="relative z-10 max-w-lg px-10 xl:px-14">
          <div className="flex items-center gap-3 mb-8">
            <div className="p-2 bg-gray-900 rounded-lg">
              <Database className="w-6 h-6 text-white" />
            </div>
            <span className="text-2xl font-bold text-gray-900">DeepSQL</span>
          </div>

          <div className="inline-flex items-center gap-2 px-4 py-2 bg-gray-100 border border-gray-300 rounded-full text-sm text-gray-700 mb-6">
            <ShieldCheck className="w-4 h-4 flex-shrink-0" />
            <span>Verified invite activation</span>
          </div>

          <h2 className="text-4xl xl:text-5xl font-extrabold tracking-tight text-gray-900 mb-4 leading-tight">
            Activate your secure DeepSQL access
          </h2>

          <p className="text-gray-600 text-lg leading-relaxed">
            Activation links are single-use and time-bound. Once accepted, DeepSQL immediately moves you into the protected sign-in flow for your role.
          </p>
        </div>
      </div>

      <div className="w-full lg:w-[55%] xl:w-1/2 flex flex-col items-center justify-center px-6 sm:px-8 lg:px-12 py-12 lg:py-16 relative bg-white">
        <div className="w-full max-w-sm">
          <div className="lg:hidden flex items-center gap-2 mb-8">
            <div className="p-2 bg-gray-900 rounded-lg">
              <Database className="w-5 h-5 text-white" />
            </div>
            <span className="text-xl font-bold text-gray-900">DeepSQL</span>
          </div>

          {loadingPreview ? (
            <div className="rounded-2xl border border-gray-200 p-10 text-center">
              <Loader2 className="w-8 h-8 animate-spin text-gray-500 mx-auto mb-4" />
              <h2 className="text-xl font-semibold text-gray-900 mb-2">Checking your invite</h2>
              <p className="text-sm text-gray-500">We’re validating this activation link now.</p>
            </div>
          ) : (
            <div className="space-y-6">
              <div>
                <h2 className="text-2xl sm:text-3xl font-bold text-gray-900 tracking-tight mb-2">
                  Activate your account
                </h2>
                <p className="text-gray-600 text-base">
                  {subtitle}
                </p>
              </div>

              {preview && (
                <div className="rounded-2xl border border-gray-200 bg-gray-50 p-4 space-y-2 text-sm">
                  <div className="flex items-center justify-between gap-4">
                    <span className="text-gray-500">Email</span>
                    <span className="font-medium text-gray-900 text-right">{preview.email}</span>
                  </div>
                  <div className="flex items-center justify-between gap-4">
                    <span className="text-gray-500">Role</span>
                    <span className="font-medium text-gray-900">{preview.role}</span>
                  </div>
                  <div className="flex items-center justify-between gap-4">
                    <span className="text-gray-500">Activation type</span>
                    <span className="font-medium text-gray-900">{preview.inviteType}</span>
                  </div>
                </div>
              )}

              {error && (
                <div className="p-3 rounded-xl bg-red-50 border border-red-200 text-red-600 text-sm">
                  {error}
                </div>
              )}

              {preview && (
                <form onSubmit={completeActivation} className="space-y-4">
                  <div>
                    <label className="text-xs font-semibold text-gray-500 uppercase tracking-wider block mb-1.5">
                      Display Name
                    </label>
                    <input
                      type="text"
                      required
                      value={username}
                      onChange={(event) => setUsername(event.target.value)}
                      className="w-full bg-white border border-gray-300 rounded-xl py-3 px-4 text-gray-900 placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-gray-300 focus:border-gray-400 transition-all"
                      placeholder="Choose a display name"
                    />
                  </div>

                  <button
                    type="submit"
                    disabled={activating}
                    className="w-full min-h-[48px] bg-gray-900 hover:bg-gray-800 text-white font-semibold py-3 rounded-full shadow-lg transition-all disabled:opacity-50"
                  >
                    {activating ? 'Activating access…' : 'Activate Secure Access'}
                  </button>

                  <div className="flex items-center justify-center gap-2 text-xs text-gray-500">
                    <CheckCircle2 className="w-4 h-4" />
                    <span>Single-use activation. Admins will continue into MFA setup automatically.</span>
                  </div>
                </form>
              )}
            </div>
          )}
        </div>

        <div className="absolute bottom-6 left-0 right-0 text-center text-gray-500 text-sm px-6">
          &copy; 2026 DeepSQL. Built for developers who love databases.
        </div>
      </div>
    </div>
  )
}
