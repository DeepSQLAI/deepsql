'use client'

import { AlertCircle, FlaskConical, RefreshCcw, Loader } from 'lucide-react'
import { formatTimestamp, formatPercent } from './utils/formatUtils'
import { calculateBcnfStats } from './utils/bcnfUtils'
import { getTrendMeta } from './utils/statusUtils'
import { HelpTooltip } from './components/HelpTooltip'
import { BRAIN_SCORE, NORMAL_FORMS, QUALITY_DIMENSIONS } from './utils/helpText'
import styles from '../Core/RagTrainingTab.module.css'

/**
 * Understanding Panel component
 * Shows overall score, coverage metrics, and BCNF analysis
 */
export function UnderstandingPanel({
    brainData,
    loading,
    error,
    profileResult,
    profileLoading,
    rescanLoading,
    onProfile,
    onRescan,
    onRefresh,
    onOpenBcnfReview,
    hideCTAs = false
}) {
    const brainSummaryLoading = loading && !brainData
    const trendMeta = getTrendMeta(brainData?.scoreHistory)
    const scoreHistory = brainData?.scoreHistory || []
    const tableCount = brainData?.tableCount ?? null
    const columnCount = brainData?.columnCount ?? null
    const schemaDrift = brainData?.schemaDrift

    const tables = brainData?.tables || []
    const columns = brainData?.columns || []

    const profiledColumnsCount = columns.filter((col) => col.profiledAt).length
    const documentedColumnsCount = columns.filter((col) => col.documented).length
    const usedColumnsCount = columns.filter((col) => (col.usageCount ?? 0) > 0).length
    const usedTablesCount = tables.filter((table) => (table.usageCount ?? 0) > 0).length

    const resolvedColumnCount = columnCount ?? 0
    const resolvedTableCount = tableCount ?? 0
    const profileCoverage = resolvedColumnCount > 0 ? (profiledColumnsCount / resolvedColumnCount) * 100 : null
    const docCoverage = resolvedColumnCount > 0 ? (documentedColumnsCount / resolvedColumnCount) * 100 : null
    const usageCoverage = resolvedColumnCount > 0 ? (usedColumnsCount / resolvedColumnCount) * 100 : null

    const bcnfStats = calculateBcnfStats(tables)
    const bcnfTotal = bcnfStats.total || 0
    const bcnfLikelyPct = bcnfTotal > 0 ? (bcnfStats.likely / bcnfTotal) * 100 : 0
    const bcnfReviewPct = bcnfTotal > 0 ? (bcnfStats.review / bcnfTotal) * 100 : 0
    const bcnfUnknownPct = bcnfTotal > 0 ? (bcnfStats.unknown / bcnfTotal) * 100 : 0
    const tablesProfiled = profileResult?.tablesProfiled ?? 0
    const columnsProfiled = profileResult?.columnsProfiled ?? 0
    const tablesSkipped = profileResult?.tablesSkipped ?? 0
    const columnsSkipped = profileResult?.columnsSkipped ?? 0
    const skippedParts = []
    if (columnsSkipped) skippedParts.push(`${columnsSkipped} columns skipped`)
    if (tablesSkipped) skippedParts.push(`${tablesSkipped} tables skipped`)
    const skippedLabel = skippedParts.length ? skippedParts.join(' · ') : 'No skips'
    const profileHasSignal = Boolean(tablesProfiled || columnsProfiled || tablesSkipped || columnsSkipped)
    const profileHasSkips = Boolean(tablesSkipped || columnsSkipped)
    const profileNote = !profileHasSignal
        ? 'No items were profiled in this run.'
        : !profileHasSkips
            ? 'No skips in this run.'
            : null

    return (
        <div className={styles.brainPanel}>
            {!hideCTAs && (
                <div className={styles.brainHeader}>
                    <div>
                        <HelpTooltip content={BRAIN_SCORE}>
                            <h3>Understanding</h3>
                        </HelpTooltip>
                        <p>Coverage of schema, usage, and documentation.</p>
                    </div>
                    {!hideCTAs && (
                        <div className={styles.brainHeaderActions}>
                        <button
                            className={styles.profileButton}
                            onClick={onProfile}
                            disabled={profileLoading}
                        >
                            {profileLoading ? <Loader className={styles.spinner} size={14} /> : <FlaskConical size={14} />}
                            <span>Profile columns</span>
                        </button>
                        <button
                            className={styles.profileButton}
                            onClick={onRescan}
                            disabled={rescanLoading}
                        >
                            {rescanLoading ? <Loader className={styles.spinner} size={14} /> : <RefreshCcw size={14} />}
                            <span>Rescan schema</span>
                        </button>
                        <button
                            className={styles.refreshButton}
                            onClick={onRefresh}
                            disabled={loading}
                        >
                            {loading ? <Loader className={styles.spinner} size={14} /> : 'Refresh'}
                        </button>
                    </div>
                    )}
                </div>
            )}

            {profileResult ? (
                <div className={styles.profileSummaryRow}>
                    <span className={styles.profileSummaryLabel}>Last profile</span>
                    <span className={styles.profileSummaryValue}>
                        {columnsProfiled} columns · {tablesProfiled} tables
                    </span>
                    <span className={styles.profileSummaryMeta}>{skippedLabel}</span>
                    <span className={styles.profileSummaryMeta}>
                        {formatTimestamp(profileResult.profiledAt)}
                    </span>
                </div>
            ) : null}

            {profileResult ? (
                <details className={styles.profileDetails}>
                    <summary>Profile results</summary>
                    <div className={styles.profileChipRow}>
                        <span className={styles.profileChip}>{tablesProfiled} tables profiled</span>
                        <span className={styles.profileChip}>{columnsProfiled} columns profiled</span>
                        <span className={styles.profileChip}>{tablesSkipped} tables skipped</span>
                        <span className={styles.profileChip}>{columnsSkipped} columns skipped</span>
                        {profileCoverage !== null ? (
                            <span className={styles.profileChip}>
                                Coverage {formatPercent(profileCoverage)}
                            </span>
                        ) : null}
                    </div>
                    {profileNote ? (
                        <div className={styles.profileEmpty}>{profileNote}</div>
                    ) : null}
                </details>
            ) : null}

            {error ? (
                <div className={styles.brainError}>
                    <AlertCircle size={14} />
                    <span>{error}</span>
                </div>
            ) : null}

            {!hideCTAs && !schemaDrift?.lastSnapshotAt ? (
                <div className={styles.brainCallout}>
                    <div>
                        <strong>No schema snapshot yet.</strong> Run a rescan to capture the current schema for faster refreshes.
                    </div>
                    <button
                        className={styles.brainActionButton}
                        onClick={onRescan}
                        disabled={rescanLoading}
                    >
                        {rescanLoading ? 'Rescanning...' : 'Rescan schema'}
                    </button>
                </div>
            ) : null}

            <div className={styles.brainSummaryGrid}>
                {/* Overall Score Card */}
                <div className={styles.brainScoreCard}>
                    <div className={styles.brainScoreValue}>
                        {brainSummaryLoading ? '...' : (brainData?.overallScore ?? '--')}
                    </div>
                    <div className={styles.brainScoreLabel}>Overall score</div>
                    <div className={styles.brainProgressTrack}>
                        <span
                            className={styles.brainProgressFill}
                            style={{ width: `${brainData?.overallScore ?? 0}%` }}
                        />
                    </div>
                    <div className={styles.brainMeta}>
                        Tables: {tableCount ?? '--'} - Columns: {columnCount ?? '--'}
                    </div>
                    <div className={styles.brainMeta}>
                        Last training: {formatTimestamp(brainData?.lastTrainingAt)}
                    </div>
                    <div className={styles.brainMetaTrend}>
                        <span>{trendMeta.icon}</span>
                        <span>{trendMeta.label}</span>
                    </div>
                </div>

                {/* Coverage Card */}
                <div className={styles.brainCoverageCard}>
                    <div className={styles.coverageHeader}>
                        <h4>Coverage</h4>
                        <span>Signals from SQL Runner + EXPLAIN</span>
                    </div>
                    <div className={styles.coverageItem}>
                        <div className={styles.coverageLabel}>
                            <span>Profiled columns</span>
                            <span>{profiledColumnsCount}/{columnCount ?? '--'}</span>
                        </div>
                        <div className={styles.coverageTrack}>
                            <span
                                className={styles.coverageFill}
                                style={{ width: `${profileCoverage ?? 0}%` }}
                            />
                        </div>
                    </div>
                    <div className={styles.coverageItem}>
                        <div className={styles.coverageLabel}>
                            <span>Documented columns</span>
                            <span>{documentedColumnsCount}/{columnCount ?? '--'}</span>
                        </div>
                        <div className={styles.coverageTrack}>
                            <span
                                className={styles.coverageFill}
                                style={{ width: `${docCoverage ?? 0}%` }}
                            />
                        </div>
                    </div>
                    <div className={styles.coverageItem}>
                        <div className={styles.coverageLabel}>
                            <span>Usage coverage</span>
                            <span>{usedColumnsCount}/{columnCount ?? '--'} columns • {usedTablesCount}/{tableCount ?? '--'} tables</span>
                        </div>
                        <div className={styles.coverageTrack}>
                            <span
                                className={styles.coverageFill}
                                style={{ width: `${usageCoverage ?? 0}%` }}
                            />
                        </div>
                    </div>
                </div>

                {/* BCNF Card */}
                <div className={styles.brainCoverageCard}>
                    <div className={styles.coverageHeader}>
                        <h4>Normalization (BCNF)</h4>
                        <span>Heuristic checks from keys, FKs, and column patterns.</span>
                    </div>
                    <div className={styles.coverageItem}>
                        <div className={styles.coverageLabel}>
                            <span>Likely BCNF</span>
                            <span>{bcnfStats.likely}/{bcnfTotal || '--'}</span>
                        </div>
                        <div className={styles.coverageTrack}>
                            <span
                                className={styles.coverageFill}
                                style={{ width: `${bcnfLikelyPct}%` }}
                            />
                        </div>
                    </div>
                    <div className={styles.coverageItem}>
                        <div className={styles.coverageLabel}>
                            <span>Needs review</span>
                            <button
                                type="button"
                                className={styles.coverageLink}
                                onClick={onOpenBcnfReview}
                                disabled={bcnfStats.review === 0}
                            >
                                {bcnfStats.review}/{bcnfTotal || '--'}
                            </button>
                        </div>
                        <div className={styles.coverageTrack}>
                            <span
                                className={styles.coverageFill}
                                style={{ width: `${bcnfReviewPct}%` }}
                            />
                        </div>
                    </div>
                    <div className={styles.coverageItem}>
                        <div className={styles.coverageLabel}>
                            <span>Unknown</span>
                            <span>{bcnfStats.unknown}/{bcnfTotal || '--'}</span>
                        </div>
                        <div className={styles.coverageTrack}>
                            <span
                                className={styles.coverageFill}
                                style={{ width: `${bcnfUnknownPct}%` }}
                            />
                        </div>
                    </div>
                    <div className={styles.coverageMeta}>
                        Average BCNF score: {bcnfStats.average ?? '--'}
                    </div>
                </div>
            </div>
        </div>
    )
}
