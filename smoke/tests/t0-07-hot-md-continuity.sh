# T0-07 — hot.md preserves cross-session continuity.
#
# Plant a fact, force a hot.md regeneration (compaction), bounce the bot,
# then ask the same conversation again. The reply must still know the fact
# even though the in-memory chat history is gone — its only path is via
# the hot.md content baked back into the prompt.
#
# Touches bot lifecycle, so this test isn't safe to skip if other tests
# are mid-flight against the same bot. Run it last in any sequence that
# includes it (default run.sh ordering already does — t0-07 sorts before
# t0-09, but t0-09 starts fresh anyway).

test_run_t0_07() {
    if ! bot_is_up; then
        echo "bot is not reachable at $HERALD_BOT_URL"
        return 2
    fi

    local conv_id="smoke-t0-07-$(date +%s)"
    local marker="WGT-99-$$"

    # Turn 1 — establish the fact.
    local reply1
    reply1=$(chat_send \
        "Remember: the smoke widget id is $marker. Just acknowledge with 'OK'." \
        "$conv_id")
    if [ -z "$reply1" ]; then
        echo "turn 1: empty reply"
        return 2
    fi

    # Give hot.md a window to update. The exact trigger depends on whether
    # the bot compacts on every turn, on a timer, or on idle — try a few
    # seconds and accept what we get.
    sleep 5

    # Bounce the bot. We don't fail if `stop` is slow; bot_start will time
    # out if it doesn't come back.
    bot_stop
    if ! bot_start "${HERALD_SMOKE_BOOT_TIMEOUT:-90}"; then
        echo "bot did not come back up after restart"
        return 2
    fi

    # Turn 2 — same conversation id, fresh process. Hot.md is the only
    # mechanism that can carry the fact across the restart.
    local reply2
    reply2=$(chat_send \
        "Earlier in this thread I told you the smoke widget id. What was it?" \
        "$conv_id")
    if [ -z "$reply2" ]; then
        echo "turn 2: empty reply after restart"
        return 2
    fi

    assert_contains "$reply2" "$marker" "post-restart reply" || return 2
    return 0
}

test_name() { echo "hot.md continuity across restart"; }
test_run()  { test_run_t0_07; }
