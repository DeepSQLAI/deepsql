import ReactMarkdown from 'react-markdown'
import remarkGfm from 'remark-gfm'
import DownloadableTable from './DownloadableTable'
import styles from './AgentChatPanel.module.css'

// GFM enables pipe tables, strikethrough, task lists, and autolinks. Without it,
// CommonMark collapses consecutive table rows into one paragraph (newlines → spaces),
// which is why "| a | | b |" appeared as a single unbroken line in chat.
const MARKDOWN_PLUGINS = [remarkGfm]
const MARKDOWN_COMPONENTS = {
  table: ({ children }) => <DownloadableTable>{children}</DownloadableTable>,
  a: ({ href, children }) => (
    <a href={href} target="_blank" rel="noopener noreferrer">{children}</a>
  ),
}

/** Renders assistant markdown with the same GFM + styles used in AgentChatPanel. */
export default function AgentMarkdown({ content }) {
  if (!content) return null
  return (
    <div className={styles.markdown}>
      <ReactMarkdown remarkPlugins={MARKDOWN_PLUGINS} components={MARKDOWN_COMPONENTS}>
        {content}
      </ReactMarkdown>
    </div>
  )
}
