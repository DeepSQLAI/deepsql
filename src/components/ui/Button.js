'use client'

import { Loader2 } from 'lucide-react'
import styles from './ui.module.css'

/**
 * Button component with variants and loading state
 *
 * @param {Object} props
 * @param {React.ReactNode} props.children - Button content
 * @param {'primary' | 'secondary' | 'ghost'} [props.variant='primary'] - Button variant
 * @param {'small' | 'medium'} [props.size='medium'] - Button size
 * @param {boolean} [props.icon] - Icon-only button style
 * @param {boolean} [props.loading=false] - Show loading spinner
 * @param {boolean} [props.disabled=false] - Disabled state
 * @param {string} [props.className] - Additional CSS class
 * @param {Function} [props.onClick] - Click handler
 * @param {'button' | 'submit' | 'reset'} [props.type='button'] - Button type
 */
export default function Button({
    children,
    variant = 'primary',
    size = 'medium',
    icon = false,
    loading = false,
    disabled = false,
    className = '',
    onClick,
    type = 'button',
    ...rest
}) {
    const variantClass = {
        primary: styles.buttonPrimary,
        secondary: styles.buttonSecondary,
        ghost: styles.buttonGhost,
    }[variant]

    const sizeClass = size === 'small' ? styles.buttonSmall : ''
    const iconClass = icon ? styles.buttonIcon : ''

    return (
        <button
            type={type}
            className={`${styles.button} ${variantClass} ${sizeClass} ${iconClass} ${className}`}
            onClick={onClick}
            disabled={disabled || loading}
            {...rest}
        >
            {loading ? (
                <>
                    <Loader2 size={14} className={styles.spinner} />
                    {!icon && children}
                </>
            ) : (
                children
            )}
        </button>
    )
}

/**
 * Icon button component for action icons
 *
 * @param {Object} props
 * @param {React.ReactNode} props.children - Icon content
 * @param {string} [props.title] - Button title/tooltip
 * @param {boolean} [props.disabled=false] - Disabled state
 * @param {string} [props.className] - Additional CSS class
 * @param {Function} [props.onClick] - Click handler
 */
export function IconButton({
    children,
    title,
    disabled = false,
    className = '',
    onClick,
    ...rest
}) {
    return (
        <button
            type="button"
            className={`${styles.iconButton} ${className}`}
            onClick={onClick}
            disabled={disabled}
            title={title}
            {...rest}
        >
            {children}
        </button>
    )
}
