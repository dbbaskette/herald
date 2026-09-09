<script setup lang="ts">
import { ref, onMounted, onUnmounted, watch } from 'vue'
import { authenticated, authEnabled, authChecked, authError, checkSession, signIn, signOut } from '@/security/consoleAuth'
const token = ref(''), busy = ref(false), mountedContent = ref(false)
watch(authenticated, value => { if (value) mountedContent.value = true }, { immediate: true })
async function submit() {
  if (busy.value) return
  busy.value = true
  const submitted = token.value; token.value = ''
  try { await signIn(submitted) } finally { busy.value = false }
}
let timer: ReturnType<typeof setInterval> | undefined
onMounted(() => { void checkSession(); timer = setInterval(() => { void checkSession() }, 60000) })
onUnmounted(() => clearInterval(timer))
</script>
<template>
  <div v-if="mountedContent" :inert="!authenticated" :aria-hidden="!authenticated"><slot /></div>
  <button v-if="authenticated && authEnabled" class="lock-console" @click="signOut">Lock console</button>
  <div v-if="!authenticated" class="console-access" role="dialog" aria-modal="true" aria-labelledby="console-access-title">
    <form @submit.prevent="submit" class="access-card">
      <h1 id="console-access-title">{{ authChecked && authEnabled ? 'Unlock Herald' : 'Connecting to Herald' }}</h1>
      <template v-if="authChecked && authEnabled">
        <p>Enter the shared console token configured on your Herald server.</p>
        <label for="console-token">Console token</label>
        <input id="console-token" v-model="token" type="password" autocomplete="off" spellcheck="false" autofocus />
        <button :disabled="busy || !token">{{ busy ? 'Signing in…' : 'Sign in' }}</button>
      </template>
      <p v-if="authError" role="alert">{{ authError }}</p>
      <button v-if="authError" type="button" @click="checkSession">Retry connection</button>
    </form>
  </div>
</template>
<style scoped>
.lock-console { position: fixed; right: 12px; bottom: 12px; z-index: 40; background: #263943; color: white; border-radius: 6px; padding: 8px 12px; }
.console-access { position: fixed; inset: 0; z-index: 1000; display: grid; place-items: center; background: var(--color-surface, #faf8f4); padding: 24px; }
.access-card { display: grid; gap: 14px; max-width: 440px; width: 100%; color: var(--color-text, #252525); }
.access-card h1 { font-size: 26px; font-weight: 600; }
.access-card input { border: 1px solid #888; border-radius: 6px; padding: 12px; width: 100%; background: white; }
.access-card button { padding: 10px 16px; background: #263943; color: white; border-radius: 6px; }
.access-card button:disabled { opacity: .5; }
</style>
