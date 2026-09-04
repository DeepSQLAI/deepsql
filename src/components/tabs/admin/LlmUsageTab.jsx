'use client'

import { useMemo, useState } from 'react'
import {
  AlertCircle,
  Coins,
  RefreshCw,
  TriangleAlert,
} from 'lucide-react'
import {
  Area,
  AreaChart,
  CartesianGrid,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from 'recharts'
import { useLlmUsageSummary } from '@/lib/hooks/queries/useLlmUsage'
import LlmPricingPanel from './LlmPricingPanel'
import { useAuth } from '@/hooks/useAuth'

const WINDOWS = [
  { days: 7, label: '7 days' },
  { days: 30, label: '30 days' },
  { days: 90, label: '90 days' },
]

function formatUsd(value) {
  const amount = Number(value ?? 0)
  if (!Number.isFinite(amount)) return '$0.00'
  // Sub-cent totals are real on small installs; showing "$0.00" for them reads as
  // "nothing was spent" when the truth is "not much was spent".
  if (amount > 0 && amount < 0.01) return '<$0.01'
  return `$${amount.toLocaleString(undefined, {
    minimumFractionDigits: 2,
    maximumFractionDigits: 2,
  })}`
}

function formatTokens(value) {
  const tokens = Number(value ?? 0)
  if (!Number.isFinite(tokens)) return '0'
  if (tokens >= 1_000_000) return `${(tokens / 1_000_000).toFixed(1)}M`
  if (tokens >= 1_000) return `${(tokens / 1_000).toFixed(1)}K`
  return String(tokens)
}

function StatCard({ label, value, hint }) {
  return (
    <div className="border border-gray-200 rounded-lg p-4">
      <span className="block text-xs font-medium uppercase tracking-wide text-gray-500">
        {label}
      </span>
      <span className="block mt-2 text-2xl font-semibold text-gray-900 tabular-nums">
        {value}
      </span>
      {hint ? <span className="block mt-1 text-xs text-gray-500">{hint}</span> : null}
    </div>
  )
}

function Breakdown({ title, rows, emptyLabel }) {
  const max = Math.max(...rows.map((r) => Number(r.costUsd ?? 0)), 0)

  return (
    <div className="border border-gray-200 rounded-lg p-4">
      <h3 className="text-sm font-semibold text-gray-800 mb-3">{title}</h3>
      {rows.length === 0 ? (
        <p className="text-sm text-gray-500">{emptyLabel}</p>
      ) : (
        <ul className="space-y-2">
          {rows.slice(0, 8).map((row) => (
            <li key={row.key}>
              <div className="flex items-baseline justify-between gap-3 text-sm">
                <span className="text-gray-700 truncate" title={row.key}>
                  {row.key}
                </span>
                <span className="text-gray-900 tabular-nums whitespace-nowrap">
                  {formatUsd(row.costUsd)}
                  <span className="text-gray-400 ml-2">
                    {formatTokens(row.totalTokens)} tok
                  </span>
                </span>
              </div>
              <div className="mt-1 h-1 bg-gray-100 rounded">
                <div
                  className="h-1 bg-gray-800 rounded"
                  style={{
                    width: max > 0 ? `${(Number(row.costUsd ?? 0) / max) * 100}%` : '0%',
                  }}
                />
              </div>
            </li>
          ))}
        </ul>
      )}
    </div>
  )
}

export default function LlmUsageTab() {
  const { isAdmin } = useAuth()
  const [days, setDays] = useState(30)
  const { data, isLoading, isError, error, refetch, isFetching } = useLlmUsageSummary(days)

  const daily = useMemo(
    () =>
      (data?.daily ?? []).map((point) => ({
        day: point.day,
        cost: Number(point.costUsd ?? 0),
      })),
    [data],
  )

  if (!isAdmin) {
    return (
      <div className="flex flex-col items-center justify-center h-full min-h-[400px] text-gray-500">
        <Coins size={48} className="mb-4 text-gray-400" />
        <h3 className="text-lg font-medium text-gray-700 mb-2">Admin Access Required</h3>
        <p className="text-sm text-gray-500">
          You need administrator privileges to view LLM spend.
        </p>
      </div>
    )
  }

  const totals = data?.totals
  const unpriced = data?.unpricedModels ?? []

  return (
    <div className="h-full flex flex-col bg-white">
      <div className="flex items-center justify-between px-6 py-4 border-b border-gray-200">
        <div className="flex items-center gap-3">
          <Coins size={20} className="text-gray-600" />
          <div>
            <h2 className="text-lg font-semibold text-gray-800">AI Usage &amp; Cost</h2>
            <p className="text-sm text-gray-500">
              What DeepSQL spent on model calls, by feature, user, and model.
            </p>
          </div>
        </div>
        <div className="flex items-center gap-2">
          <div className="flex items-center rounded-md border border-gray-300 overflow-hidden">
            {WINDOWS.map((w) => (
              <button
                key={w.days}
                type="button"
                onClick={() => setDays(w.days)}
                className={`px-3 py-1.5 text-sm transition-colors ${
                  days === w.days
                    ? 'bg-gray-900 text-white'
                    : 'text-gray-600 hover:bg-gray-100'
                }`}
              >
                {w.label}
              </button>
            ))}
          </div>
          <button
            type="button"
            onClick={() => refetch()}
            disabled={isFetching}
            className="flex items-center gap-2 px-3 py-1.5 text-sm text-gray-600 hover:text-gray-900 hover:bg-gray-100 rounded-md transition-colors disabled:opacity-50"
          >
            <RefreshCw size={14} className={isFetching ? 'animate-spin' : ''} />
            Refresh
          </button>
        </div>
      </div>

      {isError ? (
        <div className="mx-6 mt-4 p-3 bg-red-50 border border-red-200 rounded-lg flex items-center gap-2 text-red-700">
          <AlertCircle size={16} />
          <span className="text-sm">
            {error?.message || 'Could not load usage data.'}
          </span>
        </div>
      ) : null}

      <div className="flex-1 overflow-auto p-6 space-y-6">
        {isLoading ? (
          <p className="text-sm text-gray-500">Loading usage…</p>
        ) : (
          <>
            {/*
              An unpriced model is an operator gap, not a bug: the ledger records tokens
              for every call but can only cost the models that have a configured rate, so
              a partial total is surfaced as partial rather than presented as complete.
            */}
            {unpriced.length > 0 ? (
              <div className="p-3 bg-amber-50 border border-amber-200 rounded-lg flex items-start gap-2 text-amber-800">
                <TriangleAlert size={16} className="mt-0.5 shrink-0" />
                <div className="text-sm">
                  <p className="font-medium">
                    {unpriced.length === 1
                      ? '1 model has no configured price, so the totals below understate real spend.'
                      : `${unpriced.length} models have no configured price, so the totals below understate real spend.`}
                  </p>
                  <p className="mt-1 text-amber-700">
                    Set a rate in <span className="font-medium">Model pricing</span> below
                    for: {unpriced.join(', ')}
                  </p>
                </div>
              </div>
            ) : null}

            <div className="grid grid-cols-2 lg:grid-cols-4 gap-4">
              <StatCard
                label="Estimated cost"
                value={formatUsd(totals?.costUsd)}
                hint={
                  totals?.unpricedCalls > 0
                    ? `${totals.unpricedCalls.toLocaleString()} unpriced calls excluded`
                    : `Last ${days} days`
                }
              />
              <StatCard
                label="Calls"
                value={(totals?.calls ?? 0).toLocaleString()}
                hint={
                  totals?.failedCalls > 0
                    ? `${totals.failedCalls.toLocaleString()} failed`
                    : 'All succeeded'
                }
              />
              <StatCard label="Total tokens" value={formatTokens(totals?.totalTokens)} />
              <StatCard
                label="Prompt / completion"
                value={`${formatTokens(totals?.promptTokens)} / ${formatTokens(
                  totals?.completionTokens,
                )}`}
              />
            </div>

            <div className="border border-gray-200 rounded-lg p-4">
              <h3 className="text-sm font-semibold text-gray-800 mb-3">Daily spend</h3>
              {daily.length === 0 ? (
                <p className="text-sm text-gray-500">
                  No model calls recorded in this window.
                </p>
              ) : (
                // Height is given in pixels rather than as a percentage of the
                // parent. ResponsiveContainer measures its parent on mount, which
                // inside this modal reports -1 for one frame; an explicit height means
                // it recovers on the next measurement instead of collapsing.
                <div className="h-56">
                  <ResponsiveContainer width="100%" height={224}>
                    <AreaChart data={daily} margin={{ top: 4, right: 8, bottom: 0, left: 0 }}>
                      <CartesianGrid strokeDasharray="3 3" stroke="#f3f4f6" vertical={false} />
                      <XAxis
                        dataKey="day"
                        tick={{ fontSize: 11, fill: '#6b7280' }}
                        tickLine={false}
                        axisLine={{ stroke: '#e5e7eb' }}
                      />
                      <YAxis
                        tick={{ fontSize: 11, fill: '#6b7280' }}
                        tickLine={false}
                        axisLine={false}
                        width={56}
                        tickFormatter={(v) => `$${Number(v).toFixed(2)}`}
                      />
                      <Tooltip
                        formatter={(value) => [formatUsd(value), 'Cost']}
                        contentStyle={{ fontSize: 12, borderRadius: 6 }}
                      />
                      <Area
                        type="monotone"
                        dataKey="cost"
                        stroke="#111827"
                        fill="#111827"
                        fillOpacity={0.08}
                        strokeWidth={1.5}
                      />
                    </AreaChart>
                  </ResponsiveContainer>
                </div>
              )}
            </div>

            <div className="grid grid-cols-1 lg:grid-cols-3 gap-4">
              <Breakdown
                title="By feature"
                rows={data?.byFeature ?? []}
                emptyLabel="No usage recorded yet."
              />
              <Breakdown
                title="By user"
                rows={data?.byUser ?? []}
                emptyLabel="No usage recorded yet."
              />
              <Breakdown
                title="By model"
                rows={data?.byModel ?? []}
                emptyLabel="No usage recorded yet."
              />
            </div>

            <LlmPricingPanel />
          </>
        )}
      </div>
    </div>
  )
}
