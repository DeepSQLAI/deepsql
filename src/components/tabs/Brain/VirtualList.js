'use client'

import { List } from 'react-window'

/**
 * Virtual scrolling list for large datasets
 * Renders only visible items for better performance
 */
export function VirtualList({
    items = [],
    renderItem,
    itemHeight = 60,
    height = 400,
    overscanCount = 5
}) {
    if (items.length === 0) {
        return null
    }

    const Row = ({ index, style }) => {
        const item = items[index]
        return (
            <div style={style}>
                {renderItem(item, index)}
            </div>
        )
    }

    return (
        <List
            height={height}
            itemCount={items.length}
            itemSize={itemHeight}
            width="100%"
            overscanCount={overscanCount}
        >
            {Row}
        </List>
    )
}
