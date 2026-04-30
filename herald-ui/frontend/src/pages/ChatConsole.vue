<script setup lang="ts">
import { ref, nextTick, onMounted, watch, computed } from 'vue'
import { useChatStore } from '@/stores/chat'

const store = useChatStore()
const input = ref('')
const chatBody = ref<HTMLElement | null>(null)
const inputEl = ref<HTMLInputElement | null>(null)
const fileInputEl = ref<HTMLInputElement | null>(null)
const attachments = ref<File[]>([])
const dragActive = ref(false)
const attachmentError = ref('')

const MAX_TOTAL_BYTES = 20 * 1024 * 1024 // 20 MB total cap

const totalBytes = computed(() =>
  attachments.value.reduce((sum, f) => sum + f.size, 0)
)

const overLimit = computed(() => totalBytes.value > MAX_TOTAL_BYTES)

const canSend = computed(
  () => (input.value.trim().length > 0 || attachments.value.length > 0) && !store.sending && !overLimit.value
)

async function handleSend() {
  if (!canSend.value) return
  const text = input.value
  const files = attachments.value
  input.value = ''
  attachments.value = []
  attachmentError.value = ''
  await store.send(text, files)
  inputEl.value?.focus()
}

function openFilePicker() {
  fileInputEl.value?.click()
}

function onFileInputChange(event: Event) {
  const target = event.target as HTMLInputElement
  if (target.files && target.files.length > 0) {
    addFiles(Array.from(target.files))
  }
  // Reset so selecting the same file again triggers change.
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
  // Only show drop affordance when dragging files.
  if (Array.from(e.dataTransfer.items).some((i) => i.kind === 'file')) {
    e.preventDefault()
    dragActive.value = true
  }
}

function onDragLeave(e: DragEvent) {
  // Only deactivate when leaving the drop zone, not just hovering child elements.
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

// Auto-scroll whenever messages change, content updates, or sending state changes
watch(() => store.messages.length, scrollToBottom)
watch(() => store.sending, scrollToBottom)
watch(
  () => store.messages.map((m) => m.content.length).join(','),
  scrollToBottom
)

onMounted(() => {
  inputEl.value?.focus()
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
        <h1 class="page-title">Chat</h1>
        <span class="conv-id">{{ store.conversationId }}</span>
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
            <!-- Streaming bubble with no content yet: show dots inline -->
            <div v-if="msg.streaming && !msg.content" class="typing-indicator">
              <span class="typing-dot"></span>
              <span class="typing-dot"></span>
              <span class="typing-dot"></span>
            </div>
            <div
              v-else
              class="message-text"
              :class="{ 'message-streaming': msg.streaming }"
              v-html="renderMarkdown(msg.content)"
            ></div>
            <span class="message-time">{{ formatTime(msg.timestamp) }}</span>
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
        <input
          ref="inputEl"
          v-model="input"
          type="text"
          placeholder="Send a message..."
          class="chat-input"
          :disabled="store.sending"
          @keydown.enter="handleSend()"
        />
        <button
          class="send-btn"
          :disabled="!canSend"
          @click="handleSend()"
        >
          <svg viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5">
            <path d="M2 8h12M10 4l4 4-4 4"/>
          </svg>
        </button>
      </div>
    </div>
  </div>
</template>

<script lang="ts">
function renderMarkdown(text: string): string {
  if (!text) return ''
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/\*\*(.+?)\*\*/g, '<strong>$1</strong>')
    .replace(/\*(.+?)\*/g, '<em>$1</em>')
    .replace(/`([^`]+)`/g, '<code>$1</code>')
    .replace(/```(\w*)\n([\s\S]*?)```/g, '<pre><code>$2</code></pre>')
    .replace(/\n/g, '<br>')
}

function formatTime(ts: string): string {
  try {
    const d = new Date(ts)
    return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', second: '2-digit' })
  } catch { return '' }
}
</script>

<style scoped>
@import url('https://fonts.googleapis.com/css2?family=DM+Sans:opsz,wght@9..40,300;9..40,400;9..40,500;9..40,600&family=JetBrains+Mono:wght@400&display=swap');

.chat-page {
  display: flex;
  flex-direction: column;
  height: 100%;
  font-family: 'DM Sans', system-ui, sans-serif;
  position: relative;
}

/* Header */
.chat-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding-bottom: 12px;
}

.header-left {
  display: flex;
  align-items: baseline;
  gap: 10px;
}

.page-title {
  font-size: 1.5rem;
  font-weight: 600;
  color: #1a1a1a;
  letter-spacing: -0.02em;
}

.conv-id {
  font-size: 0.7rem;
  font-family: 'JetBrains Mono', monospace;
  color: #9ca3af;
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
  border: 1px solid #e5e7eb;
  background: white;
  font-family: 'DM Sans', sans-serif;
  font-size: 0.8rem;
  font-weight: 500;
  color: #374151;
  cursor: pointer;
  transition: all 0.15s;
}

.header-btn svg {
  width: 14px;
  height: 14px;
}

.header-btn:hover {
  background: #f9fafb;
  border-color: #d1d5db;
}

.header-btn-subtle {
  border-color: transparent;
  background: transparent;
  color: #9ca3af;
}

.header-btn-subtle:hover {
  color: #6b7280;
  background: #f3f4f6;
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
  border: 1px solid #e8e5df;
  border-radius: 10px;
  background: #fafaf8;
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
  color: #6b7280;
  font-weight: 500;
}

.empty-hint {
  font-size: 0.75rem;
  color: #9ca3af;
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
  background: #dbeafe;
  color: #1d4ed8;
}

.message-assistant .message-avatar {
  background: #1c1f26;
  color: #e2b563;
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
  background: #eef2ff;
  border: 1px solid #c7d2fe;
  font-size: 0.7rem;
  color: #1e3a8a;
  font-weight: 500;
}

.message-attachment-pill svg {
  width: 11px;
  height: 11px;
}

.message-attachment-pill .size {
  color: #6b7280;
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
  background: #1d4ed8;
  color: white;
  border-bottom-right-radius: 4px;
}

.message-assistant .message-text {
  background: white;
  color: #1a1a1a;
  border: 1px solid #e8e5df;
  border-bottom-left-radius: 4px;
}

.message-streaming::after {
  content: '';
  display: inline-block;
  width: 2px;
  height: 1em;
  margin-left: 2px;
  background: #1a1a1a;
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
  background: #1c1f26;
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
  background: rgba(255, 255, 255, 0.15);
}

.message-time {
  font-size: 0.65rem;
  color: #9ca3af;
  padding: 0 4px;
}

.message-user .message-time {
  text-align: right;
}

/* Typing indicator */
.typing-indicator {
  display: flex;
  gap: 4px;
  padding: 12px 16px;
  background: white;
  border: 1px solid #e8e5df;
  border-radius: 12px;
  border-bottom-left-radius: 4px;
}

.typing-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #9ca3af;
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
  background: rgba(29, 78, 216, 0.08);
  border: 2px dashed #1d4ed8;
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
  color: #1d4ed8;
  font-weight: 600;
  font-size: 0.95rem;
}

.drop-message svg {
  width: 36px;
  height: 36px;
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
  background: #f3f4f6;
  border: 1px solid #e5e7eb;
  font-size: 0.75rem;
  color: #1a1a1a;
}

.pending-name {
  font-weight: 500;
  max-width: 200px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.pending-size {
  color: #6b7280;
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
  color: #6b7280;
  cursor: pointer;
}

.pending-remove:hover {
  background: #e5e7eb;
  color: #1a1a1a;
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
  align-items: center;
  gap: 0;
  border: 1px solid #e5e7eb;
  border-radius: 10px;
  background: white;
  transition: border-color 0.15s, box-shadow 0.15s;
  overflow: hidden;
}

.input-wrap:focus-within {
  border-color: #1d4ed8;
  box-shadow: 0 0 0 3px rgba(29, 78, 216, 0.1);
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
  color: #6b7280;
  cursor: pointer;
  transition: all 0.15s;
  flex-shrink: 0;
}

.attach-btn:hover:not(:disabled) {
  background: #f3f4f6;
  color: #1a1a1a;
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
  padding: 12px 16px;
  border: none;
  outline: none;
  font-family: 'DM Sans', sans-serif;
  font-size: 0.9rem;
  color: #1a1a1a;
  background: transparent;
}

.chat-input::placeholder {
  color: #9ca3af;
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
  background: #1d4ed8;
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
  background: #1e40af;
}

.send-btn:disabled {
  background: #e5e7eb;
  color: #9ca3af;
  cursor: not-allowed;
}
</style>
