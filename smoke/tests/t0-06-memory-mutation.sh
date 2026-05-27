# T0-06 — auto-memory mutation tools fire and log.md records them.
#
# We ask the agent to remember a fact with a high-entropy phrase ("smoke
# deadline 2026-12-31"). After the turn finishes:
#
#   1. A new memory page exists somewhere under memories/ that mentions
#      the date.
#   2. log.md grew by at least one line.
#
# Per the smoke plan we accept the noise the test leaves behind: the new
# memory page is cleaned up, but log.md lines are left as-is — truncating
# them would risk racing the live bot's writes.

test_run_t0_06() {
    if ! bot_is_up; then
        echo "bot is not reachable at $HERALD_BOT_URL"
        return 2
    fi

    local marker="SMOKE-DEADLINE-2026-12-31"
    local before_lines after_lines
    before_lines=$(log_line_count)

    # shellcheck disable=SC2317
    cleanup_t0_06() {
        memory_cleanup_smoke "$marker"
    }
    trap cleanup_t0_06 EXIT

    local reply
    reply=$(chat_send \
        "Please remember this fact for me: the $marker is on 2026-12-31. Use your memory tools to save it now." \
        "smoke-t0-06-$(date +%s)")
    local rc=$?
    if [ $rc -ne 0 ]; then
        echo "chat_send failed (rc=$rc)"
        return 2
    fi

    # Give the async memory writer a moment to flush the page + log line.
    sleep 3

    # Assertion 1 — some memory page mentions the marker token.
    local matches; matches=$(memory_grep "$marker")
    if [ -z "$matches" ]; then
        echo "no memory page found mentioning '$marker'"
        echo "reply was: $(printf '%s' "$reply" | head -c 300)"
        return 2
    fi

    # Assertion 2 — log.md grew.
    after_lines=$(log_line_count)
    if [ "$after_lines" -le "$before_lines" ]; then
        echo "log.md did not grow (before=$before_lines after=$after_lines)"
        return 2
    fi

    return 0
}

test_name() { echo "memory mutation + log.md"; }
test_run()  { test_run_t0_06; }
