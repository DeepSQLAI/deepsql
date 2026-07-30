'use client'

import { ChevronDown, ChevronRight } from 'lucide-react'
import styles from './BrainAnalyticsLayout.module.css'

export default function CollapsibleSection({
  id,
  title,
  description,
  summaryChips = [],
  expanded = false,
  onToggle,
  children,
}) {
  return (
    <section id={id} className={styles.section}>
      <button
        type="button"
        className={styles.sectionHeader}
        onClick={onToggle}
        aria-expanded={expanded}
        aria-controls={`${id}-content`}
      >
        <div className={styles.sectionHeaderLeft}>
          <span className={styles.sectionTitle}>{title}</span>
          {description && <span className={styles.sectionDesc}>{description}</span>}
        </div>
        <div className={styles.sectionHeaderRight}>
          <div className={styles.sectionChips}>
            {summaryChips.length > 0 ? (
              summaryChips.map((chip, idx) => (
                <span key={`${id}-chip-${idx}`} className={styles.chip}>{chip}</span>
              ))
            ) : (
              <span className={styles.chip}>No data</span>
            )}
          </div>
          {expanded ? <ChevronDown size={18} /> : <ChevronRight size={18} />}
        </div>
      </button>
      {expanded && (
        <div id={`${id}-content`} className={styles.sectionContent}>
          {children}
        </div>
      )}
    </section>
  )
}
