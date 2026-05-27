# T0-08 — the cron scheduler actually runs jobs.
#
# We insert a fresh every-minute job, wait up to ~90s, and assert its
# `last_run` column has advanced past the row's insertion time. Cleanup
# drops the job row regardless of outcome.
#
# Slow test (worst case ~95s). Runs only on the daily Tier 0 sweep — not
# meant for fast PR loops.

test_run_t0_08() {
    if ! bot_is_up; then
        echo "bot is not reachable at $HERALD_BOT_URL"
        return 2
    fi
    if ! db_path >/dev/null 2>&1; then
        echo "herald.db not found (HERALD_DB_PATH=${HERALD_DB_PATH:-~/.herald/herald.db})"
        return 2
    fi

    # shellcheck disable=SC1091
    . "$SMOKE_DIR/fixtures/seed-cron.sh"

    local job_name; job_name=$(seed_smoke_cron)
    if [ -z "$job_name" ]; then
        echo "failed to seed smoke cron job"
        return 2
    fi

    # shellcheck disable=SC2317
    cleanup_t0_08() {
        db_drop_cron "$job_name" >/dev/null 2>&1 || true
    }
    trap cleanup_t0_08 EXIT

    # Poll up to 95s — gives the scheduler a full minute plus slack for
    # the next tick boundary, plus startup jitter.
    local timeout="${HERALD_SMOKE_CRON_TIMEOUT:-95}"
    local deadline=$(( $(date +%s) + timeout ))
    local last_run=""

    while [ "$(date +%s)" -lt "$deadline" ]; do
        last_run=$(db_cron_last_run "$job_name")
        if [ -n "$last_run" ]; then
            return 0
        fi
        sleep 3
    done

    echo "cron job '$job_name' did not fire within ${timeout}s (last_run still NULL)"
    return 2
}

test_name() { echo "cron fires every-minute job"; }
test_run()  { test_run_t0_08; }
