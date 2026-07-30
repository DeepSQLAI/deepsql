/**
 * Utility functions for formatting data in the Brain tab
 * Extracted from RagTrainingTab.js to reduce component complexity
 */

export const formatHistoryStatus = (value) => {
    if (!value) {
        return 'unknown'
    }
    return value.replace(/_/g, ' ').toLowerCase()
}

export const formatTimestamp = (value) => {
    if (!value) {
        return 'unknown time'
    }
    const date = new Date(value)
    if (Number.isNaN(date.getTime())) {
        return value
    }
    return date.toLocaleString()
}

export const getDurationMs = (entry) => {
    if (!entry?.startedAt || !entry?.completedAt) {
        return null
    }
    const start = new Date(entry.startedAt).getTime()
    const end = new Date(entry.completedAt).getTime()
    if (Number.isNaN(start) || Number.isNaN(end) || end < start) {
        return null
    }
    return end - start
}

export const formatDuration = (durationMs) => {
    if (durationMs == null) {
        return '--'
    }
    if (durationMs < 1000) {
        return `${durationMs} ms`
    }
    const seconds = durationMs / 1000
    if (seconds < 60) {
        return `${seconds.toFixed(1)} s`
    }
    const minutes = Math.floor(seconds / 60)
    const remainder = Math.round(seconds % 60)
    return `${minutes}m ${remainder}s`
}

export const formatNumber = (value) => {
    if (value === null || value === undefined) {
        return '--'
    }
    return new Intl.NumberFormat().format(value)
}

export const formatBytes = (bytes) => {
    if (bytes === null || bytes === undefined) {
        return '--'
    }
    if (bytes === 0) {
        return '0 B'
    }
    const k = 1024
    const sizes = ['B', 'KB', 'MB', 'GB', 'TB']
    const i = Math.floor(Math.log(bytes) / Math.log(k))
    return `${Math.round((bytes / Math.pow(k, i)) * 100) / 100} ${sizes[i]}`
}

export const formatPercent = (value) => {
    if (value === null || value === undefined || Number.isNaN(value)) {
        return '--'
    }
    return `${value.toFixed(1)}%`
}

export const formatScoreReasons = (reasons) => {
    if (!reasons || reasons.length === 0) {
        return ''
    }
    return reasons
        .map((reason) => `${reason.label}: ${reason.points >= 0 ? '+' : ''}${reason.points}`)
        .join('\n')
}
