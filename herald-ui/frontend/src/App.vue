<script setup lang="ts">
import SidebarNav from './components/SidebarNav.vue'
</script>

<template>
  <div class="app-shell">
    <SidebarNav />
    <main class="app-content">
      <router-view />
    </main>
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
  padding: 28px 32px;
}
</style>
