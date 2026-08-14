import { useState } from 'react'
import { ChevronDown, ChevronRight, Key, Copy, Check } from 'lucide-react'
import { REQUIRED_PRIVILEGES } from '../utils/constants'
import styles from '../ConnectionWizard.module.css'

/**
 * Expandable section showing required database privileges
 */
export function PrivilegesAccordion({ dbType }) {
  const [isOpen, setIsOpen] = useState(false)
  const [copied, setCopied] = useState(false)

  const privileges = REQUIRED_PRIVILEGES[dbType] || []

  // Generate grant statements
  const generateGrantStatements = () => {
    if (dbType === 'postgres') {
      return `-- Grant required privileges for DBA Agent
-- Replace 'your_user' with your database username
-- Replace 'your_database' with your database name

-- Basic read access (repeat GRANT block per schema you want DeepSQL to see)
GRANT SELECT ON ALL TABLES IN SCHEMA public TO your_user;
GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO your_user;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT SELECT ON TABLES TO your_user;

-- Multi-schema example (crm / sales / …)
-- GRANT USAGE ON SCHEMA crm TO your_user;
-- GRANT SELECT ON ALL TABLES IN SCHEMA crm TO your_user;
-- GRANT SELECT ON ALL SEQUENCES IN SCHEMA crm TO your_user;
-- ALTER DEFAULT PRIVILEGES IN SCHEMA crm GRANT SELECT ON TABLES TO your_user;

-- Access to system views for monitoring
GRANT pg_read_all_stats TO your_user;

-- Enable pg_stat_statements extension (if not already enabled)
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;`
    }

    if (dbType === 'mysql') {
      return `-- Grant required privileges for DBA Agent
-- Replace 'your_user' with your database username
-- Replace 'your_host' with the connection host (or '%' for any)
-- Replace 'your_database' with your database name

-- Basic read access
GRANT SELECT ON your_database.* TO 'your_user'@'your_host';

-- Process privilege for monitoring active queries
GRANT PROCESS ON *.* TO 'your_user'@'your_host';

-- Access to performance_schema for slow query analysis
GRANT SELECT ON performance_schema.* TO 'your_user'@'your_host';

-- Reload privilege for FLUSH commands (optional, for ANALYZE)
GRANT RELOAD ON *.* TO 'your_user'@'your_host';

FLUSH PRIVILEGES;`
    }

    return ''
  }

  const handleCopyGrants = async () => {
    try {
      await navigator.clipboard.writeText(generateGrantStatements())
      setCopied(true)
      setTimeout(() => setCopied(false), 2000)
    } catch (err) {
      console.error('Failed to copy:', err)
    }
  }

  if (!privileges.length) return null

  return (
    <div className={styles.privilegesAccordion}>
      <button
        type="button"
        className={styles.privilegesHeader}
        onClick={() => setIsOpen(!isOpen)}
      >
        <Key size={16} />
        <span>Required Database Privileges</span>
        {isOpen ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
      </button>

      {isOpen && (
        <div className={styles.privilegesContent}>
          <table className={styles.privilegesTable}>
            <thead>
              <tr>
                <th>Privilege</th>
                <th>Scope</th>
                <th>Purpose</th>
              </tr>
            </thead>
            <tbody>
              {privileges.map((priv, idx) => (
                <tr key={idx}>
                  <td>
                    <code>{priv.name}</code>
                  </td>
                  <td>{priv.tables}</td>
                  <td>{priv.reason}</td>
                </tr>
              ))}
            </tbody>
          </table>

          <div className={styles.grantStatements}>
            <div className={styles.grantHeader}>
              <span>Grant Statements</span>
              <button
                type="button"
                className={styles.copyGrantsButton}
                onClick={handleCopyGrants}
              >
                {copied ? (
                  <>
                    <Check size={14} />
                    Copied!
                  </>
                ) : (
                  <>
                    <Copy size={14} />
                    Copy
                  </>
                )}
              </button>
            </div>
            <pre className={styles.grantCode}>{generateGrantStatements()}</pre>
          </div>
        </div>
      )}
    </div>
  )
}

export default PrivilegesAccordion
