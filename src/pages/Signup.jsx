import { useState, useEffect } from 'react'
import { Link } from 'react-router-dom'
import { setupAPI } from '@/lib/api/client'
import { Mail, User, Database, Building2, ArrowRight, ShieldCheck } from 'lucide-react'

export default function Signup() {
  const [orgName, setOrgName] = useState('')
  const [username, setUsername] = useState('')
  const [email, setEmail] = useState('')
  const [error, setError] = useState('')
  const [loading, setLoading] = useState(false)
  const [alreadySetup, setAlreadySetup] = useState(false)
  const [statusChecked, setStatusChecked] = useState(false)

  useEffect(() => {
    setupAPI.getStatus()
      .then((status) => {
        if (status.setupComplete) {
          setAlreadySetup(true)
        }
      })
      .catch(() => {})
      .finally(() => setStatusChecked(true))
  }, [])

  const startBootstrap = async (event) => {
    event.preventDefault()
    setError('')
    setLoading(true)

    try {
      const response = await setupAPI.createBootstrapLink({
        orgName: orgName.trim(),
        username: username.trim(),
        email: email.trim(),
      })
      if (response.activationUrl) {
        window.location.assign(response.activationUrl)
        return
      }
      throw new Error('Bootstrap link was created, but no activation URL was returned.')
    } catch (err) {
      if (err.status === 409) {
        setAlreadySetup(true)
      } else {
        setError(err.message || 'Could not start secure bootstrap.')
      }
    } finally {
      setLoading(false)
    }
  }

  const leftPanel = (
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
          <span>Secure first-admin bootstrap</span>
        </div>

        <h2 className="text-4xl xl:text-5xl font-extrabold tracking-tight text-gray-900 mb-4 leading-tight">
          Bring up your self-hosted DeepSQL safely
        </h2>

        <p className="text-gray-600 text-lg mb-8 leading-relaxed">
          Standard self-host installs create the first admin directly from the install script. This page remains available only as a localhost compatibility fallback.
        </p>
      </div>
    </div>
  )

  if (!statusChecked) {
    return (
      <div className="flex min-h-screen w-full bg-white text-gray-900 overflow-x-hidden">
        <div className="fixed inset-0 bg-[url('/grid.svg')] opacity-[0.02] pointer-events-none" />
        {leftPanel}
        <div className="w-full lg:w-[55%] xl:w-1/2 flex items-center justify-center bg-white">
          <div className="w-8 h-8 border-2 border-gray-300 border-t-gray-900 rounded-full animate-spin" />
        </div>
      </div>
    )
  }

  if (alreadySetup) {
    return (
      <div className="flex min-h-screen w-full bg-white text-gray-900 overflow-x-hidden">
        <div className="fixed inset-0 bg-[url('/grid.svg')] opacity-[0.02] pointer-events-none" />
        {leftPanel}
        <div className="w-full lg:w-[55%] xl:w-1/2 flex flex-col items-center justify-center px-6 sm:px-8 lg:px-12 py-12 relative bg-white">
          <div className="w-full max-w-sm text-center">
            <div className="w-14 h-14 rounded-full bg-green-100 flex items-center justify-center mx-auto mb-5">
              <Database className="w-7 h-7 text-green-600" />
            </div>
            <h2 className="text-2xl font-bold text-gray-900 mb-2">Already set up</h2>
            <p className="text-gray-500 text-sm mb-6 leading-relaxed">
              This DeepSQL instance already has an activated admin.<br />
              Continue with secure sign-in instead.
            </p>
            <Link
              to="/login"
              className="inline-flex items-center gap-2 px-6 py-3 bg-gray-900 text-white text-sm font-semibold rounded-full hover:bg-gray-800 transition-all"
            >
              Go to Sign In
              <ArrowRight className="w-4 h-4" />
            </Link>
          </div>
          <div className="absolute bottom-6 left-0 right-0 text-center text-gray-500 text-sm px-6">
            &copy; 2026 DeepSQL. Built for developers who love databases.
          </div>
        </div>
      </div>
    )
  }

  return (
    <div className="flex min-h-screen w-full bg-white text-gray-900 overflow-x-hidden">
      <div className="fixed inset-0 bg-[url('/grid.svg')] opacity-[0.02] pointer-events-none" />
      {leftPanel}

      <div className="w-full lg:w-[55%] xl:w-1/2 flex flex-col items-center justify-center px-6 sm:px-8 lg:px-12 py-12 lg:py-16 relative bg-white">
        <div className="w-full max-w-sm">
          <div className="lg:hidden flex items-center gap-2 mb-8">
            <div className="p-2 bg-gray-900 rounded-lg">
              <Database className="w-5 h-5 text-white" />
            </div>
            <span className="text-xl font-bold text-gray-900">DeepSQL</span>
          </div>

          <div className="mb-8">
            <h2 className="text-2xl sm:text-3xl font-bold text-gray-900 tracking-tight mb-2">
              Legacy bootstrap fallback
            </h2>
            <p className="text-gray-600 text-base">
              Prefer the self-host install script for first-admin setup. Use this localhost-only flow only when you need the compatibility bootstrap path.
            </p>
          </div>

          <form onSubmit={startBootstrap} className="space-y-5">
            <div>
              <label className="text-xs font-semibold text-gray-500 uppercase tracking-wider block mb-1.5">
                Organization
              </label>
              <div className="relative group">
                <Building2 className="absolute left-3 top-1/2 -translate-y-1/2 h-5 w-5 text-gray-400 group-focus-within:text-gray-600 transition-colors" />
                <input
                  type="text"
                  value={orgName}
                  onChange={(event) => setOrgName(event.target.value)}
                  className="w-full bg-white border border-gray-300 rounded-xl py-3 pl-10 pr-4 text-gray-900 placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-gray-300 focus:border-gray-400 transition-all"
                  placeholder="Acme Corp"
                />
              </div>
            </div>

            <div>
              <label className="text-xs font-semibold text-gray-500 uppercase tracking-wider block mb-1.5">
                Admin Display Name
              </label>
              <div className="relative group">
                <User className="absolute left-3 top-1/2 -translate-y-1/2 h-5 w-5 text-gray-400 group-focus-within:text-gray-600 transition-colors" />
                <input
                  type="text"
                  required
                  autoFocus
                  value={username}
                  onChange={(event) => setUsername(event.target.value)}
                  className="w-full bg-white border border-gray-300 rounded-xl py-3 pl-10 pr-4 text-gray-900 placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-gray-300 focus:border-gray-400 transition-all"
                  placeholder="admin"
                />
              </div>
            </div>

            <div>
              <label className="text-xs font-semibold text-gray-500 uppercase tracking-wider block mb-1.5">
                Admin Email
              </label>
              <div className="relative group">
                <Mail className="absolute left-3 top-1/2 -translate-y-1/2 h-5 w-5 text-gray-400 group-focus-within:text-gray-600 transition-colors" />
                <input
                  type="email"
                  required
                  value={email}
                  onChange={(event) => setEmail(event.target.value)}
                  className="w-full bg-white border border-gray-300 rounded-xl py-3 pl-10 pr-4 text-gray-900 placeholder-gray-400 focus:outline-none focus:ring-2 focus:ring-gray-300 focus:border-gray-400 transition-all"
                  placeholder="admin@company.com"
                />
              </div>
            </div>

            {error && (
              <div className="p-3 rounded-xl bg-red-50 border border-red-200 text-red-600 text-sm text-center">
                {error}
              </div>
            )}

            <button
              type="submit"
              disabled={loading}
              className="w-full min-h-[48px] bg-gray-900 hover:bg-gray-800 text-white font-semibold py-3 rounded-full shadow-lg transition-all active:scale-[0.98] disabled:opacity-50"
            >
              {loading ? 'Creating fallback link…' : 'Create Fallback Activation Link'}
            </button>

            <p className="text-center text-gray-600 text-sm">
              Already activated this instance?{' '}
              <Link
                to="/login"
                className="text-gray-900 hover:text-gray-700 font-medium transition-colors underline underline-offset-2"
              >
                Sign in
              </Link>
            </p>
          </form>
        </div>

        <div className="absolute bottom-6 left-0 right-0 text-center text-gray-500 text-sm px-6">
          &copy; 2026 DeepSQL. Built for developers who love databases.
        </div>
      </div>
    </div>
  )
}
