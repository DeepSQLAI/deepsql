'use client'

import { useState, useEffect, useMemo } from 'react'
import {
    LineChart, Line, BarChart, Bar, XAxis, YAxis, CartesianGrid,
    Tooltip, Legend, ResponsiveContainer
} from 'recharts'
import {
    TrendingUp, AlertTriangle, Settings, Activity,
    Database, CheckCircle, Calendar, RefreshCw, Bell,
    ArrowUp, ArrowDown, Minus, ChevronDown, ChevronUp, HardDrive, Table, Clock, Loader2,
    Trash2, Archive, Layers, Zap, Search, FileWarning, Lightbulb, Download, Copy, X, Timer
} from 'lucide-react'
import { growthMonitoringAPI, queryAPI, resourceLimitsAPI, slowQueriesAPI, brainAPI } from '@/lib/api/client'
import {
    useGrowthHistory,
    useGrowthAnomalies,
    useGrowthTrends,
    useGrowthConfiguration,
    useSaveGrowthConfiguration,
    useManualCapture,
} from '@/lib/hooks/queries'
import useToast from '@/hooks/useToast'
import ToastContainer from '../../ToastContainer'
import Skeleton, { SkeletonTableRow, SkeletonText } from '../../Skeleton'
import styles from './GrowthMonitoringTab.module.css'

export default function GrowthMonitoringTab({ connectionId }) {
    const { toasts, removeToast, success, error: showError, info } = useToast()
    const [activeView, setActiveView] = useState('overview')
    const [timeRange, setTimeRange] = useState(7)
    const [selectedTable, setSelectedTable] = useState(null)

    // TanStack Query hooks for main data
    // In overview mode, load history for ALL tables (null). In trends mode, load for specific table.
    const tableFilter = activeView === 'overview' ? null : selectedTable

    const {
        data: historyData,
        isLoading: loadingHistory,
        refetch: refetchHistory,
    } = useGrowthHistory(connectionId, tableFilter, timeRange)

    const {
        data: anomaliesData,
        isLoading: loadingAnomalies,
        refetch: refetchAnomalies,
    } = useGrowthAnomalies(connectionId, {
        tableName: selectedTable,
        unacknowledgedOnly: false,
        days: timeRange,
    })

    const {
        data: trendsData,
        refetch: refetchTrends,
    } = useGrowthTrends(connectionId, selectedTable, timeRange)

    const {
        data: configData,
        isLoading: loadingConfig,
        refetch: refetchConfig,
    } = useGrowthConfiguration(connectionId, selectedTable)

    // Mutations
    const saveConfigMutation = useSaveGrowthConfiguration()
    const manualCaptureMutation = useManualCapture()

    // Derived data from queries
    const history = useMemo(() => historyData?.history || [], [historyData])
    const anomalies = useMemo(() => anomaliesData?.anomalies || [], [anomaliesData])
    const statistics = useMemo(() => anomaliesData?.statistics || null, [anomaliesData])
    const trends = useMemo(() => trendsData?.trends || null, [trendsData])

    // Config with fallback to defaults
    const getDefaultConfig = () => ({
        percentageGrowthWarning: 50.0,
        percentageGrowthCritical: 100.0,
        absoluteGrowthWarningBytes: 10737418240, // 10GB
        absoluteGrowthCriticalBytes: 53687091200, // 50GB
        rowSpikeWarning: 1000000,
        rowSpikeCritical: 10000000,
        zScoreThreshold: 3.0,
        notificationChannels: ['in-app'],
        emailRecipients: [],
        slackWebhookUrl: '',
        isEnabled: true
    })
    const fetchedConfig = useMemo(() => configData?.configuration || getDefaultConfig(), [configData])

    // Editable config state for the form (synced from fetched config)
    const [config, setConfig] = useState(getDefaultConfig)

    // Sync local config when fetched config changes
    useEffect(() => {
        if (fetchedConfig) {
            setConfig(fetchedConfig)
        }
    }, [fetchedConfig])

    // Remaining local state
    const [allTables, setAllTables] = useState([])
    const [tableStats, setTableStats] = useState([])
    const [topN, setTopN] = useState(25)

    // UI states
    const [error, setError] = useState(null)
    const [saving, setSaving] = useState(false)
    const [loadingTableStats, setLoadingTableStats] = useState(false)
    // Slow query cross-reference data
    const [slowQueryTables, setSlowQueryTables] = useState(new Map()) // tableName -> { queryCount, avgTime, severity }
    // Cleanup script generator modal
    const [showCleanupModal, setShowCleanupModal] = useState(false)
    const [cleanupTable, setCleanupTable] = useState(null)
    const [cleanupConfig, setCleanupConfig] = useState({
        retentionDays: 90,
        batchSize: 10000,
        dateColumn: 'created_at',
        dateColumnType: 'datetime' // 'datetime', 'timestamp', 'epoch'
    })
    const [capturingSnapshot, setCapturingSnapshot] = useState(false)
    const [sortBy, setSortBy] = useState('dataLength')
    const [sortDirection, setSortDirection] = useState('desc')
    const [cacheAge, setCacheAge] = useState(null)

    // Resource Limits (for collection cadence)
    const [resourceLimits, setResourceLimits] = useState(null)
    const [collectionCadence, setCollectionCadence] = useState('HOUR')

    // Cache configuration
    const CACHE_TTL_MINUTES = 5
    const CACHE_KEY = `growth_monitoring_table_stats_${connectionId}`
    const [visibleColumns, setVisibleColumns] = useState({
        name: true,
        engine: false,
        rowCount: true,
        dataSize: true,
        indexSize: true,
        totalSize: true,
        allocatedSize: true,
        logicalSize: false,
        bloatPercent: true,
        growthRate: true,
        growthBytes: true,
        rowGrowthRate: true,
        rowGrowth: false,
        collation: false
    })
    const [showColumnSelector, setShowColumnSelector] = useState(false)

    useEffect(() => {
        if (!connectionId) return

        // Prevent duplicate calls in React Strict Mode
        let isCancelled = false

        const loadAllData = async () => {
            if (!isCancelled) {
                await loadData()
                await loadAllTables()
            }
        }

        loadAllData()

        return () => {
            isCancelled = true
        }
    }, [connectionId, timeRange, selectedTable, activeView])

    useEffect(() => {
        if (!connectionId || activeView !== 'overview') return

        // Prevent duplicate calls in React Strict Mode
        let isCancelled = false

        const loadStats = async () => {
            if (!isCancelled) {
                await loadTableStats()
            }
        }

        loadStats()

        return () => {
            isCancelled = true
        }
    }, [connectionId, activeView])

    // Recalculate growth data when history becomes available
    // This fixes the race condition where tableStats loads before history
    useEffect(() => {
        if (!history.length || !tableStats.length) return

        // Check if any table is missing growth data that we could calculate from history
        const needsGrowthUpdate = tableStats.some(t => {
            if (t.hasGrowthData) return false
            const tableHistory = history.filter(h => h.tableName === t.name)
            return tableHistory.length > 1
        })

        if (!needsGrowthUpdate) return

        // Recalculate growth data from history without re-fetching
        const updatedStats = tableStats.map(table => {
            if (table.hasGrowthData) return table

            const tableHistory = history.filter(h => h.tableName === table.name)
            if (tableHistory.length > 1) {
                const latest = tableHistory[tableHistory.length - 1]
                const oldest = tableHistory[0]
                let growthBytes = 0, growthRate = 0, rowGrowth = 0, rowGrowthRate = 0
                let hasData = false

                // Calculate size growth
                if (oldest.sizeBytes > 0) {
                    growthBytes = latest.sizeBytes - oldest.sizeBytes
                    growthRate = (growthBytes / oldest.sizeBytes) * 100
                    hasData = true
                }
                // Calculate row count growth (MySQL size estimates don't always update)
                if (oldest.rowCount > 0) {
                    rowGrowth = latest.rowCount - oldest.rowCount
                    rowGrowthRate = (rowGrowth / oldest.rowCount) * 100
                    hasData = true
                }

                if (hasData) {
                    return {
                        ...table,
                        growthRate,
                        growthBytes,
                        rowGrowth,
                        rowGrowthRate,
                        hasGrowthData: true,
                        allocatedBytes: table.allocatedBytes ?? latest.allocatedBytes,
                        bloatPercent: table.bloatPercent ?? latest.bloatPercent
                    }
                }
            }
            return table
        })

        setTableStats(updatedStats)
    }, [history, tableStats.length])

    // Close column selector when clicking outside
    useEffect(() => {
        const handleClickOutside = (e) => {
            if (showColumnSelector && !e.target.closest('.columnSelectorContainer')) {
                setShowColumnSelector(false)
            }
        }
        document.addEventListener('click', handleClickOutside)
        return () => document.removeEventListener('click', handleClickOutside)
    }, [showColumnSelector])

    // Cache helper functions
    const getFromCache = () => {
        try {
            const cached = localStorage.getItem(CACHE_KEY)
            if (!cached) return null

            const { data, timestamp } = JSON.parse(cached)
            const ageMinutes = (Date.now() - timestamp) / 1000 / 60

            // Check if cache is still valid
            if (ageMinutes < CACHE_TTL_MINUTES) {
                setCacheAge(Math.floor(ageMinutes))
                console.log(`✓ Using cached table stats (${Math.floor(ageMinutes)} minutes old)`)
                return data
            }

            // Cache expired
            console.log(`⚠ Cache expired (${Math.floor(ageMinutes)} minutes old)`)
            return null
        } catch (err) {
            console.error('Error reading cache:', err)
            return null
        }
    }

    const saveToCache = (data) => {
        try {
            localStorage.setItem(CACHE_KEY, JSON.stringify({
                data,
                timestamp: Date.now()
            }))
            setCacheAge(0)
            console.log(`✓ Cached ${data.length} table stats`)
        } catch (err) {
            console.error('Error saving to cache:', err)
        }
    }

    const clearCache = () => {
        try {
            localStorage.removeItem(CACHE_KEY)
            setCacheAge(null)
            console.log('✓ Cache cleared')
        } catch (err) {
            console.error('Error clearing cache:', err)
        }
    }

    const loadAllTables = async () => {
        try {
            const objectsResponse = await queryAPI.getDatabaseObjects(connectionId)
            if (objectsResponse.success && objectsResponse.objects) {
                const tableObjects = objectsResponse.objects.filter(obj => obj.type === 'table')
                const tables = tableObjects.map(obj => obj.name).filter(Boolean).sort()
                setAllTables(tables)
                return
            }

            // Fallback: use growth history if objects are unavailable
            const data = await growthMonitoringAPI.getGrowthHistory(
                connectionId,
                null, // null = all tables
                timeRange
            )
            if (data.success && data.history) {
                const tables = [...new Set(data.history.map(h => h.tableName))].sort()
                setAllTables(tables)
            }
        } catch (err) {
            console.error('Error loading all tables:', err)
        }
    }

    // Combined loading state (TanStack Query handles individual loading)
    const loading = loadingHistory || loadingAnomalies || loadingConfig

    const loadData = async () => {
        setError(null)
        // Run remaining loads in parallel
        // TanStack Query automatically handles history, anomalies, trends, config
        await Promise.all([
            loadResourceLimits(),
            loadSlowQueryData()
        ])
    }

    // Refresh all data (for manual refresh button)
    const refreshAllData = () => {
        refetchHistory()
        refetchAnomalies()
        refetchTrends()
        refetchConfig()
        loadResourceLimits()
        loadSlowQueryData()
    }

    const captureManualSnapshot = async () => {
        try {
            setCapturingSnapshot(true)
            setError(null)

            // Show info toast that capture is starting
            const tableCount = tableStats.length || 'many'
            info(`Capturing snapshots for ${tableCount} tables. This may take several minutes...`, 10000)

            const response = await manualCaptureMutation.mutateAsync(connectionId)

            if (response.success) {
                // Backend runs capture asynchronously, so we need to wait for it to complete
                info('Snapshot capture started in background. Waiting for completion...', 5000)

                // Wait 45 seconds for capture to complete
                // (For large databases with many tables, this might need to be longer)
                await new Promise(resolve => setTimeout(resolve, 45000))

                // Invalidate cache to force fresh data
                localStorage.removeItem(CACHE_KEY)

                // Reload data after capture completes
                refetchHistory()
                await loadTableStats(true)

                success('Snapshot captured successfully!')
            } else {
                showError('Failed to capture snapshot: ' + (response.message || 'Unknown error'))
            }
        } catch (err) {
            console.error('Error capturing snapshot:', err)
            showError('Failed to capture snapshot: ' + err.message)
        } finally {
            setCapturingSnapshot(false)
        }
    }

    const loadResourceLimits = async () => {
        try {
            const data = await resourceLimitsAPI.getResourceLimits(connectionId)
            if (data && data.exists !== false) {
                setResourceLimits(data)
                setCollectionCadence(data.collectionCadence || 'HOUR')
            }
        } catch (err) {
            console.error('Error loading resource limits:', err)
            // Use defaults
            setCollectionCadence('HOUR')
        }
    }

    // Load slow query data to cross-reference with table growth
    const loadSlowQueryData = async () => {
        try {
            const data = await slowQueriesAPI.getLatest(connectionId)
            if (data && data.queries) {
                // Build a map of table -> slow query stats
                const tableMap = new Map()
                data.queries.forEach(query => {
                    // Extract table names from the query (simple pattern matching)
                    const tableMatches = query.query?.match(/(?:FROM|JOIN|UPDATE|INTO)\s+[`"]?(\w+)[`"]?/gi) || []
                    tableMatches.forEach(match => {
                        const tableName = match.replace(/(?:FROM|JOIN|UPDATE|INTO)\s+[`"]?/i, '').replace(/[`"]/g, '').toLowerCase()
                        if (tableName && tableName !== 'dual' && tableName !== 'information_schema') {
                            const existing = tableMap.get(tableName) || { queryCount: 0, totalTime: 0, maxTime: 0 }
                            existing.queryCount++
                            existing.totalTime += query.avgQueryTime || query.queryTime || 0
                            existing.maxTime = Math.max(existing.maxTime, query.avgQueryTime || query.queryTime || 0)
                            tableMap.set(tableName, existing)
                        }
                    })
                })
                // Calculate averages and severity
                tableMap.forEach((stats, tableName) => {
                    stats.avgTime = stats.totalTime / stats.queryCount
                    stats.severity = stats.maxTime > 10 ? 'critical' : stats.maxTime > 5 ? 'high' : stats.maxTime > 1 ? 'medium' : 'low'
                })
                setSlowQueryTables(tableMap)
            }
        } catch (err) {
            // Slow query data is optional - don't fail if unavailable
            console.log('Slow query data not available:', err.message)
        }
    }

    const loadTableStats = async (forceRefresh = false) => {
        try {
            console.log('Loading table stats for connection:', connectionId)

            // Try to use cached data unless force refresh
            if (!forceRefresh) {
                const cached = getFromCache()
                if (cached) {
                    setTableStats(cached)
                    return
                }
            }

            setLoadingTableStats(true)

            // Fetch table objects
            const response = await queryAPI.getDatabaseObjects(connectionId)
            console.log('Database objects response:', response)

            if (response.success) {
                const tableObjects = response.objects.filter(obj => obj.type === 'table')
                console.log(`Found ${tableObjects.length} tables`)

                // Sort by row count (descending) to prioritize larger tables
                // This ensures we load stats for the largest/most important tables first
                tableObjects.sort((a, b) => (b.rowCount || 0) - (a.rowCount || 0))

                // Limit to first 300 tables to balance performance and coverage
                // Sorted by row count, so this captures the largest tables
                const limitedTables = tableObjects.slice(0, 300)

                // Fetch statistics for each table and merge with growth data
                console.log(`Fetching stats for ${limitedTables.length} tables...`)
                const tablesWithStats = await Promise.all(
                    limitedTables.map(async (table, index) => {
                        try {
                            // Get table stats
                            const statsResponse = await queryAPI.getTableStats(connectionId, table.name)
                            console.log(`[${index + 1}/${limitedTables.length}] Loaded stats for ${table.name}:`, statsResponse.stats)

                            // Get latest growth data for this table (simplified - just use latest snapshot)
                            let growthRate = 0
                            let growthBytes = 0
                            let rowGrowth = 0
                            let rowGrowthRate = 0
                            let hasGrowthData = false
                            let allocatedBytes = null
                            let bloatPercent = null

                            // Use the history data if we already have it
                            const tableHistory = history.filter(h => h.tableName === table.name)
                            if (tableHistory.length > 0) {
                                const latest = tableHistory[tableHistory.length - 1]

                                // Get bloat data from latest snapshot
                                allocatedBytes = latest.allocatedBytes
                                bloatPercent = latest.bloatPercent

                                // Calculate growth if we have multiple snapshots
                                if (tableHistory.length > 1) {
                                    const oldest = tableHistory[0]
                                    // Calculate size growth
                                    if (oldest.sizeBytes > 0) {
                                        growthBytes = latest.sizeBytes - oldest.sizeBytes
                                        growthRate = (growthBytes / oldest.sizeBytes) * 100
                                        hasGrowthData = true
                                    }
                                    // Calculate row count growth (MySQL size estimates don't always update)
                                    if (oldest.rowCount > 0) {
                                        rowGrowth = latest.rowCount - oldest.rowCount
                                        rowGrowthRate = (rowGrowth / oldest.rowCount) * 100
                                        hasGrowthData = true
                                    }
                                }
                            }

                            // Fallback to stats response if history doesn't have bloat data
                            if (statsResponse.success && statsResponse.stats) {
                                if (allocatedBytes == null) {
                                    allocatedBytes = statsResponse.stats.allocatedBytes ?? 0
                                }
                                if (bloatPercent == null) {
                                    bloatPercent = statsResponse.stats.bloatPercent ?? 0
                                }
                            }

                            return {
                                ...table,
                                stats: statsResponse.success ? statsResponse.stats : null,
                                growthRate,
                                growthBytes,
                                rowGrowth,
                                rowGrowthRate,
                                hasGrowthData,
                                allocatedBytes,
                                bloatPercent
                            }
                        } catch (err) {
                            console.error(`Failed to fetch data for ${table.name}:`, err)
                            return {
                                ...table,
                                stats: null,
                                growthRate: 0,
                                growthBytes: 0,
                                rowGrowth: 0,
                                rowGrowthRate: 0,
                                hasGrowthData: false,
                                allocatedBytes: 0,
                                bloatPercent: 0
                            }
                        }
                    })
                )

                console.log(`✓ Loaded stats for ${tablesWithStats.length} tables`)
                console.log('Sample table stats:', tablesWithStats.slice(0, 3).map(t => ({
                    name: t.name,
                    dataSize: t.stats?.dataSize,
                    rowCount: t.stats?.rowCount
                })))

                setTableStats(tablesWithStats)

                // Cache the results
                saveToCache(tablesWithStats)

                // Small delay to ensure state updates are rendered
                await new Promise(resolve => setTimeout(resolve, 100))
                console.log('✓ Table stats state updated and ready to display')
            } else {
                console.error('Failed to get database objects:', response)
                setTableStats([])
            }
        } catch (err) {
            console.error('Error loading table stats:', err)
            setError('Failed to load table statistics: ' + err.message)
            setTableStats([])
        } finally {
            setLoadingTableStats(false)
        }
    }

    const handleAcknowledge = async (anomalyId) => {
        try {
            await growthMonitoringAPI.acknowledgeAnomaly(anomalyId, 'user')
            refetchAnomalies()
        } catch (err) {
            console.error('Error acknowledging anomaly:', err)
        }
    }

    const handleSaveConfig = async (e) => {
        e.preventDefault()
        setSaving(true)
        try {
            // Save growth monitoring configuration via mutation
            const configPayload = {
                ...config,
                connectionId,
                tableName: selectedTable || '*'
            }
            await saveConfigMutation.mutateAsync(configPayload)

            // Save collection cadence to resource limits
            const limitsData = {
                ...resourceLimits,
                collectionCadence,
                isEnabled: true
            }
            await resourceLimitsAPI.saveResourceLimits(connectionId, limitsData)

            success('Configuration saved successfully')
            // Reload to get updated settings
            refetchConfig()
            await loadResourceLimits()
        } catch (err) {
            showError('Failed to save configuration: ' + err.message)
        } finally {
            setSaving(false)
        }
    }

    const handleManualCapture = async () => {
        try {
            await manualCaptureMutation.mutateAsync(connectionId)
            // Clear cache since new snapshot data is available
            clearCache()
            success('Snapshot captured successfully')
            refreshAllData()
        } catch (err) {
            showError('Failed to capture snapshot: ' + err.message)
        }
    }

    const getSeverityClass = (severity) => {
        switch (severity) {
            case 'CRITICAL': return styles.severityCritical
            case 'WARNING': return styles.severityWarning
            case 'INFO': return styles.severityInfo
            default: return ''
        }
    }

    const formatBytes = (bytes) => {
        if (!bytes) return 'N/A'
        if (bytes < 1024) return bytes + ' B'
        if (bytes < 1024 * 1024) return (bytes / 1024).toFixed(2) + ' KB'
        if (bytes < 1024 * 1024 * 1024) return (bytes / (1024 * 1024)).toFixed(2) + ' MB'
        return (bytes / (1024 * 1024 * 1024)).toFixed(2) + ' GB'
    }

    const formatTimestamp = (timestamp) => {
        const date = new Date(timestamp)
        return date.toLocaleDateString() + ' ' + date.toLocaleTimeString([], {
            hour: '2-digit',
            minute: '2-digit'
        })
    }

    const formatNumber = (num) => {
        if (num == null) return 'N/A'
        return num.toLocaleString()
    }

    // Calculate growth forecast for a table
    const calculateGrowthForecast = (table) => {
        if (!table.hasGrowthData || !table.growthRate) return null

        const currentSize = (table.stats?.dataSize || 0) + (table.stats?.indexSize || 0)
        if (currentSize === 0) return null

        // Daily growth rate (assuming timeRange is in days)
        const dailyGrowthRate = table.growthRate / timeRange / 100

        // Project sizes for different periods
        const forecast = {
            current: currentSize,
            days30: currentSize * Math.pow(1 + dailyGrowthRate, 30),
            days60: currentSize * Math.pow(1 + dailyGrowthRate, 60),
            days90: currentSize * Math.pow(1 + dailyGrowthRate, 90),
            days180: currentSize * Math.pow(1 + dailyGrowthRate, 180),
            dailyGrowthRate: dailyGrowthRate * 100,
            daysTo100GB: null,
            daysTo500GB: null,
            daysToDouble: null
        }

        // Calculate days to reach thresholds
        const threshold100GB = 100 * 1024 * 1024 * 1024
        const threshold500GB = 500 * 1024 * 1024 * 1024

        if (dailyGrowthRate > 0) {
            // Days to double: solve currentSize * (1 + r)^n = 2 * currentSize
            forecast.daysToDouble = Math.ceil(Math.log(2) / Math.log(1 + dailyGrowthRate))

            if (currentSize < threshold100GB) {
                forecast.daysTo100GB = Math.ceil(Math.log(threshold100GB / currentSize) / Math.log(1 + dailyGrowthRate))
            }
            if (currentSize < threshold500GB) {
                forecast.daysTo500GB = Math.ceil(Math.log(threshold500GB / currentSize) / Math.log(1 + dailyGrowthRate))
            }
        }

        return forecast
    }

    // Generate cleanup script for a table
    const generateCleanupScript = (tableName, config) => {
        const { retentionDays, batchSize, dateColumn, dateColumnType } = config

        let dateCondition
        switch (dateColumnType) {
            case 'epoch':
                dateCondition = `${tableName}.${dateColumn} < (UNIX_TIMESTAMP() - ${retentionDays} * 86400)`
                break
            case 'timestamp':
                dateCondition = `${tableName}.${dateColumn} < (NOW() - INTERVAL ${retentionDays} DAY)`
                break
            case 'datetime':
            default:
                dateCondition = `${tableName}.${dateColumn} < DATE_SUB(NOW(), INTERVAL ${retentionDays} DAY)`
        }

        return `-- =============================================================
-- CLEANUP SCRIPT FOR: ${tableName}
-- Generated: ${new Date().toISOString()}
-- Retention: ${retentionDays} days | Batch Size: ${batchSize.toLocaleString()} rows
-- =============================================================

-- STEP 1: SAFETY CHECK - Count rows to be deleted
-- Run this first to verify the scope of deletion
SELECT COUNT(*) AS rows_to_delete
FROM ${tableName}
WHERE ${dateCondition};

-- STEP 2: BACKUP VERIFICATION (Optional but recommended)
-- Create a sample backup of rows to be deleted for verification
-- SELECT * FROM ${tableName} WHERE ${dateCondition} LIMIT 100;

-- STEP 3: BATCHED DELETE LOOP
-- Run this in a loop until 0 rows are affected
-- This prevents long-running transactions and reduces lock contention

-- Single batch delete (run repeatedly until rows_affected = 0):
DELETE FROM ${tableName}
WHERE ${dateCondition}
LIMIT ${batchSize.toLocaleString()};

-- Check rows affected: SELECT ROW_COUNT();

-- =============================================================
-- AUTOMATED BATCH DELETE PROCEDURE (MySQL)
-- Use this for unattended cleanup
-- =============================================================

DELIMITER //
CREATE PROCEDURE cleanup_${tableName.toLowerCase().replace(/[^a-z0-9_]/g, '_')}()
BEGIN
    DECLARE rows_deleted INT DEFAULT 1;
    DECLARE total_deleted INT DEFAULT 0;
    DECLARE batch_count INT DEFAULT 0;

    -- Log start
    SELECT CONCAT('Starting cleanup of ${tableName} at ', NOW()) AS status;

    WHILE rows_deleted > 0 DO
        DELETE FROM ${tableName}
        WHERE ${dateCondition}
        LIMIT ${batchSize.toLocaleString()};

        SET rows_deleted = ROW_COUNT();
        SET total_deleted = total_deleted + rows_deleted;
        SET batch_count = batch_count + 1;

        -- Progress update every 10 batches
        IF batch_count % 10 = 0 THEN
            SELECT CONCAT('Batch ', batch_count, ': Deleted ', total_deleted, ' rows so far') AS progress;
        END IF;

        -- Small delay to reduce load (optional)
        -- DO SLEEP(0.1);
    END WHILE;

    SELECT CONCAT('Cleanup complete. Total rows deleted: ', total_deleted) AS result;
END //
DELIMITER ;

-- Run the procedure:
-- CALL cleanup_${tableName.toLowerCase().replace(/[^a-z0-9_]/g, '_')}();

-- Drop the procedure when done:
-- DROP PROCEDURE IF EXISTS cleanup_${tableName.toLowerCase().replace(/[^a-z0-9_]/g, '_')};

-- =============================================================
-- STEP 4: RECLAIM SPACE (Run after all deletes complete)
-- This rebuilds the table and reclaims disk space
-- WARNING: This locks the table - run during low traffic
-- =============================================================

-- For InnoDB tables:
OPTIMIZE TABLE ${tableName};

-- Alternative for large tables (online DDL):
-- ALTER TABLE ${tableName} ENGINE=InnoDB;

-- =============================================================
-- STEP 5: VERIFY CLEANUP
-- =============================================================
SELECT
    TABLE_NAME,
    TABLE_ROWS,
    ROUND(DATA_LENGTH / 1024 / 1024, 2) AS data_mb,
    ROUND(INDEX_LENGTH / 1024 / 1024, 2) AS index_mb,
    ROUND((DATA_LENGTH + INDEX_LENGTH) / 1024 / 1024, 2) AS total_mb
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = DATABASE()
AND TABLE_NAME = '${tableName}';
`
    }

    // Download script as file
    const downloadScript = (script, filename) => {
        const blob = new Blob([script], { type: 'text/sql' })
        const url = URL.createObjectURL(blob)
        const a = document.createElement('a')
        a.href = url
        a.download = filename
        document.body.appendChild(a)
        a.click()
        document.body.removeChild(a)
        URL.revokeObjectURL(url)
    }

    // Copy script to clipboard
    const copyToClipboard = async (text) => {
        try {
            await navigator.clipboard.writeText(text)
            success('Script copied to clipboard!')
        } catch (err) {
            showError('Failed to copy to clipboard')
        }
    }

    // Open cleanup modal for a table
    const openCleanupModal = (table) => {
        // Try to guess the date column based on common patterns
        const commonDateColumns = ['created_at', 'creation_date', 'update_time', 'timestamp', 'created', 'date_created', 'insert_time', 'log_time']
        let guessedColumn = 'created_at'
        let guessedType = 'datetime'

        // Check table name for hints about date column
        if (table.name.toLowerCase().includes('log')) {
            guessedColumn = 'update_time'
            guessedType = 'epoch'
        }

        setCleanupTable(table)
        setCleanupConfig({
            retentionDays: 90,
            batchSize: 10000,
            dateColumn: guessedColumn,
            dateColumnType: guessedType
        })
        setShowCleanupModal(true)
    }

    const toggleColumn = (columnName) => {
        setVisibleColumns(prev => ({
            ...prev,
            [columnName]: !prev[columnName]
        }))
    }

    // Render different views
    const handleSort = (column) => {
        if (sortBy === column) {
            setSortDirection(sortDirection === 'asc' ? 'desc' : 'asc')
        } else {
            setSortBy(column)
            setSortDirection('desc')
        }
    }

    const getSortedAndFilteredTables = () => {
        let sorted = [...tableStats]

        // Sort by selected column
        sorted.sort((a, b) => {
            let aVal, bVal

            switch (sortBy) {
                case 'name':
                    aVal = a.name
                    bVal = b.name
                    break
                case 'dataLength':
                    aVal = a.stats?.dataSize || 0
                    bVal = b.stats?.dataSize || 0
                    break
                case 'indexLength':
                    aVal = a.stats?.indexSize || 0
                    bVal = b.stats?.indexSize || 0
                    break
                case 'totalSize':
                    aVal = (a.stats?.dataSize || 0) + (a.stats?.indexSize || 0)
                    bVal = (b.stats?.dataSize || 0) + (b.stats?.indexSize || 0)
                    break
                case 'allocatedSize':
                    aVal = a.allocatedBytes || 0
                    bVal = b.allocatedBytes || 0
                    break
                case 'logicalSize':
                    aVal = (a.stats?.dataSize || 0) + (a.stats?.indexSize || 0)
                    bVal = (b.stats?.dataSize || 0) + (b.stats?.indexSize || 0)
                    break
                case 'bloatPercent':
                    aVal = a.bloatPercent || 0
                    bVal = b.bloatPercent || 0
                    break
                case 'rowCount':
                    aVal = a.stats?.rowCount || 0
                    bVal = b.stats?.rowCount || 0
                    break
                case 'growthRate':
                    aVal = a.growthRate || 0
                    bVal = b.growthRate || 0
                    break
                case 'growthBytes':
                    aVal = a.growthBytes || 0
                    bVal = b.growthBytes || 0
                    break
                default:
                    aVal = 0
                    bVal = 0
            }

            if (sortDirection === 'asc') {
                return aVal > bVal ? 1 : -1
            } else {
                return aVal < bVal ? 1 : -1
            }
        })

        // Take top N
        return sorted.slice(0, topN)
    }

    const renderGrowthIndicator = (growthRate) => {
        if (!growthRate || growthRate === 0) return <Minus size={16} className={styles.iconNeutral} />
        if (growthRate > 0) return <ArrowUp size={16} className={styles.iconPositive} />
        return <ArrowDown size={16} className={styles.iconNegative} />
    }

    const renderOverview = () => {
        const totalTables = allTables.length || tableStats.length

        // Compute insights from data instead of relying solely on backend anomalies
        const MIN_SIZE_FOR_BLOAT = 1024 * 1024 * 1024 // 1GB
        const fastGrowingTables = tableStats.filter(t => t.hasGrowthData && t.growthRate > 20).length
        const highBloatTables = tableStats.filter(t => {
            const totalSize = (t.stats?.dataSize || 0) + (t.stats?.indexSize || 0)
            return t.bloatPercent > 10 && totalSize >= MIN_SIZE_FOR_BLOAT
        }).length
        const veryLargeTables = tableStats.filter(t =>
            (t.stats?.dataSize || 0) + (t.stats?.indexSize || 0) > 10 * 1024 * 1024 * 1024
        ).length

        // Critical = very large tables OR very fast growing (>50%)
        const criticalCount = tableStats.filter(t => {
            const size = (t.stats?.dataSize || 0) + (t.stats?.indexSize || 0)
            return size > 50 * 1024 * 1024 * 1024 || (t.hasGrowthData && t.growthRate > 50)
        }).length

        // Warning = fast growing (>20%) or high bloat (>10% on large tables)
        const warningCount = fastGrowingTables + highBloatTables - criticalCount
        const activeAnomalies = Math.max(0, criticalCount + warningCount)

        const displayTables = getSortedAndFilteredTables()

        // Calculate full database size (all tables, not just top N)
        const fullDatabaseSize = tableStats.reduce((sum, t) =>
            sum + (t.stats?.dataSize || 0) + (t.stats?.indexSize || 0), 0
        )

        // Calculate allocated disk space from latest snapshots (includes bloat)
        const latestSnapshots = new Map()
        history.forEach(snapshot => {
            const existing = latestSnapshots.get(snapshot.tableName)
            if (!existing || new Date(snapshot.snapshotTimestamp) > new Date(existing.snapshotTimestamp)) {
                latestSnapshots.set(snapshot.tableName, snapshot)
            }
        })

        const allocatedDiskSpace = Array.from(latestSnapshots.values()).reduce((sum, snapshot) =>
            sum + (snapshot.allocatedBytes || snapshot.sizeBytes || 0), 0
        )

        const totalRows = displayTables.reduce((sum, t) =>
            sum + (t.stats?.rowCount || 0), 0
        )

        return (
            <div className={styles.overview}>
                <div className={styles.summaryCards}>
                    <div className={styles.card}>
                        <div className={styles.cardIcon} style={{ color: 'var(--color-primary)' }}>
                            <Database size={20} />
                        </div>
                        <div className={styles.cardContent}>
                            <div className={styles.cardLabel}>Total Tables</div>
                            <div className={styles.cardValue}>{totalTables}</div>
                        </div>
                    </div>

                    <div className={styles.card} onClick={() => setActiveView('anomalies')} style={{ cursor: 'pointer' }}>
                        <div className={styles.cardIcon} style={{ color: criticalCount > 0 ? 'var(--color-danger)' : activeAnomalies > 0 ? 'var(--color-warning)' : 'var(--color-accent)' }}>
                            <AlertTriangle size={20} />
                        </div>
                        <div className={styles.cardContent}>
                            <div className={styles.cardLabel}>Issues Detected</div>
                            <div className={styles.cardValue} style={{ color: criticalCount > 0 ? 'var(--color-danger)' : activeAnomalies > 0 ? 'var(--color-warning)' : 'var(--color-accent)' }}>
                                {activeAnomalies}
                            </div>
                            <div className={styles.cardSubtext}>
                                {activeAnomalies === 0
                                    ? 'No issues found'
                                    : `${fastGrowingTables} fast growing, ${highBloatTables} high bloat`
                                }
                            </div>
                        </div>
                    </div>

                    <div className={styles.card}>
                        <div className={styles.cardIcon} style={{ color: 'var(--color-accent)' }}>
                            <Database size={20} />
                        </div>
                        <div className={styles.cardContent}>
                            <div className={styles.cardLabel}>Database Size</div>
                            <div className={styles.cardValue}>{formatBytes(fullDatabaseSize)}</div>
                            <div className={styles.cardSubtext}>Logical size (data + indexes)</div>
                        </div>
                    </div>

                    <div className={styles.card}>
                        <div className={styles.cardIcon} style={{ color: 'var(--color-warning)' }}>
                            <HardDrive size={20} />
                        </div>
                        <div className={styles.cardContent}>
                            <div className={styles.cardLabel}>Database Size on Disk</div>
                            <div className={styles.cardValue}>{formatBytes(allocatedDiskSpace)}</div>
                            <div className={styles.cardSubtext}>
                                {loadingTableStats || tableStats.length === 0
                                    ? 'Calculating bloat...'
                                    : allocatedDiskSpace > fullDatabaseSize
                                        ? `+${formatBytes(allocatedDiskSpace - fullDatabaseSize)} bloat`
                                        : 'Allocated space with bloat'
                                }
                            </div>
                        </div>
                    </div>

                    <div className={styles.card}>
                        <div className={styles.cardIcon} style={{ color: 'var(--color-primary)' }}>
                            <Activity size={20} />
                        </div>
                        <div className={styles.cardContent}>
                            <div className={styles.cardLabel}>Total Rows (Top {topN})</div>
                            <div className={styles.cardValue}>
                                {formatNumber(totalRows)}
                            </div>
                        </div>
                    </div>
                </div>

                {/* Comprehensive Table View */}
                <div className={styles.section}>
                    <div className={styles.sectionHeader}>
                        <h3>Tables Overview</h3>
                        <div className={styles.controls}>
                            <select
                                value={topN}
                                onChange={(e) => setTopN(Number(e.target.value))}
                                className={styles.select}
                            >
                                <option value={10}>Top 10</option>
                                <option value={25}>Top 25</option>
                                <option value={50}>Top 50</option>
                                <option value={100}>Top 100</option>
                            </select>

                            <div className="columnSelectorContainer" style={{ position: 'relative' }}>
                                <button
                                    onClick={(e) => {
                                        e.stopPropagation()
                                        setShowColumnSelector(!showColumnSelector)
                                    }}
                                    className={styles.refreshButton}
                                    title="Select columns"
                                >
                                    <Settings size={14} />
                                </button>

                                {showColumnSelector && (
                                    <div className={styles.columnSelector}>
                                        <div className={styles.columnSelectorHeader}>Show Columns</div>
                                        <label className={styles.columnOption}>
                                            <input
                                                type="checkbox"
                                                checked={visibleColumns.name}
                                                onChange={() => toggleColumn('name')}
                                                disabled={true}
                                            />
                                            <span>Table Name</span>
                                        </label>
                                        <label className={styles.columnOption}>
                                            <input
                                                type="checkbox"
                                                checked={visibleColumns.engine}
                                                onChange={() => toggleColumn('engine')}
                                            />
                                            <span>Engine</span>
                                        </label>
                                        <label className={styles.columnOption}>
                                            <input
                                                type="checkbox"
                                                checked={visibleColumns.rowCount}
                                                onChange={() => toggleColumn('rowCount')}
                                            />
                                            <span>Rows</span>
                                        </label>
                                        <label className={styles.columnOption}>
                                            <input
                                                type="checkbox"
                                                checked={visibleColumns.dataSize}
                                                onChange={() => toggleColumn('dataSize')}
                                            />
                                            <span>Data Size</span>
                                        </label>
                                        <label className={styles.columnOption}>
                                            <input
                                                type="checkbox"
                                                checked={visibleColumns.indexSize}
                                                onChange={() => toggleColumn('indexSize')}
                                            />
                                            <span>Index Size</span>
                                        </label>
                                        <label className={styles.columnOption}>
                                            <input
                                                type="checkbox"
                                                checked={visibleColumns.totalSize}
                                                onChange={() => toggleColumn('totalSize')}
                                            />
                                            <span>Total Size</span>
                                        </label>
                                        <label className={styles.columnOption}>
                                            <input
                                                type="checkbox"
                                                checked={visibleColumns.allocatedSize}
                                                onChange={() => toggleColumn('allocatedSize')}
                                            />
                                            <span>Allocated Size</span>
                                        </label>
                                        <label className={styles.columnOption}>
                                            <input
                                                type="checkbox"
                                                checked={visibleColumns.logicalSize}
                                                onChange={() => toggleColumn('logicalSize')}
                                            />
                                            <span>Logical Size</span>
                                        </label>
                                        <label className={styles.columnOption}>
                                            <input
                                                type="checkbox"
                                                checked={visibleColumns.bloatPercent}
                                                onChange={() => toggleColumn('bloatPercent')}
                                            />
                                            <span>Bloat %</span>
                                        </label>
                                        <label className={styles.columnOption}>
                                            <input
                                                type="checkbox"
                                                checked={visibleColumns.growthRate}
                                                onChange={() => toggleColumn('growthRate')}
                                            />
                                            <span>Growth Rate</span>
                                        </label>
                                        <label className={styles.columnOption}>
                                            <input
                                                type="checkbox"
                                                checked={visibleColumns.growthBytes}
                                                onChange={() => toggleColumn('growthBytes')}
                                            />
                                            <span>Growth (Bytes)</span>
                                        </label>
                                        <label className={styles.columnOption}>
                                            <input
                                                type="checkbox"
                                                checked={visibleColumns.rowGrowthRate}
                                                onChange={() => toggleColumn('rowGrowthRate')}
                                            />
                                            <span>Row Growth %</span>
                                        </label>
                                        <label className={styles.columnOption}>
                                            <input
                                                type="checkbox"
                                                checked={visibleColumns.rowGrowth}
                                                onChange={() => toggleColumn('rowGrowth')}
                                            />
                                            <span>Row Growth</span>
                                        </label>
                                        <label className={styles.columnOption}>
                                            <input
                                                type="checkbox"
                                                checked={visibleColumns.collation}
                                                onChange={() => toggleColumn('collation')}
                                            />
                                            <span>Collation</span>
                                        </label>
                                    </div>
                                )}
                            </div>

                            {cacheAge !== null && (
                                <span className={styles.cacheIndicator} title={`Cache TTL: ${CACHE_TTL_MINUTES} minutes`}>
                                    {cacheAge === 0 ? '●' : `${cacheAge}m`}
                                </span>
                            )}

                            {/* Growth data loading indicator - only show while actually loading */}
                            {loadingTableStats && (
                                <div className={styles.loadingCell} title="Loading table statistics">
                                    <Loader2 size={12} />
                                    <span>Loading stats...</span>
                                </div>
                            )}

                            <button
                                onClick={() => loadTableStats(true)}
                                className={`${styles.refreshButton} ${loadingTableStats ? styles.loading : ''}`}
                                disabled={loadingTableStats}
                                title={loadingTableStats ? "Loading..." : "Force Refresh (ignores cache)"}
                            >
                                <RefreshCw size={14} className={loadingTableStats ? styles.spinning : ''} />
                            </button>
                        </div>
                    </div>

                    <div className={styles.tableContainer}>
                        <table className={styles.dataTable}>
                            <thead>
                                <tr>
                                    {visibleColumns.name && (
                                        <th onClick={() => handleSort('name')}>
                                            Table Name {sortBy === 'name' && (sortDirection === 'asc' ? <ChevronUp size={14} /> : <ChevronDown size={14} />)}
                                        </th>
                                    )}
                                    {visibleColumns.engine && <th>Engine</th>}
                                    {visibleColumns.rowCount && (
                                        <th onClick={() => handleSort('rowCount')}>
                                            Rows {sortBy === 'rowCount' && (sortDirection === 'asc' ? <ChevronUp size={14} /> : <ChevronDown size={14} />)}
                                        </th>
                                    )}
                                    {visibleColumns.dataSize && (
                                        <th onClick={() => handleSort('dataLength')}>
                                            Data Size {sortBy === 'dataLength' && (sortDirection === 'asc' ? <ChevronUp size={14} /> : <ChevronDown size={14} />)}
                                        </th>
                                    )}
                                    {visibleColumns.indexSize && (
                                        <th onClick={() => handleSort('indexLength')}>
                                            Index Size {sortBy === 'indexLength' && (sortDirection === 'asc' ? <ChevronUp size={14} /> : <ChevronDown size={14} />)}
                                        </th>
                                    )}
                                    {visibleColumns.totalSize && (
                                        <th onClick={() => handleSort('totalSize')}>
                                            Total Size {sortBy === 'totalSize' && (sortDirection === 'asc' ? <ChevronUp size={14} /> : <ChevronDown size={14} />)}
                                        </th>
                                    )}
                                    {visibleColumns.allocatedSize && (
                                        <th onClick={() => handleSort('allocatedSize')}>
                                            Allocated {sortBy === 'allocatedSize' && (sortDirection === 'asc' ? <ChevronUp size={14} /> : <ChevronDown size={14} />)}
                                        </th>
                                    )}
                                    {visibleColumns.logicalSize && (
                                        <th onClick={() => handleSort('logicalSize')}>
                                            Logical {sortBy === 'logicalSize' && (sortDirection === 'asc' ? <ChevronUp size={14} /> : <ChevronDown size={14} />)}
                                        </th>
                                    )}
                                    {visibleColumns.bloatPercent && (
                                        <th onClick={() => handleSort('bloatPercent')}>
                                            Bloat % {sortBy === 'bloatPercent' && (sortDirection === 'asc' ? <ChevronUp size={14} /> : <ChevronDown size={14} />)}
                                        </th>
                                    )}
                                    {visibleColumns.growthRate && (
                                        <th onClick={() => handleSort('growthRate')}>
                                            Growth Rate {sortBy === 'growthRate' && (sortDirection === 'asc' ? <ChevronUp size={14} /> : <ChevronDown size={14} />)}
                                        </th>
                                    )}
                                    {visibleColumns.growthBytes && (
                                        <th onClick={() => handleSort('growthBytes')}>
                                            Growth (Bytes) {sortBy === 'growthBytes' && (sortDirection === 'asc' ? <ChevronUp size={14} /> : <ChevronDown size={14} />)}
                                        </th>
                                    )}
                                    {visibleColumns.rowGrowthRate && (
                                        <th onClick={() => handleSort('rowGrowthRate')}>
                                            Row Growth % {sortBy === 'rowGrowthRate' && (sortDirection === 'asc' ? <ChevronUp size={14} /> : <ChevronDown size={14} />)}
                                        </th>
                                    )}
                                    {visibleColumns.rowGrowth && (
                                        <th onClick={() => handleSort('rowGrowth')}>
                                            Row Growth {sortBy === 'rowGrowth' && (sortDirection === 'asc' ? <ChevronUp size={14} /> : <ChevronDown size={14} />)}
                                        </th>
                                    )}
                                    {visibleColumns.collation && <th>Collation</th>}
                                </tr>
                            </thead>
                            <tbody>
                                {loadingTableStats ? (
                                    // Show skeleton rows while loading - enough to fill viewport
                                    <>
                                        {Array.from({ length: 25 }).map((_, i) => {
                                            const visibleColumnCount = Object.values(visibleColumns).filter(Boolean).length
                                            return <SkeletonTableRow key={i} columns={visibleColumnCount} />
                                        })}
                                    </>
                                ) : displayTables.length === 0 ? (
                                    <tr>
                                        <td colSpan={12} className={styles.emptyState}>
                                            No tables found
                                        </td>
                                    </tr>
                                ) : (
                                    displayTables.map((table) => {
                                        const totalSize = (table.stats?.dataSize || 0) + (table.stats?.indexSize || 0)
                                        return (
                                            <tr key={table.name} onClick={() => {
                                                setSelectedTable(table.name)
                                                setActiveView('trends')
                                            }} style={{ cursor: 'pointer' }}>
                                                {visibleColumns.name && (
                                                    <td className={styles.tableName}>
                                                        <Table size={14} />
                                                        <span>{table.name}</span>
                                                    </td>
                                                )}
                                                {visibleColumns.engine && (
                                                    <td>{table.stats?.engine || 'N/A'}</td>
                                                )}
                                                {visibleColumns.rowCount && (
                                                    <td>{formatNumber(table.stats?.rowCount || 0)}</td>
                                                )}
                                                {visibleColumns.dataSize && (
                                                    <td>{formatBytes(table.stats?.dataSize || 0)}</td>
                                                )}
                                                {visibleColumns.indexSize && (
                                                    <td>{formatBytes(table.stats?.indexSize || 0)}</td>
                                                )}
                                                {visibleColumns.totalSize && (
                                                    <td><strong>{formatBytes(totalSize)}</strong></td>
                                                )}
                                                {visibleColumns.allocatedSize && (
                                                    <td>
                                                        {table.allocatedBytes !== undefined && table.allocatedBytes !== null ? (
                                                            <span className={styles.cellFadeIn}>{formatBytes(table.allocatedBytes)}</span>
                                                        ) : (
                                                            <Skeleton width="60px" height="14px" />
                                                        )}
                                                    </td>
                                                )}
                                                {visibleColumns.logicalSize && (
                                                    <td>{formatBytes(totalSize)}</td>
                                                )}
                                                {visibleColumns.bloatPercent && (
                                                    <td>
                                                        {table.bloatPercent != null ? (
                                                            <span className={styles.cellFadeIn} style={{
                                                                color: table.bloatPercent > 50 ? 'var(--color-danger)' :
                                                                       table.bloatPercent > 20 ? 'var(--color-warning)' :
                                                                       'inherit',
                                                                fontWeight: table.bloatPercent > 20 ? '600' : 'normal'
                                                            }}>
                                                                {table.bloatPercent.toFixed(1)}%
                                                            </span>
                                                        ) : loadingTableStats ? (
                                                            <Skeleton width="50px" height="14px" />
                                                        ) : (
                                                            <span className={styles.naValue} title="Bloat data not available">—</span>
                                                        )}
                                                    </td>
                                                )}
                                                {visibleColumns.growthRate && (
                                                    <td>
                                                        {table.hasGrowthData ? (
                                                            <div className={`${styles.growthCell} ${styles.cellFadeIn}`}>
                                                                {renderGrowthIndicator(table.growthRate)}
                                                                <span className={table.growthRate > 0 ? styles.growthPositive : table.growthRate < 0 ? styles.growthNegative : ''}>
                                                                    {`${table.growthRate.toFixed(1)}%`}
                                                                </span>
                                                            </div>
                                                        ) : loadingTableStats ? (
                                                            <Skeleton width="60px" height="14px" />
                                                        ) : (
                                                            <span className={styles.naValue} title="Need 2+ snapshots to calculate growth">—</span>
                                                        )}
                                                    </td>
                                                )}
                                                {visibleColumns.growthBytes && (
                                                    <td>
                                                        {table.hasGrowthData ? (
                                                            <span className={`${styles.cellFadeIn} ${table.growthBytes > 0 ? styles.growthPositive : table.growthBytes < 0 ? styles.growthNegative : ''}`}>
                                                                {formatBytes(Math.abs(table.growthBytes))}
                                                            </span>
                                                        ) : loadingTableStats ? (
                                                            <Skeleton width="60px" height="14px" />
                                                        ) : (
                                                            <span className={styles.naValue} title="Need 2+ snapshots to calculate growth">—</span>
                                                        )}
                                                    </td>
                                                )}
                                                {visibleColumns.rowGrowthRate && (
                                                    <td>
                                                        {table.hasGrowthData ? (
                                                            <div className={`${styles.growthCell} ${styles.cellFadeIn}`}>
                                                                {renderGrowthIndicator(table.rowGrowthRate)}
                                                                <span className={table.rowGrowthRate > 0 ? styles.growthPositive : table.rowGrowthRate < 0 ? styles.growthNegative : ''}>
                                                                    {`${(table.rowGrowthRate || 0).toFixed(1)}%`}
                                                                </span>
                                                            </div>
                                                        ) : loadingTableStats ? (
                                                            <Skeleton width="60px" height="14px" />
                                                        ) : (
                                                            <span className={styles.naValue} title="Need 2+ snapshots to calculate growth">—</span>
                                                        )}
                                                    </td>
                                                )}
                                                {visibleColumns.rowGrowth && (
                                                    <td>
                                                        {table.hasGrowthData ? (
                                                            <span className={`${styles.cellFadeIn} ${table.rowGrowth > 0 ? styles.growthPositive : table.rowGrowth < 0 ? styles.growthNegative : ''}`}>
                                                                {(table.rowGrowth || 0).toLocaleString()}
                                                            </span>
                                                        ) : loadingTableStats ? (
                                                            <Skeleton width="60px" height="14px" />
                                                        ) : (
                                                            <span className={styles.naValue} title="Need 2+ snapshots to calculate growth">—</span>
                                                        )}
                                                    </td>
                                                )}
                                                {visibleColumns.collation && (
                                                    <td className={styles.collation}>{table.stats?.collation || 'N/A'}</td>
                                                )}
                                            </tr>
                                        )
                                    })
                                )}
                            </tbody>
                        </table>
                    </div>
                </div>
            </div>
        )
    }

    const renderTrends = () => {
        // Show loading indicator while history is loading
        if (loadingHistory && history.length === 0) {
            return (
                <div className={styles.trends}>
                    <div style={{ padding: '24px', display: 'flex', flexDirection: 'column', gap: '24px' }}>
                        <div style={{ padding: '20px', background: 'white', border: '1px solid #e5e7eb', borderRadius: '8px' }}>
                            <Skeleton width="200px" height="24px" />
                            <div style={{ marginTop: '24px', height: '300px' }}>
                                <Skeleton width="100%" height="100%" />
                            </div>
                        </div>
                        <div style={{ padding: '20px', background: 'white', border: '1px solid #e5e7eb', borderRadius: '8px' }}>
                            <Skeleton width="150px" height="24px" />
                            <div style={{ marginTop: '24px', height: '300px' }}>
                                <Skeleton width="100%" height="100%" />
                            </div>
                        </div>
                    </div>
                </div>
            )
        }

        // When "All Tables" is selected, show database-level aggregated metrics
        if (!selectedTable) {
            // Aggregate history data by date for database-level view
            const aggregatedByDate = new Map()
            history.forEach(snapshot => {
                const date = new Date(snapshot.snapshotTimestamp).toISOString().split('T')[0]
                if (!aggregatedByDate.has(date)) {
                    aggregatedByDate.set(date, {
                        timestamp: date,
                        totalSizeBytes: 0,
                        totalRowCount: 0,
                        tableCount: 0,
                        tables: new Set()
                    })
                }
                const agg = aggregatedByDate.get(date)
                // Only count each table once per day (use latest snapshot)
                if (!agg.tables.has(snapshot.tableName)) {
                    agg.tables.add(snapshot.tableName)
                    agg.totalSizeBytes += snapshot.sizeBytes || 0
                    agg.totalRowCount += snapshot.rowCount || 0
                    agg.tableCount++
                }
            })

            // Convert to array and sort by date
            const dbSizeData = Array.from(aggregatedByDate.values())
                .map(d => ({
                    timestamp: d.timestamp,
                    sizeGB: d.totalSizeBytes / (1024 * 1024 * 1024),
                    rowCount: d.totalRowCount,
                    tableCount: d.tableCount
                }))
                .sort((a, b) => new Date(a.timestamp) - new Date(b.timestamp))

            // Calculate growth between snapshots
            const dbGrowthData = dbSizeData.map((d, i) => {
                if (i === 0) return { ...d, growthPercent: 0, growthGB: 0 }
                const prev = dbSizeData[i - 1]
                const growthPercent = prev.sizeGB > 0 ? ((d.sizeGB - prev.sizeGB) / prev.sizeGB) * 100 : 0
                const growthGB = d.sizeGB - prev.sizeGB
                return { ...d, growthPercent, growthGB }
            })

            // Get top growing tables from tableStats
            const topGrowingTables = [...tableStats]
                .filter(t => t.hasGrowthData && t.growthBytes > 0)
                .sort((a, b) => b.growthBytes - a.growthBytes)
                .slice(0, 10)
                .map(t => ({
                    name: t.name.length > 25 ? t.name.substring(0, 22) + '...' : t.name,
                    fullName: t.name,
                    growthGB: t.growthBytes / (1024 * 1024 * 1024),
                    growthPercent: t.growthRate || 0
                }))

            if (dbSizeData.length === 0) {
                return (
                    <div className={styles.trends}>
                        <div className={styles.emptyState}>
                            <Database size={48} color="var(--color-secondary)" />
                            <h3>No Trend Data Available</h3>
                            <p>Capture snapshots over time to see database growth trends.</p>
                        </div>
                    </div>
                )
            }

            return (
                <div className={styles.trends}>
                    <div className={styles.section}>
                        <h3>Total Database Size Over Time</h3>
                        <ResponsiveContainer width="100%" height={300}>
                            <LineChart data={dbSizeData}>
                                <CartesianGrid strokeDasharray="3 3" />
                                <XAxis
                                    dataKey="timestamp"
                                    tickFormatter={(value) => new Date(value).toLocaleDateString()}
                                />
                                <YAxis
                                    label={{ value: 'Size (GB)', angle: -90, position: 'insideLeft' }}
                                    tickFormatter={(value) => value.toFixed(1)}
                                />
                                <Tooltip
                                    labelFormatter={(value) => new Date(value).toLocaleDateString()}
                                    formatter={(value, name) => {
                                        if (name === 'Size (GB)') return [value.toFixed(2) + ' GB', name]
                                        return [value, name]
                                    }}
                                />
                                <Legend />
                                <Line
                                    type="monotone"
                                    dataKey="sizeGB"
                                    stroke="#8884d8"
                                    name="Size (GB)"
                                    strokeWidth={2}
                                    dot={{ r: 4 }}
                                />
                            </LineChart>
                        </ResponsiveContainer>
                    </div>

                    <div className={styles.section}>
                        <h3>Daily Growth</h3>
                        <ResponsiveContainer width="100%" height={300}>
                            <BarChart data={dbGrowthData.slice(1)}>
                                <CartesianGrid strokeDasharray="3 3" />
                                <XAxis
                                    dataKey="timestamp"
                                    tickFormatter={(value) => new Date(value).toLocaleDateString()}
                                />
                                <YAxis
                                    label={{ value: 'Growth (GB)', angle: -90, position: 'insideLeft' }}
                                    tickFormatter={(value) => value.toFixed(2)}
                                />
                                <Tooltip
                                    labelFormatter={(value) => new Date(value).toLocaleDateString()}
                                    formatter={(value, name) => {
                                        if (name === 'Growth (GB)') return [value.toFixed(3) + ' GB', name]
                                        if (name === 'Growth %') return [value.toFixed(2) + '%', name]
                                        return [value, name]
                                    }}
                                />
                                <Legend />
                                <Bar
                                    dataKey="growthGB"
                                    fill="#82ca9d"
                                    name="Growth (GB)"
                                />
                            </BarChart>
                        </ResponsiveContainer>
                    </div>

                    <div className={styles.section}>
                        <h3>Total Row Count Over Time</h3>
                        <ResponsiveContainer width="100%" height={300}>
                            <LineChart data={dbSizeData}>
                                <CartesianGrid strokeDasharray="3 3" />
                                <XAxis
                                    dataKey="timestamp"
                                    tickFormatter={(value) => new Date(value).toLocaleDateString()}
                                />
                                <YAxis
                                    label={{ value: 'Row Count', angle: -90, position: 'insideLeft' }}
                                    tickFormatter={(value) => {
                                        if (value >= 1e9) return (value / 1e9).toFixed(1) + 'B'
                                        if (value >= 1e6) return (value / 1e6).toFixed(1) + 'M'
                                        if (value >= 1e3) return (value / 1e3).toFixed(1) + 'K'
                                        return value
                                    }}
                                />
                                <Tooltip
                                    labelFormatter={(value) => new Date(value).toLocaleDateString()}
                                    formatter={(value) => [value.toLocaleString(), 'Row Count']}
                                />
                                <Legend />
                                <Line
                                    type="monotone"
                                    dataKey="rowCount"
                                    stroke="#ffc658"
                                    name="Row Count"
                                    strokeWidth={2}
                                    dot={{ r: 4 }}
                                />
                            </LineChart>
                        </ResponsiveContainer>
                    </div>

                    {topGrowingTables.length > 0 && (
                        <div className={styles.section}>
                            <h3>Top Growing Tables (Last {timeRange} Days)</h3>
                            <ResponsiveContainer width="100%" height={300}>
                                <BarChart data={topGrowingTables} layout="vertical">
                                    <CartesianGrid strokeDasharray="3 3" />
                                    <XAxis
                                        type="number"
                                        tickFormatter={(value) => value.toFixed(2) + ' GB'}
                                    />
                                    <YAxis
                                        type="category"
                                        dataKey="name"
                                        width={150}
                                        tick={{ fontSize: 12 }}
                                    />
                                    <Tooltip
                                        formatter={(value, name, props) => {
                                            if (name === 'Growth') return [value.toFixed(3) + ' GB', 'Growth']
                                            return [value, name]
                                        }}
                                        labelFormatter={(label) => topGrowingTables.find(t => t.name === label)?.fullName || label}
                                    />
                                    <Legend />
                                    <Bar
                                        dataKey="growthGB"
                                        fill="#8884d8"
                                        name="Growth"
                                    />
                                </BarChart>
                            </ResponsiveContainer>
                        </div>
                    )}
                </div>
            )
        }

        // Single table selected - use trends API data
        if (!trends) return null

        const sizeData = trends.sizeOverTime || []
        const growthData = trends.growthOverTime || []
        const rowData = trends.rowCountOverTime || []

        return (
            <div className={styles.trends}>
                <div className={styles.section}>
                    <h3>Table Size Over Time</h3>
                    <ResponsiveContainer width="100%" height={300}>
                        <LineChart data={sizeData}>
                            <CartesianGrid strokeDasharray="3 3" />
                            <XAxis
                                dataKey="timestamp"
                                tickFormatter={(value) => new Date(value).toLocaleDateString()}
                            />
                            <YAxis label={{ value: 'Size (GB)', angle: -90, position: 'insideLeft' }} />
                            <Tooltip
                                labelFormatter={(value) => formatTimestamp(value)}
                                formatter={(value) => value.toFixed(2) + ' GB'}
                            />
                            <Legend />
                            <Line
                                type="monotone"
                                dataKey="sizeGB"
                                stroke="#8884d8"
                                name="Size (GB)"
                                strokeWidth={2}
                            />
                        </LineChart>
                    </ResponsiveContainer>
                </div>

                <div className={styles.section}>
                    <h3>Growth Rate Over Time</h3>
                    <ResponsiveContainer width="100%" height={300}>
                        <BarChart data={growthData}>
                            <CartesianGrid strokeDasharray="3 3" />
                            <XAxis
                                dataKey="timestamp"
                                tickFormatter={(value) => new Date(value).toLocaleDateString()}
                            />
                            <YAxis label={{ value: 'Growth %', angle: -90, position: 'insideLeft' }} />
                            <Tooltip
                                labelFormatter={(value) => formatTimestamp(value)}
                                formatter={(value) => value.toFixed(2) + '%'}
                            />
                            <Legend />
                            <Bar
                                dataKey="growthPercent"
                                fill="#82ca9d"
                                name="Growth %"
                            />
                        </BarChart>
                    </ResponsiveContainer>
                </div>

                <div className={styles.section}>
                    <h3>Row Count Over Time</h3>
                    <ResponsiveContainer width="100%" height={300}>
                        <LineChart data={rowData}>
                            <CartesianGrid strokeDasharray="3 3" />
                            <XAxis
                                dataKey="timestamp"
                                tickFormatter={(value) => new Date(value).toLocaleDateString()}
                            />
                            <YAxis label={{ value: 'Row Count', angle: -90, position: 'insideLeft' }} />
                            <Tooltip
                                labelFormatter={(value) => formatTimestamp(value)}
                                formatter={(value) => value.toLocaleString()}
                            />
                            <Legend />
                            <Line
                                type="monotone"
                                dataKey="rowCount"
                                stroke="#ffc658"
                                name="Row Count"
                                strokeWidth={2}
                            />
                        </LineChart>
                    </ResponsiveContainer>
                </div>
            </div>
        )
    }

    const renderAnomalies = () => {
        // Show loading indicator while data is loading
        if ((loadingAnomalies || loadingTableStats) && anomalies.length === 0 && tableStats.length === 0) {
            return (
                <div className={styles.anomalies}>
                    <div className={styles.statsRow}>
                        {Array.from({ length: 4 }).map((_, i) => (
                            <div key={i} className={styles.statCard}>
                                <Skeleton width="60px" height="32px" />
                                <Skeleton width="80px" height="14px" style={{ marginTop: '8px' }} />
                            </div>
                        ))}
                    </div>
                    <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(400px, 1fr))', gap: '20px', marginTop: '20px' }}>
                        {Array.from({ length: 4 }).map((_, i) => (
                            <div key={i} style={{ padding: '20px', background: 'white', border: '1px solid #e5e7eb', borderRadius: '8px' }}>
                                <Skeleton width="40%" height="20px" />
                                <div style={{ marginTop: '16px', display: 'flex', flexDirection: 'column', gap: '12px' }}>
                                    {Array.from({ length: 5 }).map((_, j) => (
                                        <Skeleton key={j} width="100%" height="40px" />
                                    ))}
                                </div>
                            </div>
                        ))}
                    </div>
                </div>
            )
        }

        // Compute insights from tableStats
        const largestTables = [...tableStats]
            .sort((a, b) => {
                const sizeA = (a.stats?.dataSize || 0) + (a.stats?.indexSize || 0)
                const sizeB = (b.stats?.dataSize || 0) + (b.stats?.indexSize || 0)
                return sizeB - sizeA
            })
            .slice(0, 10)

        const fastestGrowing = [...tableStats]
            .filter(t => t.hasGrowthData && t.growthRate > 0)
            .sort((a, b) => b.growthRate - a.growthRate)
            .slice(0, 10)

        const MIN_SIZE_FOR_BLOAT = 1024 * 1024 * 1024 // 1GB minimum - small tables with bloat aren't meaningful
        const highBloatTables = [...tableStats]
            .filter(t => {
                const totalSize = (t.stats?.dataSize || 0) + (t.stats?.indexSize || 0)
                return t.bloatPercent > 10 && totalSize >= MIN_SIZE_FOR_BLOAT
            })
            .sort((a, b) => (b.bloatBytes || 0) - (a.bloatBytes || 0)) // Sort by actual bloat bytes, not percentage
            .slice(0, 10)

        const highRowGrowth = [...tableStats]
            .filter(t => t.hasGrowthData && t.rowGrowthRate > 5) // >5% row growth
            .sort((a, b) => b.rowGrowthRate - a.rowGrowthRate)
            .slice(0, 10)

        // Calculate total database size and growth
        const totalSize = tableStats.reduce((sum, t) => sum + (t.stats?.dataSize || 0) + (t.stats?.indexSize || 0), 0)
        const totalBloat = tableStats.reduce((sum, t) => sum + (t.bloatBytes || 0), 0)
        const tablesOver1GB = tableStats.filter(t => (t.stats?.dataSize || 0) + (t.stats?.indexSize || 0) > 1024 * 1024 * 1024).length
        const tablesOver10GB = tableStats.filter(t => (t.stats?.dataSize || 0) + (t.stats?.indexSize || 0) > 10 * 1024 * 1024 * 1024).length

        // Identify tables that likely need retention/archival (log, audit, history, event tables)
        const retentionPatterns = /(_log|_logs|_audit|_history|_archive|_event|_events|_tracking|_activity|_changes|log_|audit_|history_)/i
        const tablesNeedingRetention = [...tableStats]
            .filter(t => {
                const size = (t.stats?.dataSize || 0) + (t.stats?.indexSize || 0)
                const matchesPattern = retentionPatterns.test(t.name)
                const isLargeOrGrowing = size > 500 * 1024 * 1024 || (t.hasGrowthData && t.growthRate > 10) // >500MB or >10% growth
                return matchesPattern && isLargeOrGrowing
            })
            .map(t => ({
                ...t,
                totalSize: (t.stats?.dataSize || 0) + (t.stats?.indexSize || 0),
                suggestion: t.growthRate > 20
                    ? 'Implement time-based partitioning and automated purge job'
                    : 'Add retention policy to delete records older than X days'
            }))
            .sort((a, b) => b.totalSize - a.totalSize)
            .slice(0, 10)

        // Cross-reference large/growing tables with slow queries
        const tablesInSlowQueries = [...tableStats]
            .filter(t => {
                const tableNameLower = t.name.toLowerCase()
                return slowQueryTables.has(tableNameLower)
            })
            .map(t => {
                const slowStats = slowQueryTables.get(t.name.toLowerCase())
                return {
                    ...t,
                    totalSize: (t.stats?.dataSize || 0) + (t.stats?.indexSize || 0),
                    slowQueryCount: slowStats?.queryCount || 0,
                    avgSlowTime: slowStats?.avgTime || 0,
                    maxSlowTime: slowStats?.maxTime || 0,
                    severity: slowStats?.severity || 'low'
                }
            })
            .filter(t => t.slowQueryCount > 0)
            .sort((a, b) => b.maxSlowTime - a.maxSlowTime)
            .slice(0, 10)

        // Generate actionable recommendations combining all factors
        const actionableInsights = []

        // Critical: Large tables in slow queries
        tablesInSlowQueries
            .filter(t => t.totalSize > 1024 * 1024 * 1024 && t.severity !== 'low')
            .forEach(t => {
                actionableInsights.push({
                    severity: 'critical',
                    table: t.name,
                    issue: `Large table (${formatBytes(t.totalSize)}) causing slow queries (${t.slowQueryCount} queries, max ${t.maxSlowTime.toFixed(1)}s)`,
                    recommendations: [
                        'Add appropriate indexes for frequently queried columns',
                        'Consider partitioning by date or key range',
                        'Review query patterns and optimize with EXPLAIN'
                    ]
                })
            })

        // Warning: Fast-growing tables that will become problematic
        fastestGrowing
            .filter(t => {
                const size = (t.stats?.dataSize || 0) + (t.stats?.indexSize || 0)
                return t.growthRate > 20 && size > 100 * 1024 * 1024 // >20% growth and >100MB
            })
            .forEach(t => {
                const size = (t.stats?.dataSize || 0) + (t.stats?.indexSize || 0)
                const isLogTable = retentionPatterns.test(t.name)
                actionableInsights.push({
                    severity: 'warning',
                    table: t.name,
                    issue: `Rapid growth (${t.growthRate.toFixed(1)}% in ${timeRange} days) - currently ${formatBytes(size)}`,
                    recommendations: isLogTable
                        ? [
                            'Implement data retention policy (e.g., DELETE WHERE created_at < NOW() - INTERVAL 90 DAY)',
                            'Consider time-based partitioning (PARTITION BY RANGE)',
                            'Archive old data to cold storage before deletion'
                        ]
                        : [
                            'Investigate cause of rapid growth',
                            'Consider archiving historical data',
                            'Plan for capacity scaling'
                        ]
                })
            })

        // Info: Tables that should have partitioning
        largestTables
            .filter(t => {
                const size = (t.stats?.dataSize || 0) + (t.stats?.indexSize || 0)
                return size > 10 * 1024 * 1024 * 1024 // >10GB
            })
            .forEach(t => {
                const size = (t.stats?.dataSize || 0) + (t.stats?.indexSize || 0)
                if (!actionableInsights.find(i => i.table === t.name)) {
                    actionableInsights.push({
                        severity: 'info',
                        table: t.name,
                        issue: `Very large table (${formatBytes(size)}) may benefit from optimization`,
                        recommendations: [
                            'Consider partitioning if queries filter by date/range',
                            'Review and optimize indexes',
                            'Consider archiving old data if applicable'
                        ]
                    })
                }
            })

        return (
            <div className={styles.anomalies}>
                {/* Summary Stats */}
                <div className={styles.statsRow}>
                    <div className={styles.statCard}>
                        <div className={styles.statValue}>{tablesOver1GB}</div>
                        <div className={styles.statLabel}>Tables &gt; 1 GB</div>
                    </div>
                    <div className={styles.statCard}>
                        <div className={styles.statValue} style={{ color: tablesOver10GB > 0 ? 'var(--color-danger)' : 'var(--color-accent)' }}>
                            {tablesOver10GB}
                        </div>
                        <div className={styles.statLabel}>Tables &gt; 10 GB</div>
                    </div>
                    <div className={styles.statCard}>
                        <div className={styles.statValue} style={{ color: highBloatTables.length > 0 ? 'var(--color-warning)' : 'var(--color-accent)' }}>
                            {highBloatTables.length}
                        </div>
                        <div className={styles.statLabel}>High Bloat (&gt;10%)</div>
                    </div>
                    <div className={styles.statCard}>
                        <div className={styles.statValue} style={{ color: fastestGrowing.length > 0 ? 'var(--color-primary)' : 'var(--color-secondary)' }}>
                            {fastestGrowing.length}
                        </div>
                        <div className={styles.statLabel}>Fast Growing</div>
                    </div>
                </div>

                {/* Insights Grid */}
                <div style={{ display: 'grid', gridTemplateColumns: 'repeat(auto-fit, minmax(400px, 1fr))', gap: '20px', marginTop: '20px' }}>
                    {/* Largest Tables */}
                    <div className={styles.insightCard}>
                        <div className={styles.insightHeader}>
                            <HardDrive size={18} />
                            <h4>Largest Tables</h4>
                        </div>
                        {largestTables.length === 0 ? (
                            <div className={styles.insightEmpty}>No table data available</div>
                        ) : (
                            <div className={styles.insightList}>
                                {largestTables.map((table, idx) => {
                                    const size = (table.stats?.dataSize || 0) + (table.stats?.indexSize || 0)
                                    const pct = totalSize > 0 ? (size / totalSize * 100) : 0
                                    return (
                                        <div key={table.name} className={styles.insightRow} onClick={() => {
                                            setSelectedTable(table.name)
                                            setActiveView('trends')
                                        }}>
                                            <span className={styles.insightRank}>{idx + 1}</span>
                                            <span className={styles.insightName} title={table.name}>
                                                {table.name.length > 30 ? table.name.substring(0, 27) + '...' : table.name}
                                            </span>
                                            <div className={styles.insightBar}>
                                                <div style={{ width: `${Math.min(pct * 2, 100)}%`, background: pct > 20 ? 'var(--color-danger)' : pct > 10 ? 'var(--color-warning)' : 'var(--color-primary)' }} />
                                            </div>
                                            <span className={styles.insightValue}>{formatBytes(size)}</span>
                                        </div>
                                    )
                                })}
                            </div>
                        )}
                    </div>

                    {/* Fastest Growing */}
                    <div className={styles.insightCard}>
                        <div className={styles.insightHeader}>
                            <TrendingUp size={18} />
                            <h4>Fastest Growing (Size)</h4>
                        </div>
                        {fastestGrowing.length === 0 ? (
                            <div className={styles.insightEmpty}>
                                {history.length < 2 ? 'Need more snapshots for growth data' : 'No significant growth detected'}
                            </div>
                        ) : (
                            <div className={styles.insightList}>
                                {fastestGrowing.map((table, idx) => (
                                    <div key={table.name} className={styles.insightRow} onClick={() => {
                                        setSelectedTable(table.name)
                                        setActiveView('trends')
                                    }}>
                                        <span className={styles.insightRank}>{idx + 1}</span>
                                        <span className={styles.insightName} title={table.name}>
                                            {table.name.length > 30 ? table.name.substring(0, 27) + '...' : table.name}
                                        </span>
                                        <div className={styles.insightBar}>
                                            <div style={{ width: `${Math.min(table.growthRate, 100)}%`, background: table.growthRate > 50 ? 'var(--color-danger)' : table.growthRate > 20 ? 'var(--color-warning)' : 'var(--color-accent)' }} />
                                        </div>
                                        <span className={styles.insightValue} style={{ color: table.growthRate > 50 ? 'var(--color-danger)' : 'inherit' }}>
                                            +{table.growthRate.toFixed(1)}%
                                        </span>
                                    </div>
                                ))}
                            </div>
                        )}
                    </div>

                    {/* High Bloat Tables */}
                    <div className={styles.insightCard}>
                        <div className={styles.insightHeader}>
                            <AlertTriangle size={18} style={{ color: 'var(--color-warning)' }} />
                            <h4>High Bloat Tables</h4>
                        </div>
                        {highBloatTables.length === 0 ? (
                            <div className={styles.insightEmpty}>No tables &gt; 1GB with bloat &gt; 10%</div>
                        ) : (
                            <div className={styles.insightList}>
                                {highBloatTables.map((table, idx) => (
                                    <div key={table.name} className={styles.insightRow} onClick={() => {
                                        setSelectedTable(table.name)
                                        setActiveView('trends')
                                    }}>
                                        <span className={styles.insightRank}>{idx + 1}</span>
                                        <span className={styles.insightName} title={table.name}>
                                            {table.name.length > 30 ? table.name.substring(0, 27) + '...' : table.name}
                                        </span>
                                        <div className={styles.insightBar}>
                                            <div style={{ width: `${Math.min(table.bloatPercent, 100)}%`, background: table.bloatPercent > 50 ? 'var(--color-danger)' : 'var(--color-warning)' }} />
                                        </div>
                                        <span className={styles.insightValue} style={{ color: table.bloatPercent > 50 ? 'var(--color-danger)' : 'var(--color-warning)' }}>
                                            {table.bloatPercent.toFixed(1)}% ({formatBytes(table.bloatBytes || 0)})
                                        </span>
                                    </div>
                                ))}
                            </div>
                        )}
                        {highBloatTables.length > 0 && (
                            <div className={styles.insightTip}>
                                💡 Consider running OPTIMIZE TABLE or vacuuming to reclaim space
                            </div>
                        )}
                    </div>

                    {/* Row Growth Spikes */}
                    <div className={styles.insightCard}>
                        <div className={styles.insightHeader}>
                            <Activity size={18} />
                            <h4>Row Growth Spikes (&gt;5%)</h4>
                        </div>
                        {highRowGrowth.length === 0 ? (
                            <div className={styles.insightEmpty}>
                                {history.length < 2 ? 'Need more snapshots for row growth data' : 'No unusual row growth detected'}
                            </div>
                        ) : (
                            <div className={styles.insightList}>
                                {highRowGrowth.map((table, idx) => (
                                    <div key={table.name} className={styles.insightRow} onClick={() => {
                                        setSelectedTable(table.name)
                                        setActiveView('trends')
                                    }}>
                                        <span className={styles.insightRank}>{idx + 1}</span>
                                        <span className={styles.insightName} title={table.name}>
                                            {table.name.length > 30 ? table.name.substring(0, 27) + '...' : table.name}
                                        </span>
                                        <div className={styles.insightBar}>
                                            <div style={{ width: `${Math.min(table.rowGrowthRate, 100)}%`, background: table.rowGrowthRate > 50 ? 'var(--color-danger)' : 'var(--color-accent)' }} />
                                        </div>
                                        <span className={styles.insightValue}>
                                            +{table.rowGrowthRate.toFixed(1)}% ({formatNumber(table.rowGrowth)} rows)
                                        </span>
                                    </div>
                                ))}
                            </div>
                        )}
                    </div>
                </div>

                {/* Tables Needing Retention Policy */}
                {tablesNeedingRetention.length > 0 && (
                    <div style={{ marginTop: '24px' }}>
                        <h3 style={{ marginBottom: '16px', display: 'flex', alignItems: 'center', gap: '8px' }}>
                            <Archive size={18} />
                            Tables Needing Retention Policy ({tablesNeedingRetention.length})
                        </h3>
                        <div className={styles.insightCard}>
                            <div className={styles.insightList}>
                                {tablesNeedingRetention.map((table, idx) => (
                                    <div key={table.name} className={styles.retentionRow}>
                                        <div className={styles.retentionInfo}>
                                            <span className={styles.insightRank}>{idx + 1}</span>
                                            <div>
                                                <span className={styles.retentionTableName} onClick={() => {
                                                    setSelectedTable(table.name)
                                                    setActiveView('trends')
                                                }}>{table.name}</span>
                                                <div className={styles.retentionMeta}>
                                                    <span>{formatBytes(table.totalSize)}</span>
                                                    {table.hasGrowthData && <span>• +{table.growthRate.toFixed(1)}% growth</span>}
                                                    <span>• {formatNumber(table.stats?.rowCount || 0)} rows</span>
                                                </div>
                                            </div>
                                        </div>
                                        <div className={styles.retentionActions}>
                                            <div className={styles.retentionSuggestion}>
                                                <Lightbulb size={14} />
                                                <span>{table.suggestion}</span>
                                            </div>
                                            <button
                                                className={styles.cleanupButton}
                                                onClick={(e) => {
                                                    e.stopPropagation()
                                                    openCleanupModal(table)
                                                }}
                                                title="Generate cleanup script"
                                            >
                                                <Trash2 size={14} />
                                                Cleanup Script
                                            </button>
                                        </div>
                                    </div>
                                ))}
                            </div>
                            <div className={styles.insightTip}>
                                💡 Click "Cleanup Script" to generate a ready-to-run retention script for any table above
                            </div>
                        </div>
                    </div>
                )}

                {/* Growth Forecasting - Tables that will hit size thresholds */}
                {(() => {
                    const tablesWithForecasts = [...tableStats]
                        .map(t => ({ ...t, forecast: calculateGrowthForecast(t) }))
                        .filter(t => t.forecast && t.forecast.dailyGrowthRate > 0.5) // Only show tables with >0.5% daily growth
                        .sort((a, b) => b.forecast.dailyGrowthRate - a.forecast.dailyGrowthRate)
                        .slice(0, 10)

                    if (tablesWithForecasts.length === 0) return null

                    return (
                        <div style={{ marginTop: '24px' }}>
                            <h3 style={{ marginBottom: '16px', display: 'flex', alignItems: 'center', gap: '8px' }}>
                                <Timer size={18} style={{ color: 'var(--color-primary)' }} />
                                Growth Forecasting ({tablesWithForecasts.length} tables)
                            </h3>
                            <div className={styles.insightCard}>
                                <table className={styles.forecastTable}>
                                    <thead>
                                        <tr>
                                            <th>Table</th>
                                            <th>Current Size</th>
                                            <th>Daily Growth</th>
                                            <th>30 Days</th>
                                            <th>90 Days</th>
                                            <th>Time to Double</th>
                                            <th>Action</th>
                                        </tr>
                                    </thead>
                                    <tbody>
                                        {tablesWithForecasts.map((table, idx) => {
                                            const f = table.forecast
                                            const isHighGrowth = f.dailyGrowthRate > 2
                                            const willHit100GB = f.daysTo100GB && f.daysTo100GB < 365
                                            return (
                                                <tr key={table.name} className={isHighGrowth ? styles.forecastWarning : ''}>
                                                    <td>
                                                        <span
                                                            className={styles.forecastTableName}
                                                            onClick={() => {
                                                                setSelectedTable(table.name)
                                                                setActiveView('trends')
                                                            }}
                                                        >
                                                            {table.name}
                                                        </span>
                                                    </td>
                                                    <td>{formatBytes(f.current)}</td>
                                                    <td style={{ color: isHighGrowth ? 'var(--color-danger)' : 'inherit' }}>
                                                        +{f.dailyGrowthRate.toFixed(2)}%/day
                                                    </td>
                                                    <td>{formatBytes(f.days30)}</td>
                                                    <td style={{ color: willHit100GB ? 'var(--color-warning)' : 'inherit' }}>
                                                        {formatBytes(f.days90)}
                                                    </td>
                                                    <td>
                                                        {f.daysToDouble ? (
                                                            <span style={{ color: f.daysToDouble < 90 ? 'var(--color-danger)' : 'inherit' }}>
                                                                {f.daysToDouble} days
                                                            </span>
                                                        ) : '—'}
                                                    </td>
                                                    <td>
                                                        <button
                                                            className={styles.forecastActionBtn}
                                                            onClick={() => openCleanupModal(table)}
                                                            title="Generate cleanup script"
                                                        >
                                                            <Trash2 size={12} />
                                                        </button>
                                                    </td>
                                                </tr>
                                            )
                                        })}
                                    </tbody>
                                </table>
                                <div className={styles.insightTip}>
                                    💡 Projections based on {timeRange}-day growth rate. High-growth tables should have retention policies to prevent runaway storage costs.
                                </div>
                            </div>
                        </div>
                    )
                })()}

                {/* Tables in Slow Queries */}
                {tablesInSlowQueries.length > 0 && (
                    <div style={{ marginTop: '24px' }}>
                        <h3 style={{ marginBottom: '16px', display: 'flex', alignItems: 'center', gap: '8px' }}>
                            <Zap size={18} style={{ color: 'var(--color-warning)' }} />
                            Large Tables in Slow Queries ({tablesInSlowQueries.length})
                        </h3>
                        <div className={styles.insightCard}>
                            <div className={styles.insightList}>
                                {tablesInSlowQueries.map((table, idx) => (
                                    <div key={table.name} className={styles.slowQueryRow}>
                                        <div className={styles.slowQueryInfo}>
                                            <span className={`${styles.insightRank} ${table.severity === 'critical' ? styles.severityCritical : table.severity === 'high' ? styles.severityHigh : ''}`}>
                                                {idx + 1}
                                            </span>
                                            <div>
                                                <span className={styles.retentionTableName} onClick={() => {
                                                    setSelectedTable(table.name)
                                                    setActiveView('trends')
                                                }}>{table.name}</span>
                                                <div className={styles.retentionMeta}>
                                                    <span>{formatBytes(table.totalSize)}</span>
                                                    <span>• {table.slowQueryCount} slow queries</span>
                                                    <span>• max {table.maxSlowTime.toFixed(1)}s</span>
                                                </div>
                                            </div>
                                        </div>
                                        <span className={`${styles.severityBadge} ${table.severity === 'critical' ? styles.severityCritical : table.severity === 'high' ? styles.severityHigh : table.severity === 'medium' ? styles.severityMedium : ''}`}>
                                            {table.severity}
                                        </span>
                                    </div>
                                ))}
                            </div>
                            <div className={styles.insightTip}>
                                💡 Consider adding indexes on frequently filtered columns and reviewing query execution plans
                            </div>
                        </div>
                    </div>
                )}

                {/* Actionable Recommendations */}
                {actionableInsights.length > 0 && (
                    <div style={{ marginTop: '24px' }}>
                        <h3 style={{ marginBottom: '16px', display: 'flex', alignItems: 'center', gap: '8px' }}>
                            <FileWarning size={18} style={{ color: 'var(--color-danger)' }} />
                            Actionable Recommendations ({actionableInsights.length})
                        </h3>
                        <div className={styles.recommendationsList}>
                            {actionableInsights.map((insight, idx) => (
                                <div key={`${insight.table}-${idx}`} className={`${styles.recommendationCard} ${styles[`severity${insight.severity.charAt(0).toUpperCase() + insight.severity.slice(1)}`]}`}>
                                    <div className={styles.recommendationHeader}>
                                        <span className={styles.recommendationTable} onClick={() => {
                                            setSelectedTable(insight.table)
                                            setActiveView('trends')
                                        }}>
                                            <Table size={16} />
                                            {insight.table}
                                        </span>
                                        <span className={`${styles.severityBadge} ${styles[`severity${insight.severity.charAt(0).toUpperCase() + insight.severity.slice(1)}`]}`}>
                                            {insight.severity}
                                        </span>
                                    </div>
                                    <div className={styles.recommendationIssue}>
                                        {insight.issue}
                                    </div>
                                    <div className={styles.recommendationActions}>
                                        <strong>Recommended Actions:</strong>
                                        <ul>
                                            {insight.recommendations.map((rec, i) => (
                                                <li key={i}>{rec}</li>
                                            ))}
                                        </ul>
                                    </div>
                                </div>
                            ))}
                        </div>
                    </div>
                )}

                {/* Backend-detected Anomalies (if any) */}
                {anomalies.length > 0 && (
                    <div style={{ marginTop: '24px' }}>
                        <h3 style={{ marginBottom: '16px', display: 'flex', alignItems: 'center', gap: '8px' }}>
                            <Bell size={18} />
                            Detected Anomalies ({anomalies.length})
                        </h3>
                        <div className={styles.anomaliesList}>
                            {anomalies.map(anomaly => (
                                <div key={anomaly.id} className={`${styles.anomalyCard} ${getSeverityClass(anomaly.severity)}`}>
                                    <div className={styles.anomalyHeader}>
                                        <div>
                                            <span className={styles.anomalyType}>
                                                {anomaly.anomalyType}
                                            </span>
                                            <span className={`${styles.severityBadge} ${getSeverityClass(anomaly.severity)}`}>
                                                {anomaly.severity}
                                            </span>
                                        </div>
                                        {!anomaly.acknowledged && (
                                            <button
                                                onClick={() => handleAcknowledge(anomaly.id)}
                                                className={styles.acknowledgeButton}
                                            >
                                                <CheckCircle size={16} />
                                                Acknowledge
                                            </button>
                                        )}
                                        {anomaly.acknowledged && (
                                            <span className={styles.acknowledged}>
                                                Acknowledged by {anomaly.acknowledgedBy}
                                            </span>
                                        )}
                                    </div>

                                    <div className={styles.anomalyTable}>{anomaly.tableName}</div>
                                    <div className={styles.anomalyDescription}>{anomaly.description}</div>

                                    <div className={styles.anomalyMetrics}>
                                        {anomaly.currentSizeBytes && (
                                            <div className={styles.metric}>
                                                <span className={styles.metricLabel}>Current Size:</span>
                                                <span className={styles.metricValue}>{formatBytes(anomaly.currentSizeBytes)}</span>
                                            </div>
                                        )}
                                        {anomaly.sizeGrowthBytes && (
                                            <div className={styles.metric}>
                                                <span className={styles.metricLabel}>Growth:</span>
                                                <span className={styles.metricValue}>
                                                    {formatBytes(anomaly.sizeGrowthBytes)}
                                                    {anomaly.sizeGrowthPercent && ` (${anomaly.sizeGrowthPercent.toFixed(1)}%)`}
                                                </span>
                                            </div>
                                        )}
                                        {anomaly.currentRowCount && (
                                            <div className={styles.metric}>
                                                <span className={styles.metricLabel}>Row Count:</span>
                                                <span className={styles.metricValue}>
                                                    {anomaly.currentRowCount.toLocaleString()}
                                                </span>
                                            </div>
                                        )}
                                        {anomaly.zScore && (
                                            <div className={styles.metric}>
                                                <span className={styles.metricLabel}>Z-Score:</span>
                                                <span className={styles.metricValue}>{anomaly.zScore.toFixed(2)}</span>
                                            </div>
                                        )}
                                    </div>

                                    <div className={styles.anomalyTime}>
                                        Detected: {formatTimestamp(anomaly.detectionTimestamp)}
                                    </div>
                                </div>
                            ))}
                        </div>
                    </div>
                )}

                {/* Show message if no data at all */}
                {tableStats.length === 0 && anomalies.length === 0 && (
                    <div className={styles.emptyState}>
                        <Database size={48} color="var(--color-secondary)" />
                        <h3>No Data Available</h3>
                        <p>Capture snapshots to see table growth insights and anomalies</p>
                    </div>
                )}
            </div>
        )
    }

    const renderConfiguration = () => {
        // Show loading indicator while config is loading
        if (loadingConfig && !config) {
            return (
                <div className={styles.configuration}>
                    <div style={{ maxWidth: '900px' }}>
                        {Array.from({ length: 3 }).map((_, i) => (
                            <div key={i} style={{ marginBottom: '32px', padding: '24px', background: 'white', border: '1px solid #e5e7eb', borderRadius: '8px' }}>
                                <Skeleton width="40%" height="20px" />
                                <div style={{ marginTop: '20px', display: 'grid', gridTemplateColumns: '1fr 1fr', gap: '20px' }}>
                                    <div>
                                        <Skeleton width="60%" height="14px" />
                                        <Skeleton width="100%" height="40px" borderRadius="6px" style={{ marginTop: '8px' }} />
                                        <Skeleton width="80%" height="12px" style={{ marginTop: '6px' }} />
                                    </div>
                                    <div>
                                        <Skeleton width="60%" height="14px" />
                                        <Skeleton width="100%" height="40px" borderRadius="6px" style={{ marginTop: '8px' }} />
                                        <Skeleton width="80%" height="12px" style={{ marginTop: '6px' }} />
                                    </div>
                                </div>
                            </div>
                        ))}
                    </div>
                </div>
            )
        }

        if (!config) return null

        return (
            <div className={styles.configuration}>
                <form onSubmit={handleSaveConfig} className={styles.configForm}>
                    <div className={styles.section}>
                        <h3>Percentage Growth Thresholds</h3>
                        <div className={styles.formGrid}>
                            <div className={styles.formGroup}>
                                <label>Warning Threshold (%)</label>
                                <input
                                    type="number"
                                    value={config.percentageGrowthWarning}
                                    onChange={(e) => setConfig({...config, percentageGrowthWarning: parseFloat(e.target.value)})}
                                    step="0.1"
                                    min="0"
                                />
                                <small>Alert when growth exceeds this percentage per hour</small>
                            </div>
                            <div className={styles.formGroup}>
                                <label>Critical Threshold (%)</label>
                                <input
                                    type="number"
                                    value={config.percentageGrowthCritical}
                                    onChange={(e) => setConfig({...config, percentageGrowthCritical: parseFloat(e.target.value)})}
                                    step="0.1"
                                    min="0"
                                />
                                <small>Critical alert threshold</small>
                            </div>
                        </div>
                    </div>

                    <div className={styles.section}>
                        <h3>Absolute Size Growth Thresholds</h3>
                        <div className={styles.formGrid}>
                            <div className={styles.formGroup}>
                                <label>Warning Threshold (GB)</label>
                                <input
                                    type="number"
                                    value={(config.absoluteGrowthWarningBytes / (1024 * 1024 * 1024)).toFixed(2)}
                                    onChange={(e) => setConfig({
                                        ...config,
                                        absoluteGrowthWarningBytes: Math.round(parseFloat(e.target.value) * 1024 * 1024 * 1024)
                                    })}
                                    step="0.1"
                                    min="0"
                                />
                                <small>Alert when size increases by this amount (GB) per hour</small>
                            </div>
                            <div className={styles.formGroup}>
                                <label>Critical Threshold (GB)</label>
                                <input
                                    type="number"
                                    value={(config.absoluteGrowthCriticalBytes / (1024 * 1024 * 1024)).toFixed(2)}
                                    onChange={(e) => setConfig({
                                        ...config,
                                        absoluteGrowthCriticalBytes: Math.round(parseFloat(e.target.value) * 1024 * 1024 * 1024)
                                    })}
                                    step="0.1"
                                    min="0"
                                />
                                <small>Critical alert threshold</small>
                            </div>
                        </div>
                    </div>

                    <div className={styles.section}>
                        <h3>Row Count Spike Thresholds</h3>
                        <div className={styles.formGrid}>
                            <div className={styles.formGroup}>
                                <label>Warning Threshold (rows)</label>
                                <input
                                    type="number"
                                    value={config.rowSpikeWarning}
                                    onChange={(e) => setConfig({...config, rowSpikeWarning: parseInt(e.target.value)})}
                                    step="100000"
                                    min="0"
                                />
                                <small>Alert when row count increases by this amount per hour</small>
                            </div>
                            <div className={styles.formGroup}>
                                <label>Critical Threshold (rows)</label>
                                <input
                                    type="number"
                                    value={config.rowSpikeCritical}
                                    onChange={(e) => setConfig({...config, rowSpikeCritical: parseInt(e.target.value)})}
                                    step="100000"
                                    min="0"
                                />
                                <small>Critical alert threshold</small>
                            </div>
                        </div>
                    </div>

                    <div className={styles.section}>
                        <h3>Statistical Anomaly Detection</h3>
                        <div className={styles.formGroup}>
                            <label>Z-Score Threshold</label>
                            <input
                                type="number"
                                value={config.zScoreThreshold}
                                onChange={(e) => setConfig({...config, zScoreThreshold: parseFloat(e.target.value)})}
                                step="0.1"
                                min="1"
                                max="5"
                            />
                            <small>Standard deviations from mean (3.0 = 99.7% confidence interval)</small>
                        </div>
                    </div>

                    <div className={styles.section}>
                        <div style={{ display: 'flex', alignItems: 'center', gap: '8px', marginBottom: '15px' }}>
                            <Clock size={20} className={styles.sectionIcon} />
                            <h3 style={{ margin: 0 }}>Snapshot Collection Cadence</h3>
                        </div>
                        <div className={styles.cadenceOptions}>
                            <label className={`${styles.cadenceOption} ${collectionCadence === 'MINUTE' ? styles.cadenceOptionActive : ''}`}>
                                <input
                                    type="radio"
                                    name="cadence"
                                    value="MINUTE"
                                    checked={collectionCadence === 'MINUTE'}
                                    onChange={(e) => setCollectionCadence(e.target.value)}
                                />
                                <div className={styles.cadenceContent}>
                                    <div className={styles.cadenceTitle}>Every Minute</div>
                                    <div className={styles.cadenceDescription}>
                                        Captures snapshots every minute. Best for testing and development.
                                    </div>
                                    <div className={styles.cadenceWarning}>
                                        ⚠️ High database load - not recommended for production
                                    </div>
                                </div>
                            </label>

                            <label className={`${styles.cadenceOption} ${collectionCadence === 'HOUR' ? styles.cadenceOptionActive : ''}`}>
                                <input
                                    type="radio"
                                    name="cadence"
                                    value="HOUR"
                                    checked={collectionCadence === 'HOUR'}
                                    onChange={(e) => setCollectionCadence(e.target.value)}
                                />
                                <div className={styles.cadenceContent}>
                                    <div className={styles.cadenceTitle}>Every Hour (Recommended)</div>
                                    <div className={styles.cadenceDescription}>
                                        Captures snapshots hourly. Balanced approach for most production workloads.
                                    </div>
                                    <div className={styles.cadenceSuccess}>
                                        ✓ Recommended for production environments
                                    </div>
                                </div>
                            </label>

                            <label className={`${styles.cadenceOption} ${collectionCadence === 'DAY' ? styles.cadenceOptionActive : ''}`}>
                                <input
                                    type="radio"
                                    name="cadence"
                                    value="DAY"
                                    checked={collectionCadence === 'DAY'}
                                    onChange={(e) => setCollectionCadence(e.target.value)}
                                />
                                <div className={styles.cadenceContent}>
                                    <div className={styles.cadenceTitle}>Every Day</div>
                                    <div className={styles.cadenceDescription}>
                                        Captures snapshots daily. Minimal database load but less granular insights.
                                    </div>
                                    <div className={styles.cadenceInfo}>
                                        ℹ️ Good for long-term trend analysis
                                    </div>
                                </div>
                            </label>
                        </div>
                    </div>

                    <div className={styles.section}>
                        <h3>Snapshot History</h3>
                        <div className={styles.snapshotHistory}>
                            {capturingSnapshot && (
                                <div className={styles.snapshotInProgress}>
                                    <Activity size={16} className={styles.spinning} />
                                    <span>Snapshot capture in progress...</span>
                                </div>
                            )}
                            <div className={styles.historyList}>
                                {history.length > 0 ? (
                                    <>
                                        <div className={styles.historyHeader}>
                                            <span>Latest Snapshots</span>
                                            <span>Showing {Math.min(10, history.length)} of {history.length} snapshots</span>
                                        </div>
                                        <div className={styles.historyItems}>
                                            {/* Sort by timestamp descending and show latest 10 */}
                                            {[...history]
                                                .sort((a, b) => new Date(b.snapshotTimestamp) - new Date(a.snapshotTimestamp))
                                                .slice(0, 10)
                                                .map((snapshot, idx) => (
                                                    <div key={`${snapshot.tableName}-${snapshot.snapshotTimestamp}-${idx}`} className={styles.historyItem}>
                                                        <div className={styles.historyTime}>
                                                            {formatTimestamp(snapshot.snapshotTimestamp)}
                                                        </div>
                                                        <div className={styles.historyDetails}>
                                                            <span>{snapshot.tableName || 'All tables'}</span>
                                                            <span className={styles.historySize}>
                                                                {formatBytes(snapshot.sizeBytes)}
                                                            </span>
                                                        </div>
                                                    </div>
                                                ))}
                                        </div>
                                    </>
                                ) : (
                                    <div className={styles.emptyHistory}>
                                        <Database size={32} />
                                        <p>No snapshots captured yet</p>
                                        <small>Click "Capture Now" to create your first snapshot</small>
                                    </div>
                                )}
                            </div>
                        </div>
                    </div>

                    <div className={styles.section}>
                        <h3>Notification Settings</h3>
                        <div className={styles.formGroup}>
                            <label>
                                <input
                                    type="checkbox"
                                    checked={config.isEnabled}
                                    onChange={(e) => setConfig({...config, isEnabled: e.target.checked})}
                                />
                                Enable Growth Monitoring Alerts
                            </label>
                        </div>
                    </div>

                    <div className={styles.formActions}>
                        <button type="submit" className={styles.saveButton} disabled={saving}>
                            {saving ? 'Saving...' : 'Save Configuration'}
                        </button>
                        <button
                            type="button"
                            onClick={() => setConfig(fetchedConfig)}
                            className={styles.cancelButton}
                        >
                            Reset
                        </button>
                    </div>
                </form>
            </div>
        )
    }

    return (
        <div className={styles.container}>
            {/* Header */}
            <div className={styles.header}>
                <div>
                    <h1>Table Growth Monitoring</h1>
                    <p className={styles.subtitle}>Automatic anomaly detection and growth tracking</p>
                </div>
                <div className={styles.headerControls}>
                    <div className={styles.timeRangeSelector}>
                        <Calendar size={16} />
                        <select value={timeRange} onChange={(e) => setTimeRange(Number(e.target.value))}>
                            <option value={7}>Last 7 days</option>
                            <option value={14}>Last 14 days</option>
                            <option value={30}>Last 30 days</option>
                            <option value={60}>Last 60 days</option>
                            <option value={90}>Last 90 days</option>
                        </select>
                    </div>

                    <div className={styles.tableSelector}>
                        <Database size={16} />
                        <select
                            value={selectedTable || ''}
                            onChange={(e) => setSelectedTable(e.target.value || null)}
                        >
                            <option value="">All Tables</option>
                            {allTables.map(table => (
                                <option key={table} value={table}>{table}</option>
                            ))}
                        </select>
                    </div>

                    <button onClick={loadData} className={styles.refreshButton} title="Refresh">
                        <RefreshCw size={14} />
                    </button>

                    <button
                        onClick={captureManualSnapshot}
                        className={`${styles.captureButton} ${capturingSnapshot ? styles.loading : ''}`}
                        disabled={capturingSnapshot}
                        title={capturingSnapshot ? "Capturing snapshots for all tables (may take several minutes)..." : "Capture Now"}
                    >
                        <Activity size={14} className={capturingSnapshot ? styles.spinning : ''} />
                    </button>
                </div>
            </div>

            {/* View Tabs */}
            <div className={styles.viewTabs}>
                <button
                    className={activeView === 'overview' ? styles.activeTab : ''}
                    onClick={() => setActiveView('overview')}
                >
                    {loadingTableStats ? <Loader2 size={14} className={styles.spinning} /> : <Activity size={14} />}
                    Overview
                </button>
                <button
                    className={activeView === 'trends' ? styles.activeTab : ''}
                    onClick={() => setActiveView('trends')}
                >
                    {loadingHistory ? <Loader2 size={14} className={styles.spinning} /> : <TrendingUp size={14} />}
                    Trends
                </button>
                <button
                    className={activeView === 'anomalies' ? styles.activeTab : ''}
                    onClick={() => setActiveView('anomalies')}
                >
                    {loadingAnomalies ? <Loader2 size={14} className={styles.spinning} /> : <AlertTriangle size={14} />}
                    Anomalies {statistics?.unacknowledged > 0 && (
                        <span className={styles.badge}>{statistics.unacknowledged}</span>
                    )}
                </button>
                <button
                    className={activeView === 'configuration' ? styles.activeTab : ''}
                    onClick={() => setActiveView('configuration')}
                >
                    {loadingConfig ? <Loader2 size={14} className={styles.spinning} /> : <Settings size={14} />}
                    Configuration
                </button>
            </div>

            {/* Content - Always render, each tab manages its own loading state */}
            {error && (
                <div className={styles.errorState}>
                    <AlertTriangle size={48} color="var(--color-danger)" />
                    <h3>Error Loading Data</h3>
                    <p>{error}</p>
                </div>
            )}

            {!error && (
                <div className={styles.content}>
                    {activeView === 'overview' && renderOverview()}
                    {activeView === 'trends' && renderTrends()}
                    {activeView === 'anomalies' && renderAnomalies()}
                    {activeView === 'configuration' && renderConfiguration()}
                </div>
            )}

            {/* Cleanup Script Modal */}
            {showCleanupModal && cleanupTable && (
                <div className={styles.modalOverlay} onClick={() => setShowCleanupModal(false)}>
                    <div className={styles.cleanupModal} onClick={(e) => e.stopPropagation()}>
                        <div className={styles.modalHeader}>
                            <h2>
                                <Trash2 size={20} />
                                Generate Cleanup Script
                            </h2>
                            <button className={styles.modalClose} onClick={() => setShowCleanupModal(false)}>
                                <X size={20} />
                            </button>
                        </div>

                        <div className={styles.modalBody}>
                            <div className={styles.modalTableInfo}>
                                <div className={styles.modalTableName}>
                                    <Table size={16} />
                                    {cleanupTable.name}
                                </div>
                                <div className={styles.modalTableStats}>
                                    <span>{formatBytes((cleanupTable.stats?.dataSize || 0) + (cleanupTable.stats?.indexSize || 0))}</span>
                                    <span>•</span>
                                    <span>{formatNumber(cleanupTable.stats?.rowCount || 0)} rows</span>
                                    {cleanupTable.hasGrowthData && (
                                        <>
                                            <span>•</span>
                                            <span style={{ color: cleanupTable.growthRate > 20 ? 'var(--color-danger)' : 'inherit' }}>
                                                +{cleanupTable.growthRate.toFixed(1)}% growth
                                            </span>
                                        </>
                                    )}
                                </div>
                            </div>

                            <div className={styles.modalForm}>
                                <div className={styles.formRow}>
                                    <div className={styles.formGroup}>
                                        <label>Date Column</label>
                                        <input
                                            type="text"
                                            value={cleanupConfig.dateColumn}
                                            onChange={(e) => setCleanupConfig({ ...cleanupConfig, dateColumn: e.target.value })}
                                            placeholder="created_at"
                                        />
                                        <small>Column used to determine record age</small>
                                    </div>
                                    <div className={styles.formGroup}>
                                        <label>Column Type</label>
                                        <select
                                            value={cleanupConfig.dateColumnType}
                                            onChange={(e) => setCleanupConfig({ ...cleanupConfig, dateColumnType: e.target.value })}
                                        >
                                            <option value="datetime">DATETIME</option>
                                            <option value="timestamp">TIMESTAMP</option>
                                            <option value="epoch">UNIX Timestamp (epoch)</option>
                                        </select>
                                        <small>Data type of the date column</small>
                                    </div>
                                </div>

                                <div className={styles.formRow}>
                                    <div className={styles.formGroup}>
                                        <label>Retention Period (days)</label>
                                        <input
                                            type="number"
                                            value={cleanupConfig.retentionDays}
                                            onChange={(e) => setCleanupConfig({ ...cleanupConfig, retentionDays: parseInt(e.target.value) || 90 })}
                                            min="1"
                                        />
                                        <small>Delete records older than this many days</small>
                                    </div>
                                    <div className={styles.formGroup}>
                                        <label>Batch Size</label>
                                        <input
                                            type="number"
                                            value={cleanupConfig.batchSize}
                                            onChange={(e) => setCleanupConfig({ ...cleanupConfig, batchSize: parseInt(e.target.value) || 10000 })}
                                            min="100"
                                            step="1000"
                                        />
                                        <small>Rows to delete per batch (prevents long locks)</small>
                                    </div>
                                </div>
                            </div>

                            <div className={styles.scriptPreview}>
                                <div className={styles.scriptHeader}>
                                    <span>Generated Script Preview</span>
                                    <div className={styles.scriptActions}>
                                        <button
                                            onClick={() => copyToClipboard(generateCleanupScript(cleanupTable.name, cleanupConfig))}
                                            title="Copy to clipboard"
                                        >
                                            <Copy size={14} />
                                            Copy
                                        </button>
                                        <button
                                            onClick={() => downloadScript(
                                                generateCleanupScript(cleanupTable.name, cleanupConfig),
                                                `cleanup_${cleanupTable.name}_${new Date().toISOString().split('T')[0]}.sql`
                                            )}
                                            title="Download as .sql file"
                                        >
                                            <Download size={14} />
                                            Download
                                        </button>
                                    </div>
                                </div>
                                <pre className={styles.scriptCode}>
                                    {generateCleanupScript(cleanupTable.name, cleanupConfig)}
                                </pre>
                            </div>
                        </div>

                        <div className={styles.modalFooter}>
                            <button className={styles.modalCancelBtn} onClick={() => setShowCleanupModal(false)}>
                                Cancel
                            </button>
                            <button
                                className={styles.modalPrimaryBtn}
                                onClick={() => {
                                    downloadScript(
                                        generateCleanupScript(cleanupTable.name, cleanupConfig),
                                        `cleanup_${cleanupTable.name}_${new Date().toISOString().split('T')[0]}.sql`
                                    )
                                    setShowCleanupModal(false)
                                }}
                            >
                                <Download size={14} />
                                Download Script
                            </button>
                        </div>
                    </div>
                </div>
            )}

            {/* Toast Notifications */}
            <ToastContainer toasts={toasts} removeToast={removeToast} />
        </div>
    )
}
