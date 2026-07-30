/**
 * Utilities for analyzing database normal forms (1NF through 5NF)
 * Extends beyond BCNF to provide comprehensive normalization analysis
 */

/**
 * Analyze First Normal Form (1NF)
 * Requirements:
 * - All attributes contain only atomic (indivisible) values
 * - No repeating groups
 */
export function analyze1NF(table) {
    const issues = []
    const suggestions = []

    // Check for potential repeating groups in column names
    const columns = table.columns || []
    const columnNames = columns.map(c => c.columnName?.toLowerCase() || '')

    // Look for numbered columns (e.g., phone1, phone2, address1, address2)
    const repeatingPatterns = findRepeatingPatterns(columnNames)
    if (repeatingPatterns.length > 0) {
        repeatingPatterns.forEach(pattern => {
            issues.push(`Possible repeating group: ${pattern.base} (found ${pattern.count} variations)`)
        })
        suggestions.push('Create separate table for repeating attributes')
        suggestions.push('Use one-to-many relationship instead of repeating columns')
    }

    // Check for potential multi-valued attributes (arrays, JSON, CSV in names)
    const multiValuedHints = ['_list', '_array', '_json', '_csv', 'tags', 'items']
    const multiValuedColumns = columnNames.filter(name =>
        multiValuedHints.some(hint => name.includes(hint))
    )

    if (multiValuedColumns.length > 0) {
        issues.push(`Possible multi-valued attributes: ${multiValuedColumns.join(', ')}`)
        suggestions.push('Split multi-valued columns into separate rows in a related table')
    }

    const status = issues.length === 0 ? 'COMPLIANT' : 'VIOLATION'
    const score = issues.length === 0 ? 100 : Math.max(0, 100 - (issues.length * 20))

    return {
        normalForm: '1NF',
        status,
        score,
        issues,
        suggestions: suggestions.length > 0 ? suggestions : ['Table appears to comply with 1NF']
    }
}

/**
 * Analyze Second Normal Form (2NF)
 * Requirements:
 * - Must be in 1NF
 * - No partial dependencies (all non-key attributes depend on entire primary key)
 */
export function analyze2NF(table) {
    const issues = []
    const suggestions = []

    const primaryKey = table.primaryKey || []
    const columns = table.columns || []

    // 2NF only applies to tables with composite keys
    if (primaryKey.length <= 1) {
        return {
            normalForm: '2NF',
            status: 'NOT_APPLICABLE',
            score: 100,
            issues: ['Table has single-column primary key, 2NF automatically satisfied'],
            suggestions: ['No changes needed for 2NF']
        }
    }

    // Check for columns that might depend on only part of the key
    // This is heuristic-based since we can't determine functional dependencies without data analysis
    const nonKeyColumns = columns.filter(c =>
        !primaryKey.includes(c.columnName)
    )

    // Look for descriptive columns that match part of the key
    primaryKey.forEach(keyCol => {
        const relatedCols = nonKeyColumns.filter(c => {
            const colName = c.columnName?.toLowerCase() || ''
            const keyName = keyCol.toLowerCase()
            // e.g., if key is "product_id", look for "product_name", "product_description"
            return colName.startsWith(keyName.replace('_id', '_'))
        })

        if (relatedCols.length > 0) {
            issues.push(
                `Columns ${relatedCols.map(c => c.columnName).join(', ')} may depend only on ${keyCol}`
            )
            suggestions.push(`Consider moving ${keyCol}-related attributes to a separate table`)
        }
    })

    const status = issues.length === 0 ? 'LIKELY_COMPLIANT' : 'NEEDS_REVIEW'
    const score = issues.length === 0 ? 95 : Math.max(50, 95 - (issues.length * 15))

    return {
        normalForm: '2NF',
        status,
        score,
        issues: issues.length > 0 ? issues : ['No obvious 2NF violations detected'],
        suggestions: suggestions.length > 0 ? suggestions : ['Table appears to comply with 2NF']
    }
}

/**
 * Analyze Third Normal Form (3NF)
 * Requirements:
 * - Must be in 2NF
 * - No transitive dependencies (non-key attributes don't depend on other non-key attributes)
 */
export function analyze3NF(table) {
    const issues = []
    const suggestions = []

    const primaryKey = table.primaryKey || []
    const foreignKeys = table.foreignKeys || []
    const columns = table.columns || []

    // Look for foreign keys with additional descriptive columns
    foreignKeys.forEach(fk => {
        const fkColumn = fk.columnName
        const relatedCols = columns.filter(c => {
            if (primaryKey.includes(c.columnName)) return false
            if (c.columnName === fkColumn) return false

            const colName = c.columnName?.toLowerCase() || ''
            const fkName = fkColumn.toLowerCase()

            // e.g., if FK is "category_id", look for "category_name", "category_description"
            return colName.startsWith(fkName.replace('_id', '_'))
        })

        if (relatedCols.length > 0) {
            issues.push(
                `Transitive dependency: ${relatedCols.map(c => c.columnName).join(', ')} ` +
                `depend on ${fkColumn} which depends on the primary key`
            )
            suggestions.push(
                `Move ${fkColumn}-related attributes to the ${fk.referencedTable} table`
            )
        }
    })

    const status = issues.length === 0 ? 'LIKELY_COMPLIANT' : 'NEEDS_REVIEW'
    const score = issues.length === 0 ? 90 : Math.max(40, 90 - (issues.length * 15))

    return {
        normalForm: '3NF',
        status,
        score,
        issues: issues.length > 0 ? issues : ['No obvious 3NF violations detected'],
        suggestions: suggestions.length > 0 ? suggestions : ['Table appears to comply with 3NF']
    }
}

/**
 * Analyze Fourth Normal Form (4NF)
 * Requirements:
 * - Must be in BCNF
 * - No multi-valued dependencies
 */
export function analyze4NF(table) {
    const issues = []
    const suggestions = []

    const columns = table.columns || []
    const columnNames = columns.map(c => c.columnName?.toLowerCase() || '')

    // Look for junction tables with more than 2 foreign keys
    const foreignKeys = table.foreignKeys || []
    if (foreignKeys.length >= 3) {
        issues.push(`Table has ${foreignKeys.length} foreign keys, may contain multi-valued dependencies`)
        suggestions.push('Consider splitting into separate association tables')
    }

    // Look for tables with "_and_" pattern (e.g., students_and_courses_and_teachers)
    if (table.tableName?.toLowerCase().split('_and_').length > 2) {
        issues.push('Table name suggests multiple independent relationships')
        suggestions.push('Split into separate many-to-many relationships')
    }

    const status = issues.length === 0 ? 'LIKELY_COMPLIANT' : 'NEEDS_REVIEW'
    const score = issues.length === 0 ? 85 : Math.max(30, 85 - (issues.length * 20))

    return {
        normalForm: '4NF',
        status,
        score,
        issues: issues.length > 0 ? issues : ['No obvious 4NF violations detected'],
        suggestions: suggestions.length > 0 ? suggestions : ['Table appears to comply with 4NF']
    }
}

/**
 * Analyze Fifth Normal Form (5NF)
 * Requirements:
 * - Must be in 4NF
 * - No join dependencies (table can't be decomposed without loss of information)
 */
export function analyze5NF(table) {
    const issues = []
    const suggestions = []

    // 5NF is very difficult to detect heuristically
    // We'll provide general guidance based on table complexity

    const foreignKeys = table.foreignKeys || []
    const columns = table.columns || []

    if (foreignKeys.length >= 4) {
        issues.push('Complex join table with many relationships may violate 5NF')
        suggestions.push('Review if table can be decomposed into smaller join tables without information loss')
    }

    const status = issues.length === 0 ? 'LIKELY_COMPLIANT' : 'NEEDS_EXPERT_REVIEW'
    const score = issues.length === 0 ? 80 : 50

    return {
        normalForm: '5NF',
        status,
        score,
        issues: issues.length > 0 ? issues : ['No obvious 5NF violations detected'],
        suggestions: suggestions.length > 0
            ? suggestions
            : ['5NF compliance requires expert review of join dependencies']
    }
}

/**
 * Comprehensive normal form analysis
 */
export function analyzeAllNormalForms(table) {
    return {
        '1NF': analyze1NF(table),
        '2NF': analyze2NF(table),
        '3NF': analyze3NF(table),
        '4NF': analyze4NF(table),
        '5NF': analyze5NF(table),
        bcnf: table.bcnfAnalysis || { status: 'UNKNOWN', score: null }
    }
}

// Helper function to find repeating patterns in column names
function findRepeatingPatterns(columnNames) {
    const patterns = {}

    columnNames.forEach(name => {
        // Remove trailing numbers
        const base = name.replace(/\d+$/, '')
        if (base !== name && base.length > 2) {
            patterns[base] = (patterns[base] || 0) + 1
        }
    })

    return Object.entries(patterns)
        .filter(([_, count]) => count >= 2)
        .map(([base, count]) => ({ base, count }))
}
