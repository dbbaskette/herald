import { defineStore } from 'pinia'
import { ref } from 'vue'

export interface ChatMessage {
  id: number
  role: 'user' | 'assistant' | 'error'
  content: string
  timestamp: string
}

const BOT_URL = 'http://localhost:8081'

export const useChatStore = defineStore('chat', () => {
  const messages = ref<ChatMessage[]>([])
  const sending = ref(false)
  const conversationId = ref('web-console')
  let nextId = 1

  function addMessage(role: ChatMessage['role'], content: string): ChatMessage {
    const msg: ChatMessage = {
      id: nextId++,
      role,
      content,
      timestamp: new Date().toISOString(),
    }
    messages.value.push(msg)
    return msg
  }

  async function send(text: string) {
    if (!text.trim() || sending.value) return

    addMessage('user', text.trim())
    sending.value = true

    try {
      const res = await fetch(`${BOT_URL}/api/chat`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({
          message: text.trim(),
          conversationId: conversationId.value,
        }),
      })

      if (!res.ok) {
        addMessage('error', `HTTP ${res.status}: ${res.statusText}`)
        return
      }

      const data = await res.json()
      if (data.error) {
        addMessage('error', data.error)
      } else {
        addMessage('assistant', data.reply || '(empty response)')
      }
    } catch (e: any) {
      addMessage('error', `Connection failed: ${e.message}. Is herald-bot running on port 8081?`)
    } finally {
      sending.value = false
    }
  }

  function clearMessages() {
    messages.value = []
    nextId = 1
  }

  function newConversation() {
    conversationId.value = `web-${Date.now()}`
    clearMessages()
  }

  return { messages, sending, conversationId, send, clearMessages, newConversation }
})
