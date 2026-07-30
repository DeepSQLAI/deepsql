'use client'

import { useState } from 'react'
import { analyzeAllNormalForms } from './utils/normalFormUtils'
import { HelpTooltip } from './components/HelpTooltip'
import { NORMAL_FORMS } from './utils/helpText'
import styles from './NormalFormsPanel.module.css'

/**
 * Panel showing comprehensive normal form analysis (1NF - 5NF + BCNF)
 */
export function NormalFormsPanel({ table }) {
    const [expanded, setExpanded] = useState(false)

    if (!table) {
        return null
    }

    const analysis = analyzeAllNormalForms(table)

    const getStatusColor = (status) => {
        switch (status) {
            case 'COMPLIANT':
            case 'LIKELY_COMPLIANT':
                return styles.statusGreen
            case 'VIOLATION':
            case 'NEEDS_REVIEW':
                return styles.statusYellow
            case 'NEEDS_EXPERT_REVIEW':
                return styles.statusOrange
            case 'NOT_APPLICABLE':
                return styles.statusGray
            default:
                return styles.statusGray
        }
    }

    const normalForms = ['1NF', '2NF', '3NF', 'BCNF', '4NF', '5NF']

    return (
        <div className={styles.normalFormsPanel}>
            <div className={styles.header} onClick={() => setExpanded(!expanded)}>
                <h4>Normal Forms Analysis</h4>
                <span className={styles.toggle}>{expanded ? '▼' : '▶'}</span>
            </div>

            {expanded && (
                <div className={styles.content}>
                    <div className={styles.summary}>
                        {normalForms.map(nf => {
                            const result = analysis[nf]
                            if (!result) return null

                            return (
                                <div key={nf} className={styles.nfCard}>
                                    <div className={styles.nfHeader}>
                                        <HelpTooltip content={NORMAL_FORMS[nf]}>
                                            <span className={styles.nfName}>
                                                {nf}
                                            </span>
                                        </HelpTooltip>
                                        <span className={`${styles.status} ${getStatusColor(result.status)}`}>
                                            {result.status?.replace(/_/g, ' ')}
                                        </span>
                                    </div>

                                    {result.score !== null && result.score !== undefined && (
                                        <div className={styles.score}>
                                            <div className={styles.scoreBar}>
                                                <div
                                                    className={styles.scoreProgress}
                                                    style={{ width: `${result.score}%` }}
                                                />
                                            </div>
                                            <span className={styles.scoreValue}>{result.score}</span>
                                        </div>
                                    )}

                                    {result.issues && result.issues.length > 0 && (
                                        <div className={styles.issues}>
                                            <div className={styles.issuesHeader}>Issues:</div>
                                            <ul>
                                                {result.issues.map((issue, i) => (
                                                    <li key={i}>{issue}</li>
                                                ))}
                                            </ul>
                                        </div>
                                    )}

                                    {result.suggestions && result.suggestions.length > 0 && (
                                        <div className={styles.suggestions}>
                                            <div className={styles.suggestionsHeader}>Suggestions:</div>
                                            <ul>
                                                {result.suggestions.map((suggestion, i) => (
                                                    <li key={i}>{suggestion}</li>
                                                ))}
                                            </ul>
                                        </div>
                                    )}
                                </div>
                            )
                        })}
                    </div>
                </div>
            )}
        </div>
    )
}
