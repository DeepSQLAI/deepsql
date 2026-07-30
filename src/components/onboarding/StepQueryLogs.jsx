import { useState } from 'react'
import { Activity, Cloud, Database, Zap } from 'lucide-react'

const SOURCES = [
  {
    id:    'pg_stat',
    icon:  Database,
    title: 'pg_stat_statements',
    sub:   'Auto-detected for PostgreSQL. Zero config.',
    badge: 'Recommended',
  },
  {
    id:    's3',
    icon:  Cloud,
    title: 'AWS S3',
    sub:   'Fetch slow query logs stored in an S3 bucket.',
    badge: null,
  },
  {
    id:    'cloudwatch',
    icon:  Activity,
    title: 'AWS CloudWatch Logs',
    sub:   'Pull logs directly from a CloudWatch log group.',
    badge: null,
  },
  {
    id:    'other',
    icon:  Zap,
    title: 'Other (Datadog, ELK…)',
    sub:   'Configure after setup in the Slow Queries tab.',
    badge: 'Coming soon',
    disabled: true,
  },
]

export default function StepQueryLogs({ data, onUpdate }) {
  const [selected, setSelected] = useState(data.logSource || 'pg_stat')
  const [skipped,  setSkipped]  = useState(data.skipped   || false)

  function select(id) {
    setSelected(id)
    setSkipped(false)
    onUpdate({ logSource: id, skipped: false })
  }

  function skip() {
    setSkipped(true)
    onUpdate({ logSource: null, skipped: true })
  }

  return (
    <div className="space-y-6">
      <div>
        <h2 className="text-2xl font-semibold text-gray-900 tracking-tight">Connect query logs</h2>
        <p className="mt-2 text-sm text-gray-500 leading-relaxed">
          Query logs let the Brain detect slow queries, index gaps, and workload patterns.
          <strong className="text-gray-700"> Highly recommended</strong> — this is what makes
          DeepSQL's analysis magical.
        </p>
      </div>

      {!skipped ? (
        <div className="space-y-2">
          {SOURCES.map(s => {
            const Icon = s.icon
            const active = selected === s.id
            return (
              <button
                key={s.id}
                type="button"
                disabled={s.disabled}
                onClick={() => !s.disabled && select(s.id)}
                className={`w-full flex items-center gap-4 p-4 rounded-lg border text-left transition-all
                  ${s.disabled ? 'opacity-40 cursor-not-allowed border-gray-100 bg-gray-50'
                    : active
                      ? 'border-gray-900 bg-gray-50 ring-1 ring-gray-900'
                      : 'border-gray-200 bg-white hover:border-gray-400'}`}
              >
                <div className={`w-9 h-9 rounded-lg flex items-center justify-center flex-shrink-0 ${
                  active ? 'bg-gray-900 text-white' : 'bg-gray-100 text-gray-500'
                }`}>
                  <Icon size={16} />
                </div>
                <div className="flex-1 min-w-0">
                  <div className="flex items-center gap-2">
                    <span className="text-sm font-medium text-gray-900">{s.title}</span>
                    {s.badge && (
                      <span className={`text-[10px] font-semibold px-1.5 py-0.5 rounded uppercase tracking-wide ${
                        s.badge === 'Recommended'
                          ? 'bg-gray-900 text-white'
                          : 'bg-gray-100 text-gray-500'
                      }`}>
                        {s.badge}
                      </span>
                    )}
                  </div>
                  <p className="text-xs text-gray-500 mt-0.5">{s.sub}</p>
                </div>
                {active && !s.disabled && (
                  <div className="w-4 h-4 rounded-full bg-gray-900 flex items-center justify-center flex-shrink-0">
                    <div className="w-1.5 h-1.5 rounded-full bg-white" />
                  </div>
                )}
              </button>
            )
          })}
        </div>
      ) : (
        <div className="rounded-lg border border-dashed border-gray-200 p-6 text-center">
          <p className="text-sm text-gray-400">Skipped — you can configure this later in the</p>
          <p className="text-sm text-gray-500 font-medium">Monitor → Slow Queries → Source Config</p>
          <button
            type="button"
            onClick={() => { setSkipped(false); onUpdate({ logSource: 'pg_stat', skipped: false }) }}
            className="mt-3 text-xs text-gray-400 hover:text-gray-600 underline"
          >
            Configure now instead
          </button>
        </div>
      )}

      {selected === 's3' && !skipped && (
        <div className="rounded-lg bg-amber-50 border border-amber-100 px-4 py-3">
          <p className="text-xs text-amber-700">
            S3 credentials will be configured in the next screen after setup completes.
            You can also set them in <strong>Monitor → Slow Queries → Source Config</strong>.
          </p>
        </div>
      )}

      {!skipped && (
        <button
          type="button"
          onClick={skip}
          className="text-sm text-gray-400 hover:text-gray-600 transition-colors underline-offset-2 hover:underline"
        >
          Skip for now — I'll configure this later
        </button>
      )}
    </div>
  )
}
