'use client'

import { useState, useEffect } from 'react'
import { AlertCircle, Loader, Info, Database, Table2 } from 'lucide-react'
import { canonicalTableReference } from '@/lib/schemaNames'
import { ActionGuard } from '@/components/ActionGuard'
import { HelpTooltip } from './components/HelpTooltip'
import { useSchemaClassification } from './hooks/useSchemaClassification'
import {
    TABLE_ROLES,
    ACCESS_PATTERNS,
    ANTI_PATTERNS,
    SEVERITY_LEVELS,
    HEALTH_SCORE,
    SENSITIVITY_LEVELS,
    CLASSIFICATION_TABS
} from './utils/helpText'
import styles from './SchemaClassificationPanel.module.css'

/**
 * Schema Classification Panel component
 * Shows database schema pattern (STAR, SNOWFLAKE, HYBRID, etc.)
 * Enhanced with access patterns, anti-patterns, health scores, domain, sensitivity, and partition readiness
 */
export function SchemaClassificationPanel({ connectionId, hideHeader = false }) {

    const {
        data,
        loading,
        analyzing,
        error,
        fetchClassification,
        fetchTables,
        analyzeSchema
    } = useSchemaClassification(connectionId)

    const [tables, setTables] = useState([])
    const [roleFilter, setRoleFilter] = useState('')
    const [activeTab, setActiveTab] = useState('overview')

    useEffect(() => {
        if (connectionId) {
            fetchClassification()
        }
    }, [connectionId, fetchClassification])

    useEffect(() => {
        if (connectionId && data) {
            fetchTables(roleFilter || null).then(setTables)
        }
    }, [connectionId, data, roleFilter, fetchTables])

    // handleAnalyze is now handled by parent BrainOverview

    const getRoleColors = (role) => {
        const colorMap = {
            FACT: { text: 'var(--color-primary)', bg: 'var(--color-primary-soft)' },
            DIMENSION: { text: 'var(--color-primary)', bg: 'var(--color-primary-soft)' },
            BRIDGE: { text: 'var(--color-primary)', bg: 'var(--color-primary-soft)' },
            LOOKUP: { text: 'var(--color-primary)', bg: 'var(--color-primary-soft)' },
            ORPHANED: { text: 'var(--color-danger)', bg: 'var(--color-danger-soft)' },
            AGGREGATE: { text: 'var(--color-primary)', bg: 'var(--color-primary-soft)' }
        }
        return colorMap[role] || { text: 'var(--color-light-6)', bg: 'var(--color-light-2)' }
    }

    const getAccessPatternColors = (pattern) => {
        const colorMap = {
            READ_HEAVY: { text: 'var(--color-primary)', bg: 'var(--color-primary-soft)' },
            WRITE_HEAVY: { text: 'var(--color-primary)', bg: 'var(--color-primary-soft)' },
            APPEND_ONLY: { text: 'var(--color-primary)', bg: 'var(--color-primary-soft)' },
            UPDATE_INTENSIVE: { text: 'var(--color-primary)', bg: 'var(--color-primary-soft)' },
            MIXED_WORKLOAD: { text: 'var(--color-primary)', bg: 'var(--color-primary-soft)' },
            RARELY_ACCESSED: { text: 'var(--color-light-6)', bg: 'var(--color-light-2)' }
        }
        return colorMap[pattern] || { text: 'var(--color-light-6)', bg: 'var(--color-light-2)' }
    }

    const getSensitivityColors = (level) => {
        const colorMap = {
            PII_HIGH: { text: 'var(--color-danger)', bg: 'var(--color-danger-soft)' },
            PII_MEDIUM: { text: 'var(--color-warning)', bg: 'var(--color-warning-soft)' },
            FINANCIAL: { text: 'var(--color-warning)', bg: 'var(--color-warning-soft)' },
            HEALTH: { text: 'var(--color-warning)', bg: 'var(--color-warning-soft)' },
            REGULATED: { text: 'var(--color-warning)', bg: 'var(--color-warning-soft)' },
            PUBLIC: { text: 'var(--color-success)', bg: 'var(--color-success-soft)' }
        }
        return colorMap[level] || { text: 'var(--color-light-6)', bg: 'var(--color-light-2)' }
    }

    const getDomainColors = (domain) => {
        const colorMap = {
            CUSTOMER: { text: 'var(--color-primary)', bg: 'var(--color-primary-soft)' },
            PRODUCT: { text: 'var(--color-primary)', bg: 'var(--color-primary-soft)' },
            TRANSACTION: { text: 'var(--color-primary)', bg: 'var(--color-primary-soft)' },
            LOCATION: { text: 'var(--color-primary)', bg: 'var(--color-primary-soft)' },
            TEMPORAL: { text: 'var(--color-primary)', bg: 'var(--color-primary-soft)' },
            CONFIGURATION: { text: 'var(--color-light-6)', bg: 'var(--color-light-2)' },
            SECURITY: { text: 'var(--color-danger)', bg: 'var(--color-danger-soft)' },
            COMMUNICATION: { text: 'var(--color-primary)', bg: 'var(--color-primary-soft)' },
            ANALYTICS: { text: 'var(--color-primary)', bg: 'var(--color-primary-soft)' },
            CONTENT: { text: 'var(--color-primary)', bg: 'var(--color-primary-soft)' },
            UNKNOWN: { text: 'var(--color-light-5)', bg: 'var(--color-light-2)' }
        }
        return colorMap[domain] || { text: 'var(--color-light-6)', bg: 'var(--color-light-2)' }
    }

    const getHealthColor = (score) => {
        if (score >= 80) return 'var(--color-success)'
        if (score >= 60) return 'var(--color-warning)'
        if (score >= 40) return 'var(--color-warning)'
        return 'var(--color-danger)'
    }

    const tabs = [
        { id: 'overview', label: 'Overview' },
        { id: 'health', label: 'Health' },
        { id: 'access', label: 'Access Patterns' },
        { id: 'antipatterns', label: 'Anti-Patterns' },
        { id: 'domains', label: 'Domains' },
        { id: 'sensitivity', label: 'Sensitivity' },
        { id: 'partitioning', label: 'Partitioning' },
    ]

    return (
        <div className={styles.brainPanel}>
            {!hideHeader && (
                <div className={styles.brainHeader}>
                    <div>
                        <h3>Schema Classification</h3>
                        <p>Comprehensive database schema analysis with health scores, domains, and recommendations</p>
                    </div>
                </div>
            )}

            {error && (
                <div className={styles.brainError}>
                    <AlertCircle size={14} />
                    <span>{error}</span>
                </div>
            )}

            {loading && !data && (
                <div className={styles.loadingState}>
                    <Loader className={styles.spinner} size={24} />
                    <p>Loading classification...</p>
                </div>
            )}

            {data && (
                <>
                    {/* Tab Navigation */}
                    <div className={styles.brainTabs}>
                        {tabs.map(tab => (
                            <HelpTooltip key={tab.id} content={CLASSIFICATION_TABS[tab.id]}>
                                <button
                                    onClick={() => setActiveTab(tab.id)}
                                    className={`${styles.brainTab} ${activeTab === tab.id ? styles.brainTabActive : ''}`}
                                >
                                    {tab.label}
                                </button>
                            </HelpTooltip>
                        ))}
                    </div>

                    {/* Empty state - show when 0 tables */}
                    {data.totalTables === 0 && (
                        <div className={styles.warningBox}>
                            <Info size={20} className={styles.warningIcon} />
                            <div>
                                <h4>No Classification Data Yet</h4>
                                <p>
                                    Schema classification runs automatically during Brain initialization.
                                    If this connection has already been initialized, click <strong>Refresh Brain</strong> above to rerun the metadata lifecycle.
                                </p>
                            </div>
                        </div>
                    )}

                    {/* Tab Content */}
                    <div className={styles.tableContainer}>
                        {activeTab === 'overview' && (
                            <TableList
                                tables={tables}
                                roleFilter={roleFilter}
                                setRoleFilter={setRoleFilter}
                                getRoleColors={getRoleColors}
                                mode="overview"
                            />
                        )}

                        {activeTab === 'health' && (
                            <TableList
                                tables={tables.filter(t => t.healthScore != null).sort((a, b) => (a.healthScore || 0) - (b.healthScore || 0))}
                                roleFilter={roleFilter}
                                setRoleFilter={setRoleFilter}
                                getRoleColors={getRoleColors}
                                getHealthColor={getHealthColor}
                                mode="health"
                            />
                        )}

                        {activeTab === 'access' && (
                            <TableList
                                tables={tables.filter(t => t.accessPattern)}
                                roleFilter={roleFilter}
                                setRoleFilter={setRoleFilter}
                                getRoleColors={getRoleColors}
                                getAccessPatternColors={getAccessPatternColors}
                                mode="access"
                            />
                        )}

                        {activeTab === 'antipatterns' && (
                            <TableList
                                tables={tables.filter(t => t.antiPatternCount > 0).sort((a, b) => (b.antiPatternCount || 0) - (a.antiPatternCount || 0))}
                                roleFilter={roleFilter}
                                setRoleFilter={setRoleFilter}
                                getRoleColors={getRoleColors}
                                mode="antipatterns"
                            />
                        )}

                        {activeTab === 'domains' && (
                            <TableList
                                tables={tables.filter(t => t.businessDomain)}
                                roleFilter={roleFilter}
                                setRoleFilter={setRoleFilter}
                                getRoleColors={getRoleColors}
                                getDomainColors={getDomainColors}
                                mode="domains"
                            />
                        )}

                        {activeTab === 'sensitivity' && (
                            <TableList
                                tables={tables.filter(t => t.sensitivityLevel && t.sensitivityLevel !== 'PUBLIC')}
                                roleFilter={roleFilter}
                                setRoleFilter={setRoleFilter}
                                getRoleColors={getRoleColors}
                                getSensitivityColors={getSensitivityColors}
                                mode="sensitivity"
                            />
                        )}

                        {activeTab === 'partitioning' && (
                            <TableList
                                tables={tables.filter(t => t.partitionReadiness && t.partitionReadiness.startsWith('PARTITION_CANDIDATE'))}
                                roleFilter={roleFilter}
                                setRoleFilter={setRoleFilter}
                                getRoleColors={getRoleColors}
                                mode="partitioning"
                            />
                        )}
                    </div>
                </>
            )}

            {!data && !loading && (
                <div className={styles.emptyState}>
                    <Database size={48} />
                    <p className={styles.emptyTitle}>No schema classification available</p>
                    <p className={styles.emptyDesc}>Click "Analyze Schema" to classify your database schema</p>
                </div>
            )}
        </div>
    )
}

// Table List Component for different views
function TableList({ tables, roleFilter, setRoleFilter, getRoleColors, getHealthColor, getAccessPatternColors, getDomainColors, getSensitivityColors, mode }) {
    if (tables.length === 0) {
        const viewMessages = {
            overview: 'No tables classified yet. Run Key Column Analysis first, then re-analyze the schema.',
            health: 'No tables with health scores available.',
            access: 'No access pattern data available. This requires database statistics from pg_stat_user_tables.',
            antipatterns: 'No anti-patterns detected.',
            domains: 'No domain classifications available.',
            sensitivity: 'No tables with sensitive data detected.',
            partitioning: 'No partition candidates identified.'
        }
        return (
            <div style={{ textAlign: 'center', padding: '24px', color: 'var(--color-light-6)' }}>
                <Table2 size={32} style={{ marginBottom: '8px', opacity: 0.5 }} />
                <p>{viewMessages[mode] || 'No tables found for this view'}</p>
            </div>
        )
    }

    // Column definitions with help text
    const columnHelp = {
        'Role': { title: 'Table Role', description: 'Classification of table purpose: FACT (central metrics), DIMENSION (descriptive data), BRIDGE (junction tables), LOOKUP (reference data), ORPHANED (no relationships).' },
        'Health Score': HEALTH_SCORE,
        'Access Pattern': { title: 'Access Pattern', description: 'How this table is being accessed based on read/write statistics.' },
        'Anti-Patterns': { title: 'Anti-Patterns', description: 'Number of schema design issues detected that may impact performance or maintainability.' },
        'Severity': { title: 'Severity Level', description: 'Highest severity among detected anti-patterns: CRITICAL (immediate action), HIGH (soon), MEDIUM (plan to fix), LOW (nice to have).' },
        'Depth': { title: 'Depth from Fact', description: 'Number of joins away from the nearest fact table. Fact tables have depth 0, directly connected dimensions have depth 1.' },
        'Inbound': { title: 'Inbound Joins', description: 'Number of other tables that reference this table (foreign keys pointing here).' },
        'Outbound': { title: 'Outbound Joins', description: 'Number of tables this table references (foreign keys from this table).' }
    }

    const getColumns = () => {
        switch (mode) {
            case 'health':
                return ['Table Name', 'Role', 'Health Score', 'Status', 'Index Eff.', 'Data Quality']
            case 'access':
                return ['Table Name', 'Role', 'Access Pattern', 'Reads', 'Writes', 'R/W Ratio']
            case 'antipatterns':
                return ['Table Name', 'Role', 'Anti-Patterns', 'Severity', 'Types']
            case 'domains':
                return ['Table Name', 'Role', 'Business Domain', 'Confidence', 'Temporal Type']
            case 'sensitivity':
                return ['Table Name', 'Role', 'Sensitivity', 'Confidence', 'Sensitive Columns']
            case 'partitioning':
                return ['Table Name', 'Role', 'Readiness', 'Benefit Est.', 'Key Candidates']
            default:
                return ['Table Name', 'Role', 'Rows', 'Depth', 'Inbound', 'Outbound', 'Confidence']
        }
    }

    const renderCell = (table, column) => {
        switch (column) {
            case 'Table Name':
                return <td className={styles.tableCellName}>{canonicalTableReference(table) || table.tableName}</td>
            case 'Role':
                return (
                    <td className={styles.tableCell}>
                        <HelpTooltip content={TABLE_ROLES[table.tableRole]}>
                            <span className={styles.roleBadge} style={{
                                background: getRoleColors(table.tableRole).bg,
                                color: getRoleColors(table.tableRole).text
                            }}>
                                {table.tableRole}
                            </span>
                        </HelpTooltip>
                    </td>
                )
            case 'Health Score':
                return (
                    <td className={styles.tableCellCenter}>
                        <span className={styles.healthScore} style={{ color: getHealthColor?.(table.healthScore) || '#9CA3AF' }}>
                            {table.healthScore ? `${table.healthScore}%` : '-'}
                        </span>
                    </td>
                )
            case 'Status':
                const status = table.healthScore >= 80 ? 'Healthy' : table.healthScore >= 60 ? 'Warning' : table.healthScore >= 40 ? 'Degraded' : 'Critical'
                return <td className={styles.tableCellCenter} style={{ color: getHealthColor?.(table.healthScore) }}>{status}</td>
            case 'Index Eff.':
                return <td className={styles.tableCellCenter}>{table.healthBreakdown?.index_efficiency?.score ? `${Math.round(table.healthBreakdown.index_efficiency.score)}%` : '-'}</td>
            case 'Data Quality':
                return <td className={styles.tableCellCenter}>{table.healthBreakdown?.data_quality?.score ? `${Math.round(table.healthBreakdown.data_quality.score)}%` : '-'}</td>
            case 'Access Pattern':
                return (
                    <td className={styles.tableCell}>
                        <HelpTooltip content={ACCESS_PATTERNS[table.accessPattern]}>
                            <span className={styles.badge} style={{
                                background: getAccessPatternColors?.(table.accessPattern).bg,
                                color: getAccessPatternColors?.(table.accessPattern).text
                            }}>
                                {table.accessPattern?.replace('_', ' ')}
                            </span>
                        </HelpTooltip>
                    </td>
                )
            case 'Reads':
                return <td className={styles.tableCellRight}>{table.readCount?.toLocaleString() || '-'}</td>
            case 'Writes':
                return <td className={styles.tableCellRight}>{table.writeCount?.toLocaleString() || '-'}</td>
            case 'R/W Ratio':
                return <td className={styles.tableCellRight}>{table.readWriteRatio ? table.readWriteRatio.toFixed(2) : '-'}</td>
            case 'Anti-Patterns':
                return <td className={styles.tableCellCenter} style={{ fontWeight: 600, color: table.antiPatternCount > 0 ? '#EF4444' : '#9CA3AF' }}>{table.antiPatternCount || 0}</td>
            case 'Severity':
                const sevColor = { CRITICAL: '#EF4444', HIGH: '#EF4444', MEDIUM: '#F59E0B', LOW: '#9CA3AF', NONE: '#9CA3AF' }
                return (
                    <td className={styles.tableCellCenter}>
                        <HelpTooltip content={SEVERITY_LEVELS[table.antiPatternSeverity]}>
                            <span style={{ color: sevColor[table.antiPatternSeverity] }}>
                                {table.antiPatternSeverity || 'NONE'}
                            </span>
                        </HelpTooltip>
                    </td>
                )
            case 'Types':
                return (
                    <td className={styles.tableCellSmall}>
                        {table.antiPatterns?.map((ap, idx) => (
                            <HelpTooltip key={idx} content={ANTI_PATTERNS[ap.type]}>
                                <span style={{ marginRight: '8px' }}>
                                    {ap.type}{idx < table.antiPatterns.length - 1 ? ',' : ''}
                                </span>
                            </HelpTooltip>
                        )) || '-'}
                    </td>
                )
            case 'Business Domain':
                return (
                    <td className={styles.tableCell}>
                        <span className={styles.badge} style={{
                            background: getDomainColors?.(table.businessDomain).bg,
                            color: getDomainColors?.(table.businessDomain).text
                        }}>
                            {table.businessDomain}
                        </span>
                    </td>
                )
            case 'Temporal Type':
                return <td className={styles.tableCellSmall}>{table.temporalType !== 'NONE' ? table.temporalType : '-'}</td>
            case 'Sensitivity':
                return (
                    <td className={styles.tableCell}>
                        <HelpTooltip content={SENSITIVITY_LEVELS[table.sensitivityLevel]}>
                            <span className={styles.badge} style={{
                                background: getSensitivityColors?.(table.sensitivityLevel).bg,
                                color: getSensitivityColors?.(table.sensitivityLevel).text
                            }}>
                                {table.sensitivityLevel}
                            </span>
                        </HelpTooltip>
                    </td>
                )
            case 'Sensitive Columns':
                return <td className={styles.tableCellSmall}>{table.sensitiveColumns?.length || 0} columns</td>
            case 'Readiness':
                return <td className={styles.tableCellSmall}>{table.partitionReadiness?.replace('PARTITION_CANDIDATE_', '') || '-'}</td>
            case 'Benefit Est.':
                return <td className={styles.tableCellCenter}>{table.estimatedPartitionBenefit ? `${(table.estimatedPartitionBenefit * 100).toFixed(0)}%` : '-'}</td>
            case 'Key Candidates':
                return <td className={styles.tableCellSmall}>{table.partitionKeyCandidates?.map(k => k.column).join(', ') || '-'}</td>
            case 'Rows':
                return <td className={styles.tableCellRight}>{table.rowCount ? table.rowCount.toLocaleString() : '-'}</td>
            case 'Depth':
                return <td className={styles.tableCellCenter}>{table.depthFromFact !== null ? table.depthFromFact : '-'}</td>
            case 'Inbound':
                return <td className={styles.tableCellCenter}>{table.inboundJoinCount || 0}</td>
            case 'Outbound':
                return <td className={styles.tableCellCenter}>{table.outboundJoinCount || 0}</td>
            case 'Confidence':
                return <td className={styles.tableCellCenter}>{table.confidenceScore ? `${table.confidenceScore}%` : '-'}</td>
            default:
                return <td className={styles.tableCell}>-</td>
        }
    }

    const columns = getColumns()

    return (
        <div className={styles.tableWrapper}>
            <div className={styles.tableHeader}>
                <h4>
                    {mode === 'overview' ? 'Table Classifications' : `${mode.charAt(0).toUpperCase() + mode.slice(1)} Analysis`}
                    <span>({tables.length} tables)</span>
                </h4>
            </div>

            <div className={styles.tableScroll}>
                <table className={styles.table}>
                    <thead>
                        <tr>
                            {columns.map(col => (
                                <th key={col} className={col === 'Table Name' ? styles.thLeft : styles.thCenter}>
                                    <HelpTooltip content={columnHelp[col]}>
                                        <span>{col}</span>
                                    </HelpTooltip>
                                </th>
                            ))}
                        </tr>
                    </thead>
                    <tbody>
                        {tables.map((table, idx) => (
                            <tr key={idx}>
                                {columns.map(col => renderCell(table, col))}
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>
        </div>
    )
}
