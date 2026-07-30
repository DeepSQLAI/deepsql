import { SSL_MODES } from '../utils/constants'
import { SelectableCard } from '../components/SelectableCard'
import { CertificateUpload } from '../components/CertificateUpload'
import styles from '../ConnectionWizard.module.css'

/**
 * Step 4: SSL configuration
 * This step is hidden when SSH tunnel is selected (SSH provides encryption)
 * Features custom SSL mode icons for visual clarity
 */
export function ConnectionSecurityStep({ formData, updateFormData, errors, isEditMode, existingConnection }) {
  const showCaCert = formData.sslMode === 'server-only' || formData.sslMode === 'server-client'
  const showClientCerts = formData.sslMode === 'server-client'

  // Check if existing connection has certificates configured
  const hasExistingCaCert = isEditMode && existingConnection?.sslMode && existingConnection.sslMode !== 'none'
  const hasExistingClientCert = isEditMode && existingConnection?.sslMode === 'server-client'

  return (
    <div className={styles.stepContent}>
      <div className={styles.stepHeader}>
        <h2>Connection Security</h2>
        <p>Configure SSL/TLS encryption for your database connection</p>
      </div>

      {/* SSL Mode Selection */}
      <div className={styles.sslModeGrid}>
        {SSL_MODES.map((mode) => (
          <SelectableCard
            key={mode.id}
            id={mode.id}
            name={mode.name}
            description={mode.description}
            icon={mode.id}
            iconType="ssl"
            selected={formData.sslMode === mode.id}
            onClick={(id) => updateFormData('sslMode', id)}
            size="medium"
          />
        ))}
      </div>

      {/* SSL Certificate Fields */}
      {showCaCert && (
        <div className={styles.certificatesSection}>
          <h3>SSL Certificates</h3>

          {/* CA Certificate */}
          <CertificateUpload
            label="CA Certificate"
            value={formData.sslCaCertificate}
            onChange={(value) => updateFormData('sslCaCertificate', value)}
            error={errors.sslCaCertificate}
            hasExisting={hasExistingCaCert}
            placeholder="-----BEGIN CERTIFICATE-----\nYour CA certificate...\n-----END CERTIFICATE-----"
            required={!isEditMode}
          />

          {/* Client Certificate (mTLS only) */}
          {showClientCerts && (
            <>
              <CertificateUpload
                label="Client Certificate"
                value={formData.sslClientCertificate}
                onChange={(value) => updateFormData('sslClientCertificate', value)}
                error={errors.sslClientCertificate}
                hasExisting={hasExistingClientCert}
                placeholder="-----BEGIN CERTIFICATE-----\nYour client certificate...\n-----END CERTIFICATE-----"
                required={!isEditMode}
              />

              <CertificateUpload
                label="Client Private Key"
                value={formData.sslClientKey}
                onChange={(value) => updateFormData('sslClientKey', value)}
                error={errors.sslClientKey}
                hasExisting={hasExistingClientCert}
                placeholder="-----BEGIN PRIVATE KEY-----\nYour private key...\n-----END PRIVATE KEY-----"
                isKey
                required={!isEditMode}
              />

              {/* Key Passphrase (optional) */}
              <div className={styles.formGroup}>
                <label htmlFor="sslClientKeyPassphrase">
                  Key Passphrase (optional)
                </label>
                <input
                  id="sslClientKeyPassphrase"
                  type="password"
                  value={formData.sslClientKeyPassphrase}
                  onChange={(e) => updateFormData('sslClientKeyPassphrase', e.target.value)}
                  placeholder={isEditMode ? '(unchanged)' : 'Enter if key is encrypted'}
                />
                <span className={styles.fieldHint}>
                  Only needed if your private key is encrypted with a passphrase
                </span>
              </div>
            </>
          )}
        </div>
      )}

      {/* Help text */}
      <div className={styles.stepNote}>
        <p><strong>SSL Modes Explained:</strong></p>
        <ul>
          <li><strong>No SSL:</strong> Traffic is unencrypted. Only use in trusted networks.</li>
          <li><strong>Server-Only:</strong> Server presents certificate; client verifies it. Most common for cloud databases.</li>
          <li><strong>Mutual TLS:</strong> Both sides authenticate. Required for high-security environments.</li>
        </ul>
        {formData.dbType === 'postgres' && (
          <p className={styles.dbSpecificNote}>
            For AWS RDS/Aurora, download the CA bundle from{' '}
            <a href="https://docs.aws.amazon.com/AmazonRDS/latest/UserGuide/UsingWithRDS.SSL.html" target="_blank" rel="noopener noreferrer">
              AWS documentation
            </a>.
          </p>
        )}
        {formData.dbType === 'mysql' && (
          <p className={styles.dbSpecificNote}>
            For MySQL, you can usually find CA certificates in your cloud provider&apos;s documentation.
          </p>
        )}
      </div>
    </div>
  )
}

export default ConnectionSecurityStep
