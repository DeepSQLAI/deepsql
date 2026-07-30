'use client'

import { useCallback, useEffect, useState } from 'react'
import { MessageSquareLock, Copy, CheckCircle2 } from 'lucide-react'
import { slackLinkAPI } from '@/lib/api/client'

export default function SlackAccessCodePanel({ compact = false }) {
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState('')
  const [accessCode, setAccessCode] = useState(null)
  const [expiresAt, setExpiresAt] = useState(null)
  const [createdAt, setCreatedAt] = useState(null)
  const [visibleConnections, setVisibleConnections] = useState([])
  const [copied, setCopied] = useState(false)

  const loadCurrentCode = useCallback(async () => {
    setLoading(true)
    setError('')
    try {
      const [codeResult, connectionsResult] = await Promise.all([
        slackLinkAPI.getCurrentLinkCode(),
        slackLinkAPI.listVisibleConnections(),
      ])
      setAccessCode(codeResult?.code || null)
      setCreatedAt(codeResult?.createdAt || null)
      setExpiresAt(codeResult?.expiresAt || null)
      setVisibleConnections(Array.isArray(connectionsResult) ? connectionsResult : [])
    } catch (err) {
      setError(err.message || 'Failed to load Slack access code')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    void loadCurrentCode()
  }, [loadCurrentCode])

  const handleGenerateCode = async () => {
    setLoading(true)
    setError('')
    setCopied(false)
    try {
      const codeResult = await slackLinkAPI.createLinkCode()
      setAccessCode(codeResult?.code || null)
      setCreatedAt(codeResult?.createdAt || null)
      setExpiresAt(codeResult?.expiresAt || null)
    } catch (err) {
      setError(err.message || 'Failed to refresh Slack access code')
    } finally {
      setLoading(false)
    }
  }

  const handleCopyCode = async () => {
    if (!accessCode) return
    try {
      await navigator.clipboard.writeText(accessCode)
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch {
      setError('Failed to copy the Slack access code')
    }
  }

  return (
    <section className="border border-gray-200 rounded-xl p-5 space-y-4">
      <div className="flex items-center gap-2">
        <MessageSquareLock size={18} className="text-gray-600" />
        <div>
          <h3 className="text-sm font-semibold text-gray-900">Slack Access Code</h3>
          <p className="text-xs text-gray-500">
            Generate a persistent code to link your DeepSQL user in a Slack DM. The code stays active until you refresh it.
          </p>
        </div>
      </div>

      <div className="flex flex-wrap items-center gap-3">
        <button
          type="button"
          onClick={handleGenerateCode}
          disabled={loading}
          className="px-4 py-2 text-sm text-white bg-gray-900 hover:bg-gray-800 rounded-md transition-colors disabled:opacity-50"
        >
          {loading ? 'Refreshing…' : accessCode ? 'Refresh Slack Access Code' : 'Generate Slack Access Code'}
        </button>
        {accessCode && (
          <button
            type="button"
            onClick={handleCopyCode}
            className="inline-flex items-center gap-2 px-4 py-2 text-sm border border-gray-300 hover:border-gray-400 rounded-md transition-colors"
          >
            {copied ? <CheckCircle2 size={16} /> : <Copy size={16} />}
            {copied ? 'Copied' : 'Copy Code'}
          </button>
        )}
      </div>

      {error && (
        <div className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
          {error}
        </div>
      )}

      {accessCode && (
        <>
          <div className="rounded-lg border border-gray-200 bg-gray-50 px-4 py-4">
            <div className="text-xs font-medium uppercase tracking-wide text-gray-500 mb-2">Slack access code</div>
            <div className="flex flex-wrap items-center gap-3">
              <code className="text-lg font-semibold text-gray-900">{accessCode}</code>
              <span className="text-xs text-gray-500">
                {expiresAt ? `Expires: ${new Date(expiresAt).toLocaleString()}` : 'Active until refreshed'}
              </span>
              {createdAt && (
                <span className="text-xs text-gray-500">
                  Created: {new Date(createdAt).toLocaleString()}
                </span>
              )}
            </div>
          </div>

          <div className="rounded-lg border border-blue-100 bg-blue-50 px-4 py-3 text-sm text-blue-800">
            In Slack DM, send:
            <div className="mt-2 font-mono text-blue-900">link {accessCode}</div>
            <div className="mt-2 text-xs text-blue-700">
              Then choose a connection with <span className="font-mono">use &lt;connection&gt;</span>.
            </div>
          </div>

          {!compact && (
            <div>
              <div className="text-sm font-medium text-gray-800 mb-2">Connections available after linking</div>
              <div className="max-h-40 overflow-auto rounded-lg border border-gray-200">
                {visibleConnections.length === 0 ? (
                  <div className="px-4 py-3 text-sm text-gray-500">No visible connections found for this account.</div>
                ) : (
                  visibleConnections.map((connection) => (
                    <div key={connection.connectionId} className="px-4 py-3 border-b border-gray-100 last:border-b-0">
                      <div className="text-sm font-medium text-gray-900">{connection.connectionName}</div>
                      <div className="text-xs text-gray-500 mt-1">
                        {connection.accessLevel} · {connection.ownershipType}
                      </div>
                    </div>
                  ))
                )}
              </div>
            </div>
          )}
        </>
      )}

      {!loading && !accessCode && !error && (
        <div className="rounded-lg border border-gray-200 bg-gray-50 px-4 py-3 text-sm text-gray-600">
          No Slack access code has been created yet.
        </div>
      )}
    </section>
  )
}
