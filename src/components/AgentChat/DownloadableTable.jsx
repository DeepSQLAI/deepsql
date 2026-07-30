import { useRef } from 'react'
import { Download } from 'lucide-react'
import styles from './AgentChatPanel.module.css'

function escapeCsvCell(value) {
  const str = String(value ?? '')
  if (/[",\n\r]/.test(str)) return `"${str.replace(/"/g, '""')}"`
  return str
}

function tableToCsv(table) {
  return [...table.querySelectorAll('tr')]
    .map((tr) =>
      [...tr.querySelectorAll('th,td')]
        .map((cell) => escapeCsvCell(cell.textContent.replace(/\s+/g, ' ').trim()))
        .join(','),
    )
    .filter((row) => row.length > 0)
    .join('\n')
}

function downloadCsv(csv, filename) {
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8' })
  const url = URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  a.click()
  URL.revokeObjectURL(url)
}

/** GFM table with a CSV download control — used by AgentMarkdown. */
export default function DownloadableTable({ children }) {
  const tableRef = useRef(null)

  const onDownload = () => {
    const table = tableRef.current
    if (!table) return
    const csv = tableToCsv(table)
    if (!csv) return
    const stamp = new Date().toISOString().slice(0, 10)
    downloadCsv(csv, `deepsql-table-${stamp}.csv`)
  }

  return (
    <div className={styles.tableBlock}>
      <div className={styles.tableToolbar}>
        <button type="button" className={styles.tableDownload} onClick={onDownload} title="Download CSV">
          <Download size={13} />
          Download CSV
        </button>
      </div>
      <div className={styles.tableWrap}>
        <table ref={tableRef}>{children}</table>
      </div>
    </div>
  )
}
