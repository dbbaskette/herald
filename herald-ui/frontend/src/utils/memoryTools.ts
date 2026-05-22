/** Extract a memory file path from a memory tool call's arguments. */
export function extractMemoryPath(
  toolName: string,
  argsOrInputs: string | Record<string, unknown> | null | undefined
): string | null {
  if (!toolName || !/^memory/i.test(toolName)) return null

  let parsed: Record<string, unknown> | null = null
  if (typeof argsOrInputs === 'string') {
    try {
      parsed = JSON.parse(argsOrInputs) as Record<string, unknown>
    } catch {
      return null
    }
  } else if (argsOrInputs && typeof argsOrInputs === 'object') {
    parsed = argsOrInputs
  }
  if (!parsed) return null

  const path = parsed.path ?? parsed.old_path ?? parsed.new_path
  return typeof path === 'string' && path.trim() ? path.trim() : null
}

export function memoryViewerRoute(path: string): { path: string; query: { tab: string; path: string } } {
  return { path: '/memory', query: { tab: 'wiki', path } }
}
