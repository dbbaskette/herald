import { describe, it, expect } from 'vitest'
import { router } from '../src/router'

describe('router', () => {
  const routes = router.getRoutes()

  it('registers every console route', () => {
    expect(routes).toHaveLength(8)
  })

  it.each([
    ['/', 'status'],
    ['/chat', 'chat'],
    ['/skills', 'skills'],
    ['/prompts', 'prompts'],
    ['/memory', 'memory'],
    ['/cron', 'cron'],
    ['/history', 'history'],
    ['/settings', 'settings'],
  ])('resolves %s to route named %s', (path, name) => {
    const resolved = router.resolve(path)
    expect(resolved.name).toBe(name)
  })

  it('resolves each route to a component', () => {
    for (const route of routes) {
      expect(route.components?.default).toBeDefined()
    }
  })
})
