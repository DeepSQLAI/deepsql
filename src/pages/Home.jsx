import { useState, useEffect, useMemo, useRef } from 'react'
import AppSidebar from '@/components/layout/AppSidebar'
import ProfileSwitch from '@/components/layout/ProfileSwitch'
import AgentView from '@/components/Agent/AgentView'
import AgentChatSection from '@/components/sections/AgentChatSection'
import DigestFeedSection from '@/components/sections/DigestSection'
import BrainSection from '@/components/sections/BrainSection'
import CompanyKnowledgeSection from '@/components/sections/CompanyKnowledgeSection'
import DashboardsSection from '@/components/sections/DashboardsSection'
import DocsSection from '@/components/sections/DocsSection'
import EditorSection from '@/components/sections/EditorSection'
import SlowQueriesSection from '@/components/sections/SlowQueriesSection'
import PageTransitionBar from '@/components/layout/PageTransitionBar'
import { useAuth } from '@/hooks/useAuth'
import { AGENTS_ENABLED, canAccessHomeSection, normalizeHomeSection } from '@/lib/features'
import { useConnectionManager } from '@/lib/hooks/useConnectionManager'
import { useActiveSection, useClearNavigating, useSetActiveSection, useImmersive } from '@/lib/stores/useNavStore'

const SECTION_MAP = {
  agent:   AgentView,
  'agent-chat': AgentChatSection,
  dashboards: DashboardsSection,
  digest:  DigestFeedSection,
  ...(AGENTS_ENABLED ? { brain: BrainSection } : {}),
  'company-knowledge': CompanyKnowledgeSection,
  performance: SlowQueriesSection,
  editor:  EditorSection,
  docs:    DocsSection,
}

export default function Home() {
  const { role, canSwitchProfile } = useAuth()
  const { selectedConnection } = useConnectionManager()
  const activeSection = useActiveSection()
  const setActiveSection = useSetActiveSection()
  const clearNavigating = useClearNavigating()
  const immersive = useImmersive()
  const normalizedActiveSection = normalizeHomeSection(activeSection, role, selectedConnection)
  const visibleSections = useMemo(
    () => Object.entries(SECTION_MAP).filter(([key]) => canAccessHomeSection(key, role, selectedConnection)),
    [role, selectedConnection]
  )

  // Lazy-mount: only render sections the user has actually visited.
  // Once mounted they stay mounted (display:none) so state is preserved
  // and switching back is instant.
  const mountedRef = useRef(new Set([normalizedActiveSection]))
  const [mounted, setMounted] = useState(() => new Set([normalizedActiveSection]))

  useEffect(() => {
    if (activeSection !== normalizedActiveSection) {
      setActiveSection(normalizedActiveSection)
      return
    }

    if (!mountedRef.current.has(normalizedActiveSection)) {
      mountedRef.current = new Set([...mountedRef.current, normalizedActiveSection])
      setMounted(new Set(mountedRef.current))
    }
    // Clear the loading bar after the section is painted.
    // rAF-in-rAF ensures we wait for the browser to actually commit the frame.
    const id = requestAnimationFrame(() =>
      requestAnimationFrame(() => clearNavigating())
    )
    return () => cancelAnimationFrame(id)
  }, [activeSection, normalizedActiveSection, setActiveSection, clearNavigating])

  return (
    <main
      style={{
        display: 'flex',
        height: '100vh',
        width: '100vw',
        overflow: 'hidden',
        background: '#fff',
        fontFamily: 'var(--font-family-sans)',
      }}
    >
      <PageTransitionBar />
      {!immersive && <AppSidebar />}

      {/* Main content — lazy-mount sections on first visit, then keep alive */}
      <div style={{ flex: 1, overflow: 'hidden', position: 'relative', display: 'flex', flexDirection: 'column' }}>
        {canSwitchProfile && <ProfileSwitch />}
        <div style={{ flex: 1, overflow: 'hidden', position: 'relative' }}>
          {visibleSections.map(([key, Section]) => {
            if (!mounted.has(key)) return null
            return (
              <div
                key={key}
                style={{
                  position: 'absolute',
                  inset: 0,
                  display: normalizedActiveSection === key ? 'flex' : 'none',
                  flexDirection: 'column',
                }}
              >
                <Section />
              </div>
            )
          })}
        </div>
      </div>
    </main>
  )
}
