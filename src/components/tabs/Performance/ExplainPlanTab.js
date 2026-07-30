'use client'

import { useState, useEffect, useMemo, useRef, lazy, Suspense } from 'react'
import { AlertCircle, CheckCircle, AlertTriangle, Info, Zap, Loader2, Copy, Check, ChevronRight, ChevronDown, X, History, Clock } from 'lucide-react'
import { explainAPI, chatAPI } from '@/lib/api/client'
import styles from './ExplainPlanTab.module.css'
import { saveTabState, loadTabState } from '@/utils/tabStateCache'

// Lazy load Monaco Editor for better performance
const Editor = lazy(() => import('@monaco-editor/react'))

const DIAGRAM_NODE_WIDTH = 240
const DIAGRAM_NODE_HEIGHT = 120
const DIAGRAM_LEVEL_GAP = 90
const DIAGRAM_SIBLING_GAP = 32

const buildExpandedNodes = (node, path = ['root'], map = {}) => {
    if (!node) return map
    map[`node-${path.join('-')}`] = true
    node.children?.forEach((child, idx) => buildExpandedNodes(child, [...path, idx], map))
    return map
}

const buildDiagramLayout = (planTree, expandedNodes) => {
    if (!planTree) return null
    const nodes = []
    const edges = []
    const nodeMap = new Map()
    let nextX = 0
    let maxDepth = 0

    const walk = (node, path, depth) => {
        const nodeId = `node-${path.join('-')}`
        const hasChildren = node.children && node.children.length > 0
        const isExpanded = expandedNodes[nodeId] !== false
        const visibleChildren = hasChildren && isExpanded ? node.children : []
        maxDepth = Math.max(maxDepth, depth)

        let x
        if (!visibleChildren.length) {
            x = nextX + DIAGRAM_NODE_WIDTH / 2
            nextX += DIAGRAM_NODE_WIDTH + DIAGRAM_SIBLING_GAP
        } else {
            const childCenters = []
            visibleChildren.forEach((child, idx) => {
                const childLayout = walk(child, [...path, idx], depth + 1)
                childCenters.push(childLayout.x)
                edges.push({ from: nodeId, to: childLayout.id })
            })
            const sum = childCenters.reduce((acc, value) => acc + value, 0)
            x = sum / childCenters.length
        }

        const y = depth * (DIAGRAM_NODE_HEIGHT + DIAGRAM_LEVEL_GAP)
        const layoutNode = { id: nodeId, node, x, y, depth, hasChildren, isExpanded }
        nodes.push(layoutNode)
        nodeMap.set(nodeId, layoutNode)
        return layoutNode
    }

    walk(planTree, ['root'], 0)
    const width = Math.max(nextX, DIAGRAM_NODE_WIDTH) + DIAGRAM_SIBLING_GAP
    const height = (maxDepth + 1) * (DIAGRAM_NODE_HEIGHT + DIAGRAM_LEVEL_GAP) + DIAGRAM_LEVEL_GAP

    return { nodes, edges, width, height, nodeMap }
}

export default function ExplainPlanTab({ connectionId }) {
    // Initialize with cached state if available
    const getInitialState = () => {
        if (!connectionId) {
            return {
                query: 'SELECT * FROM users WHERE id = 1;',
                analysis: null,
                useAnalyze: false,
                expandedNodes: {}
            }
        }
        const cached = loadTabState(connectionId, 'explain-plan')
        return {
            query: cached?.query || 'SELECT * FROM users WHERE id = 1;',
            analysis: cached?.analysis || null,
            useAnalyze: cached?.useAnalyze || false,
            expandedNodes: cached?.expandedNodes || {}
        }
    }

    const initialState = getInitialState()

    const [query, setQuery] = useState(initialState.query)
    const [loading, setLoading] = useState(false)
    const [analysis, setAnalysis] = useState(initialState.analysis)
    const [error, setError] = useState(null)
    const [useAnalyze, setUseAnalyze] = useState(initialState.useAnalyze)
    const [copiedSql, setCopiedSql] = useState(null)
    const [expandedNodes, setExpandedNodes] = useState(initialState.expandedNodes)
    const [analysisHistory, setAnalysisHistory] = useState([])
    const [showHistory, setShowHistory] = useState(false)
    const [glossaryInsights, setGlossaryInsights] = useState(null)
    const [glossaryLoading, setGlossaryLoading] = useState(false)
    const [glossaryError, setGlossaryError] = useState(null)
    const [planTab, setPlanTab] = useState('diagram')
    const [planView, setPlanView] = useState('diagram')
    const [rawTab, setRawTab] = useState('text')
    const [diagramMetric, setDiagramMetric] = useState('time')
    const [bufferMetric, setBufferMetric] = useState('shared')
    const [selectedNodeId, setSelectedNodeId] = useState(null)
    const [diagramTransform, setDiagramTransform] = useState({ x: 0, y: 0, scale: 1 })
    const diagramViewportRef = useRef(null)
    const diagramDragRef = useRef(null)
    const [resultsTab, setResultsTab] = useState('analysis')
    const [showPlanDetails, setShowPlanDetails] = useState(false)

    // Load cached state and history when connection changes
    useEffect(() => {
        if (connectionId) {
            // Load cached state
            const cached = loadTabState(connectionId, 'explain-plan')
            if (cached) {
                if (cached.query) setQuery(cached.query)
                if (cached.analysis) setAnalysis(cached.analysis)
                if (cached.useAnalyze !== undefined) setUseAnalyze(cached.useAnalyze)
                if (cached.expandedNodes) setExpandedNodes(cached.expandedNodes)
            }
            fetchHistory()
        }
    }, [connectionId])

    // Save state to cache whenever important state changes
    useEffect(() => {
        if (connectionId) {
            const stateToSave = {
                query,
                analysis,
                useAnalyze,
                expandedNodes
            }
            saveTabState(connectionId, 'explain-plan', stateToSave)
        }
    }, [connectionId, query, analysis, useAnalyze, expandedNodes])

    const fetchHistory = async () => {
        if (!connectionId) return

        try {
            const histories = await explainAPI.getHistory(connectionId)
            // Convert backend format to frontend format
            const formattedHistories = histories.map(h => ({
                id: h.id,
                timestamp: h.timestamp,
                query: h.query,
                useAnalyze: h.useAnalyze,
                analysis: h.analysisData,
                performanceScore: h.performanceScore,
                issueCount: h.issueCount
            }))
            setAnalysisHistory(formattedHistories)
            console.log('📂 Loaded history from database:', formattedHistories.length, 'items')
        } catch (error) {
            console.error('Failed to load history from database:', error)
        }
    }

    const analyzeQuery = async () => {
        if (!query.trim()) {
            setError('Please enter a SQL query')
            return
        }

        if (!connectionId) {
            setError('Please select a database connection first')
            return
        }

        setLoading(true)
        setError(null)
        setAnalysis(null)

        try {
            const data = await explainAPI.analyzeQuery(connectionId, query.trim(), useAnalyze)
            console.log('✅ Analysis completed:', data)
            setAnalysis(data)
            console.log('✅ Analysis state set')
            setPlanTab(data.planTree ? 'diagram' : 'raw')
            setPlanView('diagram')
            setResultsTab('analysis')

            // Save to database
            try {
                await explainAPI.saveHistory({
                    connectionId,
                    query: query.trim(),
                    useAnalyze,
                    analysisData: data,
                    performanceScore: data.performanceScore,
                    issueCount: data.issues?.length || 0,
                    userId: null  // Optional: can add user context later
                })
                console.log('📜 Saved to database')
                // Refresh history from database
                await fetchHistory()
            } catch (historyError) {
                console.error('Error saving history:', historyError)
                // Don't fail the main analysis if history save fails
            }

            // Auto-expand root node
            if (data.planTree) {
                setExpandedNodes(buildExpandedNodes(data.planTree))
            }
        } catch (err) {
            setError(err.message)
        } finally {
            setLoading(false)
        }
    }

    const clearAnalysis = () => {
        console.log('🗑️ Clearing analysis')
        setAnalysis(null)
        setError(null)
        setExpandedNodes({})
        setShowPlanDetails(false)
        console.log('🗑️ Analysis cleared')
    }

    const loadFromHistory = (historyItem) => {
        console.log('📖 Loading from history:', historyItem.id)
        setAnalysis(historyItem.analysis)
        setQuery(historyItem.query)
        setUseAnalyze(historyItem.useAnalyze)
        setError(null)
        setShowHistory(false)
        setPlanTab(historyItem.analysis.planTree ? 'diagram' : 'raw')
        setPlanView('diagram')
        setResultsTab('analysis')
        setShowPlanDetails(false)

        // Auto-expand root node
        if (historyItem.analysis.planTree) {
            setExpandedNodes(buildExpandedNodes(historyItem.analysis.planTree))
        }
        console.log('📖 History item loaded')
    }

    const deleteFromHistory = async (id) => {
        try {
            await explainAPI.deleteHistory(id)
            console.log('🗑️ Deleted from database:', id)
            // Refresh history from database
            await fetchHistory()
        } catch (error) {
            console.error('Error deleting history:', error)
        }
    }

    const formatTimestamp = (timestamp) => {
        const date = new Date(timestamp)
        const now = new Date()
        const diffMs = now - date
        const diffMins = Math.floor(diffMs / 60000)

        if (diffMins < 1) return 'Just now'
        if (diffMins < 60) return `${diffMins}m ago`
        if (diffMins < 1440) return `${Math.floor(diffMins / 60)}h ago`
        return date.toLocaleDateString()
    }

    const copyToClipboard = async (text, id) => {
        try {
            await navigator.clipboard.writeText(text)
            setCopiedSql(id)
            setTimeout(() => setCopiedSql(null), 2000)
        } catch (err) {
            console.error('Failed to copy:', err)
        }
    }

    const getScoreColor = (score) => {
        if (score >= 90) return 'var(--color-success)'
        if (score >= 70) return 'var(--color-primary)'
        if (score >= 50) return 'var(--color-warning)'
        if (score >= 30) return 'var(--color-danger)'
        return 'var(--color-danger)'
    }

    const getSeverityColor = (severity) => {
        switch (severity) {
            case 'CRITICAL': return 'var(--color-danger)'
            case 'HIGH': return 'var(--color-danger)'
            case 'MEDIUM': return 'var(--color-warning)'
            case 'LOW': return 'var(--color-primary)'
            case 'INFO': return 'var(--color-light-6)'
            default: return 'var(--color-light-6)'
        }
    }

    const getSeverityIcon = (severity) => {
        switch (severity) {
            case 'CRITICAL':
            case 'HIGH':
                return <AlertCircle size={16} />
            case 'MEDIUM':
                return <AlertTriangle size={16} />
            case 'LOW':
            case 'INFO':
                return <Info size={16} />
            default:
                return <Info size={16} />
        }
    }

    const parseExplainAnalyze = (extra) => {
        if (!extra) return null

        const marker = 'explain analyze:'
        const lowerExtra = extra.toLowerCase()
        const markerIndex = lowerExtra.indexOf(marker)
        let text = markerIndex >= 0 ? extra.slice(markerIndex + marker.length) : extra
        text = text.trim()
        if (!text) return null

        const rawSteps = []
        if (text.includes('\n')) {
            const lines = text.split('\n')
            for (const line of lines) {
                const trimmed = line.trim()
                if (!trimmed) continue
                if (trimmed.includes('->')) {
                    const parts = trimmed.split('->').map((part) => part.trim()).filter(Boolean)
                    rawSteps.push(...parts)
                } else {
                    rawSteps.push(trimmed)
                }
            }
        } else if (text.includes('->')) {
            const parts = text.split('->').map((part) => part.trim()).filter(Boolean)
            rawSteps.push(...parts)
        } else {
            rawSteps.push(text)
        }

        let cleanedSteps = rawSteps.map((step) => {
            return step
                .replace(/\s*\(cost=[^)]+\)/gi, '')
                .replace(/\s*\(actual[^)]+\)/gi, '')
                .replace(/\s*\(never executed\)/gi, '')
                .replace(/\s+/g, ' ')
                .trim()
        }).filter(Boolean)

        if (!cleanedSteps.length && text) {
            cleanedSteps = [text.replace(/\s+/g, ' ').trim()]
        }

        const joinTypes = new Set()
        const tableScans = new Set()
        const indexLookups = new Set()
        let sortKeys = null
        let hasTempTable = false
        let actualRowsZero = false

        for (const raw of rawSteps) {
            const lower = raw.toLowerCase()
            if (lower.startsWith('sort:')) {
                sortKeys = raw.slice(5).trim()
            }
            if (lower.includes('temporary table') || lower.includes('<temporary>')) {
                hasTempTable = true
            }
            if (lower.includes('nested loop')) {
                joinTypes.add('Nested Loop')
            }
            if (lower.includes('hash join')) {
                joinTypes.add('Hash Join')
            }
            if (lower.includes('left join')) {
                joinTypes.add('Left Join')
            }
            if (lower.includes('inner join')) {
                joinTypes.add('Inner Join')
            }

            const tableScanMatch = raw.match(/table scan on\s+([^\s(]+)/i)
            if (tableScanMatch?.[1]) {
                tableScans.add(tableScanMatch[1])
            }

            const indexLookupMatch = raw.match(/index lookup on\s+([^\s(]+)/i)
            if (indexLookupMatch?.[1]) {
                indexLookups.add(indexLookupMatch[1])
            }

            if (/actual time=.*rows=0\b/i.test(raw)) {
                actualRowsZero = true
            }
        }

        return {
            steps: cleanedSteps,
            rawSteps,
            sortKeys,
            hasTempTable,
            joinTypes,
            tableScans,
            indexLookups,
            actualRowsZero
        }
    }

    const collectPlanStats = (node, stats) => {
        if (!node) return stats
        const current = stats || {
            tableScans: new Set(),
            indexLookups: new Set(),
            joinTypes: new Set()
        }

        const tableName = node.tableName
        const accessType = node.accessType ? node.accessType.toUpperCase() : null
        if (tableName && accessType) {
            if (accessType === 'ALL') {
                current.tableScans.add(tableName)
            } else {
                current.indexLookups.add(tableName)
            }
        } else if (tableName && node.key && node.key !== 'NULL') {
            current.indexLookups.add(tableName)
        }

        const nodeType = node.nodeType ? node.nodeType.toLowerCase() : ''
        if (nodeType.includes('nested loop')) {
            current.joinTypes.add('Nested Loop')
        } else if (nodeType.includes('hash')) {
            current.joinTypes.add('Hash Join')
        } else if (nodeType.includes('join')) {
            current.joinTypes.add(node.nodeType)
        }
        if (node.joinType) {
            current.joinTypes.add(node.joinType)
        }

        if (node.children?.length) {
            node.children.forEach((child) => collectPlanStats(child, current))
        }

        return current
    }

    const buildStepsFromTree = (node) => {
        const steps = []
        const walk = (current, depth = 0) => {
            if (!current) return
            const label = current.nodeType || current.selectType || 'Plan step'
            const tableLabel = current.tableName ? ` on ${current.tableName}` : ''
            const accessLabel = current.accessType ? ` (access: ${current.accessType})` : ''
            steps.push(`${'  '.repeat(depth)}${label}${tableLabel}${accessLabel}`)

            if (current.filter) {
                steps.push(`${'  '.repeat(depth + 1)}Filter: ${current.filter}`)
            }
            if (current.extra) {
                steps.push(`${'  '.repeat(depth + 1)}Notes: ${current.extra}`)
            }

            current.children?.forEach((child) => walk(child, depth + 1))
        }
        walk(node, 0)
        return steps
    }

    const buildStepsFromExtra = (extra) => {
        if (!extra) return []
        const lines = extra.split('\n').map((line) => line.trim()).filter(Boolean)
        if (!lines.length) return []
        return lines.map((line) => line.replace(/\s+/g, ' ').trim())
    }

    const getRawExplainLines = (extra) => {
        if (!extra) return []
        return extra
            .split('\n')
            .map((line) => line.replace(/\s+$/, ''))
            .filter((line) => line.trim().length > 0)
    }

    const isExplainAnalyzeExtra = (extra) => {
        if (!extra) return false
        return extra.toLowerCase().includes('explain analyze:')
    }

    const detectTermsFromExtra = (extra) => {
        if (!extra) return []
        const terms = new Set()
        const lower = extra.toLowerCase()

        if (lower.includes('sort')) terms.add('Sort')
        if (lower.includes('temporary table')) terms.add('Temporary table')
        if (lower.includes('deduplication')) terms.add('Deduplication')
        if (lower.includes('table scan')) terms.add('Table scan')
        if (lower.includes('index lookup')) terms.add('Index lookup')
        if (lower.includes('nested loop')) terms.add('Nested loop join')
        if (lower.includes('hash join')) terms.add('Hash join')
        if (lower.includes('left join')) terms.add('Left join')
        if (lower.includes('inner join')) terms.add('Inner join')
        if (lower.includes('filter')) terms.add('Filter')
        if (lower.includes('seq scan')) terms.add('Seq scan')
        if (lower.includes('index scan')) terms.add('Index scan')
        if (lower.includes('bitmap')) terms.add('Bitmap scan')

        return Array.from(terms)
    }

    const describeStep = (step) => {
        const trimmed = step.trim()
        const lower = trimmed.toLowerCase()

        if (lower.startsWith('sort:')) {
            return `Sort by ${trimmed.slice(5).trim()}`
        }
        if (lower.startsWith('table scan on <temporary>')) {
            return 'Read from a temporary result table'
        }
        if (lower.startsWith('table scan on ')) {
            return `Full scan of ${trimmed.slice(14).trim()}`
        }
        if (lower.startsWith('temporary table with deduplication')) {
            return 'Create a temporary table and remove duplicates'
        }
        if (lower.startsWith('left hash join')) {
            return 'Left hash join (build hash table, then match)'
        }
        if (lower.startsWith('hash join')) {
            return 'Hash join (build hash table, then match)'
        }
        if (lower.startsWith('nested loop')) {
            return 'Nested loop join (row-by-row matching)'
        }
        if (lower.startsWith('filter:')) {
            return `Filter rows where ${trimmed.slice(7).trim()}`
        }
        if (lower.startsWith('single-row index lookup on ')) {
            return `Index lookup on ${trimmed.slice(27).trim()} (single row)`
        }
        if (lower.startsWith('index lookup on ')) {
            return `Index lookup on ${trimmed.slice(16).trim()}`
        }

        return trimmed
    }

    const flattenPlanNodes = (node, depth = 0, list = []) => {
        if (!node) return list
        list.push({ node, depth })
        node.children?.forEach((child) => flattenPlanNodes(child, depth + 1, list))
        return list
    }

    const flattenPlanNodesWithPath = (node, path = ['root'], depth = 0, list = []) => {
        if (!node) return list
        const nodeId = `node-${path.join('-')}`
        const parentId = path.length > 1 ? `node-${path.slice(0, -1).join('-')}` : null
        list.push({ id: nodeId, node, depth, parentId })
        node.children?.forEach((child, idx) => flattenPlanNodesWithPath(child, [...path, idx], depth + 1, list))
        return list
    }

    const getNumericValue = (value) => {
        if (value === null || value === undefined) return null
        const num = Number(value)
        return Number.isFinite(num) ? num : null
    }

    const getBufferValue = (node, type) => {
        const info = node?.additionalInfo || {}
        const keysByType = {
            shared: ['Shared Hit Blocks', 'Shared Read Blocks', 'Shared Dirtied Blocks', 'Shared Written Blocks'],
            local: ['Local Hit Blocks', 'Local Read Blocks', 'Local Dirtied Blocks', 'Local Written Blocks'],
            temp: ['Temp Read Blocks', 'Temp Written Blocks']
        }
        const keys = keysByType[type] || []
        return keys.reduce((sum, key) => {
            const value = getNumericValue(info[key])
            return sum + (value || 0)
        }, 0)
    }

    const getIoValue = (node) => {
        const info = node?.additionalInfo || {}
        return Object.entries(info).reduce((sum, [key, value]) => {
            const lower = key.toLowerCase()
            if (lower.includes('read time') || lower.includes('write time')) {
                return sum + (getNumericValue(value) || 0)
            }
            return sum
        }, 0)
    }

    const getMetricValue = (node, metric, bufferType) => {
        if (!node) return null
        switch (metric) {
            case 'time':
                return getNumericValue(node.actualTotalTime)
            case 'rows':
                return getNumericValue(node.actualRows ?? node.planRows)
            case 'cost':
                return getNumericValue(node.totalCost)
            case 'estimation': {
                const actual = getNumericValue(node.actualRows)
                const estimated = getNumericValue(node.planRows)
                if (!actual || !estimated || estimated === 0) return null
                return actual / estimated
            }
            case 'buffers':
                return getBufferValue(node, bufferType)
            case 'io':
                return getIoValue(node)
            default:
                return null
        }
    }

    const formatMetricValue = (metric, value) => {
        if (value == null) return 'N/A'
        if (metric === 'time') return `${value.toFixed(2)} ms`
        if (metric === 'rows') return Math.round(value).toLocaleString()
        if (metric === 'cost') return value.toFixed(2)
        if (metric === 'estimation') return `${value.toFixed(1)}x`
        if (metric === 'buffers') return Math.round(value).toLocaleString()
        if (metric === 'io') return `${value.toFixed(2)} ms`
        return `${value}`
    }

    const getJoinOrder = (node) => {
        const order = []
        const seen = new Set()
        const walk = (current) => {
            if (!current) return
            if (current.tableName) {
                const key = current.tableName.toLowerCase()
                if (!seen.has(key)) {
                    seen.add(key)
                    order.push(current.tableName)
                }
            }
            current.children?.forEach(walk)
        }
        walk(node)
        return order
    }

    const getScanTypeLabel = (node) => {
        if (!node) return null
        if (node.accessType) {
            return `MySQL ${node.accessType.toUpperCase()}`
        }
        if (node.nodeType) {
            if (node.nodeType.toLowerCase().includes('scan')) {
                return node.nodeType
            }
            if (node.nodeType.toLowerCase().includes('index')) {
                return node.nodeType
            }
        }
        return null
    }

    const getScanSummary = (nodes) => {
        const counts = new Map()
        nodes.forEach(({ node }) => {
            const label = getScanTypeLabel(node)
            if (!label) return
            counts.set(label, (counts.get(label) || 0) + 1)
        })
        return Array.from(counts.entries()).map(([label, count]) => `${label} x${count}`)
    }

    const getTopCostDrivers = (nodes, limit = 3) => {
        const scored = nodes
            .map(({ node }) => {
                const weight = node.actualTotalTime ?? node.totalCost ?? 0
                return { node, weight }
            })
            .filter((entry) => entry.weight > 0)
            .sort((a, b) => b.weight - a.weight)
            .slice(0, limit)
        return scored.map(({ node, weight }) => {
            const label = node.nodeType || 'Plan node'
            const tableLabel = node.tableName ? ` on ${node.tableName}` : ''
            const metric = node.actualTotalTime != null
                ? `${weight.toFixed(2)} ms`
                : `cost ${weight.toFixed(2)}`
            return `${label}${tableLabel} (${metric})`
        })
    }

    const getMisestimateSummary = (nodes) => {
        const misestimates = nodes
            .map(({ node }) => {
                const estimated = node.planRows
                const actual = node.actualRows
                if (!estimated || !actual || estimated === 0) return null
                const factor = actual / estimated
                const variance = Math.abs(factor - 1) * 100
                return { node, factor, variance }
            })
            .filter(Boolean)
            .filter((entry) => entry.variance >= 50)
            .sort((a, b) => b.variance - a.variance)
        if (!misestimates.length) return null
        const top = misestimates[0]
        const label = top.node.tableName ? `${top.node.tableName}` : 'plan node'
        return `Row estimate off by ${top.factor.toFixed(1)}x on ${label}`
    }

    const buildPlanGuide = (planTree, analysisData) => {
        const extraText = planTree?.extra || ''
        const planText = analysisData?.planText || ''
        const isJsonPlanText = planText.trim().startsWith('[') && planText.includes('"Plan"')
        const explainSource = isJsonPlanText ? extraText : (planText || extraText)
        const analyze = parseExplainAnalyze(explainSource)
        const stats = collectPlanStats(planTree)
        const nodes = flattenPlanNodes(planTree)
        const joinOrder = getJoinOrder(planTree)
        const scanSummary = getScanSummary(nodes)
        const topCostDrivers = getTopCostDrivers(nodes)
        const misestimateSummary = getMisestimateSummary(nodes)

        const joinTypes = new Set([
            ...(stats?.joinTypes ? Array.from(stats.joinTypes) : []),
            ...(analyze?.joinTypes ? Array.from(analyze.joinTypes) : [])
        ])
        const tableScans = new Set([
            ...(stats?.tableScans ? Array.from(stats.tableScans) : []),
            ...(analyze?.tableScans ? Array.from(analyze.tableScans) : [])
        ])
        const indexLookups = new Set([
            ...(stats?.indexLookups ? Array.from(stats.indexLookups) : []),
            ...(analyze?.indexLookups ? Array.from(analyze.indexLookups) : [])
        ])

        const glanceItems = []
        if (joinOrder.length) {
            glanceItems.push(`Join order: ${joinOrder.join(' → ')}`)
        }
        if (scanSummary.length) {
            glanceItems.push(`Scan types: ${scanSummary.join(', ')}`)
        }
        if (analyze?.sortKeys) {
            glanceItems.push(`Sort by ${analyze.sortKeys}`)
        }
        if (analyze?.hasTempTable) {
            glanceItems.push('Uses a temporary table (often for deduplication or sorting)')
        }
        if (joinTypes.size) {
            glanceItems.push(`Joins used: ${Array.from(joinTypes).join(', ')}`)
        }
        if (tableScans.size) {
            glanceItems.push(`Full table scan on: ${Array.from(tableScans).join(', ')}`)
        }
        if (indexLookups.size) {
            glanceItems.push(`Index lookups on: ${Array.from(indexLookups).join(', ')}`)
        }
        if (analyze?.actualRowsZero) {
            glanceItems.push('This run returned 0 rows; the plan can look different with real data')
        }
        if (misestimateSummary) {
            glanceItems.push(misestimateSummary)
        }
        if (topCostDrivers.length) {
            glanceItems.push(`Top cost drivers: ${topCostDrivers.join(', ')}`)
        }

        const rawSteps = analyze?.steps?.length
            ? analyze.steps
            : explainSource
                ? buildStepsFromExtra(explainSource)
                : buildStepsFromTree(planTree)
        const stepsText = rawSteps.length
            ? rawSteps.map((step, idx) => `${idx + 1}. ${analyze ? describeStep(step) : step}`).join('\n')
            : 'No step details available.'

        const glossary = [
            'Table scan: reads every row in a table.',
            'Index lookup: uses an index to find matching rows quickly.',
            'Nested loop join: matches rows one-by-one using an index.',
            'Hash join: builds a hash table, then matches rows.',
            'Merge join: sorts both sides and merges matching rows.',
            'Seq scan: sequential scan of a table in PostgreSQL.',
            'Index scan: PostgreSQL uses an index to find rows.',
            'Bitmap scan: PostgreSQL uses a bitmap to find matching rows.',
            'Temporary table: intermediate results stored on disk or memory.',
            'Sort: orders rows, often needs extra memory.'
        ]

        const glossaryTerms = []
        if (analyze?.sortKeys) glossaryTerms.push('Sort')
        if (analyze?.hasTempTable) glossaryTerms.push('Temporary table')
        if (tableScans.size) glossaryTerms.push('Table scan')
        if (indexLookups.size) glossaryTerms.push('Index lookup')
        if (joinTypes.has('Nested Loop')) glossaryTerms.push('Nested loop join')
        if (joinTypes.has('Hash Join')) glossaryTerms.push('Hash join')
        if (rawSteps.some((step) => step.toLowerCase().startsWith('filter:'))) glossaryTerms.push('Filter')
        if (rawSteps.some((step) => step.toLowerCase().includes('deduplication'))) glossaryTerms.push('Deduplication')

        const uniqueTerms = Array.from(new Set([
            ...glossaryTerms,
            ...detectTermsFromExtra(extraText),
            ...nodes
                .map(({ node }) => node.nodeType)
                .filter(Boolean)
                .map((value) => value.replace(/scan/gi, 'scan'))
        ]))

        if (!glanceItems.length && extraText) {
            const fallbackTerms = detectTermsFromExtra(extraText)
            if (fallbackTerms.includes('Sort')) glanceItems.push('Sort step detected')
            if (fallbackTerms.includes('Temporary table')) {
                glanceItems.push('Uses a temporary table (often for deduplication or sorting)')
            }
            if (fallbackTerms.includes('Table scan')) {
                glanceItems.push('Plan includes at least one full table scan')
            }
            if (fallbackTerms.includes('Index lookup')) {
                glanceItems.push('Plan includes index lookups')
            }
            if (fallbackTerms.some((term) => term.toLowerCase().includes('join'))) {
                glanceItems.push(`Joins used: ${fallbackTerms.filter((term) => term.toLowerCase().includes('join')).join(', ')}`)
            }
            if (/rows=0\b/i.test(extraText)) {
                glanceItems.push('This run returned 0 rows; the plan can look different with real data')
            }
        }

        if (!uniqueTerms.length) {
            uniqueTerms.push(...glossary.map((item) => item.split(':')[0].trim()))
        }

        const rawExplainLines = explainSource
            ? getRawExplainLines(explainSource)
            : getRawExplainLines(extraText)

        return {
            glanceItems,
            stepsText,
            glossary,
            glossaryTerms: uniqueTerms,
            rawExplainLines,
            joinOrder,
            scanSummary,
            topCostDrivers,
            misestimateSummary
        }
    }

    const planGuide = useMemo(() => {
        if (!analysis?.planTree) return null
        return buildPlanGuide(analysis.planTree, analysis)
    }, [analysis])

    const hasPlanTree = Boolean(analysis?.planTree)

    const parsedPlanJson = useMemo(() => {
        if (!analysis?.planJson) return null
        try {
            return JSON.parse(analysis.planJson)
        } catch (err) {
            return null
        }
    }, [analysis?.planJson])

    const planJsonRoot = useMemo(() => {
        if (!parsedPlanJson) return null
        return Array.isArray(parsedPlanJson) ? parsedPlanJson[0] : parsedPlanJson
    }, [parsedPlanJson])

    const flatNodes = useMemo(() => {
        if (!analysis?.planTree) return []
        return flattenPlanNodesWithPath(analysis.planTree)
    }, [analysis])

    const nodeMap = useMemo(() => {
        const map = new Map()
        flatNodes.forEach((item) => {
            map.set(item.id, item.node)
        })
        return map
    }, [flatNodes])

    const metricAvailability = useMemo(() => {
        const availability = {
            time: false,
            rows: false,
            cost: false,
            estimation: false,
            buffers: false,
            io: false
        }
        flatNodes.forEach(({ node }) => {
            const timeValue = getMetricValue(node, 'time')
            const rowsValue = getMetricValue(node, 'rows')
            const costValue = getMetricValue(node, 'cost')
            const estimateValue = getMetricValue(node, 'estimation')
            const buffersValue = getMetricValue(node, 'buffers', bufferMetric)
            const ioValue = getMetricValue(node, 'io')
            if (timeValue !== null && timeValue !== undefined) availability.time = true
            if (rowsValue !== null && rowsValue !== undefined) availability.rows = true
            if (costValue !== null && costValue !== undefined) availability.cost = true
            if (estimateValue !== null && estimateValue !== undefined) availability.estimation = true
            if (buffersValue > 0) availability.buffers = true
            if (ioValue > 0) availability.io = true
        })
        return availability
    }, [flatNodes, bufferMetric])

    const metricMaximums = useMemo(() => {
        const maximums = {
            time: 0,
            rows: 0,
            cost: 0,
            estimation: 0,
            buffers: 0,
            io: 0
        }
        flatNodes.forEach(({ node }) => {
            maximums.time = Math.max(maximums.time, getMetricValue(node, 'time') || 0)
            maximums.rows = Math.max(maximums.rows, getMetricValue(node, 'rows') || 0)
            maximums.cost = Math.max(maximums.cost, getMetricValue(node, 'cost') || 0)
            maximums.estimation = Math.max(maximums.estimation, getMetricValue(node, 'estimation') || 0)
            maximums.buffers = Math.max(maximums.buffers, getMetricValue(node, 'buffers', bufferMetric) || 0)
            maximums.io = Math.max(maximums.io, getMetricValue(node, 'io') || 0)
        })
        return maximums
    }, [flatNodes, bufferMetric])

    const selectedNode = useMemo(() => {
        if (!selectedNodeId) return null
        return nodeMap.get(selectedNodeId) || null
    }, [selectedNodeId, nodeMap])

    useEffect(() => {
        if (!analysis?.planTree) return
        if (metricAvailability[diagramMetric]) return
        const fallback = metricAvailability.time
            ? 'time'
            : metricAvailability.rows
                ? 'rows'
                : metricAvailability.cost
                    ? 'cost'
                    : metricAvailability.estimation
                        ? 'estimation'
                        : diagramMetric
        if (fallback !== diagramMetric) {
            setDiagramMetric(fallback)
        }
    }, [analysis, metricAvailability, diagramMetric])

    useEffect(() => {
        setSelectedNodeId(null)
        setDiagramTransform({ x: 0, y: 0, scale: 1 })
        setShowPlanDetails(false)
    }, [analysis?.planTree])

    useEffect(() => {
        if (planTab !== 'raw') return
        if (rawTab === 'text' && !analysis?.planText && analysis?.planJson) {
            setRawTab('json')
        }
        if (rawTab === 'json' && !analysis?.planJson && (analysis?.planText || planGuide?.rawExplainLines?.length)) {
            setRawTab('text')
        }
    }, [planTab, rawTab, analysis, planGuide])

    const diagramLayout = useMemo(() => {
        if (!analysis?.planTree) return null
        return buildDiagramLayout(analysis.planTree, expandedNodes)
    }, [analysis, expandedNodes])

    const indexRecommendations = analysis?.indexRecommendations || []
    const indexRecommendationsByTable = useMemo(() => {
        const map = new Map()
        indexRecommendations.forEach((rec) => {
            if (!rec?.tableName) return
            const key = rec.tableName.toLowerCase()
            if (!map.has(key)) {
                map.set(key, [])
            }
            map.get(key).push(rec)
        })
        return map
    }, [indexRecommendations])

    const planDrift = useMemo(() => {
        if (!analysis?.planSignature || !analysis?.normalizedQuery) return null
        const matching = analysisHistory
            .filter((item) => item.analysis?.planSignature && item.analysis?.normalizedQuery === analysis.normalizedQuery)
            .sort((a, b) => new Date(b.timestamp) - new Date(a.timestamp))
        if (!matching.length) return null
        const signatures = new Set(matching.map((item) => item.analysis.planSignature))
        const signatureCount = signatures.size
        let lastChange = null
        for (let i = 1; i < matching.length; i += 1) {
            if (matching[i].analysis.planSignature !== matching[i - 1].analysis.planSignature) {
                lastChange = matching[i - 1]
                break
            }
        }
        return {
            signatureCount,
            hasDrift: signatureCount > 1,
            lastChangeAt: lastChange?.timestamp || null,
            latestSignature: matching[0].analysis.planSignature
        }
    }, [analysis, analysisHistory])

    const emptyPlanState = (
        <div className={styles.emptyPlan}>
            <Info size={32} />
            <p>No execution plan available</p>
        </div>
    )

    const extractJsonBlock = (text) => {
        if (!text) return null
        const startIndex = text.indexOf('[')
        const endIndex = text.lastIndexOf(']')
        if (startIndex >= 0 && endIndex > startIndex) {
            return text.slice(startIndex, endIndex + 1)
        }
        return null
    }

    useEffect(() => {
        let isActive = true
        const loadGlossaryInsights = async () => {
            if (!connectionId || !planGuide?.glossaryTerms?.length) {
                setGlossaryInsights(null)
                setGlossaryError(null)
                return
            }

            setGlossaryLoading(true)
            setGlossaryError(null)

            const terms = planGuide.glossaryTerms?.length
                ? planGuide.glossaryTerms
                : planGuide.glossary.map((item) => item.split(':')[0].trim())
            const prompt = [
                'You are helping a novice DBA understand an execution plan.',
                'Explain each term in simple language, and mention why it matters.',
                'Use the database context if useful, and keep it concise.',
                'Return JSON array with: term, meaning, why_it_matters.'
            ].join(' ')

            const message = `${prompt}\n\nTerms: ${terms.join(', ')}`

            try {
                const response = await chatAPI.sendMessage(connectionId, message)
                if (!isActive) return
                const rawMessage = response?.message || ''
                const jsonBlock = extractJsonBlock(rawMessage)
                if (jsonBlock) {
                    const parsed = JSON.parse(jsonBlock)
                    setGlossaryInsights(Array.isArray(parsed) ? parsed : null)
                } else {
                    setGlossaryInsights([{ term: 'AI Summary', meaning: rawMessage, why_it_matters: '' }])
                }
            } catch (err) {
                if (!isActive) return
                setGlossaryError(err.message || 'Failed to load AI glossary insights')
                setGlossaryInsights(null)
            } finally {
                if (isActive) {
                    setGlossaryLoading(false)
                }
            }
        }

        loadGlossaryInsights()

        return () => {
            isActive = false
        }
    }, [connectionId, planGuide])

    const getNodeBadges = (node, hasIndexRec) => {
        const badges = []
        if (node.fullTableScan || node.accessType === 'ALL' || (node.nodeType || '').toLowerCase().includes('seq scan')) {
            badges.push('Full scan')
        }
        if (node.usingFilesort) {
            badges.push('Filesort')
        }
        if (node.usingTemporary) {
            badges.push('Temp table')
        }
        if (node.actualLoops && node.actualLoops > 1) {
            badges.push(`Loops x${node.actualLoops}`)
        }
        if (node.isSlowNode) {
            badges.push('Critical path')
        }
        if (hasIndexRec) {
            badges.push('Index rec')
        }
        return badges
    }

    const buildNodeTooltip = (node, misestimateFactor) => {
        return [
            node.nodeType ? `Node: ${node.nodeType}` : null,
            node.tableName ? `Table: ${node.tableName}` : null,
            node.accessType ? `Access: ${node.accessType}` : null,
            node.joinType ? `Join: ${node.joinType}` : null,
            node.filter ? `Filter: ${node.filter}` : null,
            node.planRows != null ? `Estimated rows: ${node.planRows}` : null,
            node.actualRows != null ? `Actual rows: ${node.actualRows}` : null,
            misestimateFactor ? `Row estimate factor: ${misestimateFactor.toFixed(2)}x` : null,
            node.actualTotalTime != null ? `Actual time: ${node.actualTotalTime.toFixed(2)} ms` : null,
            node.totalCost != null ? `Cost: ${node.totalCost.toFixed(2)}` : null,
            node.additionalInfo ? `Buffers: ${JSON.stringify(node.additionalInfo)}` : null
        ].filter(Boolean).join('\n')
    }

    const toggleNode = (nodeId) => {
        setExpandedNodes(prev => ({
            ...prev,
            [nodeId]: !prev[nodeId]
        }))
    }

    const renderPlanNode = (node, path = ['root']) => {
        const nodeId = `node-${path.join('-')}`
        const hasChildren = node.children && node.children.length > 0
        const isExpanded = expandedNodes[nodeId]
        const isRawExplain = isExplainAnalyzeExtra(node.extra)
        const displayTitle = node.nodeType || node.selectType || (isRawExplain ? 'EXPLAIN ANALYZE output' : 'Plan step')
        const isChild = path.length > 1
        const estimatedRows = node.planRows ?? null
        const actualRows = node.actualRows ?? null
        const misestimateFactor = estimatedRows && actualRows && estimatedRows > 0
            ? actualRows / estimatedRows
            : null
        const misestimateBadge = misestimateFactor && (misestimateFactor >= 2 || misestimateFactor <= 0.5)
            ? `${misestimateFactor.toFixed(1)}x`
            : null
        const hasIndexRec = node.tableName && indexRecommendationsByTable.has(node.tableName.toLowerCase())
        const highlightBadges = getNodeBadges(node, hasIndexRec)
        const tooltip = buildNodeTooltip(node, misestimateFactor)

        const isSelected = selectedNodeId === nodeId

        return (
            <div
                key={nodeId}
                className={`${styles.planNode} ${node.isSlowNode ? styles.criticalNode : ''} ${isSelected ? styles.planNodeSelected : ''}`}
                title={tooltip || undefined}
            >
                <div
                    className={`${styles.nodeHeader} ${isChild ? styles.nodeHeaderChild : ''}`}
                    onClick={() => {
                        setSelectedNodeId(nodeId)
                        setShowPlanDetails(true)
                    }}
                >
                    {hasChildren && (
                        <button
                            className={styles.expandButton}
                            onClick={() => toggleNode(nodeId)}
                        >
                            {isExpanded ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
                        </button>
                    )}
                    {!hasChildren && <div className={styles.expandPlaceholder} />}

                    <div className={styles.nodeContent}>
                        <div className={styles.nodeTitle}>
                            {displayTitle}
                            {node.tableName && <span className={styles.tableName}> on {node.tableName}</span>}
                        </div>

                        <div className={styles.nodeMetrics}>
                            {node.accessType && (
                                <span className={`${styles.metric} ${node.accessType === 'ALL' ? styles.warning : ''}`}>
                                    Access: {node.accessType}
                                </span>
                            )}
                            {node.joinType && (
                                <span className={styles.metric}>Join: {node.joinType}</span>
                            )}
                            {node.parentRelationship && (
                                <span className={styles.metric}>Role: {node.parentRelationship}</span>
                            )}
                            {node.key && (
                                <span className={styles.metric}>Index: {node.key}</span>
                            )}
                            {node.planRows !== null && node.planRows !== undefined && (
                                <span className={styles.metric}>Rows: {node.planRows.toLocaleString()}</span>
                            )}
                            {node.totalCost !== null && node.totalCost !== undefined && (
                                <span className={styles.metric}>Cost: {node.totalCost.toFixed(2)}</span>
                            )}
                            {node.actualTotalTime !== null && node.actualTotalTime !== undefined && (
                                <span className={styles.metric}>Time: {node.actualTotalTime.toFixed(2)} ms</span>
                            )}
                            {node.actualRows !== null && node.actualRows !== undefined && (
                                <span className={styles.metric}>Actual: {node.actualRows.toLocaleString()}</span>
                            )}
                            {node.actualLoops !== null && node.actualLoops !== undefined && (
                                <span className={styles.metric}>Loops: {node.actualLoops}</span>
                            )}
                            {misestimateBadge && (
                                <span className={`${styles.metric} ${styles.misestimateBadge}`}>Misestimate {misestimateBadge}</span>
                            )}
                        </div>

                        {highlightBadges.length > 0 && (
                            <div className={styles.nodeBadges}>
                                {highlightBadges.map((badge) => (
                                    <span key={`${nodeId}-${badge}`} className={styles.nodeBadge}>{badge}</span>
                                ))}
                            </div>
                        )}

                        {node.extra && !isRawExplain && (
                            <div className={styles.nodeExtra}>{node.extra}</div>
                        )}
                        {isRawExplain && (
                            <div className={styles.nodeExtra}>Raw EXPLAIN ANALYZE output is shown below.</div>
                        )}
                        {node.filter && (
                            <div className={styles.nodeExtra}>Filter: {node.filter}</div>
                        )}
                    </div>
                </div>

                {isExpanded && hasChildren && (
                    <div className={styles.nodeChildren}>
                        {node.children.map((child, idx) => renderPlanNode(child, [...path, idx]))}
                    </div>
                )}
            </div>
        )
    }

    const renderDiagramNode = (layoutNode) => {
        const { node, x, y, id, hasChildren, isExpanded } = layoutNode
        const estimatedRows = node.planRows ?? null
        const actualRows = node.actualRows ?? null
        const misestimateFactor = estimatedRows && actualRows && estimatedRows > 0
            ? actualRows / estimatedRows
            : null
        const misestimateBadge = misestimateFactor && (misestimateFactor >= 2 || misestimateFactor <= 0.5)
            ? `${misestimateFactor.toFixed(1)}x`
            : null
        const hasIndexRec = node.tableName && indexRecommendationsByTable.has(node.tableName.toLowerCase())
        const highlightBadges = getNodeBadges(node, hasIndexRec)
        const tooltip = buildNodeTooltip(node, misestimateFactor)
        const displayTitle = node.nodeType || node.selectType || 'Plan step'

        const metrics = []
        if (node.joinType) metrics.push(`Join ${node.joinType}`)
        if (node.accessType) metrics.push(`Access ${node.accessType}`)
        if (node.planRows != null) metrics.push(`Rows ${node.planRows.toLocaleString()}`)
        if (node.actualTotalTime != null) {
            metrics.push(`Time ${node.actualTotalTime.toFixed(1)} ms`)
        } else if (node.totalCost != null) {
            metrics.push(`Cost ${node.totalCost.toFixed(1)}`)
        }

        const metricItems = metrics.slice(0, 3)
        if (misestimateBadge) {
            metricItems.push(`Misestimate ${misestimateBadge}`)
        }

        const metricValue = getMetricValue(node, diagramMetric, bufferMetric)
        const metricMax = metricMaximums[diagramMetric] || 0
        const metricPercent = metricValue != null && metricMax > 0
            ? Math.min(100, (metricValue / metricMax) * 100)
            : 0

        return (
            <div
                key={id}
                className={`${styles.diagramNode} ${node.isSlowNode ? styles.diagramCriticalNode : ''} ${selectedNodeId === id ? styles.diagramNodeSelected : ''}`}
                style={{
                    left: `${x - DIAGRAM_NODE_WIDTH / 2}px`,
                    top: `${y}px`,
                    width: `${DIAGRAM_NODE_WIDTH}px`,
                    height: `${DIAGRAM_NODE_HEIGHT}px`
                }}
                title={tooltip || undefined}
                data-diagram-node
                onClick={() => {
                    setSelectedNodeId(id)
                    setShowPlanDetails(true)
                }}
            >
                <div className={styles.diagramNodeHeader}>
                    <div className={styles.diagramNodeTitle}>{displayTitle}</div>
                    {hasChildren && (
                        <button
                            className={styles.diagramToggle}
                            onClick={() => toggleNode(id)}
                            title={isExpanded ? 'Collapse' : 'Expand'}
                        >
                            {isExpanded ? <ChevronDown size={12} /> : <ChevronRight size={12} />}
                        </button>
                    )}
                </div>
                {node.tableName && (
                    <div className={styles.diagramNodeTable}>on {node.tableName}</div>
                )}
                <div className={styles.diagramMetrics}>
                    {metricItems.map((metric) => (
                        <span key={`${id}-${metric}`} className={styles.diagramMetric}>{metric}</span>
                    ))}
                </div>
                {highlightBadges.length > 0 && (
                    <div className={styles.diagramBadges}>
                        {highlightBadges.slice(0, 3).map((badge) => (
                            <span key={`${id}-${badge}`} className={styles.diagramBadge}>{badge}</span>
                        ))}
                    </div>
                )}
                {diagramMetric && metricMax > 0 && (
                    <div className={styles.diagramMetricBar}>
                        <div className={styles.diagramMetricFill} style={{ width: `${metricPercent}%` }} />
                        <span className={styles.diagramMetricLabel}>
                            {formatMetricValue(diagramMetric, metricValue)}
                        </span>
                    </div>
                )}
            </div>
        )
    }

    const metricOptions = [
        { key: 'time', label: 'Time' },
        { key: 'rows', label: 'Rows' },
        { key: 'estimation', label: 'Estimation' },
        { key: 'cost', label: 'Cost' },
        { key: 'buffers', label: 'Buffers' },
        { key: 'io', label: 'IO' }
    ]

    const getMetricLabel = (metricKey) => {
        const match = metricOptions.find((option) => option.key === metricKey)
        return match ? match.label : metricKey
    }

    const renderMetricControls = () => (
        <div className={styles.metricControls}>
            {metricOptions.map((option) => (
                <button
                    key={option.key}
                    className={`${styles.metricButton} ${diagramMetric === option.key ? styles.metricButtonActive : ''}`}
                    onClick={() => setDiagramMetric(option.key)}
                    disabled={!metricAvailability[option.key]}
                >
                    {option.label}
                </button>
            ))}
            {diagramMetric === 'buffers' && (
                <div className={styles.bufferControls}>
                    {['shared', 'local', 'temp'].map((type) => (
                        <button
                            key={type}
                            className={`${styles.bufferButton} ${bufferMetric === type ? styles.metricButtonActive : ''}`}
                            onClick={() => setBufferMetric(type)}
                        >
                            {type}
                        </button>
                    ))}
                </div>
            )}
        </div>
    )

    const clampValue = (value, min, max) => Math.min(max, Math.max(min, value))

    const zoomDiagram = (factor) => {
        setDiagramTransform((prev) => {
            const nextScale = clampValue(prev.scale * factor, 0.3, 2.5)
            return { ...prev, scale: nextScale }
        })
    }

    const resetDiagramTransform = () => {
        setDiagramTransform({ x: 0, y: 0, scale: 1 })
    }

    const handleDiagramWheel = (event) => {
        if (!event.ctrlKey && !event.metaKey) return
        event.preventDefault()
        zoomDiagram(event.deltaY < 0 ? 1.1 : 0.9)
    }

    const handleDiagramPointerDown = (event) => {
        if (event.button !== 0) return
        if (event.target?.closest?.('[data-diagram-node]')) return
        diagramDragRef.current = {
            startX: event.clientX,
            startY: event.clientY,
            originX: diagramTransform.x,
            originY: diagramTransform.y
        }
        event.currentTarget.setPointerCapture(event.pointerId)
    }

    const handleDiagramPointerMove = (event) => {
        if (!diagramDragRef.current) return
        const { startX, startY, originX, originY } = diagramDragRef.current
        const dx = event.clientX - startX
        const dy = event.clientY - startY
        setDiagramTransform((prev) => ({ ...prev, x: originX + dx, y: originY + dy }))
    }

    const handleDiagramPointerUp = () => {
        diagramDragRef.current = null
    }

    if (!connectionId) {
        return (
            <div className={styles.container}>
                <div className={styles.emptyState}>
                    <AlertCircle size={48} />
                    <h3>No Connection Selected</h3>
                    <p>Please select a database connection to analyze queries</p>
                </div>
            </div>
        )
    }

    // Debug logging on every render
    console.log('🔍 Render state:', {
        hasAnalysis: !!analysis,
        historyLength: analysisHistory.length,
        showHistory,
        performanceScore: analysis?.performanceScore
    })

    return (
        <div className={styles.container}>
            {/* Query Editor Section */}
            <div className={styles.editorSection}>
                <div className={styles.editorHeader}>
                    <h3>SQL Query</h3>
                    <div className={styles.editorActions}>
                        <label className={styles.toggleSwitch}>
                            <input
                                type="checkbox"
                                checked={useAnalyze}
                                onChange={(e) => setUseAnalyze(e.target.checked)}
                                className={styles.toggleInput}
                            />
                            <span className={styles.toggleSlider}></span>
                            <span className={styles.toggleLabel}>Explain analyze (runs query)</span>
                        </label>
                        {(() => {
                            console.log('🔘 History button condition:', analysisHistory.length > 0, 'Length:', analysisHistory.length)
                            return analysisHistory.length > 0
                        })() && (
                            <button
                                className={`${styles.editorActionButton} ${styles.secondaryAction}`}
                                onClick={() => setShowHistory(!showHistory)}
                                title={`View analysis history (${analysisHistory.length})`}
                                aria-label={`View analysis history (${analysisHistory.length})`}
                            >
                                <History size={16} />
                                <span className={styles.actionLabel}>History ({analysisHistory.length})</span>
                            </button>
                        )}
                        {(() => {
                            console.log('🔘 Clear button condition:', !!analysis, 'Analysis exists:', !!analysis)
                            return analysis
                        })() && (
                            <button
                                className={`${styles.editorActionButton} ${styles.secondaryAction}`}
                                onClick={clearAnalysis}
                                disabled={!analysis}
                                title="Clear results and start new analysis"
                                aria-label="Clear results"
                            >
                                <X size={16} />
                                <span className={styles.actionLabel}>Clear</span>
                            </button>
                        )}
                        <button
                            className={`${styles.editorActionButton} ${styles.analyzeAction} ${loading ? styles.running : ''}`}
                            onClick={analyzeQuery}
                            disabled={loading || !connectionId || !query.trim()}
                            title={!connectionId ? 'Please select a database connection first' : !query.trim() ? 'Please enter a SQL query' : 'Analyze Query'}
                            aria-label="Analyze Query"
                        >
                            {loading ? <Loader2 size={16} className={styles.runSpinner} /> : <Zap size={16} />}
                            <span className={styles.actionLabel}>{loading ? 'Analyzing...' : 'Analyze Query'}</span>
                        </button>
                    </div>
                </div>

                <div className={styles.monacoWrapper}>
                    <Suspense fallback={<div className={styles.editorLoading}>Loading editor...</div>}>
                        <Editor
                            height="200px"
                            defaultLanguage="sql"
                            value={query}
                            onChange={(value) => setQuery(value || '')}
                            theme="vs-light"
                            options={{
                                minimap: { enabled: false },
                                fontSize: 13,
                                lineNumbers: 'on',
                                roundedSelection: true,
                                scrollBeyondLastLine: false,
                                automaticLayout: true,
                                tabSize: 2,
                                wordWrap: 'on',
                            }}
                        />
                    </Suspense>
                </div>
            </div>

            {/* History Panel */}
            {showHistory && analysisHistory.length > 0 && (
                <div className={styles.historyPanel}>
                    <div className={styles.historyHeader}>
                        <div className={styles.historyTitle}>
                            <History size={20} />
                            <h3>Analysis History</h3>
                            <span className={styles.historyCount}>{analysisHistory.length} analyses</span>
                        </div>
                        <button
                            className={styles.closeHistoryButton}
                            onClick={() => setShowHistory(false)}
                        >
                            <X size={20} />
                        </button>
                    </div>
                    <div className={styles.historyList}>
                        {analysisHistory.map((item) => (
                            <div
                                key={item.id}
                                className={styles.historyItem}
                                onClick={() => loadFromHistory(item)}
                            >
                                <div className={styles.historyItemHeader}>
                                    <div className={styles.historyItemMeta}>
                                        <Clock size={14} />
                                        <span className={styles.historyTimestamp}>
                                            {formatTimestamp(item.timestamp)}
                                        </span>
                                        {item.useAnalyze && (
                                            <span className={styles.analyzeFlag}>ANALYZE</span>
                                        )}
                                        {analysis?.planSignature &&
                                            item.analysis?.planSignature &&
                                            item.analysis.planSignature !== analysis.planSignature && (
                                                <span className={styles.driftBadge}>Plan drift</span>
                                            )}
                                    </div>
                                    <div className={styles.historyItemActions}>
                                        <div
                                            className={styles.historyScore}
                                            style={{
                                                color: item.performanceScore >= 70 ? 'var(--color-primary)' :
                                                       item.performanceScore >= 50 ? 'var(--color-warning)' : 'var(--color-danger)'
                                            }}
                                        >
                                            Score: {item.performanceScore}
                                        </div>
                                        <button
                                            className={styles.deleteHistoryButton}
                                            onClick={(e) => {
                                                e.stopPropagation()
                                                deleteFromHistory(item.id)
                                            }}
                                            title="Delete from history"
                                        >
                                            <X size={16} />
                                        </button>
                                    </div>
                                </div>
                                <div className={styles.historyQuery}>
                                    {item.query.substring(0, 120)}
                                    {item.query.length > 120 && '...'}
                                </div>
                                <div className={styles.historyStats}>
                                    <span>{item.issueCount} issue{item.issueCount !== 1 ? 's' : ''}</span>
                                </div>
                            </div>
                        ))}
                    </div>
                </div>
            )}

            {/* Loading State */}
            {loading && (
                <div className={styles.loadingState}>
                    <Loader2 size={48} className={styles.spinner} />
                    <h3>Analyzing Query Execution Plan</h3>
                    <p>Examining query structure and detecting performance issues...</p>
                </div>
            )}

            {/* Error State */}
            {error && (
                <div className={styles.errorState}>
                    <AlertCircle size={48} />
                    <h3>Analysis Failed</h3>
                    <p>{error}</p>
                    <button onClick={analyzeQuery} className={styles.retryButton}>
                        Retry Analysis
                    </button>
                </div>
            )}

            {/* Results */}
            {analysis && !loading && (
                <div className={styles.results}>
                    <div className={styles.resultTabs}>
                        <button
                            className={`${styles.resultTabButton} ${resultsTab === 'analysis' ? styles.resultTabActive : ''}`}
                            onClick={() => setResultsTab('analysis')}
                        >
                            AI-Powered Analysis
                        </button>
                        <button
                            className={`${styles.resultTabButton} ${resultsTab === 'performance' ? styles.resultTabActive : ''}`}
                            onClick={() => setResultsTab('performance')}
                        >
                            Performance Issues
                        </button>
                        <button
                            className={`${styles.resultTabButton} ${resultsTab === 'plan' ? styles.resultTabActive : ''}`}
                            onClick={() => setResultsTab('plan')}
                        >
                            Execution Plan
                        </button>
                    </div>

                    {resultsTab === 'analysis' && (
                        analysis.aiSummary ? (
                            <div className={styles.aiSummary}>
                                <h3>AI-Powered Analysis</h3>
                                <div className={styles.summaryContent}>
                                    {analysis.aiSummary.split('\n').map((line, idx) => (
                                        <p key={idx}>{line}</p>
                                    ))}
                                </div>
                            </div>
                        ) : (
                            <div className={styles.aiSummary}>
                                <h3>AI-Powered Analysis</h3>
                                <div className={styles.summaryContent}>
                                    <p>No AI summary available for this query yet.</p>
                                </div>
                            </div>
                        )
                    )}

                    {resultsTab === 'performance' && (
                        <>
                            <div className={styles.scoreCard}>
                                <div className={styles.scoreCircle} style={{ borderColor: getScoreColor(analysis.performanceScore) }}>
                                    <div className={styles.scoreValue} style={{ color: getScoreColor(analysis.performanceScore) }}>
                                        {analysis.performanceScore}
                                    </div>
                                    <div className={styles.scoreLabel}>Performance Score</div>
                                </div>

                                <div className={styles.scoreMetrics}>
                                    {analysis.estimatedRows !== null && analysis.estimatedRows !== undefined && (
                                        <div className={styles.scoreMetric}>
                                            <span className={styles.metricLabel}>Estimated Rows:</span>
                                            <span className={styles.metricValue}>{analysis.estimatedRows.toLocaleString()}</span>
                                        </div>
                                    )}
                                    {analysis.estimatedCost !== null && analysis.estimatedCost !== undefined && (
                                        <div className={styles.scoreMetric}>
                                            <span className={styles.metricLabel}>Estimated Cost:</span>
                                            <span className={styles.metricValue}>{analysis.estimatedCost.toFixed(2)}</span>
                                        </div>
                                    )}
                                    <div className={styles.scoreMetric}>
                                        <span className={styles.metricLabel}>Issues Found:</span>
                                        <span className={styles.metricValue}>{analysis.issues.length}</span>
                                    </div>
                                </div>
                            </div>

                            {analysis.issues.length > 0 && (
                                <div className={styles.issuesSection}>
                                    <h3>Performance Issues ({analysis.issues.length})</h3>
                                    {analysis.issues.map((issue, idx) => (
                                        <div key={idx} className={styles.issueCard}>
                                            <div className={styles.issueHeader}>
                                                <div className={styles.severityBadge} style={{ backgroundColor: getSeverityColor(issue.severity) }}>
                                                    {getSeverityIcon(issue.severity)}
                                                    <span>{issue.severity}</span>
                                                </div>
                                                <div className={styles.issueType}>{issue.type}</div>
                                            </div>

                                            <div className={styles.issueBody}>
                                                <div className={styles.issueMessage}>{issue.message}</div>

                                                {issue.affectedRows !== null && issue.affectedRows !== undefined && issue.affectedRows > 0 && (
                                                    <div className={styles.issueMetric}>
                                                        Affected Rows: {issue.affectedRows.toLocaleString()}
                                                    </div>
                                                )}

                                                {issue.recommendation && (
                                                    <div className={styles.recommendation}>
                                                        <strong>Recommendation:</strong> {issue.recommendation}
                                                    </div>
                                                )}

                                                {issue.suggestedIndex && (
                                                    <div className={styles.sqlBlock}>
                                                        <div className={styles.sqlLabel}>Suggested Index:</div>
                                                        <code>{issue.suggestedIndex}</code>
                                                        <button
                                                            className={styles.copyButton}
                                                            onClick={() => copyToClipboard(issue.suggestedIndex, `issue-${idx}`)}
                                                            title="Copy SQL"
                                                        >
                                                            {copiedSql === `issue-${idx}` ? <Check size={16} /> : <Copy size={16} />}
                                                        </button>
                                                    </div>
                                                )}
                                            </div>
                                        </div>
                                    ))}
                                </div>
                            )}

                            {indexRecommendations.length > 0 && (
                                <div className={styles.indexSection}>
                                    <h3>Index Recommendations ({indexRecommendations.length})</h3>
                                    {indexRecommendations.map((rec, idx) => (
                                        <div key={`${rec.tableName}-${idx}`} className={styles.indexCard}>
                                            <div className={styles.indexHeader}>
                                                <span className={styles.indexTable}>{rec.tableName}</span>
                                                {rec.priority && (
                                                    <span className={styles.indexPriority}>{rec.priority}</span>
                                                )}
                                            </div>
                                            {rec.columns?.length ? (
                                                <div className={styles.indexMeta}>
                                                    Columns: {rec.columns.join(', ')}
                                                </div>
                                            ) : null}
                                            {rec.reasoning && (
                                                <div className={styles.indexReason}>{rec.reasoning}</div>
                                            )}
                                            {rec.suggestedSQL && (
                                                <div className={styles.sqlBlock}>
                                                    <div className={styles.sqlLabel}>Suggested Index:</div>
                                                    <code>{rec.suggestedSQL}</code>
                                                    <button
                                                        className={styles.copyButton}
                                                        onClick={() => copyToClipboard(rec.suggestedSQL, `index-${idx}`)}
                                                        title="Copy SQL"
                                                    >
                                                        {copiedSql === `index-${idx}` ? <Check size={16} /> : <Copy size={16} />}
                                                    </button>
                                                </div>
                                            )}
                                        </div>
                                    ))}
                                </div>
                            )}

                            {analysis.issues.length === 0 && (
                                <div className={styles.noIssues}>
                                    <CheckCircle size={48} />
                                    <h3>No Issues Detected</h3>
                                    <p>This query looks optimized and ready for production!</p>
                                </div>
                            )}
                        </>
                    )}

                    {resultsTab === 'plan' && (
                    <div className={styles.planSection}>
                        <h3>Execution Plan</h3>
                        <div className={styles.planHeader}>
                            <div className={styles.planTabs}>
                                <button
                                    className={`${styles.planTabButton} ${planTab === 'diagram' ? styles.planTabActive : ''}`}
                                    onClick={() => setPlanTab('diagram')}
                                    disabled={!hasPlanTree}
                                >
                                    Diagram
                                </button>
                                <button
                                    className={`${styles.planTabButton} ${planTab === 'plan' ? styles.planTabActive : ''}`}
                                    onClick={() => setPlanTab('plan')}
                                >
                                    Guide
                                </button>
                                <button
                                    className={`${styles.planTabButton} ${planTab === 'grid' ? styles.planTabActive : ''}`}
                                    onClick={() => setPlanTab('grid')}
                                    disabled={!hasPlanTree}
                                >
                                    Grid
                                </button>
                                <button
                                    className={`${styles.planTabButton} ${planTab === 'stats' ? styles.planTabActive : ''}`}
                                    onClick={() => setPlanTab('stats')}
                                >
                                    Stats
                                </button>
                                <button
                                    className={`${styles.planTabButton} ${planTab === 'raw' ? styles.planTabActive : ''}`}
                                    onClick={() => setPlanTab('raw')}
                                    disabled={!analysis.planText && !analysis.planJson && !planGuide?.rawExplainLines?.length}
                                >
                                    Raw
                                </button>
                                <button
                                    className={`${styles.planTabButton} ${planTab === 'query' ? styles.planTabActive : ''}`}
                                    onClick={() => setPlanTab('query')}
                                >
                                    Query
                                </button>
                            </div>
                            {planTab === 'diagram' && (
                                <div className={styles.planHeaderControls}>
                                    <div className={styles.planViewToggle}>
                                        <button
                                            className={`${styles.planViewButton} ${planView === 'diagram' ? styles.planViewButtonActive : ''}`}
                                            onClick={() => setPlanView('diagram')}
                                        >
                                            Diagram
                                        </button>
                                        <button
                                            className={`${styles.planViewButton} ${planView === 'list' ? styles.planViewButtonActive : ''}`}
                                            onClick={() => setPlanView('list')}
                                        >
                                            List
                                        </button>
                                    </div>
                                    {renderMetricControls()}
                                    {planView === 'diagram' && (
                                        <div className={styles.diagramControls}>
                                            <button className={styles.diagramControlButton} onClick={() => zoomDiagram(0.9)}>-</button>
                                            <button className={styles.diagramControlButton} onClick={() => zoomDiagram(1.1)}>+</button>
                                            <button className={styles.diagramControlButton} onClick={resetDiagramTransform}>Reset</button>
                                        </div>
                                    )}
                                    <button
                                        className={styles.detailsToggle}
                                        onClick={() => setShowPlanDetails((prev) => !prev)}
                                    >
                                        {showPlanDetails ? 'Hide details' : 'Show details'}
                                    </button>
                                </div>
                            )}
                        </div>
                        {analysis.planParseError && (
                            <div className={styles.planParseError}>
                                Parsing warning: {analysis.planParseError}
                            </div>
                        )}
                        {planDrift?.hasDrift && (
                            <div className={styles.planDrift}>
                                Plan drift detected: {planDrift.signatureCount} shapes seen. Last change {planDrift.lastChangeAt ? formatTimestamp(planDrift.lastChangeAt) : 'unknown'}.
                            </div>
                        )}
                        {(hasPlanTree || analysis.planningTimeMs != null || analysis.executionTimeMs != null) && (
                            <div className={styles.planSummary}>
                                <div className={styles.planSummaryItem}>
                                    <span>Planning time</span>
                                    <strong>{analysis.planningTimeMs != null ? `${analysis.planningTimeMs.toFixed(2)} ms` : 'N/A'}</strong>
                                </div>
                                <div className={styles.planSummaryItem}>
                                    <span>Execution time</span>
                                    <strong>{analysis.executionTimeMs != null ? `${analysis.executionTimeMs.toFixed(2)} ms` : 'N/A'}</strong>
                                </div>
                                <div className={styles.planSummaryItem}>
                                    <span>Estimated rows</span>
                                    <strong>{analysis.estimatedRows != null ? Math.round(analysis.estimatedRows).toLocaleString() : 'N/A'}</strong>
                                </div>
                                <div className={styles.planSummaryItem}>
                                    <span>Actual rows</span>
                                    <strong>{analysis.actualRows != null ? analysis.actualRows.toLocaleString() : 'N/A'}</strong>
                                </div>
                                <div className={styles.planSummaryItem}>
                                    <span>Estimated cost</span>
                                    <strong>{analysis.estimatedCost != null ? analysis.estimatedCost.toFixed(2) : 'N/A'}</strong>
                                </div>
                                <div className={styles.planSummaryItem}>
                                    <span>Plan nodes</span>
                                    <strong>{analysis.nodeCount != null ? analysis.nodeCount : 'N/A'}</strong>
                                </div>
                            </div>
                        )}
                        <div className={styles.planTree}>
                            {planTab === 'plan' && (
                                hasPlanTree ? (
                                    <>
                                        {planGuide && (
                                            <div className={styles.planGuide}>
                                                    <div className={styles.planGuideHeader}>
                                                        <h4>Beginner-Friendly Plan Guide</h4>
                                                        <p>Plain-English summary of how this query is executed.</p>
                                                    </div>
                                                    <div className={styles.planGuideSections}>
                                                        <div className={styles.planGuideSection}>
                                                            <h4>At a glance</h4>
                                                            {planGuide.glanceItems.length ? (
                                                                <ul className={styles.planGuideList}>
                                                                    {planGuide.glanceItems.map((item, idx) => (
                                                                        <li key={`glance-${idx}`}>{item}</li>
                                                                    ))}
                                                                </ul>
                                                            ) : (
                                                                <div className={styles.planGuideEmpty}>No summary details available.</div>
                                                            )}
                                                        </div>
                                                        <div className={styles.planGuideSection}>
                                                            <h4>Simplified steps</h4>
                                                            <pre className={styles.planSteps}>{planGuide.stepsText}</pre>
                                                        </div>
                                                        {planGuide.rawExplainLines?.length > 0 && (
                                                            <div className={styles.planGuideSection}>
                                                                <h4>Raw EXPLAIN ANALYZE (verbatim)</h4>
                                                                <pre className={styles.planRaw}>{planGuide.rawExplainLines.join('\n')}</pre>
                                                            </div>
                                                        )}
                                                        <div className={styles.planGuideSection}>
                                                            <h4>Glossary</h4>
                                                            <ul className={styles.planGuideList}>
                                                                {planGuide.glossary.map((item, idx) => (
                                                                    <li key={`glossary-${idx}`}>{item}</li>
                                                                ))}
                                                            </ul>
                                                            <div className={styles.aiGlossary}>
                                                                <div className={styles.aiGlossaryHeader}>
                                                                    <span>AI glossary insights</span>
                                                                    {glossaryLoading && <span className={styles.aiGlossaryStatus}>Loading...</span>}
                                                                </div>
                                                                {glossaryError && (
                                                                    <div className={styles.aiGlossaryError}>{glossaryError}</div>
                                                                )}
                                                                {glossaryInsights?.length ? (
                                                                    <div className={styles.aiGlossaryList}>
                                                                        {glossaryInsights.map((item, idx) => (
                                                                            <div key={`ai-glossary-${idx}`} className={styles.aiGlossaryItem}>
                                                                                <div className={styles.aiGlossaryTerm}>{item.term}</div>
                                                                                {item.meaning && (
                                                                                    <div className={styles.aiGlossaryMeaning}>{item.meaning}</div>
                                                                                )}
                                                                                {item.why_it_matters && (
                                                                                    <div className={styles.aiGlossaryWhy}>Why it matters: {item.why_it_matters}</div>
                                                                                )}
                                                                            </div>
                                                                        ))}
                                                                    </div>
                                                                ) : (
                                                                    !glossaryLoading && (
                                                                        <div className={styles.aiGlossaryEmpty}>
                                                                            AI glossary insights will appear here.
                                                                        </div>
                                                                    )
                                                                )}
                                                            </div>
                                                        </div>
                                                    </div>
                                                </div>
                                            )}
                                        </>
                                ) : emptyPlanState
                            )}
                            {planTab === 'grid' && (
                                hasPlanTree ? (
                                    <>
                                        <div className={styles.planGrid}>
                                            <div className={styles.planGridHeader}>
                                                <div>Node</div>
                                                <div>Rows</div>
                                                <div>Time</div>
                                                <div>Cost</div>
                                                <div>Loops</div>
                                                <div>{getMetricLabel(diagramMetric)} scale</div>
                                            </div>
                                            <div className={styles.planGridBody}>
                                                {flatNodes.map((item) => {
                                                    const value = getMetricValue(item.node, diagramMetric, bufferMetric)
                                                    const max = metricMaximums[diagramMetric] || 0
                                                    const percent = value != null && max > 0 ? Math.min(100, (value / max) * 100) : 0
                                                    return (
                                                        <div
                                                            key={item.id}
                                                            className={`${styles.planGridRow} ${selectedNodeId === item.id ? styles.planGridRowSelected : ''}`}
                                                            onClick={() => setSelectedNodeId(item.id)}
                                                        >
                                                            <div className={styles.planGridCell}>
                                                                <div className={styles.planGridNode} style={{ paddingLeft: `${item.depth * 16}px` }}>
                                                                    <span className={styles.planGridNodeType}>{item.node.nodeType || item.node.selectType || 'Plan step'}</span>
                                                                    {item.node.tableName && (
                                                                        <span className={styles.planGridNodeTable}>on {item.node.tableName}</span>
                                                                    )}
                                                                </div>
                                                            </div>
                                                            <div className={styles.planGridCell}>
                                                                <span>{item.node.actualRows != null ? item.node.actualRows.toLocaleString() : 'N/A'}</span>
                                                                <span className={styles.planGridSecondary}>est {item.node.planRows != null ? item.node.planRows.toLocaleString() : 'N/A'}</span>
                                                            </div>
                                                            <div className={styles.planGridCell}>
                                                                {item.node.actualTotalTime != null ? `${item.node.actualTotalTime.toFixed(2)} ms` : 'N/A'}
                                                            </div>
                                                            <div className={styles.planGridCell}>
                                                                {item.node.totalCost != null ? item.node.totalCost.toFixed(2) : 'N/A'}
                                                            </div>
                                                            <div className={styles.planGridCell}>
                                                                {item.node.actualLoops != null ? item.node.actualLoops : 'N/A'}
                                                            </div>
                                                            <div className={styles.planGridCell}>
                                                                <div className={styles.gridMetricBar}>
                                                                    <div className={styles.gridMetricFill} style={{ width: `${percent}%` }} />
                                                                </div>
                                                                <span className={styles.gridMetricValue}>{formatMetricValue(diagramMetric, value)}</span>
                                                            </div>
                                                        </div>
                                                    )
                                                })}
                                            </div>
                                        </div>
                                    </>
                                ) : emptyPlanState
                            )}
                            {planTab === 'raw' && (
                                <div className={styles.planRawSection}>
                                    <div className={styles.rawTabs}>
                                        <button
                                            className={`${styles.rawTabButton} ${rawTab === 'text' ? styles.planTabActive : ''}`}
                                            onClick={() => setRawTab('text')}
                                            disabled={!analysis.planText && !planGuide?.rawExplainLines?.length}
                                        >
                                            Plan Text
                                        </button>
                                        <button
                                            className={`${styles.rawTabButton} ${rawTab === 'json' ? styles.planTabActive : ''}`}
                                            onClick={() => setRawTab('json')}
                                            disabled={!analysis.planJson}
                                        >
                                            Plan JSON
                                        </button>
                                    </div>
                                    {rawTab === 'text' && (
                                        <pre className={styles.planRaw}>
                                            {(analysis.planText || planGuide?.rawExplainLines?.join('\n') || 'No plan text available.').trim()}
                                        </pre>
                                    )}
                                    {rawTab === 'json' && (
                                        <pre className={styles.planRaw}>
                                            {(analysis.planJson || 'No plan JSON available.').trim()}
                                        </pre>
                                    )}
                                </div>
                            )}
                            {planTab === 'query' && (
                                <div className={styles.planQuery}>
                                    <div className={styles.planQueryBlock}>
                                        <h4>Query</h4>
                                        <pre>{analysis.query}</pre>
                                    </div>
                                    <div className={styles.planQueryBlock}>
                                        <h4>Normalized query</h4>
                                        <pre>{analysis.normalizedQuery || 'Not available.'}</pre>
                                    </div>
                                </div>
                            )}
                            {planTab === 'stats' && (
                                <div className={styles.planStats}>
                                    <div className={styles.planStatsGrid}>
                                        <div className={styles.planStatCard}>
                                            <span>Planning time</span>
                                            <strong>{analysis.planningTimeMs != null ? `${analysis.planningTimeMs.toFixed(2)} ms` : 'N/A'}</strong>
                                        </div>
                                        <div className={styles.planStatCard}>
                                            <span>Execution time</span>
                                            <strong>{analysis.executionTimeMs != null ? `${analysis.executionTimeMs.toFixed(2)} ms` : 'N/A'}</strong>
                                        </div>
                                        <div className={styles.planStatCard}>
                                            <span>Total time</span>
                                            <strong>{analysis.totalTimeMs != null ? `${analysis.totalTimeMs.toFixed(2)} ms` : 'N/A'}</strong>
                                        </div>
                                        <div className={styles.planStatCard}>
                                            <span>Estimated cost</span>
                                            <strong>{analysis.estimatedCost != null ? analysis.estimatedCost.toFixed(2) : 'N/A'}</strong>
                                        </div>
                                        <div className={styles.planStatCard}>
                                            <span>Actual cost</span>
                                            <strong>{analysis.actualCost != null ? analysis.actualCost.toFixed(2) : 'N/A'}</strong>
                                        </div>
                                        <div className={styles.planStatCard}>
                                            <span>Estimated rows</span>
                                            <strong>{analysis.estimatedRows != null ? Math.round(analysis.estimatedRows).toLocaleString() : 'N/A'}</strong>
                                        </div>
                                        <div className={styles.planStatCard}>
                                            <span>Actual rows</span>
                                            <strong>{analysis.actualRows != null ? analysis.actualRows.toLocaleString() : 'N/A'}</strong>
                                        </div>
                                        <div className={styles.planStatCard}>
                                            <span>Plan nodes</span>
                                            <strong>{analysis.nodeCount != null ? analysis.nodeCount : 'N/A'}</strong>
                                        </div>
                                        {planJsonRoot?.JIT?.Timing?.Total != null && (
                                            <div className={styles.planStatCard}>
                                                <span>JIT time</span>
                                                <strong>{`${planJsonRoot.JIT.Timing.Total.toFixed(2)} ms`}</strong>
                                            </div>
                                        )}
                                        {Array.isArray(planJsonRoot?.Triggers) && planJsonRoot.Triggers.length > 0 && (
                                            <div className={styles.planStatCard}>
                                                <span>Triggers</span>
                                                <strong>{planJsonRoot.Triggers.length}</strong>
                                            </div>
                                        )}
                                        {planJsonRoot?.Settings && (
                                            <div className={styles.planStatCard}>
                                                <span>Planner settings</span>
                                                <strong>{Object.keys(planJsonRoot.Settings).length}</strong>
                                            </div>
                                        )}
                                    </div>
                                    {Array.isArray(planJsonRoot?.Triggers) && planJsonRoot.Triggers.length > 0 && (
                                        <div className={styles.planStatsDetails}>
                                            <h4>Triggers</h4>
                                            <div className={styles.planStatsList}>
                                                {planJsonRoot.Triggers.map((trigger, idx) => (
                                                    <div key={`trigger-${idx}`} className={styles.planStatsListItem}>
                                                        <span>{trigger['Trigger Name'] || `Trigger ${idx + 1}`}</span>
                                                        <strong>{trigger.Time != null ? `${trigger.Time.toFixed(2)} ms` : 'N/A'}</strong>
                                                    </div>
                                                ))}
                                            </div>
                                        </div>
                                    )}
                                    {planJsonRoot?.Settings && (
                                        <div className={styles.planStatsDetails}>
                                            <h4>Planner settings</h4>
                                            <div className={styles.planStatsList}>
                                                {Object.entries(planJsonRoot.Settings).slice(0, 12).map(([key, value]) => (
                                                    <div key={`setting-${key}`} className={styles.planStatsListItem}>
                                                        <span>{key}</span>
                                                        <strong>{String(value)}</strong>
                                                    </div>
                                                ))}
                                            </div>
                                        </div>
                                    )}
                                </div>
                            )}
                            {planTab === 'diagram' && (
                                hasPlanTree ? (
                                    <div className={`${styles.planWorkspace} ${showPlanDetails ? styles.planWorkspaceWithDetails : styles.planWorkspaceFull}`}>
                                        <div className={styles.planCanvas}>
                                            {planView === 'diagram' && (
                                                diagramLayout ? (
                                                    <div
                                                        className={styles.planDiagram}
                                                        onWheel={handleDiagramWheel}
                                                        onPointerDown={handleDiagramPointerDown}
                                                        onPointerMove={handleDiagramPointerMove}
                                                        onPointerUp={handleDiagramPointerUp}
                                                        onPointerLeave={handleDiagramPointerUp}
                                                    >
                                                        <div
                                                            className={styles.diagramViewport}
                                                            ref={diagramViewportRef}
                                                            style={{
                                                                width: diagramLayout.width,
                                                                height: diagramLayout.height,
                                                                transform: `translate(${diagramTransform.x}px, ${diagramTransform.y}px) scale(${diagramTransform.scale})`,
                                                                transformOrigin: '0 0'
                                                            }}
                                                        >
                                                            <svg
                                                                className={styles.diagramEdges}
                                                                width={diagramLayout.width}
                                                                height={diagramLayout.height}
                                                            >
                                                                {diagramLayout.edges.map((edge, idx) => {
                                                                    const from = diagramLayout.nodeMap.get(edge.from)
                                                                    const to = diagramLayout.nodeMap.get(edge.to)
                                                                    if (!from || !to) return null
                                                                    const startX = from.x
                                                                    const startY = from.y + DIAGRAM_NODE_HEIGHT
                                                                    const endX = to.x
                                                                    const endY = to.y
                                                                    const midY = (startY + endY) / 2
                                                                    const path = `M ${startX} ${startY} C ${startX} ${midY} ${endX} ${midY} ${endX} ${endY}`
                                                                    return <path key={`edge-${idx}`} d={path} />
                                                                })}
                                                            </svg>
                                                            {diagramLayout.nodes
                                                                .slice()
                                                                .sort((a, b) => a.depth - b.depth)
                                                                .map((layoutNode) => renderDiagramNode(layoutNode))}
                                                        </div>
                                                    </div>
                                                ) : (
                                                    emptyPlanState
                                                )
                                            )}
                                            {planView === 'list' && renderPlanNode(analysis.planTree, ['root'])}
                                        </div>
                                        {showPlanDetails && (
                                            <div className={styles.planDetails}>
                                                {selectedNode ? (
                                                    <>
                                                        <div className={styles.planDetailsHeader}>
                                                            <h4>Node details</h4>
                                                            <button
                                                                className={styles.planDetailsClear}
                                                                onClick={() => setSelectedNodeId(null)}
                                                            >
                                                                <X size={14} />
                                                            </button>
                                                        </div>
                                                        <div className={styles.planDetailsBody}>
                                                            <div className={styles.detailSection}>
                                                                <h5>General</h5>
                                                                <div className={styles.detailRow}>
                                                                    <span>Node type</span>
                                                                    <strong>{selectedNode.nodeType || selectedNode.selectType || 'Plan step'}</strong>
                                                                </div>
                                                                {selectedNode.tableName && (
                                                                    <div className={styles.detailRow}>
                                                                        <span>Table</span>
                                                                        <strong>{selectedNode.tableName}</strong>
                                                                    </div>
                                                                )}
                                                                {selectedNode.joinType && (
                                                                    <div className={styles.detailRow}>
                                                                        <span>Join</span>
                                                                        <strong>{selectedNode.joinType}</strong>
                                                                    </div>
                                                                )}
                                                                {selectedNode.accessType && (
                                                                    <div className={styles.detailRow}>
                                                                        <span>Access</span>
                                                                        <strong>{selectedNode.accessType}</strong>
                                                                    </div>
                                                                )}
                                                                {selectedNode.key && (
                                                                    <div className={styles.detailRow}>
                                                                        <span>Index</span>
                                                                        <strong>{selectedNode.key}</strong>
                                                                    </div>
                                                                )}
                                                            </div>
                                                            <div className={styles.detailSection}>
                                                                <h5>Rows & time</h5>
                                                                <div className={styles.detailRow}>
                                                                    <span>Estimated rows</span>
                                                                    <strong>{selectedNode.planRows != null ? selectedNode.planRows.toLocaleString() : 'N/A'}</strong>
                                                                </div>
                                                                <div className={styles.detailRow}>
                                                                    <span>Actual rows</span>
                                                                    <strong>{selectedNode.actualRows != null ? selectedNode.actualRows.toLocaleString() : 'N/A'}</strong>
                                                                </div>
                                                                <div className={styles.detailRow}>
                                                                    <span>Loops</span>
                                                                    <strong>{selectedNode.actualLoops != null ? selectedNode.actualLoops : 'N/A'}</strong>
                                                                </div>
                                                                <div className={styles.detailRow}>
                                                                    <span>Actual time</span>
                                                                    <strong>{selectedNode.actualTotalTime != null ? `${selectedNode.actualTotalTime.toFixed(2)} ms` : 'N/A'}</strong>
                                                                </div>
                                                                <div className={styles.detailRow}>
                                                                    <span>Cost</span>
                                                                    <strong>{selectedNode.totalCost != null ? selectedNode.totalCost.toFixed(2) : 'N/A'}</strong>
                                                                </div>
                                                            </div>
                                                            <div className={styles.detailSection}>
                                                                <h5>Filters & extras</h5>
                                                                <div className={styles.detailRow}>
                                                                    <span>Filter</span>
                                                                    <strong>{selectedNode.filter || 'N/A'}</strong>
                                                                </div>
                                                                <div className={styles.detailRow}>
                                                                    <span>Index condition</span>
                                                                    <strong>{selectedNode.indexCondition || 'N/A'}</strong>
                                                                </div>
                                                                {selectedNode.rowsRemovedByFilter != null && (
                                                                    <div className={styles.detailRow}>
                                                                        <span>Rows removed</span>
                                                                        <strong>{selectedNode.rowsRemovedByFilter.toLocaleString()}</strong>
                                                                    </div>
                                                                )}
                                                                <div className={styles.detailRow}>
                                                                    <span>Extra</span>
                                                                    <strong>{selectedNode.extra || 'N/A'}</strong>
                                                                </div>
                                                            </div>
                                                            <div className={styles.detailSection}>
                                                                <h5>Buffers</h5>
                                                                {selectedNode.additionalInfo && Object.keys(selectedNode.additionalInfo).length ? (
                                                                    Object.entries(selectedNode.additionalInfo).map(([key, value]) => (
                                                                        <div key={`buffer-${key}`} className={styles.detailRow}>
                                                                            <span>{key}</span>
                                                                            <strong>{value}</strong>
                                                                        </div>
                                                                    ))
                                                                ) : (
                                                                    <div className={styles.detailEmpty}>No buffer metrics available.</div>
                                                                )}
                                                            </div>
                                                            <div className={styles.detailSection}>
                                                                <h5>Other</h5>
                                                                <div className={styles.detailRow}>
                                                                    <span>Alias</span>
                                                                    <strong>{selectedNode.aliasName || 'N/A'}</strong>
                                                                </div>
                                                                <div className={styles.detailRow}>
                                                                    <span>Select type</span>
                                                                    <strong>{selectedNode.selectType || 'N/A'}</strong>
                                                                </div>
                                                                <div className={styles.detailRow}>
                                                                    <span>Parent role</span>
                                                                    <strong>{selectedNode.parentRelationship || 'N/A'}</strong>
                                                                </div>
                                                                <div className={styles.detailRow}>
                                                                    <span>Possible keys</span>
                                                                    <strong>{selectedNode.possibleKeys || 'N/A'}</strong>
                                                                </div>
                                                                <div className={styles.detailRow}>
                                                                    <span>Key length</span>
                                                                    <strong>{selectedNode.keyLength || 'N/A'}</strong>
                                                                </div>
                                                                <div className={styles.detailRow}>
                                                                    <span>Ref</span>
                                                                    <strong>{selectedNode.ref || 'N/A'}</strong>
                                                                </div>
                                                            </div>
                                                        </div>
                                                    </>
                                                ) : (
                                                    <div className={styles.planDetailsEmpty}>
                                                        Select a node to inspect metrics and filters.
                                                    </div>
                                                )}
                                            </div>
                                        )}
                                    </div>
                                ) : emptyPlanState
                            )}
                        </div>
                        </div>
                    )}
                </div>
            )}

            {/* Empty State */}
            {!analysis && !loading && !error && (
                <div className={styles.emptyState}>
                    <Zap size={48} />
                    <h3>Ready to Analyze</h3>
                    <p>Enter a SQL SELECT query above and click "Analyze Query" to see the execution plan and performance recommendations</p>
                </div>
            )}
        </div>
    )
}
