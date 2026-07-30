'use client'

import { RefreshCw, Loader2, RotateCcw } from 'lucide-react'
import { BrainTabDropdown } from './BrainTabDropdown'
import { InitProgressIndicator } from '@/components/InitProgressIndicator'
import styles from './BrainOverviewHero.module.css'

/**
 * Hero header for Brain Overview showing title, stats, analyze button, and tab navigation dropdown.
 */
export default function BrainOverviewHero({
    title = 'Brain',
    subtitle = 'AI-powered database structure analysis and optimization insights',
    onAnalyze,
    isAnalyzing = false,
    onForceRebuild,
    isForceRebuilding = false,
    activeTab = 'overview',
    onTabChange,
    connectionId,
    children
}) {
    return (
        <div className={styles.hero}>
            <div className={styles.left}>
                <h2 className={styles.title}>{title}</h2>
                <p className={styles.subtitle}>{subtitle}</p>
                {children}
            </div>
            <div className={styles.rightActions}>
                {/* Brain init status — compact icon-only chip */}
                {connectionId && (
                    <div className={styles.initIconWrap}>
                        <InitProgressIndicator connectionId={connectionId} />
                    </div>
                )}
                {onForceRebuild && (
                    <div className={styles.analyzeButtonWrapper}>
                        <span className={styles.analyzeTooltip} role="tooltip">
                            Force Full Rebuild
                        </span>
                        <button
                            className={styles.forceRebuildButton}
                            onClick={onForceRebuild}
                            disabled={isForceRebuilding || isAnalyzing}
                            aria-label="Force Full Rebuild"
                            title="Force Full Rebuild"
                        >
                            {isForceRebuilding ? (
                                <Loader2 size={18} className={styles.spinner} />
                            ) : (
                                <RotateCcw size={18} />
                            )}
                        </button>
                    </div>
                )}
                {onAnalyze && (
                    <div className={styles.analyzeButtonWrapper}>
                        <span className={styles.analyzeTooltip} role="tooltip">
                            Refresh Brain
                        </span>
                        <button
                            className={styles.analyzeButton}
                            onClick={onAnalyze}
                            disabled={isAnalyzing || isForceRebuilding}
                            aria-label="Refresh Brain"
                            title="Refresh Brain"
                        >
                            {isAnalyzing ? (
                                <Loader2 size={18} className={styles.spinner} />
                            ) : (
                                <RefreshCw size={18} />
                            )}
                        </button>
                    </div>
                )}
                {onTabChange && <BrainTabDropdown activeTab={activeTab} onTabChange={onTabChange} />}
            </div>
        </div>
    )
}
