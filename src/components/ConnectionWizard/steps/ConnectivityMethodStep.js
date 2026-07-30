import { useState } from 'react'
import { Eye, EyeOff, Server, User, Lock, Hash, Key } from 'lucide-react'
import { CONNECTIVITY_METHODS } from '../utils/constants'
import { SelectableCard } from '../components/SelectableCard'
import { IPAllowlist } from '../components/IPAllowlist'
import { CertificateUpload } from '../components/CertificateUpload'
import { QuickTestButton } from '../components/QuickTestButton'
import styles from '../ConnectionWizard.module.css'

/**
 * Step 5: Connectivity method (IP Allowlisting or SSH Tunnel)
 * Features custom connectivity icons
 */
export function ConnectivityMethodStep({ formData, updateFormData, errors, isEditMode, existingConnection }) {
  const [showSshPassword, setShowSshPassword] = useState(false)
  const [showSshPassphrase, setShowSshPassphrase] = useState(false)

  const isSshTunnel = formData.connectivityMethod === 'ssh-tunnel'

  // Check if existing connection has SSH configured
  const hasExistingSshPassword = isEditMode && existingConnection?.sshEnabled && existingConnection?.sshAuthType === 'PASSWORD'
  const hasExistingSshKey = isEditMode && existingConnection?.sshEnabled && existingConnection?.sshAuthType === 'PRIVATE_KEY'

  return (
    <div className={styles.stepContent}>
      <div className={styles.stepHeader}>
        <h2>Connectivity Method</h2>
        <p>Choose how to connect to your database</p>
      </div>

      {/* Connectivity Method Selection */}
      <div className={styles.connectivityGrid}>
        {CONNECTIVITY_METHODS.map((method) => (
          <SelectableCard
            key={method.id}
            id={method.id}
            name={method.name}
            description={method.description}
            icon={method.icon}
            iconType="connectivity"
            selected={formData.connectivityMethod === method.id}
            onClick={(id) => updateFormData('connectivityMethod', id)}
            size="medium"
          />
        ))}
      </div>

      {errors.connectivityMethod && (
        <p className={styles.errorMessage}>{errors.connectivityMethod}</p>
      )}

      {/* IP Allowlisting Section */}
      {formData.connectivityMethod === 'direct' && (
        <IPAllowlist />
      )}

      {/* SSH Tunnel Configuration */}
      {isSshTunnel && (
        <div className={styles.sshConfigSection}>
          <h3>SSH Tunnel Configuration</h3>
          <p className={styles.sshNote}>
            Connect through an SSH bastion host. SSL is not needed when using SSH tunnel
            (the tunnel provides encryption).
          </p>

          {/* IP Allowlist for bastion host */}
          <IPAllowlist
            title="Allowlist for Bastion Host"
            note="If your bastion host's SSH port is not open to the public, add our IP to your security group or firewall rules."
          />

          <div className={styles.formGrid}>
            {/* SSH Host */}
            <div className={styles.formGroup}>
              <label htmlFor="sshHost">
                <Server size={16} />
                SSH Host (Bastion)
                <span className={styles.required}>*</span>
              </label>
              <input
                id="sshHost"
                type="text"
                value={formData.sshHost}
                onChange={(e) => updateFormData('sshHost', e.target.value)}
                placeholder="bastion.example.com"
                className={errors.sshHost ? styles.inputError : ''}
              />
              {errors.sshHost && (
                <span className={styles.fieldError}>{errors.sshHost}</span>
              )}
            </div>

            {/* SSH Port */}
            <div className={styles.formGroup}>
              <label htmlFor="sshPort">
                <Hash size={16} />
                SSH Port
              </label>
              <input
                id="sshPort"
                type="number"
                value={formData.sshPort}
                onChange={(e) => updateFormData('sshPort', e.target.value)}
                placeholder="22"
                className={errors.sshPort ? styles.inputError : ''}
              />
              {errors.sshPort && (
                <span className={styles.fieldError}>{errors.sshPort}</span>
              )}
              <span className={styles.fieldHint}>Default: 22</span>
            </div>

            {/* SSH Username */}
            <div className={styles.formGroup}>
              <label htmlFor="sshUsername">
                <User size={16} />
                SSH Username
                <span className={styles.required}>*</span>
              </label>
              <input
                id="sshUsername"
                type="text"
                value={formData.sshUsername}
                onChange={(e) => updateFormData('sshUsername', e.target.value)}
                placeholder="ec2-user"
                className={errors.sshUsername ? styles.inputError : ''}
              />
              {errors.sshUsername && (
                <span className={styles.fieldError}>{errors.sshUsername}</span>
              )}
            </div>

            {/* SSH Auth Type */}
            <div className={styles.formGroup}>
              <label htmlFor="sshAuthType">
                <Key size={16} />
                Authentication Method
              </label>
              <select
                id="sshAuthType"
                value={formData.sshAuthType}
                onChange={(e) => updateFormData('sshAuthType', e.target.value)}
              >
                <option value="PASSWORD">Password</option>
                <option value="PRIVATE_KEY">Private Key</option>
              </select>
            </div>
          </div>

          {/* Password Auth */}
          {formData.sshAuthType === 'PASSWORD' && (
            <div className={styles.formGroup}>
              <label htmlFor="sshPassword">
                <Lock size={16} />
                SSH Password
                {!isEditMode && <span className={styles.required}>*</span>}
              </label>
              <div className={styles.passwordInput}>
                <input
                  id="sshPassword"
                  type={showSshPassword ? 'text' : 'password'}
                  value={formData.sshPassword}
                  onChange={(e) => updateFormData('sshPassword', e.target.value)}
                  placeholder={isEditMode ? '(unchanged)' : 'Enter SSH password'}
                  className={errors.sshPassword ? styles.inputError : ''}
                />
                <button
                  type="button"
                  className={styles.passwordToggle}
                  onClick={() => setShowSshPassword(!showSshPassword)}
                  tabIndex={-1}
                >
                  {showSshPassword ? <EyeOff size={18} /> : <Eye size={18} />}
                </button>
              </div>
              {errors.sshPassword && (
                <span className={styles.fieldError}>{errors.sshPassword}</span>
              )}
              {isEditMode && hasExistingSshPassword && (
                <span className={styles.fieldHint}>Leave empty to keep existing password</span>
              )}
            </div>
          )}

          {/* Private Key Auth */}
          {formData.sshAuthType === 'PRIVATE_KEY' && (
            <>
              <CertificateUpload
                label="SSH Private Key"
                value={formData.sshPrivateKey}
                onChange={(value) => updateFormData('sshPrivateKey', value)}
                error={errors.sshPrivateKey}
                hasExisting={hasExistingSshKey}
                placeholder="-----BEGIN RSA PRIVATE KEY-----\n...\n-----END RSA PRIVATE KEY-----"
                isKey
                required={!isEditMode}
              />

              {/* Key Passphrase */}
              <div className={styles.formGroup}>
                <label htmlFor="sshPassphrase">
                  <Lock size={16} />
                  Key Passphrase (optional)
                </label>
                <div className={styles.passwordInput}>
                  <input
                    id="sshPassphrase"
                    type={showSshPassphrase ? 'text' : 'password'}
                    value={formData.sshPassphrase}
                    onChange={(e) => updateFormData('sshPassphrase', e.target.value)}
                    placeholder={isEditMode ? '(unchanged)' : 'Enter if key is encrypted'}
                  />
                  <button
                    type="button"
                    className={styles.passwordToggle}
                    onClick={() => setShowSshPassphrase(!showSshPassphrase)}
                    tabIndex={-1}
                  >
                    {showSshPassphrase ? <EyeOff size={18} /> : <Eye size={18} />}
                  </button>
                </div>
                <span className={styles.fieldHint}>
                  Only needed if your private key is encrypted with a passphrase
                </span>
              </div>
            </>
          )}
        </div>
      )}

      {/* Quick Test - Test full connection including SSH tunnel */}
      <QuickTestButton formData={formData} />
    </div>
  )
}

export default ConnectivityMethodStep
