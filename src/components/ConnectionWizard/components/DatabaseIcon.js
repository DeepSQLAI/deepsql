import { Database, Cloud, Server, Globe, Lock, Shield, ShieldCheck } from 'lucide-react'

/**
 * Simple icon components using lucide-react
 * Clean, consistent icons without custom graphics
 */

// Database type icons - just use Database icon with different colors
export const DatabaseIcon = ({ type, size = 32 }) => {
  const color = type === 'postgres' ? '#336791' : type === 'mysql' ? '#00758F' : '#6B7280'
  return <Database size={size} color={color} strokeWidth={1.5} />
}

// Cloud provider icons - use Cloud icon with provider colors
export const CloudProviderIcon = ({ type, size = 24 }) => {
  switch (type) {
    case 'aws':
      return <Cloud size={size} color="#FF9900" strokeWidth={1.5} />
    case 'azure':
      return <Cloud size={size} color="#0078D4" strokeWidth={1.5} />
    case 'gcp':
      return <Cloud size={size} color="#4285F4" strokeWidth={1.5} />
    case 'self-hosted':
    case 'server':
      return <Server size={size} color="#374151" strokeWidth={1.5} />
    default:
      return <Server size={size} color="#6B7280" strokeWidth={1.5} />
  }
}

// SSL mode icons
export const SSLModeIcon = ({ mode, size = 24 }) => {
  switch (mode) {
    case 'none':
      return <Globe size={size} color="#9CA3AF" strokeWidth={1.5} />
    case 'server-only':
      return <Shield size={size} color="#3B82F6" strokeWidth={1.5} />
    case 'server-client':
      return <ShieldCheck size={size} color="#10B981" strokeWidth={1.5} />
    default:
      return <Globe size={size} color="#9CA3AF" strokeWidth={1.5} />
  }
}

// Connectivity icons
export const ConnectivityIcon = ({ type, size = 24 }) => {
  switch (type) {
    case 'direct':
    case 'globe':
      return <Globe size={size} color="#6B7280" strokeWidth={1.5} />
    case 'ssh-tunnel':
    case 'lock':
      return <Lock size={size} color="#F59E0B" strokeWidth={1.5} />
    default:
      return <Globe size={size} color="#6B7280" strokeWidth={1.5} />
  }
}

export default DatabaseIcon
