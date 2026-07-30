'use client'

import { useState, useEffect } from 'react'
import {
    Star, Folder, FileText, Search, Plus, MoreVertical,
    Edit2, Trash2, FolderPlus, ChevronRight, ChevronDown, X
} from 'lucide-react'
import styles from './SavedQueriesPanel.module.css'
import { savedQueriesAPI } from '@/lib/api/client'

export default function SavedQueriesPanel({
    connectionId,
    onLoadQuery,
    initialQuery = null,
    showSaveModal: externalShowSaveModal = false,
    onCloseSaveModal = null
}) {
    const [queries, setQueries] = useState([])
    const [folders, setFolders] = useState([])
    const [searchTerm, setSearchTerm] = useState('')
    const [expandedFolders, setExpandedFolders] = useState({ favorites: true })
    const [contextMenu, setContextMenu] = useState({ visible: false, x: 0, y: 0, query: null })
    const [internalShowSaveModal, setInternalShowSaveModal] = useState(false)
    const [editingQuery, setEditingQuery] = useState(null)
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState(null)

    // Use external or internal control for save modal
    const showSaveModal = externalShowSaveModal || internalShowSaveModal
    const setShowSaveModal = (value) => {
        if (!value) {
            // Clear editing state when modal closes
            setEditingQuery(null)
            if (onCloseSaveModal) {
                onCloseSaveModal()
            }
        }
        setInternalShowSaveModal(value)
    }

    // Form state for save modal
    const [formData, setFormData] = useState({
        name: '',
        description: '',
        query: '',
        folder: '',
        tags: ''
    })

    // Update form data when initialQuery changes (for new queries from editor)
    useEffect(() => {
        if (initialQuery && initialQuery.query && !editingQuery) {
            // Only update if we're not editing an existing query
            setFormData(prev => ({
                ...prev,
                query: initialQuery.query,
                name: initialQuery.name || '',
                description: initialQuery.description || '',
                folder: initialQuery.folder || '',
                tags: initialQuery.tags || ''
            }))
        }
    }, [initialQuery, editingQuery])

    useEffect(() => {
        if (connectionId) {
            fetchQueries()
            fetchFolders()
        }
    }, [connectionId])

    // Close context menu on click outside
    useEffect(() => {
        const handleClick = () => setContextMenu({ visible: false, x: 0, y: 0, query: null })
        if (contextMenu.visible) {
            document.addEventListener('click', handleClick)
            return () => document.removeEventListener('click', handleClick)
        }
    }, [contextMenu.visible])

    const fetchQueries = async () => {
        try {
            setLoading(true)
            const response = await savedQueriesAPI.getQueriesByConnection(connectionId)
            if (response.success) {
                setQueries(response.queries)
            }
        } catch (err) {
            console.error('Failed to fetch saved queries:', err)
            setError('Failed to load saved queries')
        } finally {
            setLoading(false)
        }
    }

    const fetchFolders = async () => {
        try {
            const response = await savedQueriesAPI.getFolders(connectionId)
            if (response.success) {
                setFolders(response.folders)
            }
        } catch (err) {
            console.error('Failed to fetch folders:', err)
        }
    }

    const handleSaveQuery = async (e) => {
        e.preventDefault()
        try {
            const queryData = {
                ...formData,
                connectionId,
                isFavorite: false
            }

            if (editingQuery) {
                await savedQueriesAPI.updateQuery(editingQuery.id, queryData)
            } else {
                await savedQueriesAPI.createQuery(queryData)
            }

            await fetchQueries()
            await fetchFolders()
            setShowSaveModal(false)
            setEditingQuery(null)
            setFormData({ name: '', description: '', query: '', folder: '', tags: '' })
        } catch (err) {
            console.error('Failed to save query:', err)
            setError('Failed to save query')
        }
    }

    const handleDeleteQuery = async (query) => {
        try {
            await savedQueriesAPI.deleteQuery(query.id)
            await fetchQueries()
            await fetchFolders()
            // Clear form and editing state after deletion
            setEditingQuery(null)
            setFormData({ name: '', description: '', query: '', folder: '', tags: '' })
        } catch (err) {
            console.error('Failed to delete query:', err)
            setError('Failed to delete query')
        }
    }

    const handleToggleFavorite = async (query) => {
        try {
            await savedQueriesAPI.toggleFavorite(query.id)
            await fetchQueries()
        } catch (err) {
            console.error('Failed to toggle favorite:', err)
            setError('Failed to update favorite')
        }
    }

    const handleEditQuery = (query) => {
        setEditingQuery(query)
        setFormData({
            name: query.name,
            description: query.description || '',
            query: query.query,
            folder: query.folder || '',
            tags: query.tags || ''
        })
        // Ensure modal is open (it should already be, but this ensures it)
        if (!showSaveModal) {
            setShowSaveModal(true)
        }
        setContextMenu({ visible: false, x: 0, y: 0, query: null })
    }

    const handleContextMenu = (e, query) => {
        e.preventDefault()
        e.stopPropagation()
        setContextMenu({
            visible: true,
            x: e.clientX,
            y: e.clientY,
            query
        })
    }

    const handleLoadQuery = (query) => {
        if (onLoadQuery) {
            onLoadQuery(query.query)
        }
    }

    const toggleFolder = (folderName) => {
        setExpandedFolders(prev => ({
            ...prev,
            [folderName]: !prev[folderName]
        }))
    }

    // Filter queries by search term
    const filteredQueries = searchTerm
        ? queries.filter(q =>
            q.name.toLowerCase().includes(searchTerm.toLowerCase()) ||
            q.description?.toLowerCase().includes(searchTerm.toLowerCase()) ||
            q.query.toLowerCase().includes(searchTerm.toLowerCase())
          )
        : queries

    // Group queries
    const favoriteQueries = filteredQueries.filter(q => q.isFavorite)
    const queriesByFolder = folders.reduce((acc, folder) => {
        acc[folder] = filteredQueries.filter(q => q.folder === folder)
        return acc
    }, {})
    const uncategorizedQueries = filteredQueries.filter(q => !q.folder && !q.isFavorite)

    // Render queries list content
    const renderQueriesList = () => (
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
                    {favoriteQueries.length > 0 && (
                        <div className={styles.folderGroup}>
                            <div
                                className={styles.folderHeader}
                                onClick={() => toggleFolder('favorites')}
                            >
                                {expandedFolders.favorites ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
                                <Star size={14} className={styles.favoriteIcon} />
                                <span>Favorites</span>
                                <span className={styles.count}>{favoriteQueries.length}</span>
                            </div>
                            {expandedFolders.favorites && (
                                    <div className={styles.queryList}>
                                        {favoriteQueries.map(query => (
                                            <div
                                                key={query.id}
                                                className={`${styles.queryItem} ${editingQuery?.id === query.id ? styles.queryItemSelected : ''}`}
                                                onClick={() => handleEditQuery(query)}
                                                onDoubleClick={(e) => {
                                                    e.stopPropagation()
                                                    handleLoadQuery(query)
                                                    setShowSaveModal(false)
                                                }}
                                                onContextMenu={(e) => handleContextMenu(e, query)}
                                            >
                                                <FileText size={12} />
                                                <span className={styles.queryName}>{query.name}</span>
                                                <button
                                                    className={styles.moreButton}
                                                    onClick={(e) => {
                                                        e.stopPropagation()
                                                        handleContextMenu(e, query)
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
                        const folderQueries = queriesByFolder[folder] || []
                        if (folderQueries.length === 0) return null

                        return (
                            <div key={folder} className={styles.folderGroup}>
                                <div
                                    className={styles.folderHeader}
                                    onClick={() => toggleFolder(folder)}
                                >
                                    {expandedFolders[folder] ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
                                    <Folder size={14} />
                                    <span>{folder}</span>
                                    <span className={styles.count}>{folderQueries.length}</span>
                                </div>
                                {expandedFolders[folder] && (
                                    <div className={styles.queryList}>
                                        {folderQueries.map(query => (
                                            <div
                                                key={query.id}
                                                className={`${styles.queryItem} ${editingQuery?.id === query.id ? styles.queryItemSelected : ''}`}
                                                onClick={() => handleEditQuery(query)}
                                                onDoubleClick={(e) => {
                                                    e.stopPropagation()
                                                    handleLoadQuery(query)
                                                    setShowSaveModal(false)
                                                }}
                                                onContextMenu={(e) => handleContextMenu(e, query)}
                                            >
                                                <FileText size={12} />
                                                <span className={styles.queryName}>{query.name}</span>
                                                <button
                                                    className={styles.moreButton}
                                                    onClick={(e) => {
                                                        e.stopPropagation()
                                                        handleContextMenu(e, query)
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
                    {uncategorizedQueries.length > 0 && (
                        <div className={styles.folderGroup}>
                            <div
                                className={styles.folderHeader}
                                onClick={() => toggleFolder('uncategorized')}
                            >
                                {expandedFolders.uncategorized ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
                                <FileText size={14} />
                                <span>Uncategorized</span>
                                <span className={styles.count}>{uncategorizedQueries.length}</span>
                            </div>
                            {expandedFolders.uncategorized && (
                                <div className={styles.queryList}>
                                    {uncategorizedQueries.map(query => (
                                        <div
                                            key={query.id}
                                            className={`${styles.queryItem} ${editingQuery?.id === query.id ? styles.queryItemSelected : ''}`}
                                            onClick={() => handleEditQuery(query)}
                                            onDoubleClick={(e) => {
                                                e.stopPropagation()
                                                handleLoadQuery(query)
                                                setShowSaveModal(false)
                                            }}
                                            onContextMenu={(e) => handleContextMenu(e, query)}
                                        >
                                            <FileText size={12} />
                                            <span className={styles.queryName}>{query.name}</span>
                                            <button
                                                className={styles.moreButton}
                                                onClick={(e) => {
                                                    e.stopPropagation()
                                                    handleContextMenu(e, query)
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

                    {filteredQueries.length === 0 && !loading && (
                        <div className={styles.emptyState}>
                            <FileText size={48} />
                            <h3>No saved queries</h3>
                            <p>Save your frequently used queries for quick access</p>
                        </div>
                    )}
                </>
            )}
        </div>
    )

    return (
        <>

            {/* Context Menu */}
            {contextMenu.visible && contextMenu.query && (
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
                            handleLoadQuery(contextMenu.query)
                            setShowSaveModal(false)
                            setContextMenu({ visible: false, x: 0, y: 0, query: null })
                        }}
                    >
                        <FileText size={14} />
                        <span>Load Query</span>
                    </div>
                    <div
                        className={styles.contextMenuItem}
                        onClick={() => handleEditQuery(contextMenu.query)}
                    >
                        <Edit2 size={14} />
                        <span>Edit</span>
                    </div>
                    <div
                        className={styles.contextMenuItem}
                        onClick={() => {
                            handleToggleFavorite(contextMenu.query)
                            setContextMenu({ visible: false, x: 0, y: 0, query: null })
                        }}
                    >
                        <Star size={14} />
                        <span>{contextMenu.query.isFavorite ? 'Remove from Favorites' : 'Add to Favorites'}</span>
                    </div>
                </div>
            )}

            {/* Save Query Modal */}
            {showSaveModal && (
                <div className={styles.modalOverlay} onClick={() => setShowSaveModal(false)}>
                    <div className={styles.modal} onClick={(e) => e.stopPropagation()}>
                        <div className={styles.modalHeader}>
                            <h2>{editingQuery ? 'Edit Query' : 'Save Query'}</h2>
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
                                    <h3>Saved Queries</h3>
                                    <div className={styles.searchBox}>
                                        <Search size={14} />
                                        <input
                                            type="text"
                                            placeholder="Search queries..."
                                            value={searchTerm}
                                            onChange={(e) => setSearchTerm(e.target.value)}
                                        />
                                    </div>
                                </div>
                                {renderQueriesList()}
                            </div>
                            <div className={styles.modalDivider}></div>
                            <div className={styles.modalFormSection}>
                                <form onSubmit={handleSaveQuery}>
                                    <div className={styles.formGroup}>
                                        <label>Name *</label>
                                        <input
                                            type="text"
                                            value={formData.name}
                                            onChange={(e) => setFormData({ ...formData, name: e.target.value })}
                                            required
                                            placeholder="My Query"
                                        />
                                    </div>
                                    <div className={styles.formGroup}>
                                        <label>Description</label>
                                        <textarea
                                            value={formData.description}
                                            onChange={(e) => setFormData({ ...formData, description: e.target.value })}
                                            placeholder="What does this query do?"
                                            rows={2}
                                        />
                                    </div>
                                    <div className={styles.formGroup}>
                                        <label>Query *</label>
                                        <textarea
                                            value={formData.query}
                                            onChange={(e) => setFormData({ ...formData, query: e.target.value })}
                                            required
                                            placeholder="SELECT * FROM users WHERE..."
                                            rows={6}
                                        />
                                    </div>
                                    <div className={styles.formGroup}>
                                        <label>Folder</label>
                                        <input
                                            type="text"
                                            value={formData.folder}
                                            onChange={(e) => setFormData({ ...formData, folder: e.target.value })}
                                            placeholder="Reports"
                                            list="folders"
                                        />
                                        <datalist id="folders">
                                            {folders.map(folder => (
                                                <option key={folder} value={folder} />
                                            ))}
                                        </datalist>
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
                                        {editingQuery && (
                                            <button
                                                type="button"
                                                className={styles.dangerButton}
                                                onClick={async () => {
                                                    if (window.confirm(`Delete query "${editingQuery.name}"?`)) {
                                                        await handleDeleteQuery(editingQuery)
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
                                            {editingQuery ? 'Update' : 'Save'}
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
