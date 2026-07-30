'use client'

import { useMemo } from 'react'
import dynamic from 'next/dynamic'
import styles from './DependencyGraph.module.css'

const ForceGraph2D = dynamic(() => import('react-force-graph-2d'), { ssr: false })

export default function DependencyGraph({ dependencyGraphData }) {
    const graphData = useMemo(() => {
        if (!dependencyGraphData || !dependencyGraphData.nodes) {
            return { nodes: [], links: [] }
        }

        const nodes = dependencyGraphData.nodes.map((node) => ({
            id: node.id,
            name: node.id,
            type: node.type,
            dependencies: node.dependencies || [],
            val: 35, // Node size
        }))

        const links = dependencyGraphData.edges?.map((edge) => ({
            source: edge.from,
            target: edge.to,
            relation: edge.relation,
        })) || []

        return { nodes, links }
    }, [dependencyGraphData])

    if (!dependencyGraphData || graphData.nodes.length === 0) {
        return (
            <div className={styles.emptyState}>
                <p>No dependency data available. Please scan a database connection.</p>
            </div>
        )
    }

    const getNodeColor = (node) => {
        if (node.type === 'view') return '#8b5cf6' // Purple for views
        if (node.dependencies?.length === 0) return '#34d399' // Green for independent tables
        return 'var(--color-warning)' // Amber for dependent tables
    }

    const getNodeShape = (node) => {
        return node.type === 'view' ? 'diamond' : 'box'
    }

    return (
        <div className={styles.container}>
            <div className={styles.legend}>
                <div className={styles.legendItem}>
                    <div className={styles.legendBox} style={{ background: '#34d399' }}></div>
                    <span>Independent Tables</span>
                </div>
                <div className={styles.legendItem}>
                    <div className={styles.legendBox} style={{ background: 'var(--color-warning)' }}></div>
                    <span>Dependent Tables</span>
                </div>
                <div className={styles.legendItem}>
                    <div className={styles.legendDiamond} style={{ background: '#8b5cf6' }}></div>
                    <span>Views</span>
                </div>
            </div>
            <ForceGraph2D
                graphData={graphData}
                nodeLabel={(node) => {
                    const type = node.type === 'view' ? 'View' : 'Table'
                    const deps = node.dependencies?.length > 0
                        ? `\nDepends on:\n${node.dependencies.slice(0, 5).join('\n')}${node.dependencies.length > 5 ? '\n...' : ''}`
                        : '\nNo dependencies'
                    return `${type}: ${node.name}${deps}`
                }}
                nodeColor={getNodeColor}
                linkLabel={(link) => link.relation || 'references'}
                linkDirectionalArrowLength={8}
                linkDirectionalArrowRelPos={0.9}
                linkCurvature={0.25}
                linkColor={() => '#64748b'}
                linkWidth={2.5}
                linkDirectionalParticles={2}
                linkDirectionalParticleWidth={2}
                nodeCanvasObject={(node, ctx, globalScale) => {
                    // Safety check for valid coordinates
                    if (!node || !isFinite(node.x) || !isFinite(node.y) || !isFinite(globalScale)) {
                        return
                    }

                    const label = node.name
                    const fontSize = 13 / globalScale
                    const padding = 10 / globalScale
                    const shape = getNodeShape(node)

                    // Measure text
                    ctx.font = `bold ${fontSize}px Sans-Serif`
                    const textWidth = ctx.measureText(label).width
                    const boxWidth = Math.max(textWidth + padding * 2, 120 / globalScale)
                    const boxHeight = 50 / globalScale

                    const x = node.x
                    const y = node.y

                    if (shape === 'diamond') {
                        // Draw diamond for views
                        const size = Math.max(boxWidth, boxHeight) / 1.5
                        ctx.fillStyle = '#ffffff'
                        ctx.beginPath()
                        ctx.moveTo(x, y - size / 2)
                        ctx.lineTo(x + size / 2, y)
                        ctx.lineTo(x, y + size / 2)
                        ctx.lineTo(x - size / 2, y)
                        ctx.closePath()
                        ctx.fill()

                        // Border
                        ctx.strokeStyle = getNodeColor(node)
                        ctx.lineWidth = 3 / globalScale
                        ctx.stroke()

                        // Add shadow for depth
                        ctx.shadowColor = 'rgba(0, 0, 0, 0.1)'
                        ctx.shadowBlur = 4 / globalScale
                        ctx.shadowOffsetX = 2 / globalScale
                        ctx.shadowOffsetY = 2 / globalScale
                    } else {
                        // Draw rounded rectangle for tables
                        const radius = 8 / globalScale
                        const rectX = x - boxWidth / 2
                        const rectY = y - boxHeight / 2

                        // Shadow
                        ctx.shadowColor = 'rgba(0, 0, 0, 0.15)'
                        ctx.shadowBlur = 6 / globalScale
                        ctx.shadowOffsetX = 2 / globalScale
                        ctx.shadowOffsetY = 2 / globalScale

                        // Background
                        ctx.fillStyle = '#ffffff'
                        ctx.beginPath()
                        ctx.roundRect(rectX, rectY, boxWidth, boxHeight, radius)
                        ctx.fill()

                        // Reset shadow
                        ctx.shadowColor = 'transparent'
                        ctx.shadowBlur = 0
                        ctx.shadowOffsetX = 0
                        ctx.shadowOffsetY = 0

                        // Border with gradient effect
                        const gradient = ctx.createLinearGradient(rectX, rectY, rectX, rectY + boxHeight)
                        const baseColor = getNodeColor(node)
                        gradient.addColorStop(0, baseColor)
                        gradient.addColorStop(1, baseColor + 'cc')
                        ctx.strokeStyle = gradient
                        ctx.lineWidth = 3 / globalScale
                        ctx.stroke()

                        // Top bar for dependent tables
                        if (node.dependencies?.length > 0) {
                            ctx.fillStyle = getNodeColor(node) + '20'
                            ctx.fillRect(rectX, rectY, boxWidth, boxHeight / 3)
                        }
                    }

                    // Draw label
                    ctx.fillStyle = '#0a0a0a'
                    ctx.font = `bold ${fontSize}px Sans-Serif`
                    ctx.textAlign = 'center'
                    ctx.textBaseline = 'middle'
                    ctx.fillText(label, x, y)

                    // Draw dependency count badge
                    if (node.dependencies && node.dependencies.length > 0) {
                        const badgeText = `${node.dependencies.length} dep${node.dependencies.length > 1 ? 's' : ''}`
                        const badgeFontSize = fontSize * 0.7
                        ctx.font = `${badgeFontSize}px Sans-Serif`
                        ctx.fillStyle = '#737373'
                        const offset = shape === 'diamond' ? boxHeight / 3 : boxHeight / 2 - padding
                        ctx.fillText(badgeText, x, y + offset)
                    }
                }}
                nodeCanvasObjectMode={() => 'replace'}
                onNodeClick={(node) => {
                    console.log('Node clicked:', node.name, 'Dependencies:', node.dependencies)
                }}
                d3AlphaDecay={0.015}
                d3VelocityDecay={0.25}
                cooldownTime={4000}
                enableNodeDrag={true}
                enableZoomInteraction={true}
                enablePanInteraction={true}
            />
        </div>
    )
}

