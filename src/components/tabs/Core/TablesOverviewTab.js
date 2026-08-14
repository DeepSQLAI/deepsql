'use client'

import { useState, useEffect } from 'react'
import {
    Table, Copy, Eye, Code, FileText, Edit, Link, ChevronRight, Search, RefreshCw
} from 'lucide-react'
import styles from './TablesOverviewTab.module.css'
import { queryAPI } from '@/lib/api/client'
import { canonicalTableReference, objectKey, qualifyForSql } from '@/lib/schemaNames'

export default function TablesOverviewTab({ connectionId }) {
    const [tables, setTables] = useState([])
    const [filteredTables, setFilteredTables] = useState([])
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState(null)
    const [searchTerm, setSearchTerm] = useState('')
    const [contextMenu, setContextMenu] = useState({ visible: false, x: 0, y: 0, table: null })
    const [showSqlSubmenu, setShowSqlSubmenu] = useState(false)

    useEffect(() => {
        if (connectionId) {
            fetchTables()
        }
    }, [connectionId])

    useEffect(() => {
        if (searchTerm) {
            const q = searchTerm.toLowerCase()
            const filtered = tables.filter(table => {
                const ref = canonicalTableReference(table).toLowerCase()
                const schema = (table.schema || table.schemaName || '').toLowerCase()
                return (
                    table.name.toLowerCase().includes(q) ||
                    ref.includes(q) ||
                    schema.includes(q)
                )
            })
            setFilteredTables(filtered)
        } else {
            setFilteredTables(tables)
        }
    }, [searchTerm, tables])

    // Close context menu on click outside
    useEffect(() => {
        const handleClick = () => {
            setContextMenu({ visible: false, x: 0, y: 0, table: null })
            setShowSqlSubmenu(false)
        }
        if (contextMenu.visible) {
            document.addEventListener('click', handleClick)
            return () => document.removeEventListener('click', handleClick)
        }
    }, [contextMenu.visible])

    const fetchTables = async () => {
        try {
            setLoading(true)
            setError(null)
            const response = await queryAPI.getDatabaseObjects(connectionId)
            if (response.success) {
                const tableObjects = response.objects.filter(obj => obj.type === 'table')

                // Fetch statistics for each table
                const tablesWithStats = await Promise.all(
                    tableObjects.map(async (table) => {
                        try {
                            const statsResponse = await queryAPI.getTableStats(connectionId, objectKey(table))
                            return {
                                ...table,
                                stats: statsResponse.success ? statsResponse.stats : null
                            }
                        } catch (err) {
                            console.error(`Failed to fetch stats for ${canonicalTableReference(table)}:`, err)
                            return { ...table, stats: null }
                        }
                    })
                )

                setTables(tablesWithStats)
                setFilteredTables(tablesWithStats)
            }
        } catch (err) {
            console.error('Failed to fetch tables:', err)
            setError('Failed to load tables')
        } finally {
            setLoading(false)
        }
    }

    const handleContextMenu = (e, table) => {
        e.preventDefault()
        setContextMenu({
            visible: true,
            x: e.clientX,
            y: e.clientY,
            table
        })
        setShowSqlSubmenu(false)
    }

    const copyToClipboard = (text) => {
        navigator.clipboard.writeText(text)
        setContextMenu({ visible: false, x: 0, y: 0, table: null })
    }

    const handleCopyName = () => {
        if (contextMenu.table) {
            copyToClipboard(canonicalTableReference(contextMenu.table))
        }
    }

    const handleCopyAllColumnNames = () => {
        if (contextMenu.table && contextMenu.table.columns) {
            const columnNames = contextMenu.table.columns.map(col => col.name).join(', ')
            copyToClipboard(columnNames)
        }
    }

    const handlePreviewTableData = async () => {
        if (contextMenu.table) {
            // TODO: Open in SQL Runner tab with SELECT query
            const query = `SELECT *\nFROM ${qualifyForSql(contextMenu.table)}\nLIMIT 100;`
            copyToClipboard(query)
            setContextMenu({ visible: false, x: 0, y: 0, table: null })
        }
    }

    const handleGenerateSQL = (type) => {
        if (!contextMenu.table) return

        const tableName = qualifyForSql(contextMenu.table)
        const columns = contextMenu.table.columns || []
        const columnNames = columns.map(col => col.name).join(', ')
        const columnList = columns.map(col => col.name).join(',\n    ')

        let sql = ''
        switch (type) {
            case 'SELECT':
                sql = `SELECT ${columnList}\nFROM ${tableName}\nWHERE 1=1;`
                break
            case 'INSERT':
                const values = columns.map(() => '?').join(', ')
                sql = `INSERT INTO ${tableName} (\n    ${columnList}\n)\nVALUES (\n    ${values}\n);`
                break
            case 'UPDATE':
                const setClauses = columns.map(col => `${col.name} = ?`).join(',\n    ')
                sql = `UPDATE ${tableName}\nSET\n    ${setClauses}\nWHERE id = ?;`
                break
            case 'DELETE':
                sql = `DELETE FROM ${tableName}\nWHERE id = ?;`
                break
        }

        copyToClipboard(sql)
        setContextMenu({ visible: false, x: 0, y: 0, table: null })
    }

    const handleViewSchemaText = () => {
        if (contextMenu.table && contextMenu.table.columns) {
            const columns = contextMenu.table.columns
            const schemaText = columns.map(col => {
                let line = `${col.name} ${col.dataType}`
                if (!col.nullable) line += ' NOT NULL'
                if (col.defaultValue) line += ` DEFAULT ${col.defaultValue}`
                if (col.primaryKey) line += ' PRIMARY KEY'
                return line
            }).join('\n')

            copyToClipboard(schemaText)
        }
        setContextMenu({ visible: false, x: 0, y: 0, table: null })
    }

    const handleViewDetail = () => {
        // TODO: Open table detail modal (similar to existing implementation)
        console.log('View detail for:', contextMenu.table?.name)
        setContextMenu({ visible: false, x: 0, y: 0, table: null })
    }

    const handleEditSchema = () => {
        // TODO: Open schema editor
        console.log('Edit schema for:', contextMenu.table?.name)
        setContextMenu({ visible: false, x: 0, y: 0, table: null })
    }

    const handleCopyURL = () => {
        if (contextMenu.table) {
            const url = `${window.location.origin}/tables/${objectKey(contextMenu.table)}`
            copyToClipboard(url)
        }
    }

    const formatBytes = (bytes) => {
        if (!bytes || bytes === 0) return '0 B'
        const k = 1024
        const sizes = ['B', 'KB', 'MB', 'GB']
        const i = Math.floor(Math.log(bytes) / Math.log(k))
        return Math.round((bytes / Math.pow(k, i)) * 100) / 100 + ' ' + sizes[i]
    }

    return (
        <div className={styles.tablesOverview}>
            <div className={styles.header}>
                <div className={styles.headerLeft}>
                    <Table size={20} />
                    <h2>Tables</h2>
                    <span className={styles.count}>({filteredTables.length})</span>
                </div>
                <div className={styles.headerRight}>
                    <div className={styles.searchBox}>
                        <Search size={16} />
                        <input
                            type="text"
                            placeholder="Search tables..."
                            value={searchTerm}
                            onChange={(e) => setSearchTerm(e.target.value)}
                        />
                    </div>
                    <button
                        className={styles.refreshButton}
                        onClick={fetchTables}
                        disabled={loading}
                        title="Refresh"
                    >
                        <RefreshCw size={16} className={loading ? styles.spinning : ''} />
                    </button>
                </div>
            </div>

            {error && (
                <div className={styles.error}>
                    <strong>Error:</strong> {error}
                </div>
            )}

            {loading && (
                <div className={styles.loadingState}>
                    <div className={styles.spinner}></div>
                    <p>Loading tables...</p>
                </div>
            )}

            {!loading && !error && (
                <div className={styles.tableWrapper}>
                    <table className={styles.tablesTable}>
                        <thead>
                            <tr>
                                <th>Name</th>
                                <th>Engine</th>
                                <th>Collation</th>
                                <th>Row count est.</th>
                                <th>Data size</th>
                                <th>Index size</th>
                                <th>Comment</th>
                            </tr>
                        </thead>
                        <tbody>
                            {filteredTables.map((table) => (
                                <tr
                                    key={objectKey(table)}
                                    onContextMenu={(e) => handleContextMenu(e, table)}
                                    className={styles.tableRow}
                                >
                                    <td className={styles.tableName}>
                                        <Table size={14} />
                                        <span>{canonicalTableReference(table)}</span>
                                    </td>
                                    <td>{table.stats?.engine || 'InnoDB'}</td>
                                    <td>{table.stats?.collation || 'utf8mb3_general_ci'}</td>
                                    <td>{table.rowCount?.toLocaleString() || table.stats?.rowCount?.toLocaleString() || '-'}</td>
                                    <td>{formatBytes(table.stats?.dataSize)}</td>
                                    <td>{formatBytes(table.stats?.indexSize)}</td>
                                    <td className={styles.comment}>{table.stats?.comment || '-'}</td>
                                </tr>
                            ))}
                        </tbody>
                    </table>

                    {filteredTables.length === 0 && !loading && (
                        <div className={styles.emptyState}>
                            <Table size={48} />
                            <h3>No tables found</h3>
                            <p>
                                {searchTerm
                                    ? `No tables match "${searchTerm}"`
                                    : 'This database has no tables'}
                            </p>
                        </div>
                    )}
                </div>
            )}

            {/* Context Menu */}
            {contextMenu.visible && contextMenu.table && (
                <div
                    className={styles.contextMenu}
                    style={{
                        position: 'fixed',
                        top: contextMenu.y,
                        left: contextMenu.x,
                    }}
                    onClick={(e) => e.stopPropagation()}
                >
                    <div className={styles.contextMenuItem} onClick={handleCopyName}>
                        <Copy size={14} />
                        <span>Copy name</span>
                    </div>
                    <div className={styles.contextMenuItem} onClick={handleCopyAllColumnNames}>
                        <Copy size={14} />
                        <span>Copy all column names</span>
                    </div>
                    <div className={styles.divider}></div>
                    <div className={styles.contextMenuItem} onClick={handlePreviewTableData}>
                        <Eye size={14} />
                        <span>Preview table data</span>
                    </div>
                    <div
                        className={styles.contextMenuItem}
                        onMouseEnter={() => setShowSqlSubmenu(true)}
                    >
                        <Code size={14} />
                        <span>Generate SQL</span>
                        <ChevronRight size={14} className={styles.submenuArrow} />

                        {/* SQL Submenu */}
                        {showSqlSubmenu && (
                            <div className={styles.submenu}>
                                <div className={styles.contextMenuItem} onClick={() => handleGenerateSQL('SELECT')}>
                                    <span>SELECT</span>
                                </div>
                                <div className={styles.contextMenuItem} onClick={() => handleGenerateSQL('INSERT')}>
                                    <span>INSERT</span>
                                </div>
                                <div className={styles.contextMenuItem} onClick={() => handleGenerateSQL('UPDATE')}>
                                    <span>UPDATE</span>
                                </div>
                                <div className={styles.contextMenuItem} onClick={() => handleGenerateSQL('DELETE')}>
                                    <span>DELETE</span>
                                </div>
                            </div>
                        )}
                    </div>
                    <div className={styles.divider}></div>
                    <div className={styles.contextMenuItem} onClick={handleViewSchemaText}>
                        <FileText size={14} />
                        <span>View schema text</span>
                    </div>
                    <div className={styles.contextMenuItem} onClick={handleViewDetail}>
                        <Eye size={14} />
                        <span>View detail</span>
                    </div>
                    <div className={styles.contextMenuItem} onClick={handleEditSchema}>
                        <Edit size={14} />
                        <span>Edit Schema</span>
                    </div>
                    <div className={styles.divider}></div>
                    <div className={styles.contextMenuItem} onClick={handleCopyURL}>
                        <Link size={14} />
                        <span>Copy URL</span>
                    </div>
                </div>
            )}
        </div>
    )
}
