'use client'

import { useState, useEffect, useRef } from 'react'
import styles from './DashboardBuilderEffect.module.css'

const BUILDING_MESSAGES = [
    'Analyzing your request...',
    'Designing dashboard layout...',
    'Creating metrics...',
    'Building charts...',
    'Configuring data queries...',
    'Rendering components...',
    'Almost there...',
    'Finalizing dashboard...'
]

export default function DashboardBuilderEffect({ isBuilding = true, onComplete }) {
    const [particles, setParticles] = useState([])
    const [currentMessage, setCurrentMessage] = useState(BUILDING_MESSAGES[0])
    const [messageIndex, setMessageIndex] = useState(0)
    const containerRef = useRef(null)
    const particleIdRef = useRef(0)
    const animationFrameRef = useRef(null)

    useEffect(() => {
        if (!isBuilding) {
            // Clean up particles when building is complete
            setParticles([])
            // Don't call onComplete here - let parent control when to hide
            return
        }

        // Update progress messages
        const messageInterval = setInterval(() => {
            setMessageIndex(prev => {
                const next = (prev + 1) % BUILDING_MESSAGES.length
                setCurrentMessage(BUILDING_MESSAGES[next])
                return next
            })
        }, 1500)

        // Create particles continuously
        const createParticle = () => {
            if (!containerRef.current) return

            const container = containerRef.current
            const rect = container.getBoundingClientRect()
            
            // Create a new particle at random position
            const newParticle = {
                id: particleIdRef.current++,
                x: Math.random() * rect.width,
                y: Math.random() * rect.height,
                size: 2 + Math.random() * 4, // 2-6px
                opacity: 0.3 + Math.random() * 0.7,
                color: getRandomColor(),
                vx: (Math.random() - 0.5) * 0.5, // velocity x
                vy: (Math.random() - 0.5) * 0.5, // velocity y
                life: 0,
                maxLife: 2000 + Math.random() * 2000 // 2-4 seconds
            }

            setParticles(prev => [...prev, newParticle])
        }

        // Create particles at intervals
        const particleInterval = setInterval(createParticle, 50) // Create every 50ms

        // Animate particles
        const animate = () => {
            setParticles(prev => {
                return prev
                    .map(particle => ({
                        ...particle,
                        x: particle.x + particle.vx,
                        y: particle.y + particle.vy,
                        life: particle.life + 16, // ~60fps
                        opacity: particle.opacity * (1 - particle.life / particle.maxLife)
                    }))
                    .filter(particle => particle.life < particle.maxLife && particle.opacity > 0.1)
            })
            animationFrameRef.current = requestAnimationFrame(animate)
        }

        animationFrameRef.current = requestAnimationFrame(animate)

        return () => {
            clearInterval(messageInterval)
            clearInterval(particleInterval)
            if (animationFrameRef.current) {
                cancelAnimationFrame(animationFrameRef.current)
            }
        }
    }, [isBuilding, onComplete])

    const getRandomColor = () => {
        const colors = [
            'rgba(14, 165, 233, 0.8)',   // sky-500
            'rgba(34, 211, 238, 0.8)',   // cyan-400
            'rgba(59, 130, 246, 0.8)',  // blue-500
            'rgba(99, 102, 241, 0.8)',  // indigo-500
            'rgba(139, 92, 246, 0.8)',  // violet-500
        ]
        return colors[Math.floor(Math.random() * colors.length)]
    }

    if (!isBuilding) return null

    return (
        <div ref={containerRef} className={styles.overlay}>
            {/* Particles */}
            <div className={styles.particlesContainer}>
                {particles.map(particle => (
                    <div
                        key={particle.id}
                        className={styles.particle}
                        style={{
                            left: `${particle.x}px`,
                            top: `${particle.y}px`,
                            width: `${particle.size}px`,
                            height: `${particle.size}px`,
                            backgroundColor: particle.color,
                            opacity: particle.opacity,
                            boxShadow: `0 0 ${particle.size * 2}px ${particle.color}`
                        }}
                    />
                ))}
            </div>

            {/* Progress message */}
            <div className={styles.progressMessage}>
                <div className={styles.messageBubble}>
                    <div className={styles.messageText}>{currentMessage}</div>
                    <div className={styles.progressDots}>
                        <span className={styles.dot}></span>
                        <span className={styles.dot}></span>
                        <span className={styles.dot}></span>
                    </div>
                </div>
            </div>

            {/* Grid pattern overlay for building effect */}
            <div className={styles.gridOverlay}></div>
        </div>
    )
}

