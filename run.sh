#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"
BOT_PORT=8081
UI_PORT=8080

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

# ── Helpers ──────────────────────────────────────────────────────────
kill_herald() {
    local module=$1  # "herald-bot" or "herald-ui"
    local port=$2
    local found=0

    # Kill by port
    local pids
    pids=$(lsof -ti :"$port" 2>/dev/null || true)
    if [ -n "$pids" ]; then
        echo "Killing process on port $port (PID: $pids)"
        echo "$pids" | xargs kill 2>/dev/null || true
        found=1
    fi

    # Kill by process name (catches zombies that never bound to a port)
    pids=$(pgrep -f "$module" 2>/dev/null || true)
    if [ -n "$pids" ]; then
        echo "Killing $module processes (PID: $pids)"
        echo "$pids" | xargs kill 2>/dev/null || true
        found=1
    fi

    if [ "$found" -eq 1 ]; then
        sleep 1
        # Force kill any survivors
        pids=$(pgrep -f "$module" 2>/dev/null || true)
        if [ -n "$pids" ]; then
            echo "Force-killing $module (PID: $pids)"
            echo "$pids" | xargs kill -9 2>/dev/null || true
            sleep 1
        fi
    fi
}

check_env() {
    local missing=0
    for var in ANTHROPIC_API_KEY HERALD_TELEGRAM_BOT_TOKEN HERALD_TELEGRAM_ALLOWED_CHAT_ID; do
        if [ -z "${!var:-}" ]; then
            echo "ERROR: $var is not set in .env"
            missing=1
        fi
    done
    [ "$missing" -eq 1 ] && exit 1
}

# ── Parse command ────────────────────────────────────────────────────
cmd="${1:-all}"

case "$cmd" in
    bot)
        check_env
        kill_herald "herald-bot" "$BOT_PORT"
        echo "Starting herald-bot on port $BOT_PORT..."
        cd "$SCRIPT_DIR"
        ./mvnw -pl herald-bot spring-boot:run
        ;;
    ui)
        kill_herald "herald-ui" "$UI_PORT"
        echo "Starting herald-ui on port $UI_PORT..."
        cd "$SCRIPT_DIR"
        ./mvnw -pl herald-ui spring-boot:run
        ;;
    all)
        check_env
        kill_herald "herald-bot" "$BOT_PORT"
        kill_herald "herald-ui" "$UI_PORT"
        echo "Starting herald-bot (port $BOT_PORT) and herald-ui (port $UI_PORT)..."
        cd "$SCRIPT_DIR"
        ./mvnw -pl herald-bot spring-boot:run &
        BOT_PID=$!
        ./mvnw -pl herald-ui spring-boot:run &
        UI_PID=$!
        trap "kill $BOT_PID $UI_PID 2>/dev/null" EXIT
        wait
        ;;
    stop)
        echo "Stopping herald services..."
        kill_herald "herald-bot" "$BOT_PORT"
        kill_herald "herald-ui" "$UI_PORT"
        echo "Done."
        ;;
    build)
        echo "Building all modules..."
        cd "$SCRIPT_DIR"
        ./mvnw package -DskipTests
        ;;
    *)
        echo "Usage: ./run.sh [command]"
        echo ""
        echo "  all    Start bot + ui together (default)"
        echo "  bot    Start herald-bot only (port $BOT_PORT)"
        echo "  ui     Start herald-ui only (port $UI_PORT)"
        echo "  stop   Stop all herald services"
        echo "  build  Build all modules"
        exit 1
        ;;
esac
