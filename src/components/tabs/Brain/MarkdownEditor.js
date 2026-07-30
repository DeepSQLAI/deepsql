'use client'

import { useState } from 'react'
import ReactMarkdown from 'react-markdown'
import styles from './MarkdownEditor.module.css'

/**
 * Markdown editor with live preview
 * Supports basic markdown formatting for notes
 */
export function MarkdownEditor({
    value = '',
    onChange,
    placeholder = 'Write your note in markdown...',
    showPreview = true
}) {
    const [activeTab, setActiveTab] = useState('write')

    const handleChange = (e) => {
        onChange(e.target.value)
    }

    return (
        <div className={styles.markdownEditor}>
            {showPreview && (
                <div className={styles.tabs}>
                    <button
                        type="button"
                        className={`${styles.tab} ${activeTab === 'write' ? styles.active : ''}`}
                        onClick={() => setActiveTab('write')}
                    >
                        Write
                    </button>
                    <button
                        type="button"
                        className={`${styles.tab} ${activeTab === 'preview' ? styles.active : ''}`}
                        onClick={() => setActiveTab('preview')}
                    >
                        Preview
                    </button>
                </div>
            )}

            {activeTab === 'write' ? (
                <div className={styles.writeTab}>
                    <textarea
                        value={value}
                        onChange={handleChange}
                        placeholder={placeholder}
                        className={styles.textarea}
                    />
                    <div className={styles.toolbar}>
                        <span className={styles.hint}>
                            Markdown supported: **bold**, *italic*, `code`, [links](url)
                        </span>
                    </div>
                </div>
            ) : (
                <div className={styles.previewTab}>
                    <div className={styles.preview}>
                        {value ? (
                            <ReactMarkdown>{value}</ReactMarkdown>
                        ) : (
                            <p className={styles.emptyPreview}>Nothing to preview</p>
                        )}
                    </div>
                </div>
            )}
        </div>
    )
}
