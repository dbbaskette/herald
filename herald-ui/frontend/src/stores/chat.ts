import { defineStore } from 'pinia'
import { ref } from 'vue'

export interface ChatMessage {
  id: number
  role: 'user' | 'assistant' | 'error'
  content: string
  timestamp: string
  streaming?: boolean
  attachments?: AttachmentInfo[]
}

export interface AttachmentInfo {
  name: string
  size: number
  mimeType: string
}

export const useChatStore = defineStore('chat', () => {
  const messages = ref<ChatMessage[]>([])
  const sending = ref(false)
  const conversationId = ref('web-console')
  let nextId = 1
  let currentStream: EventSource | null = null
  let currentAbort: AbortController | null = null

  function addMessage(
    role: ChatMessage['role'],
    content: string,
    streaming = false,
    attachments?: AttachmentInfo[]
  ): ChatMessage {
    const msg: ChatMessage = {
      id: nextId++,
      role,
      content,
      timestamp: new Date().toISOString(),
      streaming,
      attachments,
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

  function send(text: string, files: File[] = []): Promise<void> {
    const trimmed = text.trim()
    if (!trimmed && files.length === 0) return Promise.resolve()
    if (sending.value) return Promise.resolve()

    const attachmentInfo: AttachmentInfo[] | undefined = files.length
      ? files.map((f) => ({ name: f.name, size: f.size, mimeType: f.type || 'application/octet-stream' }))
      : undefined

    addMessage('user', trimmed || '(file upload)', false, attachmentInfo)
    sending.value = true

    const assistantMsg = addMessage('assistant', '', true)

    if (files.length > 0) {
      return sendMultipart(trimmed, files, assistantMsg.id)
    }
    return sendEventSource(trimmed, assistantMsg.id)
  }

  function sendEventSource(text: string, assistantId: number): Promise<void> {
    const url = new URL('/api/chat/stream', window.location.origin)
    url.searchParams.set('message', text)
    url.searchParams.set('conversationId', conversationId.value)

    return new Promise<void>((resolve) => {
      const es = new EventSource(url.toString())
      currentStream = es
      let gotContent = false

      const finish = () => {
        es.close()
        if (currentStream === es) currentStream = null
        finalizeMessage(assistantId)
        sending.value = false
        resolve()
      }

      es.addEventListener('chunk', (e: MessageEvent) => {
        gotContent = true
        appendToMessage(assistantId, e.data)
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
          const idx = messages.value.findIndex((m) => m.id === assistantId)
          if (idx !== -1) messages.value.splice(idx, 1)
          addMessage('error', serverMessage)
          finish()
          return
        }
        // Connection-level error: only show one if we never received content.
        if (!gotContent) {
          const idx = messages.value.findIndex((m) => m.id === assistantId)
          if (idx !== -1) messages.value.splice(idx, 1)
          addMessage('error', 'Connection failed. Is herald-bot running?')
        }
        finish()
      })
    })
  }

  async function sendMultipart(text: string, files: File[], assistantId: number): Promise<void> {
    const form = new FormData()
    form.append('message', text)
    form.append('conversationId', conversationId.value)
    for (const file of files) {
      form.append('files', file, file.name)
    }

    const abort = new AbortController()
    currentAbort = abort
    let gotContent = false

    const fail = (msg: string) => {
      const idx = messages.value.findIndex((m) => m.id === assistantId)
      if (idx !== -1) messages.value.splice(idx, 1)
      addMessage('error', msg)
    }

    const finish = () => {
      finalizeMessage(assistantId)
      if (currentAbort === abort) currentAbort = null
      sending.value = false
    }

    try {
      const res = await fetch('/api/chat/stream-multipart', {
        method: 'POST',
        body: form,
        signal: abort.signal,
        headers: { Accept: 'text/event-stream' },
      })

      if (!res.ok || !res.body) {
        fail(`Upload failed: HTTP ${res.status}`)
        finish()
        return
      }

      const reader = res.body.getReader()
      const decoder = new TextDecoder()
      let buf = ''

      while (true) {
        const { value, done } = await reader.read()
        if (done) break
        buf += decoder.decode(value, { stream: true })

        // SSE events are separated by a blank line ("\n\n").
        let sep: number
        while ((sep = buf.indexOf('\n\n')) >= 0) {
          const rawEvent = buf.slice(0, sep)
          buf = buf.slice(sep + 2)
          const evt = parseSseEvent(rawEvent)
          if (!evt) continue
          if (evt.event === 'chunk') {
            gotContent = true
            appendToMessage(assistantId, evt.data)
          } else if (evt.event === 'done') {
            finish()
            return
          } else if (evt.event === 'error') {
            fail(evt.data || 'stream error')
            finish()
            return
          }
        }
      }
      // Stream closed without a 'done' event.
      if (!gotContent) {
        fail('Connection closed before any response.')
      }
      finish()
    } catch (e: any) {
      if (e?.name !== 'AbortError') {
        fail(`Connection failed: ${e?.message ?? e}. Is herald-bot running?`)
      }
      finish()
    }
  }

  function parseSseEvent(raw: string): { event: string; data: string } | null {
    if (!raw.trim()) return null
    let event = 'message'
    const dataLines: string[] = []
    for (const line of raw.split('\n')) {
      if (line.startsWith('event:')) event = line.slice(6).trim()
      else if (line.startsWith('data:')) dataLines.push(line.slice(5).trimStart())
    }
    return { event, data: dataLines.join('\n') }
  }

  function cancel() {
    if (currentStream) {
      currentStream.close()
      currentStream = null
    }
    if (currentAbort) {
      currentAbort.abort()
      currentAbort = null
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
