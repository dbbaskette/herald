import { spawnSync } from 'node:child_process'
import { rmSync } from 'node:fs'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const root = dirname(dirname(fileURLToPath(import.meta.url)))
const [major, minor, patch] = process.versions.node.split('.').map(Number)
const supported = (major === 22 && (minor > 22 || (minor === 22 && patch >= 2)))
  || (major === 24 && minor >= 15) || major >= 26
if (!supported) throw new Error(`Unsupported Node ${process.versions.node}. Use Node 22.23.1 (see .nvmrc).`)
const npm = process.platform === 'win32' ? 'npm.cmd' : 'npm'
function run(...args) {
  const result = spawnSync(npm, args, { cwd: root, stdio: 'inherit', shell: process.platform === 'win32' })
  if (result.error) throw result.error
  if (result.status !== 0) process.exit(result.status ?? 1)
}
switch (process.argv[2]) {
  case 'resources':
    // A non-clean Maven package must not retain assets deleted by a newer Vite build.
    rmSync(resolve(root, '../target/classes/static'), { recursive: true, force: true })
    run('ci', '--include=dev', '--include=optional', '--no-audit', '--no-fund')
    run('run', 'test:typecheck')
    run('run', 'build')
    break
  case 'test':
    if (process.argv[3] !== 'true' && process.argv[4] !== 'true') run('test')
    break
  default: throw new Error('Expected resources or test build phase')
}
