#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"

# ── Load .env ────────────────────────────────────────────────────────
if [ -f "$ENV_FILE" ]; then
    set -a
    source "$ENV_FILE"
    set +a
else
    echo "No .env file found. Copy the example and fill in your values:"
    echo "  cp .env.example .env"
    exit 1
fi

# ── Validate required vars ───────────────────────────────────────────
missing=0
for var in ANTHROPIC_API_KEY HERALD_TELEGRAM_BOT_TOKEN HERALD_TELEGRAM_ALLOWED_CHAT_ID; do
    if [ -z "${!var:-}" ]; then
        echo "ERROR: $var is not set in .env"
        missing=1
    fi
done
[ "$missing" -eq 1 ] && exit 1

# ── Parse command ────────────────────────────────────────────────────
cmd="${1:-bot}"

case "$cmd" in
    bot)
        echo "Starting herald-bot..."
        cd "$SCRIPT_DIR"
        ./mvnw -pl herald-bot spring-boot:run
        ;;
    ui)
        echo "Starting herald-ui..."
        cd "$SCRIPT_DIR"
        ./mvnw -pl herald-ui spring-boot:run
        ;;
    build)
        echo "Building all modules..."
        cd "$SCRIPT_DIR"
        ./mvnw package -DskipTests
        ;;
    all)
        echo "Starting herald-bot and herald-ui..."
        cd "$SCRIPT_DIR"
        ./mvnw -pl herald-bot spring-boot:run &
        BOT_PID=$!
        ./mvnw -pl herald-ui spring-boot:run &
        UI_PID=$!
        trap "kill $BOT_PID $UI_PID 2>/dev/null" EXIT
        wait
        ;;
    *)
        echo "Usage: ./run.sh [bot|ui|build|all]"
        echo ""
        echo "  bot    Start herald-bot (default)"
        echo "  ui     Start herald-ui console"
        echo "  build  Build all modules"
        echo "  all    Start bot and ui together"
        exit 1
        ;;
esac
