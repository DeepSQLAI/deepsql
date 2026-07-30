import { Copy, Check } from 'lucide-react'
import { useState } from 'react'
import { STATIC_IPS } from '../utils/constants'
import styles from '../ConnectionWizard.module.css'

/**
 * Display static IPs for allowlisting
 * Can be customized for database or bastion host allowlisting
 */
export function IPAllowlist({
  title = 'Add these IPs to your database allowlist',
  note = 'Allow inbound connections from these IPs on your database port. For AWS RDS, add these to your Security Group. For Azure, add to firewall rules.',
}) {
  const [copiedIp, setCopiedIp] = useState(null)

  const handleCopy = async (ip) => {
    try {
      await navigator.clipboard.writeText(ip)
      setCopiedIp(ip)
      setTimeout(() => setCopiedIp(null), 2000)
    } catch (err) {
      console.error('Failed to copy:', err)
    }
  }

  const handleCopyAll = async () => {
    const allIps = STATIC_IPS.map((s) => s.ip).join('\n')
    try {
      await navigator.clipboard.writeText(allIps)
      setCopiedIp('all')
      setTimeout(() => setCopiedIp(null), 2000)
    } catch (err) {
      console.error('Failed to copy:', err)
    }
  }

  return (
    <div className={styles.ipAllowlist}>
      <div className={styles.ipAllowlistHeader}>
        <h4>{title}</h4>
        <button
          type="button"
          className={styles.copyAllButton}
          onClick={handleCopyAll}
        >
          {copiedIp === 'all' ? (
            <>
              <Check size={14} />
              Copied!
            </>
          ) : (
            <>
              <Copy size={14} />
              Copy All
            </>
          )}
        </button>
      </div>

      <div className={styles.ipList}>
        {STATIC_IPS.map(({ ip, region }) => (
          <div key={ip} className={styles.ipItem}>
            <code className={styles.ipAddress}>{ip}</code>
            <span className={styles.ipRegion}>{region}</span>
            <button
              type="button"
              className={styles.ipCopyButton}
              onClick={() => handleCopy(ip)}
              title="Copy IP"
            >
              {copiedIp === ip ? <Check size={14} /> : <Copy size={14} />}
            </button>
          </div>
        ))}
      </div>

      <p className={styles.ipNote}>{note}</p>
    </div>
  )
}

export default IPAllowlist
