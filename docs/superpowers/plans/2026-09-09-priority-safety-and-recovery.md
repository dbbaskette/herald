# Priority Safety and Recovery Implementation Plan

> Execute with executing-plans and dispatching-parallel-agents, one owner per independent domain.

**Goal:** Complete #312, #373, #270, #272 and #390, verify, push a PR and merge.
**Spec:** Current GitHub issue acceptance, including interim compaction scope. The future Session API replacement remains separate; implement safe current APIs now.
**Architecture:** Keep bot and console loopback-first; optional shared-token console protection supports API clients and same-origin browser sessions/SSE. Recover meeting ingestion through durable job states/leases and truthful progress. Preserve complete conversation turns and synthetic summaries with configurable no-LLM sliding mode. Skill/prompt navigation uses deliberate Save/Discard/Stay and versioned conflict resolution.
**Stack:** Existing Java21/Spring Boot4.1.1/AI2.0.1, SQLite, Vue3/Pinia/CodeMirror. No dependency upgrades unless necessary.

## Constraints
- User explicitly authorized implementation, auth changes described by #312, PR push and merge. No deployment or live configuration changes.
- Worktree /private/tmp/herald-priorities-312-373-270-272-390; baseline1a5a1d0. Preserve original audit artifacts.
- Reports and test fixtures /tmp/herald-priorities-work; no personal databases, vaults, credentials or live meetings.
- Root owns security/auth/App.vue, global config bind-address changes, package/static assets and delivery. Agents own independent domains; coordinate shared config/schema edits.
- One Maven process at a time. Focused tests at milestones; final full relevant suite. Node22.23.1 explicit PATH.
- No agent commits. Root integrates and commits reviewed changes.

## #312 Remote access — root
- [x] Threat boundary: all console /api routes, including SSE/uploads/proxies; optional bearer-token validation with constant-time comparison, no secrets in URLs/logs/storage. Same-origin HttpOnly browser session so EventSource works. Auth gate prevents protected pages fetching before authentication; expiry and failed login recoverable.
- [x] Loopback defaults bot/UI, explicit environment override for containers; review launcher overrides. Doctor/config validation warns broad unauthenticated exposure using effective configuration.
- [x] Verify official Tailscale Serve HTTPS/MagicDNS and SSH guidance; write remote-access and standalone checklist, distinguish actual webhook secret from future webhook work.
- [x] Tests: unset/local behavior, valid/invalid token, all API methods/dispatches/SSE, session/logout/CSRF and defaults/warnings. Browser protected console flow.

## #373 Meetings — independent owner
- [x] Inspect existing ingest/catalog/ledger/catch-up/backfill routes and UI. Recoverable durable pending/running/succeeded/failed state with leases, restart/concurrent idempotency, no duplicate downstream effects where deterministically enforceable.
- [x] Cross-day catch-up, separate recap/Reminders controls, truthful counts and per-meeting retry/progress. Preserve enrichment and file layout; add fixtures and restart/failure tests.

## #270 + #272 Compaction — independent owner
- [x] Snap eviction before a complete user turn, preserve tool-call/result pairs and latest turn. Handle insufficient safe cut, leading system/synthetic messages and overlapping calls.
- [x] Retain summaries as metadata-tagged synthetic context that survives subsequent compaction and works with existing memory sanitizers/advisors. Configurable sliding-window mode never calls summary model.
- [x] Config documentation and tests for pair boundaries, repeated summaries, sliding mode, failure/no-loss and real memory integration. No speculative Session API dependency.

## #390 Draft protection — independent owner
- [x] Shared accessible Save/Discard/Stay handling for skill/prompt selection, route changes and reload/new actions; browser unload warning, failed save preserves draft.
- [x] Version/ETag optimistic write protection on skill/prompt routes; expose latest content and diff/explicit reconciliation without silently overwriting either side. No credential storage. Cover stale requests and concurrent editors.
- [x] Behavior/API tests and documentation. Coordinate App.vue/auth ownership and preserve recent skill validation/SSE.

## Integration
- [x] Independent crossreview and fix material findings, then full Maven/frontend tests, typecheck/build/audit and packaged fixture browser checks.
- [ ] Commit, push, verify GitHub head/checks, merge with closing references and sync local main. Confirm all five closed; summarize changes and limitations accurately.

## Verification evidence

- Full `mvn verify`: 869 tests, zero failures/errors/skips, all six reactor projects successful.
- Frontend suite: 171 tests passed; auth tests rechecked after sign-out error handling. Typecheck and production build passed; dependency audit reported zero vulnerabilities.
- Packaged static files byte-match the current frontend build.
- Temporary packaged console: API 401, login cookie, CSRF, same-origin chat SSE, logout invalidation; config validator default, broad bind, JSON override, CLI precedence, invalid-token exit codes all passed.
- Browser: skill and prompt draft navigation, lock/unlock draft retention, external-edit diff and explicit reconciliation, meeting failed-to-pending retry counts passed. All files/DBs/bot responses were temporary fixtures.
- Crossreview fixed chat GET CSRF, JSON validation fixture coverage, delayed-create draft loss, silent Telegram delivery failure and incomplete meeting payload reservation.
- Limits documented: external delivery has a crash-before-checkpoint duplication window; legacy claims need review; already-open SSE ends on disconnect/timeout or restart; Session API replacement remains tracked in #273.
