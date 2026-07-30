import { create } from 'zustand'
import { subscribeWithSelector } from 'zustand/middleware'

/**
 * Chat Store - Manages chat state per connection and tab
 *
 * Each tab maintains its own independent chat thread per connection.
 * Messages are persisted to localStorage for offline support.
 *
 * Structure: messages[connectionId][tab] = Message[]
 */

// Helper functions for localStorage persistence
const getChatKey = (connectionId, tab) => `chat-${connectionId}-${tab}`
const getChatIdKey = (connectionId, tab) => `chatId-${connectionId}-${tab}`

const loadMessages = (connectionId, tab) => {
  if (!connectionId || !tab || typeof window === 'undefined') return []
  try {
    const saved = localStorage.getItem(getChatKey(connectionId, tab))
    return saved ? JSON.parse(saved) : []
  } catch (error) {
    console.error('Failed to load chat messages:', error)
    return []
  }
}

const saveMessages = (connectionId, tab, messages) => {
  if (!connectionId || !tab || typeof window === 'undefined') return
  try {
    localStorage.setItem(getChatKey(connectionId, tab), JSON.stringify(messages))
  } catch (error) {
    console.error('Failed to save chat messages:', error)
  }
}

const loadChatId = (connectionId, tab) => {
  if (!connectionId || !tab || typeof window === 'undefined') return null
  return localStorage.getItem(getChatIdKey(connectionId, tab))
}

const saveChatId = (connectionId, tab, chatId) => {
  if (!connectionId || !tab || typeof window === 'undefined') return
  if (chatId) {
    localStorage.setItem(getChatIdKey(connectionId, tab), chatId)
  } else {
    localStorage.removeItem(getChatIdKey(connectionId, tab))
  }
}

export const useChatStore = create(
  subscribeWithSelector((set, get) => ({
    // State - nested structure: { [connectionId]: { [tab]: Message[] } }
    messages: {},
    chatIds: {},
    sendingTabs: new Set(), // Track which tabs are currently sending
    prompt: '',
    attachedImage: null,

    // Actions
    setPrompt: (prompt) => set({ prompt }),

    setAttachedImage: (image) => set({ attachedImage: image }),

    clearAttachedImage: () => set({ attachedImage: null }),

    // Load messages for a specific connection/tab
    loadChatForTab: (connectionId, tab) => {
      if (!connectionId || !tab) return

      const messages = loadMessages(connectionId, tab)
      const chatId = loadChatId(connectionId, tab)

      set((state) => ({
        messages: {
          ...state.messages,
          [connectionId]: {
            ...state.messages[connectionId],
            [tab]: messages,
          },
        },
        chatIds: {
          ...state.chatIds,
          [connectionId]: {
            ...state.chatIds[connectionId],
            [tab]: chatId,
          },
        },
      }))
    },

    // Get messages for current connection/tab
    getMessages: (connectionId, tab) => {
      const { messages } = get()
      return messages[connectionId]?.[tab] || []
    },

    // Get chat ID for current connection/tab
    getChatId: (connectionId, tab) => {
      const { chatIds } = get()
      return chatIds[connectionId]?.[tab] || null
    },

    // Add a message to a specific tab
    addMessage: (connectionId, tab, message) => {
      if (!connectionId || !tab) return

      set((state) => {
        const tabMessages = state.messages[connectionId]?.[tab] || []
        const newMessages = [...tabMessages, message]

        // Persist to localStorage
        saveMessages(connectionId, tab, newMessages)

        return {
          messages: {
            ...state.messages,
            [connectionId]: {
              ...state.messages[connectionId],
              [tab]: newMessages,
            },
          },
        }
      })
    },

    // Update the last message (for streaming responses)
    updateLastMessage: (connectionId, tab, updater) => {
      if (!connectionId || !tab) return

      set((state) => {
        const tabMessages = state.messages[connectionId]?.[tab] || []
        if (tabMessages.length === 0) return state

        const newMessages = [...tabMessages]
        const lastIndex = newMessages.length - 1
        newMessages[lastIndex] =
          typeof updater === 'function'
            ? updater(newMessages[lastIndex])
            : { ...newMessages[lastIndex], ...updater }

        // Persist to localStorage
        saveMessages(connectionId, tab, newMessages)

        return {
          messages: {
            ...state.messages,
            [connectionId]: {
              ...state.messages[connectionId],
              [tab]: newMessages,
            },
          },
        }
      })
    },

    // Set chat ID for a specific tab
    setChatId: (connectionId, tab, chatId) => {
      if (!connectionId || !tab) return

      // Persist to localStorage
      saveChatId(connectionId, tab, chatId)

      set((state) => ({
        chatIds: {
          ...state.chatIds,
          [connectionId]: {
            ...state.chatIds[connectionId],
            [tab]: chatId,
          },
        },
      }))
    },

    // Clear chat for a specific tab
    clearChat: (connectionId, tab) => {
      if (!connectionId || !tab) return

      // Clear localStorage
      if (typeof window !== 'undefined') {
        localStorage.removeItem(getChatKey(connectionId, tab))
        localStorage.removeItem(getChatIdKey(connectionId, tab))
      }

      set((state) => ({
        messages: {
          ...state.messages,
          [connectionId]: {
            ...state.messages[connectionId],
            [tab]: [],
          },
        },
        chatIds: {
          ...state.chatIds,
          [connectionId]: {
            ...state.chatIds[connectionId],
            [tab]: null,
          },
        },
      }))
    },

    // Clear all chats for a connection
    clearAllChats: (connectionId) => {
      if (!connectionId) return

      // Clear all chat-related localStorage keys for this connection
      if (typeof window !== 'undefined') {
        const keysToRemove = []
        for (let i = 0; i < localStorage.length; i++) {
          const key = localStorage.key(i)
          if (key?.startsWith(`chat-${connectionId}-`) || key?.startsWith(`chatId-${connectionId}-`)) {
            keysToRemove.push(key)
          }
        }
        keysToRemove.forEach((key) => localStorage.removeItem(key))
      }

      set((state) => {
        const { [connectionId]: _, ...restMessages } = state.messages
        const { [connectionId]: __, ...restChatIds } = state.chatIds
        return {
          messages: restMessages,
          chatIds: restChatIds,
        }
      })
    },

    // Track sending state per tab
    setSending: (connectionId, tab, isSending) => {
      set((state) => {
        const newSendingTabs = new Set(state.sendingTabs)
        const key = `${connectionId}-${tab}`
        if (isSending) {
          newSendingTabs.add(key)
        } else {
          newSendingTabs.delete(key)
        }
        return { sendingTabs: newSendingTabs }
      })
    },

    // Check if a tab is currently sending
    isSending: (connectionId, tab) => {
      const { sendingTabs } = get()
      return sendingTabs.has(`${connectionId}-${tab}`)
    },

    // Check if any tab is sending
    isAnySending: () => {
      const { sendingTabs } = get()
      return sendingTabs.size > 0
    },

    // Reset entire store — clears all chat data across all connections
    // Must be called on logout to prevent data leaking between accounts
    resetStore: () => {
      // Clear all chat-related localStorage keys
      if (typeof window !== 'undefined') {
        const keysToRemove = []
        for (let i = 0; i < localStorage.length; i++) {
          const key = localStorage.key(i)
          if (key?.startsWith('chat-') || key?.startsWith('chatId-')) {
            keysToRemove.push(key)
          }
        }
        keysToRemove.forEach((key) => localStorage.removeItem(key))
      }

      set({
        messages: {},
        chatIds: {},
        sendingTabs: new Set(),
        prompt: '',
        attachedImage: null,
      })
    },
  }))
)

// Selector hooks for optimized re-renders
export const usePrompt = () => useChatStore((state) => state.prompt)
export const useAttachedImage = () => useChatStore((state) => state.attachedImage)

// Hook to get messages for a specific connection/tab
export const useChatMessages = (connectionId, tab) => {
  return useChatStore((state) => state.messages[connectionId]?.[tab] || [])
}

// Hook to get chat ID for a specific connection/tab
export const useChatId = (connectionId, tab) => {
  return useChatStore((state) => state.chatIds[connectionId]?.[tab] || null)
}

// Hook to check if a specific tab is sending
export const useIsSending = (connectionId, tab) => {
  return useChatStore((state) => state.sendingTabs.has(`${connectionId}-${tab}`))
}

// Actions hook
export const useChatActions = () =>
  useChatStore((state) => ({
    setPrompt: state.setPrompt,
    setAttachedImage: state.setAttachedImage,
    clearAttachedImage: state.clearAttachedImage,
    loadChatForTab: state.loadChatForTab,
    getMessages: state.getMessages,
    getChatId: state.getChatId,
    addMessage: state.addMessage,
    updateLastMessage: state.updateLastMessage,
    setChatId: state.setChatId,
    clearChat: state.clearChat,
    clearAllChats: state.clearAllChats,
    resetStore: state.resetStore,
    setSending: state.setSending,
    isSending: state.isSending,
    isAnySending: state.isAnySending,
  }))
