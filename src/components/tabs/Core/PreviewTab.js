'use client'

import { useState } from 'react'
import { useDashboardConfig, useIsBuildingDashboard, useDashboardActions } from '@/lib/stores'
import DashboardBuilder from '../../DashboardBuilder'
import DashboardBuilderEffect from '../../DashboardBuilderEffect'
import SavedDashboardsPanel from '../../SavedDashboardsPanel'
import styles from './PreviewTab.module.css'

export default function PreviewTab({ connectionId }) {
    // Zustand stores - selective subscriptions for optimal performance
    const dashboardConfig = useDashboardConfig()
    const isBuildingDashboard = useIsBuildingDashboard()
    const { setDashboardConfig, clearDashboard } = useDashboardActions()

    const [showSaveModal, setShowSaveModal] = useState(false)
    const [saveDashboardData, setSaveDashboardData] = useState(null)

    const handleClearDashboard = () => {
        clearDashboard()
    }

    const handleSave = (config) => {
        // Prepare dashboard data for saving
        const dashboardData = {
            dashboardConfig: typeof config === 'string' ? config : JSON.stringify(config, null, 2),
            name: config.title || 'Untitled Dashboard',
            description: config.description || ''
        }
        setSaveDashboardData(dashboardData)
        setShowSaveModal(true)
    }

    const handleLoadDashboard = (config) => {
        // Load dashboard config and open edit mode
        setDashboardConfig({
            ...config,
            updatedAt: new Date().toISOString()
        })
        // The DashboardBuilder will handle opening edit mode if needed
    }

    return (
        <div className={styles.preview} id="dashboard-container" style={{ position: 'relative' }}>
            <DashboardBuilder
                connectionId={connectionId}
                dashboardConfig={dashboardConfig}
                setDashboardConfig={setDashboardConfig}
                onClear={handleClearDashboard}
                onSave={handleSave}
            />
            {isBuildingDashboard && (
                <DashboardBuilderEffect isBuilding={isBuildingDashboard} />
            )}
            <SavedDashboardsPanel
                connectionId={connectionId}
                onLoadDashboard={handleLoadDashboard}
                initialDashboard={saveDashboardData}
                showSaveModal={showSaveModal}
                onCloseSaveModal={() => {
                    setShowSaveModal(false)
                    setSaveDashboardData(null)
                }}
            />
        </div>
    )
}
