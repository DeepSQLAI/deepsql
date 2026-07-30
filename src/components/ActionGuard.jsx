'use client'

import { cloneElement, isValidElement } from 'react'
import { Lock } from 'lucide-react'
import { useAuth } from '@/hooks/useAuth'

/**
 * ActionGuard - Wraps action elements with permission checks.
 *
 * This is the preferred way to gate UI elements by permission.
 * Uses action names (not permissions) for loose coupling.
 *
 * Usage:
 *   <ActionGuard action="execute-query">
 *     <button onClick={runQuery}>Run Query</button>
 *   </ActionGuard>
 *
 *   <ActionGuard action="manage-users" fallback={<span>Contact admin</span>}>
 *     <button onClick={openUsers}>Manage Users</button>
 *   </ActionGuard>
 *
 * Props:
 * @param {string} action - Action name (from actions.js)
 * @param {React.ReactNode} children - The element to guard
 * @param {React.ReactNode} [fallback] - Optional element to show when denied
 * @param {'disable' | 'hide' | 'custom'} [mode] - How to handle denied state
 * @param {boolean} [showLockIcon] - Show lock icon when disabled (default: true)
 * @param {string} [tooltip] - Custom tooltip message
 * @param {string} [className] - Additional class for wrapper
 */
export function ActionGuard({
  action,
  children,
  fallback,
  mode = 'disable', // 'disable' | 'hide' | 'custom'
  showLockIcon = true,
  tooltip,
  className,
}) {
  const { getActionInfo, isChecking } = useAuth()

  // Wait for auth to load
  if (isChecking) {
    // Render disabled state while loading
    if (isValidElement(children)) {
      return cloneElement(children, { disabled: true })
    }
    return children
  }

  const actionInfo = getActionInfo(action)
  const { allowed, disabledMessage, hideWhenDisabled } = actionInfo

  // If allowed, render children as-is
  if (allowed) {
    return children
  }

  // Determine mode - hideWhenDisabled from config overrides default
  const effectiveMode = hideWhenDisabled ? 'hide' : mode

  // Handle denied state based on mode
  switch (effectiveMode) {
    case 'hide':
      // Don't render anything
      return fallback || null

    case 'custom':
      // Render fallback if provided, otherwise hide
      return fallback || null

    case 'disable':
    default:
      // Render disabled version of the element
      return renderDisabled(children, {
        disabledMessage: tooltip || disabledMessage,
        showLockIcon,
        className,
      })
  }
}

/**
 * Render a disabled version of the element.
 */
function renderDisabled(element, { disabledMessage, showLockIcon, className }) {
  if (!isValidElement(element)) {
    return element
  }

  // Clone the element with disabled props
  const disabledProps = {
    disabled: true,
    title: disabledMessage,
    style: {
      ...element.props?.style,
      cursor: 'not-allowed',
      opacity: 0.6,
    },
    className: className
      ? `${element.props?.className || ''} ${className}`.trim()
      : element.props?.className,
  }

  // If it's a button and showLockIcon is true, try to replace icon
  if (showLockIcon && (element.type === 'button' || element.type?.displayName === 'button')) {
    // Check if first child is an icon (common pattern)
    const children = element.props?.children
    if (children) {
      const modifiedChildren = modifyChildrenWithLock(children)
      return cloneElement(element, disabledProps, modifiedChildren)
    }
  }

  return cloneElement(element, disabledProps)
}

/**
 * Replace the first icon child with a Lock icon.
 */
function modifyChildrenWithLock(children) {
  if (!children) return children

  // If single child is an icon-like element
  if (isValidElement(children)) {
    if (isIconElement(children)) {
      return <Lock size={children.props?.size || 14} />
    }
    return children
  }

  // If array of children, replace first icon
  if (Array.isArray(children)) {
    let iconReplaced = false
    return children.map((child, index) => {
      if (!iconReplaced && isValidElement(child) && isIconElement(child)) {
        iconReplaced = true
        return <Lock key={index} size={child.props?.size || 14} />
      }
      return child
    })
  }

  return children
}

/**
 * Check if an element looks like an icon (from lucide-react or similar).
 */
function isIconElement(element) {
  if (!isValidElement(element)) return false

  // Check for common icon library patterns
  const typeName = element.type?.displayName || element.type?.name || ''

  // Lucide icons usually have size prop and specific names
  if (element.props?.size !== undefined) {
    return true
  }

  // Check for common icon component name patterns
  if (typeName.endsWith('Icon') || typeName.match(/^[A-Z][a-z]+$/)) {
    return true
  }

  return false
}

/**
 * ActionButton - A button with built-in permission guarding.
 *
 * Usage:
 *   <ActionButton action="execute-query" onClick={runQuery}>
 *     <Play size={14} />
 *     Run Query
 *   </ActionButton>
 */
export function ActionButton({
  action,
  children,
  onClick,
  className,
  style,
  title,
  ...props
}) {
  const { getActionInfo, isChecking } = useAuth()

  if (isChecking) {
    return (
      <button disabled className={className} style={style} {...props}>
        {children}
      </button>
    )
  }

  const actionInfo = getActionInfo(action)
  const { allowed, disabledMessage, hideWhenDisabled } = actionInfo

  // Hide if configured and not allowed
  if (!allowed && hideWhenDisabled) {
    return null
  }

  // Show lock icon when disabled
  const modifiedChildren = !allowed
    ? modifyChildrenWithLock(children)
    : children

  return (
    <button
      onClick={allowed ? onClick : undefined}
      disabled={!allowed}
      className={className}
      style={{
        ...style,
        cursor: allowed ? 'pointer' : 'not-allowed',
        opacity: allowed ? 1 : 0.6,
      }}
      title={allowed ? title : disabledMessage}
      {...props}
    >
      {modifiedChildren}
    </button>
  )
}

/**
 * PermissionGate - Show/hide content based on permission.
 * Use for conditional rendering of entire sections.
 *
 * Usage:
 *   <PermissionGate permission="MANAGE_USERS">
 *     <AdminPanel />
 *   </PermissionGate>
 */
export function PermissionGate({
  permission,
  children,
  fallback = null,
}) {
  const { hasPermission, isChecking } = useAuth()

  if (isChecking) {
    return null
  }

  return hasPermission(permission) ? children : fallback
}

/**
 * RoleGate - Show/hide content based on minimum role level.
 *
 * Usage:
 *   <RoleGate minRole="ADMIN">
 *     <AdminPanel />
 *   </RoleGate>
 */
export function RoleGate({
  minRole,
  children,
  fallback = null,
}) {
  const { hasRoleLevel, isChecking } = useAuth()

  if (isChecking) {
    return null
  }

  return hasRoleLevel(minRole) ? children : fallback
}

export default ActionGuard
