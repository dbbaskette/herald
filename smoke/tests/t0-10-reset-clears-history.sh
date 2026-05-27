# T0-10 — /reset actually clears the chat memory for a conversation.
#
# /reset is a Telegram CommandHandler, not a chat-controller endpoint, so
# this test runs through the Telegram transport. It's skipped if the smoke
# Telegram credentials aren't configured.
#
# Procedure:
#   1. Send a turn that plants a high-entropy phrase.
#   2. Send "/reset" and wait for the confirmation.
#   3. Ask "what was the smoke phrase?". The reply should NOT contain the
#      phrase, because chat memory is gone.
#
# We deliberately don't try to assert what the bot DOES say — only that
# it no longer knows the phrase.

test_run_t0_10() {
    if ! tg_enabled; then
        echo "skipped: set HERALD_SMOKE_TG_BOT_TOKEN + HERALD_SMOKE_TG_CHAT_ID in smoke/.env.smoke"
        return 77
    fi
    if ! bot_is_up; then
        echo "bot is not reachable at $HERALD_BOT_URL"
        return 2
    fi

    local phrase="PURPLE-OCEAN-$$-$(date +%s)"

    # Turn 1 — plant the phrase.
    local plant_id plant_reply
    plant_id=$(tg_send "Remember: the smoke phrase is $phrase. Just say OK.")
    [ -z "$plant_id" ] && { echo "tg_send (plant) returned no id"; return 2; }
    plant_reply=$(tg_wait_for_reply "$plant_id" 30)
    if [ -z "$plant_reply" ]; then
        echo "no reply to plant turn within 30s"
        return 2
    fi

    # Turn 2 — /reset. The command handler responds immediately.
    local reset_id reset_reply
    reset_id=$(tg_send "/reset")
    [ -z "$reset_id" ] && { echo "tg_send (/reset) returned no id"; return 2; }
    reset_reply=$(tg_wait_for_reply "$reset_id" 15)
    if [ -z "$reset_reply" ]; then
        echo "no reply to /reset within 15s"
        return 2
    fi

    # Turn 3 — ask for the phrase. Memory should be gone.
    local query_id query_reply
    query_id=$(tg_send "What was the smoke phrase I told you earlier?")
    [ -z "$query_id" ] && { echo "tg_send (query) returned no id"; return 2; }
    query_reply=$(tg_wait_for_reply "$query_id" 30)
    if [ -z "$query_reply" ]; then
        echo "no reply to post-reset query within 30s"
        return 2
    fi

    # Negative assertion — the phrase must NOT survive the reset.
    case "$query_reply" in
        *"$phrase"*)
            echo "ASSERT post-reset reply: must NOT contain '$phrase'"
            echo "  got: $(printf '%s' "$query_reply" | head -c 300)"
            return 2
            ;;
        *) return 0 ;;
    esac
}

test_name() { echo "/reset clears history (Telegram)"; }
test_run()  { test_run_t0_10; }
