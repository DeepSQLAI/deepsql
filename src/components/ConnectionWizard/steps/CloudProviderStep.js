import { useState } from 'react'
import { ChevronDown, ChevronRight, Cpu, HardDrive, MemoryStick, Gauge } from 'lucide-react'
import { CLOUD_PROVIDERS, MANAGED_SERVICES, STORAGE_TYPES } from '../utils/constants'
import { SelectableCard } from '../components/SelectableCard'
import styles from '../ConnectionWizard.module.css'

/**
 * Step 2: Select cloud provider, managed service, and instance specs (all optional)
 */
export function CloudProviderStep({ formData, updateFormData }) {
  const [showInstanceSpecs, setShowInstanceSpecs] = useState(
    // Auto-expand if any instance specs are filled
    Boolean(formData.instanceClass || formData.instanceVcpus || formData.instanceMemoryGb || formData.storageType)
  )

  const services = MANAGED_SERVICES[formData.cloudProvider] || []

  // Filter storage types based on provider
  const storageTypes = STORAGE_TYPES.filter(
    (st) => st.provider === 'all' || st.provider === formData.cloudProvider || !formData.cloudProvider
  )

  return (
    <div className={styles.stepContent}>
      <div className={styles.stepHeader}>
        <h2>Deployment Environment</h2>
        <p>Select your cloud provider and instance details (optional)</p>
      </div>

      {/* Cloud Provider Selection */}
      <div className={styles.cloudProviderGrid}>
        {CLOUD_PROVIDERS.map((provider) => (
          <SelectableCard
            key={provider.id}
            id={provider.id}
            name={provider.name}
            icon={provider.icon}
            iconType="cloud"
            selected={formData.cloudProvider === provider.id}
            onClick={(id) => {
              updateFormData('cloudProvider', id)
              // Clear managed service when provider changes
              updateFormData('managedService', '')
            }}
            size="medium"
          />
        ))}
      </div>

      {/* Skip button */}
      {formData.cloudProvider && (
        <button
          type="button"
          className={styles.skipButton}
          onClick={() => {
            updateFormData('cloudProvider', '')
            updateFormData('managedService', '')
          }}
        >
          Clear selection
        </button>
      )}

      {/* Managed Service Selection (only if provider has services) */}
      {services.length > 0 && (
        <div className={styles.managedServiceSection}>
          <h3>Service Type</h3>
          <div className={styles.managedServiceGrid}>
            {services.map((service) => (
              <SelectableCard
                key={service.id}
                id={service.id}
                name={service.name}
                selected={formData.managedService === service.id}
                onClick={(id) => updateFormData('managedService', id)}
                size="small"
              />
            ))}
          </div>
        </div>
      )}

      {/* Instance Specs Accordion */}
      <div className={styles.instanceSpecsAccordion}>
        <button
          type="button"
          className={styles.instanceSpecsHeader}
          onClick={() => setShowInstanceSpecs(!showInstanceSpecs)}
        >
          <HardDrive size={16} />
          <span>Instance Specifications (Optional)</span>
          {showInstanceSpecs ? <ChevronDown size={16} /> : <ChevronRight size={16} />}
        </button>

        {showInstanceSpecs && (
          <div className={styles.instanceSpecsContent}>
            <p className={styles.instanceSpecsNote}>
              Providing hardware details helps us give more accurate tuning recommendations.
            </p>

            <div className={styles.instanceSpecsGrid}>
              {/* Instance Class */}
              <div className={styles.formGroup}>
                <label htmlFor="instanceClass">
                  <HardDrive size={16} />
                  Instance Class
                </label>
                <input
                  id="instanceClass"
                  type="text"
                  value={formData.instanceClass}
                  onChange={(e) => updateFormData('instanceClass', e.target.value)}
                  placeholder="e.g., db.r5.large, Standard_D4s_v3"
                />
                <span className={styles.fieldHint}>The instance type/class name</span>
              </div>

              {/* vCPUs */}
              <div className={styles.formGroup}>
                <label htmlFor="instanceVcpus">
                  <Cpu size={16} />
                  vCPUs
                </label>
                <input
                  id="instanceVcpus"
                  type="number"
                  min="1"
                  value={formData.instanceVcpus}
                  onChange={(e) => updateFormData('instanceVcpus', e.target.value)}
                  placeholder="e.g., 4"
                />
                <span className={styles.fieldHint}>Number of virtual CPUs</span>
              </div>

              {/* Memory */}
              <div className={styles.formGroup}>
                <label htmlFor="instanceMemoryGb">
                  <MemoryStick size={16} />
                  Memory (GB)
                </label>
                <input
                  id="instanceMemoryGb"
                  type="number"
                  min="1"
                  step="0.5"
                  value={formData.instanceMemoryGb}
                  onChange={(e) => updateFormData('instanceMemoryGb', e.target.value)}
                  placeholder="e.g., 16"
                />
                <span className={styles.fieldHint}>RAM in gigabytes</span>
              </div>

              {/* Storage Type */}
              <div className={styles.formGroup}>
                <label htmlFor="storageType">
                  <HardDrive size={16} />
                  Storage Type
                </label>
                <select
                  id="storageType"
                  value={formData.storageType}
                  onChange={(e) => updateFormData('storageType', e.target.value)}
                >
                  <option value="">Select storage type...</option>
                  {storageTypes.map((st) => (
                    <option key={st.id} value={st.id}>
                      {st.name}
                    </option>
                  ))}
                </select>
                <span className={styles.fieldHint}>Disk type affects I/O performance</span>
              </div>

              {/* Max IOPS */}
              <div className={styles.formGroup}>
                <label htmlFor="storageMaxIops">
                  <Gauge size={16} />
                  Max IOPS
                </label>
                <input
                  id="storageMaxIops"
                  type="number"
                  min="100"
                  value={formData.storageMaxIops}
                  onChange={(e) => updateFormData('storageMaxIops', e.target.value)}
                  placeholder="e.g., 3000"
                />
                <span className={styles.fieldHint}>Maximum I/O operations per second</span>
              </div>
            </div>
          </div>
        )}
      </div>

      <div className={styles.stepNote}>
        <p>
          This information is optional and helps us provide relevant features like:
        </p>
        <ul>
          <li>Cloud-specific log ingestion (CloudWatch, Azure Monitor)</li>
          <li>Hardware-aware tuning recommendations</li>
          <li>Performance Insights integration (RDS, Aurora)</li>
        </ul>
      </div>
    </div>
  )
}

export default CloudProviderStep
