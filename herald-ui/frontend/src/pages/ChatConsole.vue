<script setup lang="ts">
import { ref, nextTick, onMounted, watch } from 'vue'
import { useChatStore } from '@/stores/chat'

const store = useChatStore()
const input = ref('')
const chatBody = ref<HTMLElement | null>(null)
const inputEl = ref<HTMLInputElement | null>(null)

async function handleSend() {
  const text = input.value.trim()
  if (!text || store.sending) return
  input.value = ''
  await store.send(text)
  inputEl.value?.focus()
}

async function scrollToBottom() {
  await nextTick()
  if (chatBody.value) {
    chatBody.value.scrollTop = chatBody.value.scrollHeight
  }
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
  <div class="chat-page">
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
        <p class="empty-hint">Same agent as Telegram — tools, memory, and skills are all available</p>
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
    </div>

    <!-- Input bar -->
    <div class="chat-input-bar">
      <div class="input-wrap">
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
          :disabled="!input.trim() || store.sending"
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
