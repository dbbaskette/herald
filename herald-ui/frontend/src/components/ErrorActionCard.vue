<script setup lang="ts">
import { computed, ref } from 'vue'
import { useRouter } from 'vue-router'

// Renders an error message with a contextual action button when the text
// matches a known, fixable failure. The agent's tool layer already maps Google
// API errors to hinted phrasing (HeraldShellDecorator.googleErrorHint), and the
// budget gate emits recognizable copy — we classify on those and surface a
// one-click next step instead of leaving the fix buried in prose.
const props = defineProps<{ text: string }>()
const router = useRouter()

type Action = { kind: 'connect-google' | 'cloud-console' | 'settings'; label: string }

const action = computed<Action | null>(() => {
  const t = props.text.toLowerCase()
  if (/not enabled|service_disabled|console\.cloud\.google/.test(t)) {
    return { kind: 'cloud-console', label: 'Open Google Cloud Console' }
  }
  if (/unauthenticated|token (is )?expired|token.*revoked|reconnect|missing.*scope|insufficient|connect google/.test(t)) {
    return { kind: 'connect-google', label: 'Connect Google' }
  }
  if (/usage limit|budget|spend|model-ceiling|paused/.test(t)) {
    return { kind: 'settings', label: 'Open Settings (switch model)' }
  }
  return null
})

const busy = ref(false)
const note = ref('')

async function run() {
  const a = action.value
  if (!a) return
  if (a.kind === 'cloud-console') {
    window.open('https://console.cloud.google.com/apis/library', '_blank', 'noopener')
    return
  }
  if (a.kind === 'settings') {
    router.push('/settings')
    return
  }
  // connect-google — drive the same OAuth flow the Settings panel uses
  busy.value = true
  note.value = ''
  try {
    const res = await fetch('/api/gws/login', { method: 'POST' })
    const data = await res.json()
    if (data.authUrl) {
      window.open(data.authUrl, '_blank', 'noopener')
      note.value = 'Opened Google sign-in in a new tab.'
    } else {
      note.value = data.message || 'Login started — check your browser.'
    }
  } catch {
    note.value = 'Could not start Google login.'
  } finally {
    busy.value = false
  }
}
</script>

<template>
  <div class="error-action">
    <div class="error-action-icon">!</div>
    <div class="error-action-body">
      <p class="error-action-text">{{ text }}</p>
      <div v-if="action" class="error-action-row">
        <button class="error-action-btn" :disabled="busy" @click="run">
          {{ busy ? 'Working…' : action.label }}
        </button>
        <span v-if="note" class="error-action-note">{{ note }}</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.error-action {
  display: flex;
  gap: 10px;
  padding: 12px 14px;
  border: 1px solid #fca5a5;
  background: #fef2f2;
  border-radius: 10px;
  color: #991b1b;
}
.error-action-icon {
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: #dc2626;
  color: #fff;
  font-weight: 700;
  font-size: 0.8rem;
  display: flex;
  align-items: center;
  justify-content: center;
}
.error-action-body { flex: 1; min-width: 0; }
.error-action-text { margin: 0 0 6px; font-size: 0.85rem; white-space: pre-wrap; word-break: break-word; }
.error-action-row { display: flex; align-items: center; gap: 10px; flex-wrap: wrap; }
.error-action-btn {
  padding: 5px 12px;
  font-size: 0.78rem;
  font-weight: 600;
  color: #fff;
  background: #dc2626;
  border: none;
  border-radius: 6px;
  cursor: pointer;
}
.error-action-btn:disabled { opacity: 0.6; cursor: default; }
.error-action-note { font-size: 0.75rem; color: #7f1d1d; }
</style>
