import { useState, useEffect, createContext, useContext, useMemo, useCallback } from 'react'
import { useNavigate, useLocation } from 'react-router-dom'
import { getActionPermission, getActionConfig } from '@/lib/actions'
import { authAPI, setupAPI, adminAPI, AUTH_CHANGE_EVENT } from '@/lib/api/client'
import { queryClient } from '@/lib/queryClient'
import { useChatStore } from '@/lib/stores/useChatStore'
import { useConnectionStore } from '@/lib/stores/useConnectionStore'
import { useDashboardStore } from '@/lib/stores/useDashboardStore'
import { useNavStore } from '@/lib/stores/useNavStore'

export { PERMISSIONS, ROLES, ROLE_LEVELS, normalizeRole, roleIsAtLeast } from '@/lib/permissions'
import { PERMISSIONS, ROLES, ROLE_LEVELS, normalizeRole } from '@/lib/permissions'

const AuthContext = createContext(null)

const AUTH_PUBLIC_PATHS = ['/login', '/signup', '/activate']

const isPublicAuthPath = (pathname) => AUTH_PUBLIC_PATHS.some((prefix) => pathname.startsWith(prefix))

const resetClientSessionState = () => {
  localStorage.removeItem('selectedConnectionId')

  useChatStore.getState().resetStore()

  useConnectionStore.persist.clearStorage()
  useConnectionStore.setState({
    selectedConnectionId: null,
    lastConnections: [],
  })

  useDashboardStore.persist.clearStorage()
  useDashboardStore.setState({
    connectionId: null,
    dashboardConfig: null,
    activeTab: 'rag-training',
    isBuildingDashboard: false,
  })

  useNavStore.persist.clearStorage()
  useNavStore.setState({
    activeSection: 'agent-chat',
    isNavigating: false,
  })

  queryClient.clear()
}

const toPermissionSet = (permissionList) => {
  if (!permissionList) return new Set()
  if (permissionList instanceof Set) return permissionList
  if (Array.isArray(permissionList)) return new Set(permissionList)
  return new Set(Object.values(permissionList))
}

export function AuthProvider({ children }) {
  const navigate = useNavigate()
  const location = useLocation()

  const [isAuthenticated, setIsAuthenticated] = useState(false)
  const [isChecking, setIsChecking] = useState(true)
  const [user, setUser] = useState(null)
  const [role, setRole] = useState(null)
  const [permissions, setPermissions] = useState(new Set())

  const clearAuthState = useCallback(({ resetSession = true } = {}) => {
    if (resetSession) {
      resetClientSessionState()
    }
    setIsAuthenticated(false)
    setUser(null)
    setRole(null)
    setPermissions(new Set())
  }, [])

  const applyAuthPayload = useCallback((payload, { resetSession = true } = {}) => {
    const normalizedRole = normalizeRole(payload?.role)
    const permissionSet = toPermissionSet(payload?.permissions)

    if (resetSession) {
      resetClientSessionState()
    }

    setUser({
      username: payload?.username || 'User',
      email: payload?.email || '',
      emailVerified: payload?.emailVerified ?? false,
      accountStatus: payload?.accountStatus || null,
      emailTwoFactorEnabled: payload?.emailTwoFactorEnabled ?? false,
      mfaRequired: false,
      mfaEnrolled: false,
      impersonating: Boolean(payload?.impersonating),
      impersonatorUsername: payload?.impersonatorUsername || null,
      impersonatorEmail: payload?.impersonatorEmail || null,
    })
    setRole(normalizedRole)
    setPermissions(permissionSet)
    setIsAuthenticated(true)
  }, [])

  const handleLoggedOut = useCallback((redirectToLogin = true) => {
    clearAuthState()
    if (redirectToLogin && !location.pathname.startsWith('/login')) {
      navigate('/login', { replace: true })
    }
  }, [clearAuthState, location.pathname, navigate])

  const refreshCurrentUser = useCallback(async () => {
    try {
      const currentUser = await authAPI.getCurrentUser()
      applyAuthPayload(currentUser, { resetSession: false })
      return currentUser
    } catch {
      clearAuthState({ resetSession: false })
      return null
    }
  }, [applyAuthPayload, clearAuthState])

  useEffect(() => {
    let cancelled = false

    const checkAuth = async () => {
      setIsChecking(true)
      const currentUser = await refreshCurrentUser()
      if (cancelled) return

      const publicAuthPath = isPublicAuthPath(location.pathname)

      if (!currentUser && !publicAuthPath) {
        navigate('/login', { replace: true })
      } else if (currentUser && publicAuthPath) {
        navigate('/dashboard', { replace: true })
      }

      setIsChecking(false)
    }

    checkAuth()

    return () => {
      cancelled = true
    }
  }, [location.pathname, navigate, refreshCurrentUser])

  useEffect(() => {
    const handleAuthChange = (event) => {
      const detail = event.detail || {}
      if (detail.action === 'logout') {
        handleLoggedOut(Boolean(detail.redirect !== false))
        return
      }
      if (detail.action === 'login' && detail.payload) {
        applyAuthPayload(detail.payload)
        navigate('/dashboard', { replace: true })
        return
      }
      if (detail.action === 'refresh' && detail.payload) {
        applyAuthPayload(detail.payload, { resetSession: false })
      }
    }

    const handleStorageChange = (event) => {
      if (event.key === 'selectedConnectionId') return
      if (event.key === null) return
      refreshCurrentUser()
    }

    window.addEventListener(AUTH_CHANGE_EVENT, handleAuthChange)
    window.addEventListener('storage', handleStorageChange)

    return () => {
      window.removeEventListener(AUTH_CHANGE_EVENT, handleAuthChange)
      window.removeEventListener('storage', handleStorageChange)
    }
  }, [applyAuthPayload, handleLoggedOut, navigate, refreshCurrentUser])

  // Product-ready ≠ "an admin exists" — a fresh install has no connections
  // yet, so send a just-logged-in user into the setup wizard instead of a
  // dashboard with nothing to show. Best-effort: if the status check itself
  // fails, don't block login on it — fall through to the dashboard.
  const postLoginDestination = useCallback(async () => {
    try {
      const status = await setupAPI.getStatus()
      if (status && status.hasConnections === false) {
        return '/onboarding'
      }
    } catch {
      /* status check failed — don't block login on it */
    }
    return '/dashboard'
  }, [])

  const login = useCallback((payload) => {
    applyAuthPayload(payload)
    postLoginDestination().then((destination) => navigate(destination, { replace: true }))
  }, [applyAuthPayload, navigate, postLoginDestination])

  const logout = useCallback(async () => {
    try {
      await authAPI.logout()
    } catch {
      // Best effort only. We still clear local client state.
    }
    handleLoggedOut(true)
  }, [handleLoggedOut])

  const startImpersonation = useCallback(async (userId) => {
    const payload = await adminAPI.startImpersonation(userId)
    applyAuthPayload(payload, { resetSession: true })
    return payload
  }, [applyAuthPayload])

  const stopImpersonation = useCallback(async () => {
    const payload = await adminAPI.stopImpersonation()
    applyAuthPayload(payload, { resetSession: true })
    return payload
  }, [applyAuthPayload])

  const hasPermission = useCallback((permission) => {
    return permissions.has(permission)
  }, [permissions])

  const hasAnyPermission = useCallback((permissionList) => {
    return permissionList.some((permission) => permissions.has(permission))
  }, [permissions])

  const hasAllPermissions = useCallback((permissionList) => {
    return permissionList.every((permission) => permissions.has(permission))
  }, [permissions])

  const canAction = useCallback((action) => {
    const permission = getActionPermission(action)
    if (!permission) {
      console.warn(`Unknown action: ${action}`)
      return false
    }
    return permissions.has(permission)
  }, [permissions])

  const getActionInfo = useCallback((action) => {
    const config = getActionConfig(action)
    if (!config) {
      return {
        allowed: false,
        label: action,
        disabledMessage: 'Unknown action',
        hideWhenDisabled: false,
      }
    }

    return {
      allowed: permissions.has(config.permission),
      label: config.label,
      permission: config.permission,
      disabledMessage: config.disabledMessage || 'Action not permitted',
      hideWhenDisabled: config.hideWhenDisabled === true,
    }
  }, [permissions])

  const hasRole = useCallback((requiredRole) => {
    return role === normalizeRole(requiredRole)
  }, [role])

  const hasRoleLevel = useCallback((requiredRole) => {
    const normalizedRole = normalizeRole(role)
    const normalizedRequiredRole = normalizeRole(requiredRole)
    const userLevel = normalizedRole ? (ROLE_LEVELS[normalizedRole] ?? -1) : -1
    const requiredLevel = normalizedRequiredRole ? (ROLE_LEVELS[normalizedRequiredRole] ?? 999) : 999
    return userLevel >= requiredLevel
  }, [role])

  const isAdmin = useMemo(() => role === ROLES.ADMIN, [role])
  const isDeveloper = useMemo(() => role === ROLES.DEVELOPER, [role])
  const impersonating = Boolean(user?.impersonating)
  const canSwitchProfile = isAdmin || impersonating

  const canExecute = useMemo(() => permissions.has(PERMISSIONS.EXECUTE_QUERIES), [permissions])
  const canChat = useMemo(() => permissions.has(PERMISSIONS.USE_CHAT), [permissions])
  const canManageUsers = useMemo(() => permissions.has(PERMISSIONS.MANAGE_USERS), [permissions])
  const canManageConnections = useMemo(() => permissions.has(PERMISSIONS.MANAGE_CONNECTIONS), [permissions])
  const canRunAnalysis = useMemo(() => permissions.has(PERMISSIONS.RUN_ANALYSIS), [permissions])

  const value = useMemo(() => ({
    isAuthenticated,
    isChecking,
    login,
    logout,
    refreshCurrentUser,
    startImpersonation,
    stopImpersonation,
    user,
    username: user?.username || 'User',
    email: user?.email || '',
    impersonating,
    impersonatorUsername: user?.impersonatorUsername || null,
    impersonatorEmail: user?.impersonatorEmail || null,
    canSwitchProfile,
    role,
    permissions: [...permissions],
    hasPermission,
    hasAnyPermission,
    hasAllPermissions,
    canAction,
    getActionInfo,
    hasRole,
    hasRoleLevel,
    isAdmin,
    isDeveloper,
    canExecute,
    canChat,
    canManageUsers,
    canManageConnections,
    canRunAnalysis,
  }), [
    isAuthenticated,
    isChecking,
    login,
    logout,
    refreshCurrentUser,
    startImpersonation,
    stopImpersonation,
    user,
    role,
    permissions,
    impersonating,
    canSwitchProfile,
    hasPermission,
    hasAnyPermission,
    hasAllPermissions,
    canAction,
    getActionInfo,
    hasRole,
    hasRoleLevel,
    isAdmin,
    isDeveloper,
    canExecute,
    canChat,
    canManageUsers,
    canManageConnections,
    canRunAnalysis,
  ])

  return (
    <AuthContext.Provider value={value}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error('useAuth must be used within AuthProvider')
  }
  return context
}
