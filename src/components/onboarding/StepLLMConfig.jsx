import { useState } from 'react'
import { Eye, EyeOff, CheckCircle, XCircle, Loader2, ExternalLink } from 'lucide-react'
import { setupAPI } from '@/lib/api/client'

const PROVIDERS = [
  { id: 'openai',       label: 'OpenAI',        placeholder: 'sk-proj-…',    link: 'https://platform.openai.com/api-keys' },
  { id: 'azure-openai', label: 'Azure OpenAI',  placeholder: 'Azure API key', link: 'https://portal.azure.com' },
]

const OPENAI_MODELS = [
  { value: 'gpt-4o',        label: 'GPT-4o (recommended)' },
  { value: 'gpt-4o-mini',   label: 'GPT-4o mini (faster, cheaper)' },
  { value: 'gpt-4-turbo',   label: 'GPT-4 Turbo' },
  { value: 'gpt-3.5-turbo', label: 'GPT-3.5 Turbo' },
]

export default function StepLLMConfig({ data, onUpdate }) {
  const [provider,  setProvider]  = useState(data.provider  || 'openai')
  const [apiKey,    setApiKey]    = useState(data.apiKey    || '')
  const [endpoint,  setEndpoint]  = useState(data.endpoint  || '')
  const [chatModel, setChatModel] = useState(data.chatModel || 'gpt-4o')
  const [showKey,   setShowKey]   = useState(false)
  const [testState, setTestState] = useState('idle')    // idle | testing | ok | error
  const [testError, setTestError] = useState('')

  function notifyParent(overrides = {}) {
    onUpdate({
      provider, apiKey, endpoint, chatModel,
      ...overrides,
      configured: testState === 'ok' || overrides.configured,
    })
  }

  async function handleTest() {
    if (!apiKey.trim()) return
    setTestState('testing')
    setTestError('')
    try {
      const res = await setupAPI.testLlmConfig({
        provider,
        apiKey: apiKey.trim(),
        endpoint: endpoint.trim() || undefined,
      })
      if (res.valid) {
        setTestState('ok')
        notifyParent({ configured: true })
      } else {
        setTestState('error')
        setTestError(res.error || 'Invalid API key')
        notifyParent({ configured: false })
      }
    } catch (err) {
      setTestState('error')
      setTestError(err.response?.data?.error || err.message || 'Test failed')
      notifyParent({ configured: false })
    }
  }

  const providerInfo = PROVIDERS.find(p => p.id === provider)

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-2xl font-semibold text-gray-900 tracking-tight">Configure AI</h2>
        <p className="mt-2 text-sm text-gray-500 leading-relaxed">
          DeepSQL uses an LLM to understand your database, generate SQL, and surface insights.
          Your key is stored encrypted on your server and never sent to us.
        </p>
      </div>

      {/* Provider tabs */}
      <div className="flex gap-2">
        {PROVIDERS.map(p => (
          <button
            key={p.id}
            type="button"
            onClick={() => { setProvider(p.id); setTestState('idle'); notifyParent({ provider: p.id, configured: false }) }}
            className={`px-4 py-2 rounded-lg border text-sm font-medium transition-all ${
              provider === p.id
                ? 'border-gray-900 !bg-gray-900 !text-white'
                : 'border-gray-200 !bg-white text-gray-700 hover:border-gray-400'
            }`}
          >
            {p.label}
          </button>
        ))}
      </div>

      {/* API Key */}
      <div className="space-y-1.5">
        <div className="flex items-center justify-between">
          <label className="text-xs font-medium text-gray-600 uppercase tracking-wide">API Key</label>
          <a
            href={providerInfo.link}
            target="_blank"
            rel="noopener noreferrer"
            className="flex items-center gap-1 text-xs text-gray-400 hover:text-gray-600 transition-colors"
          >
            Get your key
            <ExternalLink size={10} />
          </a>
        </div>
        <div className="relative">
          <input
            type={showKey ? 'text' : 'password'}
            value={apiKey}
            onChange={e => { setApiKey(e.target.value); setTestState('idle'); notifyParent({ apiKey: e.target.value, configured: false }) }}
            placeholder={providerInfo.placeholder}
            className="input-field w-full pr-10 font-mono text-sm"
          />
          <button
            type="button"
            onClick={() => setShowKey(v => !v)}
            className="absolute right-3 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600"
          >
            {showKey ? <EyeOff size={14} /> : <Eye size={14} />}
          </button>
        </div>
      </div>

      {/* Azure endpoint (only for azure-openai) */}
      {provider === 'azure-openai' && (
        <div className="space-y-1.5">
          <label className="text-xs font-medium text-gray-600 uppercase tracking-wide">Azure Endpoint</label>
          <input
            type="url"
            value={endpoint}
            onChange={e => { setEndpoint(e.target.value); notifyParent({ endpoint: e.target.value }) }}
            placeholder="https://your-resource.cognitiveservices.azure.com/"
            className="input-field w-full"
          />
        </div>
      )}

      {/* Model selection (OpenAI only) */}
      {provider === 'openai' && (
        <div className="space-y-1.5">
          <label className="text-xs font-medium text-gray-600 uppercase tracking-wide">Model</label>
          <select
            value={chatModel}
            onChange={e => { setChatModel(e.target.value); notifyParent({ chatModel: e.target.value }) }}
            className="input-field w-full"
          >
            {OPENAI_MODELS.map(m => (
              <option key={m.value} value={m.value}>{m.label}</option>
            ))}
          </select>
          <p className="text-xs text-gray-400">
            GPT-4o gives the best results. GPT-4o mini is significantly cheaper for high-volume usage.
          </p>
        </div>
      )}

      {/* Test + status */}
      <div className="flex items-center gap-3">
        <button
          type="button"
          onClick={handleTest}
          disabled={!apiKey.trim() || testState === 'testing'}
          className={`flex items-center gap-2 px-4 py-2 rounded-lg text-sm font-medium transition-all ${
            !apiKey.trim()
              ? 'border border-gray-200 text-gray-300 cursor-not-allowed'
              : '!bg-gray-900 !text-white hover:!bg-gray-700'
          }`}
        >
          {testState === 'testing' && <Loader2 size={14} className="animate-spin" />}
          {testState === 'ok'      && <CheckCircle size={14} />}
          {testState === 'error'   && <XCircle size={14} />}
          {testState === 'testing' ? 'Verifying…' : 'Test & verify key'}
        </button>

        {testState === 'ok' && (
          <span className="flex items-center gap-1.5 text-sm text-green-600 font-medium">
            <CheckCircle size={14} />
            Connected
          </span>
        )}
        {testState === 'error' && (
          <span className="flex items-center gap-1.5 text-sm text-red-500">
            <XCircle size={14} />
            {testError}
          </span>
        )}
      </div>

      {/* Trust note */}
      <div className="flex gap-2.5 rounded-lg bg-gray-50 border border-gray-100 px-4 py-3">
        <div className="text-gray-400 flex-shrink-0 mt-0.5">🔒</div>
        <p className="text-xs text-gray-500 leading-relaxed">
          Your API key is encrypted with AES-256-GCM and stored only in your local database.
          It is never transmitted to DeepSQL servers — all LLM calls are made directly from
          your server to the AI provider.
        </p>
      </div>
    </div>
  )
}
