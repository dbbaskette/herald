<script setup lang="ts">
import { RouterLink } from 'vue-router'

declare const __APP_VERSION__: string
const appVersion = __APP_VERSION__

// Character glyphs that read as native to a monospaced UI.
const navItems = [
  { to: '/',         label: 'Status',   glyph: '●', exact: true  },
  { to: '/chat',     label: 'Chat',     glyph: '◇', exact: false },
  { to: '/skills',   label: 'Skills',   glyph: '⊞', exact: false },
  { to: '/prompts',  label: 'Prompts',  glyph: '⌖', exact: false },
  { to: '/memory',   label: 'Memory',   glyph: '▤', exact: false },
  { to: '/cron',     label: 'Cron',     glyph: '⊡', exact: false },
  { to: '/history',  label: 'History',  glyph: '∷', exact: false },
]
</script>

<template>
  <aside class="sidebar">
    <div class="sidebar-header">
      <span class="brand-mark">H</span>
      <span class="brand-name">Herald</span>
    </div>

    <nav class="sidebar-nav" aria-label="Primary">
      <RouterLink
        v-for="item in navItems"
        :key="item.to"
        :to="item.to"
        class="nav-item"
        :active-class="item.exact ? '' : 'nav-item--active'"
        :exact-active-class="item.exact ? 'nav-item--active' : ''"
      >
        <span class="nav-glyph" aria-hidden="true">{{ item.glyph }}</span>
        <span class="nav-label">{{ item.label }}</span>
      </RouterLink>
    </nav>

    <div class="sidebar-footer">
      <RouterLink to="/settings" class="nav-item" active-class="nav-item--active">
        <span class="nav-glyph" aria-hidden="true">⚙</span>
        <span class="nav-label">Settings</span>
      </RouterLink>
      <div class="sidebar-version">v{{ appVersion }}</div>
    </div>
  </aside>
</template>

<style scoped>
.sidebar {
  width: 200px;
  min-width: 200px;
  background: var(--sidebar);
  display: flex;
  flex-direction: column;
  border-right: 1px solid rgba(220, 215, 200, 0.08);
}

/* ─── Brand ─────────────────────────────────────────────────────────── */
.sidebar-header {
  display: flex;
  align-items: baseline;
  gap: 10px;
  padding: 22px 18px 18px;
  border-bottom: 1px solid rgba(220, 215, 200, 0.06);
}

.brand-mark {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 22px;
  height: 22px;
  background: var(--gold);
  color: var(--ink);
  font-family: 'IBM Plex Serif', Georgia, serif;
  font-size: 0.875rem;
  font-weight: 600;
  border-radius: 2px;
  /* Optical alignment for the H */
  padding-top: 1px;
}

.brand-name {
  font-family: 'IBM Plex Serif', Georgia, serif;
  font-size: 1.0625rem;
  font-weight: 500;
  letter-spacing: 0.02em;
  color: var(--sidebar-text-active);
}

/* ─── Nav items ─────────────────────────────────────────────────────── */
.sidebar-nav {
  flex: 1;
  padding: 14px 0;
  display: flex;
  flex-direction: column;
  gap: 0;
}

.nav-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 8px 18px 8px 20px;
  font-size: 0.9375rem;
  font-weight: 400;
  color: var(--sidebar-text);
  text-decoration: none;
  border-left: 2px solid transparent;
  transition: color 100ms, background 100ms, border-color 100ms;
}

.nav-item:hover {
  color: var(--sidebar-text-active);
  background: var(--sidebar-hover);
}

.nav-item--active {
  color: var(--sidebar-text-active);
  border-left-color: var(--gold);
  background: var(--sidebar-hover);
  font-weight: 500;
}

.nav-glyph {
  display: inline-flex;
  justify-content: center;
  width: 16px;
  font-size: 1rem;
  color: var(--sidebar-text);
  opacity: 0.8;
  transition: color 100ms, opacity 100ms;
}

.nav-item:hover .nav-glyph,
.nav-item--active .nav-glyph {
  color: var(--gold);
  opacity: 1;
}

.nav-label {
  letter-spacing: 0.01em;
}

/* ─── Footer ────────────────────────────────────────────────────────── */
.sidebar-footer {
  padding: 14px 0 12px;
  border-top: 1px solid rgba(161, 161, 170, 0.10);
}

.sidebar-version {
  padding: 8px 20px 0;
  font-size: 0.6875rem;
  color: rgba(161, 161, 170, 0.45);
  letter-spacing: 0.06em;
  font-variant-numeric: tabular-nums;
}
</style>
