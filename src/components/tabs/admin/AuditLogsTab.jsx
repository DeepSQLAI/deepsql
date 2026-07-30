'use client'

import { useEffect, useMemo, useState } from 'react'
import {
  Activity,
  AlertCircle,
  RefreshCw,
  Users,
} from 'lucide-react'
import { adminAPI } from '@/lib/api/client'
import { useAuth } from '@/hooks/useAuth'

function formatDate(value) {
  if (!value) return '—'
  try {
    return new Date(value).toLocaleString()
  } catch {
    return value
  }
}

function formatEventLabel(eventType) {
  if (!eventType) return 'Activity'
  return eventType
    .toLowerCase()
    .split('_')
    .map((segment) => segment.charAt(0).toUpperCase() + segment.slice(1))
    .join(' ')
}

function toDateTimeLocalValue(value) {
  const date = value instanceof Date ? value : new Date(value)
  const offsetMs = date.getTimezoneOffset() * 60 * 1000
  return new Date(date.getTime() - offsetMs).toISOString().slice(0, 16)
}

function defaultWindow() {
  const now = new Date()
  const from = new Date(now.getTime() - 24 * 60 * 60 * 1000)
  return {
    fromTime: toDateTimeLocalValue(from),
    toTime: toDateTimeLocalValue(now),
  }
}

function buildEventDetails(event) {
  const metadata = event?.eventMetadata || {}
  const details = []

  if (metadata.connectionName || metadata.dbType) {
    details.push(
      [metadata.connectionName, metadata.dbType ? String(metadata.dbType).toUpperCase() : null]
        .filter(Boolean)
        .join(' · '),
    )
  }

  if (metadata.queryText) {
    details.push(`SQL: ${metadata.queryText}`)
  }

  if (metadata.executionTimeMs != null || metadata.rowCount != null) {
    const metrics = []
    if (metadata.executionTimeMs != null) metrics.push(`${metadata.executionTimeMs} ms`)
    if (metadata.rowCount != null) metrics.push(`${metadata.rowCount} row${metadata.rowCount === 1 ? '' : 's'}`)
    details.push(metrics.join(' · '))
  }

  if (metadata.origin) {
    details.push(`Origin: ${metadata.origin}`)
  }

  return details
}

export default function AuditLogsTab() {
  const { isAdmin } = useAuth()
  const [users, setUsers] = useState([])
  const [events, setEvents] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState(null)
  const [filters, setFilters] = useState(() => ({
    ...defaultWindow(),
    userIds: [],
  }))

  const selectedUserIds = useMemo(
    () => filters.userIds.map((value) => String(value)),
    [filters.userIds],
  )

  useEffect(() => {
    if (!isAdmin) return
    void loadUsers()
  }, [isAdmin])

  useEffect(() => {
    if (!isAdmin) return
    void loadEvents(filters)
  }, [isAdmin, filters])

  const loadUsers = async () => {
    try {
      const data = await adminAPI.listUsers()
      setUsers(Array.isArray(data) ? data : [])
    } catch (err) {
      setError(err.message || 'Failed to load users')
    }
  }

  const loadEvents = async (nextFilters) => {
    setLoading(true)
    setError(null)
    try {
      const data = await adminAPI.listSecurityEvents({
        fromTime: nextFilters.fromTime,
        toTime: nextFilters.toTime,
        userIds: nextFilters.userIds,
        size: 100,
      })
      setEvents(Array.isArray(data?.content) ? data.content : [])
    } catch (err) {
      setError(err.message || 'Failed to load audit logs')
      setEvents([])
    } finally {
      setLoading(false)
    }
  }

  const updateFilter = (key, value) => {
    setFilters((prev) => ({ ...prev, [key]: value }))
  }

  const handleUserMultiSelect = (event) => {
    const values = Array.from(event.target.selectedOptions, (option) => Number(option.value))
    updateFilter('userIds', values)
  }

  const resetFilters = () => {
    setFilters({
      ...defaultWindow(),
      userIds: [],
    })
  }

  if (!isAdmin) {
    return (
      <div className="flex flex-col items-center justify-center h-full min-h-[400px] text-gray-500">
        <Activity size={48} className="mb-4 text-gray-400" />
        <h3 className="text-lg font-medium text-gray-700 mb-2">Admin Access Required</h3>
        <p className="text-sm text-gray-500">You need administrator privileges to access audit logs.</p>
      </div>
    )
  }

  return (
    <div className="h-full flex flex-col bg-white">
      <div className="flex items-center justify-between px-6 py-4 border-b border-gray-200">
        <div className="flex items-center gap-3">
          <Activity size={20} className="text-gray-600" />
          <div>
            <h2 className="text-lg font-semibold text-gray-800">Audit Logs</h2>
            <p className="text-sm text-gray-500">Showing security and editor activity for the last 24 hours by default.</p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <button
            onClick={resetFilters}
            className="px-3 py-1.5 text-sm text-gray-600 hover:text-gray-900 hover:bg-gray-100 rounded-md transition-colors"
          >
            Reset
          </button>
          <button
            onClick={() => void loadEvents(filters)}
            disabled={loading}
            className="flex items-center gap-2 px-3 py-1.5 text-sm text-gray-600 hover:text-gray-900 hover:bg-gray-100 rounded-md transition-colors disabled:opacity-50"
          >
            <RefreshCw size={14} className={loading ? 'animate-spin' : ''} />
            Refresh
          </button>
        </div>
      </div>

      {error && (
        <div className="mx-6 mt-4 p-3 bg-red-50 border border-red-200 rounded-lg flex items-center gap-2 text-red-700">
          <AlertCircle size={16} />
          <span className="text-sm">{error}</span>
        </div>
      )}

      <div className="flex-1 overflow-auto p-6 space-y-6">
        <div className="border border-gray-200 rounded-lg p-4">
          <div className="grid grid-cols-1 xl:grid-cols-[1fr_1fr_320px] gap-4">
            <label className="block">
              <span className="block text-xs font-medium uppercase tracking-wide text-gray-500 mb-2">From</span>
              <input
                type="datetime-local"
                value={filters.fromTime}
                onChange={(event) => updateFilter('fromTime', event.target.value)}
                className="w-full px-3 py-2 text-sm border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-gray-900 focus:border-transparent"
              />
            </label>

            <label className="block">
              <span className="block text-xs font-medium uppercase tracking-wide text-gray-500 mb-2">To</span>
              <input
                type="datetime-local"
                value={filters.toTime}
                onChange={(event) => updateFilter('toTime', event.target.value)}
                className="w-full px-3 py-2 text-sm border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-gray-900 focus:border-transparent"
              />
            </label>

            <label className="block">
              <span className="block text-xs font-medium uppercase tracking-wide text-gray-500 mb-2">Users</span>
              <select
                multiple
                value={selectedUserIds}
                onChange={handleUserMultiSelect}
                className="w-full min-h-[108px] px-3 py-2 text-sm border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-gray-900 focus:border-transparent"
              >
                {users.map((user) => (
                  <option key={user.id} value={String(user.id)}>
                    {user.username} · {user.email}
                  </option>
                ))}
              </select>
              <p className="text-xs text-gray-500 mt-2">Hold Command or Ctrl to select multiple users.</p>
            </label>
          </div>

          <div className="flex items-center gap-2 mt-4 text-xs text-gray-500">
            <Users size={14} />
            <span>
              {filters.userIds.length === 0
                ? 'All users included'
                : `${filters.userIds.length} user${filters.userIds.length === 1 ? '' : 's'} selected`}
            </span>
          </div>
        </div>

        <div className="border border-gray-200 rounded-lg overflow-hidden">
          <div className="px-4 py-3 bg-gray-50 border-b border-gray-200 flex items-center justify-between">
            <div className="text-sm font-medium text-gray-800">Recent Activity</div>
            <div className="text-xs text-gray-500">{events.length} event{events.length === 1 ? '' : 's'}</div>
          </div>

          {loading ? (
            <div className="flex items-center justify-center h-48">
              <RefreshCw size={24} className="animate-spin text-gray-400" />
            </div>
          ) : events.length === 0 ? (
            <div className="p-6 text-sm text-gray-500">No audit logs found for the selected window and users.</div>
          ) : (
            <div className="divide-y divide-gray-200">
              {events.map((event) => (
                <div key={event.id} className="p-4">
                  <div className="flex items-start justify-between gap-4">
                    <div className="min-w-0">
                      <div className="flex items-center gap-2 flex-wrap">
                        <span className="text-sm font-semibold text-gray-900">{formatEventLabel(event.eventType)}</span>
                        <span className={`inline-flex items-center px-2 py-0.5 rounded-full text-[11px] font-medium border ${
                          event.outcome === 'SUCCESS'
                            ? 'bg-green-50 text-green-700 border-green-200'
                            : event.outcome === 'FAILURE'
                              ? 'bg-red-50 text-red-700 border-red-200'
                              : 'bg-gray-50 text-gray-700 border-gray-200'
                        }`}>
                          {event.outcome}
                        </span>
                      </div>
                      <div className="text-xs text-gray-500 mt-1">
                        {formatDate(event.createdAt)}
                        {event.clientIp ? ` · ${event.clientIp}` : ''}
                        {event.email ? ` · ${event.email}` : ''}
                      </div>
                    </div>
                    <div className="text-xs text-gray-500 text-right">
                      {event.targetResource || '—'}
                    </div>
                  </div>

                  <div className="mt-3 space-y-2">
                    {buildEventDetails(event).map((detail, index) => (
                      <div
                        key={`${event.id}-detail-${index}`}
                        className="text-sm text-gray-700 break-words whitespace-pre-wrap"
                      >
                        {detail}
                      </div>
                    ))}
                    {event.reason && (
                      <div className="text-sm text-red-600 break-words whitespace-pre-wrap">{event.reason}</div>
                    )}
                  </div>
                </div>
              ))}
            </div>
          )}
        </div>
      </div>
    </div>
  )
}
