import { describe, it, expect } from 'vitest'
import { router } from '../src/router'

describe('router', () => {
  const routes = router.getRoutes()

  it('has 5 routes', () => {
    expect(routes).toHaveLength(5)
  })

  it.each([
    ['/', 'status'],
    ['/skills', 'skills'],
    ['/memory', 'memory'],
    ['/cron', 'cron'],
    ['/history', 'history'],
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
