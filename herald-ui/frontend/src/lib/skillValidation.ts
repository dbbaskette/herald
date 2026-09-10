import { LineCounter, parseDocument } from 'yaml'
export interface SkillDiagnostic { severity: 'error' | 'warning'; code: string; message: string; line: number; column: number }
export interface SkillValidation { valid: boolean; name: string; description: string; diagnostics: SkillDiagnostic[]; capabilityStatus: string; vaultStatus: string }
export function validateFrontmatter(content: string): SkillValidation {
  const result: SkillValidation = { valid: true, name: '', description: '', diagnostics: [], capabilityStatus: 'unchecked', vaultStatus: 'unchecked' }
  const add = (code: string, message: string, line = 1, column = 1) => {
    result.valid = false
    result.diagnostics.push({ severity: 'error', code, message, line, column })
  }
  const lines = content.split(/\r?\n/)
  if (new TextEncoder().encode(content).length > 256 * 1024) { add('size', 'Skill content must be at most 256 KiB.'); return result }
  if (lines[0] !== '---') { add('frontmatter', 'Start with a YAML frontmatter delimiter (---).'); return result }
  const end = lines.findIndex((line, i) => i > 0 && line === '---')
  if (end < 0) { add('frontmatter', 'Missing closing frontmatter delimiter (---).', lines.length); return result }
  const counter = new LineCounter()
  const doc = parseDocument(lines.slice(1, end).join('\n'), { lineCounter: counter, uniqueKeys: true, schema: 'yaml-1.1' })
  for (const error of doc.errors) {
    const pos = counter.linePos(error.pos[0])
    add('yaml', error.message.split('\n')[0]!.replace(/ at line \d+, column \d+:?$/, ''), pos.line + 1, pos.col)
  }
  if (!result.valid) return result
  try {
    const parsed = doc.toJS({ maxAliasCount: 20 })
    if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) { add('yaml', 'Frontmatter must contain key: value fields.', 2); return result }
    for (const key of ['name', 'description'] as const) {
      if (typeof parsed[key] !== 'string' || !parsed[key].trim()) {
        const line = lines.findIndex((text, i) => i > 0 && i < end && new RegExp(`^${key}\\s*:`).test(text))
        add(`required-${key}`, `${key} must be a non-empty string.`, line < 0 ? 2 : line + 1)
      } else result[key] = parsed[key].trim()
    }
  } catch { add('yaml', 'Invalid or excessively complex YAML.', 2) }
  return result
}
