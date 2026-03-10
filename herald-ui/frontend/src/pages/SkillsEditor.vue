<script setup lang="ts">
import { ref, watch, onMounted, onUnmounted, nextTick } from 'vue'
import { useSkillsStore } from '../stores/skills'
import { useSkillReloadSse } from '../composables/useSkillReloadSse'
import { EditorView, keymap } from '@codemirror/view'
import { EditorState } from '@codemirror/state'
import { markdown } from '@codemirror/lang-markdown'
import { yaml } from '@codemirror/lang-yaml'
import { oneDark } from '@codemirror/theme-one-dark'
import { defaultKeymap, history, historyKeymap } from '@codemirror/commands'
import { basicSetup } from 'codemirror'

const store = useSkillsStore()
const { status: reloadStatus, lastLoadedAt, setReloading } = useSkillReloadSse()

const editorContainer = ref<HTMLElement | null>(null)
let editorView: EditorView | null = null

const showNewSkillModal = ref(false)
const newSkillName = ref('')
const showDeleteConfirm = ref(false)

function createEditorState(content: string, readOnly: boolean) {
  return EditorState.create({
    doc: content,
    extensions: [
      basicSetup,
      markdown({ codeLanguages: [{ name: 'yaml', parser: yaml().language.parser }] }),
      oneDark,
      keymap.of([...defaultKeymap, ...historyKeymap]),
      history(),
      EditorView.updateListener.of((update) => {
        if (update.docChanged) {
          store.editorContent = update.state.doc.toString()
        }
      }),
      ...(readOnly ? [EditorState.readOnly.of(true), EditorView.editable.of(false)] : []),
    ],
  })
}

function mountEditor() {
  if (!editorContainer.value) return
  if (editorView) {
    editorView.destroy()
    editorView = null
  }
  const readOnly = store.selectedSkill?.readOnly ?? false
  editorView = new EditorView({
    state: createEditorState(store.editorContent, readOnly),
    parent: editorContainer.value,
  })
}

function syncEditorContent() {
  if (!editorView) return
  const currentDoc = editorView.state.doc.toString()
  if (currentDoc !== store.editorContent) {
    editorView.dispatch({
      changes: { from: 0, to: editorView.state.doc.length, insert: store.editorContent },
    })
  }
}

// Watch for skill selection changes — remount editor for readOnly changes
watch(
  () => store.selectedSkill?.name,
  async () => {
    await nextTick()
    mountEditor()
  },
)

function handleSelect(name: string) {
  store.selectSkill(name)
}

async function handleSave() {
  setReloading()
  await store.saveSkill()
}

function handleDiscard() {
  store.editorContent = store.lastSavedContent
  syncEditorContent()
}

async function handleCreateSkill() {
  const name = newSkillName.value.trim()
  if (!name) return
  setReloading()
  await store.createSkill(name)
  showNewSkillModal.value = false
  newSkillName.value = ''
}

async function handleDeleteSkill() {
  if (!store.selectedSkill) return
  setReloading()
  await store.deleteSkill(store.selectedSkill.name)
  showDeleteConfirm.value = false
  if (editorView) {
    editorView.destroy()
    editorView = null
  }
}

function formatTime(ts: string | null): string {
  if (!ts) return 'never'
  try {
    return new Date(ts).toLocaleString()
  } catch {
    return ts
  }
}

onMounted(() => {
  store.fetchSkills()
})

onUnmounted(() => {
  if (editorView) {
    editorView.destroy()
    editorView = null
  }
})
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
          @click="handleSelect(skill.name)"
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

    <!-- Right panel: editor -->
    <div class="flex-1 flex flex-col min-w-0">
      <!-- No skill selected -->
      <div v-if="!store.selectedSkill" class="flex-1 p-6">
        <h1 class="text-2xl font-bold text-gray-900 mb-4">Skills Editor</h1>
        <p class="text-gray-500">Select a skill from the list to view or edit its content.</p>
      </div>

      <template v-else>
        <!-- Toolbar -->
        <div class="flex items-center gap-2 px-4 py-2 border-b border-gray-200 bg-white">
          <h1 class="text-lg font-semibold text-gray-900 mr-auto truncate">
            {{ store.selectedSkill.name }}
          </h1>

          <!-- SSE skill-reload status chip -->
          <span
            class="inline-flex items-center gap-1.5 text-xs font-medium px-2 py-0.5 rounded-full"
            :class="{
              'bg-green-100 text-green-700': reloadStatus === 'loaded',
              'bg-yellow-100 text-yellow-700': reloadStatus === 'reloading',
              'bg-red-100 text-red-700': reloadStatus === 'error',
            }"
          >
            <span
              class="inline-block w-1.5 h-1.5 rounded-full"
              :class="{
                'bg-green-500': reloadStatus === 'loaded',
                'bg-yellow-500': reloadStatus === 'reloading',
                'bg-red-500': reloadStatus === 'error',
              }"
            />
            {{ reloadStatus === 'loaded' ? 'Loaded' : reloadStatus === 'reloading' ? 'Reloading' : 'Error' }}
          </span>

          <span
            v-if="lastLoadedAt"
            class="text-xs text-gray-400 hidden sm:inline"
          >
            Last loaded: {{ formatTime(lastLoadedAt) }}
          </span>

          <div class="w-px h-5 bg-gray-200 mx-1" />

          <button
            class="px-3 py-1.5 text-sm font-medium rounded-md transition-colors"
            :class="
              !store.isDirty || store.isSaving || store.selectedSkill.readOnly
                ? 'bg-gray-100 text-gray-400 cursor-not-allowed'
                : 'bg-blue-600 text-white hover:bg-blue-700'
            "
            :disabled="!store.isDirty || store.isSaving || store.selectedSkill.readOnly"
            @click="handleSave"
          >
            {{ store.isSaving ? 'Saving…' : 'Save' }}
          </button>

          <button
            class="px-3 py-1.5 text-sm font-medium rounded-md transition-colors"
            :class="
              !store.isDirty || store.selectedSkill.readOnly
                ? 'bg-gray-100 text-gray-400 cursor-not-allowed'
                : 'bg-gray-100 text-gray-700 hover:bg-gray-200'
            "
            :disabled="!store.isDirty || store.selectedSkill.readOnly"
            @click="handleDiscard"
          >
            Discard
          </button>

          <button
            class="px-3 py-1.5 text-sm font-medium rounded-md bg-gray-100 text-gray-700 hover:bg-gray-200 transition-colors"
            @click="showNewSkillModal = true"
          >
            New Skill
          </button>

          <button
            class="px-3 py-1.5 text-sm font-medium rounded-md transition-colors"
            :class="
              store.selectedSkill.readOnly
                ? 'bg-gray-100 text-gray-400 cursor-not-allowed'
                : 'bg-red-50 text-red-600 hover:bg-red-100'
            "
            :disabled="store.selectedSkill.readOnly"
            @click="showDeleteConfirm = true"
          >
            Delete
          </button>
        </div>

        <!-- Read-only badge -->
        <div
          v-if="store.selectedSkill.readOnly"
          class="px-4 py-1.5 bg-yellow-50 border-b border-yellow-100 text-xs text-yellow-700"
        >
          This is a bundled skill and cannot be edited.
        </div>

        <!-- CodeMirror editor -->
        <div ref="editorContainer" class="flex-1 overflow-auto" />
      </template>

      <!-- New Skill Modal -->
      <div
        v-if="showNewSkillModal"
        class="fixed inset-0 bg-black/40 flex items-center justify-center z-50"
        @click.self="showNewSkillModal = false"
      >
        <div class="bg-white rounded-lg shadow-lg p-6 w-full max-w-sm">
          <h2 class="text-lg font-semibold text-gray-900 mb-4">New Skill</h2>
          <label class="block text-sm font-medium text-gray-700 mb-1">Skill name</label>
          <input
            v-model="newSkillName"
            type="text"
            class="w-full border border-gray-300 rounded-md px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
            placeholder="my-skill"
            pattern="[a-zA-Z0-9_-]+"
            @keydown.enter="handleCreateSkill"
          />
          <p class="text-xs text-gray-400 mt-1">Letters, numbers, hyphens, and underscores only.</p>
          <div class="flex justify-end gap-2 mt-4">
            <button
              class="px-3 py-1.5 text-sm font-medium rounded-md bg-gray-100 text-gray-700 hover:bg-gray-200"
              @click="showNewSkillModal = false"
            >
              Cancel
            </button>
            <button
              class="px-3 py-1.5 text-sm font-medium rounded-md bg-blue-600 text-white hover:bg-blue-700 disabled:opacity-50"
              :disabled="!newSkillName.trim()"
              @click="handleCreateSkill"
            >
              Create
            </button>
          </div>
        </div>
      </div>

      <!-- Delete Confirmation Modal -->
      <div
        v-if="showDeleteConfirm"
        class="fixed inset-0 bg-black/40 flex items-center justify-center z-50"
        @click.self="showDeleteConfirm = false"
      >
        <div class="bg-white rounded-lg shadow-lg p-6 w-full max-w-sm">
          <h2 class="text-lg font-semibold text-gray-900 mb-2">Delete Skill</h2>
          <p class="text-sm text-gray-600 mb-4">
            Are you sure you want to delete
            <strong>{{ store.selectedSkill?.name }}</strong>? This cannot be undone.
          </p>
          <div class="flex justify-end gap-2">
            <button
              class="px-3 py-1.5 text-sm font-medium rounded-md bg-gray-100 text-gray-700 hover:bg-gray-200"
              @click="showDeleteConfirm = false"
            >
              Cancel
            </button>
            <button
              class="px-3 py-1.5 text-sm font-medium rounded-md bg-red-600 text-white hover:bg-red-700"
              @click="handleDeleteSkill"
            >
              Delete
            </button>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
