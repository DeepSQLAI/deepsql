import { Check } from 'lucide-react'
import { DatabaseIcon, CloudProviderIcon, SSLModeIcon, ConnectivityIcon } from './DatabaseIcon'
import styles from '../ConnectionWizard.module.css'

/**
 * A selectable card component for wizard options
 * Uses custom SVG icons for better visual recognition
 */
export function SelectableCard({
  id,
  name,
  description,
  icon,
  selected,
  onClick,
  disabled,
  size = 'medium', // 'small', 'medium', 'large'
  iconType = 'auto', // 'database', 'cloud', 'ssl', 'connectivity', 'auto'
}) {
  // Determine which icon component to use
  const renderIcon = () => {
    const iconSize = size === 'large' ? 40 : size === 'small' ? 20 : 28

    // Auto-detect icon type based on the icon prop
    if (iconType === 'database' || ['postgres', 'mysql', 'postgresql'].includes(icon)) {
      return <DatabaseIcon type={icon} size={iconSize} />
    }
    if (iconType === 'cloud' || ['aws', 'azure', 'gcp', 'self-hosted', 'server'].includes(icon)) {
      return <CloudProviderIcon type={icon} size={iconSize} />
    }
    if (iconType === 'ssl' || ['none', 'server-only', 'server-client'].includes(id)) {
      return <SSLModeIcon mode={id} size={iconSize} />
    }
    if (iconType === 'connectivity' || ['direct', 'ssh-tunnel', 'globe', 'lock'].includes(icon)) {
      return <ConnectivityIcon type={icon} size={iconSize} />
    }

    // Fallback to database icon
    return <DatabaseIcon type={icon} size={iconSize} />
  }

  const handleClick = () => {
    if (!disabled && onClick) {
      onClick(id)
    }
  }

  const handleKeyDown = (e) => {
    if (e.key === 'Enter' || e.key === ' ') {
      e.preventDefault()
      handleClick()
    }
  }

  return (
    <button
      type="button"
      className={`${styles.selectableCard} ${selected ? styles.selected : ''} ${styles[size]} ${disabled ? styles.disabled : ''}`}
      onClick={handleClick}
      onKeyDown={handleKeyDown}
      disabled={disabled}
      role="option"
      aria-selected={selected}
    >
      <div className={styles.cardIcon}>
        {renderIcon()}
      </div>
      <div className={styles.cardContent}>
        <span className={styles.cardName}>{name}</span>
        {description && <span className={styles.cardDescription}>{description}</span>}
      </div>
      {selected && (
        <div className={styles.cardCheck}>
          <Check size={14} strokeWidth={3} />
        </div>
      )}
    </button>
  )
}

export default SelectableCard
