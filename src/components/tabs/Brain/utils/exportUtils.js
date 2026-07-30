/**
 * Export utilities for Brain data
 * Supports CSV, JSON, and formatted reports
 */

export const exportToCSV = (data, filename = 'brain-export.csv') => {
    if (!data || data.length === 0) {
        console.warn('No data to export')
        return
    }

    // Get headers from first object
    const headers = Object.keys(data[0])

    // Create CSV content
    const csvContent = [
        headers.join(','),
        ...data.map(row =>
            headers.map(header => {
                const value = row[header]
                // Escape quotes and wrap in quotes if contains comma
                const escaped = String(value ?? '').replace(/"/g, '""')
                return escaped.includes(',') ? `"${escaped}"` : escaped
            }).join(',')
        )
    ].join('\n')

    // Download file
    downloadFile(csvContent, filename, 'text/csv')
}

export const exportToJSON = (data, filename = 'brain-export.json') => {
    if (!data) {
        console.warn('No data to export')
        return
    }

    const jsonContent = JSON.stringify(data, null, 2)
    downloadFile(jsonContent, filename, 'application/json')
}

export const exportBrainReport = (brainData, filename = 'brain-report.json') => {
    if (!brainData) {
        console.warn('No brain data to export')
        return
    }

    const report = {
        exportedAt: new Date().toISOString(),
        overallScore: brainData.overallScore,
        tableCount: brainData.tableCount,
        columnCount: brainData.columnCount,
        lastTrainingAt: brainData.lastTrainingAt,
        tables: brainData.tables?.map(table => ({
            name: table.tableName,
            schema: table.schemaName,
            score: table.score,
            bcnfStatus: table.bcnfStatus,
            bcnfScore: table.bcnfScore,
            documented: table.documented,
            usageCount: table.usageCount
        })) || [],
        columns: brainData.columns?.map(column => ({
            table: column.tableName,
            name: column.columnName,
            score: column.score,
            type: column.dataType,
            documented: column.documented,
            profiled: !!column.profiledAt,
            ambiguous: (column.ambiguityCount ?? 0) > 1
        })) || [],
        needsInput: brainData.needsInput || [],
        scoreHistory: brainData.scoreHistory || []
    }

    exportToJSON(report, filename)
}

export const exportNotes = (notes, format = 'json', filename = 'brain-notes') => {
    if (!notes || notes.length === 0) {
        console.warn('No notes to export')
        return
    }

    if (format === 'csv') {
        const csvData = notes.map(note => ({
            scope: note.scopeType,
            table: note.tableName,
            column: note.columnName || '',
            note: note.noteText,
            confidence: note.confidenceScore,
            createdAt: note.createdAt,
            updatedAt: note.updatedAt,
            createdBy: note.createdBy || ''
        }))
        exportToCSV(csvData, `${filename}.csv`)
    } else {
        exportToJSON(notes, `${filename}.json`)
    }
}

export const exportTasks = (tasks, format = 'json', filename = 'brain-tasks') => {
    if (!tasks || tasks.length === 0) {
        console.warn('No tasks to export')
        return
    }

    if (format === 'csv') {
        const csvData = tasks.map(task => ({
            summary: task.summary,
            table: task.tableName || task.tableLabel,
            bcnfScore: task.bcnfScore,
            issues: task.issues?.join('; ') || '',
            createdAt: task.createdAt,
            status: task.status
        }))
        exportToCSV(csvData, `${filename}.csv`)
    } else {
        exportToJSON(tasks, `${filename}.json`)
    }
}

// Helper function to trigger download
function downloadFile(content, filename, mimeType) {
    const blob = new Blob([content], { type: mimeType })
    const url = URL.createObjectURL(blob)
    const link = document.createElement('a')
    link.href = url
    link.download = filename
    document.body.appendChild(link)
    link.click()
    document.body.removeChild(link)
    URL.revokeObjectURL(url)
}
