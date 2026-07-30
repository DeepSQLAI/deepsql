'use client'

import styles from './Skeleton.module.css'

export default function Skeleton({
    width = '100%',
    height = '14px',
    borderRadius = '4px',
    className = '',
    variant = 'text' // 'text', 'rectangular', 'circular'
}) {
    const style = {
        width,
        height,
        borderRadius: variant === 'circular' ? '50%' : borderRadius
    }

    return (
        <div
            className={`${styles.skeleton} ${className}`}
            style={style}
        />
    )
}

// Pre-built skeleton variants for common use cases
export function SkeletonText({ width = '100%', lines = 1 }) {
    if (lines === 1) {
        return <Skeleton width={width} height="14px" />
    }

    return (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '8px' }}>
            {Array.from({ length: lines }).map((_, i) => (
                <Skeleton
                    key={i}
                    width={i === lines - 1 ? `${Math.random() * 30 + 60}%` : '100%'}
                    height="14px"
                />
            ))}
        </div>
    )
}

export function SkeletonTableRow({ columns = 5 }) {
    return (
        <tr className={styles.skeletonRow}>
            {Array.from({ length: columns }).map((_, i) => {
                // First column (table name) should be wider
                const width = i === 0 ? `${Math.random() * 20 + 70}%` : `${Math.random() * 30 + 60}%`
                return (
                    <td key={i}>
                        <Skeleton width={width} height="14px" />
                    </td>
                )
            })}
        </tr>
    )
}

export function SkeletonCard({ height = '120px' }) {
    return (
        <div className={styles.skeletonCard} style={{ height }}>
            <Skeleton width="60%" height="20px" borderRadius="6px" />
            <Skeleton width="40%" height="16px" borderRadius="4px" />
            <Skeleton width="80%" height="14px" borderRadius="4px" />
        </div>
    )
}
