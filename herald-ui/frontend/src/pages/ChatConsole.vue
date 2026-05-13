<script setup lang="ts">
import { ref, nextTick, onMounted, onUnmounted, watch, computed } from 'vue'
import MarkdownIt from 'markdown-it'
import { useChatStore } from '@/stores/chat'
import { useConversationsStore } from '@/stores/conversations'
import ConversationList from '@/components/ConversationList.vue'
import ToolCallCard from '@/components/ToolCallCard.vue'

const store = useChatStore()
const conversations = useConversationsStore()
const showSidebar = ref(true)
const conversationListRef = ref<InstanceType<typeof ConversationList> | null>(null)
const input = ref('')
const chatBody = ref<HTMLElement | null>(null)
const inputEl = ref<HTMLTextAreaElement | null>(null)
const fileInputEl = ref<HTMLInputElement | null>(null)
const attachments = ref<File[]>([])
const dragActive = ref(false)
const attachmentError = ref('')

// Model badge state
const modelStatus = ref<{ provider: string; model: string; available: Record<string, string> } | null>(null)
const modelSwitching = ref(false)
const modelMenuOpen = ref(false)

// Per-message action state (#360)
const editingMessageId = ref<number | null>(null)
const editingText = ref('')
const copiedMessageId = ref<number | null>(null)

const editTextarea = ref<HTMLTextAreaElement | null>(null)

async function startEdit(msgId: number) {
  const msg = store.messages.find((m) => m.id === msgId)
  if (!msg || msg.role !== 'user') return
  editingMessageId.value = msgId
  editingText.value = msg.content
  await nextTick()
  editTextarea.value?.focus()
}

function cancelEdit() {
  editingMessageId.value = null
  editingText.value = ''
}

async function submitEdit() {
  if (editingMessageId.value == null) return
  const id = editingMessageId.value
  const text = editingText.value.trim()
  editingMessageId.value = null
  editingText.value = ''
  if (text) await store.editUserMessage(id, text)
}

function onEditKeydown(e: KeyboardEvent) {
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    submitEdit()
  } else if (e.key === 'Escape') {
    e.preventDefault()
    cancelEdit()
  }
}

async function copyMessage(msgId: number, content: string) {
  try {
    await navigator.clipboard.writeText(content)
    copiedMessageId.value = msgId
    setTimeout(() => {
      if (copiedMessageId.value === msgId) copiedMessageId.value = null
    }, 1400)
  } catch { /* clipboard unavailable */ }
}

async function retryFrom(msgId: number) {
  // Only retry the LAST assistant message via the simple store helper.
  // The icon is rendered on all assistant messages, but for any non-last one
  // we silently no-op (a fuller version would truncate-and-resend).
  const lastAssistantIdx = [...store.messages].reverse().findIndex(
    (m) => m.role === 'assistant' || m.role === 'error'
  )
  if (lastAssistantIdx === -1) return
  const realIdx = store.messages.length - 1 - lastAssistantIdx
  if (store.messages[realIdx].id !== msgId) return
  await store.retryLastAssistant()
}

const MAX_TOTAL_BYTES = 20 * 1024 * 1024 // 20 MB total cap
const TEXTAREA_MAX_HEIGHT = 200 // px

const totalBytes = computed(() =>
  attachments.value.reduce((sum, f) => sum + f.size, 0)
)

const overLimit = computed(() => totalBytes.value > MAX_TOTAL_BYTES)

const canSend = computed(
  () => (input.value.trim().length > 0 || attachments.value.length > 0) && !store.sending && !overLimit.value
)

// Configure markdown-it: enable links, lists, tables, etc. with safe HTML escaping.
const md = new MarkdownIt({
  html: false,         // never trust raw HTML in agent output
  linkify: true,       // auto-link bare URLs
  breaks: true,        // \n → <br> (chat-style)
  typographer: false,
})

// Open links in a new tab safely.
const defaultLinkOpen = md.renderer.rules.link_open || function (tokens: any, idx: number, options: any, _env: any, self: any) {
  return self.renderToken(tokens, idx, options)
}
md.renderer.rules.link_open = function (tokens, idx, options, env, self) {
  const token = tokens[idx]
  const aIndex = token.attrIndex('target')
  if (aIndex < 0) token.attrPush(['target', '_blank'])
  else token.attrs![aIndex][1] = '_blank'
  const rIndex = token.attrIndex('rel')
  if (rIndex < 0) token.attrPush(['rel', 'noopener noreferrer'])
  else token.attrs![rIndex][1] = 'noopener noreferrer'
  return defaultLinkOpen(tokens, idx, options, env, self)
}

function renderMarkdown(text: string): string {
  if (!text) return ''
  try {
    return md.render(text)
  } catch {
    // Fallback to escaped plain text if markdown-it throws on weird input.
    return text
      .replace(/&/g, '&amp;')
      .replace(/</g, '&lt;')
      .replace(/>/g, '&gt;')
      .replace(/\n/g, '<br>')
  }
}

function formatTime(ts: string): string {
  try {
    const d = new Date(ts)
    return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
  } catch { return '' }
}

// --- Background processing indicator ---
// When a multipart upload (saved files like PDFs) lands, the bot acks immediately
// and runs the ingest pipeline in the background. We show a persistent pill with
// elapsed time until a notification arrives clearing it.
const processingActive = computed(() => store.processing.active)
const processingLabel = computed(() => store.processing.label)
const processingElapsed = ref(0)
let processingTimer: ReturnType<typeof setInterval> | null = null

watch(processingActive, (active) => {
  if (active) {
    processingElapsed.value = 0
    if (processingTimer) clearInterval(processingTimer)
    processingTimer = setInterval(() => {
      processingElapsed.value += 1
    }, 1000)
  } else {
    if (processingTimer) {
      clearInterval(processingTimer)
      processingTimer = null
    }
  }
})

function formatElapsed(s: number): string {
  if (s < 60) return `${s}s`
  const m = Math.floor(s / 60)
  const sec = s % 60
  return `${m}m ${sec.toString().padStart(2, '0')}s`
}

// --- Model status (badge + quick-switch) ---
async function fetchModelStatus() {
  try {
    const res = await fetch('/api/model')
    if (res.ok) modelStatus.value = await res.json()
  } catch { /* bot offline */ }
}

const availableProviders = computed(() =>
  modelStatus.value ? Object.keys(modelStatus.value.available).filter(k => k !== 'error') : []
)

async function switchModel(provider: string) {
  if (!modelStatus.value) return
  if (provider === modelStatus.value.provider) {
    modelMenuOpen.value = false
    return
  }
  const targetModel = modelStatus.value.available[provider]
  if (!targetModel) return
  modelSwitching.value = true
  try {
    const res = await fetch('/api/model', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ provider, model: targetModel }),
    })
    if (res.ok) modelStatus.value = await res.json()
  } catch { /* ignore */ }
  finally {
    modelSwitching.value = false
    modelMenuOpen.value = false
  }
}

function toggleModelMenu() {
  modelMenuOpen.value = !modelMenuOpen.value
}

function onDocumentClick(e: MouseEvent) {
  if (!modelMenuOpen.value) return
  const target = e.target as HTMLElement
  if (!target.closest('.model-badge-wrap')) modelMenuOpen.value = false
}

// --- Send / attachment handling ---
async function handleSend() {
  if (!canSend.value) return
  const text = input.value
  const files = attachments.value
  input.value = ''
  attachments.value = []
  attachmentError.value = ''
  resizeTextarea()
  await store.send(text, files)
  inputEl.value?.focus()
}

function onInputKeydown(e: KeyboardEvent) {
  // Enter sends, Shift+Enter inserts newline (matches ChatGPT/Claude).
  if (e.key === 'Enter' && !e.shiftKey && !e.isComposing) {
    e.preventDefault()
    handleSend()
  }
}

function resizeTextarea() {
  const el = inputEl.value
  if (!el) return
  el.style.height = 'auto'
  el.style.height = Math.min(el.scrollHeight, TEXTAREA_MAX_HEIGHT) + 'px'
}

function openFilePicker() {
  fileInputEl.value?.click()
}

function onFileInputChange(event: Event) {
  const target = event.target as HTMLInputElement
  if (target.files && target.files.length > 0) {
    addFiles(Array.from(target.files))
  }
  target.value = ''
}

function addFiles(files: File[]) {
  attachmentError.value = ''
  const next = [...attachments.value, ...files]
  const total = next.reduce((s, f) => s + f.size, 0)
  if (total > MAX_TOTAL_BYTES) {
    attachmentError.value = `Total upload size exceeds ${formatBytes(MAX_TOTAL_BYTES)}.`
  }
  attachments.value = next
}

function removeAttachment(index: number) {
  attachments.value = attachments.value.filter((_, i) => i !== index)
  if (!overLimit.value) attachmentError.value = ''
}

function onDragOver(e: DragEvent) {
  if (!e.dataTransfer) return
  if (Array.from(e.dataTransfer.items).some((i) => i.kind === 'file')) {
    e.preventDefault()
    dragActive.value = true
  }
}

function onDragLeave(e: DragEvent) {
  if ((e.currentTarget as HTMLElement)?.contains(e.relatedTarget as Node)) return
  dragActive.value = false
}

function onDrop(e: DragEvent) {
  e.preventDefault()
  dragActive.value = false
  const dropped = Array.from(e.dataTransfer?.files ?? [])
  if (dropped.length > 0) addFiles(dropped)
}

async function scrollToBottom() {
  await nextTick()
  if (chatBody.value) {
    chatBody.value.scrollTop = chatBody.value.scrollHeight
  }
}

function formatBytes(bytes: number): string {
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / (1024 * 1024)).toFixed(1)} MB`
}

// Auto-grow textarea on input.
watch(input, () => {
  nextTick(() => resizeTextarea())
})

// Auto-scroll on new content.
watch(() => store.messages.length, scrollToBottom)
watch(() => store.sending, scrollToBottom)
watch(
  () => store.messages.map((m) => m.content.length).join(','),
  scrollToBottom
)

onMounted(() => {
  inputEl.value?.focus()
  store.ensureNotificationsConnected()
  fetchModelStatus()
  conversations.fetchAll()
  document.addEventListener('click', onDocumentClick)
})

// Refresh the sidebar after each completed send so new conversations appear
// and turn counts stay current.
watch(() => store.sending, (sending, wasSending) => {
  if (wasSending && !sending) {
    conversationListRef.value?.refresh()
  }
})

onUnmounted(() => {
  if (processingTimer) clearInterval(processingTimer)
  document.removeEventListener('click', onDocumentClick)
})
</script>

<template>
  <div
    class="chat-page"
    :class="{ 'drag-active': dragActive }"
    @dragover="onDragOver"
    @dragleave="onDragLeave"
    @drop="onDrop"
  >
    <header class="chat-header">
      <div class="header-left">
        <button
          class="sidebar-toggle"
          :class="{ active: showSidebar }"
          :title="showSidebar ? 'Hide conversation list' : 'Show conversation list'"
          @click="showSidebar = !showSidebar"
        >
          <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5">
            <rect x="2" y="3" width="12" height="10" rx="1.5"/>
            <line x1="6" y1="3" x2="6" y2="13"/>
          </svg>
        </button>
        <h1 class="page-title">Chat</h1>
        <span class="conv-id">{{ store.conversationId }}</span>
        <!-- Model badge with quick-switch dropdown -->
        <div v-if="modelStatus" class="model-badge-wrap">
          <button
            class="model-badge"
            :class="{ 'is-open': modelMenuOpen, 'is-switching': modelSwitching }"
            :title="`${modelStatus.provider}/${modelStatus.model} — click to switch`"
            @click.stop="toggleModelMenu"
          >
            <span class="model-dot"></span>
            <span class="model-text">{{ modelStatus.provider }}/{{ modelStatus.model }}</span>
            <svg class="model-chevron" viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.5">
              <path d="M3 5l3 3 3-3"/>
            </svg>
          </button>
          <div v-if="modelMenuOpen" class="model-menu">
            <div class="model-menu-header">Switch provider</div>
            <button
              v-for="p in availableProviders"
              :key="p"
              class="model-menu-item"
              :class="{ 'is-active': p === modelStatus.provider }"
              @click="switchModel(p)"
            >
              <span class="model-menu-radio">
                <span v-if="p === modelStatus.provider" class="model-menu-radio-fill"></span>
              </span>
              <span class="model-menu-name">{{ p }}</span>
              <span class="model-menu-model">{{ modelStatus.available[p] }}</span>
            </button>
          </div>
        </div>
      </div>
      <div class="header-right">
        <button class="header-btn" @click="store.newConversation()" title="New conversation">
          <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5"><line x1="8" y1="3" x2="8" y2="13"/><line x1="3" y1="8" x2="13" y2="8"/></svg>
          New Chat
        </button>
        <button
          class="header-btn header-btn-subtle"
          @click="store.clearMessages()"
          :disabled="store.messages.length === 0"
          title="Clear messages"
        >Clear</button>
      </div>
    </header>

    <div class="chat-main">
      <ConversationList v-if="showSidebar" ref="conversationListRef" class="chat-side" />
      <div class="chat-stage">
    <div ref="chatBody" class="chat-body">
      <!-- Empty state -->
      <div v-if="store.messages.length === 0 && !store.sending" class="chat-empty">
        <div class="empty-icon">
          <svg viewBox="0 0 80 80" fill="none" xmlns="http://www.w3.org/2000/svg">
            <rect x="12" y="16" width="56" height="40" rx="6" stroke="currentColor" stroke-width="1.5" opacity="0.2"/>
            <path d="M24 60l8-8h24" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" opacity="0.2"/>
            <path d="M28 32h24M28 40h16" stroke="currentColor" stroke-width="1.5" stroke-linecap="round" opacity="0.15"/>
          </svg>
        </div>
        <p class="empty-text">Send a message to start chatting with Herald</p>
        <p class="empty-hint">Same agent as Telegram — tools, memory, and skills are all available. Drop a file to attach it.</p>
      </div>

      <!-- Messages -->
      <div v-else class="messages-list">
        <div
          v-for="msg in store.messages"
          :key="msg.id"
          class="message"
          :class="'message-' + msg.role"
        >
          <div class="message-avatar">
            <span v-if="msg.role === 'user'">You</span>
            <span v-else-if="msg.role === 'assistant'">H</span>
            <span v-else>!</span>
          </div>
          <div class="message-content">
            <!-- Attachment pills above the user bubble -->
            <div v-if="msg.attachments && msg.attachments.length" class="message-attachments">
              <span
                v-for="(att, idx) in msg.attachments"
                :key="idx"
                class="message-attachment-pill"
                :title="att.mimeType"
              >
                <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5">
                  <path d="M11 7l-4 4a2 2 0 0 1-3-3l5-5a3 3 0 0 1 4 4l-5 5"/>
                </svg>
                {{ att.name }} <span class="size">({{ formatBytes(att.size) }})</span>
              </span>
            </div>
            <!-- Tool-call cards (#362) — rendered above the assistant bubble. -->
            <div v-if="msg.role === 'assistant' && msg.toolCalls && msg.toolCalls.length > 0" class="tool-calls-block">
              <ToolCallCard v-for="tc in msg.toolCalls" :key="tc.id" :call="tc" />
            </div>
            <!-- Streaming bubble with no content yet: show dots inline -->
            <div v-if="msg.streaming && !msg.content && !(msg.toolCalls && msg.toolCalls.length > 0)" class="typing-indicator">
              <span class="typing-dot"></span>
              <span class="typing-dot"></span>
              <span class="typing-dot"></span>
            </div>
            <!-- Inline edit mode for user messages (#360) -->
            <div v-else-if="editingMessageId === msg.id" class="message-edit">
              <textarea
                ref="editTextarea"
                v-model="editingText"
                class="message-edit-input"
                rows="2"
                @keydown="onEditKeydown"
              ></textarea>
              <div class="message-edit-actions">
                <button class="message-edit-btn" @click="cancelEdit">Cancel</button>
                <button class="message-edit-btn primary" @click="submitEdit">Send</button>
              </div>
            </div>
            <div
              v-else
              class="message-text"
              :class="{ 'message-streaming': msg.streaming }"
              v-html="renderMarkdown(msg.content)"
            ></div>
            <span class="message-time">{{ formatTime(msg.timestamp) }}</span>
            <!-- Per-message actions (#360) -->
            <div
              v-if="!msg.streaming && editingMessageId !== msg.id && msg.role !== 'error'"
              class="message-actions"
            >
              <button
                class="msg-action"
                :title="copiedMessageId === msg.id ? 'Copied!' : 'Copy markdown'"
                @click="copyMessage(msg.id, msg.content)"
              >
                <svg v-if="copiedMessageId !== msg.id" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5">
                  <rect x="4" y="4" width="9" height="10" rx="1.5"/>
                  <path d="M11 4V2.5A1.5 1.5 0 0 0 9.5 1h-5A1.5 1.5 0 0 0 3 2.5v8A1.5 1.5 0 0 0 4.5 12"/>
                </svg>
                <svg v-else viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="2">
                  <path d="M3 8l3 3 7-7"/>
                </svg>
              </button>
              <button
                v-if="msg.role === 'user'"
                class="msg-action"
                title="Edit and resend"
                @click="startEdit(msg.id)"
              >
                <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5">
                  <path d="M11.5 2.5l2 2L5 13H3v-2l8.5-8.5z"/>
                </svg>
              </button>
              <button
                v-if="msg.role === 'assistant'"
                class="msg-action"
                title="Retry"
                @click="retryFrom(msg.id)"
              >
                <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5">
                  <path d="M3 8a5 5 0 1 0 1.5-3.5"/>
                  <path d="M3 3v3h3"/>
                </svg>
              </button>
            </div>
          </div>
        </div>
      </div>

      <!-- Drag-drop overlay -->
      <div v-if="dragActive" class="drop-overlay">
        <div class="drop-message">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.5">
            <path d="M14 3v4a1 1 0 0 0 1 1h4M5 12V5a2 2 0 0 1 2-2h7l5 5v6M9 14l3 3 3-3M12 17v-6"/>
          </svg>
          <span>Drop files to attach</span>
        </div>
      </div>
    </div>

    <!-- Background processing indicator -->
    <div v-if="processingActive" class="processing-bar">
      <div class="processing-spinner"></div>
      <div class="processing-content">
        <div class="processing-label">{{ processingLabel || 'Processing in background…' }}</div>
        <div class="processing-meta">
          <span class="processing-elapsed">{{ formatElapsed(processingElapsed) }}</span>
          <span class="processing-stages">Convert → Ingest → Summarize</span>
        </div>
      </div>
    </div>

    <!-- Pending attachment pills + error banner -->
    <div v-if="attachments.length > 0 || attachmentError" class="pending-row">
      <div v-if="attachmentError" class="pending-error">{{ attachmentError }}</div>
      <div v-if="attachments.length > 0" class="pending-pills">
        <span
          v-for="(file, idx) in attachments"
          :key="idx"
          class="pending-pill"
        >
          <span class="pending-name">{{ file.name }}</span>
          <span class="pending-size">{{ formatBytes(file.size) }}</span>
          <button class="pending-remove" @click="removeAttachment(idx)" title="Remove">
            <svg viewBox="0 0 12 12" fill="none" stroke="currentColor" stroke-width="1.5">
              <path d="M3 3l6 6M9 3l-6 6"/>
            </svg>
          </button>
        </span>
      </div>
    </div>

    <!-- Input bar -->
    <div class="chat-input-bar">
      <div class="input-wrap">
        <input
          ref="fileInputEl"
          type="file"
          multiple
          class="file-input-hidden"
          @change="onFileInputChange"
        />
        <button
          class="attach-btn"
          :disabled="store.sending"
          @click="openFilePicker"
          title="Attach files"
        >
          <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5">
            <path d="M11 7.5l-4.25 4.25a2.5 2.5 0 0 1-3.54-3.54L8.5 3a3.5 3.5 0 0 1 4.95 4.95l-5.66 5.66"/>
          </svg>
        </button>
        <textarea
          ref="inputEl"
          v-model="input"
          rows="1"
          placeholder="Send a message…  (Shift+Enter for newline)"
          class="chat-input"
          :disabled="store.sending"
          @keydown="onInputKeydown"
        ></textarea>
        <button
          class="send-btn"
          :disabled="!canSend"
          @click="handleSend()"
          :title="canSend ? 'Send (Enter)' : ''"
        >
          <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5">
            <path d="M2 8h12M10 4l4 4-4 4"/>
          </svg>
        </button>
      </div>
    </div>
      </div><!-- /chat-stage -->
    </div><!-- /chat-main -->
  </div>
</template>

<style scoped>
@import url('https://fonts.googleapis.com/css2?family=DM+Sans:opsz,wght@9..40,300;9..40,400;9..40,500;9..40,600&family=JetBrains+Mono:wght@400&display=swap');

.chat-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  font-family: 'DM Sans', system-ui, sans-serif;
  position: relative;
}

/* Sidebar layout (#361) */
.chat-main {
  flex: 1;
  display: flex;
  gap: 14px;
  min-height: 0;
}

.chat-stage {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
}

.chat-side {
  /* sized inside the component */
}

.sidebar-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 28px;
  height: 28px;
  padding: 0;
  margin-right: 4px;
  border: 1px solid var(--color-border);
  background: var(--color-surface-raised);
  color: var(--color-text-secondary);
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.15s;
}

.sidebar-toggle:hover {
  background: var(--color-surface);
  color: var(--color-text-primary);
}

.sidebar-toggle.active {
  background: rgba(200, 165, 90, 0.10);
  border-color: rgba(200, 165, 90, 0.45);
  color: var(--color-brand-dim);
}

.sidebar-toggle svg {
  width: 14px;
  height: 14px;
}

/* Header */
.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 12px;
  gap: 12px;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.page-title {
  font-size: 1.5rem;
  font-weight: 600;
  color: var(--color-text-primary);
  letter-spacing: -0.02em;
}

.conv-id {
  font-size: 0.7rem;
  font-family: 'JetBrains Mono', monospace;
  color: var(--color-text-muted);
}

/* Model badge */
.model-badge-wrap {
  position: relative;
}

.model-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 8px 4px 10px;
  border-radius: 999px;
  border: 1px solid var(--color-border);
  background: var(--color-surface-raised);
  color: var(--color-text-secondary);
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.7rem;
  cursor: pointer;
  transition: all 0.15s;
  max-width: 280px;
}

.model-badge:hover, .model-badge.is-open {
  border-color: var(--color-brand);
  color: var(--color-text-primary);
  background: var(--color-surface);
}

.model-badge.is-switching {
  opacity: 0.6;
  cursor: wait;
}

.model-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #4ade80;
  box-shadow: 0 0 6px rgba(74, 222, 128, 0.4);
  flex-shrink: 0;
}

.model-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.model-chevron {
  width: 10px;
  height: 10px;
  flex-shrink: 0;
  transition: transform 0.15s;
}

.model-badge.is-open .model-chevron {
  transform: rotate(180deg);
}

.model-menu {
  position: absolute;
  top: calc(100% + 4px);
  left: 0;
  z-index: 20;
  min-width: 280px;
  background: var(--color-surface-raised);
  border: 1px solid var(--color-border);
  border-radius: 8px;
  box-shadow: 0 4px 16px rgba(0, 0, 0, 0.08);
  padding: 6px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.model-menu-header {
  font-size: 0.65rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--color-text-muted);
  padding: 6px 10px 4px;
}

.model-menu-item {
  display: grid;
  grid-template-columns: 14px 1fr auto;
  align-items: center;
  gap: 8px;
  padding: 8px 10px;
  border: none;
  background: transparent;
  text-align: left;
  border-radius: 6px;
  cursor: pointer;
  font-family: 'DM Sans', sans-serif;
  font-size: 0.8rem;
  color: var(--color-text-primary);
  transition: background 0.1s;
}

.model-menu-item:hover {
  background: var(--color-surface);
}

.model-menu-radio {
  width: 12px;
  height: 12px;
  border: 1.5px solid var(--color-border);
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
}

.model-menu-item.is-active .model-menu-radio {
  border-color: var(--color-brand);
}

.model-menu-radio-fill {
  width: 6px;
  height: 6px;
  background: var(--color-brand);
  border-radius: 50%;
}

.model-menu-name {
  font-weight: 500;
  text-transform: capitalize;
}

.model-menu-model {
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.7rem;
  color: var(--color-text-muted);
}

.header-right {
  display: flex;
  gap: 6px;
}

.header-btn {
  display: inline-flex;
  align-items: center;
  gap: 5px;
  padding: 5px 12px;
  border-radius: 6px;
  border: 1px solid var(--color-border);
  background: var(--color-surface-raised);
  font-family: 'DM Sans', sans-serif;
  font-size: 0.8rem;
  font-weight: 500;
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all 0.15s;
}

.header-btn svg {
  width: 14px;
  height: 14px;
}

.header-btn:hover:not(:disabled) {
  background: var(--color-surface);
  border-color: var(--color-text-muted);
  color: var(--color-text-primary);
}

.header-btn-subtle {
  border-color: transparent;
  background: transparent;
  color: var(--color-text-muted);
}

.header-btn-subtle:hover:not(:disabled) {
  color: var(--color-text-secondary);
  background: var(--color-border-light);
}

.header-btn:disabled {
  opacity: 0.3;
  cursor: not-allowed;
}

/* Chat body */
.chat-body {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  border: 1px solid var(--color-border);
  border-radius: 10px;
  background: var(--color-surface);
  position: relative;
}

.chat-empty {
  height: 100%;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
}

.empty-icon {
  width: 80px;
  height: 80px;
  color: #c0b8a8;
}

.empty-text {
  font-size: 0.875rem;
  color: var(--color-text-secondary);
  font-weight: 500;
}

.empty-hint {
  font-size: 0.75rem;
  color: var(--color-text-muted);
}

/* Messages */
.messages-list {
  padding: 16px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.message {
  display: flex;
  gap: 10px;
  max-width: 85%;
}

.message-user {
  align-self: flex-end;
  flex-direction: row-reverse;
}

.message-assistant {
  align-self: flex-start;
}

.message-error {
  align-self: flex-start;
}

.message-avatar {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.65rem;
  font-weight: 600;
  flex-shrink: 0;
  text-transform: uppercase;
}

.message-user .message-avatar {
  background: var(--color-brand);
  color: #fff;
}

.message-assistant .message-avatar {
  background: var(--color-sidebar);
  color: var(--color-brand-light);
}

.message-error .message-avatar {
  background: #fee2e2;
  color: #dc2626;
}

.message-content {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.message-attachments {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

.message-user .message-attachments {
  justify-content: flex-end;
}

.message-attachment-pill {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  padding: 3px 8px;
  border-radius: 999px;
  background: rgba(200, 165, 90, 0.12);
  border: 1px solid rgba(200, 165, 90, 0.35);
  font-size: 0.7rem;
  color: var(--color-brand-dim);
  font-weight: 500;
}

.message-attachment-pill svg {
  width: 11px;
  height: 11px;
}

.message-attachment-pill .size {
  color: var(--color-text-muted);
  font-weight: 400;
}

.message-text {
  padding: 10px 14px;
  border-radius: 12px;
  font-size: 0.875rem;
  line-height: 1.6;
  word-break: break-word;
}

.message-user .message-text {
  background: var(--color-brand);
  color: #fff;
  border-bottom-right-radius: 4px;
}

.message-assistant .message-text {
  background: var(--color-surface-raised);
  color: var(--color-text-primary);
  border: 1px solid var(--color-border);
  border-bottom-left-radius: 4px;
}

.message-streaming::after {
  content: '';
  display: inline-block;
  width: 2px;
  height: 1em;
  margin-left: 2px;
  background: var(--color-text-primary);
  vertical-align: text-bottom;
  animation: streaming-cursor 1s steps(2) infinite;
}

@keyframes streaming-cursor {
  0%, 100% { opacity: 1; }
  50% { opacity: 0; }
}

.message-error .message-text {
  background: #fef2f2;
  color: #b91c1c;
  border: 1px solid #fecaca;
  border-bottom-left-radius: 4px;
  font-size: 0.8rem;
}

/* Markdown content rendered inside .message-text */
.message-text :deep(p) {
  margin: 0;
}
.message-text :deep(p + p),
.message-text :deep(ul + p),
.message-text :deep(ol + p),
.message-text :deep(blockquote + p),
.message-text :deep(pre + p) {
  margin-top: 8px;
}
.message-text :deep(h1),
.message-text :deep(h2),
.message-text :deep(h3),
.message-text :deep(h4) {
  margin: 12px 0 6px;
  font-weight: 600;
  line-height: 1.3;
}
.message-text :deep(h1) { font-size: 1.1rem; }
.message-text :deep(h2) { font-size: 1.0rem; }
.message-text :deep(h3) { font-size: 0.95rem; }
.message-text :deep(h4) { font-size: 0.9rem; }
.message-text :deep(ul),
.message-text :deep(ol) {
  margin: 6px 0;
  padding-left: 22px;
}
.message-text :deep(li) {
  margin: 2px 0;
}
.message-text :deep(li > p) {
  margin: 0;
}
.message-text :deep(blockquote) {
  margin: 8px 0;
  padding: 4px 12px;
  border-left: 3px solid var(--color-border);
  color: var(--color-text-secondary);
}
.message-text :deep(hr) {
  margin: 12px 0;
  border: none;
  border-top: 1px solid var(--color-border);
}
.message-text :deep(table) {
  border-collapse: collapse;
  margin: 8px 0;
  font-size: 0.82rem;
}
.message-text :deep(th),
.message-text :deep(td) {
  border: 1px solid var(--color-border);
  padding: 4px 8px;
  text-align: left;
}
.message-text :deep(th) {
  background: var(--color-surface);
  font-weight: 600;
}
.message-text :deep(a) {
  color: var(--color-brand-dim);
  text-decoration: underline;
  text-decoration-color: rgba(166, 138, 62, 0.4);
}
.message-text :deep(a:hover) {
  text-decoration-color: currentColor;
}
.message-text :deep(code) {
  font-family: 'JetBrains Mono', monospace;
  font-size: 0.8em;
  padding: 1px 5px;
  border-radius: 3px;
  background: rgba(0, 0, 0, 0.06);
}
.message-text :deep(pre) {
  margin: 8px 0;
  padding: 10px 12px;
  border-radius: 6px;
  background: var(--color-sidebar);
  overflow-x: auto;
}
.message-text :deep(pre code) {
  background: none;
  padding: 0;
  color: #d4cdc4;
  font-size: 0.8rem;
  line-height: 1.5;
}
.message-user .message-text :deep(code) {
  background: rgba(255, 255, 255, 0.18);
}
.message-user .message-text :deep(a) {
  color: #fff;
  text-decoration-color: rgba(255, 255, 255, 0.7);
}

.message-time {
  font-size: 0.65rem;
  color: var(--color-text-muted);
  padding: 0 4px;
}

.message-user .message-time {
  text-align: right;
}

/* Tool-call cards block (#362) */
.tool-calls-block {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-bottom: 4px;
}

/* Per-message actions (#360) */
.message-actions {
  display: flex;
  gap: 2px;
  padding: 0 2px;
  opacity: 0;
  transition: opacity 0.15s;
}

.message-user .message-actions {
  justify-content: flex-end;
}

.message:hover .message-actions,
.message:focus-within .message-actions {
  opacity: 1;
}

.msg-action {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  padding: 0;
  border: none;
  background: transparent;
  color: var(--color-text-muted);
  border-radius: 4px;
  cursor: pointer;
  transition: all 0.12s;
}

.msg-action:hover {
  background: var(--color-border-light);
  color: var(--color-text-primary);
}

.msg-action svg {
  width: 13px;
  height: 13px;
}

/* Inline edit-and-resend (#360) */
.message-edit {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 10px 12px;
  background: var(--color-surface-raised);
  border: 1px solid var(--color-brand);
  border-radius: 12px;
  box-shadow: 0 0 0 3px rgba(200, 165, 90, 0.15);
}

.message-edit-input {
  width: 280px;
  min-height: 48px;
  max-height: 200px;
  padding: 4px 0;
  border: none;
  outline: none;
  background: transparent;
  font-family: 'DM Sans', sans-serif;
  font-size: 0.875rem;
  color: var(--color-text-primary);
  resize: vertical;
  line-height: 1.5;
}

.message-edit-actions {
  display: flex;
  gap: 6px;
  justify-content: flex-end;
}

.message-edit-btn {
  padding: 3px 10px;
  font-size: 0.75rem;
  border-radius: 5px;
  border: 1px solid var(--color-border);
  background: var(--color-surface);
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all 0.12s;
}

.message-edit-btn:hover {
  border-color: var(--color-text-muted);
  color: var(--color-text-primary);
}

.message-edit-btn.primary {
  background: var(--color-brand);
  border-color: var(--color-brand);
  color: #fff;
}

.message-edit-btn.primary:hover {
  background: var(--color-brand-dim);
}

/* Typing indicator */
.typing-indicator {
  display: flex;
  gap: 4px;
  padding: 12px 16px;
  background: var(--color-surface-raised);
  border: 1px solid var(--color-border);
  border-radius: 12px;
  border-bottom-left-radius: 4px;
}

.typing-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: var(--color-text-muted);
  animation: typing-bounce 1s ease-in-out infinite;
}

.typing-dot:nth-child(2) { animation-delay: 0.15s; }
.typing-dot:nth-child(3) { animation-delay: 0.3s; }

@keyframes typing-bounce {
  0%, 100% { opacity: 0.3; transform: translateY(0); }
  50% { opacity: 1; transform: translateY(-4px); }
}

/* Drag-drop overlay */
.drop-overlay {
  position: absolute;
  inset: 0;
  background: rgba(200, 165, 90, 0.10);
  border: 2px dashed var(--color-brand);
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  pointer-events: none;
  z-index: 10;
}

.drop-message {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  color: var(--color-brand-dim);
  font-weight: 600;
  font-size: 0.95rem;
}

.drop-message svg {
  width: 36px;
  height: 36px;
}

/* Background processing indicator */
.processing-bar {
  margin-top: 8px;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  border-radius: 10px;
  background: linear-gradient(90deg, rgba(200, 165, 90, 0.08), rgba(200, 165, 90, 0.03));
  border: 1px solid rgba(200, 165, 90, 0.35);
}

.processing-spinner {
  width: 16px;
  height: 16px;
  border: 2px solid rgba(200, 165, 90, 0.25);
  border-top-color: var(--color-brand);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
  flex-shrink: 0;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.processing-content {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
  flex: 1;
}

.processing-label {
  font-size: 0.85rem;
  font-weight: 500;
  color: var(--color-text-primary);
}

.processing-meta {
  display: flex;
  gap: 10px;
  font-size: 0.7rem;
  color: var(--color-text-muted);
}

.processing-elapsed {
  font-family: 'JetBrains Mono', monospace;
  color: var(--color-brand-dim);
  font-weight: 500;
}

.processing-stages {
  letter-spacing: 0.02em;
}

/* Pending attachment row */
.pending-row {
  margin-top: 8px;
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.pending-error {
  font-size: 0.75rem;
  color: #b91c1c;
  background: #fef2f2;
  border: 1px solid #fecaca;
  border-radius: 6px;
  padding: 6px 10px;
}

.pending-pills {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.pending-pill {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 4px 4px 4px 10px;
  border-radius: 999px;
  background: var(--color-border-light);
  border: 1px solid var(--color-border);
  font-size: 0.75rem;
  color: var(--color-text-primary);
}

.pending-name {
  font-weight: 500;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pending-size {
  color: var(--color-text-muted);
}

.pending-remove {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
  border-radius: 50%;
  border: none;
  background: transparent;
  color: var(--color-text-muted);
  cursor: pointer;
}

.pending-remove:hover {
  background: var(--color-border);
  color: var(--color-text-primary);
}

.pending-remove svg {
  width: 10px;
  height: 10px;
}

/* Input bar */
.chat-input-bar {
  padding-top: 12px;
  flex-shrink: 0;
}

.input-wrap {
  display: flex;
  align-items: flex-end;
  gap: 0;
  border: 1px solid var(--color-border);
  border-radius: 10px;
  background: var(--color-surface-raised);
  transition: border-color 0.15s, box-shadow 0.15s;
  overflow: hidden;
}

.input-wrap:focus-within {
  border-color: var(--color-brand);
  box-shadow: 0 0 0 3px rgba(200, 165, 90, 0.15);
}

.file-input-hidden {
  display: none;
}

.attach-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  margin: 2px;
  border-radius: 8px;
  border: none;
  background: transparent;
  color: var(--color-text-secondary);
  cursor: pointer;
  transition: all 0.15s;
  flex-shrink: 0;
}

.attach-btn:hover:not(:disabled) {
  background: var(--color-border-light);
  color: var(--color-text-primary);
}

.attach-btn:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.attach-btn svg {
  width: 16px;
  height: 16px;
}

.chat-input {
  flex: 1;
  padding: 11px 14px;
  border: none;
  outline: none;
  font-family: 'DM Sans', sans-serif;
  font-size: 0.9rem;
  color: var(--color-text-primary);
  background: transparent;
  resize: none;
  line-height: 1.45;
  min-height: 22px;
  max-height: 200px;
  overflow-y: auto;
}

.chat-input::placeholder {
  color: var(--color-text-muted);
}

.chat-input:disabled {
  opacity: 0.5;
}

.send-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  margin: 2px;
  border-radius: 8px;
  border: none;
  background: var(--color-brand);
  color: white;
  cursor: pointer;
  transition: all 0.15s;
  flex-shrink: 0;
}

.send-btn svg {
  width: 16px;
  height: 16px;
}

.send-btn:hover:not(:disabled) {
  background: var(--color-brand-dim);
}

.send-btn:disabled {
  background: var(--color-border);
  color: var(--color-text-muted);
  cursor: not-allowed;
}
</style>
