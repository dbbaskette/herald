# Console reliability and editing implementation plan

> Execution: use executing-plans with dispatching-parallel-agents for independent issue domains; coordinate shared files before edits. User explicitly authorized implementation and subsequent merge.

**Goal:** Resolve #190, #177, #285 and #286, merge verified changes, then rank the remaining open backlog.

**Architecture:** Retain the Java/UI split and existing visual design. Establish explicit DTOs around cron, safe file-editing boundaries around memory/skills, and one cancellable SSE lifecycle used by status and skills. Preserve optional capability behavior and distinguish unknown/offline from disabled. Use existing Spring CronExpression and existing YAML support on the backend; use a maintained YAML parser for client linting.

**Specs:** GitHub issues 190, 177, 285, 286 and their acceptance criteria, saved under /tmp/herald-console-work. Optional memory graph is omitted; all required acceptance remains in scope.

## Global constraints

- Worktree /private/tmp/herald-console-190-177-285-286, branch codex/console-reliability-and-editing, baseline ae255f6.
- User authorizes merge after verification. Do not deploy or run against personal ~/.herald data/credentials. Use temporary SQLite/filesystem/HTTP fixtures.
- Preserve original checkout docs/reviews. No new auth/CORS/external service integration.
- Validate API payloads before writes. Reject absolute/traversal/symlink escapes; cap text sizes; keep errors actionable without exposing internals.
- Preserve drafts on failures and stale request races. Memory deletion is recoverable, with restore rejecting overwrite conflicts.
- Milestone targeted tests; complete relevant suites at integration. No strict TDD requirement. No agent runs parallel Maven builds in this shared worktree; coordinate test windows.
- Parent owns package manifests, generated static bundles, shared documentation and final integration. Agents do not commit or edit other owners' files; parent commits coherent reviewed slices.

## Task 1 — Cron contract and scheduling (#190)

Owner: cron agent. Files: UI CronController/repository/tests, frontend cron store/CronBuilder/tests, persistence CronService/repository/tests, CommandPoller/command handling and narrow schema migration if required.

- [x] Read actual UI/persistence scheduler/command contracts and preserve public compatibility aliases as needed.
- [x] Provide a typed shared list/create/partial-update/toggle/delete/run contract under /api/cron, compatible alias /api/cron-jobs if useful. DTO includes expression, promptText, builtIn, enabled, timezone, nextRun and execution status.
- [x] Canonicalize five-field schedules by prepending zero seconds for Spring validation/execution; preserve incoming expression losslessly for editing. Use explicit configured ZoneId, Spring CronExpression validation and DST-aware next-run computation. Validate before cancelling schedules or DB writes.
- [x] Persist/surface accepted command versus queued/running/completed/failed run state; synchronize scheduler changes via existing command architecture. Preserve omitted fields and built-in/disabled state; protect built-in deletion at the durable boundary.
- [x] Add preset + lossless advanced editor and actionable errors, status polling/refresh, no falsely completed accepted runs.
- [x] Tests: actual HTTP DTO route roundtrip, partial updates, invalid schedules no writes/cancel, built-in/disabled state, step/range/list/weekly/DST five/six-field cases, command queue/run states, frontend controller-shaped fixtures.

## Task 2 — Cancellable connection lifecycle (#177)

Owner: connection agent. Files: shared frontend SSE utility, status store/spec, NowStripe/SystemStatus and their tests, SkillsEditor SSE lifecycle only. Parent waits before editing SkillsEditor for task3.

- [x] A connection owner cancels retry timers and ignores stale callbacks; bounded exponential delays, explicit stop, idempotent start. Shared status subscribers must not disconnect each other. Scope lifecycle to app or reference-counted consumer ownership.
- [x] Preserve last good status on failures; expose initial loading, live/reconnecting/offline/stale states plus last-success timestamp, retry action. Validate incoming nested payload fields and cap activity at20 with stable keys.
- [x] Apply shared retry/cancellation to skill reload events, keeping current editor behavior intact. Show cron empty state and disabled optional capability distinctly from fetch failure.
- [x] Tests use fake timers for disconnect-before-retry, duplicate mounts, repeated errors/backoff ceiling, stale callbacks, malformed payload and recovery; verify offline data remains truthful.

## Task 3 — Skill validation (#285)

Owner: parent. Files: new skill validation service/controller/DTO/tests, SkillsController save validation, capability/reference endpoint as necessary, frontend YAML/validation helper and editor panel/inline diagnostics/tests. Coordinate after Task2's SkillsEditor edits.

- [x] POST /api/skills/validate accepts skill name + text and returns {valid,name,description,diagnostics:[{severity,code,message,line,column}],capabilityStatus}. Malformed YAML, missing string name/description and duplicate keys are errors; reference warnings are non-blocking.
- [x] Client debounces safe YAML parse with source positions, displays exact name/description preview and inline CodeMirror diagnostics; abort/version checks ensure responses never attach to another draft. Invalid frontmatter blocks Save; backend repeats validation before writing.
- [x] Check explicit configured-tool references, relative examples/docs links, and concrete wikilinks against safe configured roots. Distinguish example placeholders and remote links from actual missing references; existing valid bundled fixtures should not warn falsely. Disabled vault/tool capability is explained, not guessed from UI visibility.
- [x] Tests cover malformed/duplicate/missing YAML, folded descriptions, save rejection/no mutation, configured/unknown tools, missing/existing relative refs and wikilinks, symlink/traversal confinement, stale validation responses and existing bundled skills.

## Task 4 — Memory management (#286)

Owner: memory agent. Files: FileMemoryController/new file-memory service/DTO/tests, fileMemory store/MemoryViewer/editor components/tests, narrow memory context/attribution recording or lookup integration. Coordinate any shared bot config edits with parent.

- [x] Browse typed tree including MEMORY.md; keyword/full-text/tag search with bounded reads, excluded trash and symlink confinement.
- [x] Read/edit with version conflict handling; text size bound and atomic writes. Confirmed delete moves to .memory-trash under configured root, returns recoverable path/token; restore/undo refuses overwrite. Never silently truncate and save a partial original.
- [x] Active-conversation context markers must be backed by actual advisor/tool usage or retained message evidence; never mark all files or inferred retrieval as loaded. Capture minimal path/conversation/timestamp metadata where needed; preserve reset/compaction truthfulness and optional memory operation.
- [x] Show frontmatter/recorded attribution when available with conversation history link and graceful legacy absence. Hide disabled Obsidian capability; label legacy key-value data distinctly.
- [x] Frontend selection/search request ordering and draft guards; tests prove rapid selection safety, edit persistence/conflicts, trash+restore, traversal/symlink rejection, context markers and attribution, graceful disabled/legacy cases. Graph remains optional and omitted.

## Integration and delivery

- [x] Independently review issue diffs and cross-feature boundaries. Address important findings.
- [x] Full Maven verify and frontend tests/typecheck/build; audit new dependencies; browser fixture smoke across new flows; refresh tracked static assets and verify packaging.
- Delivery: Commit, push, create PR with closing references to all four issues; merge only verified head after checks. Sync local main preserving audit artifacts.
- Delivery: Fetch all remaining open issues and rank by correctness/data risk, user impact, dependencies and effort. Provide complete ranked artifact plus concise top priorities; do not equate ranking with completed review of every issue's code.

## Verification and review outcome

- Java reactor suites: 825 tests, zero failures/errors/skips. `mvn verify` completed
  all modules except an obsolete quoted-template assertion in UI; after correcting
  that assertion, `mvn -pl herald-ui verify` passed all92 UI tests and packaged the jar.
- Frontend: all160 tests pass; typecheck and production build pass; final wording
  refinement passes15 focused tests. npm audit reports zero vulnerabilities.
- Packaged console browser fixture: inline YAML blocking, nonblocking missing-tool
  and file warnings, successful skill save, memory save/delete/undo, cron lossless
  five-field save preserving disabled state, queued commands, and SSE disconnect
  retaining last-good data with stale/reconnecting/last-updated labels verified.
- Independent crossreview resolved delayed scheduled execution, selection/draft
  races, tutorial-link false warnings, YAML template scalar typing, and memory
  context evidence across Reactor threads. All filesystem/API fixtures were temporary.
- Existing large entry chunk and ineffective dynamic import warnings remain tracked
  in #392; broad optionality/draft/CI work remains in #388/#390/#387.
