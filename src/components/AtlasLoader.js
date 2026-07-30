'use client'

import styles from './AtlasLoader.module.css'

export default function AtlasLoader({ message = 'Creating your dashboard...' }) {
    return (
        <div className={styles.atlasLoader}>
            <div className={styles.container}>
                {/* Outer glow rings */}
                <div className={styles.ring1}></div>
                <div className={styles.ring2}></div>
                <div className={styles.ring3}></div>
                
                {/* Central core */}
                <div className={styles.core}>
                    <div className={styles.innerGlow}></div>
                    <div className={styles.particles}>
                        {Array.from({ length: 12 }).map((_, i) => (
                            <div
                                key={i}
                                className={styles.particle}
                                style={{
                                    '--angle': `${i * 30}deg`,
                                    '--delay': `${i * 0.1}s`
                                }}
                            ></div>
                        ))}
                    </div>
                </div>
                
                {/* Floating particles */}
                <div className={styles.floatingParticles}>
                    {Array.from({ length: 8 }).map((_, i) => (
                        <div
                            key={i}
                            className={styles.floatingParticle}
                            style={{
                                '--x': `${Math.random() * 200 - 100}px`,
                                '--y': `${Math.random() * 200 - 100}px`,
                                '--delay': `${Math.random() * 2}s`,
                                '--duration': `${3 + Math.random() * 2}s`
                            }}
                        ></div>
                    ))}
                </div>
            </div>
            
            <p className={styles.message}>{message}</p>
        </div>
    )
}

