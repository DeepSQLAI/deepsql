/**
 * Tab State Cache Utility
 * Manages saving and restoring tab states to/from localStorage
 */

const TAB_STATE_PREFIX = 'tab-state'
const TAB_STATE_VERSION = 1

// ── Schema object cache (separate key, TTL-aware) ──────────────────────────
const SCHEMA_CACHE_PREFIX = 'schema-objects'
const SCHEMA_CACHE_TTL_MS = 30 * 60 * 1000  // 30 minutes

export function saveSchemaCache(connectionId, objects, username = '') {
    if (!connectionId || !Array.isArray(objects)) return
    try {
        localStorage.setItem(
            schemaCacheKey(connectionId, username),
            JSON.stringify({ timestamp: Date.now(), objects })
        )
    } catch (e) {
        // localStorage full — silently ignore
    }
}

export function loadSchemaCache(connectionId, username = '') {
    if (!connectionId) return null
    try {
        const raw = localStorage.getItem(schemaCacheKey(connectionId, username))
        if (!raw) return null
        const { timestamp, objects } = JSON.parse(raw)
        if (Date.now() - timestamp > SCHEMA_CACHE_TTL_MS) return null  // stale
        return objects
    } catch {
        return null
    }
}

export function clearSchemaCache(connectionId, username = '') {
    if (!connectionId) return
    localStorage.removeItem(schemaCacheKey(connectionId, username))
}

function schemaCacheKey(connectionId, username) {
    return `${SCHEMA_CACHE_PREFIX}-${connectionId}-${username || 'anon'}`
}

/**
 * Get the localStorage key for a tab's state
 */
export function getTabStateKey(connectionId, tabId) {
    if (!connectionId || !tabId) return null
    return `${TAB_STATE_PREFIX}-${connectionId}-${tabId}`
}

/**
 * Save tab state to localStorage
 */
export function saveTabState(connectionId, tabId, state) {
    if (!connectionId || !tabId) return
    
    const key = getTabStateKey(connectionId, tabId)
    if (!key) return
    
    try {
        const stateToSave = {
            version: TAB_STATE_VERSION,
            timestamp: Date.now(),
            data: state
        }
        localStorage.setItem(key, JSON.stringify(stateToSave))
    } catch (error) {
        console.error(`Failed to save tab state for ${tabId}:`, error)
    }
}

/**
 * Load tab state from localStorage
 */
export function loadTabState(connectionId, tabId) {
    if (!connectionId || !tabId) return null
    
    const key = getTabStateKey(connectionId, tabId)
    if (!key) return null
    
    try {
        const saved = localStorage.getItem(key)
        if (saved) {
            const parsed = JSON.parse(saved)
            // Return the data, ignoring version and timestamp for now
            return parsed.data || null
        }
    } catch (error) {
        console.error(`Failed to load tab state for ${tabId}:`, error)
    }
    
    return null
}

/**
 * Clear tab state from localStorage
 */
export function clearTabState(connectionId, tabId) {
    if (!connectionId || !tabId) return
    
    const key = getTabStateKey(connectionId, tabId)
    if (!key) return
    
    try {
        localStorage.removeItem(key)
    } catch (error) {
        console.error(`Failed to clear tab state for ${tabId}:`, error)
    }
}

/**
 * Clear all tab states for a connection
 */
export function clearAllTabStates(connectionId) {
    if (!connectionId) return
    
    try {
        const prefix = `${TAB_STATE_PREFIX}-${connectionId}-`
        const keysToRemove = []
        
        for (let i = 0; i < localStorage.length; i++) {
            const key = localStorage.key(i)
            if (key && key.startsWith(prefix)) {
                keysToRemove.push(key)
            }
        }
        
        keysToRemove.forEach(key => localStorage.removeItem(key))
    } catch (error) {
        console.error(`Failed to clear all tab states for connection ${connectionId}:`, error)
    }
}






