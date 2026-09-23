import { Building2, Loader2 } from 'lucide-react'
import { useConnectionManager } from '@/lib/hooks/useConnectionManager'
import CompanyKnowledgePanel from '@/components/company-knowledge/CompanyKnowledgePanel'
import styles from './SectionEmpty.module.css'

export default function CompanyKnowledgeSection() {
  const { connectionId, selectedConnection, isLoading } = useConnectionManager()

  if (isLoading) {
    return (
      <div className={styles.root}>
        <Loader2 size={24} color="#9ca3af" className={styles.spin} />
        <p className={styles.subtitle}>Loading connections…</p>
      </div>
    )
  }

  // Either no connection or stale connectionId not in the effective user's list
  if (!connectionId || !selectedConnection) {
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
