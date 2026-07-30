import { Clock, Loader, Check, AlertCircle, XCircle } from 'lucide-react'

/**
 * Utility functions for rendering training status
 * Extracted from RagTrainingTab.js to reduce component complexity
 */

export const renderStatus = (status) => {
    if (!status) {
        return {
            label: 'Not started',
            subLabel: 'Choose a connection to train',
            icon: Clock
        }
    }

    switch (status.status) {
        case 'PENDING':
            return {
                label: 'Queued',
                subLabel: status.message || 'Waiting',
                icon: Clock
            }
        case 'RUNNING':
            return {
                label: `Training ${status.processedTables || 0}/${status.totalTables || 0}`,
                subLabel: status.currentTable || status.message || 'Processing',
                icon: Loader,
                spinning: true
            }
        case 'COMPLETED':
            return {
                label: 'Completed',
                subLabel: status.message || 'Ready',
                icon: Check
            }
        case 'FAILED':
            return {
                label: 'Failed',
                subLabel: status.error || status.message || 'Error',
                icon: AlertCircle
            }
        case 'CANCELLED':
            return {
                label: 'Cancelled',
                subLabel: status.message || 'Stopped',
                icon: XCircle
            }
        case 'REJECTED':
            return {
                label: 'Queue full',
                subLabel: status.error || status.message || 'Try again',
                icon: AlertCircle
            }
        default:
            return {
                label: 'Not started',
                subLabel: status.message || 'Idle',
                icon: Clock
            }
    }
}

export const getTrendMeta = (scoreHistory = []) => {
    if (!scoreHistory || scoreHistory.length < 2) {
        return { icon: '—', label: 'No trend data' }
    }

    const recent = scoreHistory[scoreHistory.length - 1]?.score ?? 0
    const previous = scoreHistory[scoreHistory.length - 2]?.score ?? 0
    const delta = recent - previous

    if (delta > 0) {
        return { icon: '↑', label: `+${delta.toFixed(1)} from last training` }
    }
    if (delta < 0) {
        return { icon: '↓', label: `${delta.toFixed(1)} from last training` }
    }
    return { icon: '—', label: 'No change from last training' }
}
