<script setup lang="ts">
import SidebarNav from './components/SidebarNav.vue'
</script>

<template>
  <div class="flex h-screen bg-gray-100">
    <SidebarNav />
    <main class="flex-1 overflow-auto p-6">
      <router-view />
    </main>
  </div>
</template>
