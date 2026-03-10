import { defineStore } from 'pinia'
import { ref, computed } from 'vue'

export interface Skill {
  name: string
  description: string
  source: string
  readOnly: boolean
  content?: string
}

export const useSkillsStore = defineStore('skills', () => {
  const skills = ref<Skill[]>([])
  const loading = ref(false)
  const selectedSkill = ref<Skill | null>(null)
  const editorContent = ref('')
  const lastSavedContent = ref('')
  const isSaving = ref(false)

  const isDirty = computed(() => editorContent.value !== lastSavedContent.value)

  async function fetchSkills() {
    loading.value = true
    try {
      const res = await fetch('/api/skills')
      if (!res.ok) throw new Error(res.statusText)
      skills.value = await res.json()
    } catch {
      skills.value = []
    } finally {
      loading.value = false
    }
  }

  async function selectSkill(name: string): Promise<boolean> {
    try {
      const res = await fetch(`/api/skills/${encodeURIComponent(name)}`)
      if (!res.ok) throw new Error(res.statusText)
      const content = await res.text()
      const skill = skills.value.find(s => s.name === name) ?? null
      selectedSkill.value = skill
      editorContent.value = content
      lastSavedContent.value = content
      return true
    } catch {
      return false
    }
  }

  async function saveSkill(): Promise<boolean> {
    if (!selectedSkill.value) return false
    isSaving.value = true
    try {
      const res = await fetch(`/api/skills/${encodeURIComponent(selectedSkill.value.name)}`, {
        method: 'PUT',
        headers: { 'Content-Type': 'text/plain' },
        body: editorContent.value,
      })
      if (!res.ok) throw new Error(res.statusText)
      lastSavedContent.value = editorContent.value
      return true
    } catch {
      return false
    } finally {
      isSaving.value = false
    }
  }

  async function createSkill(name: string): Promise<boolean> {
    try {
      const res = await fetch('/api/skills', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ name }),
      })
      if (!res.ok) throw new Error(res.statusText)
      await fetchSkills()
      await selectSkill(name)
      return true
    } catch {
      return false
    }
  }

  async function deleteSkill(name: string): Promise<boolean> {
    try {
      const res = await fetch(`/api/skills/${encodeURIComponent(name)}`, {
        method: 'DELETE',
      })
      if (!res.ok) throw new Error(res.statusText)
      skills.value = skills.value.filter(s => s.name !== name)
      if (selectedSkill.value?.name === name) {
        selectedSkill.value = null
        editorContent.value = ''
        lastSavedContent.value = ''
      }
      return true
    } catch {
      return false
    }
  }

  return {
    skills,
    loading,
    selectedSkill,
    editorContent,
    lastSavedContent,
    isDirty,
    isSaving,
    fetchSkills,
    selectSkill,
    saveSkill,
    createSkill,
    deleteSkill,
  }
})
