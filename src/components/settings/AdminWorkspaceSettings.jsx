'use client'

import { useEffect, useState } from 'react'
import { CheckCircle, Mail, RefreshCw, Settings2, Shield, Slack, AlertCircle } from 'lucide-react'
import { adminAPI } from '@/lib/api/client'
import { useAuth } from '@/hooks/useAuth'
import SlackAccessCodePanel from '@/components/settings/SlackAccessCodePanel'

const DEFAULT_EMAIL_SETTINGS = {
  host: '',
  port: 587,
  username: '',
  password: '',
  fromEmail: '',
  startTls: true,
  ssl: false,
}

const DEFAULT_SECURITY_SETTINGS = {
  workspaceEmail2faEnabled: false,
  smtpConfigured: false,
  canEnableWorkspaceEmail2fa: false,
}

const DEFAULT_SLACK_SETTINGS = {
  enabled: false,
  socketModeEnabled: false,
  deepsqlBotUsername: '',
  appToken: '',
  botToken: '',
  signingSecret: '',
  appTokenConfigured: false,
  botTokenConfigured: false,
  signingSecretConfigured: false,
}

export default function AdminWorkspaceSettings() {
  const { email } = useAuth()
  const [loading, setLoading] = useState(true)
  const [saving, setSaving] = useState(false)
  const [testingEmail, setTestingEmail] = useState(false)
  const [error, setError] = useState('')
  const [success, setSuccess] = useState('')
  const [emailSettings, setEmailSettings] = useState(DEFAULT_EMAIL_SETTINGS)
  const [securitySettings, setSecuritySettings] = useState(DEFAULT_SECURITY_SETTINGS)
  const [slackSettings, setSlackSettings] = useState(DEFAULT_SLACK_SETTINGS)
  const [testRecipient, setTestRecipient] = useState(email || '')
  const [sectionFeedback, setSectionFeedback] = useState({
    email: null,
    security: null,
    slack: null,
  })

  useEffect(() => {
    void loadSettings()
  }, [])

  const loadSettings = async () => {
    setLoading(true)
    setError('')
    try {
      const [emailConfig, securityConfig, slackConfig] = await Promise.all([
        adminAPI.getEmailSettings(),
        adminAPI.getSecuritySettings(),
        adminAPI.getSlackSettings(),
      ])

      setEmailSettings((prev) => ({
        ...prev,
        host: emailConfig.host || '',
        port: emailConfig.port || 587,
        username: emailConfig.username || '',
        password: '',
        fromEmail: emailConfig.fromEmail || '',
        startTls: emailConfig.startTls ?? true,
        ssl: emailConfig.ssl ?? false,
        passwordConfigured: emailConfig.passwordConfigured ?? false,
        configured: emailConfig.configured ?? false,
      }))
      setSecuritySettings({
        workspaceEmail2faEnabled: securityConfig.workspaceEmail2faEnabled ?? false,
        smtpConfigured: securityConfig.smtpConfigured ?? false,
        canEnableWorkspaceEmail2fa: securityConfig.canEnableWorkspaceEmail2fa ?? false,
      })
      setSlackSettings((prev) => ({
        ...prev,
        enabled: slackConfig.enabled ?? false,
        socketModeEnabled: slackConfig.socketModeEnabled ?? false,
        deepsqlBotUsername: slackConfig.deepsqlBotUsername || '',
        appToken: '',
        botToken: '',
        signingSecret: '',
        appTokenConfigured: slackConfig.appTokenConfigured ?? false,
        botTokenConfigured: slackConfig.botTokenConfigured ?? false,
        signingSecretConfigured: slackConfig.signingSecretConfigured ?? false,
      }))
    } catch (err) {
      setError(err.message || 'Failed to load workspace settings')
    } finally {
      setLoading(false)
    }
  }

  const showSuccess = (message) => {
    setSuccess(message)
    setTimeout(() => setSuccess(''), 3000)
  }

  const setFeedback = (section, type, message) => {
    setSectionFeedback((prev) => ({
      ...prev,
      [section]: { type, message },
    }))
  }

  const clearFeedback = (section) => {
    setSectionFeedback((prev) => ({
      ...prev,
      [section]: null,
    }))
  }

  const handleSaveEmail = async (event) => {
    event.preventDefault()
    setSaving(true)
    setError('')
    clearFeedback('email')
    try {
      const payload = {
        host: emailSettings.host,
        port: Number(emailSettings.port),
        username: emailSettings.username,
        password: emailSettings.password || null,
        fromEmail: emailSettings.fromEmail,
        startTls: emailSettings.startTls,
        ssl: emailSettings.ssl,
      }
      const updated = await adminAPI.updateEmailSettings(payload)
      setEmailSettings((prev) => ({
        ...prev,
        ...updated,
        password: '',
      }))
      const updatedSecurity = await adminAPI.getSecuritySettings()
      setSecuritySettings(updatedSecurity)
      showSuccess('SMTP settings saved')
      setFeedback('email', 'success', 'SMTP settings saved')
    } catch (err) {
      setError(err.message || 'Failed to save SMTP settings')
      setFeedback('email', 'error', err.message || 'Failed to save SMTP settings')
    } finally {
      setSaving(false)
    }
  }

  const handleTestEmail = async () => {
    setTestingEmail(true)
    setError('')
    clearFeedback('email')
    try {
      await adminAPI.testEmailSettings(testRecipient)
      const updatedSecurity = await adminAPI.getSecuritySettings()
      setSecuritySettings(updatedSecurity)
      showSuccess(`Test email sent to ${testRecipient}`)
      setFeedback('email', 'success', `Test email sent to ${testRecipient}`)
    } catch (err) {
      setError(err.message || 'Failed to send test email')
      setFeedback('email', 'error', err.message || 'Failed to send test email')
    } finally {
      setTestingEmail(false)
    }
  }

  const handleSaveSecurity = async (event) => {
    event.preventDefault()
    setSaving(true)
    setError('')
    clearFeedback('security')
    try {
      const updated = await adminAPI.updateSecuritySettings({
        workspaceEmail2faEnabled: securitySettings.workspaceEmail2faEnabled,
      })
      setSecuritySettings(updated)
      showSuccess('Security settings saved')
      setFeedback('security', 'success', 'Security settings saved')
    } catch (err) {
      setError(err.message || 'Failed to save security settings')
      setFeedback('security', 'error', err.message || 'Failed to save security settings')
    } finally {
      setSaving(false)
    }
  }

  const handleSaveSlack = async (event) => {
    event.preventDefault()
    setSaving(true)
    setError('')
    clearFeedback('slack')
    try {
      const updated = await adminAPI.updateSlackSettings(slackSettings)
      setSlackSettings((prev) => ({
        ...prev,
        ...updated,
        appToken: '',
        botToken: '',
        signingSecret: '',
      }))
      showSuccess('Slack settings saved')
      setFeedback('slack', 'success', 'Slack settings saved')
    } catch (err) {
      setError(err.message || 'Failed to save Slack settings')
      setFeedback('slack', 'error', err.message || 'Failed to save Slack settings')
    } finally {
      setSaving(false)
    }
  }

  if (loading) {
    return (
      <div className="flex items-center justify-center h-full min-h-[300px]">
        <RefreshCw size={24} className="animate-spin text-gray-400" />
      </div>
    )
  }

  return (
    <div className="h-full overflow-auto p-6 space-y-6 bg-white">
      <div className="flex items-center gap-3">
        <Settings2 size={20} className="text-gray-600" />
        <div>
          <h2 className="text-lg font-semibold text-gray-800">Workspace Settings</h2>
          <p className="text-sm text-gray-500">Configure SMTP, Slack, and workspace email 2FA after bootstrap.</p>
        </div>
      </div>

      {success && (
        <div className="p-3 bg-green-50 border border-green-200 rounded-lg flex items-center gap-2 text-green-700">
          <CheckCircle size={16} />
          <span className="text-sm">{success}</span>
        </div>
      )}

      {error && (
        <div className="p-3 bg-red-50 border border-red-200 rounded-lg flex items-center gap-2 text-red-700">
          <AlertCircle size={16} />
          <span className="text-sm">{error}</span>
        </div>
      )}

      <section className="border border-gray-200 rounded-xl p-5 space-y-4">
        <div className="flex items-center gap-2">
          <Mail size={18} className="text-gray-600" />
          <div>
            <h3 className="text-sm font-semibold text-gray-900">SMTP</h3>
            <p className="text-xs text-gray-500">Email delivery for sign-in verification, notifications, and invites.</p>
          </div>
        </div>

        <form onSubmit={handleSaveEmail} className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <Input label="SMTP Host" value={emailSettings.host} onChange={(value) => setEmailSettings((prev) => ({ ...prev, host: value }))} />
          <Input label="Port" type="number" value={emailSettings.port} onChange={(value) => setEmailSettings((prev) => ({ ...prev, port: value }))} />
          <Input label="Username" value={emailSettings.username} onChange={(value) => setEmailSettings((prev) => ({ ...prev, username: value }))} />
          <Input label="From Email" type="email" value={emailSettings.fromEmail} onChange={(value) => setEmailSettings((prev) => ({ ...prev, fromEmail: value }))} />
          <Input
            label={emailSettings.passwordConfigured ? 'Password (leave blank to keep current)' : 'Password'}
            type="password"
            value={emailSettings.password}
            onChange={(value) => setEmailSettings((prev) => ({ ...prev, password: value }))}
          />
          <div className="flex items-end gap-6 pb-2 text-sm text-gray-700">
            <label className="flex items-center gap-2">
              <input type="checkbox" checked={emailSettings.startTls} onChange={(event) => setEmailSettings((prev) => ({ ...prev, startTls: event.target.checked }))} />
              STARTTLS
            </label>
            <label className="flex items-center gap-2">
              <input type="checkbox" checked={emailSettings.ssl} onChange={(event) => setEmailSettings((prev) => ({ ...prev, ssl: event.target.checked }))} />
              SSL Wrapper
            </label>
          </div>

          <div className="md:col-span-2 flex flex-wrap items-center gap-3 pt-2">
            <button type="submit" disabled={saving} className="px-4 py-2 text-sm text-white bg-gray-900 hover:bg-gray-800 rounded-md transition-colors disabled:opacity-50">
              {saving ? 'Saving…' : 'Save SMTP'}
            </button>
            <div className="flex items-center gap-2">
              <input
                type="email"
                value={testRecipient}
                onChange={(event) => setTestRecipient(event.target.value)}
                placeholder="Test recipient"
                className="px-3 py-2 border border-gray-300 rounded-md text-sm"
              />
              <button type="button" disabled={testingEmail} onClick={handleTestEmail} className="px-4 py-2 text-sm border border-gray-300 hover:border-gray-400 rounded-md transition-colors disabled:opacity-50">
                {testingEmail ? 'Sending…' : 'Send Test Email'}
              </button>
            </div>
            <span className={`text-xs font-medium ${securitySettings.smtpConfigured ? 'text-green-600' : 'text-amber-600'}`}>
              {securitySettings.smtpConfigured ? 'SMTP ready' : 'SMTP not fully configured yet'}
            </span>
            {sectionFeedback.email && (
              <InlineFeedback
                type={sectionFeedback.email.type}
                message={sectionFeedback.email.message}
              />
            )}
          </div>
        </form>
      </section>

      <section className="border border-gray-200 rounded-xl p-5 space-y-4">
        <div className="flex items-center gap-2">
          <Shield size={18} className="text-gray-600" />
          <div>
            <h3 className="text-sm font-semibold text-gray-900">Security</h3>
            <p className="text-xs text-gray-500">Workspace-wide email 2FA applies to password and Google sign-ins.</p>
          </div>
        </div>

        <form onSubmit={handleSaveSecurity} className="space-y-4">
          <label className="flex items-start gap-3 text-sm text-gray-700">
            <input
              type="checkbox"
              checked={securitySettings.workspaceEmail2faEnabled}
              disabled={!securitySettings.canEnableWorkspaceEmail2fa}
              onChange={(event) => setSecuritySettings((prev) => ({ ...prev, workspaceEmail2faEnabled: event.target.checked }))}
            />
            <div>
              <div className="font-medium text-gray-900">Enable email 2FA for the workspace</div>
              <div className="text-xs text-gray-500 mt-1">
                Requires working SMTP. Users will sign in with email and password first, then complete an email verification code.
              </div>
            </div>
          </label>
          {!securitySettings.canEnableWorkspaceEmail2fa && (
            <div className="text-xs text-amber-600">
              Save and test SMTP before enabling workspace email 2FA.
            </div>
          )}
          <div className="flex flex-wrap items-center gap-3">
            <button type="submit" disabled={saving} className="px-4 py-2 text-sm text-white bg-gray-900 hover:bg-gray-800 rounded-md transition-colors disabled:opacity-50">
              {saving ? 'Saving…' : 'Save Security Settings'}
            </button>
            {sectionFeedback.security && (
              <InlineFeedback
                type={sectionFeedback.security.type}
                message={sectionFeedback.security.message}
              />
            )}
          </div>
        </form>
      </section>

      <section className="border border-gray-200 rounded-xl p-5 space-y-4">
        <div className="flex items-center gap-2">
          <Slack size={18} className="text-gray-600" />
          <div>
            <h3 className="text-sm font-semibold text-gray-900">Slack</h3>
            <p className="text-xs text-gray-500">Save workspace Slack bot settings. A backend restart may still be required for runtime reconnect.</p>
          </div>
        </div>

        <form onSubmit={handleSaveSlack} className="grid grid-cols-1 md:grid-cols-2 gap-4">
          <label className="flex items-center gap-2 text-sm text-gray-700">
            <input type="checkbox" checked={slackSettings.enabled} onChange={(event) => setSlackSettings((prev) => ({ ...prev, enabled: event.target.checked }))} />
            Slack enabled
          </label>
          <label className="flex items-center gap-2 text-sm text-gray-700">
            <input type="checkbox" checked={slackSettings.socketModeEnabled} onChange={(event) => setSlackSettings((prev) => ({ ...prev, socketModeEnabled: event.target.checked }))} />
            Socket Mode enabled
          </label>
          <Input label="DeepSQL Slack username" value={slackSettings.deepsqlBotUsername} onChange={(value) => setSlackSettings((prev) => ({ ...prev, deepsqlBotUsername: value }))} />
          <div />
          <Input label={slackSettings.appTokenConfigured ? 'App Token (leave blank to keep current)' : 'App Token'} type="password" value={slackSettings.appToken} onChange={(value) => setSlackSettings((prev) => ({ ...prev, appToken: value }))} />
          <Input label={slackSettings.botTokenConfigured ? 'Bot Token (leave blank to keep current)' : 'Bot Token'} type="password" value={slackSettings.botToken} onChange={(value) => setSlackSettings((prev) => ({ ...prev, botToken: value }))} />
          <Input label={slackSettings.signingSecretConfigured ? 'Signing Secret (leave blank to keep current)' : 'Signing Secret'} type="password" value={slackSettings.signingSecret} onChange={(value) => setSlackSettings((prev) => ({ ...prev, signingSecret: value }))} />
          <div className="md:col-span-2 text-xs text-gray-500">
            Existing secret state: App token {slackSettings.appTokenConfigured ? 'configured' : 'missing'} · Bot token {slackSettings.botTokenConfigured ? 'configured' : 'missing'} · Signing secret {slackSettings.signingSecretConfigured ? 'configured' : 'missing'}
          </div>
          <div className="md:col-span-2">
            <div className="flex flex-wrap items-center gap-3">
              <button type="submit" disabled={saving} className="px-4 py-2 text-sm text-white bg-gray-900 hover:bg-gray-800 rounded-md transition-colors disabled:opacity-50">
                {saving ? 'Saving…' : 'Save Slack Settings'}
              </button>
              {sectionFeedback.slack && (
                <InlineFeedback
                  type={sectionFeedback.slack.type}
                  message={sectionFeedback.slack.message}
                />
              )}
            </div>
          </div>
        </form>
      </section>

      <SlackAccessCodePanel />
    </div>
  )
}

function Input({ label, value, onChange, type = 'text' }) {
  return (
    <div>
      <label className="block text-sm font-medium text-gray-700 mb-1">{label}</label>
      <input
        type={type}
        value={value}
        onChange={(event) => onChange(event.target.value)}
        className="w-full px-3 py-2 border border-gray-300 rounded-md text-sm focus:outline-none focus:ring-2 focus:ring-gray-900 focus:border-transparent"
      />
    </div>
  )
}

function InlineFeedback({ type, message }) {
  const success = type === 'success'
  return (
    <div className={`inline-flex items-center gap-2 text-sm ${success ? 'text-green-700' : 'text-red-700'}`}>
      {success ? <CheckCircle size={16} /> : <AlertCircle size={16} />}
      <span>{message}</span>
    </div>
  )
}
