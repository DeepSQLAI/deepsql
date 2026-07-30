/**
 * Utility functions for BCNF analysis in the Brain tab
 * Extracted from RagTrainingTab.js to reduce component complexity
 */

export const getBcnfMeta = (value) => {
    const status = (value || '').toLowerCase()
    if (status === 'likely') {
        return { label: 'Likely', className: 'bcnfLikely' }
    }
    if (status === 'review') {
        return { label: 'Needs review', className: 'bcnfReview' }
    }
    return { label: 'Unknown', className: 'bcnfUnknown' }
}

export const buildBcnfSuggestions = (issues = []) => {
    const suggestions = new Set()
    issues.forEach((issue) => {
        const lower = issue.toLowerCase()
        if (lower.includes('repeating group')) {
            suggestions.add('Split repeating attributes into a child table.')
        }
        if (lower.includes('transitive dependency')) {
            suggestions.add('Move descriptive attributes to a lookup table keyed by the foreign key.')
        }
        if (lower.includes('composite key')) {
            suggestions.add('Ensure non-key attributes depend on the full composite key, or add a surrogate key.')
        }
        if (lower.includes('no primary') || lower.includes('unique key')) {
            suggestions.add('Define a primary or unique key to validate functional dependencies.')
        }
    })
    if (suggestions.size === 0) {
        suggestions.add('Review functional dependencies with a domain expert.')
        suggestions.add('Document business rules that explain each attribute dependency.')
    }
    suggestions.add('Validate candidate keys and remove partial dependencies.')
    return Array.from(suggestions).slice(0, 4)
}

export const calculateBcnfStats = (tables = []) => {
    const stats = {
        likely: 0,
        review: 0,
        unknown: 0,
        total: tables.length,
        average: null
    }

    if (tables.length === 0) {
        return stats
    }

    let totalScore = 0
    let scoreCount = 0

    tables.forEach((table) => {
        const status = (table.bcnfStatus || '').toLowerCase()
        if (status === 'likely') {
            stats.likely++
        } else if (status === 'review') {
            stats.review++
        } else {
            stats.unknown++
        }

        if (typeof table.bcnfScore === 'number') {
            totalScore += table.bcnfScore
            scoreCount++
        }
    })

    if (scoreCount > 0) {
        stats.average = Math.round(totalScore / scoreCount)
    }

    return stats
}
