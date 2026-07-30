import { useState } from 'react'
import { Eye, EyeOff, Server, User, Lock, Database, Hash } from 'lucide-react'
import { DATABASE_TYPES } from '../utils/constants'
import { PrivilegesAccordion } from '../components/PrivilegesAccordion'
import { ConnectionStringParser } from '../components/ConnectionStringParser'
import styles from '../ConnectionWizard.module.css'

/**
 * Step 3: Connection details (host, port, database, username, password)
 * Now includes connection string parser and quick test functionality
 */
export function EndpointCredentialsStep({ formData, updateFormData, errors, isEditMode }) {
  const [showPassword, setShowPassword] = useState(false)

  const dbInfo = DATABASE_TYPES.find((db) => db.id === formData.dbType)
  const defaultPort = dbInfo?.defaultPort || 5432

  // Handle parsed connection string
  const handleConnectionStringParsed = (parsed) => {
    if (parsed.host) updateFormData('host', parsed.host)
    if (parsed.port) updateFormData('port', parsed.port)
    if (parsed.database) updateFormData('database', parsed.database)
    if (parsed.username) updateFormData('username', parsed.username)
    if (parsed.password) updateFormData('password', parsed.password)

    // Auto-generate connection name if not set
    if (!formData.connectionName && parsed.host) {
      const suggestedName = `${parsed.database || 'db'}@${parsed.host.split('.')[0]}`
      updateFormData('connectionName', suggestedName)
    }
  }

  return (
    <div className={styles.stepContent}>
      <div className={styles.stepHeader}>
        <h2>Connection Details</h2>
        <p>Enter your database endpoint and credentials</p>
      </div>

      {/* Connection String Parser */}
      {!isEditMode && (
        <ConnectionStringParser
          onParse={handleConnectionStringParsed}
          currentDbType={formData.dbType}
        />
      )}

      <div className={styles.formGrid}>
        {/* Connection Name */}
        <div className={styles.formGroup}>
          <label htmlFor="connectionName">
            <Database size={16} />
            Connection Name
            <span className={styles.required}>*</span>
          </label>
          <input
            id="connectionName"
            type="text"
            value={formData.connectionName}
            onChange={(e) => updateFormData('connectionName', e.target.value)}
            placeholder="My Production Database"
            className={errors.connectionName ? styles.inputError : ''}
          />
          {errors.connectionName && (
            <span className={styles.fieldError}>{errors.connectionName}</span>
          )}
        </div>

        {/* Host */}
        <div className={styles.formGroup}>
          <label htmlFor="host">
            <Server size={16} />
            Host
            <span className={styles.required}>*</span>
          </label>
          <input
            id="host"
            type="text"
            value={formData.host}
            onChange={(e) => updateFormData('host', e.target.value)}
            placeholder="db.example.com or 10.0.0.1"
            className={errors.host ? styles.inputError : ''}
          />
          {errors.host && (
            <span className={styles.fieldError}>{errors.host}</span>
          )}
        </div>

        {/* Port */}
        <div className={styles.formGroup}>
          <label htmlFor="port">
            <Hash size={16} />
            Port
          </label>
          <input
            id="port"
            type="number"
            value={formData.port}
            onChange={(e) => updateFormData('port', e.target.value)}
            placeholder={String(defaultPort)}
            className={errors.port ? styles.inputError : ''}
          />
          {errors.port && (
            <span className={styles.fieldError}>{errors.port}</span>
          )}
          <span className={styles.fieldHint}>Default: {defaultPort}</span>
        </div>

        {/* Database */}
        <div className={styles.formGroup}>
          <label htmlFor="database">
            <Database size={16} />
            Database Name
            <span className={styles.required}>*</span>
          </label>
          <input
            id="database"
            type="text"
            value={formData.database}
            onChange={(e) => updateFormData('database', e.target.value)}
            placeholder={formData.dbType === 'postgres' ? 'postgres' : 'mysql'}
            className={errors.database ? styles.inputError : ''}
          />
          {errors.database && (
            <span className={styles.fieldError}>{errors.database}</span>
          )}
        </div>

        {/* Username */}
        <div className={styles.formGroup}>
          <label htmlFor="username">
            <User size={16} />
            Username
            <span className={styles.required}>*</span>
          </label>
          <input
            id="username"
            type="text"
            value={formData.username}
            onChange={(e) => updateFormData('username', e.target.value)}
            placeholder="dbadmin"
            className={errors.username ? styles.inputError : ''}
            autoComplete="username"
          />
          {errors.username && (
            <span className={styles.fieldError}>{errors.username}</span>
          )}
        </div>

        {/* Password */}
        <div className={styles.formGroup}>
          <label htmlFor="password">
            <Lock size={16} />
            Password
            {!isEditMode && <span className={styles.required}>*</span>}
          </label>
          <div className={styles.passwordInput}>
            <input
              id="password"
              type={showPassword ? 'text' : 'password'}
              value={formData.password}
              onChange={(e) => updateFormData('password', e.target.value)}
              placeholder={isEditMode ? '(unchanged)' : 'Enter password'}
              className={errors.password ? styles.inputError : ''}
              autoComplete="current-password"
            />
            <button
              type="button"
              className={styles.passwordToggle}
              onClick={() => setShowPassword(!showPassword)}
              tabIndex={-1}
            >
              {showPassword ? <EyeOff size={18} /> : <Eye size={18} />}
            </button>
          </div>
          {errors.password && (
            <span className={styles.fieldError}>{errors.password}</span>
          )}
          {isEditMode && (
            <span className={styles.fieldHint}>Leave empty to keep existing password</span>
          )}
        </div>
      </div>

      {/* Required Privileges */}
      <PrivilegesAccordion dbType={formData.dbType} />
    </div>
  )
}

export default EndpointCredentialsStep
