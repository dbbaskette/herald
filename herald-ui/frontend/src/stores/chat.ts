import { defineStore } from 'pinia'
import { reactive, ref } from 'vue'

export interface ChatMessage {
  id: number
  role: 'user' | 'assistant' | 'error'
  content: string
  timestamp: string
  streaming?: boolean
  attachments?: AttachmentInfo[]
  toolCalls?: ToolCall[]
}

export interface AttachmentInfo {
  name: string
  size: number
  mimeType: string
}

/**
 * Live tool-call observation for the chat UI (#362). One entry is pushed onto
 * the most recent streaming assistant message when a `tool_start` event arrives;
 * it transitions to `ok` or `error` when the matching `tool_end` arrives.
 */
export interface ToolCall {
  id: string
  name: string
  args: string
  status: 'running' | 'ok' | 'error'
  startedAt: number
  elapsedMs?: number
  summary?: string
}

export interface ProcessingState {
  active: boolean
  label: string
}

export const useChatStore = defineStore('chat', () => {
  const messages = ref<ChatMessage[]>([])
  const sending = ref(false)
  const conversationId = ref('web-console')
  // Tracks an in-flight background document-processing turn (PDF ingest, etc.).
  // Set when an upload-with-saved-file triggers the ack-and-run-async path,
  // cleared when a `message`/`error` notification arrives or a `progress`
  // event signals completion.
  const processing = reactive<ProcessingState>({ active: false, label: '' })
  let nextId = 1
  let currentStream: EventSource | null = null
  let currentAbort: AbortController | null = null
  let notificationsStream: EventSource | null = null

  // Open a long-lived SSE channel for async results (background document processing
  // — e.g. markitdown + wiki-ingest after a PDF upload). The bot pushes the final
  // assistant reply here when the in-flight turn completes.
  function ensureNotificationsConnected() {
    if (notificationsStream) return
    const url = new URL('/api/chat/notifications', window.location.origin)
    url.searchParams.set('conversationId', conversationId.value)
    const es = new EventSource(url.toString())
    notificationsStream = es

    es.addEventListener('message', (e: MessageEvent) => {
      // If a placeholder was created for tool cards during the background turn,
      // finalize that placeholder with the assistant's final text rather than
      // appending a new bubble.
      const last = messages.value[messages.value.length - 1]
      if (last && last.role === 'assistant' && (last.streaming || (last.toolCalls && last.toolCalls.length > 0)) && !last.content) {
        last.content = e.data
        last.streaming = false
      } else {
        addMessage('assistant', e.data)
      }
      processing.active = false
      processing.label = ''
    })
    es.addEventListener('error', (e: MessageEvent) => {
      // Server emitted a named error event; otherwise EventSource will reconnect.
      if (e.data) {
        addMessage('error', e.data)
        processing.active = false
        processing.label = ''
      }
    })
    // Optional progress event: backend may push stage updates ("Converting…",
    // "Ingesting…"). The data payload is treated as the new label; an empty
    // payload clears the indicator.
    es.addEventListener('progress', (e: MessageEvent) => {
      if (!e.data) {
        processing.active = false
        processing.label = ''
      } else {
        processing.active = true
        processing.label = e.data
      }
    })
    // Tool-call events forwarded from the agent during background turns (#362).
    // Latch onto whatever assistant placeholder is most recent; if there isn't
    // one yet (the ack message has been emitted but no assistant turn started),
    // create an in-progress placeholder so the cards have somewhere to attach.
    es.addEventListener('tool_start', (e: MessageEvent) => {
      ensureBackgroundPlaceholder()
      onToolStart(e.data)
    })
    es.addEventListener('tool_end', (e: MessageEvent) => onToolEnd(e.data))
    es.addEventListener('approval_required', (e: MessageEvent) => {
      if (!e.data) return
      // Lazy import avoids circular dependency at module load time.
      import('./approvals').then(({ useApprovalsStore }) => {
        useApprovalsStore().upsertFromSse(e.data)
      })
    })
  }

  function ensureBackgroundPlaceholder() {
    const last = messages.value[messages.value.length - 1]
    if (last && last.role === 'assistant' && (last.streaming || (last.toolCalls && last.toolCalls.length > 0))) {
      return
    }
    addMessage('assistant', '', true)
  }

  function reconnectNotifications() {
    if (notificationsStream) {
      notificationsStream.close()
      notificationsStream = null
    }
    ensureNotificationsConnected()
  }

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

  /**
   * Handle a `tool_start` SSE event (#362). Attaches a running ToolCall to the
   * most recent streaming assistant message; if none is streaming, finds the
   * latest assistant message instead.
   */
  function onToolStart(data: string) {
    try {
      const evt = JSON.parse(data)
      const target = findLiveAssistantMessage()
      if (!target) return
      if (!target.toolCalls) target.toolCalls = []
      target.toolCalls.push({
        id: evt.id,
        name: evt.name,
        args: evt.args ?? '',
        status: 'running',
        startedAt: Date.now(),
      })
    } catch { /* malformed event */ }
  }

  function onToolEnd(data: string) {
    try {
      const evt = JSON.parse(data)
      const target = findLiveAssistantMessage()
      if (!target?.toolCalls) return
      const tc = target.toolCalls.find((c) => c.id === evt.id)
      if (!tc) return
      tc.status = evt.ok ? 'ok' : 'error'
      tc.elapsedMs = evt.elapsedMs
      tc.summary = evt.summary
    } catch { /* malformed event */ }
  }

  function findLiveAssistantMessage(): ChatMessage | undefined {
    for (let i = messages.value.length - 1; i >= 0; i--) {
      const m = messages.value[i]
      if (m.role === 'assistant') return m
    }
    return undefined
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

      es.addEventListener('tool_start', (e: MessageEvent) => onToolStart(e.data))
      es.addEventListener('tool_end',   (e: MessageEvent) => onToolEnd(e.data))

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

    // Predict whether this upload will trigger background processing.
    // The bot acks immediately for non-image / non-audio files (PDFs, DOCX, etc.)
    // and runs the ingest pipeline async. We start the indicator now and the
    // notification channel will clear it on completion.
    const willTriggerBackground = files.some(
      (f) => !(f.type || '').startsWith('image/') && !(f.type || '').startsWith('audio/')
    )
    if (willTriggerBackground) {
      processing.active = true
      processing.label = files.length === 1
        ? `Processing ${files[0].name}…`
        : `Processing ${files.length} files…`
    }

    const abort = new AbortController()
    currentAbort = abort
    let gotContent = false

    const fail = (msg: string) => {
      const idx = messages.value.findIndex((m) => m.id === assistantId)
      if (idx !== -1) messages.value.splice(idx, 1)
      addMessage('error', msg)
      processing.active = false
      processing.label = ''
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
          } else if (evt.event === 'tool_start') {
            onToolStart(evt.data)
          } else if (evt.event === 'tool_end') {
            onToolEnd(evt.data)
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

  /**
   * Edit a previously-sent user message in place, then re-send the conversation
   * from that point. Truncates everything after the edited message and replays
   * with the new text. Matches the standard "Edit" behavior in ChatGPT / Claude.ai.
   */
  async function editUserMessage(id: number, newText: string): Promise<void> {
    if (sending.value) return
    const idx = messages.value.findIndex((m) => m.id === id && m.role === 'user')
    if (idx < 0) return
    const target = messages.value[idx]
    const attachmentInfo = target.attachments
    // Truncate everything from the edited message onward; we'll re-add the user
    // turn and rerun the agent. Attachments are dropped on edit (typing a new
    // question shouldn't silently re-upload bytes the user can't see).
    messages.value = messages.value.slice(0, idx)
    sending.value = true
    addMessage('user', newText, false, attachmentInfo)
    const assistantMsg = addMessage('assistant', '', true)
    return sendEventSource(newText, assistantMsg.id)
  }

  /**
   * Drop the last assistant message and re-run the agent from the prior user
   * turn. If the last message isn't an assistant message, this is a no-op.
   */
  async function retryLastAssistant(): Promise<void> {
    if (sending.value) return
    const lastIdx = messages.value.length - 1
    if (lastIdx < 0) return
    const last = messages.value[lastIdx]
    if (last.role !== 'assistant' && last.role !== 'error') return
    // Find the user turn that preceded this assistant message.
    let userIdx = lastIdx - 1
    while (userIdx >= 0 && messages.value[userIdx].role !== 'user') userIdx--
    if (userIdx < 0) return
    const prior = messages.value[userIdx]
    // Drop the previous assistant reply; keep the user turn.
    messages.value = messages.value.slice(0, lastIdx)
    sending.value = true
    const assistantMsg = addMessage('assistant', '', true)
    return sendEventSource(prior.content, assistantMsg.id)
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
    processing.active = false
    processing.label = ''
    nextId = 1
  }

  function newConversation() {
    conversationId.value = `web-${Date.now()}`
    clearMessages()
    reconnectNotifications()
  }

  /**
   * Switch the active conversation and load its persisted history from
   * SPRING_AI_CHAT_MEMORY via /api/conversations/{id}/messages. Used by the
   * conversation sidebar (#361).
   */
  async function switchConversation(id: string): Promise<void> {
    if (id === conversationId.value) return
    cancel()
    conversationId.value = id
    messages.value = []
    processing.active = false
    processing.label = ''
    nextId = 1
    try {
      const res = await fetch(`/api/conversations/${encodeURIComponent(id)}/messages`)
      if (res.ok) {
        const rows: Array<{ role: string; content: string; timestamp: string }> = await res.json()
        for (const r of rows) {
          const role = r.role === 'USER' ? 'user'
                     : r.role === 'ASSISTANT' ? 'assistant'
                     : r.role === 'SYSTEM' ? 'assistant' // surface system as assistant for now
                     : 'assistant'
          // The persisted content is a JSON blob (Spring AI's serialized form);
          // best-effort extract the visible text.
          let text = r.content
          const m = text.match(/"text"\s*:\s*"((?:[^"\\]|\\.)*)"/)
          if (m) {
            text = m[1].replace(/\\n/g, '\n').replace(/\\"/g, '"')
          }
          const msg: ChatMessage = {
            id: nextId++,
            role: role as ChatMessage['role'],
            content: text,
            timestamp: r.timestamp,
          }
          messages.value.push(msg)
        }
      }
    } catch {
      // History load is best-effort; an empty timeline is fine.
    }
    reconnectNotifications()
  }

  return {
    messages,
    sending,
    conversationId,
    processing,
    send,
    editUserMessage,
    retryLastAssistant,
    cancel,
    clearMessages,
    newConversation,
    switchConversation,
    ensureNotificationsConnected,
  }
})
