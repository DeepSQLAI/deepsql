import { CheckCircle, ArrowRight, Database, Brain, Zap } from 'lucide-react'

export default function StepComplete({ orgName, onFinish }) {
  const greeting = orgName ? `${orgName} is` : 'You\'re'

  return (
    <div className="space-y-8">
      {/* Header */}
      <div className="text-center py-4">
        <div className="w-16 h-16 rounded-2xl bg-gray-900 flex items-center justify-center mx-auto mb-4">
          <CheckCircle size={32} className="text-white" />
        </div>
        <h2 className="text-2xl font-semibold text-gray-900 tracking-tight">
          {greeting} all set!
        </h2>
        <p className="mt-2 text-sm text-gray-500">
          DeepSQL has analyzed your database and is ready to help.
        </p>
      </div>

      {/* Feature cards */}
      <div className="grid grid-cols-3 gap-3">
        {[
          {
            icon: Database,
            title: 'Schema intelligence',
            desc: 'Ask anything about your tables, columns, and relationships in plain English.',
          },
          {
            icon: Brain,
            title: 'Brain insights',
            desc: 'Workload analysis, slow query detection, and index recommendations.',
          },
          {
            icon: Zap,
            title: 'Query optimizer',
            desc: 'Paste any slow query and get AI-powered optimization suggestions instantly.',
          },
        ].map(card => {
          const Icon = card.icon
          return (
            <div key={card.title} className="rounded-lg border border-gray-100 bg-gray-50 p-4">
              <Icon size={18} className="text-gray-900 mb-2" />
              <p className="text-sm font-medium text-gray-900">{card.title}</p>
              <p className="text-xs text-gray-500 mt-1 leading-relaxed">{card.desc}</p>
            </div>
          )
        })}
      </div>

      {/* CTA */}
      <div className="text-center">
        <button
          type="button"
          onClick={onFinish}
          className="inline-flex items-center gap-2 px-6 py-3 !bg-gray-900 !text-white
                     text-sm font-medium rounded-lg hover:!bg-gray-700 transition-colors"
        >
          Open Dashboard
          <ArrowRight size={15} />
        </button>
        <p className="mt-3 text-xs text-gray-400">
          Pro tip: start with the <strong className="text-gray-600">Chat</strong> tab and ask
          "What are my top 5 slowest queries?"
        </p>
      </div>
    </div>
  )
}
