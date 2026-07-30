import { useConnectionManager } from '@/lib/hooks/useConnectionManager'
import SqlRunnerTab from '@/components/tabs/Core/SqlRunnerTab'
import { Code2 } from 'lucide-react'
import styles from './SectionEmpty.module.css'

export default function EditorSection() {
  const { connectionId } = useConnectionManager()

  if (!connectionId) {
    return (
      <div className={styles.root}>
        <div className={styles.iconWrap}>
          <Code2 size={26} color="#9ca3af" />
        </div>
        <h2 className={styles.title}>No connection selected</h2>
        <p className={styles.subtitle}>Select a database connection to open the SQL editor.</p>
      </div>
    )
  }

  return <SqlRunnerTab connectionId={connectionId} />
}
