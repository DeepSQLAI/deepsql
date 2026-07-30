'use client'

import { useState } from 'react'
import { X, Upload, Loader2 } from 'lucide-react'
import styles from '../SlowQueryAnalysisTab.module.css'

/**
 * Modal for uploading slow query log files or analyzing from S3
 */
export default function UploadModal({
    onClose,
    onFileUpload,
    onS3Analyze,
    uploading,
    uploadLabel,
}) {
    const [databaseType, setDatabaseType] = useState('mysql')
    const [s3Url, setS3Url] = useState('')
    const [s3Region, setS3Region] = useState('')

    const handleFileChange = (e) => {
        onFileUpload(e, databaseType)
        onClose()
    }

    const handleS3Submit = () => {
        onClose()
        onS3Analyze(s3Url, s3Region, databaseType)
    }

    return (
        <div className={styles.uploadModal}>
            <div className={styles.uploadModalContent}>
                <div className={styles.uploadModalHeader}>
                    <h3>Upload Slow Query Log</h3>
                    <button
                        className={styles.closeModalButton}
                        onClick={onClose}
                    >
                        <X size={20} />
                    </button>
                </div>
                <div className={styles.uploadModalBody}>
                    <div className={styles.formGroup}>
                        <label>Database Type</label>
                        <select
                            value={databaseType}
                            onChange={(e) => setDatabaseType(e.target.value)}
                            className={styles.dbTypeSelect}
                        >
                            <option value="mysql">MySQL</option>
                            <option value="postgresql">PostgreSQL</option>
                        </select>
                    </div>
                    <div className={styles.formGroup}>
                        <label>Log File (.log or .txt)</label>
                        <label htmlFor="log-file-input" className={styles.fileInputLabel}>
                            <Upload size={20} />
                            <span>Choose File</span>
                            <input
                                id="log-file-input"
                                type="file"
                                accept=".log,.txt"
                                onChange={handleFileChange}
                                style={{ display: 'none' }}
                            />
                        </label>
                    </div>
                    <div className={styles.formDivider}>OR</div>
                    <div className={styles.formGroup}>
                        <label>S3 Log URL</label>
                        <input
                            type="text"
                            placeholder="s3://bucket/path/to/slow.log"
                            value={s3Url}
                            onChange={(e) => setS3Url(e.target.value)}
                            className={styles.textInput}
                        />
                    </div>
                    <div className={styles.formGroup}>
                        <label>AWS Region (optional)</label>
                        <input
                            type="text"
                            placeholder="us-west-2"
                            value={s3Region}
                            onChange={(e) => setS3Region(e.target.value)}
                            className={styles.textInput}
                        />
                    </div>
                    <button
                        className={styles.s3AnalyzeButton}
                        onClick={handleS3Submit}
                        disabled={!s3Url || uploading}
                    >
                        Analyze from S3
                    </button>
                    {uploading && (
                        <div className={styles.uploadingIndicator}>
                            <Loader2 size={16} className={styles.spinner} />
                            <span>Analyzing {uploadLabel}...</span>
                        </div>
                    )}
                </div>
            </div>
        </div>
    )
}
