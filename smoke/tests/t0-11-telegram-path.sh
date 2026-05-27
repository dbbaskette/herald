# T0-11 — Telegram transport exercises poller → agent → sender round-trip.
#
# Sends a free-text smoke ping via the Telegram Bot API and asserts a reply
# comes back within 30s. Skipped if the smoke Telegram credentials aren't
# configured.

test_name() { echo "Telegram round-trip"; }

test_run() {
    if ! tg_enabled; then
        echo "skipped: set HERALD_SMOKE_TG_BOT_TOKEN + HERALD_SMOKE_TG_CHAT_ID in smoke/.env.smoke"
        return 77
    fi
    if ! bot_is_up; then
        echo "bot is not reachable at $HERALD_BOT_URL"
        return 2
    fi

    local ts msg_id reply
    ts=$(date +%s)
    msg_id=$(tg_send "smoke ping $ts — please reply with the literal word PONG")
    if [ -z "$msg_id" ]; then
        echo "tg_send returned no message_id"
        return 2
    fi

    reply=$(tg_wait_for_reply "$msg_id" 30)
    if [ -z "$reply" ]; then
        echo "no reply from bot within 30s"
        return 2
    fi

    assert_not_empty "$reply" "Telegram reply" || return 2
    # Soft assertion — the agent might decorate "PONG" but should mention it.
    assert_contains "$reply" "PONG" "Telegram reply" || return 2
    return 0
}
