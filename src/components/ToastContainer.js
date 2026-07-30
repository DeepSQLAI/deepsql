'use client'

import { createPortal } from 'react-dom'
import Toast from './Toast'
import styles from './ToastContainer.module.css'

export default function ToastContainer({ toasts, removeToast }) {
    if (typeof window === 'undefined') return null

    return createPortal(
        <div className={styles.toastContainer}>
            {toasts.map((toast) => (
                <Toast
                    key={toast.id}
                    message={toast.message}
                    type={toast.type}
                    duration={toast.duration}
                    onClose={() => removeToast(toast.id)}
                />
            ))}
        </div>,
        document.body
    )
}
