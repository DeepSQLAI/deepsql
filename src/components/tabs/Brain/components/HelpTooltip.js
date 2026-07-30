import React, { useState, useRef, useEffect } from 'react';
import { createPortal } from 'react-dom';

/**
 * HelpTooltip - A clean tooltip that shows on hover over wrapped content.
 * No icon - just wrap any element and it shows a tooltip on hover.
 *
 * Usage:
 *   <HelpTooltip content={{ title: 'Term', description: 'Explanation' }}>
 *     <span className="badge">FACT</span>
 *   </HelpTooltip>
 */

const styles = {
  wrapper: {
    display: 'inline-flex',
    alignItems: 'center',
    cursor: 'help',
  },
  tooltip: {
    position: 'fixed',
    zIndex: 10000,
    maxWidth: '380px',
    minWidth: '180px',
    backgroundColor: 'var(--color-dark-1)',
    color: 'var(--color-light-1)',
    borderRadius: '6px',
    boxShadow: '0 10px 25px -5px rgba(0, 0, 0, 0.3), 0 8px 10px -6px rgba(0, 0, 0, 0.2)',
    fontSize: '12px',
    lineHeight: '1.5',
    padding: '10px 12px',
    maxHeight: '400px',
    overflowY: 'auto',
  },
  title: {
    fontWeight: '600',
    fontSize: '13px',
    color: 'var(--color-light-1)',
    marginBottom: '4px',
  },
  description: {
    color: 'var(--color-light-4)',
    margin: 0,
  },
  section: {
    marginTop: '8px',
    paddingTop: '8px',
    borderTop: '1px solid var(--color-light-8)',
  },
  sectionLabel: {
    fontSize: '10px',
    fontWeight: '600',
    color: 'var(--color-light-5)',
    textTransform: 'uppercase',
    letterSpacing: '0.5px',
    marginBottom: '2px',
  },
  sectionContent: {
    color: 'var(--color-light-4)',
    fontSize: '11px',
  },
  example: {
    backgroundColor: 'var(--color-light-8)',
    padding: '4px 6px',
    borderRadius: '3px',
    fontFamily: 'monospace',
    fontSize: '10px',
    color: 'var(--color-light-3)',
    marginTop: '4px',
  },
};

const HelpTooltip = ({ content, children, position = 'top', block = false }) => {
  const [isVisible, setIsVisible] = useState(false);
  const [tooltipPosition, setTooltipPosition] = useState({ top: 0, left: 0 });
  const [placement, setPlacement] = useState('bottom'); // 'top' or 'bottom'
  const wrapperRef = useRef(null);
  const tooltipRef = useRef(null);
  const showTimeoutRef = useRef(null);
  const hideTimeoutRef = useRef(null);

  // Check if this tooltip has scrollable content (details field)
  const isScrollable = content && content.details;

  // Don't render tooltip if no content
  if (!content || (!content.title && !content.description)) {
    return children;
  }

  const calculatePosition = () => {
    if (!wrapperRef.current) return;

    const rect = wrapperRef.current.getBoundingClientRect();
    // Use wider tooltip for content with details (metrics breakdown)
    const tooltipWidth = content.details ? 360 : 280;
    const gap = 12; // Gap between trigger and tooltip
    const viewportPadding = 10;

    let top, left;

    // Always position BELOW the element to avoid blocking clicks
    // This is more predictable and user-friendly
    top = rect.bottom + gap;
    left = rect.left + rect.width / 2 - tooltipWidth / 2;

    // If not enough space below, show above
    const spaceBelow = window.innerHeight - rect.bottom;
    const estimatedHeight = 150; // Conservative estimate

    if (spaceBelow < estimatedHeight + gap && rect.top > estimatedHeight + gap) {
      // Show above only if there's more room above
      top = rect.top - estimatedHeight - gap;
      setPlacement('top');
    } else {
      setPlacement('bottom');
    }

    // Keep within viewport horizontally
    left = Math.max(viewportPadding, Math.min(left, window.innerWidth - tooltipWidth - viewportPadding));

    setTooltipPosition({ top, left });
  };

  const showTooltip = () => {
    // Clear any pending hide timeout
    if (hideTimeoutRef.current) {
      clearTimeout(hideTimeoutRef.current);
      hideTimeoutRef.current = null;
    }
    showTimeoutRef.current = setTimeout(() => {
      setIsVisible(true);
      calculatePosition();
    }, 300);
  };

  const hideTooltip = () => {
    if (showTimeoutRef.current) {
      clearTimeout(showTimeoutRef.current);
      showTimeoutRef.current = null;
    }
    // For scrollable tooltips, add a small delay to allow moving to the tooltip
    if (isScrollable) {
      hideTimeoutRef.current = setTimeout(() => {
        setIsVisible(false);
      }, 150);
    } else {
      setIsVisible(false);
    }
  };

  const handleTooltipMouseEnter = () => {
    // Cancel any pending hide when entering the tooltip
    if (hideTimeoutRef.current) {
      clearTimeout(hideTimeoutRef.current);
      hideTimeoutRef.current = null;
    }
  };

  const handleTooltipMouseLeave = () => {
    setIsVisible(false);
  };

  useEffect(() => {
    return () => {
      if (showTimeoutRef.current) {
        clearTimeout(showTimeoutRef.current);
      }
      if (hideTimeoutRef.current) {
        clearTimeout(hideTimeoutRef.current);
      }
    };
  }, []);

  const renderTooltip = () => (
    <div
      ref={tooltipRef}
      style={{
        ...styles.tooltip,
        top: tooltipPosition.top,
        left: tooltipPosition.left,
        animation: placement === 'top' ? 'tooltipFadeInUp 0.15s ease-out' : 'tooltipFadeInDown 0.15s ease-out',
        // Allow pointer events for scrollable tooltips
        pointerEvents: isScrollable ? 'auto' : 'none',
      }}
      onMouseEnter={isScrollable ? handleTooltipMouseEnter : undefined}
      onMouseLeave={isScrollable ? handleTooltipMouseLeave : undefined}
    >
      {content.title && <div style={styles.title}>{content.title}</div>}
      {content.description && <p style={styles.description}>{content.description}</p>}

      {content.impact && (
        <div style={styles.section}>
          <div style={styles.sectionLabel}>Impact</div>
          <div style={styles.sectionContent}>{content.impact}</div>
        </div>
      )}

      {content.recommendation && (
        <div style={styles.section}>
          <div style={styles.sectionLabel}>Recommendation</div>
          <div style={styles.sectionContent}>{content.recommendation}</div>
        </div>
      )}

      {content.optimization && (
        <div style={styles.section}>
          <div style={styles.sectionLabel}>Optimization</div>
          <div style={styles.sectionContent}>{content.optimization}</div>
        </div>
      )}

      {content.examples && (
        <div style={styles.section}>
          <div style={styles.sectionLabel}>Examples</div>
          <div style={styles.example}>{content.examples}</div>
        </div>
      )}

      {/* Render details for metrics breakdown (MySQL/PostgreSQL) */}
      {content.details && (
        <div style={styles.section}>
          {Object.entries(content.details).map(([dbType, data]) => (
            <div key={dbType} style={{ marginBottom: '10px' }}>
              <div style={{ ...styles.sectionLabel, marginBottom: '6px', fontSize: '11px' }}>{data.title}</div>
              <div style={{ display: 'flex', flexDirection: 'column', gap: '4px' }}>
                {data.categories && data.categories.map((cat, idx) => (
                  <div key={idx} style={{ display: 'flex', gap: '6px', fontSize: '10px' }}>
                    <span style={{ color: 'var(--color-light-5)', minWidth: '80px', flexShrink: 0 }}>{cat.name}:</span>
                    <span style={{ color: 'var(--color-light-4)', fontFamily: 'monospace', fontSize: '9px' }}>{cat.metrics}</span>
                  </div>
                ))}
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  );

  return (
    <>
      <span
        ref={wrapperRef}
        style={{
          ...styles.wrapper,
          ...(block && { display: 'block', width: '100%' })
        }}
        onMouseEnter={showTooltip}
        onMouseLeave={hideTooltip}
      >
        {children}
      </span>
      {isVisible && typeof document !== 'undefined' && createPortal(renderTooltip(), document.body)}
    </>
  );
};

// Add keyframes style once
if (typeof document !== 'undefined' && !document.getElementById('tooltip-keyframes')) {
  const style = document.createElement('style');
  style.id = 'tooltip-keyframes';
  style.textContent = `
    @keyframes tooltipFadeInDown {
      from { opacity: 0; transform: translateY(-4px); }
      to { opacity: 1; transform: translateY(0); }
    }
    @keyframes tooltipFadeInUp {
      from { opacity: 0; transform: translateY(4px); }
      to { opacity: 1; transform: translateY(0); }
    }
  `;
  document.head.appendChild(style);
}

export { HelpTooltip };
export default HelpTooltip;
