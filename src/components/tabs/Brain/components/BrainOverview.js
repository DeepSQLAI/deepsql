'use client'

import { useState, useMemo, useCallback, useEffect, useRef } from 'react'
import { Network, Database, RefreshCw } from 'lucide-react'
import { connectionAPI } from '@/lib/api/client'
import { useQueryClient } from '@tanstack/react-query'
import { useBrainData } from '../hooks/useBrainData'
import { useKeyColumns } from '../hooks/useKeyColumns'
import { useSchemaClassification } from '../hooks/useSchemaClassification'
import { useTableClassifications, useBrainScore } from '@/lib/hooks/queries/useBrain'
import useInitProgressStore from '@/lib/stores/useInitProgressStore'
import { queryKeys } from '@/lib/queryKeys'
import { SchemaERD3D, ERD3DErrorBoundary, SchemaDiagramFilter, DEFAULT_ROLE_FILTERS } from '../SchemaERD3D'
import { SchemaClassificationPanel } from '../SchemaClassificationPanel'
import BrainOverviewHero from './BrainOverviewHero'
import styles from './BrainOverview.module.css'

/**
 * Consolidated Brain Overview component with compact design.
 * Shows quick insights and collapsible detail panels.
 */
export default function BrainOverview({
    connectionId,
    activeTab,
    onTabChange
}) {
    const [isAnalyzing, setIsAnalyzing] = useState(false)
    const [forceRebuildConfirmOpen, setForceRebuildConfirmOpen] = useState(false)
    const [isForceRebuilding, setIsForceRebuilding] = useState(false)
    const [roleFilters, setRoleFilters] = useState(DEFAULT_ROLE_FILTERS)
    const previousInitStageRef = useRef(null)
    const queryClient = useQueryClient()
    const initStage = useInitProgressStore((state) => state.stage)
    const initConnectionId = useInitProgressStore((state) => state.connectionId)

    // Fetch data using existing hooks
    const brainData = useBrainData(connectionId)
    const { data: brainScoreData, isLoading: brainScoreLoading, error: brainScoreError } = useBrainScore(connectionId)
    
    // Debug: Log brain score data
    useEffect(() => {
        if (brainScoreData) {
            console.log('Brain Score Data:', brainScoreData)
        }
        if (brainScoreError) {
            console.error('Brain Score Error:', brainScoreError)
        }
    }, [brainScoreData, brainScoreError])
    const { data: keyColumnsData } = useKeyColumns(connectionId)
    const { data: schemaClassification } = useSchemaClassification(connectionId)
    const { data: tableClassifications } = useTableClassifications(connectionId)

    // Basic stats from schema
    const stats = useMemo(() => {
        const data = brainData.data
        const tables = data?.tables || []
        const columns = data?.columns || []
        const totalColumns = data?.columnCount ?? columns.length
        
        // Calculate total rows
        const totalRows = (tableClassifications || []).reduce((sum, t) => sum + (Number(t.rowCount) || 0), 0)
        
        // Calculate total key columns
        const totalKeyColumns = keyColumnsData?.topColumns?.length || 0

        // Schema classification stats
        const factTables = schemaClassification?.factTables || 0
        const dimensionTables = schemaClassification?.dimensionTables || 0
        const piiTables = schemaClassification?.piiTablesCount || 0
        const partitionCandidates = schemaClassification?.partitionCandidatesCount || 0
        const antiPatternTables = schemaClassification?.tablesWithAntiPatterns || 0

        // Check multiple sources for last analyzed timestamp
        const lastAnalyzed = keyColumnsData?.analyzedAt ||
            schemaClassification?.analyzedAt ||
            data?.lastProfiledAt

        return {
            tableCount: data?.tableCount ?? tables.length,
            columnCount: totalColumns,
            totalRows,
            totalKeyColumns,
            factTables,
            dimensionTables,
            piiTables,
            partitionCandidates,
            antiPatternTables,
            lastAnalyzed,
        }
    }, [brainData.data, keyColumnsData, schemaClassification, tableClassifications])

    useEffect(() => {
        const runJustCompleted =
            previousInitStageRef.current &&
            !['COMPLETED', 'FAILED'].includes(previousInitStageRef.current) &&
            initStage === 'COMPLETED' &&
            initConnectionId === connectionId

        if (runJustCompleted) {
            queryClient.invalidateQueries({ queryKey: queryKeys.brain.all(connectionId) })
            queryClient.invalidateQueries({ queryKey: queryKeys.schema.all(connectionId) })
            brainData.refresh()
        }

        previousInitStageRef.current = initStage
    }, [brainData, connectionId, initConnectionId, initStage, queryClient])

    // Handler: Run full analysis
    const handleAnalyze = useCallback(async () => {
        if (!connectionId || isAnalyzing) return

        setIsAnalyzing(true)
        try {
            await connectionAPI.reinitialize(connectionId)
            brainData.refresh()
        } catch (err) {
            console.error('Analysis failed:', err)
        } finally {
            setIsAnalyzing(false)
        }
    }, [connectionId, isAnalyzing, brainData])

    const handleForceRebuild = useCallback(async () => {
        if (!connectionId || isForceRebuilding) return
        setIsForceRebuilding(true)
        setForceRebuildConfirmOpen(false)
        try {
            await connectionAPI.forceRebuild(connectionId)
            brainData.refresh()
        } catch (err) {
            console.error('Force rebuild failed:', err)
        } finally {
            setIsForceRebuilding(false)
        }
    }, [connectionId, isForceRebuilding, brainData])

    return (
        <div className={styles.overview}>
            {/* Force Rebuild confirmation modal */}
            {forceRebuildConfirmOpen && (
                <div
                    style={{
                        position: 'fixed', inset: 0, background: 'rgba(0,0,0,0.45)',
                        zIndex: 9999, display: 'flex', alignItems: 'center', justifyContent: 'center',
                    }}
                    onClick={() => setForceRebuildConfirmOpen(false)}
                >
                    <div
                        style={{
                            background: '#fff', borderRadius: '12px', padding: '28px 32px',
                            maxWidth: '440px', width: '90%', boxShadow: '0 20px 60px rgba(0,0,0,0.18)',
                        }}
                        onClick={(e) => e.stopPropagation()}
                    >
                        <div style={{ display: 'flex', alignItems: 'center', gap: '10px', marginBottom: '12px' }}>
                            <div style={{
                                width: '36px', height: '36px', borderRadius: '50%',
                                background: '#fef2f2', display: 'flex', alignItems: 'center', justifyContent: 'center', flexShrink: 0,
                            }}>
                                <RefreshCw size={18} color="#dc2626" />
                            </div>
                            <h3 style={{ margin: 0, fontSize: '16px', fontWeight: 700, color: '#111827' }}>
                                Force Full Rebuild?
                            </h3>
                        </div>
                        <p style={{ margin: '0 0 8px', fontSize: '14px', color: '#374151', lineHeight: '1.6' }}>
                            This will rebuild <strong>everything from scratch</strong> — schema scan, data sampling, AI descriptions, embeddings, and all analysis stages.
                        </p>
                        <p style={{ margin: '0 0 24px', fontSize: '13px', color: '#6b7280', lineHeight: '1.6' }}>
                            Expect this to take <strong>30 minutes to 1 hour</strong> depending on the size of your database. The Brain will be unavailable for queries during this time.
                        </p>
                        <div style={{ display: 'flex', gap: '10px', justifyContent: 'flex-end' }}>
                            <button
                                onClick={() => setForceRebuildConfirmOpen(false)}
                                style={{
                                    padding: '8px 18px', borderRadius: '8px', border: '1px solid #e5e7eb',
                                    background: 'transparent', color: '#6b7280', fontSize: '13px',
                                    fontWeight: 500, cursor: 'pointer',
                                }}
                            >
                                Cancel
                            </button>
                            <button
                                onClick={handleForceRebuild}
                                style={{
                                    padding: '8px 18px', borderRadius: '8px', border: 'none',
                                    background: '#dc2626', color: '#fff', fontSize: '13px',
                                    fontWeight: 600, cursor: 'pointer',
                                }}
                            >
                                Yes, Force Rebuild
                            </button>
                        </div>
                    </div>
                </div>
            )}

            {/* Hero */}
            <BrainOverviewHero
                onAnalyze={handleAnalyze}
                isAnalyzing={isAnalyzing}
                onForceRebuild={() => setForceRebuildConfirmOpen(true)}
                isForceRebuilding={isForceRebuilding}
                activeTab={activeTab}
                onTabChange={onTabChange}
                connectionId={connectionId}
            />

            {/* Brain Score Section (Centered) */}
            <div className={styles.brainScoreSection}>
                <div className={styles.brainScoreContainer}>
                    <div className={styles.brainScoreCard}>
                        {(() => {
                            const score = brainScoreData?.overallScore ?? brainData.data?.overallScore ?? null
                            const isLoading = brainScoreLoading
                            
                            return (
                                <>
                                    <div className={styles.brainScoreValue}>
                                        {isLoading ? (
                                            <span style={{ fontSize: '32px', color: '#9CA3AF' }}>...</span>
                                        ) : score != null ? (
                                            Math.round(Number(score))
                                        ) : (
                                            '--'
                                        )}
                                    </div>
                                    <div className={styles.brainScoreLabel}>Brain Score</div>
                                    {score != null ? (
                                        <div className={styles.brainScoreBar}>
                                            <div 
                                                className={styles.brainScoreBarFill}
                                                style={{ width: `${Number(score)}%` }}
                                            />
                                        </div>
                                    ) : !isLoading && (
                                        <div style={{ fontSize: '11px', color: '#9CA3AF', marginTop: '8px' }}>
                                            Run analysis to calculate
                                        </div>
                                    )}
                                </>
                            )
                        })()}
                    </div>
                    
                    {/* Stats next to Brain Score */}
                    <div className={styles.brainStats}>
                        <div className={styles.statItem}>
                            <span className={styles.statValue}>{stats.tableCount}</span>
                            <span className={styles.statLabel}>Tables</span>
                        </div>
                        <div className={styles.statItem}>
                            <span className={styles.statValue}>{stats.factTables}</span>
                            <span className={styles.statLabel} style={{ color: 'var(--color-primary)' }}>Facts</span>
                        </div>
                        <div className={styles.statItem}>
                            <span className={styles.statValue}>{stats.dimensionTables}</span>
                            <span className={styles.statLabel} style={{ color: 'var(--color-success)' }}>Dims</span>
                        </div>
                        {stats.antiPatternTables > 0 && (
                            <div className={styles.statItem}>
                                <span className={styles.statValue} style={{ color: '#EF4444' }}>
                                    {stats.antiPatternTables}
                                </span>
                                <span className={styles.statLabel}>Anti-Patterns</span>
                            </div>
                        )}
                        {stats.piiTables > 0 && (
                            <div className={styles.statItem}>
                                <span className={styles.statValue} style={{ color: '#F59E0B' }}>{stats.piiTables}</span>
                                <span className={styles.statLabel}>PII</span>
                            </div>
                        )}
                    </div>

                    {/* Schema Classification Badge */}
                    {schemaClassification?.classification && (
                        <div className={styles.schemaBadge}>
                            <Database size={20} className={styles.schemaBadgeIcon} />
                            <div className={styles.schemaBadgeText}>
                                <span className={styles.schemaBadgeLabel}>Schema Type</span>
                                <span className={styles.schemaBadgeValue}>{schemaClassification.classification}</span>
                            </div>
                        </div>
                    )}
                </div>
            </div>

            {/* Schema - always visible, non-collapsible; stats below header; filter on same line as header */}
            <div className={styles.staticSection}>
                <div className={styles.staticSectionHeaderRow}>
                    <div className={styles.staticSectionHeader}>
                        <Network size={16} />
                        <div className={styles.sectionInfo}>
                            <span className={styles.sectionTitle}>Schema</span>
                            <span className={styles.sectionDesc}>Interactive schema visualization showing table relationships, foreign keys, and join graphs.</span>
                        </div>
                    </div>
                    <div className={styles.schemaHeaderControls}>
                        <SchemaDiagramFilter roleFilters={roleFilters} setRoleFilters={setRoleFilters} />
                    </div>
                </div>
                <div className={styles.sectionContent}>
                    <ERD3DErrorBoundary>
                        <SchemaERD3D
                            connectionId={connectionId}
                            height={700}
                            hideTitle
                            roleFilters={roleFilters}
                            setRoleFilters={setRoleFilters}
                        />
                    </ERD3DErrorBoundary>
                </div>
            </div>

            {/* Schema Classification Section */}
            <div className={styles.staticSection}>
                <SchemaClassificationPanel connectionId={connectionId} hideHeader />
            </div>
        </div>
    )
}
