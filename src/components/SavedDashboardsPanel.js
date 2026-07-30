'use client'

import { useState, useEffect, lazy, Suspense } from 'react'
import {
    Star, Folder, LayoutDashboard, Search, Plus, MoreVertical,
    Edit2, Trash2, FolderPlus, ChevronRight, ChevronDown, X
} from 'lucide-react'
import styles from './SavedQueriesPanel.module.css'
import { savedDashboardsAPI } from '@/lib/api/client'

// Lazy load Monaco Editor for better performance
const Editor = lazy(() => import('@monaco-editor/react'))

export default function SavedDashboardsPanel({
    connectionId,
    onLoadDashboard,
    initialDashboard = null,
    showSaveModal: externalShowSaveModal = false,
    onCloseSaveModal = null
}) {
    const [dashboards, setDashboards] = useState([])
    const [folders, setFolders] = useState([])
    const [searchTerm, setSearchTerm] = useState('')
    const [expandedFolders, setExpandedFolders] = useState({ favorites: true })
    const [contextMenu, setContextMenu] = useState({ visible: false, x: 0, y: 0, dashboard: null })
    const [internalShowSaveModal, setInternalShowSaveModal] = useState(false)
    const [editingDashboard, setEditingDashboard] = useState(null)
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState(null)
    const [showNewFolderInput, setShowNewFolderInput] = useState(false)

    // Use external or internal control for save modal
    const showSaveModal = externalShowSaveModal || internalShowSaveModal
    const setShowSaveModal = (value) => {
        if (!value) {
            // Clear editing state when modal closes
            setEditingDashboard(null)
            setError(null) // Clear error when closing modal
            setShowNewFolderInput(false) // Reset new folder input state
            if (onCloseSaveModal) {
                onCloseSaveModal()
            }
        } else {
            setError(null) // Clear error when opening modal
        }
        setInternalShowSaveModal(value)
    }

    // Form state for save modal
    const [formData, setFormData] = useState({
        name: '',
        description: '',
        dashboardConfig: '',
        folder: '',
        tags: ''
    })

    // Update form data when initialDashboard changes (for new dashboards from editor)
    useEffect(() => {
        if (initialDashboard && initialDashboard.dashboardConfig && !editingDashboard) {
            // Only update if we're not editing an existing dashboard
            const configString = typeof initialDashboard.dashboardConfig === 'string' 
                ? initialDashboard.dashboardConfig 
                : JSON.stringify(initialDashboard.dashboardConfig, null, 2)
            setFormData(prev => ({
                ...prev,
                dashboardConfig: configString,
                name: initialDashboard.name || '',
                description: initialDashboard.description || '',
                folder: initialDashboard.folder || '',
                tags: initialDashboard.tags || ''
            }))
            setError(null) // Clear any previous errors when opening with new data
        }
    }, [initialDashboard, editingDashboard])

    // Clear error when modal opens
    useEffect(() => {
        if (showSaveModal) {
            setError(null)
        }
    }, [showSaveModal])

    useEffect(() => {
        if (connectionId) {
            fetchDashboards()
            fetchFolders()
        } else {
            // Clear dashboards if connectionId is not available
            setDashboards([])
            setFolders([])
            setError(null)
        }
    }, [connectionId])

    // Close context menu on click outside
    useEffect(() => {
        const handleClick = () => setContextMenu({ visible: false, x: 0, y: 0, dashboard: null })
        if (contextMenu.visible) {
            document.addEventListener('click', handleClick)
            return () => document.removeEventListener('click', handleClick)
        }
    }, [contextMenu.visible])

    const fetchDashboards = async () => {
        if (!connectionId) {
            setDashboards([])
            return
        }
        try {
            setLoading(true)
            setError(null)
            const response = await savedDashboardsAPI.getDashboardsByConnection(connectionId)
            if (response && response.success) {
                setDashboards(response.dashboards || [])
            } else {
                setDashboards([])
            }
        } catch (err) {
            console.error('Failed to fetch saved dashboards:', err)
            // Don't set error for initial load failures, just log it
            setDashboards([])
        } finally {
            setLoading(false)
        }
    }

    const fetchFolders = async () => {
        if (!connectionId) {
            setFolders([])
            return
        }
        try {
            const response = await savedDashboardsAPI.getFolders(connectionId)
            if (response && response.success) {
                setFolders(response.folders || [])
            } else {
                setFolders([])
            }
        } catch (err) {
            console.error('Failed to fetch folders:', err)
            setFolders([])
        }
    }

    const handleSaveDashboard = async (e) => {
        e.preventDefault()
        
        if (!connectionId) {
            setError('No connection selected')
            return
        }

        if (!formData.name || !formData.name.trim()) {
            setError('Name is required')
            return
        }

        try {
            setError(null)
            // Validate JSON if dashboardConfig is provided
            let configString = formData.dashboardConfig
            if (!configString || !configString.trim()) {
                setError('Dashboard Config (JSON) is required')
                return
            }

            try {
                JSON.parse(configString)
            } catch (e) {
                setError(`Invalid JSON in dashboard config: ${e.message}`)
                return
            }

            const dashboardData = {
                name: formData.name.trim(),
                description: formData.description?.trim() || '',
                dashboardConfig: configString.trim(),
                folder: formData.folder?.trim() || null,
                tags: formData.tags?.trim() || null,
                connectionId,
                isFavorite: false
            }

            let response
            if (editingDashboard) {
                response = await savedDashboardsAPI.updateDashboard(editingDashboard.id, dashboardData)
            } else {
                response = await savedDashboardsAPI.createDashboard(dashboardData)
            }

            // Check if the response indicates success
            if (response && response.success !== false) {
            await fetchDashboards()
            await fetchFolders()
            setShowSaveModal(false)
            setEditingDashboard(null)
            setFormData({ name: '', description: '', dashboardConfig: '', folder: '', tags: '' })
            setShowNewFolderInput(false)
            setError(null)
            } else {
                setError(response?.message || 'Failed to save dashboard')
            }
        } catch (err) {
            console.error('Failed to save dashboard:', err)
            const errorMessage = err.response?.data?.message || err.message || 'Failed to save dashboard'
            setError(errorMessage)
        }
    }

    const handleDeleteDashboard = async (dashboard) => {
        try {
            await savedDashboardsAPI.deleteDashboard(dashboard.id)
            await fetchDashboards()
            await fetchFolders()
            // Clear form and editing state after deletion
            setEditingDashboard(null)
            setFormData({ name: '', description: '', dashboardConfig: '', folder: '', tags: '' })
        } catch (err) {
            console.error('Failed to delete dashboard:', err)
            setError('Failed to delete dashboard')
        }
    }

    const handleToggleFavorite = async (dashboard) => {
        try {
            await savedDashboardsAPI.toggleFavorite(dashboard.id)
            await fetchDashboards()
        } catch (err) {
            console.error('Failed to toggle favorite:', err)
            setError('Failed to update favorite')
        }
    }

    const handleEditDashboard = (dashboard) => {
        setEditingDashboard(dashboard)
        const folder = dashboard.folder || ''
        setFormData({
            name: dashboard.name,
            description: dashboard.description || '',
            dashboardConfig: dashboard.dashboardConfig,
            folder: folder,
            tags: dashboard.tags || ''
        })
        // Show new folder input if folder doesn't exist in folders list
        setShowNewFolderInput(folder && !folders.includes(folder))
        // Ensure modal is open (it should already be, but this ensures it)
        if (!showSaveModal) {
            setShowSaveModal(true)
        }
        setContextMenu({ visible: false, x: 0, y: 0, dashboard: null })
    }

    const handleContextMenu = (e, dashboard) => {
        e.preventDefault()
        e.stopPropagation()
        setContextMenu({
            visible: true,
            x: e.clientX,
            y: e.clientY,
            dashboard
        })
    }

    const handleLoadDashboard = (dashboard) => {
        if (onLoadDashboard) {
            try {
                const config = JSON.parse(dashboard.dashboardConfig)
                onLoadDashboard(config)
            } catch (e) {
                console.error('Failed to parse dashboard config:', e)
                setError('Invalid dashboard configuration')
            }
        }
    }

    const toggleFolder = (folderName) => {
        setExpandedFolders(prev => ({
            ...prev,
            [folderName]: !prev[folderName]
        }))
    }

    // Filter dashboards by search term
    const filteredDashboards = searchTerm
        ? dashboards.filter(d =>
            d.name.toLowerCase().includes(searchTerm.toLowerCase()) ||
            d.description?.toLowerCase().includes(searchTerm.toLowerCase())
          )
        : dashboards

    // Group dashboards
    const favoriteDashboards = filteredDashboards.filter(d => d.isFavorite)
    const dashboardsByFolder = folders.reduce((acc, folder) => {
        acc[folder] = filteredDashboards.filter(d => d.folder === folder)
        return acc
    }, {})
    const uncategorizedDashboards = filteredDashboards.filter(d => !d.folder && !d.isFavorite)

    // Render dashboards list content
    const renderDashboardsList = () => (
        <div className={styles.queriesList}>
            {loading && (
                <div className={styles.loadingState}>
                    <div className={styles.spinner}></div>
                    <span>Loading...</span>
                </div>
            )}

            {error && (
                <div className={styles.error}>
                    {error}
                </div>
            )}

            {!loading && !error && (
                <>
                    {/* Favorites */}
                    {favoriteDashboards.length > 0 && (
                        <div className={styles.folderGroup}>
                            <div
                                className={styles.folderHeader}
                                onClick={() => toggleFolder('favorites')}
                            >
                                {expandedFolders.favorites ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
                                <Star size={14} className={styles.favoriteIcon} />
                                <span>Favorites</span>
                                <span className={styles.count}>{favoriteDashboards.length}</span>
                            </div>
                            {expandedFolders.favorites && (
                                    <div className={styles.queryList}>
                                        {favoriteDashboards.map(dashboard => (
                                            <div
                                                key={dashboard.id}
                                                className={`${styles.queryItem} ${editingDashboard?.id === dashboard.id ? styles.queryItemSelected : ''}`}
                                                onClick={() => handleEditDashboard(dashboard)}
                                                onDoubleClick={(e) => {
                                                    e.stopPropagation()
                                                    handleLoadDashboard(dashboard)
                                                    setShowSaveModal(false)
                                                }}
                                                onContextMenu={(e) => handleContextMenu(e, dashboard)}
                                            >
                                                <LayoutDashboard size={12} />
                                                <span className={styles.queryName}>{dashboard.name}</span>
                                                <button
                                                    className={styles.moreButton}
                                                    onClick={(e) => {
                                                        e.stopPropagation()
                                                        handleContextMenu(e, dashboard)
                                                    }}
                                                >
                                                    <MoreVertical size={12} />
                                                </button>
                                            </div>
                                        ))}
                                    </div>
                            )}
                        </div>
                    )}

                    {/* Folders */}
                    {folders.map(folder => {
                        const folderDashboards = dashboardsByFolder[folder] || []
                        if (folderDashboards.length === 0) return null

                        return (
                            <div key={folder} className={styles.folderGroup}>
                                <div
                                    className={styles.folderHeader}
                                    onClick={() => toggleFolder(folder)}
                                >
                                    {expandedFolders[folder] ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
                                    <Folder size={14} />
                                    <span>{folder}</span>
                                    <span className={styles.count}>{folderDashboards.length}</span>
                                </div>
                                {expandedFolders[folder] && (
                                    <div className={styles.queryList}>
                                        {folderDashboards.map(dashboard => (
                                            <div
                                                key={dashboard.id}
                                                className={`${styles.queryItem} ${editingDashboard?.id === dashboard.id ? styles.queryItemSelected : ''}`}
                                                onClick={() => handleEditDashboard(dashboard)}
                                                onDoubleClick={(e) => {
                                                    e.stopPropagation()
                                                    handleLoadDashboard(dashboard)
                                                    setShowSaveModal(false)
                                                }}
                                                onContextMenu={(e) => handleContextMenu(e, dashboard)}
                                            >
                                                <LayoutDashboard size={12} />
                                                <span className={styles.queryName}>{dashboard.name}</span>
                                                <button
                                                    className={styles.moreButton}
                                                    onClick={(e) => {
                                                        e.stopPropagation()
                                                        handleContextMenu(e, dashboard)
                                                    }}
                                                >
                                                    <MoreVertical size={12} />
                                                </button>
                                            </div>
                                        ))}
                                    </div>
                                )}
                            </div>
                        )
                    })}

                    {/* Uncategorized */}
                    {uncategorizedDashboards.length > 0 && (
                        <div className={styles.folderGroup}>
                            <div
                                className={styles.folderHeader}
                                onClick={() => toggleFolder('uncategorized')}
                            >
                                {expandedFolders.uncategorized ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
                                <LayoutDashboard size={14} />
                                <span>Uncategorized</span>
                                <span className={styles.count}>{uncategorizedDashboards.length}</span>
                            </div>
                            {expandedFolders.uncategorized && (
                                <div className={styles.queryList}>
                                    {uncategorizedDashboards.map(dashboard => (
                                        <div
                                            key={dashboard.id}
                                            className={`${styles.queryItem} ${editingDashboard?.id === dashboard.id ? styles.queryItemSelected : ''}`}
                                            onClick={() => handleEditDashboard(dashboard)}
                                            onDoubleClick={(e) => {
                                                e.stopPropagation()
                                                handleLoadDashboard(dashboard)
                                                setShowSaveModal(false)
                                            }}
                                            onContextMenu={(e) => handleContextMenu(e, dashboard)}
                                        >
                                            <LayoutDashboard size={12} />
                                            <span className={styles.queryName}>{dashboard.name}</span>
                                            <button
                                                className={styles.moreButton}
                                                onClick={(e) => {
                                                    e.stopPropagation()
                                                    handleContextMenu(e, dashboard)
                                                }}
                                            >
                                                <MoreVertical size={12} />
                                            </button>
                                        </div>
                                    ))}
                                </div>
                            )}
                        </div>
                    )}

                    {filteredDashboards.length === 0 && !loading && (
                        <div className={styles.emptyState}>
                            <LayoutDashboard size={48} />
                            <h3>No saved dashboards</h3>
                            <p>Save your frequently used dashboards for quick access</p>
                        </div>
                    )}
                </>
            )}
        </div>
    )

    return (
        <>
            {/* Context Menu */}
            {contextMenu.visible && contextMenu.dashboard && (
                <div
                    className={styles.contextMenu}
                    style={{
                        position: 'fixed',
                        top: contextMenu.y,
                        left: contextMenu.x,
                    }}
                    onClick={(e) => e.stopPropagation()}
                >
                    <div
                        className={styles.contextMenuItem}
                        onClick={() => {
                            handleLoadDashboard(contextMenu.dashboard)
                            setShowSaveModal(false)
                            setContextMenu({ visible: false, x: 0, y: 0, dashboard: null })
                        }}
                    >
                        <LayoutDashboard size={14} />
                        <span>Load Dashboard</span>
                    </div>
                    <div
                        className={styles.contextMenuItem}
                        onClick={() => handleEditDashboard(contextMenu.dashboard)}
                    >
                        <Edit2 size={14} />
                        <span>Edit</span>
                    </div>
                    <div
                        className={styles.contextMenuItem}
                        onClick={() => {
                            handleToggleFavorite(contextMenu.dashboard)
                            setContextMenu({ visible: false, x: 0, y: 0, dashboard: null })
                        }}
                    >
                        <Star size={14} />
                        <span>{contextMenu.dashboard.isFavorite ? 'Remove from Favorites' : 'Add to Favorites'}</span>
                    </div>
                </div>
            )}

            {/* Save Dashboard Modal */}
            {showSaveModal && (
                <div className={styles.modalOverlay} onClick={() => setShowSaveModal(false)}>
                    <div className={styles.modal} onClick={(e) => e.stopPropagation()}>
                        <div className={styles.modalHeader}>
                            <h2>{editingDashboard ? 'Edit Dashboard' : 'Save Dashboard'}</h2>
                            <button
                                className={styles.closeButton}
                                onClick={() => setShowSaveModal(false)}
                            >
                                <X size={20} />
                            </button>
                        </div>
                        <div className={styles.modalContent}>
                            <div className={styles.modalQueriesSection}>
                                <div className={styles.modalQueriesHeader}>
                                    <h3>Saved Dashboards</h3>
                                    <div className={styles.searchBox}>
                                        <Search size={14} />
                                        <input
                                            type="text"
                                            placeholder="Search dashboards..."
                                            value={searchTerm}
                                            onChange={(e) => setSearchTerm(e.target.value)}
                                        />
                                    </div>
                                </div>
                                {renderDashboardsList()}
                            </div>
                            <div className={styles.modalDivider}></div>
                            <div className={styles.modalFormSection}>
                                <form onSubmit={handleSaveDashboard}>
                                    <div className={styles.formGroup}>
                                        <label>Name *</label>
                                        <input
                                            type="text"
                                            value={formData.name}
                                            onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                                            required
                                            placeholder="My Dashboard"
                                        />
                                    </div>
                                    <div className={styles.formGroup}>
                                        <label>Description</label>
                                        <textarea
                                            value={formData.description}
                                            onChange={(e) => setFormData({ ...formData, description: e.target.value })}
                                            placeholder="What does this dashboard show?"
                                            rows={2}
                                        />
                                    </div>
                                    <div className={styles.formGroup}>
                                        <label>Dashboard Config (JSON) *</label>
                                        <div style={{ border: '1px solid #ddd', borderRadius: '4px', height: '300px', overflow: 'hidden' }}>
                                            <Suspense fallback={<div style={{ padding: '10px', textAlign: 'center' }}>Loading editor...</div>}>
                                                <Editor
                                                    height="300px"
                                                    defaultLanguage="json"
                                                    value={formData.dashboardConfig}
                                                    onChange={(value) => {
                                                        setFormData({ ...formData, dashboardConfig: value || '' })
                                                        setError(null)
                                                    }}
                                                    theme="vs-light"
                                                    options={{
                                                        minimap: { enabled: false },
                                                        fontSize: 12,
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
                                    {error && (
                                        <div className={styles.error} style={{ marginBottom: '16px' }}>
                                            {error}
                                        </div>
                                    )}
                                    <div className={styles.formGroup}>
                                        <label>Folder</label>
                                        <select
                                            value={showNewFolderInput ? '__new__' : (formData.folder || '')}
                                            onChange={(e) => {
                                                const value = e.target.value
                                                if (value === '__new__') {
                                                    // User selected "Create New" - show input
                                                    setShowNewFolderInput(true)
                                                    setFormData({ ...formData, folder: '' })
                                                } else {
                                                    setShowNewFolderInput(false)
                                                    setFormData({ ...formData, folder: value })
                                                }
                                            }}
                                            style={{
                                                width: '100%',
                                                padding: '8px 12px',
                                                border: '1px solid #ddd',
                                                borderRadius: '4px',
                                                fontSize: '14px',
                                                backgroundColor: 'white',
                                                cursor: 'pointer',
                                                marginBottom: showNewFolderInput ? '8px' : '0'
                                            }}
                                        >
                                            <option value="">-- Select Folder --</option>
                                            {folders.map(folder => (
                                                <option key={folder} value={folder}>
                                                    {folder}
                                                </option>
                                            ))}
                                            <option value="__new__">+ Create New Folder</option>
                                        </select>
                                        {showNewFolderInput && (
                                            <input
                                                type="text"
                                                placeholder="Enter new folder name"
                                                value={formData.folder || ''}
                                                onChange={(e) => setFormData({ ...formData, folder: e.target.value })}
                                                onBlur={(e) => {
                                                    // Hide input if empty
                                                    if (!e.target.value.trim()) {
                                                        setShowNewFolderInput(false)
                                                    }
                                                }}
                                                autoFocus
                                                style={{
                                                    width: '100%',
                                                    padding: '8px 12px',
                                                    border: '1px solid #ddd',
                                                    borderRadius: '4px',
                                                    fontSize: '14px',
                                                    marginTop: '8px'
                                                }}
                                            />
                                        )}
                                    </div>
                                    <div className={styles.formGroup}>
                                        <label>Tags</label>
                                        <input
                                            type="text"
                                            value={formData.tags}
                                            onChange={(e) => setFormData({ ...formData, tags: e.target.value })}
                                            placeholder="analytics, reporting"
                                        />
                                    </div>
                                    <div className={styles.modalActions}>
                                        {editingDashboard && (
                                            <button
                                                type="button"
                                                className={styles.dangerButton}
                                                onClick={async () => {
                                                    if (window.confirm(`Delete dashboard "${editingDashboard.name}"?`)) {
                                                        await handleDeleteDashboard(editingDashboard)
                                                        setShowSaveModal(false)
                                                    }
                                                }}
                                            >
                                                <Trash2 size={14} />
                                                Delete
                                            </button>
                                        )}
                                        <button
                                            type="button"
                                            className={styles.secondaryButton}
                                            onClick={() => setShowSaveModal(false)}
                                        >
                                            Cancel
                                        </button>
                                        <button type="submit" className={styles.primaryButton}>
                                            {editingDashboard ? 'Update' : 'Save'}
                                        </button>
                                    </div>
                                </form>
                            </div>
                        </div>
                    </div>
                </div>
            )}
        </>
    )
}
