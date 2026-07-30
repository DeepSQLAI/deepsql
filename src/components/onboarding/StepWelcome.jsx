import { useState } from 'react'
import { Building2, Users } from 'lucide-react'

const TEAM_SIZES = [
  { value: '1-5',    label: '1–5',    sub: 'Startup' },
  { value: '6-20',   label: '6–20',   sub: 'Small team' },
  { value: '21-100', label: '21–100', sub: 'Scale-up' },
  { value: '100+',   label: '100+',   sub: 'Enterprise' },
]

export default function StepWelcome({ data, onUpdate }) {
  const [orgName,  setOrgName]  = useState(data.orgName  || '')
  const [teamSize, setTeamSize] = useState(data.teamSize || '')

  function handleChange(field, value) {
    if (field === 'orgName')  { setOrgName(value);  onUpdate({ orgName: value, teamSize }) }
    if (field === 'teamSize') { setTeamSize(value); onUpdate({ orgName, teamSize: value }) }
  }

  return (
    <div className="space-y-8">
      <div>
        <h2 className="text-2xl font-semibold text-gray-900 tracking-tight">
          Welcome to DeepSQL
        </h2>
        <p className="mt-2 text-gray-500 text-sm leading-relaxed">
          Let's get your self-hosted instance configured in under&nbsp;10&nbsp;minutes.
          You'll connect your database, set up AI, and watch DeepSQL learn your schema.
        </p>
      </div>

      {/* Org name */}
      <div className="space-y-2">
        <label className="flex items-center gap-2 text-sm font-medium text-gray-700">
          <Building2 size={15} className="text-gray-400" />
          Organization name
          <span className="text-gray-400 font-normal">(optional)</span>
        </label>
        <input
          type="text"
          value={orgName}
          onChange={e => handleChange('orgName', e.target.value)}
          placeholder="Acme Inc."
          className="w-full px-3 py-2 text-sm border border-gray-200 rounded-lg
                     bg-white text-gray-900 placeholder-gray-400
                     focus:outline-none focus:ring-1 focus:ring-gray-900 focus:border-gray-900
                     transition-colors"
        />
      </div>

      {/* Team size */}
      <div className="space-y-2">
        <label className="flex items-center gap-2 text-sm font-medium text-gray-700">
          <Users size={15} className="text-gray-400" />
          Team size
          <span className="text-gray-400 font-normal">(optional)</span>
        </label>
        <div className="grid grid-cols-4 gap-2">
          {TEAM_SIZES.map(s => (
            <button
              key={s.value}
              type="button"
              onClick={() => handleChange('teamSize', s.value)}
              className={`py-3 px-2 rounded-lg border text-center transition-all ${
                teamSize === s.value
                  ? 'border-gray-900 !bg-gray-900 !text-white'
                  : 'border-gray-200 !bg-white text-gray-700 hover:border-gray-400'
              }`}
            >
              <div className="text-sm font-semibold">{s.label}</div>
              <div className={`text-xs mt-0.5 ${teamSize === s.value ? 'text-gray-300' : 'text-gray-400'}`}>
                {s.sub}
              </div>
            </button>
          ))}
        </div>
      </div>

      {/* What's ahead */}
      <div className="rounded-lg bg-gray-50 border border-gray-100 px-4 py-3">
        <p className="text-xs font-medium text-gray-500 uppercase tracking-wide mb-2">What's next</p>
        <ol className="space-y-1">
          {[
            'Connect your PostgreSQL or MySQL database',
            'Optionally connect query logs for workload insights',
            'Enter your OpenAI API key',
            'DeepSQL Brain analyzes your schema (~2 min)',
          ].map((step, i) => (
            <li key={i} className="flex items-start gap-2 text-sm text-gray-600">
              <span className="w-4 h-4 rounded-full bg-gray-200 text-gray-500 text-[10px]
                               flex items-center justify-center flex-shrink-0 mt-0.5 font-medium">
                {i + 1}
              </span>
              {step}
            </li>
          ))}
        </ol>
      </div>
    </div>
  )
}
