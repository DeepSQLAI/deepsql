import { useEffect, useRef, useState } from 'react'
import { useIsNavigating } from '@/lib/stores/useNavStore'

/**
 * NProgress-style top loading bar.
 * - Appears immediately on nav switch (isNavigating = true)
 * - Races to ~85% quickly, then crawls until clearNavigating() is called
 * - Completes to 100% and fades out smoothly
 */
export default function PageTransitionBar() {
  const isNavigating = useIsNavigating()
  const [width, setWidth] = useState(0)
  const [visible, setVisible] = useState(false)
  const [completing, setCompleting] = useState(false)
  const rafRef = useRef(null)
  const timerRef = useRef(null)

  useEffect(() => {
    if (isNavigating) {
      // Reset and start
      setCompleting(false)
      setWidth(0)
      setVisible(true)

      // Animate quickly to ~75% then slow down
      let current = 0
      const step = () => {
        current = current < 60 ? current + 4
               : current < 80 ? current + 1.2
               : current < 90 ? current + 0.4
               : current       // hold at 90 — wait for clearNavigating
        setWidth(Math.min(current, 90))
        if (current < 90) rafRef.current = requestAnimationFrame(step)
      }
      rafRef.current = requestAnimationFrame(step)
    } else if (visible) {
      // Navigation done — shoot to 100% then fade out
      if (rafRef.current) cancelAnimationFrame(rafRef.current)
      setCompleting(true)
      setWidth(100)
      timerRef.current = setTimeout(() => {
        setVisible(false)
        setWidth(0)
        setCompleting(false)
      }, 400)
    }

    return () => {
      if (rafRef.current) cancelAnimationFrame(rafRef.current)
      if (timerRef.current) clearTimeout(timerRef.current)
    }
  }, [isNavigating])

  if (!visible) return null

  return (
    <div
      style={{
        position: 'fixed',
        top: 0,
        left: 0,
        right: 0,
        height: 3,
        zIndex: 9999,
        pointerEvents: 'none',
      }}
    >
      <div
        style={{
          height: '100%',
          width: `${width}%`,
          background: 'linear-gradient(90deg, #6366f1, #8b5cf6, #a78bfa)',
          transition: completing
            ? 'width 0.25s ease-out, opacity 0.15s ease'
            : 'width 0.08s linear',
          opacity: completing && width === 100 ? 0 : 1,
          borderRadius: '0 2px 2px 0',
          boxShadow: '0 0 8px rgba(99, 102, 241, 0.6)',
        }}
      />
    </div>
  )
}
