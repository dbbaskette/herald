import { defineStore } from 'pinia'
import { ref } from 'vue'

export interface ChatMessage {
  id: number
  role: 'user' | 'assistant' | 'error'
  content: string
  timestamp: string
  streaming?: boolean
}

export const useChatStore = defineStore('chat', () => {
  const messages = ref<ChatMessage[]>([])
  const sending = ref(false)
  const conversationId = ref('web-console')
  let nextId = 1
  let currentStream: EventSource | null = null

  function addMessage(role: ChatMessage['role'], content: string, streaming = false): ChatMessage {
    const msg: ChatMessage = {
      id: nextId++,
      role,
      content,
      timestamp: new Date().toISOString(),
      streaming,
    }
    messages.value.push(msg)
    return msg
  }

  function appendToMessage(id: number, chunk: string) {
    const msg = messages.value.find((m) => m.id === id)
    if (msg) {
      msg.content += chunk
    }
  }

  function finalizeMessage(id: number) {
    const msg = messages.value.find((m) => m.id === id)
    if (msg) {
      msg.streaming = false
      if (!msg.content) {
        msg.content = '(empty response)'
      }
    }
  }

  function send(text: string): Promise<void> {
    if (!text.trim() || sending.value) return Promise.resolve()

    addMessage('user', text.trim())
    sending.value = true

    const assistantMsg = addMessage('assistant', '', true)

    const url = new URL('/api/chat/stream', window.location.origin)
    url.searchParams.set('message', text.trim())
    url.searchParams.set('conversationId', conversationId.value)

    return new Promise<void>((resolve) => {
      const es = new EventSource(url.toString())
      currentStream = es
      let gotContent = false

      const finish = () => {
        es.close()
        if (currentStream === es) currentStream = null
        finalizeMessage(assistantMsg.id)
        sending.value = false
        resolve()
      }

      es.addEventListener('chunk', (e: MessageEvent) => {
        gotContent = true
        appendToMessage(assistantMsg.id, e.data)
      })

      es.addEventListener('done', () => {
        finish()
      })

      es.addEventListener('error', (e: MessageEvent) => {
        // EventSource dispatches a plain Event on connection error (no data field).
        // Our server sends a named 'error' event carrying the message text.
        const serverMessage = e.data
        if (serverMessage) {
          // Replace the streaming assistant bubble with the error in-place.
          const idx = messages.value.findIndex((m) => m.id === assistantMsg.id)
          if (idx !== -1) messages.value.splice(idx, 1)
          addMessage('error', serverMessage)
          finish()
          return
        }
        // Connection-level error: only show one if we never received content.
        if (!gotContent) {
          const idx = messages.value.findIndex((m) => m.id === assistantMsg.id)
          if (idx !== -1) messages.value.splice(idx, 1)
          addMessage('error', 'Connection failed. Is herald-bot running?')
        }
        finish()
      })
    })
  }

  function cancel() {
    if (currentStream) {
      currentStream.close()
      currentStream = null
    }
    // Finalize any in-flight assistant message
    const last = messages.value[messages.value.length - 1]
    if (last && last.streaming) {
      finalizeMessage(last.id)
    }
    sending.value = false
  }

  function clearMessages() {
    cancel()
    messages.value = []
    nextId = 1
  }

  function newConversation() {
    conversationId.value = `web-${Date.now()}`
    clearMessages()
  }

  return { messages, sending, conversationId, send, cancel, clearMessages, newConversation }
})
