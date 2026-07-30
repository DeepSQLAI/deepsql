import { Building2 } from 'lucide-react'
import { useConnectionManager } from '@/lib/hooks/useConnectionManager'
import CompanyKnowledgePanel from '@/components/company-knowledge/CompanyKnowledgePanel'
import styles from './SectionEmpty.module.css'

export default function CompanyKnowledgeSection() {
  const { connectionId } = useConnectionManager()

  if (!connectionId) {
    return (
      <div className={styles.root}>
        <div className={styles.iconWrap}><Building2 size={26} color="#9ca3af" /></div>
        <h2 className={styles.title}>No connection selected</h2>
        <p className={styles.subtitle}>Select a database connection to add company context, workflows, business rules, and glossary knowledge.</p>
      </div>
    )
  }

  return <CompanyKnowledgePanel connectionId={connectionId} />
}
