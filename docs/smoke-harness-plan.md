# Smoke harness — plan

> **Status:** plan only. No code yet. Reviewed once before any implementation.

## Goal

A single executable that exercises every **Tier 0** capability (see
[NORTHSTAR.md](../NORTHSTAR.md)) end-to-end against a live `herald-bot` instance,
reports pass/fail per test, and exits non-zero on any failure. Runs daily via
`launchd`. Tier 1 runs weekly via the same harness with a flag.

Success = "I can look at one badge and know whether the spine is healthy without
manually exercising the bot."

## Design choices

### Why Bash (not JUnit)

The harness runs **from outside Herald**, against a deployed instance. JUnit
tests would pull verification back into the codebase, where they'd share JVMs,
classloaders, and test fixtures with the unit suite. That's the wrong vantage
point — Herald is a deployed black box to its user; the smoke should be too.

Bash + `curl` + `jq` + `sqlite3` reads as plain shell. Adding a new test is
copy-and-edit. There's no framework to learn.

### Two transports, one harness

Every test that *can* run through `/api/chat` should — it's an order of
magnitude faster than Telegram round-trips. One dedicated "Telegram path" test
covers the actual transport layer. If `/api/chat` works but Telegram doesn't,
that one test fails and points at the right module.

### Production memory dir, prefixed cleanup

Don't fork the memory store. Use the production `~/.herald/memories/` dir but
namespace every smoke-generated artifact with a `smoke-YYYYMMDD-HHMMSS-` prefix
in slug and conversation id. Cleanup deletes by prefix. Trade-off: if cleanup
fails, the next run sees yesterday's debris — accepted, the harness logs orphans
and continues.

### Failure modes

| Per-test outcome | Exit contribution |
|---|---|
| pass | nothing |
| pass-slow (latency > target × 2) | exit 1 |
| fail | exit 2 |

Run-level exit is the max of any test's contribution.

## Architecture

```
smoke/
├── run.sh                 # entry point
├── lib/
│   ├── chat.sh            # POST /api/chat helper
│   ├── tg.sh              # Telegram Bot API helpers
│   ├── db.sh              # sqlite3 wrappers
│   ├── log.sh             # log-tail + ERROR-line assertions
│   ├── memory.sh          # memory-page helpers (read, assert, cleanup)
│   └── assert.sh          # assert_* primitives (eq, contains, in_range, etc.)
├── tests/
│   ├── t0-01-boot-clean.sh
│   ├── t0-02-doctor-healthy.sh
│   ├── t0-03-doctor-detects-missing-env.sh
│   ├── t0-04-basic-reply.sh
│   ├── t0-05-memory-injection.sh
│   ├── t0-06-memory-mutation.sh
│   ├── t0-07-hot-md-continuity.sh
│   ├── t0-08-cron-fires.sh
│   ├── t0-09-status-command.sh
│   ├── t0-10-reset-clears-history.sh
│   ├── t0-11-telegram-path.sh
│   └── (Tier 1 lives in tests/t1-*.sh)
├── fixtures/
│   ├── seed-memory.sh     # creates a known memory page used by T0-05
│   └── seed-cron.sql      # inserts the every-minute test cron job
└── README.md              # how to run, how to add a test, expected output
```

### Entry point

```
./smoke/run.sh                  # human output, exit 0/1/2
./smoke/run.sh --json           # machine-readable, same exit codes
./smoke/run.sh --quiet          # exit only, no output
./smoke/run.sh --tier 0         # default
./smoke/run.sh --tier 1         # weekly
./smoke/run.sh --tier 0,1       # both
./smoke/run.sh --only T0-05     # single test
```

Matches the `doctor` command's interface so users have one mental model.

### Per-test contract

Every `tests/t*-*.sh` file exports two functions:

```bash
test_name() { echo "T0-05 — memory injection"; }
test_run()  { ... ; return 0|1|2 ; }
```

`run.sh` sources each test, captures stdout/stderr, times it, and emits one
result line. No globals leak between tests.

## Test specs (Tier 0)

### T0-01 — boot clean

| | |
|---|---|
| **Premise** | `herald-bot` cold-starts without errors. |
| **Method** | `./run.sh stop` then `./run.sh bot &`; poll `http://127.0.0.1:8081/actuator/health` every 1s until 200 or 30s. |
| **Assert** | Health returns 200 within 20s. `grep ERROR ~/.herald/logs/herald.log` against the lines written in the last 30s returns nothing. |
| **Target latency** | < 20s. |
| **Cleanup** | Leave bot running for subsequent tests. |

### T0-02 — doctor healthy

| | |
|---|---|
| **Premise** | `./run.sh doctor` understands a healthy install. |
| **Method** | `./run.sh doctor --quiet`. |
| **Assert** | Exit 0. JSON mode (`--json`) emits no `fail` keys. |

### T0-03 — doctor detects missing env

| | |
|---|---|
| **Premise** | `doctor` actually catches misconfig, not just rubber-stamps. |
| **Method** | Snapshot `ANTHROPIC_API_KEY`; `unset`; run `./run.sh doctor`; restore. |
| **Assert** | Exit 1. Output contains `ANTHROPIC_API_KEY`. |
| **Cleanup** | Restore env var. |

### T0-04 — basic reply

| | |
|---|---|
| **Premise** | The agent loop terminates and returns text. |
| **Method** | POST to `/api/chat`: `{"conversationId":"smoke-<ts>","message":"reply with the literal word PONG"}`. |
| **Assert** | HTTP 200. Response body contains `PONG`. End-to-end latency < 15s. |
| **Cleanup** | Drop the conversation row by id prefix. |

### T0-05 — memory injection

| | |
|---|---|
| **Premise** | `MEMORY.md` and the memory advisor actually shape replies. |
| **Method** | `fixtures/seed-memory.sh` creates `memories/concepts/smoke-marker.md` with body "The smoke marker is `BIRD-7741`." Re-trigger `MEMORY.md` regeneration. POST: `"what is the smoke marker?"`. |
| **Assert** | Reply contains `BIRD-7741`. Optional: enable `/trace` for this run and verify `MEMORY.md` appears in the dumped prompt. |
| **Cleanup** | Delete the seeded page; regenerate `MEMORY.md`. |

### T0-06 — memory mutation + log.md

| | |
|---|---|
| **Premise** | Auto-memory tools fire, and `log.md` records them. |
| **Method** | Snapshot `log.md` line count. POST: `"Remember that the smoke deadline is 2026-12-31"`. Wait up to 10s for tool-call completion. |
| **Assert** | A new file appears under `memories/` mentioning `2026-12-31`. `log.md` line count increased by ≥1. The new log line references the page that was created. |
| **Cleanup** | Delete the new memory page. Truncate the log lines added during the test (or accept the noise and move on — recommended: accept). |

### T0-07 — hot.md continuity

| | |
|---|---|
| **Premise** | Restarts don't lose session continuity. |
| **Method** | POST a turn that establishes a non-trivial fact (e.g. "the smoke widget id is `WGT-99`"). Wait for `hot.md` regeneration (compaction). `./run.sh stop`; restart bot. POST: `"what was the smoke widget id?"`. |
| **Assert** | Reply contains `WGT-99`. |
| **Cleanup** | Smoke conversation row dropped; `hot.md` either left or regenerated. |

### T0-08 — cron fires

| | |
|---|---|
| **Premise** | Cron actually runs. |
| **Method** | `fixtures/seed-cron.sql` inserts a job `smoke-cron-<ts>` with schedule `* * * * *` and prompt `echo SMOKE-CRON-FIRED via test channel`. Wait 90s. |
| **Assert** | `cron_jobs.last_run` for the test job updated within the wait window. Output captured in the test channel or written to a sentinel file. |
| **Cleanup** | Delete the cron job row. |

### T0-09 — `/status` command

| | |
|---|---|
| **Premise** | The most-used command is well-formed. |
| **Method** | POST `/status` via `/api/chat`. |
| **Assert** | Reply contains `uptime`, the model name (matches `HERALD_MODEL_DEFAULT`), `tools`. |

### T0-10 — `/reset` clears history

| | |
|---|---|
| **Premise** | Reset actually resets the conversation. |
| **Method** | POST "the smoke phrase is `PURPLE-OCEAN`". POST `/reset`. POST "what was the smoke phrase?". |
| **Assert** | Second reply does **not** contain `PURPLE-OCEAN`. Long-term memory pages remain untouched (a separate before/after diff confirms). |

### T0-11 — Telegram path

| | |
|---|---|
| **Premise** | The Telegram transport (poller, sender, command handler) is actually wired. |
| **Method** | Send via Bot API: `sendMessage` to the test chat id with body `"smoke ping <ts>"`. Poll `getUpdates` for a reply mentioning the timestamp. |
| **Assert** | A reply arrives within 30s and references the timestamp. |
| **Cleanup** | None needed at the Telegram side; the conversation row is the same as T0-04's pattern and gets cleaned by prefix. |

## Tier 1 tests (sketch)

These get exercised weekly. Specs follow the same shape. Listing names only for
now; full specs land when Tier 0 is green for a week.

- **T1-01** — `gws gmail search` returns results; agent summarises one.
- **T1-02** — `gws cal list --today` returns events; agent answers "what's on my calendar."
- **T1-03** — Apple Reminders create + complete round-trip.
- **T1-04** — Web search returns ≥3 results; agent cites one.
- **T1-05** — `wiki-ingest` of a known URL produces the right `sources/` page.
- **T1-06** — `wiki-query` for a known concept cites the right page.
- **T1-07** — Console pages (status, memory, cron, skills) all return 200.
- **T1-08** — MeetingNotes bridge: drop a fixture `summary.md` in the meetings dir, assert a memory page appears within 30s and a Telegram message fires.

## Scheduling

`~/Library/LaunchAgents/com.herald.smoke.plist`:

- Tier 0 — daily at 06:00 local.
- Tier 1 — Sundays at 06:30 local (assumes Tier 0 already green that morning).
- Logs to `~/Library/Logs/herald-smoke.log`.
- On red: send a Telegram message via the **production** bot ("Smoke red: T0-05, T0-08 failed at 06:01 — see ~/Library/Logs/herald-smoke.log").

A README badge or a Console widget could reflect the latest result. Out of scope
for v1 — file in a separate issue once the harness exists.

## Day-one minimal version

If the full plan is too much for one PR, ship in this order:

1. **Just T0-01, T0-02, T0-04, T0-09, T0-11.** Five tests, covering "boots, doctor, can reply, status works, Telegram path works." That's the bare-minimum sanity check.
2. **Add T0-05, T0-06, T0-07, T0-10.** Memory tests — the next layer of trust.
3. **Add T0-03, T0-08.** Negative tests and cron — slowest to write, lowest day-to-day value.
4. **Wire `launchd` + alerting.** Without this, the harness is a manual run-it-yourself script. Don't skip.
5. **Then Tier 1.** Only after Tier 0 has run reliably for a week.

## Open decisions before any code

These need answers before writing the harness:

1. **Where do conversation ids and chat ids for smoke live?** Recommendation: dedicated `smoke@local` chat id, dedicated test bot token in `.env.smoke`, both read by `smoke/run.sh` and never by `herald-bot` proper.
2. **How is `/trace` enabled programmatically for T0-05?** Need a REST endpoint or an env-var hook — currently only set via `/trace on` chat command. Possibly out of scope for v1; rely on the user-visible reply content instead of the prompt trace.
3. **What's the smoke conversation's interaction with cost caps?** Recommendation: `HERALD_BUDGET_DAILY_USD` accounts for smoke runs. At ~0.05 USD/run × 1 daily run × Sonnet, that's ~$1.50/month. Negligible. Document it.
4. **Should T0-01 stop and restart, or assume bot is running?** Recommendation: stop + start. The test exercises the boot sequence, which is exactly where startup-error regressions live (see recent commits #341–343).
5. **What happens if a test fails partway through cleanup?** Recommendation: cleanup wrapped in `trap '...' EXIT`, runs unconditionally. If cleanup itself fails, log it and keep going — never let cleanup mask a real test failure.

## Out of scope

- Performance benchmarking — smoke is pass/fail, not p95/p99.
- Fuzzing — smoke is "does the happy path work." Fuzzing is separate.
- Multi-user / concurrency testing — Herald is a single-user tool.
- Coverage of Tier 2 — by definition not covered. If Tier 2 starts breaking
  Tier 0, that's a Tier 0 bug.
