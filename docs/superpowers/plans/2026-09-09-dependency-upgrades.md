# Combined dependency upgrades — #385 and #386

> **For agentic workers:** Use superpowers:subagent-driven-development or superpowers:executing-plans to implement this plan task by task.

**Goal:** Bring Herald's Java and Vue dependency families to current stable releases with verified compatibility.

**Architecture:** Preserve the existing five-module application and console behavior. Align SDK/BOM families and migrate frontend build integration where a library major requires it. Treat pre-existing frontend failures as a separate baseline, repairing obsolete test fixtures as necessary to make upgrade verification meaningful.

**Tech Stack:** Java 21, Spring Boot 4.1.1, Spring AI 2.0.1, Agent Utils 0.12.0, Vue 3.5, Vite 8, Tailwind 4, Vitest 5, Node 22.23.1.

**Spec:** GitHub issues https://github.com/dbbaskette/herald/issues/385 and https://github.com/dbbaskette/herald/issues/386.

## Global constraints

- Stable releases only; verify actual published metadata and family alignment.
- Work in `/private/tmp/herald-upgrades-385-386` on `codex/dependency-upgrades-385-386`.
- Baseline main `9b48447`: Maven verify passes 773 tests; frontend builds; frontend suite has 44 failures/76 passes.
- Preserve audit artifacts in the original checkout. No runtime credentials, personal data or live-service configuration changes.
- User testing cadence: coherent increments, targeted milestones, full relevant suites before completion; no strict TDD requirement.
- Do not merge or deploy. Keep unrelated UX work in its existing issues.

## Task 1: Backend dependency family

Files: parent/module POMs and compatibility changes/tests within Java modules.

- [x] Upgrade Boot to 4.1.1, AI to 2.0.1, Agent Utils/A2A together to 0.12.0, Telegram to 10.1.0, WireMock stable 3.13.2 and dependency plugin 3.11.0.
- [x] Resolve MCP from the AI BOM rather than the 1.0.0 override. Inspect dependency tree for OpenAI SDK and Jackson/victools, align the OpenAI family, retain victools only if needed. Remove obsolete milestone/snapshot repositories after verifying stable resolution.
- [x] Run `./mvnw -DskipTests package` to diagnose compile/API migration boundaries, then appropriate compatibility tests and full `./mvnw verify` after the slice is complete.
- [x] Verify tool schema generation, memory tools, Gemini thought signature, Telegram behavior and an isolated MCP fixture. Record any external smoke prerequisites explicitly.

## Task 2: Frontend dependency family

Files: `herald-ui/frontend/package.json`, lockfile, Vite/PostCSS/Tailwind config, CSS, affected tests and TypeScript compatibility.

- [x] Update all direct packages to registry stable targets from the audit; move markdown-it types to development dependencies and document Node constraints.
- [x] Migrate Tailwind to its Vite integration and CSS imports, preserving the existing theme. Remove replaced PostCSS/autoprefixer dependencies if unused. Resolve custom-class `@apply` migration explicitly.
- [x] Install with npm to regenerate lockfile, then run typecheck/build and full frontend tests. Diagnose changed major-version APIs and obsolete fixtures; preserve meaningful behavior checks.
- [x] Re-run npm audit, inspect remaining runtime versus dev advisories, and verify route/editor/markdown/SSE flows with fixtures. Render console screens to catch CSS regressions.

## Task 3: Integration, maintenance and delivery

Files: README/toolchain docs, grouped dependency update configuration, verification notes.

- [x] Document supported Java/Node/browser baseline, release targets and any justified compatibility deferrals.
- [x] Add grouped update automation so coupled dependencies move together, without auto-merge.
- [x] Review the combined diff and independently review compatibility risks. Run full relevant suites on final code, inspect clean dependency trees and refreshed audit results.
- [x] Commit the combined change on the isolated branch and prepare a reviewable result linked to both issues. Keep issues open until integration state justifies closure.

## Compatibility decisions

- TypeScript 6.0.3 is the current compatible choice for vue-tsc 3.3.11; TypeScript 7.0.2 is deferred following Microsoft's Vue guidance.
- OpenAI, MCP and SQLite use the tested framework-managed alignment documented in `docs/maintenance/dependencies.md`.
- Editor verification uncovered an existing reversed diff revert control; a minimal correction and real widget regression tests are included.
- Credentialed Tier 0 smoke remains a release prerequisite; isolated fixtures were used here.
