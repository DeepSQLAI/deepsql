'use client'

import styles from './Pagination.module.css'

/**
 * Improved pagination component
 * Supports custom page sizes and dynamic navigation
 */
export function Pagination({
    currentPage = 1,
    totalItems = 0,
    pageSize = 20,
    onPageChange,
    onPageSizeChange,
    pageSizeOptions = [10, 20, 50, 100]
}) {
    const totalPages = Math.ceil(totalItems / pageSize)
    const startItem = totalItems === 0 ? 0 : (currentPage - 1) * pageSize + 1
    const endItem = Math.min(currentPage * pageSize, totalItems)

    const canGoPrevious = currentPage > 1
    const canGoNext = currentPage < totalPages

    const handlePrevious = () => {
        if (canGoPrevious) {
            onPageChange(currentPage - 1)
        }
    }

    const handleNext = () => {
        if (canGoNext) {
            onPageChange(currentPage + 1)
        }
    }

    const handleFirst = () => {
        onPageChange(1)
    }

    const handleLast = () => {
        onPageChange(totalPages)
    }

    // Generate page numbers to display
    const getPageNumbers = () => {
        const pages = []
        const maxVisible = 7

        if (totalPages <= maxVisible) {
            for (let i = 1; i <= totalPages; i++) {
                pages.push(i)
            }
        } else {
            // Always show first page
            pages.push(1)

            if (currentPage > 3) {
                pages.push('...')
            }

            // Show pages around current
            const start = Math.max(2, currentPage - 1)
            const end = Math.min(totalPages - 1, currentPage + 1)

            for (let i = start; i <= end; i++) {
                pages.push(i)
            }

            if (currentPage < totalPages - 2) {
                pages.push('...')
            }

            // Always show last page
            pages.push(totalPages)
        }

        return pages
    }

    if (totalItems === 0) {
        return null
    }

    return (
        <div className={styles.pagination}>
            <div className={styles.info}>
                Showing {startItem}-{endItem} of {totalItems}
            </div>

            <div className={styles.controls}>
                <button
                    className={styles.pageButton}
                    onClick={handleFirst}
                    disabled={!canGoPrevious}
                    title="First page"
                >
                    &laquo;
                </button>

                <button
                    className={styles.pageButton}
                    onClick={handlePrevious}
                    disabled={!canGoPrevious}
                    title="Previous page"
                >
                    &lsaquo;
                </button>

                {getPageNumbers().map((page, index) => (
                    page === '...' ? (
                        <span key={`ellipsis-${index}`} className={styles.ellipsis}>
                            ...
                        </span>
                    ) : (
                        <button
                            key={page}
                            className={`${styles.pageButton} ${currentPage === page ? styles.active : ''}`}
                            onClick={() => onPageChange(page)}
                        >
                            {page}
                        </button>
                    )
                ))}

                <button
                    className={styles.pageButton}
                    onClick={handleNext}
                    disabled={!canGoNext}
                    title="Next page"
                >
                    &rsaquo;
                </button>

                <button
                    className={styles.pageButton}
                    onClick={handleLast}
                    disabled={!canGoNext}
                    title="Last page"
                >
                    &raquo;
                </button>
            </div>

            {onPageSizeChange && (
                <div className={styles.pageSize}>
                    <label htmlFor="page-size">Per page:</label>
                    <select
                        id="page-size"
                        value={pageSize}
                        onChange={(e) => onPageSizeChange(Number(e.target.value))}
                    >
                        {pageSizeOptions.map(size => (
                            <option key={size} value={size}>{size}</option>
                        ))}
                    </select>
                </div>
            )}
        </div>
    )
}
