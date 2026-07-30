'use client'

import { useMemo, useCallback } from 'react'
import ForceGraph2D from 'react-force-graph-2d'
import styles from './SchemaERD.module.css'

/**
 * Entity Relationship Diagram visualization
 * Shows tables and their relationships
 */
export function SchemaERD({ tables = [], height = 600 }) {
    // Transform tables data into graph format
    const graphData = useMemo(() => {
        const nodes = []
        const links = []

        // Create nodes for each table
        tables.forEach(table => {
            nodes.push({
                id: table.tableName,
                name: table.tableName,
                val: (table.columnCount || 10), // Size based on column count
                color: getNodeColor(table)
            })
        })

        // Create links based on foreign keys
        tables.forEach(table => {
            const foreignKeys = table.foreignKeys || []
            foreignKeys.forEach(fk => {
                if (fk.referencedTable) {
                    links.push({
                        source: table.tableName,
                        target: fk.referencedTable,
                        label: fk.columnName
                    })
                }
            })
        })

        return { nodes, links }
    }, [tables])

    const getNodeColor = (table) => {
        // Color based on BCNF status
        const bcnfStatus = (table.bcnfStatus || '').toLowerCase()
        if (bcnfStatus === 'likely') return 'var(--color-success)'
        if (bcnfStatus === 'review') return 'var(--color-warning)'
        return 'var(--color-light-5)'
    }

    const handleNodeClick = useCallback((node) => {
        console.log('Clicked table:', node.name)
    }, [])

    if (tables.length === 0) {
        return (
            <div className={styles.empty}>
                <p>No schema data available for visualization</p>
            </div>
        )
    }

    return (
        <div className={styles.erdContainer}>
            <div className={styles.legend}>
                <div className={styles.legendItem}>
                    <div className={`${styles.legendDot} ${styles.green}`}></div>
                    <span>BCNF Compliant</span>
                </div>
                <div className={styles.legendItem}>
                    <div className={`${styles.legendDot} ${styles.orange}`}></div>
                    <span>Needs Review</span>
                </div>
                <div className={styles.legendItem}>
                    <div className={`${styles.legendDot} ${styles.gray}`}></div>
                    <span>Unknown</span>
                </div>
            </div>

            <ForceGraph2D
                graphData={graphData}
                height={height}
                nodeLabel="name"
                nodeColor="color"
                nodeRelSize={6}
                linkDirectionalArrowLength={3.5}
                linkDirectionalArrowRelPos={1}
                linkCurvature={0.25}
                onNodeClick={handleNodeClick}
                enableNodeDrag={true}
                enableZoomPanInteraction={true}
            />
        </div>
    )
}
