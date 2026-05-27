# Telegram Bot API helpers. Reads HERALD_SMOKE_TG_BOT_TOKEN and
# HERALD_SMOKE_TG_CHAT_ID from env (set in smoke/.env.smoke).
#
# To avoid dirtying the production chat history, point these at a DEDICATED
# test bot + chat (typically the user DM'd to a second BotFather bot).

tg_enabled() {
    [ -n "${HERALD_SMOKE_TG_BOT_TOKEN:-}" ] && [ -n "${HERALD_SMOKE_TG_CHAT_ID:-}" ]
}

tg_base() {
    echo "https://api.telegram.org/bot${HERALD_SMOKE_TG_BOT_TOKEN}"
}

# Send a message to the test chat. Echoes the message_id on stdout.
tg_send() {
    local text="$1"
    local resp
    resp=$(curl -fsS --max-time 10 \
        -d "chat_id=${HERALD_SMOKE_TG_CHAT_ID}" \
        --data-urlencode "text=$text" \
        "$(tg_base)/sendMessage" 2>&1)
    local rc=$?
    if [ $rc -ne 0 ]; then
        echo "tg_send: HTTP error: $resp" >&2
        return $rc
    fi
    printf '%s' "$resp" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("result",{}).get("message_id",""))' 2>/dev/null
}

# Poll getUpdates for the next message in our chat from the bot itself (i.e.
# Herald's reply), since a given offset and within $timeout seconds. Prints
# the message text on stdout if found.
#
# Usage:
#   tg_wait_for_reply <since_message_id> <timeout_seconds>
tg_wait_for_reply() {
    local since="$1" timeout="${2:-30}"
    local deadline=$(( $(date +%s) + timeout ))
    local last_update_id=0

    # Seed update offset by reading current getUpdates.
    local seed
    seed=$(curl -fsS --max-time 5 "$(tg_base)/getUpdates?limit=1&offset=-1" 2>/dev/null || echo '{}')
    last_update_id=$(printf '%s' "$seed" | python3 -c '
import json,sys
d=json.load(sys.stdin)
r=d.get("result") or []
print(r[-1]["update_id"] if r else 0)
' 2>/dev/null || echo 0)

    while [ "$(date +%s)" -lt "$deadline" ]; do
        local updates
        updates=$(curl -fsS --max-time 5 \
            "$(tg_base)/getUpdates?offset=$((last_update_id+1))&timeout=2&allowed_updates=%5B%22message%22%5D" \
            2>/dev/null || echo '{}')

        local found
        found=$(printf '%s' "$updates" | python3 -c "
import json,sys
d=json.load(sys.stdin)
chat_id=${HERALD_SMOKE_TG_CHAT_ID}
since=${since}
text=''
last=0
for u in d.get('result') or []:
    last=max(last,u.get('update_id',0))
    m=u.get('message') or {}
    if m.get('chat',{}).get('id') != chat_id: continue
    if (m.get('from') or {}).get('is_bot') and m.get('message_id', 0) > since:
        text=m.get('text','')
        break
print(f'{last}\\t{text}')
" 2>/dev/null || echo "0\t")

        local new_last new_text
        new_last="${found%%	*}"
        new_text="${found#*	}"

        if [ "$new_last" != "0" ] && [ "$new_last" -gt "$last_update_id" ]; then
            last_update_id="$new_last"
        fi

        if [ -n "$new_text" ]; then
            printf '%s\n' "$new_text"
            return 0
        fi

        sleep 1
    done

    return 1
}
