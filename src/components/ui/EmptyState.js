'use client'

import { Inbox } from 'lucide-react'
import styles from './ui.module.css'

/**
 * Empty state component with optional icon and actions
 *
 * @param {Object} props
 * @param {string} [props.title='No Data'] - Title text
 * @param {string} [props.message] - Description message
 * @param {React.ReactNode} [props.icon] - Custom icon (defaults to Inbox)
 * @param {number} [props.iconSize=48] - Icon size
 * @param {React.ReactNode} [props.actions] - Action buttons/content
 */
export default function EmptyState({
    title = 'No Data',
    message,
    icon,
    iconSize = 48,
    actions,
}) {
    const IconComponent = icon || <Inbox size={iconSize} className={styles.emptyStateIcon} />

    return (
        <div className={styles.emptyState}>
            {typeof icon === 'function' ? icon({ size: iconSize, className: styles.emptyStateIcon }) : IconComponent}
            <h3>{title}</h3>
            {message && <p>{message}</p>}
            {actions && <div className={styles.emptyStateActions}>{actions}</div>}
        </div>
    )
}
