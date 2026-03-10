<script setup lang="ts">
import { RouterLink } from 'vue-router'

const navItems = [
  { to: '/', label: 'System Status', icon: '&#9632;' },
  { to: '/skills', label: 'Skills Editor', icon: '&#9733;' },
  { to: '/memory', label: 'Memory Viewer', icon: '&#9683;' },
  { to: '/cron', label: 'Cron Builder', icon: '&#9200;' },
  { to: '/history', label: 'History', icon: '&#9776;' },
]
</script>

<template>
  <aside class="w-64 bg-gray-900 text-gray-100 flex flex-col">
    <div class="px-6 py-4 text-xl font-bold tracking-wide border-b border-gray-700">
      Herald
    </div>
    <nav class="flex-1 px-3 py-4 space-y-1">
      <RouterLink
        v-for="item in navItems"
        :key="item.to"
        :to="item.to"
        class="flex items-center gap-3 px-3 py-2 rounded-md text-sm font-medium hover:bg-gray-800 transition-colors"
        active-class="bg-gray-800 text-white"
      >
        <span v-html="item.icon" class="w-5 text-center" />
        {{ item.label }}
      </RouterLink>
    </nav>
  </aside>
</template>
