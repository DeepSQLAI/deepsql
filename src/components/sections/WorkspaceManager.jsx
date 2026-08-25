'use client'

import { useCallback, useEffect, useState } from 'react'
import { Loader2, Plus, Trash2, Users, X, AlertTriangle, Shield, Eye } from 'lucide-react'
import { dashboardWorkspacesAPI, adminAPI } from '@/lib/api/client'
import { useAuth } from '@/hooks/useAuth'
import styles from './WorkspaceManager.module.css'

const COLORS = ['#534AB7', '#0E7C66', '#B4530A', '#9B2C64', '#2563EB', '#525252']

/**
 * Create and administer dashboard workspaces for one connection.
 *
 * <p>A workspace groups dashboards and carries its own member list. Access is an AND:
 * a member still needs read access to the connection, so adding someone here can never
 * grant them a database they were not already given.
 */
export default function WorkspaceManager({ connectionId, open, onClose, onChanged }) {
  const { isAdmin, username } = useAuth()
  const [workspaces, setWorkspaces] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [creating, setCreating] = useState(false)
  const [newName, setNewName] = useState('')
  const [newDescription, setNewDescription] = useState('')
  const [newColor, setNewColor] = useState(COLORS[0])

  const [selectedId, setSelectedId] = useState(null)
  const [members, setMembers] = useState([])
  const [membersLoading, setMembersLoading] = useState(false)
  const [users, setUsers] = useState([])
  const [addUsername, setAddUsername] = useState('')
  const [addRole, setAddRole] = useState('VIEWER')
  const [busy, setBusy] = useState(null)
  const [confirmDeleteId, setConfirmDeleteId] = useState(null)

  const load = useCallback(async () => {
    if (!connectionId) return
    setLoading(true)
    setError(null)
    try {
      const list = await dashboardWorkspacesAPI.listByConnection(connectionId)
      setWorkspaces(Array.isArray(list) ? list : [])
    } catch (e) {
      setError(e?.response?.data?.message || 'Could not load workspaces.')
    } finally {
      setLoading(false)
    }
  }, [connectionId])

  useEffect(() => { if (open) load() }, [open, load])

  // The member picker needs the user directory, which only admins can read. For
  // everyone else the field stays a free-text username entry rather than showing a
  // broken or empty dropdown.
  useEffect(() => {
    if (!open || !isAdmin) return
    adminAPI.listUsers()
      .then((res) => setUsers(Array.isArray(res) ? res : res?.users || []))
      .catch(() => setUsers([]))
  }, [open, isAdmin])

  const loadMembers = useCallback(async (workspaceId) => {
    setMembersLoading(true)
    try {
      const list = await dashboardWorkspacesAPI.listMembers(workspaceId)
      setMembers(Array.isArray(list) ? list : [])
    } catch (e) {
      setError(e?.response?.data?.message || 'Could not load members.')
      setMembers([])
    } finally {
      setMembersLoading(false)
    }
  }, [])

  const select = useCallback((workspace) => {
    setSelectedId(workspace.id)
    setError(null)
    loadMembers(workspace.id)
  }, [loadMembers])

  const create = useCallback(async () => {
    const name = newName.trim()
    if (!name || creating) return
    setCreating(true)
    setError(null)
    try {
      const created = await dashboardWorkspacesAPI.create({
        connectionId,
        name,
        description: newDescription.trim(),
        color: newColor,
      })
      setWorkspaces((list) => [...list, created].sort((a, b) => a.name.localeCompare(b.name)))
      setNewName('')
      setNewDescription('')
      onChanged?.()
    } catch (e) {
      setError(e?.response?.data?.message || 'Could not create the workspace.')
    } finally {
      setCreating(false)
    }
  }, [newName, newDescription, newColor, connectionId, creating, onChanged])

  const remove = useCallback(async (workspace) => {
    setBusy(workspace.id)
    setError(null)
    try {
      await dashboardWorkspacesAPI.remove(workspace.id)
      setWorkspaces((list) => list.filter((w) => w.id !== workspace.id))
      if (selectedId === workspace.id) {
        setSelectedId(null)
        setMembers([])
      }
      setConfirmDeleteId(null)
      onChanged?.()
    } catch (e) {
      setError(e?.response?.data?.message || 'Could not delete the workspace.')
    } finally {
      setBusy(null)
    }
  }, [selectedId, onChanged])

  const addMember = useCallback(async () => {
    const name = addUsername.trim()
    if (!name || !selectedId || busy) return
    setBusy('add-member')
    setError(null)
    try {
      await dashboardWorkspacesAPI.addMember(selectedId, { username: name, workspaceRole: addRole })
      await loadMembers(selectedId)
      setAddUsername('')
    } catch (e) {
      setError(e?.response?.data?.message || 'Could not add the member.')
    } finally {
      setBusy(null)
    }
  }, [addUsername, addRole, selectedId, busy, loadMembers])

  const removeMember = useCallback(async (member) => {
    if (!selectedId || busy) return
    setBusy(member.username)
    setError(null)
    try {
      await dashboardWorkspacesAPI.removeMember(selectedId, member.username)
      await loadMembers(selectedId)
    } catch (e) {
      setError(e?.response?.data?.message || 'Could not remove the member.')
    } finally {
      setBusy(null)
    }
  }, [selectedId, busy, loadMembers])

  if (!open) return null

  const selected = workspaces.find((w) => w.id === selectedId) || null

  return (
    <div className={styles.backdrop} onClick={onClose} role="presentation">
      <div className={styles.modal} onClick={(e) => e.stopPropagation()} role="dialog" aria-label="Manage workspaces">
        <header className={styles.header}>
          <div>
            <h2 className={styles.title}>Workspaces</h2>
            <p className={styles.subtitle}>
              Group dashboards and choose who can see them. Members still need access to this connection.
            </p>
          </div>
          <button className={styles.iconBtn} onClick={onClose} aria-label="Close">
            <X size={16} />
          </button>
        </header>

        {error && (
          <div className={styles.error}>
            <AlertTriangle size={14} />
            <span>{error}</span>
          </div>
        )}

        <div className={styles.body}>
          <section className={styles.listPane}>
            <div className={styles.createBox}>
              <input
                className={styles.input}
                placeholder="New workspace name"
                value={newName}
                onChange={(e) => setNewName(e.target.value)}
                onKeyDown={(e) => { if (e.key === 'Enter') create() }}
              />
              <input
                className={styles.input}
                placeholder="Description (optional)"
                value={newDescription}
                onChange={(e) => setNewDescription(e.target.value)}
              />
              <div className={styles.colorRow}>
                {COLORS.map((c) => (
                  <button
                    key={c}
                    className={`${styles.swatch} ${newColor === c ? styles.swatchActive : ''}`}
                    style={{ background: c }}
                    onClick={() => setNewColor(c)}
                    aria-label={`Use colour ${c}`}
                  />
                ))}
                <button className={styles.createBtn} onClick={create} disabled={!newName.trim() || creating}>
                  {creating ? <Loader2 size={13} className={styles.spin} /> : <Plus size={13} />} Create
                </button>
              </div>
            </div>

            {loading ? (
              <div className={styles.placeholder}><Loader2 size={16} className={styles.spin} /> Loading…</div>
            ) : workspaces.length === 0 ? (
              <div className={styles.placeholder}>No workspaces yet. Create one above.</div>
            ) : (
              <ul className={styles.list}>
                {workspaces.map((w) => (
                  <li key={w.id}>
                    <button
                      className={`${styles.listItem} ${selectedId === w.id ? styles.listItemActive : ''}`}
                      onClick={() => select(w)}
                    >
                      <span className={styles.dot} style={{ background: w.color || COLORS[0] }} />
                      <span className={styles.listName}>{w.name}</span>
                      <span className={styles.listCount}>{w.dashboardCount ?? 0}</span>
                    </button>
                  </li>
                ))}
              </ul>
            )}
          </section>

          <section className={styles.detailPane}>
            {!selected ? (
              <div className={styles.placeholder}>
                <Users size={18} />
                <span>Select a workspace to manage its members.</span>
              </div>
            ) : (
              <>
                <div className={styles.detailHead}>
                  <div>
                    <h3 className={styles.detailTitle}>{selected.name}</h3>
                    {selected.description && <p className={styles.detailSub}>{selected.description}</p>}
                    <p className={styles.detailMeta}>Created by {selected.createdBy}</p>
                  </div>
                  {confirmDeleteId === selected.id ? (
                    <div className={styles.confirmRow}>
                      <span className={styles.confirmText}>Delete? Dashboards stay, ungrouped.</span>
                      <button className={styles.ghostBtn} onClick={() => setConfirmDeleteId(null)}>Cancel</button>
                      <button className={styles.dangerBtn} onClick={() => remove(selected)} disabled={busy === selected.id}>
                        {busy === selected.id ? <Loader2 size={12} className={styles.spin} /> : 'Delete'}
                      </button>
                    </div>
                  ) : (
                    <button className={styles.iconBtn} onClick={() => setConfirmDeleteId(selected.id)} aria-label="Delete workspace">
                      <Trash2 size={15} />
                    </button>
                  )}
                </div>

                <div className={styles.addMemberRow}>
                  {isAdmin && users.length > 0 ? (
                    <select
                      className={styles.input}
                      value={addUsername}
                      onChange={(e) => setAddUsername(e.target.value)}
                    >
                      <option value="">Select a user…</option>
                      {users
                        .filter((u) => !members.some((m) => m.username.toLowerCase() === (u.username || '').toLowerCase()))
                        .map((u) => (
                          <option key={u.id || u.username} value={u.username}>
                            {u.username} · {u.email}
                          </option>
                        ))}
                    </select>
                  ) : (
                    <input
                      className={styles.input}
                      placeholder="Username"
                      value={addUsername}
                      onChange={(e) => setAddUsername(e.target.value)}
                      onKeyDown={(e) => { if (e.key === 'Enter') addMember() }}
                    />
                  )}
                  <select className={styles.roleSelect} value={addRole} onChange={(e) => setAddRole(e.target.value)}>
                    <option value="VIEWER">Viewer</option>
                    <option value="MANAGER">Manager</option>
                  </select>
                  <button className={styles.createBtn} onClick={addMember} disabled={!addUsername.trim() || busy === 'add-member'}>
                    {busy === 'add-member' ? <Loader2 size={13} className={styles.spin} /> : <Plus size={13} />} Add
                  </button>
                </div>

                {membersLoading ? (
                  <div className={styles.placeholder}><Loader2 size={16} className={styles.spin} /> Loading members…</div>
                ) : (
                  <ul className={styles.memberList}>
                    {members.map((m) => (
                      <li key={m.id} className={styles.memberRow}>
                        <span className={styles.memberIcon}>
                          {m.workspaceRole === 'MANAGER' ? <Shield size={13} /> : <Eye size={13} />}
                        </span>
                        <span className={styles.memberName}>
                          {m.username}
                          {m.username?.toLowerCase() === username?.toLowerCase() && (
                            <span className={styles.youTag}>you</span>
                          )}
                        </span>
                        <span className={styles.memberRole}>
                          {m.workspaceRole === 'MANAGER' ? 'Manager' : 'Viewer'}
                        </span>
                        <button
                          className={styles.iconBtn}
                          onClick={() => removeMember(m)}
                          disabled={busy === m.username}
                          aria-label={`Remove ${m.username}`}
                        >
                          {busy === m.username ? <Loader2 size={12} className={styles.spin} /> : <X size={13} />}
                        </button>
                      </li>
                    ))}
                    {members.length === 0 && (
                      <li className={styles.placeholder}>No members yet — only admins can see this workspace.</li>
                    )}
                  </ul>
                )}
              </>
            )}
          </section>
        </div>
      </div>
    </div>
  )
}
