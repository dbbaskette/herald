# T0-01 — herald-bot cold-starts without errors.
#
# This test STOPS and RESTARTS the bot. Skip it via --only=... if you don't
# want to bounce a running session.
#
# Boot timeout defaults to 90s because `./run.sh bot` uses `mvnw
# spring-boot:run`, which compiles + spawns a JVM — much slower than
# `java -jar`. Override with HERALD_SMOKE_BOOT_TIMEOUT in .env.smoke.

test_name() { echo "boot clean"; }

test_run() {
    local timeout="${HERALD_SMOKE_BOOT_TIMEOUT:-90}"

    # Snapshot current state. If we're starting from down, just bring it up;
    # if we're up, bounce.
    if bot_is_up; then
        bot_stop
    fi

    if ! bot_start "$timeout"; then
        echo "bot did not become healthy within ${timeout}s"
        echo "log: $(bot_logfile)"
        return 2
    fi

    # Give logs a moment to flush.
    sleep 2

    local err_count
    err_count=$(errors_since 30)
    if [ "$err_count" -gt 0 ]; then
        echo "$err_count ERROR-level log lines observed since startup"
        echo "log: $(bot_logfile)"
        return 2
    fi

    return 0
}
