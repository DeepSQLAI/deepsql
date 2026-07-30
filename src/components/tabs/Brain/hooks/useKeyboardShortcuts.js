import { useEffect, useCallback } from 'react'

/**
 * Custom hook for keyboard shortcuts
 * Improves navigation and productivity
 */
export function useKeyboardShortcuts(shortcuts = {}) {
    const handleKeyDown = useCallback((event) => {
        // Check if user is typing in an input/textarea
        const activeElement = document.activeElement
        const isTyping = activeElement?.tagName === 'INPUT' ||
                        activeElement?.tagName === 'TEXTAREA' ||
                        activeElement?.isContentEditable

        // Don't trigger shortcuts while typing (except for Escape)
        if (isTyping && event.key !== 'Escape') {
            return
        }

        // Build key combination string
        const modifiers = []
        if (event.ctrlKey) modifiers.push('ctrl')
        if (event.altKey) modifiers.push('alt')
        if (event.shiftKey) modifiers.push('shift')
        if (event.metaKey) modifiers.push('meta')

        const key = event.key.toLowerCase()
        const combination = [...modifiers, key].join('+')

        // Check if this combination has a handler
        const handler = shortcuts[combination] || shortcuts[key]

        if (handler) {
            event.preventDefault()
            handler(event)
        }
    }, [shortcuts])

    useEffect(() => {
        document.addEventListener('keydown', handleKeyDown)
        return () => {
            document.removeEventListener('keydown', handleKeyDown)
        }
    }, [handleKeyDown])
}

/**
 * Pre-defined keyboard shortcuts for Brain tab
 */
export const BRAIN_SHORTCUTS = {
    't': 'Train Brain',
    'p': 'Profile columns',
    'r': 'Rescan schema',
    'n': 'Add new note',
    '/': 'Focus search',
    'escape': 'Close modal',
    'ctrl+k': 'Command palette',
    '?': 'Show shortcuts help'
}
