# Task 2 report: frontend dependency family

## Status

DONE_WITH_CONCERNS

## Implemented

- Updated every direct frontend package to the 2026-09-09 stable target, except TypeScript. TypeScript is pinned to the latest compatible stable 6.x release (6.0.3) because `vue-tsc` 3.3.11 cannot run against TypeScript 7.0.2; TypeScript 7 removes the compiler API/export used by Vue tooling. The normal `vue-tsc -b && vite build` validation remains intact.
- Moved `@types/markdown-it` to development dependencies.
- Added the tested Node pin (`.nvmrc`: 22.23.1) and the actual dependency-engine intersection (`^22.22.2 || ^24.15.0 || >=26`).
- Migrated Tailwind 3/PostCSS to Tailwind 4's `@tailwindcss/vite` integration. Removed `postcss.config.js`, `postcss`, and `autoprefixer`; converted CSS directives to `@config` plus `@import`; replaced custom-class `@apply` with grouped selectors/explicit declarations while retaining the existing tokens and theme.
- Updated the Vite config to `import.meta.dirname` for native-config compatibility.
- Refreshed obsolete router, API-shape, label, selector, async, CodeMirror, and EventSource test fixtures for Vue Router 5/Vitest 5/current UI behavior without removing tests.
- Added direct compatibility coverage for real CodeMirror markdown state, MergeView behavior/API availability, and markdown-it rendering/HTML escaping. The real MergeView regression test proves reverting copies the bundled baseline into the editable pane, emits the modified content, preserves the original pane, and hides mutation controls for read-only diffs. Existing store tests cover real SSE event handling and router tests cover all eight routes.
- Fixed invalid nested buttons in the conversation list (now a keyboard-operable list item containing its delete button), removing the Vue 3.5 compiler warning.
- Added an accessible label/title and a read-only guard to the skill delete icon, preserving the intended bundled-skill behavior.

## Versions

Runtime: `@codemirror/commands` 6.11.0, `@codemirror/lang-markdown` 6.5.2, `@codemirror/lang-yaml` 6.1.3, `@codemirror/language` 6.12.4, `@codemirror/merge` 6.12.2, `@codemirror/state` 6.7.4, `@codemirror/theme-one-dark` 6.1.3, `@codemirror/view` 6.43.11, `codemirror` 6.0.2, `markdown-it` 15.0.1, `pinia` 4.0.3, `vue` 3.5.42, `vue-router` 5.3.1.

Development: `@tailwindcss/vite` 4.3.3, `@types/markdown-it` 14.2.0, `@vitejs/plugin-vue` 6.0.8, `@vue/test-utils` 2.5.0, `jsdom` 30.0.1, `tailwindcss` 4.3.3, `typescript` 6.0.3, `vite` 8.2.2, `vitest` 5.0.0, `vue-tsc` 3.3.11.

## Verification

- `PATH=/Users/dbbaskette/.nvm/versions/node/v22.23.1/bin:/usr/bin:/bin:/usr/sbin:/sbin npm install` — success; 206 packages audited, 0 vulnerabilities.
- `PATH=... npm test > /tmp/herald-upgraded-frontend-tests.log 2>&1` — 14/14 files and 128/128 tests passed; output pristine.
- `PATH=... npm run build -- --outDir /tmp/herald-upgraded-frontend-build > /tmp/herald-upgraded-frontend-build.log 2>&1` — success; vue-tsc and Vite transformed 156 modules and produced the fixture-ready build.
- `PATH=... npm ls --all > /tmp/herald-upgraded-npm-ls.log 2>&1` — success; dependency tree valid with no missing, invalid, or extraneous packages.
- `PATH=... npm audit --json > /tmp/herald-upgraded-npm-audit.json` — success; 0 production or development vulnerabilities.
- `PATH=... npm audit --omit=dev --json > /tmp/herald-upgraded-npm-audit-runtime.json` — success; 0 runtime vulnerabilities.
- `git diff --check` — success.
- Parent fixture-browser verification against the upgraded build covered status, skill editor, prompt diff, navigation among these pages, and chat chunk/done SSE with rendered bold/code/link/list; browser error logs were empty. Status typography and layout matched baseline. Tailwind 4 now consistently exposes existing semantic card variants (green/blue/cyan), which matches the source design comments and configured theme.

## Files changed

`.nvmrc`; frontend `package.json`, `package-lock.json`, `vite.config.ts`, deleted `postcss.config.js`, `src/assets/main.css`, `src/components/ConversationList.vue`, `src/pages/SkillsEditor.vue`, affected page/store/router specs, and `tests/dependency-compatibility.spec.ts`.

## Self-review and concerns

- Reviewed the full diff and confirmed no Java, README, install script, GitHub, or maintenance-doc files are included in this task's commit.
- Build emits two non-failing pre-existing optimization warnings: approvals is both statically and dynamically imported, and the main JS chunk is about 848 kB (about 302 kB gzip). These do not affect compatibility or runtime behavior but remain opportunities for later code splitting.
- TypeScript 7.0.2 is intentionally deferred until Vue's language tooling supports its compiler architecture. All other requested stable targets are installed exactly.

## Review fix round 1

- Replaced the ARIA `role="button"` conversation row containing a native delete button with a neutral row containing sibling native select and delete buttons. Both controls retain independent focus, keyboard activation, hover/focus visibility, and actions without nested interactive semantics.
- Added `src/components/ConversationList.spec.ts`, which asserts there are no nested buttons, both controls are native buttons, selecting calls only the conversation switch, and deleting opens confirmation and calls only conversation deletion.
- `PATH=... npx vitest run src/components/ConversationList.spec.ts` — 1/1 test passed.
- `PATH=... npm test > /tmp/herald-upgraded-frontend-tests.log 2>&1` — 15/15 files and 129/129 tests passed; output pristine.
- `PATH=... npm run build > /tmp/herald-upgraded-frontend-build-default.log 2>&1` — success; vue-tsc and Vite transformed 156 modules and refreshed the default packaged assets.
- `PATH=... npm run build -- --outDir /tmp/herald-upgraded-frontend-build > /tmp/herald-upgraded-frontend-build.log 2>&1` — success; refreshed isolated fixture build. Both builds retain the two previously documented optimization warnings.
