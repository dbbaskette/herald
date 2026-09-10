import { execFileSync } from 'node:child_process'
import { mkdtempSync, readFileSync, writeFileSync, rmSync } from 'node:fs'
import { dirname, join, basename } from 'node:path'
import { fileURLToPath } from 'node:url'

// A compiler compatibility check must prove Vue template diagnostics still run.
const root = dirname(dirname(fileURLToPath(import.meta.url)))
const compiler = JSON.parse(readFileSync(join(root, 'node_modules/typescript/package.json'), 'utf8'))
if (compiler.name !== 'typescript-native-bridge' || !compiler.version.endsWith('.tsgo.7.0.2')) {
  throw new Error('Expected the pinned TypeScript 7.0.2 native bridge; verify compiler compatibility before changing it.')
}
const fixture = mkdtempSync(join(root, 'src/typecheck-probe-'))
const name = basename(fixture)
const check = () => execFileSync(process.execPath, [
  join(root, 'node_modules/vue-tsc/bin/vue-tsc.js'), '--noEmit', '--incremental', 'false',
], { cwd: root, encoding: 'utf8', timeout: 60000, stdio: ['ignore', 'pipe', 'pipe'] })
const component = expression => `<script setup lang="ts">\nimport { value } from '@/${name}/values'\n</script>\n<template>{{ ${expression} }}</template>\n`
try {
  writeFileSync(join(fixture, 'values.ts'), 'export const value: number = 1\n')
  writeFileSync(join(fixture, 'Probe.vue'), component('value.toFixed(2)'))
  check()
  writeFileSync(join(fixture, 'values.ts'), "export const value: number = 'invalid'\n")
  writeFileSync(join(fixture, 'Probe.vue'), component('value.nonexistent'))
  let diagnostics = ''
  try { check() } catch (error) {
    if (error.status !== 2) throw error
    diagnostics = String(error.stdout)
  }
  if (!diagnostics.includes(`${name}/values.ts`) || !diagnostics.includes('TS2322')
      || !diagnostics.includes(`${name}/Probe.vue`) || !diagnostics.includes('TS2339')) {
    throw new Error(`Expected TypeScript and Vue template errors were not reported:\n${diagnostics}`)
  }
  console.log('Typechecking verified: valid Vue passes; invalid TypeScript and Vue template fail.')
} finally {
  rmSync(fixture, { recursive: true, force: true })
}
