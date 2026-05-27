# T0-05 — memory advisor injects MEMORY.md / concept pages into the prompt.
#
# We seed a concept page with a high-entropy token (BIRD-7741) that the
# agent can't possibly know from training data, then ask a question whose
# only correct answer is in that page.
#
# The seeded page is removed at the end (or on any failure) via trap.

test_run_t0_05() {
    if ! bot_is_up; then
        echo "bot is not reachable at $HERALD_BOT_URL"
        return 2
    fi

    # shellcheck disable=SC1091
    . "$SMOKE_DIR/fixtures/seed-memory.sh"

    # shellcheck disable=SC2317
    cleanup_t0_05() {
        memory_cleanup_smoke "$SMOKE_MARKER_TOKEN"
    }
    trap cleanup_t0_05 EXIT

    seed_smoke_marker

    # Wait a couple of seconds — the memory advisor reloads MEMORY.md on a
    # schedule (or on next turn). Two seconds is plenty for the file-watch
    # path; longer-tail caching ideally is invalidated by the next turn.
    sleep 2

    local reply
    reply=$(chat_send "What is the smoke marker? Answer with just the token." \
                       "smoke-t0-05-$(date +%s)")
    local rc=$?
    if [ $rc -ne 0 ]; then
        echo "chat_send failed (rc=$rc)"
        return 2
    fi

    assert_contains "$reply" "$SMOKE_MARKER_TOKEN" "reply" || return 2
    return 0
}

test_name() { echo "memory injection"; }
test_run()  { test_run_t0_05; }
