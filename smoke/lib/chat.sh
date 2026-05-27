# HTTP chat client — POSTs to /api/chat and returns the agent's reply text.

# Usage:
#   chat_send "your message" [conversation_id]
# Prints the reply on stdout; non-zero on transport error or agent error.
chat_send() {
    local message="$1"
    local conv_id="${2:-smoke-$(date +%Y%m%d-%H%M%S)-$$}"

    local body resp reply err
    body=$(printf '{"message":%s,"conversationId":%s}' \
        "$(json_string "$message")" "$(json_string "$conv_id")")

    resp=$(curl -fsS --max-time "$HERALD_SMOKE_TIMEOUT" \
        -H 'Content-Type: application/json' \
        -d "$body" \
        "$HERALD_BOT_URL/api/chat" 2>&1)
    local rc=$?
    if [ $rc -ne 0 ]; then
        echo "chat_send: HTTP error: $resp" >&2
        return $rc
    fi

    err=$(printf '%s' "$resp" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("error") or "")' 2>/dev/null || echo "")
    reply=$(printf '%s' "$resp" | python3 -c 'import json,sys; d=json.load(sys.stdin); print(d.get("reply") or "")' 2>/dev/null || echo "")

    if [ -n "$err" ]; then
        echo "chat_send: agent error: $err" >&2
        return 1
    fi
    printf '%s\n' "$reply"
}

# JSON-encode a single string. Uses python3 to be safe with quotes/newlines.
json_string() {
    python3 -c 'import json,sys;print(json.dumps(sys.argv[1]))' "$1"
}
