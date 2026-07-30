'use client'

import styles from './LoadingSkeleton.module.css'

/**
 * Loading skeleton components for better UX
 */
export function SkeletonCard() {
    return (
        <div className={styles.skeletonCard}>
            <div className={styles.skeletonTitle}></div>
            <div className={styles.skeletonText}></div>
            <div className={styles.skeletonText} style={{ width: '60%' }}></div>
        </div>
    )
}

export function SkeletonRow() {
    return (
        <div className={styles.skeletonRow}>
            <div className={styles.skeletonCell}></div>
            <div className={styles.skeletonCell} style={{ width: '40%' }}></div>
            <div className={styles.skeletonCell} style={{ width: '20%' }}></div>
        </div>
    )
}

export function SkeletonGrid({ rows = 5 }) {
    return (
        <div className={styles.skeletonGrid}>
            {Array.from({ length: rows }).map((_, i) => (
                <SkeletonRow key={i} />
            ))}
        </div>
    )
}

export function SkeletonPanel() {
    return (
        <div className={styles.skeletonPanel}>
            <div className={styles.skeletonHeader}></div>
            <div className={styles.skeletonContent}>
                <div className={styles.skeletonText}></div>
                <div className={styles.skeletonText}></div>
                <div className={styles.skeletonText} style={{ width: '80%' }}></div>
            </div>
        </div>
    )
}
