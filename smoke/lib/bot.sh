# Helpers for the herald-bot lifecycle + health probe.

# Returns 0 if /actuator/health returns 200 within $1 seconds, else 1.
wait_for_health() {
    local timeout="${1:-30}"
    local deadline=$(( $(date +%s) + timeout ))
    while [ "$(date +%s)" -lt "$deadline" ]; do
        if curl -fsS --max-time 2 "$HERALD_BOT_URL/actuator/health" >/dev/null 2>&1; then
            return 0
        fi
        sleep 1
    done
    return 1
}

# Returns 0 if a bot is reachable right now, else 1. No retry.
bot_is_up() {
    curl -fsS --max-time 2 "$HERALD_BOT_URL/actuator/health" >/dev/null 2>&1
}

# Stop the bot via run.sh stop. Best-effort.
bot_stop() {
    ( cd "$HERALD_DIR" && ./run.sh stop ) >/dev/null 2>&1 || true
    # Give launchd / java a moment to actually exit.
    sleep 2
}

# Start the bot via run.sh bot (detached). Returns 0 once health probe passes.
bot_start() {
    local timeout="${1:-30}"
    ( cd "$HERALD_DIR" && nohup ./run.sh bot >/dev/null 2>&1 & )
    wait_for_health "$timeout"
}

# Return the path to the active bot log. The repo logs to ~/Library/Logs in
# installed mode and ./logs in dev mode. Pick the most recently modified.
bot_logfile() {
    local candidates=(
        "$HOME/Library/Logs/herald.log"
        "$HERALD_DIR/logs/herald-bot.log"
        "$HERALD_DIR/logs/bot.log"
    )
    local newest="" mtime=0 cmt
    for c in "${candidates[@]}"; do
        if [ -f "$c" ]; then
            cmt=$(stat -f %m "$c" 2>/dev/null || stat -c %Y "$c" 2>/dev/null || echo 0)
            if [ "$cmt" -gt "$mtime" ]; then mtime=$cmt; newest="$c"; fi
        fi
    done
    [ -n "$newest" ] && echo "$newest"
}

# Count ERROR-level log lines written in the last N seconds.
# Usage: errors_since 30
errors_since() {
    local seconds="${1:-30}"
    local log; log="$(bot_logfile)"
    [ -z "$log" ] && { echo 0; return; }
    # Cheap heuristic: tail the last 500 lines, grep ERROR, count.
    # Time-based filtering would require parsing each line's timestamp; for
    # smoke this approximation is fine — we just restarted, anything ERROR
    # in the tail is fresh.
    tail -n 500 "$log" 2>/dev/null | grep -cE '\bERROR\b' || true
}
