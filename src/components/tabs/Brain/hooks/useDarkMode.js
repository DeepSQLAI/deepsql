import { useState, useEffect } from 'react'

/**
 * Custom hook for dark mode support
 * Syncs with system preferences and local storage
 */
export function useDarkMode() {
    const [isDark, setIsDark] = useState(() => {
        // Check local storage first
        if (typeof window !== 'undefined') {
            const saved = localStorage.getItem('brain-dark-mode')
            if (saved !== null) {
                return saved === 'true'
            }
            // Fall back to system preference
            return window.matchMedia('(prefers-color-scheme: dark)').matches
        }
        return false
    })

    useEffect(() => {
        // Save preference to local storage
        if (typeof window !== 'undefined') {
            localStorage.setItem('brain-dark-mode', isDark)
        }

        // Apply dark mode class to document
        if (isDark) {
            document.documentElement.classList.add('dark-mode')
        } else {
            document.documentElement.classList.remove('dark-mode')
        }
    }, [isDark])

    useEffect(() => {
        // Listen for system theme changes
        if (typeof window !== 'undefined') {
            const mediaQuery = window.matchMedia('(prefers-color-scheme: dark)')
            const handleChange = (e) => {
                // Only update if user hasn't explicitly set a preference
                const saved = localStorage.getItem('brain-dark-mode')
                if (saved === null) {
                    setIsDark(e.matches)
                }
            }

            mediaQuery.addEventListener('change', handleChange)
            return () => mediaQuery.removeEventListener('change', handleChange)
        }
    }, [])

    const toggle = () => setIsDark(!isDark)

    return { isDark, toggle }
}
