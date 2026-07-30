import { SchemaDocsPanel } from '@/components/tabs/Brain/SchemaDocs/SchemaDocsPanel'
import { useCompanyKnowledgeStore } from '@/lib/stores/useCompanyKnowledgeStore'
import UnresolvedPanel from './UnresolvedPanel'
import styles from './CompanyKnowledgePanel.module.css'

export default function SchemaContextTab({ connectionId }) {
  const setLinkedFilters = useCompanyKnowledgeStore((s) => s.setLinkedFilters)

  if (!connectionId) {
    return <div className={styles.emptyState}>Select a connection to view schema context.</div>
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', gap: 14 }}>
      <UnresolvedPanel connectionId={connectionId} />
      <SchemaDocsPanel
        connectionId={connectionId}
        showHero={false}
        onOpenCompanyKnowledge={({ table, column }) => {
          // Cross-link to the Business Rules tab filtered by the chosen object.
          setLinkedFilters({
            linkedTableFilter: table || '',
            linkedColumnFilter: column || '',
          })
          useCompanyKnowledgeStore.getState().setActiveTab('business-rules')
        }}
      />
    </div>
  )
}
