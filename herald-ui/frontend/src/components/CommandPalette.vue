<script setup lang="ts">
/**
 * Cmd/Ctrl+K command palette (#359). Fuzzy-searches across routes plus a small
 * set of global actions. Stays self-contained: the parent (App.vue) handles the
 * shortcut and toggles the `open` prop; the palette emits `close` when the user
 * picks an item or hits Esc.
 *
 * Items:
 * - All routes (Status, Chat, Skills, Prompts, Memory, Cron, History, Settings)
 * - Actions: New chat, Clear chat, Open Settings
 *
 * Future extensions: hook in skill names, prompts, recent conversations.
 */
import { ref, computed, watch, nextTick, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useChatStore } from '@/stores/chat'

const props = defineProps<{ open: boolean }>()
const emit = defineEmits<{ (e: 'close'): void }>()

const router = useRouter()
const chat = useChatStore()
const query = ref('')
const selectedIndex = ref(0)
const inputEl = ref<HTMLInputElement | null>(null)

interface Item {
  id: string
  label: string
  hint?: string
  category: 'Navigate' | 'Action'
  shortcut?: string
  run: () => void | Promise<void>
}

const items = computed<Item[]>(() => {
  const list: Item[] = [
    // Navigation
    { id: 'nav-status', label: 'Go to Status', hint: 'Dashboard', category: 'Navigate', shortcut: 'g s', run: () => { router.push('/') } },
    { id: 'nav-chat', label: 'Go to Chat', category: 'Navigate', shortcut: 'g c', run: () => { router.push('/chat') } },
    { id: 'nav-skills', label: 'Go to Skills', category: 'Navigate', shortcut: 'g k', run: () => { router.push('/skills') } },
    { id: 'nav-prompts', label: 'Go to Prompts', category: 'Navigate', shortcut: 'g p', run: () => { router.push('/prompts') } },
    { id: 'nav-memory', label: 'Go to Memory', category: 'Navigate', shortcut: 'g m', run: () => { router.push('/memory') } },
    { id: 'nav-cron', label: 'Go to Cron', category: 'Navigate', run: () => { router.push('/cron') } },
    { id: 'nav-history', label: 'Go to History', category: 'Navigate', shortcut: 'g h', run: () => { router.push('/history') } },
    { id: 'nav-settings', label: 'Open Settings', category: 'Navigate', shortcut: '⌘,', run: () => { router.push('/settings') } },
    // Actions
    { id: 'act-new-chat', label: 'New conversation', hint: 'Start a fresh chat thread', category: 'Action', shortcut: '⌘N',
      run: () => { router.push('/chat'); chat.newConversation() } },
    { id: 'act-clear-chat', label: 'Clear current chat', category: 'Action', shortcut: '⌘L',
      run: () => { router.push('/chat'); chat.clearMessages() } },
  ]
  return list
})

const filtered = computed<Item[]>(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return items.value
  return items.value.filter(it =>
    it.label.toLowerCase().includes(q) ||
    (it.hint || '').toLowerCase().includes(q) ||
    it.category.toLowerCase().includes(q)
  )
})

watch(filtered, () => { selectedIndex.value = 0 })

watch(() => props.open, async (v) => {
  if (v) {
    query.value = ''
    selectedIndex.value = 0
    await nextTick()
    inputEl.value?.focus()
  }
})

function runItem(item: Item) {
  emit('close')
  // Defer one tick so the route push happens after the modal closes,
  // avoiding focus traps on the disappearing input.
  nextTick(() => item.run())
}

function onKeydown(e: KeyboardEvent) {
  if (!props.open) return
  if (e.key === 'Escape') { e.preventDefault(); emit('close'); return }
  if (e.key === 'ArrowDown') {
    e.preventDefault()
    selectedIndex.value = Math.min(filtered.value.length - 1, selectedIndex.value + 1)
  } else if (e.key === 'ArrowUp') {
    e.preventDefault()
    selectedIndex.value = Math.max(0, selectedIndex.value - 1)
  } else if (e.key === 'Enter') {
    e.preventDefault()
    const it = filtered.value[selectedIndex.value]
    if (it) runItem(it)
  }
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onUnmounted(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <Transition name="palette">
    <div v-if="open" class="palette-overlay" @click.self="emit('close')">
      <div class="palette-card">
        <div class="palette-input-wrap">
          <svg class="palette-search-icon" viewBox="0 0 16 16" fill="none" stroke="currentColor" stroke-width="1.5">
            <circle cx="7" cy="7" r="4.5"/>
            <path d="M11 11l3 3"/>
          </svg>
          <input
            ref="inputEl"
            v-model="query"
            type="text"
            class="palette-input"
            placeholder="Type a command or page name…"
          />
          <kbd class="palette-kbd">esc</kbd>
        </div>
        <div class="palette-list">
          <template v-if="filtered.length === 0">
            <div class="palette-empty">No matches</div>
          </template>
          <template v-else>
            <button
              v-for="(it, idx) in filtered"
              :key="it.id"
              class="palette-item"
              :class="{ active: idx === selectedIndex }"
              @mouseenter="selectedIndex = idx"
              @click="runItem(it)"
            >
              <span class="palette-category" :class="'cat-' + it.category.toLowerCase()">
                {{ it.category }}
              </span>
              <span class="palette-label">{{ it.label }}</span>
              <span v-if="it.hint" class="palette-hint">{{ it.hint }}</span>
              <kbd v-if="it.shortcut" class="palette-shortcut">{{ it.shortcut }}</kbd>
            </button>
          </template>
        </div>
        <div class="palette-footer">
          <span><kbd>↑</kbd><kbd>↓</kbd> navigate</span>
          <span><kbd>↵</kbd> select</span>
          <span><kbd>esc</kbd> close</span>
        </div>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.palette-overlay {
  position: fixed;
  inset: 0;
  background: rgba(20, 22, 23, 0.55);
  backdrop-filter: blur(2px);
  display: flex;
  align-items: flex-start;
  justify-content: center;
  padding-top: 12vh;
  z-index: 1000;
}

.palette-card {
  width: min(560px, calc(100vw - 32px));
  background: var(--color-surface-raised);
  border: 1px solid var(--color-border);
  border-radius: 12px;
  box-shadow: 0 24px 64px rgba(0, 0, 0, 0.18);
  display: flex;
  flex-direction: column;
  max-height: 70vh;
  overflow: hidden;
  font-family: 'DM Sans', system-ui, sans-serif;
}

.palette-input-wrap {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px 16px;
  border-bottom: 1px solid var(--color-border-light);
}

.palette-search-icon {
  width: 16px;
  height: 16px;
  color: var(--color-text-muted);
}

.palette-input {
  flex: 1;
  border: none;
  outline: none;
  background: transparent;
  font-family: inherit;
  font-size: 0.95rem;
  color: var(--color-text-primary);
}

.palette-kbd {
  font-family: 'JetBrains Mono', monospace;
  font-size: 11px;
  padding: 2px 6px;
  border: 1px solid var(--color-border);
  border-radius: 4px;
  color: var(--color-text-muted);
  background: var(--color-surface);
}

.palette-list {
  flex: 1;
  overflow-y: auto;
  padding: 6px;
}

.palette-empty {
  padding: 24px;
  text-align: center;
  color: var(--color-text-muted);
  font-size: 0.85rem;
}

.palette-item {
  display: grid;
  grid-template-columns: 80px 1fr auto;
  align-items: center;
  gap: 12px;
  width: 100%;
  padding: 9px 12px;
  border: none;
  background: transparent;
  text-align: left;
  border-radius: 7px;
  cursor: pointer;
  font-family: inherit;
  font-size: 0.875rem;
  color: var(--color-text-primary);
}

.palette-item.active {
  background: rgba(200, 165, 90, 0.10);
}

.palette-item:hover {
  background: rgba(200, 165, 90, 0.06);
}

.palette-category {
  font-size: 0.65rem;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.08em;
  color: var(--color-text-muted);
}

.cat-navigate { color: #2563eb; }
.cat-action   { color: var(--color-brand-dim); }

.palette-label {
  font-weight: 500;
}

.palette-hint {
  font-size: 0.75rem;
  color: var(--color-text-muted);
  grid-column: 2;
  grid-row: 2;
  margin-top: -2px;
}

.palette-shortcut {
  font-family: 'JetBrains Mono', monospace;
  font-size: 11px;
  padding: 1px 6px;
  border: 1px solid var(--color-border);
  border-radius: 4px;
  color: var(--color-text-muted);
  background: var(--color-surface);
}

.palette-footer {
  display: flex;
  gap: 16px;
  padding: 8px 16px;
  border-top: 1px solid var(--color-border-light);
  font-size: 0.7rem;
  color: var(--color-text-muted);
  background: var(--color-surface);
}

.palette-footer kbd {
  display: inline-block;
  font-family: 'JetBrains Mono', monospace;
  font-size: 10px;
  padding: 0 4px;
  margin-right: 3px;
  border: 1px solid var(--color-border);
  border-radius: 3px;
  background: var(--color-surface-raised);
}

.palette-enter-active,
.palette-leave-active {
  transition: opacity 0.15s;
}

.palette-enter-from,
.palette-leave-to {
  opacity: 0;
}

.palette-enter-active .palette-card,
.palette-leave-active .palette-card {
  transition: transform 0.15s ease-out;
}

.palette-enter-from .palette-card,
.palette-leave-to .palette-card {
  transform: scale(0.97) translateY(-6px);
}
</style>
