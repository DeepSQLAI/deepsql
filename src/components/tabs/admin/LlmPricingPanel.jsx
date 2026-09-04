'use client'

import { useEffect, useState } from 'react'
import { AlertCircle, Check, Plus, X } from 'lucide-react'
import { useLlmPricing, useUpdateLlmPricing } from '@/lib/hooks/queries/useLlmUsage'

const FIELDS = [
  { key: 'inputPer1m', label: 'Input', hint: 'Required' },
  { key: 'outputPer1m', label: 'Output', hint: 'Blank for embeddings' },
  { key: 'cachedInputPer1m', label: 'Cached input', hint: 'Defaults to input' },
]

/** '' for a null rate so an empty box round-trips as "not set" rather than as 0. */
function toForm(row) {
  return {
    inputPer1m: row.inputPer1m ?? '',
    outputPer1m: row.outputPer1m ?? '',
    cachedInputPer1m: row.cachedInputPer1m ?? '',
  }
}

function toPayload(form) {
  const parse = (v) => {
    const trimmed = String(v ?? '').trim()
    if (trimmed === '') return null
    const n = Number(trimmed)
    return Number.isFinite(n) ? n : null
  }
  return {
    inputPer1m: parse(form.inputPer1m),
    outputPer1m: parse(form.outputPer1m),
    cachedInputPer1m: parse(form.cachedInputPer1m),
  }
}

function isInvalid(form) {
  return FIELDS.some(({ key }) => {
    const raw = String(form[key] ?? '').trim()
    if (raw === '') return false
    const n = Number(raw)
    return !Number.isFinite(n) || n < 0
  })
}

function RateInput({ value, onChange, label, disabled }) {
  return (
    <label className="block">
      <span className="sr-only">{label}</span>
      <div className="relative">
        <span className="absolute left-2 top-1/2 -translate-y-1/2 text-xs text-gray-400">
          $
        </span>
        <input
          type="number"
          min="0"
          step="0.01"
          inputMode="decimal"
          value={value}
          disabled={disabled}
          onChange={(e) => onChange(e.target.value)}
          placeholder="—"
          className="w-full pl-5 pr-2 py-1.5 text-sm text-right tabular-nums border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-gray-900 focus:border-transparent disabled:bg-gray-50"
        />
      </div>
    </label>
  )
}

function PricingRow({ row, onSave, saving, justSaved }) {
  const [form, setForm] = useState(() => toForm(row))

  // Compared as strings because the inputs hold strings; a numeric compare would call
  // "2.50" a change from 2.5 and leave Save enabled on an untouched row.
  const dirty = FIELDS.some(
    ({ key }) => String(form[key] ?? '') !== String(toForm(row)[key] ?? ''),
  )
  const invalid = isInvalid(form)

  const save = () => onSave(row.model, toPayload(form))

  return (
    <tr className="border-b border-gray-100 last:border-b-0">
      <td className="py-2 pr-3 align-middle">
        <div className="flex items-center gap-2">
          <span className="font-mono text-xs text-gray-800 break-all">{row.model}</span>
          {!row.priced ? (
            <span className="shrink-0 px-1.5 py-0.5 text-[10px] uppercase tracking-wide rounded bg-amber-100 text-amber-800">
              Unpriced
            </span>
          ) : null}
          {!row.seenInUsage ? (
            <span
              className="shrink-0 px-1.5 py-0.5 text-[10px] uppercase tracking-wide rounded bg-gray-100 text-gray-500"
              title="Configured, but no recorded calls"
            >
              Unused
            </span>
          ) : null}
        </div>
      </td>
      {FIELDS.map(({ key, label }) => (
        <td key={key} className="py-2 px-1.5 align-middle w-28">
          <RateInput
            label={`${label} rate for ${row.model}`}
            value={form[key]}
            disabled={saving}
            onChange={(v) => setForm((f) => ({ ...f, [key]: v }))}
          />
        </td>
      ))}
      <td className="py-2 pl-2 align-middle w-24 text-right">
        {dirty ? (
          <div className="flex items-center justify-end gap-1">
            <button
              type="button"
              onClick={save}
              disabled={saving || invalid}
              title={invalid ? 'Rates must be zero or greater' : 'Save'}
              className="px-2 py-1 text-xs bg-gray-900 text-white rounded-md hover:bg-gray-700 disabled:opacity-40 transition-colors"
            >
              Save
            </button>
            <button
              type="button"
              onClick={() => setForm(toForm(row))}
              disabled={saving}
              title="Discard changes"
              className="p-1 text-gray-400 hover:text-gray-700 transition-colors"
            >
              <X size={14} />
            </button>
          </div>
        ) : justSaved ? (
          <span className="inline-flex items-center gap-1 text-xs text-green-700">
            <Check size={13} /> Saved
          </span>
        ) : null}
      </td>
    </tr>
  )
}

export default function LlmPricingPanel() {
  const { data: rows = [], isLoading, isError, error } = useLlmPricing()
  const updatePricing = useUpdateLlmPricing()
  const [newModel, setNewModel] = useState('')
  const [adding, setAdding] = useState(false)
  // Held here, not in the row: a save refetches the list and the row is remounted with
  // its new values, which would discard a flag owned by the row itself.
  const [savedModel, setSavedModel] = useState(null)

  useEffect(() => {
    if (!savedModel) return undefined
    const timer = setTimeout(() => setSavedModel(null), 4000)
    return () => clearTimeout(timer)
  }, [savedModel])

  // The rejection is caught deliberately. mutateAsync rejects on failure, and an
  // uncaught rejection here propagated out of the row's click handler instead of
  // letting the isError banner render — a save against a broken backend showed the
  // user nothing at all. The mutation's own error state is what surfaces the message.
  const save = async (model, rates) => {
    try {
      const result = await updatePricing.mutateAsync({ model, rates })
      setSavedModel(result?.model ?? model)
      return true
    } catch {
      setSavedModel(null)
      return false
    }
  }

  const addModel = async () => {
    const model = newModel.trim()
    if (!model) return
    // Written with no rates: the row appears immediately as Unpriced and is filled in
    // through the same inputs as every other row, so there is only one editing path.
    const ok = await save(model, {
      inputPer1m: null,
      outputPer1m: null,
      cachedInputPer1m: null,
    })
    // The form stays open on failure so the typed name is not lost and the error is
    // visible next to what caused it.
    if (ok) {
      setNewModel('')
      setAdding(false)
    }
  }

  return (
    <div className="border border-gray-200 rounded-lg">
      <div className="flex items-start justify-between gap-4 p-4 border-b border-gray-200">
        <div>
          <h3 className="text-sm font-semibold text-gray-800">Model pricing</h3>
          <p className="mt-1 text-xs text-gray-500">
            USD per 1M tokens. Rates apply to calls made from now on — editing a rate does
            not re-cost calls already recorded.
          </p>
        </div>
        {adding ? (
          <div className="flex items-center gap-1 shrink-0">
            <input
              autoFocus
              value={newModel}
              onChange={(e) => setNewModel(e.target.value)}
              onKeyDown={(e) => {
                if (e.key === 'Enter') addModel()
                if (e.key === 'Escape') {
                  setNewModel('')
                  setAdding(false)
                }
              }}
              placeholder="model name"
              className="w-48 px-2 py-1.5 text-sm font-mono border border-gray-300 rounded-md focus:outline-none focus:ring-2 focus:ring-gray-900"
            />
            <button
              type="button"
              onClick={addModel}
              disabled={!newModel.trim() || updatePricing.isPending}
              className="px-2 py-1.5 text-xs bg-gray-900 text-white rounded-md hover:bg-gray-700 disabled:opacity-40"
            >
              Add
            </button>
            <button
              type="button"
              onClick={() => {
                setNewModel('')
                setAdding(false)
              }}
              className="p-1.5 text-gray-400 hover:text-gray-700"
            >
              <X size={14} />
            </button>
          </div>
        ) : (
          <button
            type="button"
            onClick={() => setAdding(true)}
            className="shrink-0 flex items-center gap-1.5 px-3 py-1.5 text-sm text-gray-600 hover:text-gray-900 hover:bg-gray-100 rounded-md transition-colors"
          >
            <Plus size={14} />
            Add model
          </button>
        )}
      </div>

      {updatePricing.isError ? (
        <div className="mx-4 mt-4 p-2.5 bg-red-50 border border-red-200 rounded-md flex items-center gap-2 text-red-700">
          <AlertCircle size={14} />
          <span className="text-xs">
            {updatePricing.error?.message || 'Could not save the rate.'}
          </span>
        </div>
      ) : null}

      <div className="p-4 overflow-x-auto">
        {isLoading ? (
          <p className="text-sm text-gray-500">Loading rates…</p>
        ) : isError ? (
          <p className="text-sm text-red-700">
            {error?.message || 'Could not load pricing.'}
          </p>
        ) : rows.length === 0 ? (
          <p className="text-sm text-gray-500">
            No models recorded yet. Rates can be added ahead of the first call.
          </p>
        ) : (
          <table className="w-full min-w-[560px]">
            <thead>
              <tr className="text-left">
                <th className="pb-2 pr-3 text-[10px] font-medium uppercase tracking-wide text-gray-500">
                  Model
                </th>
                {FIELDS.map(({ key, label, hint }) => (
                  <th
                    key={key}
                    className="pb-2 px-1.5 text-[10px] font-medium uppercase tracking-wide text-gray-500 text-right"
                  >
                    {label}
                    <span className="block font-normal normal-case tracking-normal text-gray-400">
                      {hint}
                    </span>
                  </th>
                ))}
                <th className="pb-2 pl-2" />
              </tr>
            </thead>
            <tbody>
              {rows.map((row) => (
                // Keyed by model *and* its saved values so the row's local form state is
                // rebuilt after a save; keying on model alone would keep showing the
                // pre-save draft and leave the row looking permanently dirty.
                <PricingRow
                  key={`${row.model}:${row.inputPer1m}:${row.outputPer1m}:${row.cachedInputPer1m}`}
                  row={row}
                  onSave={save}
                  saving={updatePricing.isPending}
                  justSaved={savedModel === row.model}
                />
              ))}
            </tbody>
          </table>
        )}
      </div>
    </div>
  )
}
