'use client'

import { useEffect, useMemo, useState } from 'react'
import {
  Users,
  Shield,
  Trash2,
  RefreshCw,
  AlertCircle,
  CheckCircle,
  Settings,
  Plus,
  X,
  Mail,
  Lock,
  Unlock,
  Ban,
  LaptopMinimal,
  KeyRound,
  CircleSlash,
  ShieldCheck,
} from 'lucide-react'
import { adminAPI } from '@/lib/api/client'
import { useAuth, ROLES } from '@/hooks/useAuth'

const ROLE_BADGE_CLASSES = {
  [ROLES.ADMIN]: 'bg-red-100 text-red-700 border-red-200',
  [ROLES.DEVELOPER]: 'bg-blue-100 text-blue-700 border-blue-200',
}

const STATUS_BADGE_CLASSES = {
  ACTIVE: 'bg-green-100 text-green-700 border-green-200',
  PENDING_INVITE: 'bg-amber-100 text-amber-700 border-amber-200',
  LOCKED: 'bg-orange-100 text-orange-700 border-orange-200',
  DISABLED: 'bg-gray-100 text-gray-700 border-gray-200',
}

const ACCESS_MATRIX = [
  { area: 'Chat', developer: 'Own + Assigned', admin: 'Full' },
  { area: 'Editor', developer: 'Own + Assigned', admin: 'Full' },
  { area: 'Brain', developer: 'Own + Full Access', admin: 'Full' },
  { area: 'Schema Docs', developer: 'Own + Full Access', admin: 'Full' },
  { area: 'Company Knowledge', developer: 'Own + Full Access', admin: 'Full' },
  { area: 'Performance', developer: '—', admin: 'Full' },
]

const FALLBACK_ROLES = [
  { name: ROLES.DEVELOPER, description: 'Access to Chat and the SQL Editor' },
  { name: ROLES.ADMIN, description: 'Access to all product areas and administrative controls' },
]

function badgeClassForRole(role) {
  return ROLE_BADGE_CLASSES[role] || 'bg-gray-100 text-gray-700 border-gray-200'
}

function badgeClassForStatus(status) {
  return STATUS_BADGE_CLASSES[status] || 'bg-gray-100 text-gray-700 border-gray-200'
}

function formatDate(value) {
  if (!value) return '—'
  try {
    return new Date(value).toLocaleString()
  } catch {
    return value
  }
}

export default function UsersTab() {
  const { isAdmin } = useAuth()
  const [users, setUsers] = useState([])
  const [roles, setRoles] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [successMessage, setSuccessMessage] = useState(null)
  const [actionLoading, setActionLoading] = useState(null)
  const [showInviteForm, setShowInviteForm] = useState(false)
  const [selectedUserId, setSelectedUserId] = useState(null)
  const [securitySessions, setSecuritySessions] = useState([])
  const [securityLoading, setSecurityLoading] = useState(false)
  const [confirmDelete, setConfirmDelete] = useState(null)
  const [connectionAccessUser, setConnectionAccessUser] = useState(null)
  const [connectionAccessData, setConnectionAccessData] = useState(null)
  const [connectionAccessLoading, setConnectionAccessLoading] = useState(false)
  const [securitySettings, setSecuritySettings] = useState(null)
  const [passwordUser, setPasswordUser] = useState(null)

  const selectedUser = useMemo(
    () => users.find((candidate) => candidate.id === selectedUserId) || null,
    [users, selectedUserId],
  )

  const availableRoles = useMemo(() => (
    Array.isArray(roles) && roles.length > 0 ? roles : FALLBACK_ROLES
  ), [roles])

  useEffect(() => {
    if (isAdmin) {
      void loadData()
    }
  }, [isAdmin])

  useEffect(() => {
    if (!selectedUserId) {
      setSecuritySessions([])
      return
    }
    void loadSecurityDetails(selectedUserId)
  }, [selectedUserId])

  const showSuccess = (message) => {
    setSuccessMessage(message)
    setTimeout(() => setSuccessMessage(null), 3000)
  }

  const loadData = async () => {
    setLoading(true)
    setError(null)
    try {
      const [usersResult, rolesResult, securitySettingsResult] = await Promise.allSettled([
        adminAPI.listUsers(),
        adminAPI.getRoles(),
        adminAPI.getSecuritySettings(),
      ])

      if (usersResult.status !== 'fulfilled') {
        throw usersResult.reason
      }
      if (rolesResult.status !== 'fulfilled') {
        throw rolesResult.reason
      }

      const usersData = usersResult.value
      const rolesData = rolesResult.value
      setUsers(usersData)
      setRoles(rolesData)
      setSecuritySettings(
        securitySettingsResult.status === 'fulfilled' ? securitySettingsResult.value : null,
      )

      if (!selectedUserId && usersData.length > 0) {
        setSelectedUserId(usersData[0].id)
      }
    } catch (err) {
      setError(err.message || 'Failed to load user data')
    } finally {
      setLoading(false)
    }
  }

  const loadSecurityDetails = async (userId) => {
    setSecurityLoading(true)
    setError(null)
    try {
      const sessions = await adminAPI.listSecuritySessions(userId)
      setSecuritySessions(Array.isArray(sessions) ? sessions : [])
    } catch (err) {
      setError(err.message || 'Failed to load active sessions')
    } finally {
      setSecurityLoading(false)
    }
  }

  const handleRoleChange = async (userId, newRole) => {
    setActionLoading(`role-${userId}`)
    setError(null)
    try {
      const updatedUser = await adminAPI.updateUserRole(userId, newRole)
      setUsers((prev) => prev.map((user) => (user.id === userId ? updatedUser : user)))
      showSuccess(`Role updated to ${newRole}`)
    } catch (err) {
      setError(err.message || 'Failed to update role')
    } finally {
      setActionLoading(null)
    }
  }

  const handleInviteUser = async (userData) => {
    setActionLoading('invite')
    setError(null)
    try {
      const invitedUser = await adminAPI.createUser(userData)
      setUsers((prev) => [...prev, invitedUser])
      setShowInviteForm(false)
      showSuccess(`User created for ${userData.email}`)
      setSelectedUserId(invitedUser.id)
    } catch (err) {
      setError(err.message || 'Failed to create user')
    } finally {
      setActionLoading(null)
    }
  }

  const handleStatusAction = async (userId, action) => {
    const key = `${action}-${userId}`
    setActionLoading(key)
    setError(null)
    try {
      const actionMap = {
        resend: adminAPI.resendInvite,
        lock: adminAPI.lockUser,
        unlock: adminAPI.unlockUser,
        disable: adminAPI.disableUser,
      }
      const updatedUser = await actionMap[action](userId)
      if (updatedUser?.id) {
        setUsers((prev) => prev.map((user) => (user.id === userId ? updatedUser : user)))
      }
      const messageMap = {
        resend: 'Invite re-sent',
        lock: 'User locked',
        unlock: 'User unlocked',
        disable: 'User disabled',
      }
      showSuccess(messageMap[action])
      if (selectedUserId === userId) {
        void loadSecurityDetails(userId)
      }
    } catch (err) {
      setError(err.message || `Failed to ${action} user`)
    } finally {
      setActionLoading(null)
    }
  }

  const handleDeleteUser = async (userId) => {
    setActionLoading(`delete-${userId}`)
    setError(null)
    try {
      await adminAPI.deleteUser(userId)
      setUsers((prev) => prev.filter((user) => user.id !== userId))
      setConfirmDelete(null)
      if (selectedUserId === userId) {
        setSelectedUserId(null)
      }
      showSuccess('User deleted successfully')
    } catch (err) {
      setError(err.message || 'Failed to delete user')
    } finally {
      setActionLoading(null)
    }
  }

  const handleChangePassword = async ({ userId, password }) => {
    setActionLoading(`password-${userId}`)
    setError(null)
    try {
      await adminAPI.changeUserPassword(userId, password)
      setPasswordUser(null)
      showSuccess('Password updated successfully')
      if (selectedUserId === userId) {
        void loadSecurityDetails(userId)
      }
    } catch (err) {
      setError(err.message || 'Failed to update password')
    } finally {
      setActionLoading(null)
    }
  }

  const openConnectionAccess = async (user) => {
    setConnectionAccessUser(user)
    setConnectionAccessLoading(true)
    setError(null)
    try {
      const data = await adminAPI.getUserConnectionAccess(user.id)
      setConnectionAccessData(data)
    } catch (err) {
      setError(err.message || 'Failed to load connection access')
    } finally {
      setConnectionAccessLoading(false)
    }
  }

  const handleConnectionAccessSave = async (userId, connectionId, accessLevel) => {
    setConnectionAccessLoading(true)
    setError(null)
    try {
      const data = await adminAPI.updateUserConnectionAccess(userId, connectionId, accessLevel)
      setConnectionAccessData(data)
      showSuccess('Connection access updated')
    } catch (err) {
      setError(err.message || 'Failed to update connection access')
    } finally {
      setConnectionAccessLoading(false)
    }
  }

  const handleConnectionAccessRevoke = async (userId, connectionId) => {
    setConnectionAccessLoading(true)
    setError(null)
    try {
      const data = await adminAPI.revokeUserConnectionAccess(userId, connectionId)
      setConnectionAccessData(data)
      showSuccess('Connection access removed')
    } catch (err) {
      setError(err.message || 'Failed to remove connection access')
    } finally {
      setConnectionAccessLoading(false)
    }
  }

  const handleConnectionPolicySave = async (userId, connectionId, plainEnglishPolicy, active = true) => {
    setConnectionAccessLoading(true)
    setError(null)
    try {
      await adminAPI.updateUserConnectionChatPolicy(userId, connectionId, {
        plainEnglishPolicy,
        active,
      })
      const data = await adminAPI.getUserConnectionAccess(userId)
      setConnectionAccessData(data)
      showSuccess('Chat access policy updated')
    } catch (err) {
      setError(err.message || 'Failed to update chat access policy')
    } finally {
      setConnectionAccessLoading(false)
    }
  }

  const handleConnectionPolicyPreview = async (connectionId, plainEnglishPolicy) => {
    return adminAPI.previewConnectionChatPolicy(connectionId, plainEnglishPolicy)
  }

  if (!isAdmin) {
    return (
      <div className="flex flex-col items-center justify-center h-full min-h-[400px] text-gray-500">
        <Shield size={48} className="mb-4 text-gray-400" />
        <h3 className="text-lg font-medium text-gray-700 mb-2">Admin Access Required</h3>
        <p className="text-sm text-gray-500">You need administrator privileges to access this page.</p>
      </div>
    )
  }

  return (
    <div className="h-full flex flex-col bg-white">
      <div className="flex items-center justify-between px-6 py-4 border-b border-gray-200">
        <div className="flex items-center gap-3">
          <Users size={20} className="text-gray-600" />
          <h2 className="text-lg font-semibold text-gray-800">User Management</h2>
          <span className="text-sm text-gray-500">({users.length} users)</span>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={() => setShowInviteForm(true)}
            disabled={loading}
            className="flex items-center gap-2 px-3 py-1.5 text-sm text-white bg-gray-900 hover:bg-gray-800 rounded-md transition-colors disabled:opacity-50"
          >
            <Plus size={14} />
            Create User
          </button>
          <button
            onClick={loadData}
            disabled={loading}
            className="flex items-center gap-2 px-3 py-1.5 text-sm text-gray-600 hover:text-gray-900 hover:bg-gray-100 rounded-md transition-colors"
          >
            <RefreshCw size={14} className={loading ? 'animate-spin' : ''} />
            Refresh
          </button>
        </div>
      </div>

      {successMessage && (
        <div className="mx-6 mt-4 p-3 bg-green-50 border border-green-200 rounded-lg flex items-center gap-2 text-green-700">
          <CheckCircle size={16} />
          <span className="text-sm">{successMessage}</span>
        </div>
      )}

      {error && (
        <div className="mx-6 mt-4 p-3 bg-red-50 border border-red-200 rounded-lg flex items-center gap-2 text-red-700">
          <AlertCircle size={16} />
          <span className="text-sm">{error}</span>
        </div>
      )}

      <div className="flex-1 overflow-auto p-6 space-y-8">
        {loading ? (
          <div className="flex items-center justify-center h-64">
            <RefreshCw size={24} className="animate-spin text-gray-400" />
          </div>
        ) : (
          <>
            <div className="border border-gray-200 rounded-lg overflow-hidden">
              <table className="w-full">
                <thead className="bg-gray-50">
                  <tr>
                    <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">User</th>
                    <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Email</th>
                    <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Role</th>
                    <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Status</th>
                    <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">Verified</th>
                    <th className="px-4 py-3 text-left text-xs font-medium text-gray-500 uppercase tracking-wider">2FA</th>
                    <th className="px-4 py-3 text-right text-xs font-medium text-gray-500 uppercase tracking-wider">Actions</th>
                  </tr>
                </thead>
                <tbody className="bg-white divide-y divide-gray-200">
                  {users.map((user) => {
                    const selected = user.id === selectedUserId
                    return (
                      <tr
                        key={user.id}
                        className={`transition-colors ${selected ? 'bg-blue-50/60' : 'hover:bg-gray-50'}`}
                        onClick={() => setSelectedUserId(user.id)}
                      >
                        <td className="px-4 py-3">
                          <div className="flex items-center gap-2">
                            <div className="w-8 h-8 rounded-full bg-gray-200 flex items-center justify-center text-gray-600 font-medium text-sm">
                              {user.username.charAt(0).toUpperCase()}
                            </div>
                            <span className="font-medium text-gray-900">{user.username}</span>
                          </div>
                        </td>
                        <td className="px-4 py-3 text-sm text-gray-600">{user.email}</td>
                        <td className="px-4 py-3">
                          <RoleSelector
                            user={user}
                            roles={availableRoles}
                            onRoleChange={handleRoleChange}
                            loading={actionLoading === `role-${user.id}`}
                            disabled={user.username === 'admin'}
                          />
                        </td>
                        <td className="px-4 py-3">
                          <span className={`inline-flex items-center px-2 py-1 rounded-full text-xs font-medium border ${badgeClassForStatus(user.accountStatus)}`}>
                            {user.accountStatus || 'ACTIVE'}
                          </span>
                        </td>
                        <td className="px-4 py-3 text-sm text-gray-600">
                          {user.emailVerified ? formatDate(user.emailVerifiedAt) : 'Pending'}
                        </td>
                        <td className="px-4 py-3 text-sm text-gray-600">
                          {securitySettings?.workspaceEmail2faEnabled ? 'Enabled' : 'Disabled'}
                        </td>
                        <td className="px-4 py-3 text-right">
                          <div className="flex items-center justify-end gap-1">
                            <button
                              onClick={(event) => {
                                event.stopPropagation()
                                setPasswordUser(user)
                              }}
                              className="p-1.5 text-gray-400 hover:text-gray-700 hover:bg-gray-100 rounded transition-colors"
                              title="Change password"
                            >
                              <KeyRound size={16} />
                            </button>
                            <button
                              onClick={(event) => {
                                event.stopPropagation()
                                openConnectionAccess(user)
                              }}
                              className="p-1.5 text-gray-400 hover:text-gray-700 hover:bg-gray-100 rounded transition-colors"
                              title="Manage connection access"
                            >
                              <Settings size={16} />
                            </button>
                            {user.accountStatus === 'PENDING_INVITE' && (
                              <button
                                onClick={(event) => {
                                  event.stopPropagation()
                                  void handleStatusAction(user.id, 'resend')
                                }}
                                disabled={actionLoading === `resend-${user.id}`}
                                className="p-1.5 text-gray-400 hover:text-blue-600 hover:bg-blue-50 rounded transition-colors"
                                title="Resend invite"
                              >
                                <Mail size={16} />
                              </button>
                            )}
                            {user.accountStatus === 'LOCKED' ? (
                              <button
                                onClick={(event) => {
                                  event.stopPropagation()
                                  void handleStatusAction(user.id, 'unlock')
                                }}
                                disabled={actionLoading === `unlock-${user.id}`}
                                className="p-1.5 text-gray-400 hover:text-green-600 hover:bg-green-50 rounded transition-colors"
                                title="Unlock user"
                              >
                                <Unlock size={16} />
                              </button>
                            ) : (
                              <button
                                onClick={(event) => {
                                  event.stopPropagation()
                                  void handleStatusAction(user.id, 'lock')
                                }}
                                disabled={actionLoading === `lock-${user.id}` || user.username === 'admin'}
                                className="p-1.5 text-gray-400 hover:text-amber-600 hover:bg-amber-50 rounded transition-colors disabled:opacity-40"
                                title="Lock user"
                              >
                                <Lock size={16} />
                              </button>
                            )}
                            <button
                              onClick={(event) => {
                                event.stopPropagation()
                                void handleStatusAction(user.id, 'disable')
                              }}
                              disabled={actionLoading === `disable-${user.id}` || user.username === 'admin'}
                              className="p-1.5 text-gray-400 hover:text-gray-700 hover:bg-gray-100 rounded transition-colors disabled:opacity-40"
                              title="Disable user"
                            >
                              <Ban size={16} />
                            </button>
                            {user.username !== 'admin' && (
                              confirmDelete === user.id ? (
                                <div className="flex items-center gap-2">
                                  <span className="text-xs text-gray-500">Delete?</span>
                                  <button
                                    onClick={(event) => {
                                      event.stopPropagation()
                                      void handleDeleteUser(user.id)
                                    }}
                                    disabled={actionLoading === `delete-${user.id}`}
                                    className="px-2 py-1 text-xs text-white bg-red-500 hover:bg-red-600 rounded transition-colors disabled:opacity-50"
                                  >
                                    {actionLoading === `delete-${user.id}` ? 'Deleting…' : 'Yes'}
                                  </button>
                                  <button
                                    onClick={(event) => {
                                      event.stopPropagation()
                                      setConfirmDelete(null)
                                    }}
                                    className="px-2 py-1 text-xs text-gray-600 hover:text-gray-900 rounded transition-colors"
                                  >
                                    No
                                  </button>
                                </div>
                              ) : (
                                <button
                                  onClick={(event) => {
                                    event.stopPropagation()
                                    setConfirmDelete(user.id)
                                  }}
                                  className="p-1.5 text-gray-400 hover:text-red-500 hover:bg-red-50 rounded transition-colors"
                                  title="Delete user"
                                >
                                  <Trash2 size={16} />
                                </button>
                              )
                            )}
                          </div>
                        </td>
                      </tr>
                    )
                  })}
                </tbody>
              </table>
            </div>

            <div className="grid grid-cols-1 xl:grid-cols-[minmax(0,1fr)_420px] gap-6">
              <div>
                <h3 className="text-sm font-medium text-gray-700 mb-4">Role Permissions</h3>
                <div className="border border-gray-200 rounded-lg overflow-hidden">
                  <table className="w-full">
                    <thead className="bg-gray-50">
                      <tr>
                        <th className="px-4 py-2.5 text-left text-xs font-medium text-gray-500 uppercase">Area</th>
                        <th className="px-4 py-2.5 text-center text-xs font-medium text-gray-500 uppercase">Developer</th>
                        <th className="px-4 py-2.5 text-center text-xs font-medium text-gray-500 uppercase">Admin</th>
                      </tr>
                    </thead>
                    <tbody className="divide-y divide-gray-200">
                      {ACCESS_MATRIX.map((row) => (
                        <tr key={row.area}>
                          <td className="px-4 py-2 text-sm text-gray-700 font-medium">{row.area}</td>
                          <td className="px-4 py-2 text-center text-xs text-gray-600">{row.developer}</td>
                          <td className="px-4 py-2 text-center text-xs text-green-600 font-medium">{row.admin}</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>

              <div className="border border-gray-200 rounded-lg p-4">
                <div className="flex items-center gap-2 mb-4">
                  <LaptopMinimal size={16} className="text-gray-600" />
                  <h3 className="text-sm font-semibold text-gray-800">
                    Active Sessions{selectedUser ? ` · ${selectedUser.username}` : ''}
                  </h3>
                </div>

                {securityLoading ? (
                  <div className="flex items-center justify-center h-40">
                    <RefreshCw size={20} className="animate-spin text-gray-400" />
                  </div>
                ) : securitySessions.length === 0 ? (
                  <div className="text-sm text-gray-500">No live sessions found for the selected user.</div>
                ) : (
                  <div className="space-y-3">
                    {securitySessions.map((session) => (
                      <div key={session.id} className="border border-gray-100 rounded-lg p-3">
                        <div className="flex items-center justify-between gap-3">
                          <div className="text-sm font-medium text-gray-900">{session.clientIp || 'Unknown IP'}</div>
                          <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-medium border ${
                            session.revokedAt
                              ? 'bg-gray-50 text-gray-700 border-gray-200'
                              : 'bg-green-50 text-green-700 border-green-200'
                          }`}>
                            {session.revokedAt ? 'Revoked' : 'Active'}
                          </span>
                        </div>
                        <div className="text-xs text-gray-500 mt-1">
                          Created {formatDate(session.createdAt)}
                        </div>
                        <div className="text-xs text-gray-500 mt-1">
                          Last seen {formatDate(session.lastSeenAt)}
                        </div>
                        {session.userAgent && (
                          <div className="text-xs text-gray-600 mt-2 break-words">{session.userAgent}</div>
                        )}
                      </div>
                    ))}
                  </div>
                )}
              </div>
            </div>
          </>
        )}
      </div>

      {showInviteForm && (
        <InviteUserModal
          roles={availableRoles}
          onClose={() => setShowInviteForm(false)}
          onSubmit={handleInviteUser}
          loading={actionLoading === 'invite'}
        />
      )}

      {passwordUser && (
        <ChangePasswordModal
          user={passwordUser}
          loading={actionLoading === `password-${passwordUser.id}`}
          onClose={() => setPasswordUser(null)}
          onSubmit={handleChangePassword}
        />
      )}

      {connectionAccessUser && (
        <ConnectionAccessModal
          user={connectionAccessUser}
          data={connectionAccessData}
          loading={connectionAccessLoading}
          onClose={() => {
            setConnectionAccessUser(null)
            setConnectionAccessData(null)
          }}
          onSave={handleConnectionAccessSave}
          onRevoke={handleConnectionAccessRevoke}
          onSavePolicy={handleConnectionPolicySave}
          onPreviewPolicy={handleConnectionPolicyPreview}
        />
      )}
    </div>
  )
}

function RoleSelector({ user, roles, onRoleChange, loading, disabled }) {
  return (
    <select
      value={user.role}
      onChange={(event) => onRoleChange(user.id, event.target.value)}
      disabled={disabled || loading}
      className={`px-3 py-1.5 rounded-full text-xs font-medium border focus:outline-none focus:ring-2 focus:ring-gray-900 focus:border-transparent ${badgeClassForRole(user.role)}`}
    >
      {roles.map((role) => (
        <option key={role.name} value={role.name}>
          {role.name}
        </option>
      ))}
    </select>
  )
}

function InviteUserModal({ roles, onClose, onSubmit, loading }) {
  const [formData, setFormData] = useState({
    username: '',
    email: '',
    role: ROLES.DEVELOPER,
    password: '',
  })
  const [errors, setErrors] = useState({})

  const handleChange = (event) => {
    const { name, value } = event.target
    setFormData((prev) => ({ ...prev, [name]: value }))
    if (errors[name]) {
      setErrors((prev) => ({ ...prev, [name]: null }))
    }
  }

  const validate = () => {
    const nextErrors = {}
    if (!formData.username.trim()) {
      nextErrors.username = 'Display name is required'
    }
    if (!formData.email.trim()) {
      nextErrors.email = 'Email is required'
    } else if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(formData.email)) {
      nextErrors.email = 'Enter a valid email address'
    }
    if (!formData.password.trim()) {
      nextErrors.password = 'Password is required'
    } else if (formData.password.length < 8) {
      nextErrors.password = 'Password must be at least 8 characters'
    }
    setErrors(nextErrors)
    return Object.keys(nextErrors).length === 0
  }

  const handleSubmit = (event) => {
    event.preventDefault()
    if (validate()) {
      onSubmit(formData)
    }
  }

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
      <div className="bg-white rounded-lg shadow-xl w-full max-w-md mx-4">
        <div className="flex items-center justify-between px-6 py-4 border-b border-gray-200">
          <h3 className="text-lg font-semibold text-gray-800">Create New User</h3>
          <button onClick={onClose} className="p-1 text-gray-400 hover:text-gray-600 rounded transition-colors">
            <X size={20} />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="p-6 space-y-4">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">
              Display Name <span className="text-red-500">*</span>
            </label>
            <input
              type="text"
              name="username"
              value={formData.username}
              onChange={handleChange}
              autoFocus
              className={`w-full px-3 py-2 border rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-gray-900 focus:border-transparent ${
                errors.username ? 'border-red-300' : 'border-gray-300'
              }`}
              placeholder="Enter display name"
            />
            {errors.username && <p className="mt-1 text-xs text-red-500">{errors.username}</p>}
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">
              Email <span className="text-red-500">*</span>
            </label>
            <input
              type="email"
              name="email"
              value={formData.email}
              onChange={handleChange}
              className={`w-full px-3 py-2 border rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-gray-900 focus:border-transparent ${
                errors.email ? 'border-red-300' : 'border-gray-300'
              }`}
              placeholder="user@company.com"
            />
            {errors.email && <p className="mt-1 text-xs text-red-500">{errors.email}</p>}
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Role</label>
            <select
              name="role"
              value={formData.role}
              onChange={handleChange}
              className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-gray-900 focus:border-transparent"
            >
              {roles.map((role) => (
                <option key={role.name} value={role.name}>
                  {role.name} - {role.description}
                </option>
              ))}
            </select>
          </div>

          <div className="p-3 rounded-lg bg-gray-50 border border-gray-200 text-xs text-gray-600">
            The account becomes active immediately. Users sign in with the email and password you set here.
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">
              Password <span className="text-red-500">*</span>
            </label>
            <input
              type="password"
              name="password"
              value={formData.password}
              onChange={handleChange}
              className={`w-full px-3 py-2 border rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-gray-900 focus:border-transparent ${
                errors.password ? 'border-red-300' : 'border-gray-300'
              }`}
              placeholder="Set an initial password"
            />
            {errors.password && <p className="mt-1 text-xs text-red-500">{errors.password}</p>}
          </div>

          <div className="flex items-center justify-end gap-3 pt-4">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 text-sm text-gray-600 hover:text-gray-900 transition-colors"
              disabled={loading}
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={loading}
              className="flex items-center gap-2 px-4 py-2 text-sm text-white bg-gray-900 hover:bg-gray-800 rounded-md transition-colors disabled:opacity-50"
            >
              {loading ? (
                <>
                  <RefreshCw size={14} className="animate-spin" />
                  Creating…
                </>
              ) : (
                <>
                  <Plus size={14} />
                  Create User
                </>
              )}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

function ChangePasswordModal({ user, loading, onClose, onSubmit }) {
  const [password, setPassword] = useState('')
  const [confirmPassword, setConfirmPassword] = useState('')
  const [error, setError] = useState('')

  const handleSubmit = (event) => {
    event.preventDefault()
    if (!password.trim()) {
      setError('Password is required')
      return
    }
    if (password.length < 8) {
      setError('Password must be at least 8 characters')
      return
    }
    if (password !== confirmPassword) {
      setError('Passwords do not match')
      return
    }
    setError('')
    onSubmit({ userId: user.id, password })
  }

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
      <div className="bg-white rounded-lg shadow-xl w-full max-w-md mx-4">
        <div className="flex items-center justify-between px-6 py-4 border-b border-gray-200">
          <h3 className="text-lg font-semibold text-gray-800">Change Password</h3>
          <button onClick={onClose} className="p-1 text-gray-400 hover:text-gray-600 rounded transition-colors">
            <X size={20} />
          </button>
        </div>

        <form onSubmit={handleSubmit} className="p-6 space-y-4">
          <div className="text-sm text-gray-600">
            Set a new password for <span className="font-medium text-gray-900">{user.username}</span>.
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">New Password</label>
            <input
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-gray-900 focus:border-transparent"
              placeholder="Enter a new password"
              autoFocus
            />
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Confirm Password</label>
            <input
              type="password"
              value={confirmPassword}
              onChange={(event) => setConfirmPassword(event.target.value)}
              className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-gray-900 focus:border-transparent"
              placeholder="Re-enter the new password"
            />
          </div>

          {error && <div className="text-xs text-red-500">{error}</div>}

          <div className="flex items-center justify-end gap-3 pt-4">
            <button
              type="button"
              onClick={onClose}
              className="px-4 py-2 text-sm text-gray-600 hover:text-gray-900 transition-colors"
              disabled={loading}
            >
              Cancel
            </button>
            <button
              type="submit"
              disabled={loading}
              className="flex items-center gap-2 px-4 py-2 text-sm text-white bg-gray-900 hover:bg-gray-800 rounded-md transition-colors disabled:opacity-50"
            >
              {loading ? 'Saving…' : 'Update Password'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}

function ConnectionAccessModal({ user, data, loading, onClose, onSave, onRevoke, onSavePolicy, onPreviewPolicy }) {
  const [drafts, setDrafts] = useState({})
  const [policyDrafts, setPolicyDrafts] = useState({})
  const [policyPreviews, setPolicyPreviews] = useState({})
  const [previewLoading, setPreviewLoading] = useState({})

  const assignmentsByConnection = new Map(
    (data?.assignments || []).map((assignment) => [assignment.connectionId, assignment]),
  )

  const assignableConnections = data?.assignableConnections || []

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
      <div className="bg-white rounded-lg shadow-xl w-full max-w-3xl mx-4 max-h-[80vh] overflow-hidden">
        <div className="flex items-center justify-between px-6 py-4 border-b border-gray-200">
          <div>
            <h3 className="text-lg font-semibold text-gray-800">Connection Access</h3>
            <p className="text-sm text-gray-500 mt-1">
              Manage shared admin-owned connections for <span className="font-medium text-gray-700">{user.username}</span>.
            </p>
          </div>
          <button onClick={onClose} className="p-1 text-gray-400 hover:text-gray-600 rounded transition-colors">
            <X size={20} />
          </button>
        </div>

        <div className="p-6 overflow-auto max-h-[calc(80vh-88px)]">
          {loading && !data ? (
            <div className="flex items-center justify-center h-40">
              <RefreshCw size={24} className="animate-spin text-gray-400" />
            </div>
          ) : assignableConnections.length === 0 ? (
            <div className="p-6 border border-dashed border-gray-300 rounded-lg text-sm text-gray-500">
              No admin-owned shared connections are available yet.
            </div>
          ) : (
            <div className="space-y-3">
              {assignableConnections.map((connection) => {
                const assignment = assignmentsByConnection.get(connection.connectionId)
                const draft = drafts[connection.connectionId] || assignment?.accessLevel || 'CHAT_EDITOR'
                const policyDraft = policyDrafts[connection.connectionId]
                  ?? assignment?.chatAccessPolicy?.plainEnglishPolicy
                  ?? ''
                const preview = policyPreviews[connection.connectionId] || assignment?.chatAccessPolicy

                const isAssigned = Boolean(assignment)
                const isFull = assignment?.accessLevel === 'FULL_CONTENT'
                const isDirty = isAssigned && draft !== assignment.accessLevel

                return (
                  <div
                    key={connection.connectionId}
                    className={`rounded-lg p-4 space-y-4 border-l-4 border-y border-r transition-colors ${
                      isAssigned
                        ? 'border-l-green-500 border-y-green-200 border-r-green-200 bg-green-50/40'
                        : 'border-l-gray-300 border-y-gray-200 border-r-gray-200 bg-white'
                    }`}
                  >
                    <div className="flex items-start justify-between gap-4">
                      <div className="min-w-0">
                        <div className="flex items-center gap-2 flex-wrap">
                          <span className="font-medium text-gray-900">{connection.connectionName}</span>
                          {isAssigned ? (
                            <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-semibold border ${
                              isFull
                                ? 'border-green-300 bg-green-100 text-green-800'
                                : 'border-emerald-300 bg-emerald-100 text-emerald-800'
                            }`}>
                              <CheckCircle size={12} />
                              {isFull ? 'Full Access' : 'Chat + Editor'}
                            </span>
                          ) : (
                            <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[11px] font-semibold border border-gray-300 bg-gray-100 text-gray-500">
                              <CircleSlash size={12} />
                              Not assigned
                            </span>
                          )}
                        </div>
                        <div className="text-sm text-gray-500 mt-1">{connection.dbType?.toUpperCase()}</div>
                      </div>

                      <div className="flex items-center gap-2 shrink-0">
                        <select
                          value={draft}
                          onChange={(event) => setDrafts((prev) => ({ ...prev, [connection.connectionId]: event.target.value }))}
                          className="px-3 py-2 border border-gray-300 rounded-md text-sm bg-white focus:outline-none focus:ring-2 focus:ring-gray-900 focus:border-transparent"
                          disabled={loading}
                        >
                          <option value="CHAT_EDITOR">Chat + Editor</option>
                          <option value="FULL_CONTENT">Full Access</option>
                        </select>
                        <button
                          onClick={() => onSave(user.id, connection.connectionId, draft)}
                          disabled={loading || (isAssigned && !isDirty)}
                          className="inline-flex items-center gap-1.5 px-3 py-2 text-sm font-medium text-white bg-gray-900 hover:bg-gray-800 rounded-md transition-colors disabled:opacity-40 disabled:cursor-not-allowed"
                        >
                          <CheckCircle size={14} />
                          {isAssigned ? (isDirty ? 'Update' : 'Saved') : 'Assign'}
                        </button>
                        <button
                          onClick={() => onRevoke(user.id, connection.connectionId)}
                          disabled={loading || !isAssigned}
                          className={`inline-flex items-center gap-1.5 px-3 py-2 text-sm font-medium rounded-md border transition-colors disabled:cursor-not-allowed ${
                            isAssigned
                              ? 'text-red-600 border-red-300 bg-white hover:bg-red-50'
                              : 'text-gray-300 border-gray-200 bg-gray-50'
                          }`}
                        >
                          <Trash2 size={14} />
                          Remove
                        </button>
                      </div>
                    </div>

                    <div className="border-t border-gray-100 pt-4">
                      <div className="flex items-center gap-2 mb-2">
                        <span className="text-sm font-medium text-gray-900">Chat Access Policy</span>
                        {assignment?.chatAccessPolicy?.plainEnglishPolicy ? (
                          <span className="inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-[10px] font-semibold border border-blue-200 bg-blue-50 text-blue-700">
                            <ShieldCheck size={11} />
                            Active
                          </span>
                        ) : (
                          <span className="inline-flex items-center px-2 py-0.5 rounded-full text-[10px] font-semibold border border-gray-200 bg-gray-50 text-gray-400">
                            None
                          </span>
                        )}
                      </div>
                      <textarea
                        value={policyDraft}
                        onChange={(event) => setPolicyDrafts((prev) => ({ ...prev, [connection.connectionId]: event.target.value }))}
                        placeholder="Example: This user should not access customer PII or financial data like emails, phone numbers, bank details, or card details."
                        rows={3}
                        disabled={loading}
                        className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-gray-900 focus:border-transparent"
                      />
                      <div className="flex items-center gap-2 mt-3">
                        <button
                          onClick={async () => {
                            setPreviewLoading((prev) => ({ ...prev, [connection.connectionId]: true }))
                            try {
                              const result = await onPreviewPolicy(connection.connectionId, policyDraft)
                              setPolicyPreviews((prev) => ({ ...prev, [connection.connectionId]: result }))
                            } finally {
                              setPreviewLoading((prev) => ({ ...prev, [connection.connectionId]: false }))
                            }
                          }}
                          disabled={loading || !policyDraft.trim()}
                          className="px-3 py-2 text-sm text-gray-700 border border-gray-300 rounded-md hover:bg-gray-50 disabled:opacity-50"
                        >
                          {previewLoading[connection.connectionId] ? 'Previewing…' : 'Preview'}
                        </button>
                        <button
                          onClick={() => onSavePolicy(user.id, connection.connectionId, policyDraft, true)}
                          disabled={loading || !assignment || !policyDraft.trim()}
                          className="px-3 py-2 text-sm text-white bg-gray-900 hover:bg-gray-800 rounded-md transition-colors disabled:opacity-50"
                        >
                          Save Policy
                        </button>
                      </div>
                      {!assignment && (
                        <div className="mt-2 text-xs text-amber-700">
                          Assign the connection first, then save a per-user chat policy for it.
                        </div>
                      )}
                      {assignment?.chatAccessPolicy?.allowedSchemas?.length > 0 && (
                        <div className="mt-2 text-xs text-gray-600">
                          <span className="font-medium">Resolved scope:</span>{' '}
                          {assignment.chatAccessPolicy.allowedSchemas.join(', ')}
                        </div>
                      )}

                      {preview && (
                        <div className="mt-3 rounded-md border border-gray-200 bg-gray-50 p-3 text-sm text-gray-700 space-y-2">
                          <div><span className="font-medium">Blocked sensitivity:</span> {(preview.blockedSensitivityCategories || []).join(', ') || 'None'}</div>
                          <div><span className="font-medium">Allowed schemas:</span> {(preview.allowedSchemas || []).join(', ') || 'All schemas'}</div>
                          <div><span className="font-medium">Impacted tables:</span> {(preview.impactedTables || []).join(', ') || 'None'}</div>
                          <div><span className="font-medium">Impacted columns:</span> {(preview.impactedColumns || []).join(', ') || 'None'}</div>
                          <div><span className="font-medium">Modes:</span> {preview.blockMode ? 'Block' : 'Allow'} + {preview.redactMode ? 'Redact' : 'Pass through'}</div>
                        </div>
                      )}
                    </div>
                  </div>
                )
              })}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
