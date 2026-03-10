import { createRouter, createWebHistory } from 'vue-router'
import SystemStatus from './pages/SystemStatus.vue'
import SkillsEditor from './pages/SkillsEditor.vue'
import MemoryViewer from './pages/MemoryViewer.vue'
import CronBuilder from './pages/CronBuilder.vue'
import ConversationHistory from './pages/ConversationHistory.vue'

const routes = [
  { path: '/', name: 'status', component: SystemStatus },
  { path: '/skills', name: 'skills', component: SkillsEditor },
  { path: '/memory', name: 'memory', component: MemoryViewer },
  { path: '/cron', name: 'cron', component: CronBuilder },
  { path: '/history', name: 'history', component: ConversationHistory },
]

export const router = createRouter({
  history: createWebHistory(),
  routes,
})
