<script setup lang="ts">
import { RouterLink } from 'vue-router'

declare const __APP_VERSION__: string
const appVersion = __APP_VERSION__

const navItems = [
  { to: '/', label: 'System Status', icon: '■', exact: true },
  { to: '/chat', label: 'Chat', icon: '💬', exact: false },
  { to: '/skills', label: 'Skills Editor', icon: '★', exact: false },
  { to: '/memory', label: 'Memory Viewer', icon: '◓', exact: false },
  { to: '/cron', label: 'Cron Builder', icon: '⏰', exact: false },
  { to: '/history', label: 'History', icon: '☰', exact: false },
]
</script>

<template>
  <aside class="w-64 bg-gray-900 text-gray-100 flex flex-col">
    <div class="flex items-center gap-3 px-5 py-4 border-b border-gray-700">
      <img src="/herald-icon.png" alt="Herald" class="w-8 h-8 rounded-md" />
      <span class="text-xl font-bold tracking-wide">Herald</span>
    </div>
    <nav class="flex-1 px-3 py-4 space-y-1">
      <RouterLink
        v-for="item in navItems"
        :key="item.to"
        :to="item.to"
        class="flex items-center gap-3 px-3 py-2 rounded-md text-sm font-medium hover:bg-gray-800 transition-colors"
        :active-class="item.exact ? '' : 'bg-gray-800 text-white'"
        :exact-active-class="item.exact ? 'bg-gray-800 text-white' : ''"
      >
        <span class="w-5 text-center">{{ item.icon }}</span>
        {{ item.label }}
      </RouterLink>
    </nav>
    <div class="px-5 py-3 border-t border-gray-700">
      <span class="text-xs text-gray-500 font-mono">v{{ appVersion }}</span>
    </div>
  </aside>
</template>
