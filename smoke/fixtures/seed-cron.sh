#!/usr/bin/env bash
# Seed an every-minute test cron job that T0-08 watches for `last_run` to
# advance. Picks a unique name per run via a unix-time suffix so cleanup is
# unambiguous even if a previous run crashed before its cleanup ran.
#
# Echoes the inserted job name on stdout.
#
# Usage (sourced from a test):
#   . "$SMOKE_DIR/lib/db.sh"
#   . "$SMOKE_DIR/fixtures/seed-cron.sh"
#   job_name=$(seed_smoke_cron)
#
# The prompt is intentionally a no-op-looking sentinel ("emit SMOKE-CRON-FIRED")
# so a human inspecting the bot log can correlate the firing event without
# needing to trace anything.

seed_smoke_cron() {
    local name="smoke-cron-$(date +%s)-$$"
    db_seed_cron "$name" "Emit the literal phrase SMOKE-CRON-FIRED so the smoke harness can verify cron is running. No memory or tool calls." >/dev/null
    printf '%s\n' "$name"
}

if [ "${BASH_SOURCE[0]}" = "$0" ]; then
    # shellcheck disable=SC1091
    . "$(dirname "$0")/../lib/db.sh"
    seed_smoke_cron
fi
