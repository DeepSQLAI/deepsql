'use client'

import { Cloud, Loader } from 'lucide-react'
import { useSlowLogSourceConfig } from '@/lib/hooks/queries'
import { getProviderLabel } from '@/components/tabs/Performance/SlowQueries/utils'
import styles from './ConnectionSlowQueryConfig.module.css'

/**
 * Status badge for slow query source config in the connections table.
 * Clicking opens the full SlowQuerySourceModal (managed by parent).
 */
export default function ConnectionSlowQueryConfig({ connectionId, onClick }) {
    const { data: sourceConfigData, isLoading } = useSlowLogSourceConfig(connectionId)
    const hasConfig = Boolean(sourceConfigData?.id)

    return (
        <button
            className={styles.toggleButton}
            onClick={onClick}
            title={hasConfig ? `${getProviderLabel(sourceConfigData.providerType)} configured` : 'Configure slow query source'}
        >
            <Cloud size={14} />
            {isLoading ? (
                <Loader size={12} className={styles.spinner} />
            ) : (
                <>
                    <span className={`${styles.statusDot} ${hasConfig ? styles.statusDotOn : styles.statusDotOff}`} />
                    <span className={styles.label}>
                        {hasConfig ? getProviderLabel(sourceConfigData.providerType) : 'Configure'}
                    </span>
                </>
            )}
        </button>
    )
}
