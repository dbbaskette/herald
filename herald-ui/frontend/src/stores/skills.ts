import { defineStore } from 'pinia'
import { ref } from 'vue'

export interface Skill {
  id: string
  name: string
  enabled: boolean
}

export const useSkillsStore = defineStore('skills', () => {
  const skills = ref<Skill[]>([])
  const loading = ref(false)

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

  return { skills, loading, fetchSkills }
})
