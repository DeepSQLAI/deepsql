import { useState } from 'react'
import { Database, CheckCircle, XCircle, Loader2, ChevronDown, ChevronUp } from 'lucide-react'
import { connectionAPI } from '@/lib/api/client'

const DB_TYPES = [
  { value: 'postgres', label: 'PostgreSQL' },
  { value: 'mysql',    label: 'MySQL' },
]

const DEFAULT_PORTS = { postgres: '5432', mysql: '3306' }

export default function StepDatabase({ data, onUpdate }) {
  const [form, setForm] = useState({
    dbType:   data.dbType   || 'postgres',
    host:     data.host     || '',
    port:     data.port     || '5432',
    database: data.database || '',
    username: data.username || '',
    password: data.password || '',
    sslMode:  data.sslMode  || 'prefer',
  })
  const [testState, setTestState]   = useState('idle') // idle | testing | ok | error
  const [testError, setTestError]   = useState('')
  const [showAdvanced, setShowAdvanced] = useState(false)

  function update(field, value) {
    const next = { ...form, [field]: value }
    if (field === 'dbType') next.port = DEFAULT_PORTS[value] || form.port
    setForm(next)
    onUpdate({ ...next, tested: false })
    setTestState('idle')
  }

  async function handleTest() {
    if (!form.host || !form.database || !form.username) return
    setTestState('testing')
    setTestError('')
    try {
      await connectionAPI.testConnection({
        dbType:   form.dbType,
        host:     form.host,
        port:     parseInt(form.port) || 5432,
        database: form.database,
        username: form.username,
        password: form.password,
        sslMode:  form.sslMode,
      })
      setTestState('ok')
      onUpdate({ ...form, tested: true })
    } catch (err) {
      const msg = err.response?.data?.message || err.message || 'Connection failed'
      setTestError(msg)
      setTestState('error')
    }
  }

  const canTest = form.host && form.database && form.username

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-2xl font-semibold text-gray-900 tracking-tight">Connect your database</h2>
        <p className="mt-2 text-sm text-gray-500">
          DeepSQL reads metadata only — it never modifies your data.
        </p>
      </div>

      {/* DB type */}
      <div className="space-y-2">
        <label className="text-sm font-medium text-gray-700">Database type</label>
        <div className="flex gap-2">
          {DB_TYPES.map(t => (
            <button
              key={t.value}
              type="button"
              onClick={() => update('dbType', t.value)}
              className={`flex-1 py-2 px-4 rounded-lg border text-sm font-medium transition-all ${
                form.dbType === t.value
                  ? 'border-gray-900 !bg-gray-900 !text-white'
                  : 'border-gray-200 !bg-white text-gray-700 hover:border-gray-400'
              }`}
            >
              {t.label}
            </button>
          ))}
        </div>
      </div>

      {/* Host + Port */}
      <div className="grid grid-cols-3 gap-3">
        <div className="col-span-2 space-y-1">
          <label className="text-xs font-medium text-gray-600 uppercase tracking-wide">Host</label>
          <input
            value={form.host}
            onChange={e => update('host', e.target.value)}
            placeholder="localhost or db.example.com"
            className="input-field w-full"
          />
        </div>
        <div className="space-y-1">
          <label className="text-xs font-medium text-gray-600 uppercase tracking-wide">Port</label>
          <input
            value={form.port}
            onChange={e => update('port', e.target.value)}
            placeholder="5432"
            className="input-field w-full"
          />
        </div>
      </div>

      {/* Database name */}
      <div className="space-y-1">
        <label className="text-xs font-medium text-gray-600 uppercase tracking-wide">Database name</label>
        <input
          value={form.database}
          onChange={e => update('database', e.target.value)}
          placeholder="myapp_production"
          className="input-field w-full"
        />
      </div>

      {/* Credentials */}
      <div className="grid grid-cols-2 gap-3">
        <div className="space-y-1">
          <label className="text-xs font-medium text-gray-600 uppercase tracking-wide">Username</label>
          <input
            value={form.username}
            onChange={e => update('username', e.target.value)}
            placeholder="readonly_user"
            className="input-field w-full"
          />
        </div>
        <div className="space-y-1">
          <label className="text-xs font-medium text-gray-600 uppercase tracking-wide">Password</label>
          <input
            type="password"
            value={form.password}
            onChange={e => update('password', e.target.value)}
            placeholder="••••••••"
            className="input-field w-full"
          />
        </div>
      </div>

      {/* Advanced */}
      <button
        type="button"
        onClick={() => setShowAdvanced(v => !v)}
        className="flex items-center gap-1 text-xs text-gray-400 hover:text-gray-600 transition-colors"
      >
        {showAdvanced ? <ChevronUp size={13} /> : <ChevronDown size={13} />}
        Advanced (SSL, SSH tunnel)
      </button>
      {showAdvanced && (
        <div className="pl-3 border-l-2 border-gray-100 space-y-3">
          <div className="space-y-1">
            <label className="text-xs font-medium text-gray-600 uppercase tracking-wide">SSL mode</label>
            <select
              value={form.sslMode}
              onChange={e => update('sslMode', e.target.value)}
              className="input-field w-full"
            >
              <option value="disable">Disable</option>
              <option value="allow">Allow</option>
              <option value="prefer">Prefer (default)</option>
              <option value="require">Require</option>
              <option value="verify-ca">Verify CA</option>
              <option value="verify-full">Verify Full</option>
            </select>
          </div>
          <p className="text-xs text-gray-400">
            SSH tunnel and certificate upload are available in Connection Settings after setup.
          </p>
        </div>
      )}

      {/* Test button */}
      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={handleTest}
          disabled={!canTest || testState === 'testing'}
          className={`flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium border transition-all ${
            !canTest
              ? 'border-gray-200 text-gray-300 cursor-not-allowed'
              : 'border-gray-900 !bg-gray-900 !text-white hover:!bg-gray-700'
          }`}
        >
          {testState === 'testing' && <Loader2 size={14} className="animate-spin" />}
          {testState === 'idle'    && <Database size={14} />}
          {testState === 'ok'     && <CheckCircle size={14} />}
          {testState === 'error'  && <XCircle size={14} />}
          {testState === 'testing' ? 'Testing…' : 'Test connection'}
        </button>

        {testState === 'ok' && (
          <span className="flex items-center gap-1.5 text-sm text-green-600 font-medium">
            <CheckCircle size={14} />
            Connected successfully
          </span>
        )}
        {testState === 'error' && (
          <span className="flex items-center gap-1.5 text-sm text-red-500">
            <XCircle size={14} />
            {testError}
          </span>
        )}
      </div>

      <p className="text-xs text-gray-400">
        We recommend a read-only user with access to{' '}
        <code className="bg-gray-100 px-1 rounded">pg_stat_statements</code> and schema metadata.
      </p>
    </div>
  )
}
