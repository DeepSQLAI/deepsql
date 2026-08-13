import { useEffect, useMemo, useState } from 'react'
import { Link, useSearchParams } from 'react-router-dom'
import { useAuth } from '@/hooks/useAuth'
import { authAPI, setupAPI } from '@/lib/api/client'
import { ArrowLeft, Database, KeyRound, Mail, ShieldCheck, Sparkles, Zap, Activity, LineChart } from 'lucide-react'

const STEP_LOGIN = 'login'
const STEP_OTP = 'otp'

export default function Login() {
  const { login } = useAuth()
  const [searchParams] = useSearchParams()

  const initialChallengeId = searchParams.get('challengeId') || ''
  const initialEmail = searchParams.get('email') || ''
  const initialError = searchParams.get('error') || ''

  const initialStep = useMemo(() => (
    initialChallengeId ? STEP_OTP : STEP_LOGIN
  ), [initialChallengeId])

  const [step, setStep] = useState(initialStep)
  const [email, setEmail] = useState(initialEmail)
  const [password, setPassword] = useState('')
  const [otp, setOtp] = useState('')
  const [challengeId, setChallengeId] = useState(initialChallengeId)
  const [error, setError] = useState(initialError)
  const [info, setInfo] = useState('')
  const [loading, setLoading] = useState(false)
  const [setupStatus, setSetupStatus] = useState(null)

  useEffect(() => {
    if (initialError) {
      setError(initialError)
    }
  }, [initialError])

  // Public endpoint — no auth required. Lets a returning admin who never
  // finished the wizard jump straight back into it instead of guessing why
  // the dashboard looks empty after login.
  useEffect(() => {
    setupAPI.getStatus().then(setSetupStatus).catch(() => {})
  }, [])

  const handleAuthenticated = (payload) => {
    setError('')
    setInfo('')
    login(payload)
  }

  const handlePasswordLogin = async (event) => {
    event.preventDefault()
    setLoading(true)
    setError('')
    setInfo('')
    try {
      const response = await authAPI.login({ email, password })
      if (response.challengeId) {
        setChallengeId(response.challengeId)
        setStep(STEP_OTP)
        setInfo(response.message || `Enter the verification code we sent to ${email}.`)
        return
      }
      handleAuthenticated(response)
    } catch (err) {
      setError(err.message || 'Invalid email or password.')
    } finally {
      setLoading(false)
    }
  }

  const handleVerifyOtp = async (event) => {
    event.preventDefault()
    setLoading(true)
    setError('')
    setInfo('')
    try {
      const response = await authAPI.verifyEmailOtp({ challengeId, otp })
      handleAuthenticated(response)
    } catch (err) {
      setError(err.message || 'Invalid or expired verification code.')
    } finally {
      setLoading(false)
    }
  }

  const handleResendCode = async () => {
    setLoading(true)
    setError('')
    try {
      const response = await authAPI.startEmailLogin(challengeId)
      setInfo(response.message || `A new verification code was sent to ${email}.`)
    } catch (err) {
      setError(err.message || 'Could not resend verification code.')
    } finally {
      setLoading(false)
    }
  }


  const renderLoginStep = () => (
    <form onSubmit={handlePasswordLogin} className="space-y-5">
      <div>
        <label htmlFor="email" className="text-xs font-semibold text-gray-500 uppercase tracking-wider block mb-1.5">
          Email
        </label>
        <div className="relative group">
          <Mail className="absolute left-3 top-1/2 -translate-y-1/2 h-5 w-5 text-gray-400 group-focus-within:text-gray-600 transition-colors" />
          <input
            id="email"
            name="email"
            type="email"
            required
            autoFocus
            value={email}
            onChange={(event) => setEmail(event.target.value)}
            className="w-full bg-white border border-gray-300 rounded-xl py-3 pl-10 pr-4 text-gray-900 placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-gray-300 focus:border-gray-400 transition-all"
            placeholder="you@company.com"
          />
        </div>
      </div>

      <div>
        <label htmlFor="password" className="text-xs font-semibold text-gray-500 uppercase tracking-wider block mb-1.5">
          Password
        </label>
        <div className="relative group">
          <KeyRound className="absolute left-3 top-1/2 -translate-y-1/2 h-5 w-5 text-gray-400 group-focus-within:text-gray-600 transition-colors" />
          <input
            id="password"
            name="password"
            type="password"
            required
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            className="w-full bg-white border border-gray-300 rounded-xl py-3 pl-10 pr-4 text-gray-900 placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-gray-300 focus:border-gray-400 transition-all"
            placeholder="Enter your password"
          />
        </div>
      </div>

      <button
        type="submit"
        disabled={loading}
        className="w-full min-h-[48px] bg-gray-900 hover:bg-gray-800 text-white font-semibold py-3 rounded-full shadow-lg transition-all active:scale-[0.98] disabled:opacity-50 disabled:cursor-not-allowed disabled:active:scale-100"
      >
        {loading ? 'Signing in…' : 'Sign In'}
      </button>
    </form>
  )

  const renderOtpStep = () => (
    <form onSubmit={handleVerifyOtp} className="space-y-5">
      <button
        type="button"
        onClick={() => {
          setStep(STEP_LOGIN)
          setOtp('')
          setChallengeId('')
          setError('')
          setInfo('')
        }}
        className="flex items-center gap-1 text-sm text-gray-500 hover:text-gray-700 transition-colors"
      >
        <ArrowLeft className="w-4 h-4" />
        Back to password sign-in
      </button>

      <div className="rounded-2xl border border-gray-200 bg-gray-50 p-4 text-sm text-gray-600">
        Workspace email 2FA is enabled. Enter the verification code we sent to <span className="font-medium text-gray-900">{email || 'your inbox'}</span>.
      </div>

      <div>
        <label htmlFor="otp" className="text-xs font-semibold text-gray-500 uppercase tracking-wider block mb-1.5">
          Email Verification Code
        </label>
        <input
          id="otp"
          name="otp"
          type="text"
          inputMode="numeric"
          required
          autoFocus
          maxLength={6}
          value={otp}
          onChange={(event) => setOtp(event.target.value.replace(/\D/g, '').slice(0, 6))}
          className="w-full bg-white border border-gray-300 rounded-xl py-3 px-4 text-gray-900 text-center tracking-[0.5em] placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-gray-300 focus:border-gray-400 transition-all"
          placeholder="000000"
        />
      </div>

      <button
        type="submit"
        disabled={loading}
        className="w-full min-h-[48px] bg-gray-900 hover:bg-gray-800 text-white font-semibold py-3 rounded-full shadow-lg transition-all active:scale-[0.98] disabled:opacity-50"
      >
        {loading ? 'Verifying…' : 'Verify Code'}
      </button>

      <button
        type="button"
        disabled={loading || !challengeId}
        onClick={handleResendCode}
        className="w-full min-h-[44px] border border-gray-300 hover:border-gray-400 text-gray-700 font-medium py-3 rounded-full transition-all disabled:opacity-50"
      >
        Resend Code
      </button>
    </form>
  )

  const stepTitle = step === STEP_OTP ? 'Verify your sign-in' : 'Secure sign-in'
  const stepSubtitle = step === STEP_OTP
    ? 'Complete the extra email verification step for this workspace.'
    : 'Sign in with your email and password to access DeepSQL.'

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
            <Sparkles className="w-4 h-4 flex-shrink-0" />
            <span>The World&apos;s Most Advanced DBA Agent</span>
          </div>

          <h2 className="text-4xl xl:text-5xl font-extrabold tracking-tight text-gray-900 mb-4 leading-tight">
            An AI agent for your SQL databases
          </h2>

          <p className="text-gray-600 text-lg mb-8 leading-relaxed">
            The agentic way to query, monitor, and visualize your databases.
          </p>

          <div className="space-y-3">
            {[
              { icon: Zap, text: 'Context-aware SQL from plain English' },
              { icon: Activity, text: 'Real-time workload & performance insights' },
              { icon: LineChart, text: 'AI-built, shareable BI dashboards' },
            ].map((item, i) => (
              <div key={i} className="flex items-center gap-3 text-gray-600">
                <item.icon className="w-4 h-4 flex-shrink-0 text-gray-400" />
                <span className="text-[15px]">{item.text}</span>
              </div>
            ))}
          </div>
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

          <div className="mb-8">
            <div className="inline-flex items-center gap-2 px-3 py-1 rounded-full bg-gray-100 text-gray-700 text-xs font-semibold uppercase tracking-wider mb-4">
              {step === STEP_OTP ? <ShieldCheck className="w-3.5 h-3.5" /> : <KeyRound className="w-3.5 h-3.5" />}
              Protected Access
            </div>
            <h2 className="text-2xl sm:text-3xl font-bold text-gray-900 tracking-tight mb-2">
              {stepTitle}
            </h2>
            <p className="text-gray-600 text-base">
              {stepSubtitle}
            </p>
          </div>

          {info && (
            <div className="mb-4 p-3 rounded-xl bg-blue-50 border border-blue-200 text-blue-700 text-sm">
              {info}
            </div>
          )}

          {error && (
            <div className="mb-4 p-3 rounded-xl bg-red-50 border border-red-200 text-red-600 text-sm">
              {error}
            </div>
          )}

          {step === STEP_OTP ? renderOtpStep() : renderLoginStep()}

          {step === STEP_LOGIN && setupStatus && setupStatus.hasConnections === false && (
            <p className="mt-5 text-center text-sm text-gray-500">
              No database connected yet.{' '}
              <Link to="/onboarding" className="text-gray-900 hover:text-gray-700 font-medium underline underline-offset-2">
                Finish setup
              </Link>
            </p>
          )}
        </div>

        <div className="absolute bottom-6 left-0 right-0 text-center text-gray-500 text-sm px-6">
          &copy; 2026 DeepSQL. Built for developers who love databases.
        </div>
      </div>
    </div>
  )
}
