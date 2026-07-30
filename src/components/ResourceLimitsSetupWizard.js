'use client'

import { useState, useEffect } from 'react'
import { X, Server, Zap, Users, Cpu, CheckCircle, AlertCircle, Info, Clock } from 'lucide-react'
import { resourceLimitsAPI } from '@/lib/api/client'

export default function ResourceLimitsSetupWizard({ connectionId, onClose, onComplete }) {
    const [step, setStep] = useState(1)
    const [loading, setLoading] = useState(false)
    const [error, setError] = useState(null)
    const [success, setSuccess] = useState(false)

    const [formData, setFormData] = useState({
        // Storage
        maxStorageGB: 100,
        storageWarningPercent: 70,
        storageCriticalPercent: 85,
        // IOPS
        maxIops: 10000,
        iopsWarningPercent: 70,
        iopsCriticalPercent: 85,
        // Connections
        maxConnections: 500,
        connectionWarningPercent: 70,
        connectionCriticalPercent: 85,
        // CPU
        maxCpuPercent: 80,
        cpuWarningPercent: 70,
        cpuCriticalPercent: 85,
        // Collection
        collectionCadence: 'HOUR',
        performanceInsightsRetentionDays: 7,
        // Enabled
        isEnabled: true
    })

    useEffect(() => {
        loadExistingLimits()
    }, [connectionId])

    const loadExistingLimits = async () => {
        try {
            const response = await resourceLimitsAPI.getResourceLimits(connectionId)
            if (response.exists !== false && response.maxStorageBytes) {
                // Convert existing data
                setFormData({
                    maxStorageGB: Math.round(response.maxStorageBytes / (1024 * 1024 * 1024)),
                    storageWarningPercent: response.storageWarningPercent || 70,
                    storageCriticalPercent: response.storageCriticalPercent || 85,
                    maxIops: response.maxIops || 10000,
                    iopsWarningPercent: response.iopsWarningPercent || 70,
                    iopsCriticalPercent: response.iopsCriticalPercent || 85,
                    maxConnections: response.maxConnections || 500,
                    connectionWarningPercent: response.connectionWarningPercent || 70,
                    connectionCriticalPercent: response.connectionCriticalPercent || 85,
                    maxCpuPercent: response.maxCpuPercent || 80,
                    cpuWarningPercent: response.cpuWarningPercent || 70,
                    cpuCriticalPercent: response.cpuCriticalPercent || 85,
                    collectionCadence: response.collectionCadence || 'HOUR',
                    performanceInsightsRetentionDays: response.performanceInsightsRetentionDays || 7,
                    isEnabled: response.isEnabled !== false
                })
            }
        } catch (err) {
            console.error('Failed to load existing limits:', err)
        }
    }

    const handleInputChange = (field, value) => {
        setFormData(prev => ({ ...prev, [field]: value }))
    }

    const handleSubmit = async () => {
        setLoading(true)
        setError(null)

        try {
            // Convert GB to bytes
            const limits = {
                maxStorageBytes: formData.maxStorageGB * 1024 * 1024 * 1024,
                storageWarningPercent: formData.storageWarningPercent,
                storageCriticalPercent: formData.storageCriticalPercent,
                maxIops: formData.maxIops,
                iopsWarningPercent: formData.iopsWarningPercent,
                iopsCriticalPercent: formData.iopsCriticalPercent,
                maxConnections: formData.maxConnections,
                connectionWarningPercent: formData.connectionWarningPercent,
                connectionCriticalPercent: formData.connectionCriticalPercent,
                maxCpuPercent: formData.maxCpuPercent,
                cpuWarningPercent: formData.cpuWarningPercent,
                cpuCriticalPercent: formData.cpuCriticalPercent,
                collectionCadence: formData.collectionCadence,
                performanceInsightsRetentionDays: formData.performanceInsightsRetentionDays,
                isEnabled: formData.isEnabled
            }

            await resourceLimitsAPI.saveResourceLimits(connectionId, limits)
            setSuccess(true)

            setTimeout(() => {
                onComplete?.()
                onClose?.()
            }, 2000)

        } catch (err) {
            setError(err.message || 'Failed to save resource limits')
        } finally {
            setLoading(false)
        }
    }

    const nextStep = () => setStep(s => Math.min(s + 1, 6))
    const prevStep = () => setStep(s => Math.max(s - 1, 1))

    return (
        <div className="fixed inset-0 bg-black bg-opacity-50 flex items-center justify-center z-50 p-4">
            <div className="bg-white rounded-lg shadow-xl max-w-2xl w-full max-h-[90vh] overflow-hidden flex flex-col">
                {/* Header */}
                <div className="p-6 border-b border-gray-200 flex items-center justify-between bg-gradient-to-r from-blue-600 to-blue-700 text-white">
                    <div>
                        <h2 className="text-2xl font-bold">Resource Limits Setup</h2>
                        <p className="text-blue-100 text-sm mt-1">Configure capacity thresholds for Sentinel Analytics</p>
                    </div>
                    <button
                        onClick={onClose}
                        className="p-2 hover:bg-blue-500 rounded-lg transition-colors"
                    >
                        <X size={24} />
                    </button>
                </div>

                {/* Progress Steps */}
                <div className="p-6 border-b border-gray-200 bg-gray-50">
                    <div className="flex items-center justify-between">
                        {[1, 2, 3, 4, 5, 6].map((s) => (
                            <div key={s} className="flex items-center">
                                <div className={`w-8 h-8 rounded-full flex items-center justify-center text-sm font-semibold transition-colors ${
                                    step >= s ? 'bg-blue-600 text-white' : 'bg-gray-300 text-gray-600'
                                }`}>
                                    {s}
                                </div>
                                {s < 6 && (
                                    <div className={`w-12 h-1 mx-1 transition-colors ${
                                        step > s ? 'bg-blue-600' : 'bg-gray-300'
                                    }`} />
                                )}
                            </div>
                        ))}
                    </div>
                    <div className="mt-3 text-center text-sm font-medium text-gray-700">
                        {step === 1 && 'Storage Limits'}
                        {step === 2 && 'IOPS Limits'}
                        {step === 3 && 'Connection Limits'}
                        {step === 4 && 'CPU Limits'}
                        {step === 5 && 'Collection Cadence'}
                        {step === 6 && 'Review & Save'}
                    </div>
                </div>

                {/* Content */}
                <div className="flex-1 overflow-y-auto p-6">
                    {success ? (
                        <div className="text-center py-12">
                            <CheckCircle size={64} className="mx-auto text-green-500 mb-4" />
                            <h3 className="text-2xl font-bold text-gray-900 mb-2">Success!</h3>
                            <p className="text-gray-600">Resource limits configured successfully</p>
                        </div>
                    ) : (
                        <>
                            {/* Step 1: Storage */}
                            {step === 1 && (
                                <div className="space-y-6">
                                    <div className="flex items-start gap-3 p-4 bg-blue-50 border border-blue-200 rounded-lg">
                                        <Server className="text-blue-600 flex-shrink-0 mt-1" size={20} />
                                        <div className="text-sm text-blue-900">
                                            <p className="font-semibold mb-1">Storage Capacity Configuration</p>
                                            <p>Set your maximum storage capacity and alert thresholds. Sentinel will forecast when tables will exhaust storage.</p>
                                        </div>
                                    </div>

                                    <div>
                                        <label className="block text-sm font-medium text-gray-700 mb-2">
                                            Maximum Storage (GB)
                                        </label>
                                        <input
                                            type="number"
                                            value={formData.maxStorageGB}
                                            onChange={(e) => handleInputChange('maxStorageGB', parseInt(e.target.value))}
                                            className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                                            placeholder="e.g., 100"
                                        />
                                    </div>

                                    <div className="grid grid-cols-2 gap-4">
                                        <div>
                                            <label className="block text-sm font-medium text-gray-700 mb-2">
                                                Warning Threshold (%)
                                            </label>
                                            <input
                                                type="number"
                                                value={formData.storageWarningPercent}
                                                onChange={(e) => handleInputChange('storageWarningPercent', parseFloat(e.target.value))}
                                                className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                                                min="0"
                                                max="100"
                                            />
                                        </div>
                                        <div>
                                            <label className="block text-sm font-medium text-gray-700 mb-2">
                                                Critical Threshold (%)
                                            </label>
                                            <input
                                                type="number"
                                                value={formData.storageCriticalPercent}
                                                onChange={(e) => handleInputChange('storageCriticalPercent', parseFloat(e.target.value))}
                                                className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                                                min="0"
                                                max="100"
                                            />
                                        </div>
                                    </div>
                                </div>
                            )}

                            {/* Step 2: IOPS */}
                            {step === 2 && (
                                <div className="space-y-6">
                                    <div className="flex items-start gap-3 p-4 bg-purple-50 border border-purple-200 rounded-lg">
                                        <Zap className="text-purple-600 flex-shrink-0 mt-1" size={20} />
                                        <div className="text-sm text-purple-900">
                                            <p className="font-semibold mb-1">IOPS Capacity Configuration</p>
                                            <p>Configure I/O operations per second limits and alert thresholds for performance monitoring.</p>
                                        </div>
                                    </div>

                                    <div>
                                        <label className="block text-sm font-medium text-gray-700 mb-2">
                                            Maximum IOPS
                                        </label>
                                        <input
                                            type="number"
                                            value={formData.maxIops}
                                            onChange={(e) => handleInputChange('maxIops', parseInt(e.target.value))}
                                            className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                                            placeholder="e.g., 10000"
                                        />
                                    </div>

                                    <div className="grid grid-cols-2 gap-4">
                                        <div>
                                            <label className="block text-sm font-medium text-gray-700 mb-2">
                                                Warning Threshold (%)
                                            </label>
                                            <input
                                                type="number"
                                                value={formData.iopsWarningPercent}
                                                onChange={(e) => handleInputChange('iopsWarningPercent', parseFloat(e.target.value))}
                                                className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                                                min="0"
                                                max="100"
                                            />
                                        </div>
                                        <div>
                                            <label className="block text-sm font-medium text-gray-700 mb-2">
                                                Critical Threshold (%)
                                            </label>
                                            <input
                                                type="number"
                                                value={formData.iopsCriticalPercent}
                                                onChange={(e) => handleInputChange('iopsCriticalPercent', parseFloat(e.target.value))}
                                                className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                                                min="0"
                                                max="100"
                                            />
                                        </div>
                                    </div>
                                </div>
                            )}

                            {/* Step 3: Connections */}
                            {step === 3 && (
                                <div className="space-y-6">
                                    <div className="flex items-start gap-3 p-4 bg-green-50 border border-green-200 rounded-lg">
                                        <Users className="text-green-600 flex-shrink-0 mt-1" size={20} />
                                        <div className="text-sm text-green-900">
                                            <p className="font-semibold mb-1">Connection Limits Configuration</p>
                                            <p>Set maximum concurrent database connections and alert thresholds for connection pool monitoring.</p>
                                        </div>
                                    </div>

                                    <div>
                                        <label className="block text-sm font-medium text-gray-700 mb-2">
                                            Maximum Connections
                                        </label>
                                        <input
                                            type="number"
                                            value={formData.maxConnections}
                                            onChange={(e) => handleInputChange('maxConnections', parseInt(e.target.value))}
                                            className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                                            placeholder="e.g., 500"
                                        />
                                    </div>

                                    <div className="grid grid-cols-2 gap-4">
                                        <div>
                                            <label className="block text-sm font-medium text-gray-700 mb-2">
                                                Warning Threshold (%)
                                            </label>
                                            <input
                                                type="number"
                                                value={formData.connectionWarningPercent}
                                                onChange={(e) => handleInputChange('connectionWarningPercent', parseFloat(e.target.value))}
                                                className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                                                min="0"
                                                max="100"
                                            />
                                        </div>
                                        <div>
                                            <label className="block text-sm font-medium text-gray-700 mb-2">
                                                Critical Threshold (%)
                                            </label>
                                            <input
                                                type="number"
                                                value={formData.connectionCriticalPercent}
                                                onChange={(e) => handleInputChange('connectionCriticalPercent', parseFloat(e.target.value))}
                                                className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                                                min="0"
                                                max="100"
                                            />
                                        </div>
                                    </div>
                                </div>
                            )}

                            {/* Step 4: CPU */}
                            {step === 4 && (
                                <div className="space-y-6">
                                    <div className="flex items-start gap-3 p-4 bg-orange-50 border border-orange-200 rounded-lg">
                                        <Cpu className="text-orange-600 flex-shrink-0 mt-1" size={20} />
                                        <div className="text-sm text-orange-900">
                                            <p className="font-semibold mb-1">CPU Limits Configuration</p>
                                            <p>Configure CPU utilization thresholds for performance and workload drift monitoring.</p>
                                        </div>
                                    </div>

                                    <div>
                                        <label className="block text-sm font-medium text-gray-700 mb-2">
                                            Maximum CPU Utilization (%)
                                        </label>
                                        <input
                                            type="number"
                                            value={formData.maxCpuPercent}
                                            onChange={(e) => handleInputChange('maxCpuPercent', parseInt(e.target.value))}
                                            className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                                            min="0"
                                            max="100"
                                            placeholder="e.g., 80"
                                        />
                                    </div>

                                    <div className="grid grid-cols-2 gap-4">
                                        <div>
                                            <label className="block text-sm font-medium text-gray-700 mb-2">
                                                Warning Threshold (%)
                                            </label>
                                            <input
                                                type="number"
                                                value={formData.cpuWarningPercent}
                                                onChange={(e) => handleInputChange('cpuWarningPercent', parseFloat(e.target.value))}
                                                className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                                                min="0"
                                                max="100"
                                            />
                                        </div>
                                        <div>
                                            <label className="block text-sm font-medium text-gray-700 mb-2">
                                                Critical Threshold (%)
                                            </label>
                                            <input
                                                type="number"
                                                value={formData.cpuCriticalPercent}
                                                onChange={(e) => handleInputChange('cpuCriticalPercent', parseFloat(e.target.value))}
                                                className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                                                min="0"
                                                max="100"
                                            />
                                        </div>
                                    </div>
                                </div>
                            )}

                            {/* Step 5: Collection Cadence */}
                            {step === 5 && (
                                <div className="space-y-6">
                                    <div className="flex items-start gap-3 p-4 bg-indigo-50 border border-indigo-200 rounded-lg">
                                        <Clock className="text-indigo-600 flex-shrink-0 mt-1" size={20} />
                                        <div className="text-sm text-indigo-900">
                                            <p className="font-semibold mb-1">Data Collection Frequency</p>
                                            <p>Configure how often Sentinel captures database snapshots for analytics. More frequent snapshots provide better insights but increase database load.</p>
                                        </div>
                                    </div>

                                    <div>
                                        <label className="block text-sm font-medium text-gray-700 mb-3">
                                            Collection Frequency
                                        </label>
                                        <div className="space-y-3">
                                            <label className="flex items-start p-4 border-2 border-gray-300 rounded-lg cursor-pointer hover:border-blue-500 transition-colors">
                                                <input
                                                    type="radio"
                                                    name="cadence"
                                                    value="MINUTE"
                                                    checked={formData.collectionCadence === 'MINUTE'}
                                                    onChange={(e) => handleInputChange('collectionCadence', e.target.value)}
                                                    className="mt-1 mr-3"
                                                />
                                                <div className="flex-1">
                                                    <div className="font-semibold text-gray-900">Every Minute</div>
                                                    <div className="text-sm text-gray-600 mt-1">Captures snapshots every minute. Best for testing and development environments.</div>
                                                    <div className="text-xs text-orange-600 mt-1">⚠️ High database load - not recommended for production</div>
                                                </div>
                                            </label>

                                            <label className="flex items-start p-4 border-2 border-gray-300 rounded-lg cursor-pointer hover:border-blue-500 transition-colors">
                                                <input
                                                    type="radio"
                                                    name="cadence"
                                                    value="HOUR"
                                                    checked={formData.collectionCadence === 'HOUR'}
                                                    onChange={(e) => handleInputChange('collectionCadence', e.target.value)}
                                                    className="mt-1 mr-3"
                                                />
                                                <div className="flex-1">
                                                    <div className="font-semibold text-gray-900">Every Hour (Recommended)</div>
                                                    <div className="text-sm text-gray-600 mt-1">Captures snapshots hourly. Balanced approach for most production workloads.</div>
                                                    <div className="text-xs text-green-600 mt-1">✓ Recommended for production environments</div>
                                                </div>
                                            </label>

                                            <label className="flex items-start p-4 border-2 border-gray-300 rounded-lg cursor-pointer hover:border-blue-500 transition-colors">
                                                <input
                                                    type="radio"
                                                    name="cadence"
                                                    value="DAY"
                                                    checked={formData.collectionCadence === 'DAY'}
                                                    onChange={(e) => handleInputChange('collectionCadence', e.target.value)}
                                                    className="mt-1 mr-3"
                                                />
                                                <div className="flex-1">
                                                    <div className="font-semibold text-gray-900">Every Day</div>
                                                    <div className="text-sm text-gray-600 mt-1">Captures snapshots daily. Minimal database load but less granular insights.</div>
                                                    <div className="text-xs text-blue-600 mt-1">ℹ️ Good for long-term trend analysis</div>
                                                </div>
                                            </label>
                                        </div>
                                    </div>

                                    <div className="mt-6 p-4 bg-blue-50 border border-blue-200 rounded-lg">
                                        <label className="block text-sm font-medium text-gray-700 mb-2">
                                            Performance Insights Retention (days)
                                        </label>
                                        <input
                                            type="number"
                                            value={formData.performanceInsightsRetentionDays}
                                            onChange={(e) => handleInputChange('performanceInsightsRetentionDays', parseInt(e.target.value))}
                                            className="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-transparent"
                                            min="1"
                                            max="90"
                                            placeholder="e.g., 7"
                                        />
                                        <p className="text-xs text-gray-600 mt-2">
                                            How many days to keep Performance Insights snapshots (collected every 5 minutes).
                                            Common values: 3 days (minimal), 7 days (default), 14 days (extended), 30 days (AWS default).
                                        </p>
                                    </div>
                                </div>
                            )}

                            {/* Step 6: Review */}
                            {step === 6 && (
                                <div className="space-y-6">
                                    <div className="flex items-start gap-3 p-4 bg-blue-50 border border-blue-200 rounded-lg">
                                        <Info className="text-blue-600 flex-shrink-0 mt-1" size={20} />
                                        <div className="text-sm text-blue-900">
                                            <p className="font-semibold mb-1">Review Your Configuration</p>
                                            <p>Please review your resource limits before saving. These values will be used by Sentinel Analytics for capacity forecasting.</p>
                                        </div>
                                    </div>

                                    <div className="bg-gray-50 rounded-lg p-4 space-y-3">
                                        <div className="flex justify-between items-center py-2 border-b border-gray-200">
                                            <span className="font-medium text-gray-700">Storage</span>
                                            <span className="text-gray-900">{formData.maxStorageGB} GB (⚠️ {formData.storageWarningPercent}% / 🔴 {formData.storageCriticalPercent}%)</span>
                                        </div>
                                        <div className="flex justify-between items-center py-2 border-b border-gray-200">
                                            <span className="font-medium text-gray-700">IOPS</span>
                                            <span className="text-gray-900">{formData.maxIops.toLocaleString()} (⚠️ {formData.iopsWarningPercent}% / 🔴 {formData.iopsCriticalPercent}%)</span>
                                        </div>
                                        <div className="flex justify-between items-center py-2 border-b border-gray-200">
                                            <span className="font-medium text-gray-700">Connections</span>
                                            <span className="text-gray-900">{formData.maxConnections} (⚠️ {formData.connectionWarningPercent}% / 🔴 {formData.connectionCriticalPercent}%)</span>
                                        </div>
                                        <div className="flex justify-between items-center py-2 border-b border-gray-200">
                                            <span className="font-medium text-gray-700">CPU</span>
                                            <span className="text-gray-900">{formData.maxCpuPercent}% (⚠️ {formData.cpuWarningPercent}% / 🔴 {formData.cpuCriticalPercent}%)</span>
                                        </div>
                                        <div className="flex justify-between items-center py-2 border-b border-gray-200">
                                            <span className="font-medium text-gray-700">Collection Frequency</span>
                                            <span className="text-gray-900">
                                                {formData.collectionCadence === 'MINUTE' && 'Every Minute'}
                                                {formData.collectionCadence === 'HOUR' && 'Every Hour'}
                                                {formData.collectionCadence === 'DAY' && 'Every Day'}
                                            </span>
                                        </div>
                                        <div className="flex justify-between items-center py-2">
                                            <span className="font-medium text-gray-700">Performance Insights Retention</span>
                                            <span className="text-gray-900">{formData.performanceInsightsRetentionDays} days</span>
                                        </div>
                                    </div>

                                    {error && (
                                        <div className="flex items-start gap-2 p-4 bg-red-50 border border-red-200 rounded-lg">
                                            <AlertCircle className="text-red-600 flex-shrink-0 mt-0.5" size={18} />
                                            <p className="text-sm text-red-800">{error}</p>
                                        </div>
                                    )}
                                </div>
                            )}
                        </>
                    )}
                </div>

                {/* Footer */}
                {!success && (
                    <div className="p-6 border-t border-gray-200 bg-gray-50 flex justify-between">
                        <button
                            onClick={prevStep}
                            disabled={step === 1}
                            className="px-6 py-2 border border-gray-300 text-gray-700 rounded-lg hover:bg-gray-100 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
                        >
                            Previous
                        </button>

                        {step < 6 ? (
                            <button
                                onClick={nextStep}
                                className="px-6 py-2 bg-blue-600 text-white rounded-lg hover:bg-blue-700 transition-colors"
                            >
                                Next
                            </button>
                        ) : (
                            <button
                                onClick={handleSubmit}
                                disabled={loading}
                                className="px-6 py-2 bg-green-600 text-white rounded-lg hover:bg-green-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
                            >
                                {loading ? 'Saving...' : 'Save Configuration'}
                            </button>
                        )}
                    </div>
                )}
            </div>
        </div>
    )
}
