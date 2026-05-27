# Herald — North Star

**Herald is a Telegram-native personal AI assistant with persistent file-based memory.**
Everything else is in service of (or in spite of) that single job.

## Why this document exists

Herald can technically do many things — multi-provider routing, CLI task-agent mode,
A2A protocol, ephemeral sessions, hot-reloadable skills, the Spring AI Agentic
Patterns reference implementation. The risk of that breadth is that nothing works
*reliably*, because attention is spread across a surface that's too large to verify.

This document defines the **small set of capabilities that must always work**, and
explicitly demotes everything else to "best-effort." Any contributor — including
future me — should be able to read this and immediately know what the daily smoke
tests cover, what's a release blocker, and what can break without panicking.

If anything written elsewhere (README, blog posts, slide decks, agent prompts)
contradicts this document, **this document wins**. Update here first when
priorities change.

## The four tiers

### Tier 0 — Spine

If any of these regress, Herald is broken and merging is blocked until they're
fixed. The daily smoke harness exercises every one end-to-end.

| Capability | One-sentence test |
|---|---|
| `herald-bot` boots clean | Start-up under 20s; no `ERROR`-level log lines in the first 30s; `/actuator/health` returns 200. |
| `./run.sh doctor` accuracy | Exits 0 on a healthy install; exits 1 with `ANTHROPIC_API_KEY` mentioned when the key is unset; exits 2 when the SQLite file is corrupt. |
| Telegram ↔ Claude loop | A free-text Telegram message produces a non-empty streamed reply within 15s. |
| Memory advisor injects `MEMORY.md` | After a turn that mentions a known memory entity, the reply references the entity and `MEMORY.md` appears in the dumped prompt. |
| `hot.md` session-continuity | Restart `herald-bot`; the next reply references content from the prior session via `hot.md`. |
| Auto-memory mutations + `log.md` | "Remember that Jamie's deadline is Friday" triggers a `MemoryCreate` or `MemoryStrReplace`; `log.md` gains a new entry. |
| Cron briefings | A test cron job at `* * * * *` posts to the Telegram chat within two minutes. |
| Core commands | `/status`, `/help`, `/save`, `/reset`, `/memory`, `/debug` all return non-empty, well-formed responses. |

### Tier 1 — Daily-use

High-value capabilities the user touches multiple times a week. Smoke harness
exercises these weekly (and on PRs that touch their code paths). Regression on
these is a "fix this week" priority, not a merge blocker.

| Capability | One-sentence test |
|---|---|
| Gmail via `gws` | `gws gmail search "from:me newer_than:1d"` returns ≥1 result; agent can summarise sent threads. |
| Calendar via `gws` | `gws cal list --today` returns today's events; agent can answer "what's on my calendar." |
| Apple Reminders | `reminders show-today` returns items; agent can create + complete a reminder by name. |
| Web search | A search query returns ≥3 results; the agent cites at least one in the reply. |
| `wiki-ingest` | Ingest a known URL; assert a new file under `memories/sources/` with takeaways + cross-links. |
| `wiki-query` | Query for a known concept; reply cites the matching `memories/concepts/<page>.md`. |
| Management console | `/status`, `/memory`, `/cron`, `/skills` pages render without errors at `http://localhost:8080`. |
| MeetingNotes bridge (#367, in flight) | A finished MeetingNotes meeting auto-files a `memories/meetings/...` page within 30s; Telegram receives a summary push. |

### Tier 2 — Best-effort

Features that exist and should keep working but don't get smoke coverage. If they
break, file an issue, don't block on them. **Don't expand this surface** — every
new "could we add…" should justify why it isn't Tier 2 cruft from day one.

- CLI task-agent mode (`--agents=foo.md`)
- Multi-provider model routing — Anthropic is gold; OpenAI / Gemini / Ollama / LM Studio are best-effort
- A2A protocol
- Streaming chat (open PR #340) — ship it but don't depend on it
- Image / file uploads via web chat
- Telegram inline keyboards for `AskUserQuestion` / `TodoWrite`
- `/think`, `/trace`, `/why`, `/compact`, `/budget` commands
- Anthropic prompt caching — works → great; doesn't → not a blocker
- `skill-browser`, `code-writer`, `markitdown`, `obsidian`, `weather`, `broadcom`, `github`, `skill-creator`, `voice-handling` skills
- Tiered model routing (Haiku / Sonnet / Opus) — only Sonnet is verified

### Tier 3 — Experimental / archive

Code that exists in the repo or in branches but should not be relied on. Candidates
for explicit `@Experimental` marker, README badge, or deletion. **Don't expand. Don't
justify their existence — justify keeping them.**

- Most `feature/*` branches more than 30 days old (`ephemeral-mode-218-221`,
  `phase2-ephemeral-222-225`, `phase3-module-split-226-230`, `phase4-agents-md-231-234`,
  `module-inventory`, `conditional-advisors-tools`, `streaming-chat` once #340 lands)
- All `claude/*` agent branches (the auto-generated agent experiments)
- Spring AI 2.1 / Session API migration prep — wait for upstream
- ToolSearchToolCallAdvisor tombstones (already removed; remove remaining refs)

## Process

1. **PR labels.** Every PR labels itself with the highest tier it touches. Tier 0
   PRs require the daily smoke green; Tier 1 PRs require the weekly smoke;
   Tier 2+ PRs require no smoke.
2. **Smoke-green window.** No new Tier 2 work merges until the daily Tier 0 smoke
   has been green for **7 consecutive days**. This is the discipline that
   side-projects usually lack — enforce it.
3. **Issue triage.** Open issues get a `tier-0` / `tier-1` / `tier-2` / `tier-3`
   label.
4. **Quarterly tier review.** Every 90 days, re-read this doc. Things drift;
   identities drift. Re-justify each Tier 0 entry by name.
5. **New feature gate.** Any proposed Tier 0/1 addition needs a paired smoke test
   in the same PR. No test → not Tier 0/1.

## Initial issue tiering (snapshot)

Captured at the time this document was written; trust labels in GitHub over this
list once triage is done.

| Tier | Issues |
|---|---|
| Tier 0 | _none currently — file as found_ |
| Tier 1 | #367 (MeetingNotes bridge), #368 (daily activity log) |
| Tier 2 | #310 (menu-bar app), #311 (Gmail push), #312 (Tailscale), #321 (skill autosave), #331 (ClawHub), #365 (screen-context advisor), #366 (hotkey overlay), #369 (federated search), #370 (integration breadth) |
| Tier 3 | #371 (mobile apps) |

## Non-goals (today)

- **Be a great open-source project for others to deploy.** The single-user-on-laptop
  posture is the design constraint. If hardening for multi-user happens, it's a
  side-effect of better code, not a goal.
- **Be the canonical Spring AI Agentic Patterns reference.** Patterns happen to be
  used here, but the codebase is curated to be a useful assistant, not to teach
  the patterns.
- **Match feature parity with any commercial assistant.** Pick the few features
  that compound (memory, meetings, calendar, briefings) and let everything else
  go.
- **Ship every skill that could be useful.** The skills shipped today already
  outpace the user's verified daily needs. New skills should replace, not add.

## Living document

Update this file first when priorities change. Then propagate to README, blog
posts, agent prompts. Reverse order is how documents drift apart.
