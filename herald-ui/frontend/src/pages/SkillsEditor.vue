<script setup lang="ts">
import { onMounted } from 'vue'
import { useSkillsStore } from '../stores/skills'

const store = useSkillsStore()

onMounted(() => {
  store.fetchSkills()
})

function handleSelect(name: string, readOnly: boolean) {
  if (readOnly) return
  store.selectSkill(name)
}
</script>

<template>
  <div class="flex h-full">
    <!-- Left panel: file tree -->
    <div class="w-64 border-r border-gray-200 overflow-y-auto bg-gray-50 flex-shrink-0">
      <div class="px-4 py-3 border-b border-gray-200">
        <h2 class="text-sm font-semibold text-gray-700 uppercase tracking-wide">Skills</h2>
      </div>

      <div v-if="store.loading" class="px-4 py-3 text-sm text-gray-500">Loading…</div>

      <ul v-else class="py-1">
        <li
          v-for="skill in store.skills"
          :key="skill.name"
          class="px-3 py-2 mx-1 rounded-md text-sm cursor-pointer transition-colors"
          :class="[
            store.selectedSkill?.name === skill.name
              ? 'bg-gray-200 text-gray-900'
              : 'hover:bg-gray-100 text-gray-700',
            skill.readOnly ? 'cursor-default opacity-75' : '',
          ]"
          @click="handleSelect(skill.name, skill.readOnly)"
        >
          <div class="flex items-center gap-2">
            <span class="font-medium truncate">{{ skill.name }}</span>
            <svg
              v-if="skill.readOnly"
              class="w-3.5 h-3.5 text-gray-400 flex-shrink-0"
              fill="none"
              stroke="currentColor"
              stroke-width="2"
              viewBox="0 0 24 24"
            >
              <rect x="3" y="11" width="18" height="11" rx="2" ry="2" />
              <path d="M7 11V7a5 5 0 0 1 10 0v4" />
            </svg>
          </div>
          <div v-if="skill.description" class="text-xs text-gray-500 truncate mt-0.5">
            {{ skill.description }}
          </div>
        </li>
      </ul>
    </div>

    <!-- Right panel: placeholder for editor (next issue) -->
    <div class="flex-1 p-6">
      <div v-if="!store.selectedSkill" class="text-gray-500">
        <h1 class="text-2xl font-bold text-gray-900 mb-4">Skills Editor</h1>
        <p>Select a skill from the list to view or edit its content.</p>
      </div>
      <div v-else>
        <h1 class="text-2xl font-bold text-gray-900 mb-4">{{ store.selectedSkill.name }}</h1>
        <p class="text-sm text-gray-500 mb-4">{{ store.selectedSkill.description }}</p>
        <pre class="bg-gray-100 rounded-md p-4 text-sm whitespace-pre-wrap overflow-auto">{{ store.editorContent }}</pre>
      </div>
    </div>
  </div>
</template>
