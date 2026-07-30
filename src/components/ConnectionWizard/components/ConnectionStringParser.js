import { useState } from 'react'
import { Link, Sparkles, X, Check, AlertCircle } from 'lucide-react'
import styles from '../ConnectionWizard.module.css'

/**
 * Parse various connection string formats and extract connection details
 */
const parseConnectionString = (str) => {
  const result = {
    host: '',
    port: '',
    database: '',
    username: '',
    password: '',
    dbType: '',
  }

  const trimmed = str.trim()

  // Try URI format: postgresql://user:pass@host:port/database
  // or mysql://user:pass@host:port/database
  const uriMatch = trimmed.match(
    /^(postgres(?:ql)?|mysql):\/\/(?:([^:]+):([^@]+)@)?([^:/]+)(?::(\d+))?(?:\/([^?]+))?/i
  )

  if (uriMatch) {
    const [, protocol, user, pass, host, port, db] = uriMatch
    result.dbType = protocol.toLowerCase().startsWith('postgres') ? 'postgres' : 'mysql'
    result.username = user ? decodeURIComponent(user) : ''
    result.password = pass ? decodeURIComponent(pass) : ''
    result.host = host || ''
    result.port = port || ''
    result.database = db || ''
    return result
  }

  // Try JDBC format: jdbc:postgresql://host:port/database
  // or jdbc:mysql://host:port/database
  const jdbcMatch = trimmed.match(
    /^jdbc:(postgres(?:ql)?|mysql):\/\/([^:/]+)(?::(\d+))?(?:\/([^?]+))?/i
  )

  if (jdbcMatch) {
    const [, protocol, host, port, db] = jdbcMatch
    result.dbType = protocol.toLowerCase().startsWith('postgres') ? 'postgres' : 'mysql'
    result.host = host || ''
    result.port = port || ''
    result.database = db || ''
    return result
  }

  // Try key=value format: host=localhost port=5432 dbname=mydb user=admin
  const kvPairs = trimmed.split(/\s+/)
  let isKvFormat = false

  for (const pair of kvPairs) {
    const [key, ...valueParts] = pair.split('=')
    const value = valueParts.join('=')
    if (!value) continue

    isKvFormat = true
    const lowerKey = key.toLowerCase()

    if (lowerKey === 'host' || lowerKey === 'server') {
      result.host = value
    } else if (lowerKey === 'port') {
      result.port = value
    } else if (lowerKey === 'database' || lowerKey === 'dbname' || lowerKey === 'db') {
      result.database = value
    } else if (lowerKey === 'user' || lowerKey === 'username' || lowerKey === 'uid') {
      result.username = value
    } else if (lowerKey === 'password' || lowerKey === 'pwd') {
      result.password = value
    }
  }

  if (isKvFormat && result.host) {
    // Try to infer database type from port
    if (result.port === '5432') {
      result.dbType = 'postgres'
    } else if (result.port === '3306') {
      result.dbType = 'mysql'
    }
    return result
  }

  // Try simple host:port format
  const simpleMatch = trimmed.match(/^([^:]+):(\d+)$/)
  if (simpleMatch) {
    const [, host, port] = simpleMatch
    result.host = host
    result.port = port
    if (port === '5432') result.dbType = 'postgres'
    if (port === '3306') result.dbType = 'mysql'
    return result
  }

  // If nothing matched, treat as hostname
  if (trimmed && !trimmed.includes(' ')) {
    result.host = trimmed
    return result
  }

  return null
}

/**
 * Connection string parser with paste functionality
 */
export function ConnectionStringParser({ onParse, currentDbType }) {
  const [isOpen, setIsOpen] = useState(false)
  const [input, setInput] = useState('')
  const [parseResult, setParseResult] = useState(null)
  const [error, setError] = useState(null)

  const handleParse = () => {
    if (!input.trim()) {
      setError('Please paste a connection string')
      return
    }

    const result = parseConnectionString(input)

    if (!result || !result.host) {
      setError('Could not parse connection string. Try a different format.')
      return
    }

    // Warn if database type doesn't match
    if (result.dbType && currentDbType && result.dbType !== currentDbType) {
      setError(`This looks like a ${result.dbType} connection string, but you selected ${currentDbType}`)
      return
    }

    setError(null)
    setParseResult(result)
  }

  const handleApply = () => {
    if (parseResult) {
      onParse(parseResult)
      setIsOpen(false)
      setInput('')
      setParseResult(null)
    }
  }

  const handleClose = () => {
    setIsOpen(false)
    setInput('')
    setParseResult(null)
    setError(null)
  }

  if (!isOpen) {
    return (
      <button
        type="button"
        className={styles.parseStringButton}
        onClick={() => setIsOpen(true)}
      >
        <Sparkles size={14} />
        Paste connection string
      </button>
    )
  }

  return (
    <div className={styles.parseStringContainer}>
      <div className={styles.parseStringHeader}>
        <div className={styles.parseStringTitle}>
          <Link size={16} />
          <span>Parse Connection String</span>
        </div>
        <button
          type="button"
          className={styles.parseStringClose}
          onClick={handleClose}
        >
          <X size={16} />
        </button>
      </div>

      <div className={styles.parseStringBody}>
        <textarea
          value={input}
          onChange={(e) => {
            setInput(e.target.value)
            setError(null)
            setParseResult(null)
          }}
          placeholder={`Paste your connection string here...\n\nSupported formats:\n• postgresql://user:pass@host:5432/db\n• mysql://user:pass@host:3306/db\n• jdbc:postgresql://host:5432/db\n• host=localhost port=5432 dbname=mydb user=admin`}
          className={styles.parseStringInput}
          rows={4}
        />

        {error && (
          <div className={styles.parseStringError}>
            <AlertCircle size={14} />
            {error}
          </div>
        )}

        {parseResult && (
          <div className={styles.parseStringPreview}>
            <div className={styles.parseStringPreviewTitle}>
              <Check size={14} />
              Parsed successfully
            </div>
            <div className={styles.parseStringPreviewGrid}>
              {parseResult.host && (
                <div className={styles.parseStringPreviewItem}>
                  <span className={styles.parseStringPreviewLabel}>Host</span>
                  <span className={styles.parseStringPreviewValue}>{parseResult.host}</span>
                </div>
              )}
              {parseResult.port && (
                <div className={styles.parseStringPreviewItem}>
                  <span className={styles.parseStringPreviewLabel}>Port</span>
                  <span className={styles.parseStringPreviewValue}>{parseResult.port}</span>
                </div>
              )}
              {parseResult.database && (
                <div className={styles.parseStringPreviewItem}>
                  <span className={styles.parseStringPreviewLabel}>Database</span>
                  <span className={styles.parseStringPreviewValue}>{parseResult.database}</span>
                </div>
              )}
              {parseResult.username && (
                <div className={styles.parseStringPreviewItem}>
                  <span className={styles.parseStringPreviewLabel}>Username</span>
                  <span className={styles.parseStringPreviewValue}>{parseResult.username}</span>
                </div>
              )}
              {parseResult.password && (
                <div className={styles.parseStringPreviewItem}>
                  <span className={styles.parseStringPreviewLabel}>Password</span>
                  <span className={styles.parseStringPreviewValue}>••••••••</span>
                </div>
              )}
            </div>
          </div>
        )}
      </div>

      <div className={styles.parseStringActions}>
        <button
          type="button"
          className={styles.parseStringCancel}
          onClick={handleClose}
        >
          Cancel
        </button>
        {!parseResult ? (
          <button
            type="button"
            className={styles.parseStringParse}
            onClick={handleParse}
          >
            Parse
          </button>
        ) : (
          <button
            type="button"
            className={styles.parseStringApply}
            onClick={handleApply}
          >
            <Check size={14} />
            Apply
          </button>
        )}
      </div>
    </div>
  )
}

export default ConnectionStringParser
