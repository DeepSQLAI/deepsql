import { FileText } from 'lucide-react'
import { useConnectionManager } from '@/lib/hooks/useConnectionManager'
import { useSetActiveSection } from '@/lib/stores/useNavStore'
import { useCompanyKnowledgeStore } from '@/lib/stores/useCompanyKnowledgeStore'
import { SchemaDocsPanel } from '@/components/tabs/Brain/SchemaDocs/SchemaDocsPanel'
import styles from './SectionEmpty.module.css'
import workspaceStyles from './TopLevelSection.module.css'

export default function SchemaDocsSection() {
  const { connectionId } = useConnectionManager()
  const setActiveSection = useSetActiveSection()
  const setLinkedFilters = useCompanyKnowledgeStore((state) => state.setLinkedFilters)

  if (!connectionId) {
    return (
      <div className={styles.root}>
        <div className={styles.iconWrap}><FileText size={26} color="#9ca3af" /></div>
        <h2 className={styles.title}>No connection selected</h2>
        <p className={styles.subtitle}>Select a database connection to review and edit schema documentation.</p>
      </div>
    )
  }

  return (
    <div className={workspaceStyles.page}>
      <div className={workspaceStyles.header}>
        <div className={workspaceStyles.eyebrow}>Schema Docs</div>
        <h1 className={workspaceStyles.title}>Edit the knowledge your users will rely on</h1>
        <p className={workspaceStyles.subtitle}>
          Fine-tune AI-generated table and column docs, then jump straight into linked company knowledge when business context matters.
        </p>
      </div>
      <SchemaDocsPanel
        connectionId={connectionId}
        showHero={false}
        onOpenCompanyKnowledge={({ table, column }) => {
          setLinkedFilters({
            linkedTableFilter: table || '',
            linkedColumnFilter: column || '',
          })
          setActiveSection('company-knowledge')
        }}
      />
    </div>
  )
}
