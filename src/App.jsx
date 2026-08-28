import { Routes, Route, Navigate } from 'react-router-dom'
import { AuthProvider, useAuth } from './hooks/useAuth'
import Home from './pages/Home'
import Login from './pages/Login'
import Signup from './pages/Signup'
import ActivateInvite from './pages/ActivateInvite'
import CliAuthorize from './pages/CliAuthorize'
import Download from './pages/Download'
import Onboarding from './pages/Onboarding'
import PublicDashboardPage from './pages/PublicDashboardPage'
import SharedDashboardPage from './pages/SharedDashboardPage'
import { Component } from 'react'

class ErrorBoundary extends Component {
  constructor(props) {
    super(props)
    this.state = { hasError: false, error: null }
  }

  static getDerivedStateFromError(error) {
    return { hasError: true, error }
  }

  componentDidCatch(error, errorInfo) {
    console.error('[ErrorBoundary] Caught error:', error, errorInfo)
  }

  render() {
    if (this.state.hasError) {
      return (
        <div style={{ padding: 40, fontFamily: 'sans-serif' }}>
          <h1 style={{ color: '#dc2626' }}>Something went wrong</h1>
          <pre style={{ background: '#f3f4f6', padding: 20, borderRadius: 8, overflow: 'auto' }}>
            {this.state.error?.toString()}
          </pre>
          <button
            onClick={() => window.location.reload()}
            style={{
              marginTop: 20,
              padding: '10px 20px',
              background: '#000',
              color: '#fff',
              border: 'none',
              borderRadius: 8,
              cursor: 'pointer',
            }}
          >
            Reload Page
          </button>
        </div>
      )
    }
    return this.props.children
  }
}

function ProtectedRoute({ children }) {
  const { isAuthenticated, isChecking } = useAuth()

  if (isChecking) {
    return (
      <div className="flex items-center justify-center h-screen w-screen bg-gray-50">
        <div className="text-center">
          <div className="w-10 h-10 border-2 border-gray-900 border-t-transparent rounded-full animate-spin mx-auto mb-3" />
          <p className="text-sm text-gray-500">Loading…</p>
        </div>
      </div>
    )
  }

  return isAuthenticated ? children : <Navigate to="/login" replace />
}

function PublicRoute({ children }) {
  const { isAuthenticated, isChecking } = useAuth()

  if (isChecking) return null

  return isAuthenticated ? <Navigate to="/dashboard" replace /> : children
}

function App() {
  console.log('[App] Rendering')
  return (
    <ErrorBoundary>
      <AuthProvider>
        <Routes>
          <Route
            path="/"
            element={
              <PublicRoute>
                <Navigate to="/login" replace />
              </PublicRoute>
            }
          />
          <Route
            path="/login"
            element={
              <PublicRoute>
                <Login />
              </PublicRoute>
            }
          />
          <Route
            path="/signup"
            element={
              <PublicRoute>
                <Signup />
              </PublicRoute>
            }
          />
          <Route
            path="/activate"
            element={
              <PublicRoute>
                <ActivateInvite />
              </PublicRoute>
            }
          />
          <Route
            path="/dashboard"
            element={
              <ProtectedRoute>
                <Home />
              </ProtectedRoute>
            }
          />
          {/* First-run setup wizard — add a connection, configure the LLM, kick Brain init. */}
          <Route
            path="/onboarding"
            element={
              <ProtectedRoute>
                <Onboarding />
              </ProtectedRoute>
            }
          />
          {/* Public shared dashboard — no login; the token is the authorization. */}
          <Route path="/share/dashboard/:token" element={<PublicDashboardPage />} />
          {/* Internal deep link to one dashboard (login + access required). */}
          <Route
            path="/dashboard-view/:id"
            element={
              <ProtectedRoute>
                <SharedDashboardPage />
              </ProtectedRoute>
            }
          />
          {/* Legacy /setup route — now the real onboarding wizard, not a dead end. */}
          <Route path="/setup" element={<Navigate to="/onboarding" replace />} />
          {/* Public desktop-client download page — no login: it is reached from
              the marketing site by people who do not have an account yet. */}
          <Route path="/download" element={<Download />} />
          <Route path="/cli-authorize" element={<CliAuthorize />} />
          <Route path="/cli-authorize/device" element={<CliAuthorize />} />
        </Routes>
      </AuthProvider>
    </ErrorBoundary>
  )
}

export default App
