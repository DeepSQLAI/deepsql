import { useState } from 'react'
import { Zap, Loader2, Check, X, AlertTriangle, Shield, ShieldCheck, ShieldX } from 'lucide-react'
import { connectionAPI } from '@/lib/api/client'
import styles from '../ConnectionWizard.module.css'

/**
 * Quick test connection button with status display and privilege checks
 */
export function QuickTestButton({ formData, disabled }) {
  const [status, setStatus] = useState('idle') // idle, testing, success, partial, error
  const [errorMessage, setErrorMessage] = useState('')
  const [testResult, setTestResult] = useState(null)

  const canTest = formData.host && formData.database && formData.username && (formData.password || formData.id)

  const buildTestPayload = () => {
    const payload = {
      dbType: formData.dbType,
      connectionName: formData.connectionName || 'Test Connection',
      host: formData.host,
      port: formData.port || (formData.dbType === 'postgres' ? '5432' : '3306'),
      database: formData.database,
      username: formData.username,
      password: formData.password,
      sslMode: formData.sslMode || 'none',
    }

    // Add SSL certificates if configured
    if (formData.sslMode !== 'none') {
      if (formData.sslCaCertificate) payload.sslCaCertificate = formData.sslCaCertificate
      if (formData.sslClientCertificate) payload.sslClientCertificate = formData.sslClientCertificate
      if (formData.sslClientKey) payload.sslClientKey = formData.sslClientKey
      if (formData.sslClientKeyPassphrase) payload.sslClientKeyPassphrase = formData.sslClientKeyPassphrase
    }

    // Add SSH tunnel config if enabled
    if (formData.connectivityMethod === 'ssh-tunnel' && formData.sshHost) {
      payload.sshEnabled = true
      payload.sshHost = formData.sshHost
      payload.sshPort = formData.sshPort || 22
      payload.sshUsername = formData.sshUsername
      payload.sshAuthType = formData.sshAuthType

      if (formData.sshAuthType === 'PASSWORD') {
        payload.sshPassword = formData.sshPassword
      } else {
        payload.sshPrivateKey = formData.sshPrivateKey
        payload.sshPassphrase = formData.sshPassphrase
      }
    }

    return payload
  }

  const handleTest = async () => {
    if (!canTest || disabled) return

    setStatus('testing')
    setErrorMessage('')
    setTestResult(null)

    try {
      const payload = buildTestPayload()
      const result = await connectionAPI.testConnection(payload)

      setTestResult(result)

      if (result.success) {
        setStatus('success')
        // Reset after 5 seconds
        setTimeout(() => {
          setStatus('idle')
          setTestResult(null)
        }, 5000)
      } else if (result.connectionSuccessful && result.privileges?.some(p => !p.granted)) {
        // Connection works but missing some privileges
        setStatus('partial')
        setErrorMessage(result.message || 'Missing some privileges')
      } else {
        setStatus('error')
        setErrorMessage(result.message || 'Connection failed')
      }
    } catch (err) {
      setStatus('error')
      setErrorMessage(err.response?.data?.message || err.message || 'Connection test failed')
      setTestResult(err.response?.data || null)
    }
  }

  const renderIcon = () => {
    switch (status) {
      case 'testing':
        return <Loader2 size={14} className={styles.quickTestSpinner} />
      case 'success':
        return <ShieldCheck size={14} />
      case 'partial':
        return <Shield size={14} />
      case 'error':
        return <AlertTriangle size={14} />
      default:
        return <Zap size={14} />
    }
  }

  const getButtonClass = () => {
    const base = styles.quickTestButton
    switch (status) {
      case 'success':
        return `${base} ${styles.quickTestSuccess}`
      case 'partial':
        return `${base} ${styles.quickTestPartial}`
      case 'error':
        return `${base} ${styles.quickTestError}`
      case 'testing':
        return `${base} ${styles.quickTestTesting}`
      default:
        return base
    }
  }

  const getButtonText = () => {
    switch (status) {
      case 'testing':
        return 'Testing...'
      case 'success':
        return 'All Checks Passed!'
      case 'partial':
        return 'Missing Privileges'
      case 'error':
        return 'Failed'
      default:
        return 'Quick Test'
    }
  }

  const handleDismiss = () => {
    setStatus('idle')
    setErrorMessage('')
    setTestResult(null)
  }

  const renderPrivilegeResults = () => {
    if (!testResult?.privileges || testResult.privileges.length === 0) return null

    const granted = testResult.privileges.filter(p => p.granted)
    const missing = testResult.privileges.filter(p => !p.granted)

    return (
      <div className={styles.privilegeResults}>
        <div className={styles.privilegeHeader}>
          <Shield size={14} />
          <span>Privilege Check Results</span>
          <button
            type="button"
            className={styles.quickTestDismiss}
            onClick={handleDismiss}
          >
            <X size={14} />
          </button>
        </div>

        {/* Connection status */}
        <div className={styles.privilegeSection}>
          <div className={`${styles.privilegeItem} ${testResult.connectionSuccessful ? styles.privilegeGranted : styles.privilegeDenied}`}>
            {testResult.connectionSuccessful ? <Check size={12} /> : <X size={12} />}
            <span>Database Connection</span>
          </div>
          {formData.connectivityMethod === 'ssh-tunnel' && (
            <div className={`${styles.privilegeItem} ${testResult.sshTunnelSuccessful ? styles.privilegeGranted : styles.privilegeDenied}`}>
              {testResult.sshTunnelSuccessful ? <Check size={12} /> : <X size={12} />}
              <span>SSH Tunnel</span>
            </div>
          )}
        </div>

        {/* Granted privileges */}
        {granted.length > 0 && (
          <div className={styles.privilegeSection}>
            <div className={styles.privilegeSectionTitle}>Granted</div>
            {granted.map((priv, idx) => (
              <div key={idx} className={`${styles.privilegeItem} ${styles.privilegeGranted}`}>
                <Check size={12} />
                <span>{priv.name}</span>
                <span className={styles.privilegeScope}>{priv.scope}</span>
              </div>
            ))}
          </div>
        )}

        {/* Missing privileges */}
        {missing.length > 0 && (
          <div className={styles.privilegeSection}>
            <div className={styles.privilegeSectionTitle}>Missing</div>
            {missing.map((priv, idx) => (
              <div key={idx} className={`${styles.privilegeItem} ${styles.privilegeDenied}`}>
                <X size={12} />
                <span>{priv.name}</span>
                <span className={styles.privilegeScope}>{priv.scope}</span>
                {priv.error && (
                  <div className={styles.privilegeError}>{priv.error}</div>
                )}
              </div>
            ))}
          </div>
        )}
      </div>
    )
  }

  return (
    <div className={styles.quickTestContainer}>
      <button
        type="button"
        className={getButtonClass()}
        onClick={handleTest}
        disabled={!canTest || disabled || status === 'testing'}
        title={!canTest ? 'Fill in host, database, username, and password to test' : 'Test connection and verify privileges'}
      >
        {renderIcon()}
        {getButtonText()}
      </button>

      {/* Show detailed privilege results */}
      {(status === 'success' || status === 'partial' || status === 'error') && testResult?.privileges && (
        renderPrivilegeResults()
      )}

      {/* Show simple error message if no privilege details */}
      {status === 'error' && errorMessage && !testResult?.privileges && (
        <div className={styles.quickTestErrorMessage}>
          <X size={12} />
          <span>{errorMessage}</span>
          <button
            type="button"
            className={styles.quickTestDismiss}
            onClick={handleDismiss}
          >
            <X size={12} />
          </button>
        </div>
      )}

      {!canTest && status === 'idle' && (
        <span className={styles.quickTestHint}>
          Fill in required fields to test
        </span>
      )}
    </div>
  )
}

export default QuickTestButton
