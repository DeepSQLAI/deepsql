'use client'

import { useAuth, PERMISSIONS, ROLES } from '@/hooks/useAuth'
import { Lock } from 'lucide-react'

/**
 * PermissionGuard - A wrapper component that checks permissions before rendering children.
 *
 * Props:
 * - permission: Single permission required (e.g., PERMISSIONS.EXECUTE_QUERIES)
 * - permissions: Array of permissions (any of which grants access)
 * - allPermissions: Array of permissions (all of which are required)
 * - role: Single role required (e.g., ROLES.ADMIN)
 * - minRole: Minimum role level required (DEVELOPER < ADMIN)
 * - fallback: Custom fallback component to show when access is denied
 * - hide: If true, hide content entirely instead of showing fallback (default: false)
 * - children: Content to render when access is granted
 *
 * Usage examples:
 *
 * // Require a single permission
 * <PermissionGuard permission={PERMISSIONS.EXECUTE_QUERIES}>
 *   <SqlRunner />
 * </PermissionGuard>
 *
 * // Require any of multiple permissions
 * <PermissionGuard permissions={[PERMISSIONS.EXECUTE_QUERIES, PERMISSIONS.USE_CHAT]}>
 *   <InteractiveFeature />
 * </PermissionGuard>
 *
 * // Require all permissions
 * <PermissionGuard allPermissions={[PERMISSIONS.MANAGE_USERS, PERMISSIONS.MANAGE_SETTINGS]}>
 *   <AdminPanel />
 * </PermissionGuard>
 *
 * // Require admin role
 * <PermissionGuard role={ROLES.ADMIN}>
 *   <AdminOnlyFeature />
 * </PermissionGuard>
 *
 * // Require minimum developer role
 * <PermissionGuard minRole={ROLES.DEVELOPER}>
 *   <DeveloperFeature />
 * </PermissionGuard>
 *
 * // Hide instead of showing fallback
 * <PermissionGuard permission={PERMISSIONS.MANAGE_USERS} hide>
 *   <AdminTab />
 * </PermissionGuard>
 */
export default function PermissionGuard({
  permission,
  permissions,
  allPermissions,
  role,
  minRole,
  fallback,
  hide = false,
  children,
}) {
  const auth = useAuth()

  // Check if user has required access
  let hasAccess = true

  // Check single permission
  if (permission) {
    hasAccess = auth.hasPermission(permission)
  }

  // Check any of multiple permissions
  if (permissions && permissions.length > 0) {
    hasAccess = auth.hasAnyPermission(permissions)
  }

  // Check all permissions required
  if (allPermissions && allPermissions.length > 0) {
    hasAccess = auth.hasAllPermissions(allPermissions)
  }

  // Check specific role
  if (role) {
    hasAccess = auth.hasRole(role)
  }

  // Check minimum role level
  if (minRole) {
    hasAccess = auth.hasRoleLevel(minRole)
  }

  // If user has access, render children
  if (hasAccess) {
    return children
  }

  // If hide is true, render nothing
  if (hide) {
    return null
  }

  // If custom fallback provided, use it
  if (fallback) {
    return fallback
  }

  // Default fallback: Access Denied message
  return <AccessDenied />
}

/**
 * Default Access Denied component
 */
function AccessDenied() {
  return (
    <div className="flex flex-col items-center justify-center h-full min-h-[200px] text-gray-500">
      <Lock size={48} className="mb-4 text-gray-400" />
      <h3 className="text-lg font-medium text-gray-700 mb-2">Access Denied</h3>
      <p className="text-sm text-gray-500 text-center max-w-md">
        You don't have permission to access this feature.
        Contact an administrator if you need access.
      </p>
    </div>
  )
}

/**
 * Hook version for programmatic permission checking in components.
 * Returns true if the user has the required permission/role.
 *
 * Usage:
 * const canExecute = usePermission(PERMISSIONS.EXECUTE_QUERIES)
 * if (canExecute) { ... }
 */
export function usePermission(permission) {
  const { hasPermission } = useAuth()
  return hasPermission(permission)
}

/**
 * Hook to check if user has required role.
 *
 * Usage:
 * const isAdmin = useRole(ROLES.ADMIN)
 */
export function useRole(role) {
  const { hasRole } = useAuth()
  return hasRole(role)
}

/**
 * Hook to check if user has at least the required role level.
 *
 * Usage:
 * const canUseProduct = useMinRole(ROLES.DEVELOPER)
 */
export function useMinRole(minRole) {
  const { hasRoleLevel } = useAuth()
  return hasRoleLevel(minRole)
}

// Re-export permissions and roles for convenience
export { PERMISSIONS, ROLES }
