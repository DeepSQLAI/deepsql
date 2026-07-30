'use client'

import { useState, useEffect, useRef } from 'react'
import { User, Settings, LogOut, MessageSquareLock, Copy, CheckCircle2, RefreshCw } from 'lucide-react'
import { useAuth } from '@/hooks/useAuth'
import { slackLinkAPI } from '@/lib/api/client'
import SettingsModal from './SettingsModal'
import styles from './UserMenu.module.css'

export default function UserMenu() {
    const { logout, username, email } = useAuth()
    const [isOpen, setIsOpen] = useState(false)
    const [isSettingsOpen, setIsSettingsOpen] = useState(false)
    const [isSlackCodeOpen, setIsSlackCodeOpen] = useState(false)
    const [slackCode, setSlackCode] = useState(null)
    const [slackCreatedAt, setSlackCreatedAt] = useState(null)
    const [slackExpiresAt, setSlackExpiresAt] = useState(null)
    const [slackConnections, setSlackConnections] = useState([])
    const [slackLoading, setSlackLoading] = useState(false)
    const [slackError, setSlackError] = useState(null)
    const [copied, setCopied] = useState(false)
    const menuRef = useRef(null)

    useEffect(() => {
        // Close menu when clicking outside
        const handleClickOutside = (event) => {
            if (menuRef.current && !menuRef.current.contains(event.target)) {
                setIsOpen(false)
            }
        }

        if (isOpen) {
            document.addEventListener('mousedown', handleClickOutside)
            return () => document.removeEventListener('mousedown', handleClickOutside)
        }
    }, [isOpen])

    const handleLogout = () => {
        logout()
    }

    const openSlackCode = async () => {
        setIsOpen(false)
        setIsSlackCodeOpen(true)
        setSlackLoading(true)
        setSlackError(null)
        setCopied(false)
        try {
            const [codeResult, connectionsResult] = await Promise.all([
                slackLinkAPI.getCurrentLinkCode(),
                slackLinkAPI.listVisibleConnections(),
            ])
            setSlackCode(codeResult?.code || null)
            setSlackCreatedAt(codeResult?.createdAt || null)
            setSlackExpiresAt(codeResult?.expiresAt || null)
            setSlackConnections(Array.isArray(connectionsResult) ? connectionsResult : [])
        } catch (error) {
            setSlackError(error.message || 'Failed to load Slack access code')
        } finally {
            setSlackLoading(false)
        }
    }

    const refreshSlackCode = async () => {
        setSlackLoading(true)
        setSlackError(null)
        setCopied(false)
        try {
            const codeResult = await slackLinkAPI.createLinkCode()
            setSlackCode(codeResult?.code || null)
            setSlackCreatedAt(codeResult?.createdAt || null)
            setSlackExpiresAt(codeResult?.expiresAt || null)
        } catch (error) {
            setSlackError(error.message || 'Failed to refresh Slack access code')
        } finally {
            setSlackLoading(false)
        }
    }

    const copySlackCode = async () => {
        if (!slackCode) return
        try {
            await navigator.clipboard.writeText(slackCode)
            setCopied(true)
            setTimeout(() => setCopied(false), 2000)
        } catch {
            setSlackError('Failed to copy the Slack access code')
        }
    }

    return (
        <div className={styles.userMenu} ref={menuRef}>
            <button
                className={styles.trigger}
                onClick={() => setIsOpen(!isOpen)}
                title={`User menu (${username})`}
            >
                <div className={styles.avatar}>
                    <User size={16} />
                </div>
            </button>

            {isOpen && (
                <div className={styles.dropdown}>
                    <div className={styles.userInfo}>
                        <div className={styles.avatarLarge}>
                            <User size={24} />
                        </div>
                        <div className={styles.userDetails}>
                            <div className={styles.name}>{username}</div>
                            <div className={styles.email}>{email || 'Logged in'}</div>
                        </div>
                    </div>

                    <div className={styles.divider} />

                    <div className={styles.menuItems}>
                        <button
                            className={styles.menuItem}
                            onClick={() => {
                                setIsOpen(false)
                                setIsSettingsOpen(true)
                            }}
                        >
                            <Settings size={16} />
                            <span>Settings</span>
                        </button>

                        <button
                            className={styles.menuItem}
                            onClick={openSlackCode}
                        >
                            <MessageSquareLock size={16} />
                            <span>Slack Access Code</span>
                        </button>

                        <button
                            className={styles.menuItem}
                            onClick={handleLogout}
                        >
                            <LogOut size={16} />
                            <span>Logout</span>
                        </button>
                    </div>
                </div>
            )}

            <SettingsModal
                isOpen={isSettingsOpen}
                onClose={() => setIsSettingsOpen(false)}
            />

            {isSlackCodeOpen && (
                <div className="fixed inset-0 z-[100] bg-black/50 flex items-center justify-center p-4">
                    <div className="w-full max-w-lg rounded-xl bg-white shadow-2xl border border-gray-200">
                        <div className="flex items-center justify-between px-6 py-4 border-b border-gray-200">
                            <div>
                                <h3 className="text-lg font-semibold text-gray-900">Slack Access Code</h3>
                                <p className="text-sm text-gray-500 mt-1">Use this persistent code in a Slack DM: <span className="font-medium text-gray-700">link &lt;code&gt;</span></p>
                            </div>
                            <button
                                className="text-gray-400 hover:text-gray-600"
                                onClick={() => setIsSlackCodeOpen(false)}
                            >
                                <RefreshCw size={0} className="hidden" />
                                <span className="text-xl leading-none">×</span>
                            </button>
                        </div>

                        <div className="px-6 py-5 space-y-4">
                            {slackLoading ? (
                                <div className="flex items-center gap-2 text-sm text-gray-500">
                                    <RefreshCw size={16} className="animate-spin" />
                                    Loading Slack access code...
                                </div>
                            ) : slackError ? (
                                <div className="rounded-lg border border-red-200 bg-red-50 px-4 py-3 text-sm text-red-700">
                                    {slackError}
                                </div>
                            ) : (
                                <>
                                    <div className="rounded-lg border border-gray-200 bg-gray-50 px-4 py-4">
                                        <div className="text-xs font-medium uppercase tracking-wide text-gray-500 mb-2">Slack access code</div>
                                        <div className="flex items-center gap-3">
                                            <code className="text-lg font-semibold text-gray-900">{slackCode || '—'}</code>
                                            <button
                                                className="inline-flex items-center gap-2 rounded-md border border-gray-300 px-3 py-2 text-sm text-gray-700 hover:bg-white"
                                                onClick={copySlackCode}
                                                disabled={!slackCode}
                                            >
                                                {copied ? <CheckCircle2 size={16} /> : <Copy size={16} />}
                                                {copied ? 'Copied' : 'Copy'}
                                            </button>
                                            <button
                                                className="inline-flex items-center gap-2 rounded-md border border-gray-300 px-3 py-2 text-sm text-gray-700 hover:bg-white"
                                                onClick={refreshSlackCode}
                                            >
                                                <RefreshCw size={16} />
                                                Refresh
                                            </button>
                                        </div>
                                        <div className="mt-2 text-xs text-gray-500">
                                            {slackExpiresAt ? `Expires: ${new Date(slackExpiresAt).toLocaleString()}` : 'Active until refreshed'}
                                        </div>
                                        {slackCreatedAt && (
                                            <div className="mt-1 text-xs text-gray-500">
                                                Created: {new Date(slackCreatedAt).toLocaleString()}
                                            </div>
                                        )}
                                    </div>

                                    <div className="rounded-lg border border-blue-100 bg-blue-50 px-4 py-3 text-sm text-blue-800">
                                        In Slack DM:
                                        <div className="mt-2 font-mono text-blue-900">link {slackCode || '&lt;code&gt;'}</div>
                                    </div>

                                    <div>
                                        <div className="text-sm font-medium text-gray-800 mb-2">Connections available after linking</div>
                                        <div className="max-h-40 overflow-auto rounded-lg border border-gray-200">
                                            {(slackConnections || []).length === 0 ? (
                                                <div className="px-4 py-3 text-sm text-gray-500">No visible connections found for this account.</div>
                                            ) : (
                                                slackConnections.map((connection) => (
                                                    <div key={connection.connectionId} className="px-4 py-3 border-b border-gray-100 last:border-b-0">
                                                        <div className="text-sm font-medium text-gray-900">{connection.connectionName}</div>
                                                        <div className="text-xs text-gray-500 mt-1">{connection.accessLevel} · {connection.ownershipType}</div>
                                                    </div>
                                                ))
                                            )}
                                        </div>
                                    </div>
                                </>
                            )}
                        </div>
                    </div>
                </div>
            )}
        </div>
    )
}
