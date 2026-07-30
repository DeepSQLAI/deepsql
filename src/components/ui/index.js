/**
 * Shared UI Components
 *
 * Import from '@/components/ui' for reusable UI primitives.
 *
 * Usage:
 *   import { LoadingState, ErrorState, EmptyState, Modal, Panel, Button, StatusBadge } from '@/components/ui'
 */

// State Components
export { default as LoadingState } from './LoadingState'
export { default as ErrorState } from './ErrorState'
export { default as EmptyState } from './EmptyState'

// Layout Components
export { default as Modal } from './Modal'
export { default as Panel } from './Panel'

// Interactive Components
export { default as Button, IconButton } from './Button'

// Display Components
export {
    default as StatusBadge,
    getHealthVariant,
    getSeverityVariant,
    getStatusVariant,
} from './StatusBadge'
