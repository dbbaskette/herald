<script setup lang="ts">
import { ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import SidebarNav from './components/SidebarNav.vue'
import CommandPalette from './components/CommandPalette.vue'
import { useChatStore } from '@/stores/chat'

const router = useRouter()
const chat = useChatStore()
const paletteOpen = ref(false)

// Vim-style "g <key>" sequence for fast page navigation.
let pendingG = false
let pendingGTimer: ReturnType<typeof setTimeout> | null = null
const G_NAV: Record<string, string> = {
  s: '/',         // status
  c: '/chat',
  k: '/skills',
  p: '/prompts',
  m: '/memory',
  h: '/history',
}

function isTypingTarget(el: EventTarget | null): boolean {
  if (!(el instanceof HTMLElement)) return false
  const tag = el.tagName
  return tag === 'INPUT' || tag === 'TEXTAREA' || el.isContentEditable
}

function onKeydown(e: KeyboardEvent) {
  const meta = e.metaKey || e.ctrlKey

  // Cmd/Ctrl + K — open command palette. Allowed even from inputs.
  if (meta && e.key.toLowerCase() === 'k') {
    e.preventDefault()
    paletteOpen.value = true
    return
  }

  // Cmd/Ctrl + , — settings
  if (meta && e.key === ',') {
    e.preventDefault()
    router.push('/settings')
    return
  }

  // The rest of the shortcuts should NOT fire while the user is typing.
  if (isTypingTarget(e.target)) return

  // Cmd/Ctrl + N — new chat
  if (meta && e.key.toLowerCase() === 'n') {
    e.preventDefault()
    router.push('/chat')
    chat.newConversation()
    return
  }

  // Cmd/Ctrl + L — clear current chat (only on chat page)
  if (meta && e.key.toLowerCase() === 'l') {
    if (router.currentRoute.value.path === '/chat') {
      e.preventDefault()
      chat.clearMessages()
    }
    return
  }

  // Cmd/Ctrl + / — focus chat input
  if (meta && e.key === '/') {
    e.preventDefault()
    router.push('/chat').then(() => {
      const el = document.querySelector<HTMLTextAreaElement>('.chat-input')
      el?.focus()
    })
    return
  }

  // "?" — open palette as cheat-sheet substitute (full sheet is the palette).
  if (e.key === '?' && !meta) {
    e.preventDefault()
    paletteOpen.value = true
    return
  }

  // Vim-style "g <letter>" navigation. First press "g", second press a key.
  if (e.key === 'g' && !meta && !e.shiftKey && !e.altKey) {
    e.preventDefault()
    pendingG = true
    if (pendingGTimer) clearTimeout(pendingGTimer)
    pendingGTimer = setTimeout(() => { pendingG = false }, 800)
    return
  }
  if (pendingG && !meta && !e.altKey) {
    const key = e.key.toLowerCase()
    if (G_NAV[key]) {
      e.preventDefault()
      pendingG = false
      router.push(G_NAV[key])
    } else if (key !== 'g') {
      pendingG = false
    }
  }
}

onMounted(() => window.addEventListener('keydown', onKeydown))
onUnmounted(() => window.removeEventListener('keydown', onKeydown))
</script>

<template>
  <div class="app-shell">
    <SidebarNav />
    <main class="app-content">
      <router-view />
    </main>
    <CommandPalette :open="paletteOpen" @close="paletteOpen = false" />
  </div>
</template>

<style scoped>
.app-shell {
  display: flex;
  height: 100vh;
  background: var(--color-surface);
}

.app-content {
  flex: 1;
  overflow: auto;
  padding: 28px 36px;
}
</style>
