'use client'

import { useState } from 'react'
import { ChevronDown, ChevronUp } from 'lucide-react'
import styles from './ui.module.css'

/**
 * Panel component with optional collapsible functionality
 *
 * @param {Object} props
 * @param {string} [props.title] - Panel title
 * @param {React.ReactNode} [props.icon] - Title icon
 * @param {React.ReactNode} [props.actions] - Header action buttons
 * @param {React.ReactNode} props.children - Panel content
 * @param {boolean} [props.collapsible=false] - Enable collapse functionality
 * @param {boolean} [props.defaultExpanded=true] - Initial expanded state
 * @param {boolean} [props.noPadding=false] - Remove body padding
 * @param {string} [props.className] - Additional CSS class
 */
export default function Panel({
    title,
    icon,
    actions,
    children,
    collapsible = false,
    defaultExpanded = true,
    noPadding = false,
    className = '',
}) {
    const [expanded, setExpanded] = useState(defaultExpanded)

    const handleHeaderClick = () => {
        if (collapsible) {
            setExpanded(prev => !prev)
        }
    }

    return (
        <div className={`${styles.panel} ${collapsible ? styles.panelCollapsible : ''} ${className}`}>
            {title && (
                <div
                    className={styles.panelHeader}
                    onClick={handleHeaderClick}
                    role={collapsible ? 'button' : undefined}
                    tabIndex={collapsible ? 0 : undefined}
                    onKeyDown={collapsible ? (e) => e.key === 'Enter' && handleHeaderClick() : undefined}
                >
                    <h4 className={styles.panelTitle}>
                        {icon}
                        {title}
                    </h4>
                    <div className={styles.panelActions}>
                        {actions}
                        {collapsible && (
                            expanded ? <ChevronUp size={16} /> : <ChevronDown size={16} />
                        )}
                    </div>
                </div>
            )}
            {(!collapsible || expanded) && (
                <div className={noPadding ? styles.panelBodyNoPadding : styles.panelBody}>
                    {children}
                </div>
            )}
        </div>
    )
}
