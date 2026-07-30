import { Brain } from 'lucide-react'
import { useConnectionManager } from '@/lib/hooks/useConnectionManager'
import BrainView from '@/components/Brain/BrainView'
import styles from './SectionEmpty.module.css'

export default function BrainSection() {
  const { connections } = useConnectionManager()

  if (connections.length === 0) {
    return (
      <div className={styles.root}>
        <div className={styles.iconWrap}><Brain size={26} color="#9ca3af" /></div>
        <h2 className={styles.title}>No connections</h2>
        <p className={styles.subtitle}>
          Add a database connection first, then build agents to automate your data insights.
        </p>
      </div>
    )
  }

  return <BrainView connections={connections} />
}
