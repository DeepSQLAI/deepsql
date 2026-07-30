/**
 * AgentArtifacts — parses agent response text and renders embedded
 * charts (pie, bar, line) and data tables with CSV/Excel downloads.
 *
 * The AI is instructed to embed artifact blocks using the format:
 *   :::chart:pie  { …json… }  :::
 *   :::chart:bar  { …json… }  :::
 *   :::chart:line { …json… }  :::
 *   :::table      { …json… }  :::
 */

import { useState } from 'react'
import {
  PieChart, Pie, Cell, Tooltip as ReTooltip, Legend,
  BarChart, Bar, XAxis, YAxis, CartesianGrid, ResponsiveContainer,
  LineChart, Line,
} from 'recharts'
import * as XLSX from 'xlsx'
import { Download, FileSpreadsheet, FileText, ChevronDown, ChevronUp } from 'lucide-react'
import styles from './AgentArtifacts.module.css'

// ── Palette ───────────────────────────────────────────────────────────────────

const PALETTE = [
  '#4f46e5', '#10b981', '#f59e0b', '#ef4444',
  '#3b82f6', '#8b5cf6', '#06b6d4', '#ec4899',
  '#f97316', '#84cc16', '#14b8a6', '#a855f7',
]

// ── Content sanitiser ─────────────────────────────────────────────────────────
// Strips internal blocks that must never appear in the Output tab:
//   :::plan ... :::   — agent execution plan (lives in Inspect tab)
//   ```sql ... ```    — raw SQL queries (lives in Inspect tab)
//   ```               — any other fenced code block
//   [ASK] ...         — clarification prefix (rendered by ClarificationCard)

export function sanitiseOutputContent(text) {
  if (!text) return text
  return text
    // Remove :::plan ... ::: blocks (multiline)
    .replace(/:::plan[\s\S]*?:::/gi, '')
    // Remove ```sql ... ``` blocks (multiline)
    .replace(/```sql[\s\S]*?```/gi, '')
    // Remove any remaining fenced code blocks
    .replace(/```[\s\S]*?```/g, '')
    // Remove bare [ASK] prefix line
    .replace(/^\[ASK\]\s*/im, '')
    // Collapse excessive blank lines left after removals
    .replace(/\n{3,}/g, '\n\n')
    .trim()
}

// ── Parser ────────────────────────────────────────────────────────────────────
// Splits raw text into segments: { type: 'text'|'chart'|'table', ... }

const BLOCK_RE = /:::chart:(pie|bar|line)\s*\n([\s\S]*?)\n\s*:::|:::table\s*\n([\s\S]*?)\n\s*:::/g

export function parseArtifacts(text) {
  if (!text) return [{ type: 'text', content: '' }]

  const segments = []
  let lastIndex = 0
  let match

  // Reset regex state
  BLOCK_RE.lastIndex = 0

  while ((match = BLOCK_RE.exec(text)) !== null) {
    // Flush preceding text
    if (match.index > lastIndex) {
      const chunk = text.slice(lastIndex, match.index).trim()
      if (chunk) segments.push({ type: 'text', content: chunk })
    }

    if (match[1]) {
      // chart block
      try {
        segments.push({ type: 'chart', chartType: match[1], data: JSON.parse(match[2]) })
      } catch {
        segments.push({ type: 'text', content: match[0] })
      }
    } else {
      // table block
      try {
        segments.push({ type: 'table', data: JSON.parse(match[3]) })
      } catch {
        segments.push({ type: 'text', content: match[0] })
      }
    }

    lastIndex = match.index + match[0].length
  }

  // Trailing text
  const tail = text.slice(lastIndex).trim()
  if (tail) segments.push({ type: 'text', content: tail })

  return segments.length ? segments : [{ type: 'text', content: text }]
}

// ── Download helpers ──────────────────────────────────────────────────────────

function downloadCSV(filename, columns, rows) {
  const header = columns.join(',')
  const body   = rows.map((r) => r.map((c) => `"${String(c).replace(/"/g, '""')}"`).join(',')).join('\n')
  const blob   = new Blob([`${header}\n${body}`], { type: 'text/csv;charset=utf-8;' })
  triggerDownload(blob, `${filename}.csv`)
}

function downloadExcel(filename, columns, rows) {
  const ws = XLSX.utils.aoa_to_sheet([columns, ...rows])
  const wb = XLSX.utils.book_new()
  XLSX.utils.book_append_sheet(wb, ws, 'Data')
  XLSX.writeFile(wb, `${filename}.xlsx`)
}

function triggerDownload(blob, name) {
  const url = URL.createObjectURL(blob)
  const a   = document.createElement('a')
  a.href    = url
  a.download = name
  a.click()
  URL.revokeObjectURL(url)
}

// Chart data → rows
function chartToRows(data) {
  if (!data?.data?.length) return { columns: [], rows: [] }
  const columns = Object.keys(data.data[0])
  const rows    = data.data.map((d) => columns.map((k) => d[k]))
  return { columns, rows }
}

// ── Markdown text renderer ───────────────────────────────────────────────────

function boldify(text) {
  return text
    .replace(/\*\*(.*?)\*\*/g, '<strong>$1</strong>')
    .replace(/`([^`]+)`/g, '<code style="background:#f3f4f6;padding:1px 5px;border-radius:3px;font-size:0.88em;font-family:monospace">$1</code>')
}

function RenderText({ content }) {
  if (!content) return null
  return (
    <div className={styles.textBlock}>
      {content.split('\n').map((line, i) => {
        if (!line.trim()) return <br key={i} />
        if (line.match(/^#{1,3}\s/)) {
          const text = line.replace(/^#{1,3}\s/, '')
          return <p key={i} style={{ fontWeight: 700, margin: '10px 0 4px', color: '#111' }}>{text}</p>
        }
        if (line.startsWith('- ') || line.startsWith('• ')) {
          const text = line.replace(/^[-•]\s/, '')
          return (
            <p key={i} style={{ display: 'flex', gap: 7, margin: '3px 0' }}>
              <span style={{ color: '#9ca3af', flexShrink: 0 }}>•</span>
              <span dangerouslySetInnerHTML={{ __html: boldify(text) }} />
            </p>
          )
        }
        return <p key={i} style={{ margin: '3px 0' }} dangerouslySetInnerHTML={{ __html: boldify(line) }} />
      })}
    </div>
  )
}

// ── Pie chart ─────────────────────────────────────────────────────────────────

function PieArtifact({ data }) {
  const { title, data: slices, unit = '' } = data
  const { columns, rows } = chartToRows(data)

  const renderLabel = ({ name, value, percent }) =>
    `${(percent * 100).toFixed(1)}%`

  return (
    <div className={styles.artifact}>
      <div className={styles.artifactHeader}>
        <span className={styles.artifactTitle}>{title}</span>
        <DownloadBar
          onCSV={() => downloadCSV(title || 'chart', columns, rows)}
          onExcel={() => downloadExcel(title || 'chart', columns, rows)}
        />
      </div>
      <ResponsiveContainer width="100%" height={300}>
        <PieChart>
          <Pie
            data={slices}
            cx="50%"
            cy="50%"
            innerRadius={70}
            outerRadius={120}
            paddingAngle={2}
            dataKey="value"
            labelLine={false}
          >
            {slices.map((_, i) => (
              <Cell key={i} fill={PALETTE[i % PALETTE.length]} />
            ))}
          </Pie>
          <ReTooltip
            formatter={(value, name) => [`${value}${unit}`, name]}
            contentStyle={{ borderRadius: 8, border: '1px solid #f0f0f0', fontSize: 12 }}
          />
          <Legend
            iconType="circle"
            iconSize={8}
            formatter={(value) => <span style={{ fontSize: 12, color: '#374151' }}>{value}</span>}
          />
        </PieChart>
      </ResponsiveContainer>
    </div>
  )
}

// ── Bar chart ─────────────────────────────────────────────────────────────────

function BarArtifact({ data }) {
  const { title, xKey, bars = [], data: rows, unit = '' } = data
  const { columns, rows: tableRows } = chartToRows(data)

  return (
    <div className={styles.artifact}>
      <div className={styles.artifactHeader}>
        <span className={styles.artifactTitle}>{title}</span>
        <DownloadBar
          onCSV={() => downloadCSV(title || 'chart', columns, tableRows)}
          onExcel={() => downloadExcel(title || 'chart', columns, tableRows)}
        />
      </div>
      <ResponsiveContainer width="100%" height={280}>
        <BarChart data={rows} margin={{ top: 4, right: 16, bottom: 4, left: 0 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
          <XAxis dataKey={xKey} tick={{ fontSize: 11, fill: '#6b7280' }} />
          <YAxis tick={{ fontSize: 11, fill: '#6b7280' }} />
          <ReTooltip
            formatter={(value) => [`${value}${unit}`]}
            contentStyle={{ borderRadius: 8, border: '1px solid #f0f0f0', fontSize: 12 }}
          />
          {bars.length > 1 && <Legend iconSize={8} formatter={(v) => <span style={{ fontSize: 12 }}>{v}</span>} />}
          {bars.map((b, i) => (
            <Bar
              key={b.key}
              dataKey={b.key}
              name={b.label || b.key}
              fill={b.color || PALETTE[i % PALETTE.length]}
              radius={[4, 4, 0, 0]}
            />
          ))}
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}

// ── Line chart ────────────────────────────────────────────────────────────────

function LineArtifact({ data }) {
  const { title, xKey, lines = [], data: rows, unit = '' } = data
  const { columns, rows: tableRows } = chartToRows(data)

  return (
    <div className={styles.artifact}>
      <div className={styles.artifactHeader}>
        <span className={styles.artifactTitle}>{title}</span>
        <DownloadBar
          onCSV={() => downloadCSV(title || 'chart', columns, tableRows)}
          onExcel={() => downloadExcel(title || 'chart', columns, tableRows)}
        />
      </div>
      <ResponsiveContainer width="100%" height={280}>
        <LineChart data={rows} margin={{ top: 4, right: 16, bottom: 4, left: 0 }}>
          <CartesianGrid strokeDasharray="3 3" stroke="#f0f0f0" />
          <XAxis dataKey={xKey} tick={{ fontSize: 11, fill: '#6b7280' }} />
          <YAxis tick={{ fontSize: 11, fill: '#6b7280' }} />
          <ReTooltip
            formatter={(value) => [`${value}${unit}`]}
            contentStyle={{ borderRadius: 8, border: '1px solid #f0f0f0', fontSize: 12 }}
          />
          {lines.length > 1 && <Legend iconSize={8} formatter={(v) => <span style={{ fontSize: 12 }}>{v}</span>} />}
          {lines.map((l, i) => (
            <Line
              key={l.key}
              type="monotone"
              dataKey={l.key}
              name={l.label || l.key}
              stroke={l.color || PALETTE[i % PALETTE.length]}
              strokeWidth={2}
              dot={{ r: 3 }}
              activeDot={{ r: 5 }}
            />
          ))}
        </LineChart>
      </ResponsiveContainer>
    </div>
  )
}

// ── Table artifact ────────────────────────────────────────────────────────────

function TableArtifact({ data }) {
  const { title, columns = [], rows = [] } = data
  const [collapsed, setCollapsed] = useState(rows.length > 10)
  const visibleRows = collapsed ? rows.slice(0, 10) : rows

  return (
    <div className={styles.artifact}>
      <div className={styles.artifactHeader}>
        <span className={styles.artifactTitle}>
          {title}
          <span className={styles.rowCount}>{rows.length} rows</span>
        </span>
        <DownloadBar
          onCSV={() => downloadCSV(title || 'data', columns, rows)}
          onExcel={() => downloadExcel(title || 'data', columns, rows)}
        />
      </div>

      <div className={styles.tableWrap}>
        <table className={styles.table}>
          <thead>
            <tr>
              {columns.map((col, i) => (
                <th key={i} className={styles.th}>{col}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {visibleRows.map((row, ri) => (
              <tr key={ri} className={styles.tr}>
                {row.map((cell, ci) => (
                  <td key={ci} className={styles.td}>{cell}</td>
                ))}
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {rows.length > 10 && (
        <button className={styles.toggleRows} onClick={() => setCollapsed((v) => !v)}>
          {collapsed
            ? <><ChevronDown size={13} /> Show all {rows.length} rows</>
            : <><ChevronUp size={13} /> Collapse</>
          }
        </button>
      )}
    </div>
  )
}

// ── Download toolbar ──────────────────────────────────────────────────────────

function DownloadBar({ onCSV, onExcel }) {
  return (
    <div className={styles.downloadBar}>
      <button className={styles.downloadBtn} onClick={onCSV} title="Download CSV">
        <FileText size={12} />
        CSV
      </button>
      <button className={styles.downloadBtn} onClick={onExcel} title="Download Excel">
        <FileSpreadsheet size={12} />
        Excel
      </button>
    </div>
  )
}

// ── Main export ───────────────────────────────────────────────────────────────

export default function AgentArtifacts({ content }) {
  const segments = parseArtifacts(sanitiseOutputContent(content))

  return (
    <div className={styles.root}>
      {segments.map((seg, i) => {
        if (seg.type === 'text') {
          return <RenderText key={i} content={seg.content} />
        }
        if (seg.type === 'chart') {
          if (seg.chartType === 'pie')  return <PieArtifact  key={i} data={seg.data} />
          if (seg.chartType === 'bar')  return <BarArtifact  key={i} data={seg.data} />
          if (seg.chartType === 'line') return <LineArtifact key={i} data={seg.data} />
        }
        if (seg.type === 'table') {
          return <TableArtifact key={i} data={seg.data} />
        }
        return null
      })}
    </div>
  )
}
