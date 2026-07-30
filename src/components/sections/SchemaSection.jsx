import { Network } from 'lucide-react'
import { useConnectionManager } from '@/lib/hooks/useConnectionManager'
import BrainWorkspace from '@/components/tabs/Brain/BrainWorkspace'
import styles from './SectionEmpty.module.css'

export default function SchemaSection() {
  const { connectionId } = useConnectionManager()

  if (!connectionId) {
    return (
      <div className={styles.root}>
        <div className={styles.iconWrap}><Network size={26} color="#9ca3af" /></div>
        <h2 className={styles.title}>No connection selected</h2>
        <p className={styles.subtitle}>Select a database connection to view Brain insights.</p>
      </div>
    )
  }

  return <BrainWorkspace connectionId={connectionId} />
}
