'use client'

import { useEffect, useCallback } from 'react'
import { createPortal } from 'react-dom'
import { X } from 'lucide-react'
import styles from './ui.module.css'

/**
 * Modal component with portal rendering
 *
 * @param {Object} props
 * @param {boolean} props.isOpen - Modal open state
 * @param {Function} props.onClose - Close callback
 * @param {string} [props.title] - Modal title
 * @param {React.ReactNode} props.children - Modal content
 * @param {React.ReactNode} [props.footer] - Footer content
 * @param {'small' | 'medium' | 'large'} [props.size='medium'] - Modal size
 * @param {boolean} [props.closeOnOverlayClick=true] - Close on overlay click
 * @param {boolean} [props.closeOnEscape=true] - Close on Escape key
 * @param {boolean} [props.showCloseButton=true] - Show close button in header
 */
export default function Modal({
    isOpen,
    onClose,
    title,
    children,
    footer,
    size = 'medium',
    closeOnOverlayClick = true,
    closeOnEscape = true,
    showCloseButton = true,
}) {
    // Handle Escape key
    const handleKeyDown = useCallback((e) => {
        if (closeOnEscape && e.key === 'Escape') {
            onClose()
        }
    }, [closeOnEscape, onClose])

    useEffect(() => {
        if (isOpen) {
            document.addEventListener('keydown', handleKeyDown)
            document.body.style.overflow = 'hidden'
        }

        return () => {
            document.removeEventListener('keydown', handleKeyDown)
            document.body.style.overflow = ''
        }
    }, [isOpen, handleKeyDown])

    if (!isOpen) return null

    const sizeClass = {
        small: styles.modalSmall,
        medium: styles.modalMedium,
        large: styles.modalLarge,
    }[size]

    const handleOverlayClick = (e) => {
        if (closeOnOverlayClick && e.target === e.currentTarget) {
            onClose()
        }
    }

    const modalContent = (
        <div className={styles.modalOverlay} onClick={handleOverlayClick}>
            <div className={`${styles.modalContent} ${sizeClass}`} role="dialog" aria-modal="true">
                {title && (
                    <div className={styles.modalHeader}>
                        <h3 className={styles.modalTitle}>{title}</h3>
                        {showCloseButton && (
                            <button
                                className={styles.modalCloseButton}
                                onClick={onClose}
                                aria-label="Close modal"
                            >
                                <X size={20} />
                            </button>
                        )}
                    </div>
                )}
                <div className={styles.modalBody}>
                    {children}
                </div>
                {footer && (
                    <div className={styles.modalFooter}>
                        {footer}
                    </div>
                )}
            </div>
        </div>
    )

    // Use portal to render at document body level
    if (typeof document !== 'undefined') {
        return createPortal(modalContent, document.body)
    }

    return modalContent
}
