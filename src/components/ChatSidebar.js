'use client'

import { useState, useEffect } from 'react'
import { MessageSquare, Plus, Trash2, Edit2, Folder } from 'lucide-react'
import { chatHistoryAPI, projectAPI } from '@/lib/api/client'

export default function ChatSidebar({
    connectionId,
    currentChatId,
    currentProjectId,
    onChatSelect,
    onNewChat,
    onProjectSelect,
    refreshTrigger = 0
}) {
    const [projects, setProjects] = useState([])
    const [chats, setChats] = useState([])
    const [loading, setLoading] = useState(false)
    const [expandedProjects, setExpandedProjects] = useState(new Set())
    const [showNewProjectForm, setShowNewProjectForm] = useState(false)
    const [newProjectName, setNewProjectName] = useState('')
    const [newProjectDesc, setNewProjectDesc] = useState('')

    useEffect(() => {
        if (connectionId) {
            fetchProjects()
            fetchChats()
        }
    }, [connectionId, refreshTrigger])

    const fetchProjects = async () => {
        try {
            setLoading(true)
            const data = await projectAPI.getAllProjects(connectionId)
            setProjects(data)
            // Projects are collapsed by default (no auto-expand)
        } catch (error) {
            console.error('Failed to fetch projects:', error)
        } finally {
            setLoading(false)
        }
    }

    const fetchChats = async () => {
        try {
            // Always fetch ALL chats for this connection (both assigned and unassigned)
            const data = await chatHistoryAPI.getAllChats(null, connectionId)
            setChats(data)
        } catch (error) {
            console.error('Failed to fetch chats:', error)
        }
    }

    const handleCreateProject = async () => {
        if (!newProjectName.trim()) return

        try {
            const project = await projectAPI.createProject(
                newProjectName,
                newProjectDesc,
                connectionId
            )
            setProjects(prev => [project, ...prev])
            setExpandedProjects(prev => new Set([...prev, project.id]))
            setNewProjectName('')
            setNewProjectDesc('')
            setShowNewProjectForm(false)
            onProjectSelect(project.id)
        } catch (error) {
            console.error('Failed to create project:', error)
        }
    }

    const handleDeleteChat = async (chatId, e) => {
        e.stopPropagation()
        if (!confirm('Delete this chat?')) return

        try {
            await chatHistoryAPI.deleteChat(chatId)
            setChats(prev => prev.filter(c => c.id !== chatId))
            if (currentChatId === chatId) {
                onChatSelect(null)
            }
        } catch (error) {
            console.error('Failed to delete chat:', error)
        }
    }

    const toggleProject = (projectId) => {
        setExpandedProjects(prev => {
            const next = new Set(prev)
            if (next.has(projectId)) {
                next.delete(projectId)
            } else {
                next.add(projectId)
            }
            return next
        })
    }

    const getProjectChats = (projectId) => {
        return chats.filter(chat => chat.projectId === projectId)
    }

    const getUnassignedChats = () => {
        return chats.filter(chat => !chat.projectId)
    }

    const formatDate = (dateString) => {
        const date = new Date(dateString)
        const now = new Date()
        const diffMs = now - date
        const diffMins = Math.floor(diffMs / 60000)
        const diffHours = Math.floor(diffMs / 3600000)
        const diffDays = Math.floor(diffMs / 86400000)

        if (diffMins < 1) return 'Just now'
        if (diffMins === 1) return '1m ago'
        if (diffMins < 60) return `${diffMins}m ago`
        if (diffHours === 1) return '1h ago'
        if (diffHours < 24) return `${diffHours}h ago`
        if (diffDays === 1) return '1d ago'
        if (diffDays < 7) return `${diffDays}d ago`
        return date.toLocaleDateString()
    }

    const selectedProject = projects.find(p => p.id === currentProjectId)

    return (
        <div className="flex flex-col h-full bg-gray-50 border-r border-gray-200">
            {/* Header */}
            <div className="flex-shrink-0 p-3 border-b border-gray-200 bg-white">
                <button
                    onClick={onNewChat}
                    className="w-full flex flex-col items-center justify-center gap-1 px-3 py-2 bg-blue-600 hover:bg-blue-500 text-white rounded-lg text-sm font-medium transition-colors"
                >
                    <div className="flex items-center gap-2">
                        <Plus size={16} />
                        New Chat
                    </div>
                    {selectedProject && (
                        <div className="text-xs opacity-90 font-normal">
                            in {selectedProject.name}
                        </div>
                    )}
                </button>
            </div>

            {/* Projects and Chats List */}
            <div className="flex-1 overflow-y-auto">
                {loading ? (
                    <div className="p-4 text-center text-sm text-gray-500">Loading...</div>
                ) : (
                    <div className="p-2 space-y-1">
                        {/* Projects */}
                        {projects.map(project => {
                            const projectChats = getProjectChats(project.id)
                            const isExpanded = expandedProjects.has(project.id)

                            return (
                                <div key={project.id}>
                                    <div className={`flex items-center gap-1 ${currentProjectId === project.id ? 'bg-blue-50 rounded-md' : ''}`}>
                                        <button
                                            onClick={() => {
                                                onProjectSelect(project.id)
                                                if (!isExpanded) {
                                                    toggleProject(project.id)
                                                }
                                            }}
                                            className={`flex-1 flex items-center gap-2 px-2 py-1.5 rounded-md text-left transition-colors ${
                                                currentProjectId === project.id
                                                    ? 'bg-blue-100 text-blue-900'
                                                    : 'hover:bg-gray-200'
                                            }`}
                                        >
                                            <Folder size={14} className={currentProjectId === project.id ? 'text-blue-600' : 'text-gray-600'} />
                                            <span className="text-sm truncate flex-1">
                                                {project.name}
                                            </span>
                                            <span className="text-xs text-gray-400">
                                                {projectChats.length}
                                            </span>
                                        </button>
                                    </div>

                                    {isExpanded && (
                                        <div className="ml-4 mt-1 space-y-0.5 overflow-hidden">
                                            {/* New Chat in Project button */}
                                            <button
                                                onClick={() => {
                                                    onProjectSelect(project.id)
                                                    onNewChat()
                                                }}
                                                className="w-full flex items-center gap-2 px-2 py-1.5 rounded-md hover:bg-blue-50 text-blue-600 text-xs transition-colors border border-dashed border-blue-300"
                                            >
                                                <Plus size={12} />
                                                New chat in this project
                                            </button>

                                            {projectChats.length === 0 ? (
                                                <div className="px-2 py-2 text-xs text-gray-400 italic">
                                                    No chats yet
                                                </div>
                                            ) : (
                                                projectChats.map(chat => (
                                                    <div
                                                        key={chat.id}
                                                        onClick={() => onChatSelect(chat.id, project.id)}
                                                        className={`group flex items-center gap-2 px-2 py-2 rounded-md cursor-pointer transition-colors overflow-hidden ${
                                                            currentChatId === chat.id
                                                                ? 'bg-blue-100 text-blue-900'
                                                                : 'hover:bg-gray-200 text-gray-700'
                                                        }`}
                                                    >
                                                        <MessageSquare
                                                            size={14}
                                                            className={`flex-shrink-0 ${
                                                                currentChatId === chat.id
                                                                    ? 'text-blue-600'
                                                                    : 'text-gray-500'
                                                            }`}
                                                        />
                                                        <div className="flex-1 min-w-0 overflow-hidden">
                                                            <div className="text-sm truncate">
                                                                {chat.title}
                                                            </div>
                                                            <div className="text-xs text-gray-400 truncate">
                                                                {formatDate(chat.lastMessageAt || chat.createdAt)}
                                                            </div>
                                                        </div>
                                                        <button
                                                            onClick={(e) => handleDeleteChat(chat.id, e)}
                                                            className="opacity-0 group-hover:opacity-100 p-1 hover:bg-red-100 rounded transition-opacity flex-shrink-0"
                                                            title="Delete chat"
                                                        >
                                                            <Trash2 size={12} className="text-white" />
                                                        </button>
                                                    </div>
                                                ))
                                            )}
                                        </div>
                                    )}
                                </div>
                            )
                        })}

                        {/* Unassigned Chats */}
                        {getUnassignedChats().length > 0 && (
                            <div className="mt-2">
                                <div className="px-2 py-1.5 text-xs font-semibold text-gray-500 uppercase tracking-wider">
                                    Unassigned
                                </div>
                                <div className="space-y-0.5 overflow-hidden">
                                    {getUnassignedChats().map(chat => (
                                        <div
                                            key={chat.id}
                                            onClick={() => onChatSelect(chat.id, null)}
                                            className={`group flex items-center gap-2 px-2 py-2 rounded-md cursor-pointer transition-colors overflow-hidden ${
                                                currentChatId === chat.id
                                                    ? 'bg-blue-100 text-blue-900'
                                                    : 'hover:bg-gray-200 text-gray-700'
                                            }`}
                                        >
                                            <MessageSquare
                                                size={14}
                                                className={`flex-shrink-0 ${
                                                    currentChatId === chat.id
                                                        ? 'text-blue-600'
                                                        : 'text-gray-500'
                                                }`}
                                            />
                                            <div className="flex-1 min-w-0 overflow-hidden">
                                                <div className="text-sm truncate">
                                                    {chat.title}
                                                </div>
                                                <div className="text-xs text-gray-400 truncate">
                                                    {formatDate(chat.lastMessageAt || chat.createdAt)}
                                                </div>
                                            </div>
                                            <button
                                                onClick={(e) => handleDeleteChat(chat.id, e)}
                                                className="opacity-0 group-hover:opacity-100 p-1 hover:bg-red-100 rounded transition-opacity flex-shrink-0"
                                                title="Delete chat"
                                            >
                                            <Trash2 size={12} className="text-white" />
                                        </button>
                                        </div>
                                    ))}
                                </div>
                            </div>
                        )}

                        {/* New Project Button */}
                        {!showNewProjectForm && (
                            <button
                                onClick={() => setShowNewProjectForm(true)}
                                className="w-full flex items-center gap-2 px-2 py-2 mt-2 rounded-md hover:bg-gray-200 text-gray-600 text-sm transition-colors"
                            >
                                <Plus size={14} />
                                New Project
                            </button>
                        )}

                        {/* New Project Form */}
                        {showNewProjectForm && (
                            <div className="mt-2 p-2 bg-white border border-gray-200 rounded-lg">
                                <input
                                    type="text"
                                    placeholder="Project name"
                                    value={newProjectName}
                                    onChange={(e) => setNewProjectName(e.target.value)}
                                    className="w-full px-2 py-1 text-sm border border-gray-300 rounded mb-2 focus:outline-none focus:ring-2 focus:ring-blue-500"
                                    autoFocus
                                />
                                <textarea
                                    placeholder="Description (optional)"
                                    value={newProjectDesc}
                                    onChange={(e) => setNewProjectDesc(e.target.value)}
                                    className="w-full px-2 py-1 text-sm border border-gray-300 rounded mb-2 focus:outline-none focus:ring-2 focus:ring-blue-500 resize-none"
                                    rows={2}
                                />
                                <div className="flex gap-2">
                                    <button
                                        onClick={handleCreateProject}
                                        disabled={!newProjectName.trim()}
                                        className="flex-1 px-2 py-1 bg-blue-600 hover:bg-blue-500 disabled:bg-gray-300 text-white text-xs rounded transition-colors"
                                    >
                                        Create
                                    </button>
                                    <button
                                        onClick={() => {
                                            setShowNewProjectForm(false)
                                            setNewProjectName('')
                                            setNewProjectDesc('')
                                        }}
                                        className="flex-1 px-2 py-1 bg-gray-200 hover:bg-gray-300 text-gray-700 text-xs rounded transition-colors"
                                    >
                                        Cancel
                                    </button>
                                </div>
                            </div>
                        )}
                    </div>
                )}
            </div>
        </div>
    )
}
