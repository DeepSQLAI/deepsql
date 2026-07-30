'use client'

import { useMemo } from 'react'
import dynamic from 'next/dynamic'
import styles from './SchemaVisualization.module.css'

const ForceGraph2D = dynamic(() => import('react-force-graph-2d'), { ssr: false })

export default function SchemaVisualization({ erDiagramData }) {
    const graphData = useMemo(() => {
        if (!erDiagramData || !erDiagramData.entities) {
            return { nodes: [], links: [] }
        }

        const nodes = erDiagramData.entities.map((entity, index) => ({
            id: entity.name,
            name: entity.name,
            type: entity.type,
            columns: entity.columns || [],
            val: 30, // Node size value
        }))

        const links = erDiagramData.relationships?.map((rel) => ({
            source: rel.from,
            target: rel.to,
            type: rel.type,
            foreignKey: rel.foreignKey,
        })) || []

        return { nodes, links }
    }, [erDiagramData])

    if (!erDiagramData || graphData.nodes.length === 0) {
        return (
            <div className={styles.emptyState}>
                <p>No schema data available. Please scan a database connection.</p>
            </div>
        )
    }

    return (
        <div className={styles.container}>
            <ForceGraph2D
                graphData={graphData}
                nodeLabel={(node) => {
                    const columns = node.columns?.slice(0, 10).map(col =>
                        `${col.primaryKey ? '🔑 ' : ''}${col.name}: ${col.dataType || col.type}`
                    ).join('\n') || ''
                    const extra = node.columns?.length > 10 ? `\n... ${node.columns.length - 10} more columns` : ''
                    return `${node.name}\n${columns}${extra}`
                }}
                nodeColor={(node) => node.type === 'view' ? '#60a5fa' : '#34d399'}
                linkLabel={(link) => link.foreignKey ? `FK: ${link.foreignKey}` : link.type}
                linkDirectionalArrowLength={6}
                linkDirectionalArrowRelPos={0.9}
                linkCurvature={0.2}
                linkColor={() => '#94a3b8'}
                linkWidth={2}
                nodeCanvasObject={(node, ctx, globalScale) => {
                    // Safety check for valid coordinates
                    if (!node || !isFinite(node.x) || !isFinite(node.y) || !isFinite(globalScale)) {
                        return
                    }

                    const label = node.name
                    const fontSize = 14 / globalScale
                    const padding = 8 / globalScale

                    // Measure text
                    ctx.font = `bold ${fontSize}px Sans-Serif`
                    const textWidth = ctx.measureText(label).width
                    const boxWidth = Math.max(textWidth + padding * 2, 100 / globalScale)
                    const boxHeight = 40 / globalScale

                    // Draw rounded rectangle background
                    const radius = 6 / globalScale
                    const x = node.x - boxWidth / 2
                    const y = node.y - boxHeight / 2

                    // Background
                    ctx.fillStyle = '#ffffff'
                    ctx.beginPath()
                    ctx.roundRect(x, y, boxWidth, boxHeight, radius)
                    ctx.fill()

                    // Border
                    ctx.strokeStyle = node.type === 'view' ? '#60a5fa' : '#34d399'
                    ctx.lineWidth = 2 / globalScale
                    ctx.stroke()

                    // Table name
                    ctx.fillStyle = '#0a0a0a'
                    ctx.textAlign = 'center'
                    ctx.textBaseline = 'middle'
                    ctx.fillText(label, node.x, node.y)

                    // Column count badge
                    if (node.columns && node.columns.length > 0) {
                        const badgeText = `${node.columns.length} cols`
                        ctx.font = `${fontSize * 0.75}px Sans-Serif`
                        ctx.fillStyle = '#737373'
                        ctx.fillText(badgeText, node.x, node.y + boxHeight / 2 - padding * 2)
                    }
                }}
                nodeCanvasObjectMode={() => 'replace'}
                onNodeClick={(node) => {
                    console.log('Table clicked:', node.name, node.columns)
                }}
                d3AlphaDecay={0.02}
                d3VelocityDecay={0.3}
                cooldownTime={3000}
                enableNodeDrag={true}
                enableZoomInteraction={true}
                enablePanInteraction={true}
            />
        </div>
    )
}

