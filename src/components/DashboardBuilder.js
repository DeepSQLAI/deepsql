'use client'

import { useState, useEffect, useCallback, useRef, lazy, Suspense } from 'react'
import { RefreshCw, X, TrendingUp, DollarSign, Users, Database, Activity, AlertCircle, Edit2, Check, XCircle, Save } from 'lucide-react'
import { BarChart, Bar, LineChart, Line, PieChart, Pie, Cell, AreaChart, Area, XAxis, YAxis, CartesianGrid, Tooltip, Legend, ResponsiveContainer } from 'recharts'
import { queryAPI } from '@/lib/api/client'
import { renderInput } from './DashboardInputs'
import styles from './DashboardBuilder.module.css'

// Lazy load Monaco Editor for better performance
const Editor = lazy(() => import('@monaco-editor/react'))

export default function DashboardBuilder({ connectionId, dashboardConfig, onRefresh, onClear, setDashboardConfig, onSave }) {
    const [dashboardData, setDashboardData] = useState(null)
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState(null)
    const [inputValues, setInputValues] = useState({})
    const [isEditing, setIsEditing] = useState(false)
    const [editConfig, setEditConfig] = useState('')
    const [editError, setEditError] = useState(null)
    const previewReloadTimeoutRef = useRef(null)

    // Debug logging
    useEffect(() => {
        console.log('DashboardBuilder render:', { connectionId, hasConfig: !!dashboardConfig, hasData: !!dashboardData, loading, error })
    }, [connectionId, dashboardConfig, dashboardData, loading, error])

    // Initialize input values from dashboard config
    // Reset input values whenever config changes (including from editor edits)
    useEffect(() => {
        if (dashboardConfig?.inputs) {
            const initialValues = {}
            dashboardConfig.inputs.forEach(input => {
                if (input.defaultValue !== undefined) {
                    initialValues[input.name] = input.defaultValue
                } else if (input.type === 'checkbox' || input.type === 'switch' || input.type === 'toggle') {
                    initialValues[input.name] = false
                } else if (input.type === 'dateRange') {
                    initialValues[input.name] = { startDate: null, endDate: null }
                } else {
                    initialValues[input.name] = null
                }
            })
            setInputValues(initialValues)
        } else {
            // Clear input values if no config
            setInputValues({})
        }
    }, [dashboardConfig])

    // Replace placeholders in queries with actual input values
    const substituteQueryParams = useCallback((query) => {
        if (!query) return query
        
        let substitutedQuery = query
        
        // Replace {{inputName}} with actual values
        Object.keys(inputValues).forEach(inputName => {
            const value = inputValues[inputName]
            let replacement = ''
            let shouldSkipGenericReplacement = false
            
            if (value === null || value === undefined || value === '') {
                // For empty values, try to use a safe default - return 1=1 for WHERE clauses
                // This allows queries to work even if input is not set
                replacement = '1=1'
            } else if (typeof value === 'object' && value.startDate && value.endDate) {
                // Date range - format dates properly (ensure YYYY-MM-DD format)
                const startDate = String(value.startDate).trim()
                const endDate = String(value.endDate).trim()
                
                // Validate date format (should be YYYY-MM-DD)
                const dateRegex = /^\d{4}-\d{2}-\d{2}$/
                if (!dateRegex.test(startDate) || !dateRegex.test(endDate)) {
                    console.warn(`Invalid date format for ${inputName}: startDate=${startDate}, endDate=${endDate}`)
                    replacement = '1=1' // Fallback to safe default
                } else {
                    // Check if BETWEEN is already in the query (case-insensitive, with proper pattern matching)
                    const betweenPattern = new RegExp(`BETWEEN\\s+\\{\\{${inputName}\\}\\}`, 'gi')
                    if (betweenPattern.test(substitutedQuery)) {
                        // Replace BETWEEN {{inputName}} with BETWEEN 'startDate' AND 'endDate'
                        substitutedQuery = substitutedQuery.replace(betweenPattern, `BETWEEN '${startDate}' AND '${endDate}'`)
                        shouldSkipGenericReplacement = true // Already replaced, skip generic replacement
                    } else {
                        // If BETWEEN not found, assume it's a WHERE clause and provide both dates
                        replacement = `'${startDate}' AND '${endDate}'`
                    }
                }
            } else if (value && typeof value === 'object' && ('startDate' in value || 'endDate' in value)) {
                // Incomplete date range (no default, or the user cleared a side).
                // Without this branch it would stringify to '[object Object]' and
                // break the SQL. Substitute a neutral always-true filter instead.
                const betweenPattern = new RegExp(`BETWEEN\\s+\\{\\{${inputName}\\}\\}`, 'gi')
                if (betweenPattern.test(substitutedQuery)) {
                    substitutedQuery = substitutedQuery.replace(betweenPattern, `BETWEEN '0001-01-01' AND '9999-12-31'`)
                    shouldSkipGenericReplacement = true
                } else {
                    replacement = '1=1'
                }
            } else if (typeof value === 'boolean') {
                replacement = value ? 'TRUE' : 'FALSE'
            } else if (typeof value === 'number') {
                replacement = value.toString()
            } else {
                // String value - escape single quotes for SQL
                const escapedValue = String(value).replace(/'/g, "''")
                replacement = `'${escapedValue}'`
            }
            
            // Replace {{inputName}} patterns (case-insensitive) - but skip if already replaced above
            if (!shouldSkipGenericReplacement) {
                const pattern = new RegExp(`\\{\\{${inputName}\\}\\}`, 'gi')
                substitutedQuery = substitutedQuery.replace(pattern, replacement)
            }
        })
        
        return substitutedQuery
    }, [inputValues])

    // Load dashboard data function - defined before useEffect that uses it
    const loadDashboardData = useCallback(async (config) => {
        if (!config || !connectionId) return

        setLoading(true)
        setError(null)

        const data = {
            metrics: [],
            tables: [],
            charts: []
        }

        try {
            // Execute metric queries
            for (const metric of config.metrics || []) {
                if (metric.query) {
                    try {
                        const substitutedQuery = substituteQueryParams(metric.query)
                        const result = await queryAPI.executeQuery(connectionId, substitutedQuery, 1)
                        
                        if (result.success && result.result && result.result.rows && result.result.rows.length > 0) {
                            const columns = result.result.columns || []
                            const row = result.result.rows[0]
                            const valueIndex = columns.findIndex(col => 
                                col.toLowerCase() === 'value' || 
                                col.toLowerCase() === metric.query.match(/as\s+(\w+)/i)?.[1]?.toLowerCase()
                            )
                            const metricValue = valueIndex >= 0 && row.length > valueIndex && row[valueIndex] !== null && row[valueIndex] !== undefined
                                ? (() => {
                                    const cellValue = row[valueIndex]
                                    if (cellValue === null || cellValue === undefined) {
                                        return 0
                                    }
                                    if (metric.type === 'number' || metric.type === 'currency') {
                                        const numValue = typeof cellValue === 'number' ? cellValue : parseFloat(cellValue)
                                        return isNaN(numValue) ? 0 : numValue
                                    }
                                    return cellValue
                                })()
                                : (row[0] !== null && row[0] !== undefined ? row[0] : 0)
                            data.metrics.push({
                                ...metric,
                                value: metricValue
                            })
                        } else {
                            data.metrics.push({
                                ...metric,
                                value: 0
                            })
                        }
                    } catch (e) {
                        console.error(`Error executing metric query: ${metric.label}`, e)
                        data.metrics.push({
                            ...metric,
                            value: 0,
                            error: e.message
                        })
                    }
                }
            }

            // Execute table queries
            for (const table of config.tables || []) {
                if (table.query) {
                    try {
                        const substitutedQuery = substituteQueryParams(table.query)
                        const result = await queryAPI.executeQuery(connectionId, substitutedQuery, table.limit || 100)
                        
                        if (result.success) {
                            data.tables.push({
                                ...table,
                                columns: result.result.columns || [],
                                rows: result.result.rows || []
                            })
                        } else {
                            data.tables.push({
                                ...table,
                                columns: [],
                                rows: [],
                                error: result.message || 'Query execution failed'
                            })
                        }
                    } catch (e) {
                        console.error(`Error executing table query: ${table.title}`, e)
                        data.tables.push({
                            ...table,
                            columns: [],
                            rows: [],
                            error: e.message
                        })
                    }
                }
            }

            // Execute chart queries
            for (const chart of config.charts || []) {
                if (chart.query) {
                    try {
                        const substitutedQuery = substituteQueryParams(chart.query)
                        const result = await queryAPI.executeQuery(connectionId, substitutedQuery, chart.limit || 100)
                        
                        if (result.success && result.result && result.result.rows && result.result.rows.length > 0) {
                            const columns = result.result.columns || []
                            const dataPoints = result.result.rows.map(row => {
                                const dataPoint = {}
                                columns.forEach((col, idx) => {
                                    dataPoint[col] = row[idx]
                                })
                                return dataPoint
                            })
                            data.charts.push({
                                ...chart,
                                data: dataPoints
                            })
                        } else {
                            data.charts.push({
                                ...chart,
                                data: [],
                                error: 'No data returned'
                            })
                        }
                    } catch (e) {
                        console.error(`Error executing chart query: ${chart.title}`, e)
                        data.charts.push({
                            ...chart,
                            data: [],
                            error: e.message
                        })
                    }
                }
            }

            setDashboardData(data)
        } catch (error) {
            console.error('Error loading dashboard data:', error)
            setError(error.message)
        } finally {
            setLoading(false)
        }
    }, [connectionId, substituteQueryParams])

    // Load dashboard data - but only auto-refresh on input change if autoRefresh is enabled
    // Use updatedAt as dependency to ensure reload when config is edited
    useEffect(() => {
        if (dashboardConfig && connectionId) {
            loadDashboardData(dashboardConfig)
        } else {
            setDashboardData(null)
        }
    }, [dashboardConfig?.updatedAt, connectionId, loadDashboardData])

    // Optionally auto-refresh when input values change (if enabled in config)
    useEffect(() => {
        if (dashboardConfig?.autoRefresh && dashboardConfig && connectionId && Object.keys(inputValues).length > 0) {
            const timer = setTimeout(() => {
                loadDashboardData(dashboardConfig)
            }, 500) // Debounce for 500ms
            return () => clearTimeout(timer)
        }
    }, [inputValues, dashboardConfig, connectionId, loadDashboardData])

    const handleRefresh = useCallback(async () => {
        if (!dashboardConfig) return
        setLoading(true)
        try {
            await loadDashboardData(dashboardConfig)
            if (onRefresh) onRefresh()
        } catch (error) {
            console.error('Error refreshing dashboard:', error)
        } finally {
            setLoading(false)
        }
    }, [dashboardConfig, loadDashboardData, onRefresh])

    const handleInputChange = useCallback((name, value) => {
        setInputValues(prev => {
            const newValues = { ...prev, [name]: value }
            // Auto-refresh if enabled
            if (dashboardConfig?.autoRefresh) {
                // Refresh will be triggered by useEffect watching inputValues
            }
            return newValues
        })
    }, [dashboardConfig])

    const handleButtonClick = useCallback(() => {
        // Refresh dashboard when a button is clicked
        handleRefresh()
    }, [handleRefresh])

    const getIcon = (iconName) => {
        const icons = {
            TrendingUp,
            DollarSign,
            Users,
            Database,
            Activity
        }
        return icons[iconName] || TrendingUp
    }

    const formatValue = (value, type) => {
        // Text KPIs (a name, a label, a status) render the string verbatim — never
        // coerce to a number. This is the hero-name case the numeric-only path used
        // to turn into 0.
        if (type === 'text') {
            return value === null || value === undefined ? '—' : String(value)
        }
        if (type === 'currency') {
            const n = typeof value === 'number' ? value : parseFloat(value)
            return new Intl.NumberFormat('en-US', {
                style: 'currency',
                currency: 'USD',
                minimumFractionDigits: 0,
                maximumFractionDigits: 0,
            }).format(isNaN(n) ? 0 : n)
        }
        if (type === 'percent' || type === 'percentage') {
            const n = typeof value === 'number' ? value : parseFloat(value)
            return `${(isNaN(n) ? 0 : n).toFixed(1)}%`
        }
        return (value ?? 0).toLocaleString()
    }

    const renderChart = (chart) => {
        const { type, data, xAxis, yAxis, series, colors } = chart
        
        // Default colors for charts
        const defaultColors = ['#0ea5e9', '#22d3ee', '#14b8a6', '#10b981', '#f59e0b', '#f97316', '#64748b', '#0f172a']
        const chartColors = colors || defaultColors

        switch (type?.toLowerCase()) {
            case 'bar':
            case 'barchart':
            case 'bar chart':
                return (
                    <BarChart data={data}>
                        <CartesianGrid strokeDasharray="3 3" />
                        <XAxis dataKey={xAxis || data[0] && Object.keys(data[0])[0]} />
                        <YAxis />
                        <Tooltip />
                        <Legend />
                        {series && series.map((serie, idx) => (
                            <Bar 
                                key={serie.dataKey || idx} 
                                dataKey={serie.dataKey || serie.name || Object.keys(data[0])[1]} 
                                fill={serie.color || chartColors[idx % chartColors.length]}
                                name={serie.name || serie.dataKey}
                            />
                        ))}
                        {!series && data[0] && Object.keys(data[0]).slice(1).map((key, idx) => (
                            <Bar 
                                key={key} 
                                dataKey={key} 
                                fill={chartColors[idx % chartColors.length]}
                            />
                        ))}
                    </BarChart>
                )
            
            case 'line':
            case 'linechart':
            case 'line chart':
                return (
                    <LineChart data={data}>
                        <CartesianGrid strokeDasharray="3 3" />
                        <XAxis dataKey={xAxis || data[0] && Object.keys(data[0])[0]} />
                        <YAxis />
                        <Tooltip />
                        <Legend />
                        {series && series.map((serie, idx) => (
                            <Line 
                                key={serie.dataKey || idx} 
                                type="monotone"
                                dataKey={serie.dataKey || serie.name || Object.keys(data[0])[1]} 
                                stroke={serie.color || chartColors[idx % chartColors.length]}
                                name={serie.name || serie.dataKey}
                                strokeWidth={2}
                            />
                        ))}
                        {!series && data[0] && Object.keys(data[0]).slice(1).map((key, idx) => (
                            <Line 
                                key={key} 
                                type="monotone"
                                dataKey={key} 
                                stroke={chartColors[idx % chartColors.length]}
                                strokeWidth={2}
                            />
                        ))}
                    </LineChart>
                )
            
            case 'pie':
            case 'piechart':
            case 'pie chart':
                const pieDataKey = yAxis || (data[0] && Object.keys(data[0])[1]) || 'value'
                const pieNameKey = xAxis || (data[0] && Object.keys(data[0])[0]) || 'name'
                return (
                    <PieChart>
                        <Pie
                            data={data}
                            cx="50%"
                            cy="50%"
                            labelLine={false}
                            label={({ name, percent }) => `${name}: ${(percent * 100).toFixed(0)}%`}
                            outerRadius={120}
                            fill="#8884d8"
                            dataKey={pieDataKey}
                            nameKey={pieNameKey}
                        >
                            {data.map((entry, index) => (
                                <Cell key={`cell-${index}`} fill={chartColors[index % chartColors.length]} />
                            ))}
                        </Pie>
                        <Tooltip />
                        <Legend />
                    </PieChart>
                )
            
            case 'area':
            case 'areachart':
            case 'area chart':
                return (
                    <AreaChart data={data}>
                        <CartesianGrid strokeDasharray="3 3" />
                        <XAxis dataKey={xAxis || data[0] && Object.keys(data[0])[0]} />
                        <YAxis />
                        <Tooltip />
                        <Legend />
                        {series && series.map((serie, idx) => (
                            <Area 
                                key={serie.dataKey || idx} 
                                type="monotone"
                                dataKey={serie.dataKey || serie.name || Object.keys(data[0])[1]} 
                                stroke={serie.color || chartColors[idx % chartColors.length]}
                                fill={serie.color || chartColors[idx % chartColors.length]}
                                fillOpacity={0.6}
                                name={serie.name || serie.dataKey}
                            />
                        ))}
                        {!series && data[0] && Object.keys(data[0]).slice(1).map((key, idx) => (
                            <Area 
                                key={key} 
                                type="monotone"
                                dataKey={key} 
                                stroke={chartColors[idx % chartColors.length]}
                                fill={chartColors[idx % chartColors.length]}
                                fillOpacity={0.6}
                            />
                        ))}
                    </AreaChart>
                )
            
            default:
                // Default to bar chart if type is not recognized
                return (
                    <BarChart data={data}>
                        <CartesianGrid strokeDasharray="3 3" />
                        <XAxis dataKey={xAxis || data[0] && Object.keys(data[0])[0]} />
                        <YAxis />
                        <Tooltip />
                        <Legend />
                        {data[0] && Object.keys(data[0]).slice(1).map((key, idx) => (
                            <Bar 
                                key={key} 
                                dataKey={key} 
                                fill={chartColors[idx % chartColors.length]}
                            />
                        ))}
                    </BarChart>
                )
        }
    }

    // Check if connectionId is missing
    if (!connectionId) {
        return (
            <div className={styles.emptyState}>
                <Database size={48} className={styles.emptyIcon} />
                <h3>No Connection Selected</h3>
                <p>Please select a database connection from the dropdown to continue</p>
            </div>
        )
    }

    if (!dashboardConfig) {
        return (
            <div className={styles.emptyState}>
                <Database size={48} className={styles.emptyIcon} />
                <h3>No Dashboard</h3>
                <p>Ask the DBA Agent to create a dashboard for you</p>
                <p className={styles.hint}>Example: "Create a sales overview dashboard with total sales, total revenue, and total customers metrics, plus a table of recent orders"</p>
            </div>
        )
    }

    if (loading && !dashboardData) {
        return (
            <div className={styles.loadingState}>
                <div className={styles.spinner}></div>
                <p>Loading dashboard...</p>
            </div>
        )
    }

    if (error) {
        return (
            <div className={styles.errorState}>
                <AlertCircle size={24} />
                <p>Error loading dashboard: {error}</p>
                <button onClick={() => loadDashboardData(dashboardConfig)} className={styles.retryButton}>
                    Retry
                </button>
            </div>
        )
    }

    if (!dashboardData) {
        return (
            <div className={styles.emptyState}>
                <Database size={48} className={styles.emptyIcon} />
                <h3>Loading Dashboard Data...</h3>
                <p>Please wait while we load your dashboard data</p>
            </div>
        )
    }

    // If editing mode, show code editor
    if (isEditing) {
        return (
            <div className={styles.dashboard}>
                <div className={styles.dashboardHeader}>
                    <div>
                        <h2>Edit Dashboard Code</h2>
                        <p className={styles.dashboardDescription}>Edit the dashboard configuration JSON and see live preview</p>
                    </div>
                    <div className={styles.dashboardActions}>
                        <button
                            className={styles.saveButton}
                            onClick={async () => {
                                try {
                                    const parsed = JSON.parse(editConfig)
                                    if (setDashboardConfig) {
                                        const updatedConfig = {
                                            ...parsed,
                                            updatedAt: new Date().toISOString()
                                        }
                                        setDashboardConfig(updatedConfig)
                                        // Force reload dashboard data to pick up all changes
                                        if (connectionId) {
                                            setLoading(true)
                                            try {
                                                await loadDashboardData(updatedConfig)
                                            } catch (error) {
                                                console.error('Error reloading dashboard after edit:', error)
                                            } finally {
                                                setLoading(false)
                                            }
                                        }
                                    }
                                    setIsEditing(false)
                                    setEditError(null)
                                } catch (e) {
                                    setEditError(`Invalid JSON: ${e.message}`)
                                }
                            }}
                            title="Save Changes"
                        >
                            <Check size={14} />
                        </button>
                        <button
                            className={styles.cancelButton}
                            onClick={() => {
                                setIsEditing(false)
                                setEditConfig('')
                                setEditError(null)
                            }}
                            title="Cancel Editing"
                        >
                            <XCircle size={14} />
                        </button>
                    </div>
                </div>
            
                {editError && (
                    <div className={styles.editError}>
                        <AlertCircle size={16} />
                        <span>{editError}</span>
                    </div>
                )}
            
                <div className={styles.codeEditorContainer}>
                    <Suspense fallback={<div className={styles.editorLoading}>Loading editor...</div>}>
                        <Editor
                            height="100%"
                            defaultLanguage="json"
                            value={editConfig}
                            onChange={(value) => {
                                setEditConfig(value || '')
                                setEditError(null)
                                // Try to parse for live preview - update config immediately for real-time preview
                                try {
                                    const parsed = JSON.parse(value || '{}')
                                    if (setDashboardConfig) {
                                        // Always update with new timestamp to force refresh
                                        const updatedConfig = {
                                            ...parsed,
                                            updatedAt: new Date().toISOString()
                                        }
                                        setDashboardConfig(updatedConfig)
                                        // Debounce data reload to avoid excessive reloads during typing
                                        if (previewReloadTimeoutRef.current) {
                                            clearTimeout(previewReloadTimeoutRef.current)
                                        }
                                        previewReloadTimeoutRef.current = setTimeout(() => {
                                            if (connectionId) {
                                                loadDashboardData(updatedConfig)
                                            }
                                        }, 500) // Debounce for 500ms
                                    }
                                } catch (e) {
                                    // Ignore parse errors during typing
                                }
                            }}
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
                                formatOnPaste: true,
                                formatOnType: true,
                            }}
                        />
                    </Suspense>
                </div>
            </div>
        )
    }

    // Normal dashboard view
    return (
        <div className={styles.dashboard}>
            <div className={styles.dashboardHeader}>
                <div>
                    <h2>{dashboardConfig.title}</h2>
                    {dashboardConfig.description && (
                        <p className={styles.dashboardDescription}>{dashboardConfig.description}</p>
                    )}
                </div>
                <div className={styles.dashboardActions}>
                    {!isEditing && (
                        <>
                            {onSave && (
                                <button
                                    className={styles.saveButton}
                                    onClick={() => {
                                        if (onSave && dashboardConfig) {
                                            onSave(dashboardConfig)
                                        }
                                    }}
                                    title="Save Dashboard"
                                >
                                    <Save size={14} />
                                </button>
                            )}
                            <button
                                className={styles.editButton}
                                onClick={() => {
                                    if (dashboardConfig) {
                                        setEditConfig(JSON.stringify(dashboardConfig, null, 2))
                                        setEditError(null)
                                        setIsEditing(true)
                                    }
                                }}
                                title="Edit Dashboard Code"
                            >
                                <Edit2 size={14} />
                            </button>
                            <button
                                className={styles.refreshButton}
                                onClick={handleRefresh}
                                disabled={loading}
                                title="Refresh"
                            >
                                <RefreshCw size={14} className={loading ? styles.spinning : ''} />
                            </button>
                        </>
                    )}
                    {onClear && !isEditing && (
                        <button
                            className={styles.clearButton}
                            onClick={onClear}
                            title="New Dashboard"
                        >
                            <X size={14} />
                        </button>
                    )}
                </div>
            </div>

            {/* Input Elements */}
            {dashboardConfig.inputs && dashboardConfig.inputs.length > 0 && (
                <div className={styles.inputsContainer}>
                    {dashboardConfig.inputs.map((input, idx) => {
                        // Determine layout class based on input width property
                        let widthClass = ''
                        if (input.width === 'full') {
                            widthClass = styles.inputFullWidth
                        } else if (input.width === 'half') {
                            widthClass = styles.inputHalfWidth
                        } else if (input.width === 'third') {
                            widthClass = styles.inputThirdWidth
                        }

                        return (
                            <div key={`${input.name || idx}-${dashboardConfig?.updatedAt || ''}`} className={widthClass}>
                                {renderInput(input, inputValues, handleInputChange, handleButtonClick)}
                            </div>
                        )
                    })}
                </div>
            )}

            {/* Metrics */}
            {dashboardData.metrics && dashboardData.metrics.length > 0 && (
                <div className={styles.metricsGrid}>
                    {dashboardData.metrics.map((metric, idx) => {
                        const IconComponent = getIcon(metric.icon)
                        return (
                            <div key={idx} className={styles.metricCard}>
                                <div className={styles.metricIcon}>
                                    <IconComponent size={24} />
                                </div>
                                <div className={styles.metricContent}>
                                    <div className={styles.metricValue}>
                                        {metric.error ? (
                                            <span className={styles.errorText}>Error</span>
                                        ) : (
                                            formatValue(metric.value, metric.type)
                                        )}
                                    </div>
                                    <div className={styles.metricLabel}>{metric.label}</div>
                                </div>
                            </div>
                        )
                    })}
                </div>
            )}

            {/* Charts */}
            {dashboardData.charts && dashboardData.charts.map((chart, chartIdx) => (
                <div key={chartIdx} className={styles.chartSection}>
                    <h3 className={styles.chartTitle}>{chart.title}</h3>
                    {chart.error ? (
                        <div className={styles.errorBox}>
                            <AlertCircle size={16} />
                            <span>Error loading chart: {chart.error}</span>
                        </div>
                    ) : chart.data && chart.data.length > 0 ? (
                        <div className={styles.chartWrapper}>
                            <ResponsiveContainer width="100%" height={chart.height || 400}>
                                {renderChart(chart)}
                            </ResponsiveContainer>
                        </div>
                    ) : (
                        <div className={styles.emptyChart}>
                            No data available for chart
                        </div>
                    )}
                </div>
            ))}

            {/* Tables */}
            {dashboardData.tables && dashboardData.tables.map((table, tableIdx) => (
                <div key={tableIdx} className={styles.tableSection}>
                    <h3 className={styles.tableTitle}>{table.title}</h3>
                    {table.error ? (
                        <div className={styles.errorBox}>
                            <AlertCircle size={16} />
                            <span>Error loading table: {table.error}</span>
                        </div>
                    ) : (
                        <div className={styles.tableWrapper}>
                            <table className={styles.dataTable}>
                                <thead>
                                    <tr>
                                        {table.columns && table.columns.map((col, colIdx) => (
                                            <th key={colIdx}>{col}</th>
                                        ))}
                                    </tr>
                                </thead>
                                <tbody>
                                    {table.rows && table.rows.length > 0 ? (
                                        table.rows.map((row, rowIdx) => (
                                            <tr key={rowIdx}>
                                                {table.columns && table.columns.map((col, colIdx) => (
                                                    <td key={colIdx}>
                                                        {row[colIdx] === null ? (
                                                            <span className={styles.nullValue}>NULL</span>
                                                        ) : (
                                                            String(row[colIdx])
                                                        )}
                                                    </td>
                                                ))}
                                            </tr>
                                        ))
                                    ) : (
                                        <tr>
                                            <td colSpan={table.columns?.length || 1} className={styles.emptyTable}>
                                                No data available
                                            </td>
                                        </tr>
                                    )}
                                </tbody>
                            </table>
                        </div>
                    )}
                </div>
            ))}
        </div>
    )
}