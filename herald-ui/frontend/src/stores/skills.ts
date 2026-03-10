import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export interface SkillDetail {
  name: string
  content: string
}

export const useSkillsStore = defineStore('skills', () => {
  const skillNames = ref<string[]>([])
  const selectedName = ref<string | null>(null)
  const editorContent = ref('')
  const savedContent = ref('')
  const loading = ref(false)
  const saving = ref(false)
  const isDirty = computed(() => editorContent.value !== savedContent.value)
  const error = ref<string | null>(null)

  async function fetchSkills() {
    loading.value = true
    error.value = null
    try {
      const res = await fetch('/api/skills')
      if (!res.ok) throw new Error(res.statusText)
      skillNames.value = await res.json()
    } catch (e: any) {
      error.value = e.message
      skillNames.value = []
    } finally {
      loading.value = false
    }
  }

  async function selectSkill(name: string) {
    loading.value = true
    error.value = null
    try {
      const res = await fetch(`/api/skills/${encodeURIComponent(name)}`)
      if (!res.ok) throw new Error(res.statusText)
      const detail: SkillDetail = await res.json()
      selectedName.value = detail.name
      editorContent.value = detail.content
      savedContent.value = detail.content
    } catch (e: any) {
      error.value = e.message
    } finally {
      loading.value = false
    }
  }

  async function saveSkill(): Promise<boolean> {
    if (!selectedName.value || !isDirty.value) return false
    saving.value = true
    error.value = null
    try {
      const res = await fetch(`/api/skills/${encodeURIComponent(selectedName.value)}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name: selectedName.value, content: editorContent.value }),
      })
      if (!res.ok) throw new Error(res.statusText)
      savedContent.value = editorContent.value
      return true
    } catch (e: any) {
      error.value = e.message
      return false
    } finally {
      saving.value = false
    }
  }

  async function createSkill(name: string): Promise<boolean> {
    error.value = null
    try {
      const res = await fetch('/api/skills', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name, content: `---\nname: ${name}\ndescription: \"\"\n---\n\n` }),
      })
      if (!res.ok) {
        const text = await res.text()
        throw new Error(res.status === 409 ? 'Skill already exists' : text || res.statusText)
      }
      await fetchSkills()
      await selectSkill(name)
      return true
    } catch (e: any) {
      error.value = e.message
      return false
    }
  }

  async function deleteSkill(name: string): Promise<boolean> {
    error.value = null
    try {
      const res = await fetch(`/api/skills/${encodeURIComponent(name)}`, { method: 'DELETE' })
      if (!res.ok) throw new Error(res.statusText)
      if (selectedName.value === name) {
        selectedName.value = null
        editorContent.value = ''
        savedContent.value = ''
      }
      await fetchSkills()
      return true
    } catch (e: any) {
      error.value = e.message
      return false
    }
  }

  function discardChanges() {
    editorContent.value = savedContent.value
  }

  return {
    skillNames, selectedName, editorContent, savedContent,
    loading, saving, isDirty, error,
    fetchSkills, selectSkill, saveSkill, createSkill, deleteSkill, discardChanges,
  }
})
