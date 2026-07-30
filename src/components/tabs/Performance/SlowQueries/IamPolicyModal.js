'use client'

import { useState } from 'react'
import { X, Copy, Check, Info } from 'lucide-react'
import { getProviderLabel, generateIamPolicy } from './utils'
import styles from '../SlowQueryAnalysisTab.module.css'

/**
 * Modal displaying IAM policy for AWS S3 or CloudWatch log access
 */
export default function IamPolicyModal({
    sourceConfig,
    onClose,
}) {
    const [copiedPolicy, setCopiedPolicy] = useState(false)

    const copyIamPolicy = async () => {
        try {
            const policy = generateIamPolicy(sourceConfig)
            await navigator.clipboard.writeText(JSON.stringify(policy, null, 2))
            setCopiedPolicy(true)
            setTimeout(() => setCopiedPolicy(false), 2000)
        } catch (err) {
            console.error('Failed to copy IAM policy:', err)
        }
    }

    const policy = generateIamPolicy(sourceConfig)

    return (
        <div className={styles.uploadModal}>
            <div className={styles.uploadModalContent}>
                <div className={styles.uploadModalHeader}>
                    <h3>Suggested IAM Policy</h3>
                    <button
                        className={styles.closeModalButton}
                        onClick={onClose}
                    >
                        <X size={20} />
                    </button>
                </div>
                <div className={styles.uploadModalBody}>
                    <div className={styles.iamPolicyHeader}>
                        <span className={styles.iamPolicyLabel}>
                            {sourceConfig.providerType === 'S3'
                                ? 'AWS S3 Read-Only Policy'
                                : sourceConfig.providerType === 'CLOUDWATCH'
                                    ? 'AWS CloudWatch Logs Read-Only Policy'
                                    : `${getProviderLabel(sourceConfig.providerType)} Setup Guide`}
                        </span>
                        <button
                            type="button"
                            className={styles.copyPolicyButton}
                            onClick={copyIamPolicy}
                            title="Copy IAM Policy"
                        >
                            {copiedPolicy ? <Check size={14} /> : <Copy size={14} />}
                            <span>{copiedPolicy ? 'Copied!' : 'Copy'}</span>
                        </button>
                    </div>
                    <pre className={styles.iamPolicyCode}>
                        {JSON.stringify(policy, null, 2)}
                    </pre>
                    <div className={styles.iamPolicyNote}>
                        <strong>Note:</strong> Replace <code>&lt;ACCOUNT_ID&gt;</code> with your AWS account ID.
                        {sourceConfig.providerType === 'S3' && !sourceConfig.bucketName && (
                            <> Replace <code>&lt;BUCKET_NAME&gt;</code> with your S3 bucket name.</>
                        )}
                        {sourceConfig.providerType === 'CLOUDWATCH' && !sourceConfig.logGroupName && (
                            <> Replace <code>&lt;LOG_GROUP_NAME&gt;</code> with your CloudWatch log group.</>
                        )}
                    </div>
                    <div className={styles.iamPolicyInfo}>
                        <Info size={14} />
                        <span>
                            This policy grants the minimum permissions required for slow log ingestion from{' '}
                            {sourceConfig.providerType === 'S3' ? 'S3' : 'CloudWatch Logs'}.
                        </span>
                    </div>
                </div>
            </div>
        </div>
    )
}
