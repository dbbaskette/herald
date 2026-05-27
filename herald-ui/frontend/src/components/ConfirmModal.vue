<script setup lang="ts">
defineProps<{
  open: boolean
  title: string
  message: string
  confirmLabel?: string
  cancelLabel?: string
  danger?: boolean
}>()

const emit = defineEmits<{
  (e: 'confirm'): void
  (e: 'cancel'): void
}>()
</script>

<template>
  <Transition name="modal">
    <div v-if="open" class="modal-overlay" @click.self="emit('cancel')">
      <div class="modal-card" :class="{ 'modal-card-danger': danger }">
        <div class="modal-header">
          <h2>{{ title }}</h2>
        </div>
        <div class="modal-body">
          <p class="modal-message">{{ message }}</p>
        </div>
        <div class="modal-footer">
          <button class="btn-secondary" @click="emit('cancel')">
            {{ cancelLabel || 'Cancel' }}
          </button>
          <button
            class="btn-primary"
            :class="{ 'btn-danger': danger }"
            @click="emit('confirm')"
          >
            {{ confirmLabel || 'Confirm' }}
          </button>
        </div>
      </div>
    </div>
  </Transition>
</template>

<style scoped>
.modal-overlay {
  position: fixed;
  inset: 0;
  background: rgba(20, 22, 23, 0.55);
  backdrop-filter: blur(2px);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 24px;
}

.modal-card {
  width: min(420px, 100%);
  background: var(--color-surface-raised);
  border: 1px solid var(--color-border);
  border-radius: 12px;
  box-shadow: 0 24px 64px rgba(0, 0, 0, 0.18);
  overflow: hidden;
}

.modal-header {
  padding: 18px 20px 0;
}

.modal-header h2 {
  margin: 0;
  font-size: 1rem;
  font-weight: 600;
  color: var(--color-text-primary);
}

.modal-body {
  padding: 12px 20px 4px;
}

.modal-message {
  margin: 0;
  font-size: 0.875rem;
  line-height: 1.5;
  color: var(--color-text-secondary);
}

.modal-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  padding: 16px 20px 18px;
}

.btn-danger {
  background: #dc2626 !important;
}

.btn-danger:hover:not(:disabled) {
  background: #b91c1c !important;
}

.modal-enter-active,
.modal-leave-active {
  transition: opacity 0.15s;
}

.modal-enter-from,
.modal-leave-to {
  opacity: 0;
}
</style>
