# T0-04 — POST a free-text message to /api/chat, assert non-empty reply.
#
# Uses a "say the literal word PONG" prompt to keep the assertion stable.
# Won't always make Sonnet say exactly PONG (it may decorate), so we accept
# any reply that contains the substring.

test_name() { echo "basic reply via /api/chat"; }

test_run() {
    if ! bot_is_up; then
        echo "bot is not reachable at $HERALD_BOT_URL"
        return 2
    fi

    local reply
    reply=$(chat_send "Reply with the single word PONG and nothing else." \
                       "smoke-t0-04-$(date +%s)")
    local rc=$?
    if [ $rc -ne 0 ]; then
        echo "chat_send failed (rc=$rc)"
        return 2
    fi

    assert_not_empty "$reply" "reply" || return 2
    assert_contains "$reply" "PONG" "reply" || return 2
    return 0
}
