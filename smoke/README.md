# Herald smoke harness

End-to-end Tier 0 / Tier 1 tests run against a live `herald-bot` instance.
See [`NORTHSTAR.md`](../NORTHSTAR.md) for tier definitions and
[`docs/smoke-harness-plan.md`](../docs/smoke-harness-plan.md) for the
overall design.

## Quick start

```bash
# Make scripts executable (one-time, post-clone)
chmod +x smoke/run.sh smoke/tests/*.sh

# Optional: configure a dedicated Telegram bot for the Telegram-path tests
cp smoke/.env.smoke.example smoke/.env.smoke
$EDITOR smoke/.env.smoke

# Run the Tier 0 spine
./smoke/run.sh
```

Without `.env.smoke`, the Telegram-path tests (T0-09, T0-11) are **skipped
with a clear notice**. T0-01, T0-02, and T0-04 run against `/api/chat` and
work with no extra config.

## Tier 0 coverage

| ID    | Name                              | Transport         | Skippable? | Slow? |
|-------|-----------------------------------|-------------------|------------|-------|
| T0-01 | boot clean                        | `run.sh` + health | no         | yes (~60–90s) |
| T0-02 | doctor healthy                    | `run.sh doctor`   | no         | no |
| T0-03 | doctor detects missing env        | `run.sh doctor`   | no         | no |
| T0-04 | basic reply                       | `/api/chat`       | no         | no |
| T0-05 | memory injection                  | `/api/chat`       | no         | no |
| T0-06 | memory mutation + log.md          | `/api/chat`       | no         | no |
| T0-07 | hot.md continuity across restart  | `/api/chat`       | no         | yes (bot bounce) |
| T0-08 | cron fires every-minute job       | sqlite poll       | no         | yes (~95s) |
| T0-09 | `/status` command                 | Telegram          | yes (no token) | no |
| T0-10 | `/reset` clears history           | Telegram          | yes (no token) | no |
| T0-11 | Telegram round-trip               | Telegram          | yes (no token) | no |

Tier 1 specs are sketched in
[`docs/smoke-harness-plan.md`](../docs/smoke-harness-plan.md); no
`tests/t1-*.sh` shipped yet — they land once Tier 0 is green for a week.

## Usage

```bash
./smoke/run.sh                 # Tier 0, human output
./smoke/run.sh --tier 1        # Tier 1 only (specs in plan, not yet wired)
./smoke/run.sh --tier 0,1      # both
./smoke/run.sh --only T0-04    # single test by id
./smoke/run.sh --json          # machine-readable
./smoke/run.sh --quiet         # exit code only, no output
```

Exit codes match the `doctor` convention:

- `0` — every test passed
- `1` — passes with warnings (reserved; not yet wired)
- `2` — at least one test failed

## Layout

```
smoke/
├── run.sh                    # entry point
├── lib/
│   ├── colors.sh             # ANSI helpers (TTY-aware)
│   ├── assert.sh             # assert_eq / assert_contains / ...
│   ├── bot.sh                # bot lifecycle, health probe, log scanning
│   ├── chat.sh               # POST /api/chat client
│   ├── db.sh                 # sqlite3 wrappers (cron jobs)
│   ├── memory.sh             # memory dir + page helpers, log.md
│   └── tg.sh                 # Telegram Bot API helpers
├── fixtures/
│   ├── seed-memory.sh        # plants concepts/smoke-marker.md (T0-05)
│   └── seed-cron.sh          # plants an every-minute cron job (T0-08)
├── tests/
│   ├── t0-01-boot-clean.sh
│   ├── t0-02-doctor-healthy.sh
│   ├── t0-03-doctor-missing-env.sh
│   ├── t0-04-basic-reply.sh
│   ├── t0-05-memory-injection.sh
│   ├── t0-06-memory-mutation.sh
│   ├── t0-07-hot-md-continuity.sh
│   ├── t0-08-cron-fires.sh
│   ├── t0-09-status-command.sh
│   ├── t0-10-reset-clears-history.sh
│   └── t0-11-telegram-path.sh
├── .env.smoke.example
└── README.md                 (this file)
```

## Adding a test

Each test is one shell file that defines two functions:

```bash
# smoke/tests/t0-XX-short-name.sh
test_name() { echo "what this test asserts"; }

test_run() {
    # ... arrange ...
    # ... act ...
    assert_contains "$result" "expected" "label" || return 2
    return 0    # 0 = pass, 1 = warn, 2 = fail, 77 = skip
}
```

Filename pattern: `t<TIER>-<NN>-<slug>.sh`. The harness picks them up
automatically. Source the helpers via the runner — don't `source` them
yourself; the runner already has them loaded in the subshell environment.

## Telegram setup

The Telegram-path tests need a **dedicated test bot** so smoke runs don't
clutter your real chat history.

1. Talk to [@BotFather](https://t.me/BotFather), `/newbot`, get a token.
2. Send your new bot a message (any). This makes you discoverable.
3. Visit `https://api.telegram.org/bot<TOKEN>/getUpdates` and find your chat id.
4. Copy `smoke/.env.smoke.example` → `smoke/.env.smoke` and fill in:

    ```bash
    HERALD_SMOKE_TG_BOT_TOKEN=123456:ABC-...
    HERALD_SMOKE_TG_CHAT_ID=987654321
    ```

5. Configure `herald-bot` to accept messages from the test chat id by adding
   it to `HERALD_TELEGRAM_ALLOWED_CHAT_ID` (comma-separated if multiple), or
   point a second herald instance at the test bot token.

> The Telegram tests use the test bot's token to *send* a message, then
> poll `getUpdates` for Herald's reply. So Herald must be configured to
> reply to the test chat — same chat id as `HERALD_SMOKE_TG_CHAT_ID`.

## Scheduling (TODO)

Daily run via `launchd` is out of scope for v1 — see
`docs/smoke-harness-plan.md` § Scheduling. The intended plist lives at
`~/Library/LaunchAgents/com.herald.smoke.plist` once written.

For now: run `./smoke/run.sh` manually before merging Tier 0 work, weekly
otherwise.

## Known limitations

- **T0-01 and T0-07 bounce the bot.** Both stop and restart your running
  instance. Use `--only T0-04` (etc.) to run individual tests without
  restarting.
- **T0-01 cold-start is slow.** `./run.sh bot` invokes `mvnw spring-boot:run`,
  which compiles + spawns the JVM — 60–90s is normal. Default boot timeout
  is 90s; bump `HERALD_SMOKE_BOOT_TIMEOUT` in `.env.smoke` if your machine
  is slower. (A future optimization: switch T0-01 to `java -jar` for ~10s
  startup against the pre-built JAR.)
- **T0-03 temporarily moves `.env` aside.** If the test crashes mid-run
  before the EXIT trap fires, you may find `.env.smoke-bak-<pid>` next to
  `.env`. Rename it back manually.
- **T0-06 leaves log.md noise.** The plan accepts this — truncating mid-run
  would race the live bot's writes.
- **T0-08 is slow by design.** It waits a full minute plus slack for the
  scheduler to tick. Run only on daily / weekly sweeps, not in PR loops.
- **The Telegram poller is slow.** A 30s timeout is normal for replies.
  If your bot is slower than 30s on first reply, raise `HERALD_SMOKE_TIMEOUT`.
- **Log-error detection is heuristic** — `tail -n 500 | grep ERROR` rather
  than time-window-precise. Good enough for "did the boot blow up." Refine
  later if false positives appear.
- **`chat_send` doesn't stream.** It POSTs to the JSON endpoint, not SSE.
  Streaming-specific regressions need a separate test.
