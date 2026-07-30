import React, { useState } from 'react'
import { ThumbsUp, ThumbsDown, MessageSquare, X, Send, Loader, Bot } from 'lucide-react'
import { feedbackAPI } from '../lib/api/client'

/**
 * Feedback buttons component for AI responses.
 * Displays thumbs up/down buttons and optional correction/teaching form.
 */
export default function FeedbackButtons({
  connectionId,
  chatId,
  messageId,
  userMessage,
  aiResponse,
  sql,
  agentRunId,
  onBuildAgent,
  className = ''
}) {
  const [feedback, setFeedback] = useState(null) // 'up', 'down', or null
  const [showFeedbackForm, setShowFeedbackForm] = useState(false)
  const [feedbackText, setFeedbackText] = useState('')
  const [feedbackType, setFeedbackType] = useState('thumbs_down') // 'thumbs_down', 'correction', 'teaching'
  const [tableName, setTableName] = useState('')
  const [columnName, setColumnName] = useState('')
  const [submitting, setSubmitting] = useState(false)
  const [submitted, setSubmitted] = useState(false)

  const handleThumbsUp = async () => {
    if (feedback === 'up') return

    try {
      await feedbackAPI.thumbsUp({
        connectionId,
        chatId,
        messageId,
        userMessage,
        aiResponse,
        sql,
        agentRunId
      })
      setFeedback('up')
      setShowFeedbackForm(false)
    } catch (error) {
      console.error('Failed to record thumbs up:', error)
    }
  }

  const handleThumbsDown = () => {
    if (feedback === 'down') {
      setShowFeedbackForm(!showFeedbackForm)
      return
    }
    setFeedback('down')
    setShowFeedbackForm(true)
  }

  const handleSubmitFeedback = async () => {
    if (!feedbackText.trim()) return

    setSubmitting(true)
    try {
      if (feedbackType === 'correction') {
        await feedbackAPI.correction({
          connectionId,
          chatId,
          messageId,
          userMessage,
          aiResponse,
          sql,
          correction: feedbackText,
          tableName: tableName || null,
          columnName: columnName || null
        })
      } else if (feedbackType === 'teaching') {
        await feedbackAPI.teaching({
          connectionId,
          chatId,
          messageId,
          userMessage,
          teaching: feedbackText,
          tableName: tableName || null,
          columnName: columnName || null
        })
      } else {
        await feedbackAPI.thumbsDown({
          connectionId,
          chatId,
          messageId,
          userMessage,
          aiResponse,
          sql,
          feedbackText
        })
      }

      setSubmitted(true)
      setShowFeedbackForm(false)
      setFeedbackText('')
      setTableName('')
      setColumnName('')
    } catch (error) {
      console.error('Failed to submit feedback:', error)
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className={`mt-2 ${className}`}>
      {/* Feedback buttons row */}
      <div className="flex items-center gap-2">
        <button
          onClick={handleThumbsUp}
          disabled={feedback === 'up'}
          className={`p-1.5 rounded-md transition-all ${
            feedback === 'up'
              ? 'bg-green-100 text-green-600'
              : 'hover:bg-gray-200 text-gray-400 hover:text-gray-600'
          }`}
          title="Helpful response"
        >
          <ThumbsUp size={14} className={feedback === 'up' ? 'fill-current' : ''} />
        </button>

        <button
          onClick={handleThumbsDown}
          disabled={submitted}
          className={`p-1.5 rounded-md transition-all ${
            feedback === 'down'
              ? 'bg-red-100 text-red-600'
              : 'hover:bg-gray-200 text-gray-400 hover:text-gray-600'
          }`}
          title="Not helpful - provide feedback"
        >
          <ThumbsDown size={14} className={feedback === 'down' ? 'fill-current' : ''} />
        </button>

        {submitted && (
          <span className="text-xs text-green-600 ml-2">Thanks for your feedback!</span>
        )}

        {feedback === 'up' && onBuildAgent && (
          <button
            onClick={() => onBuildAgent({ userMessage, sql })}
            className="flex items-center gap-1 px-2 py-1 ml-1 text-xs text-gray-500 hover:text-gray-800 hover:bg-gray-100 rounded-md transition-colors"
            title="Create an autonomous agent from this query"
          >
            <Bot size={13} />
            <span>Build Agent</span>
          </button>
        )}
      </div>

      {/* Expanded feedback form */}
      {showFeedbackForm && (
        <div className="mt-3 p-3 bg-white rounded-lg border border-gray-200 shadow-sm">
          <div className="flex items-center justify-between mb-2">
            <span className="text-sm font-medium text-gray-700">Help improve responses</span>
            <button
              onClick={() => setShowFeedbackForm(false)}
              className="p-1 hover:bg-gray-100 rounded"
            >
              <X size={14} className="text-gray-400" />
            </button>
          </div>

          {/* Feedback type selector */}
          <div className="flex gap-2 mb-3">
            <button
              onClick={() => setFeedbackType('thumbs_down')}
              className={`px-2 py-1 text-xs rounded-full border transition-colors ${
                feedbackType === 'thumbs_down'
                  ? 'bg-gray-800 text-white border-gray-800'
                  : 'bg-white text-gray-600 border-gray-300 hover:border-gray-400'
              }`}
            >
              General feedback
            </button>
            <button
              onClick={() => setFeedbackType('correction')}
              className={`px-2 py-1 text-xs rounded-full border transition-colors ${
                feedbackType === 'correction'
                  ? 'bg-gray-800 text-white border-gray-800'
                  : 'bg-white text-gray-600 border-gray-300 hover:border-gray-400'
              }`}
            >
              Correction
            </button>
            <button
              onClick={() => setFeedbackType('teaching')}
              className={`px-2 py-1 text-xs rounded-full border transition-colors ${
                feedbackType === 'teaching'
                  ? 'bg-gray-800 text-white border-gray-800'
                  : 'bg-white text-gray-600 border-gray-300 hover:border-gray-400'
              }`}
            >
              Teach me
            </button>
          </div>

          {/* Table/Column inputs for correction and teaching */}
          {(feedbackType === 'correction' || feedbackType === 'teaching') && (
            <div className="flex gap-2 mb-2">
              <input
                type="text"
                value={tableName}
                onChange={(e) => setTableName(e.target.value)}
                placeholder="Table name (optional)"
                className="flex-1 px-2 py-1.5 text-xs border border-gray-200 rounded focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
              <input
                type="text"
                value={columnName}
                onChange={(e) => setColumnName(e.target.value)}
                placeholder="Column name (optional)"
                className="flex-1 px-2 py-1.5 text-xs border border-gray-200 rounded focus:outline-none focus:ring-1 focus:ring-blue-500"
              />
            </div>
          )}

          {/* Feedback text input */}
          <div className="flex gap-2">
            <textarea
              value={feedbackText}
              onChange={(e) => setFeedbackText(e.target.value)}
              placeholder={
                feedbackType === 'correction'
                  ? "e.g., For status column, use 'active' not 'enabled'"
                  : feedbackType === 'teaching'
                    ? "e.g., The bookings.status values are: confirmed, cancelled, pending"
                    : "What was wrong with this response?"
              }
              className="flex-1 px-3 py-2 text-sm border border-gray-200 rounded-lg focus:outline-none focus:ring-2 focus:ring-blue-500/50 resize-none"
              rows={2}
            />
            <button
              onClick={handleSubmitFeedback}
              disabled={!feedbackText.trim() || submitting}
              className="self-end px-3 py-2 bg-gray-800 text-white rounded-lg hover:bg-gray-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
            >
              {submitting ? (
                <Loader size={16} className="animate-spin" />
              ) : (
                <Send size={16} />
              )}
            </button>
          </div>

          {/* Hint text */}
          <p className="mt-2 text-xs text-gray-500">
            {feedbackType === 'correction'
              ? "Corrections help me remember the right values for next time."
              : feedbackType === 'teaching'
                ? "Teachings help me learn about your data (e.g., valid column values)."
                : "Your feedback helps improve future responses."
            }
          </p>
        </div>
      )}
    </div>
  )
}
