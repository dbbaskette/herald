# T0-09 — /status Telegram command returns a well-formed status response.
#
# /status is a Telegram CommandHandler, not an /api/chat agent turn, so this
# test goes through the Telegram transport. It's skipped if the smoke
# Telegram credentials aren't configured.

test_name() { echo "/status command (Telegram)"; }

test_run() {
    if ! tg_enabled; then
        echo "skipped: set HERALD_SMOKE_TG_BOT_TOKEN + HERALD_SMOKE_TG_CHAT_ID in smoke/.env.smoke"
        return 77
    fi
    if ! bot_is_up; then
        echo "bot is not reachable at $HERALD_BOT_URL"
        return 2
    fi

    local msg_id reply
    msg_id=$(tg_send "/status")
    if [ -z "$msg_id" ]; then
        echo "tg_send returned no message_id"
        return 2
    fi

    reply=$(tg_wait_for_reply "$msg_id" 30)
    if [ -z "$reply" ]; then
        echo "no reply from bot within 30s"
        return 2
    fi

    # /status output mentions uptime, the active model, tools — assert a few
    # substrings that have been stable across recent versions.
    assert_contains "$reply" "uptime" "/status reply" || return 2
    assert_contains "$reply" "model"  "/status reply" || return 2
    return 0
}
