'use client'

import { Component } from 'react'
import { AlertCircle } from 'lucide-react'

/**
 * Error Boundary component to catch errors in the Brain tab
 * Prevents the entire app from crashing when Brain components error
 */
export class BrainErrorBoundary extends Component {
    constructor(props) {
        super(props)
        this.state = { hasError: false, error: null, errorInfo: null }
    }

    static getDerivedStateFromError(error) {
        return { hasError: true }
    }

    componentDidCatch(error, errorInfo) {
        console.error('Brain tab error:', error, errorInfo)
        this.setState({
            error,
            errorInfo
        })
    }

    render() {
        if (this.state.hasError) {
            return (
                <div style={{
                    padding: '2rem',
                    display: 'flex',
                    flexDirection: 'column',
                    alignItems: 'center',
                    justifyContent: 'center',
                    gap: '1rem',
                    minHeight: '400px'
                }}>
                    <AlertCircle size={48} color="var(--color-danger)" />
                    <h2 style={{ margin: 0, fontSize: '1.5rem', fontWeight: 600 }}>
                        Something went wrong
                    </h2>
                    <p style={{ margin: 0, color: 'var(--color-grey)', textAlign: 'center', maxWidth: '600px' }}>
                        The Brain tab encountered an error. Please try refreshing the page.
                        If the problem persists, check the console for more details.
                    </p>
                    {this.state.error && (
                        <details style={{ marginTop: '1rem', maxWidth: '800px', width: '100%' }}>
                            <summary style={{ cursor: 'pointer', color: 'var(--color-grey)', fontSize: '0.875rem' }}>
                                Error details
                            </summary>
                            <pre style={{
                                marginTop: '0.5rem',
                                padding: '1rem',
                                backgroundColor: 'var(--color-light-2)',
                                borderRadius: '4px',
                                overflow: 'auto',
                                fontSize: '0.75rem'
                            }}>
                                {this.state.error.toString()}
                                {this.state.errorInfo?.componentStack}
                            </pre>
                        </details>
                    )}
                    <button
                        onClick={() => window.location.reload()}
                        style={{
                            marginTop: '1rem',
                            padding: '0.5rem 1rem',
                            backgroundColor: 'var(--color-primary)',
                            color: 'white',
                            border: 'none',
                            borderRadius: '4px',
                            cursor: 'pointer',
                            fontSize: '0.875rem',
                            fontWeight: 500
                        }}
                    >
                        Refresh page
                    </button>
                </div>
            )
        }

        return this.props.children
    }
}
