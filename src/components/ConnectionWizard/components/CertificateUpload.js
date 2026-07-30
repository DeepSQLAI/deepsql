import { useState, useRef } from 'react'
import { Upload, FileText, CheckCircle, X, AlertCircle } from 'lucide-react'
import styles from '../ConnectionWizard.module.css'

/**
 * Certificate/Key upload component with file upload and text paste support
 */
export function CertificateUpload({
  label,
  value,
  onChange,
  error,
  hasExisting,
  placeholder,
  isKey = false,
  required = false,
}) {
  const [isDragging, setIsDragging] = useState(false)
  const fileInputRef = useRef(null)

  const handleFileChange = async (event) => {
    const file = event.target.files?.[0]
    if (!file) return

    try {
      const text = await file.text()
      onChange(text)
    } catch (err) {
      console.error('Failed to read file:', err)
    }

    // Reset input so same file can be selected again
    event.target.value = ''
  }

  const handleDragOver = (e) => {
    e.preventDefault()
    setIsDragging(true)
  }

  const handleDragLeave = (e) => {
    e.preventDefault()
    setIsDragging(false)
  }

  const handleDrop = async (e) => {
    e.preventDefault()
    setIsDragging(false)

    const file = e.dataTransfer.files?.[0]
    if (!file) return

    try {
      const text = await file.text()
      onChange(text)
    } catch (err) {
      console.error('Failed to read dropped file:', err)
    }
  }

  const handleClear = () => {
    onChange('')
    if (fileInputRef.current) {
      fileInputRef.current.value = ''
    }
  }

  const hasValue = value && value.trim() !== ''
  const showConfigured = hasExisting && !hasValue

  return (
    <div className={styles.certificateUpload}>
      <label className={styles.certificateLabel}>
        {label}
        {required && <span className={styles.required}>*</span>}
      </label>

      {/* Show "configured" badge in edit mode when no new value provided */}
      {showConfigured && (
        <div className={styles.configuredBadge}>
          <CheckCircle size={14} />
          <span>{isKey ? 'Key' : 'Certificate'} configured</span>
          <span className={styles.configuredHint}>(leave empty to keep existing)</span>
        </div>
      )}

      {/* Drop zone / textarea */}
      <div
        className={`${styles.certificateDropZone} ${isDragging ? styles.dragging : ''} ${error ? styles.error : ''} ${hasValue ? styles.hasValue : ''}`}
        onDragOver={handleDragOver}
        onDragLeave={handleDragLeave}
        onDrop={handleDrop}
      >
        {hasValue ? (
          <div className={styles.certificatePreview}>
            <FileText size={20} />
            <span className={styles.certificatePreviewText}>
              {isKey ? 'Private key loaded' : 'Certificate loaded'}
            </span>
            <span className={styles.certificatePreviewSize}>
              ({value.length.toLocaleString()} characters)
            </span>
            <button
              type="button"
              className={styles.certificateClear}
              onClick={handleClear}
              title="Clear"
            >
              <X size={16} />
            </button>
          </div>
        ) : (
          <div className={styles.certificatePlaceholder}>
            <Upload size={24} />
            <span>
              Drop {isKey ? 'key' : 'certificate'} file here or{' '}
              <button
                type="button"
                className={styles.certificateBrowse}
                onClick={() => fileInputRef.current?.click()}
              >
                browse
              </button>
            </span>
            <span className={styles.certificateFormats}>
              Accepts .pem, .crt, .key files
            </span>
          </div>
        )}
      </div>

      {/* Hidden file input */}
      <input
        ref={fileInputRef}
        type="file"
        accept=".pem,.crt,.key,.cer,.cert,.txt"
        onChange={handleFileChange}
        style={{ display: 'none' }}
      />

      {/* Manual paste textarea */}
      <details className={styles.certificatePaste}>
        <summary>Or paste {isKey ? 'key' : 'certificate'} content</summary>
        <textarea
          value={value}
          onChange={(e) => onChange(e.target.value)}
          placeholder={placeholder || `-----BEGIN ${isKey ? 'PRIVATE KEY' : 'CERTIFICATE'}-----\n...\n-----END ${isKey ? 'PRIVATE KEY' : 'CERTIFICATE'}-----`}
          className={styles.certificateTextarea}
          rows={6}
        />
      </details>

      {/* Error message */}
      {error && (
        <div className={styles.certificateError}>
          <AlertCircle size={14} />
          <span>{error}</span>
        </div>
      )}
    </div>
  )
}

export default CertificateUpload
