'use client'

import { useCallback, useMemo, useState } from 'react'
import { Plus, Trash2, RefreshCw, AlertCircle, Pencil, X, ShieldCheck } from 'lucide-react'
import { permissionsAPI } from '@/lib/api/client'
import { roleLabel } from '@/lib/permissions'

/**
 * Permissions grouped for the role editor, so an admin ticks "which menus" rather than
 * reading a flat list of 25 codes. Any permission not listed here still exists on the
 * backend — it simply isn't offered as a checkbox.
 */
const PERMISSION_GROUPS = [
  {
    label: 'Sections',
    items: [
      ['VIEW_AGENT', 'Agent'],
      ['VIEW_DASHBOARDS', 'Dashboards'],
      ['VIEW_DIGEST', 'Digest'],
      ['VIEW_BRAIN', 'Brain'],
      ['VIEW_PERFORMANCE', 'Performance'],
      ['VIEW_EDITOR', 'Editor'],
    ],
  },
  {
    label: 'Working with data',
    items: [
      ['EXECUTE_QUERIES', 'Run SQL queries'],
      ['USE_CHAT', 'Use the AI assistant'],
      ['EXPORT_DATA', 'Export results'],
      ['MANAGE_DASHBOARD_WORKSPACES', 'Create dashboard workspaces'],
      ['VIEW_SLOW_QUERIES', 'View slow queries'],
      ['VIEW_SCHEMA', 'Browse schema'],
    ],
  },
  {
    label: 'Administration',
    items: [
      ['MANAGE_CONNECTIONS', 'Connection settings'],
      ['MANAGE_SETTINGS', 'System settings'],
      ['RUN_ANALYSIS', 'Run analysis tasks'],
      ['MANAGE_ALERTS', 'Manage alerts'],
      ['MANAGE_USERS', 'Manage users'],
      ['MANAGE_PERMISSIONS', 'Manage roles'],
    ],
  },
]

const EMPTY_DRAFT = { code: null, name: '', description: '', permissions: [] }

/**
 * Create and edit custom roles.
 *
 * <p>Built-in roles are shown read-only: their permission sets are code, so changing one
 * is what the override endpoint is for, and editing them here would not survive an
 * upgrade. Deleting a custom role is refused by the backend while any user still holds it.
 */
export default function RoleManager({ roles = [], onChanged }) {
  const [draft, setDraft] = useState(null)
  const [saving, setSaving] = useState(false)
  const [deletingCode, setDeletingCode] = useState(null)
  const [confirmCode, setConfirmCode] = useState(null)
  const [error, setError] = useState(null)

  const customRoles = useMemo(
    () => roles.filter((role) => role.builtIn === false),
    [roles],
  )

  const togglePermission = useCallback((code) => {
    setDraft((current) => {
      if (!current) return current
      const has = current.permissions.includes(code)
      return {
        ...current,
        permissions: has
          ? current.permissions.filter((p) => p !== code)
          : [...current.permissions, code],
      }
    })
  }, [])

  const startCreate = useCallback(() => {
    setError(null)
    setDraft({ ...EMPTY_DRAFT })
  }, [])

  const startEdit = useCallback((role) => {
    setError(null)
    setDraft({
      code: role.name,
      name: role.displayName || roleLabel(role.name),
      description: role.description || '',
      permissions: (role.permissions || []).map((p) => (typeof p === 'string' ? p : p?.name)).filter(Boolean),
    })
  }, [])

  const save = useCallback(async () => {
    if (!draft || saving) return
    const name = draft.name.trim()
    if (!name) {
      setError('Give the role a name.')
      return
    }
    setSaving(true)
    setError(null)
    try {
      if (draft.code) {
        await permissionsAPI.updateRole(draft.code, {
          name,
          description: draft.description,
          permissions: draft.permissions,
        })
      } else {
        await permissionsAPI.createRole({
          name,
          description: draft.description,
          permissions: draft.permissions,
        })
      }
      setDraft(null)
      onChanged?.()
    } catch (e) {
      setError(e?.response?.data?.message || 'Could not save the role.')
    } finally {
      setSaving(false)
    }
  }, [draft, saving, onChanged])

  const remove = useCallback(async (role) => {
    setDeletingCode(role.name)
    setError(null)
    try {
      await permissionsAPI.deleteRole(role.name)
      setConfirmCode(null)
      onChanged?.()
    } catch (e) {
      setError(e?.response?.data?.message || 'Could not delete the role.')
    } finally {
      setDeletingCode(null)
    }
  }, [onChanged])

  return (
    <div className="border border-gray-200 rounded-lg p-4">
      <div className="flex items-center justify-between gap-3 mb-3">
        <div className="flex items-center gap-2">
          <ShieldCheck size={16} className="text-gray-600" />
          <h3 className="text-sm font-semibold text-gray-800">Custom roles</h3>
        </div>
        {!draft && (
          <button
            onClick={startCreate}
            className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-white bg-gray-900 rounded-md hover:bg-gray-800"
          >
            <Plus size={13} /> New role
          </button>
        )}
      </div>

      {error && (
        <div className="flex items-start gap-2 mb-3 px-3 py-2 text-xs text-red-700 bg-red-50 border border-red-200 rounded-md">
          <AlertCircle size={13} className="mt-0.5 shrink-0" />
          <span>{error}</span>
        </div>
      )}

      {customRoles.length === 0 && !draft && (
        <p className="text-xs text-gray-500">
          No custom roles yet. Create one to define an exact set of sections and capabilities.
        </p>
      )}

      {customRoles.length > 0 && (
        <ul className="divide-y divide-gray-100 mb-3">
          {customRoles.map((role) => (
            <li key={role.name} className="py-2 flex items-start justify-between gap-3">
              <div className="min-w-0">
                <div className="text-sm font-medium text-gray-800">
                  {role.displayName || roleLabel(role.name)}
                </div>
                <div className="text-xs text-gray-500 truncate">
                  {(role.permissions || []).length} permission
                  {(role.permissions || []).length === 1 ? '' : 's'}
                  {role.description ? ` · ${role.description}` : ''}
                </div>
              </div>
              <div className="flex items-center gap-1 shrink-0">
                <button
                  onClick={() => startEdit(role)}
                  className="p-1.5 text-gray-500 hover:text-gray-900 hover:bg-gray-100 rounded"
                  aria-label={`Edit ${role.name}`}
                >
                  <Pencil size={13} />
                </button>
                {confirmCode === role.name ? (
                  <>
                    <button
                      onClick={() => setConfirmCode(null)}
                      className="px-2 py-1 text-[11px] text-gray-600 border border-gray-200 rounded"
                    >
                      Cancel
                    </button>
                    <button
                      onClick={() => remove(role)}
                      disabled={deletingCode === role.name}
                      className="px-2 py-1 text-[11px] text-white bg-red-600 rounded disabled:opacity-50"
                    >
                      {deletingCode === role.name ? <RefreshCw size={11} className="animate-spin" /> : 'Delete'}
                    </button>
                  </>
                ) : (
                  <button
                    onClick={() => setConfirmCode(role.name)}
                    className="p-1.5 text-gray-500 hover:text-red-600 hover:bg-red-50 rounded"
                    aria-label={`Delete ${role.name}`}
                  >
                    <Trash2 size={13} />
                  </button>
                )}
              </div>
            </li>
          ))}
        </ul>
      )}

      {draft && (
        <div className="border border-gray-200 rounded-md p-3 bg-gray-50">
          <div className="flex items-center justify-between gap-3 mb-3">
            <h4 className="text-xs font-semibold text-gray-700 uppercase tracking-wide">
              {draft.code ? `Edit ${roleLabel(draft.code)}` : 'New role'}
            </h4>
            <button
              onClick={() => { setDraft(null); setError(null) }}
              className="p-1 text-gray-400 hover:text-gray-700 rounded"
              aria-label="Cancel"
            >
              <X size={14} />
            </button>
          </div>

          <div className="grid grid-cols-1 sm:grid-cols-2 gap-2 mb-3">
            <input
              value={draft.name}
              onChange={(e) => setDraft({ ...draft, name: e.target.value })}
              placeholder="Role name (e.g. Analyst)"
              className="px-3 py-1.5 text-sm border border-gray-200 rounded-md focus:outline-none focus:ring-2 focus:ring-gray-900"
            />
            <input
              value={draft.description}
              onChange={(e) => setDraft({ ...draft, description: e.target.value })}
              placeholder="Description (optional)"
              className="px-3 py-1.5 text-sm border border-gray-200 rounded-md focus:outline-none focus:ring-2 focus:ring-gray-900"
            />
          </div>
          {draft.code && (
            <p className="mb-3 text-[11px] text-gray-500">
              Renaming changes the label only — people already assigned keep this role.
            </p>
          )}

          <div className="space-y-3">
            {PERMISSION_GROUPS.map((group) => (
              <div key={group.label}>
                <div className="text-[11px] font-medium text-gray-500 uppercase tracking-wide mb-1.5">
                  {group.label}
                </div>
                <div className="grid grid-cols-2 sm:grid-cols-3 gap-x-4 gap-y-1.5">
                  {group.items.map(([code, label]) => (
                    <label key={code} className="flex items-center gap-2 text-xs text-gray-700 cursor-pointer">
                      <input
                        type="checkbox"
                        checked={draft.permissions.includes(code)}
                        onChange={() => togglePermission(code)}
                        className="rounded border-gray-300 text-gray-900 focus:ring-gray-900"
                      />
                      {label}
                    </label>
                  ))}
                </div>
              </div>
            ))}
          </div>

          <div className="flex items-center justify-end gap-2 mt-4">
            <button
              onClick={() => { setDraft(null); setError(null) }}
              className="px-3 py-1.5 text-xs text-gray-600 border border-gray-200 rounded-md hover:bg-gray-100"
            >
              Cancel
            </button>
            <button
              onClick={save}
              disabled={saving || !draft.name.trim()}
              className="inline-flex items-center gap-1.5 px-3 py-1.5 text-xs font-medium text-white bg-gray-900 rounded-md hover:bg-gray-800 disabled:opacity-50"
            >
              {saving && <RefreshCw size={12} className="animate-spin" />}
              {draft.code ? 'Save changes' : 'Create role'}
            </button>
          </div>
        </div>
      )}
    </div>
  )
}
