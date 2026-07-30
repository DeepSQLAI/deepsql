'use client'

import { useEffect } from 'react'
import { CheckCircle, XCircle, AlertTriangle, Info, X } from 'lucide-react'
import styles from './Toast.module.css'

export default function Toast({ message, type = 'success', onClose, duration = 3000 }) {
    useEffect(() => {
        if (duration > 0) {
            const timer = setTimeout(() => {
                onClose()
            }, duration)
            return () => clearTimeout(timer)
        }
    }, [duration, onClose])

    const icons = {
        success: <CheckCircle size={20} />,
        error: <XCircle size={20} />,
        warning: <AlertTriangle size={20} />,
        info: <Info size={20} />
    }

    return (
        <div className={`${styles.toast} ${styles[type]}`}>
            <div className={styles.iconWrapper}>
                {icons[type]}
            </div>
            <div className={styles.message}>
                {message}
            </div>
            <button onClick={onClose} className={styles.closeButton}>
                <X size={16} />
            </button>
        </div>
    )
}
