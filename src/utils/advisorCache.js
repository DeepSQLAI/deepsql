/**
 * Advisor Score Cache Utility
 * Manages caching of advisor analysis scores with 24-hour expiration
 */

const ADVISOR_CACHE_PREFIX = 'advisor-score'
const CACHE_EXPIRY_HOURS = 24

/**
 * Get the localStorage key for advisor score
 */
export function getAdvisorCacheKey(connectionId) {
    if (!connectionId) return null
    return `${ADVISOR_CACHE_PREFIX}-${connectionId}`
}

/**
 * Save advisor score to cache
 */
export function saveAdvisorScore(connectionId, scoreData) {
    if (!connectionId) return
    
    const key = getAdvisorCacheKey(connectionId)
    if (!key) return
    
    try {
        const cacheData = {
            timestamp: Date.now(),
            data: scoreData
        }
        localStorage.setItem(key, JSON.stringify(cacheData))
    } catch (error) {
        console.error('Failed to save advisor score to cache:', error)
    }
}

/**
 * Load advisor score from cache
 */
export function loadAdvisorScore(connectionId) {
    if (!connectionId) return null
    
    const key = getAdvisorCacheKey(connectionId)
    if (!key) return null
    
    try {
        const cached = localStorage.getItem(key)
        if (cached) {
            const parsed = JSON.parse(cached)
            const now = Date.now()
            const cacheAge = now - parsed.timestamp
            const expiryMs = CACHE_EXPIRY_HOURS * 60 * 60 * 1000
            
            // Check if cache is still valid (less than 24 hours old)
            if (cacheAge < expiryMs) {
                return parsed.data
            } else {
                // Cache expired, remove it
                localStorage.removeItem(key)
            }
        }
    } catch (error) {
        console.error('Failed to load advisor score from cache:', error)
    }
    
    return null
}

/**
 * Check if cached score is expired (older than 24 hours)
 */
export function isAdvisorScoreExpired(connectionId) {
    if (!connectionId) return true
    
    const key = getAdvisorCacheKey(connectionId)
    if (!key) return true
    
    try {
        const cached = localStorage.getItem(key)
        if (cached) {
            const parsed = JSON.parse(cached)
            const now = Date.now()
            const cacheAge = now - parsed.timestamp
            const expiryMs = CACHE_EXPIRY_HOURS * 60 * 60 * 1000
            return cacheAge >= expiryMs
        }
    } catch (error) {
        console.error('Failed to check advisor score expiry:', error)
    }
    
    return true
}

/**
 * Clear advisor score cache
 */
export function clearAdvisorScore(connectionId) {
    if (!connectionId) return
    
    const key = getAdvisorCacheKey(connectionId)
    if (!key) return
    
    try {
        localStorage.removeItem(key)
    } catch (error) {
        console.error('Failed to clear advisor score cache:', error)
    }
}






