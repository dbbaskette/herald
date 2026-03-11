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
    <div class="px-3 py-3 border-t border-gray-700">
      <RouterLink
        to="/settings"
        class="flex items-center gap-3 px-3 py-2 rounded-md text-sm font-medium hover:bg-gray-800 transition-colors text-gray-400"
        active-class="bg-gray-800 text-white"
      >
        <span class="w-5 text-center">
          <svg class="w-4 h-4 inline" fill="none" stroke="currentColor" viewBox="0 0 24 24">
            <path stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M10.325 4.317c.426-1.756 2.924-1.756 3.35 0a1.724 1.724 0 002.573 1.066c1.543-.94 3.31.826 2.37 2.37a1.724 1.724 0 001.066 2.573c1.756.426 1.756 2.924 0 3.35a1.724 1.724 0 00-1.066 2.573c.94 1.543-.826 3.31-2.37 2.37a1.724 1.724 0 00-2.573 1.066c-.426 1.756-2.924 1.756-3.35 0a1.724 1.724 0 00-2.573-1.066c-1.543.94-3.31-.826-2.37-2.37a1.724 1.724 0 00-1.066-2.573c-1.756-.426-1.756-2.924 0-3.35a1.724 1.724 0 001.066-2.573c-.94-1.543.826-3.31 2.37-2.37.996.608 2.296.07 2.572-1.065z" />
            <circle cx="12" cy="12" r="3" stroke-width="2" />
          </svg>
        </span>
        Settings
      </RouterLink>
      <span class="block mt-2 px-3 text-xs text-gray-500 font-mono">v{{ appVersion }}</span>
    </div>
  </aside>
</template>
