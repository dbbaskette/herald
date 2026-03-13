#!/usr/bin/env bash
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ENV_FILE="$SCRIPT_DIR/.env"
BOT_PORT=8081
UI_PORT=8080
LOG_DIR="$SCRIPT_DIR/logs"

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

# ── Bootstrap bundled skills ─────────────────────────────────────────
HERALD_HOME="${HERALD_HOME:-$HOME/.herald}"
BUNDLED_SKILLS_DIR="$SCRIPT_DIR/.claude/skills"
RUNTIME_SKILLS_DIR="$HERALD_HOME/skills"
if [ -d "$BUNDLED_SKILLS_DIR" ]; then
    for skill_dir in "$BUNDLED_SKILLS_DIR"/*/; do
        skill_name="$(basename "$skill_dir")"
        target_dir="$RUNTIME_SKILLS_DIR/$skill_name"
        if [ ! -d "$target_dir" ]; then
            mkdir -p "$target_dir"
            cp -r "$skill_dir"* "$target_dir"/
            echo "  Bootstrapped skill: $skill_name"
        fi
    done
fi

# ── Check Google Workspace CLI auth ──────────────────────────────────
if command -v gws &>/dev/null; then
    gws_auth=$(gws auth status 2>/dev/null | grep -o '"auth_method": "[^"]*"' | cut -d'"' -f4)
    if [ "$gws_auth" = "none" ] || [ -z "$gws_auth" ]; then
        echo "  ⚠  Google Workspace CLI (gws) installed but not authenticated."
        echo "     Gmail/Calendar/Drive skills will be unavailable."
        echo "     Run: source .env && gws auth login -s gmail,calendar,drive"
        echo "     See: docs/gws-setup.md"
        echo ""
    fi
else
    echo "  ⚠  Google Workspace CLI (gws) not found — Google skills unavailable."
    echo "     Install: brew install googleworkspace-cli"
    echo "     See: docs/gws-setup.md"
    echo ""
fi

# ── Helpers ──────────────────────────────────────────────────────────
kill_on_port() {
    local port=$1
    local pids
    pids=$(lsof -ti :"$port" 2>/dev/null || true)
    if [ -n "$pids" ]; then
        echo "  Killing PID(s) on port $port: $(echo $pids | tr '\n' ' ')"
        echo "$pids" | xargs kill 2>/dev/null || true
        sleep 2
        # Force-kill survivors
        pids=$(lsof -ti :"$port" 2>/dev/null || true)
        if [ -n "$pids" ]; then
            echo "  Force-killing PID(s) on port $port: $(echo $pids | tr '\n' ' ')"
            echo "$pids" | xargs kill -9 2>/dev/null || true
            sleep 1
        fi
    fi
}

kill_java_procs() {
    local pattern=$1
    local pids
    pids=$(pgrep -f "java.*${pattern}" 2>/dev/null | grep -v $$ || true)
    if [ -n "$pids" ]; then
        echo "  Killing Java processes matching '$pattern': $(echo $pids | tr '\n' ' ')"
        echo "$pids" | xargs kill 2>/dev/null || true
        sleep 1
        pids=$(pgrep -f "java.*${pattern}" 2>/dev/null | grep -v $$ || true)
        if [ -n "$pids" ]; then
            echo "  Force-killing: $(echo $pids | tr '\n' ' ')"
            echo "$pids" | xargs kill -9 2>/dev/null || true
        fi
    fi
}

stop_module() {
    local module=$1
    local port=$2
    echo "Stopping $module..."
    kill_on_port "$port"
    kill_java_procs "$module"
    # Kill Maven wrapper processes
    local mvn_pids
    mvn_pids=$(pgrep -f "classworlds.*-pl ${module}" 2>/dev/null | grep -v $$ || true)
    if [ -n "$mvn_pids" ]; then
        echo "  Killing Maven for $module: $(echo $mvn_pids | tr '\n' ' ')"
        echo "$mvn_pids" | xargs kill 2>/dev/null || true
    fi
}

wait_for_port() {
    local port=$1
    local name=$2
    local timeout=90
    local elapsed=0
    while [ $elapsed -lt $timeout ]; do
        if lsof -ti :"$port" &>/dev/null; then
            return 0
        fi
        sleep 2
        elapsed=$((elapsed + 2))
    done
    return 1
}

check_env() {
    local missing=0

    if [ -z "${ANTHROPIC_API_KEY:-}" ]; then
        echo "ERROR: ANTHROPIC_API_KEY is not set in .env"
        echo "       Run 'claude setup-token' and add the token to .env"
        missing=1
    fi

    for var in HERALD_TELEGRAM_BOT_TOKEN HERALD_TELEGRAM_ALLOWED_CHAT_ID; do
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
        stop_module "herald-bot" "$BOT_PORT"
        echo "Starting herald-bot on port $BOT_PORT..."
        cd "$SCRIPT_DIR"
        ./mvnw -pl herald-bot spring-boot:run
        ;;
    ui)
        stop_module "herald-ui" "$UI_PORT"
        echo "Starting herald-ui on port $UI_PORT..."
        cd "$SCRIPT_DIR"
        ./mvnw -pl herald-ui spring-boot:run
        ;;
    all)
        check_env
        mkdir -p "$LOG_DIR"
        stop_module "herald-bot" "$BOT_PORT"
        stop_module "herald-ui" "$UI_PORT"
        sleep 1

        echo ""
        echo "=== Starting Herald ==="
        echo "  Logs: $LOG_DIR/"
        echo ""

        cd "$SCRIPT_DIR"

        # Start both modules, log to files
        ./mvnw -pl herald-bot spring-boot:run > "$LOG_DIR/herald-bot.log" 2>&1 &
        BOT_PID=$!
        echo "  [bot] Maven started (PID $BOT_PID)"

        ./mvnw -pl herald-ui spring-boot:run > "$LOG_DIR/herald-ui.log" 2>&1 &
        UI_PID=$!
        echo "  [ui]  Maven started (PID $UI_PID)"

        # Cleanup on exit — kill everything by port (catches child Java processes)
        cleanup() {
            echo ""
            echo "=== Shutting down Herald ==="
            # Kill the Maven wrappers first
            kill $BOT_PID 2>/dev/null || true
            kill $UI_PID 2>/dev/null || true
            sleep 2
            # Then kill anything still on the ports
            kill_on_port "$BOT_PORT"
            kill_on_port "$UI_PORT"
            # Mop up any orphaned Java processes
            kill_java_procs "HeraldApplication" 2>/dev/null || true
            kill_java_procs "HeraldUiApplication" 2>/dev/null || true
            echo "=== Herald stopped ==="
        }
        trap cleanup EXIT INT TERM

        # Wait for ports to come up
        echo ""
        echo "  Waiting for herald-bot on port $BOT_PORT..."
        if wait_for_port "$BOT_PORT" "herald-bot"; then
            echo "  [bot] Ready: http://localhost:$BOT_PORT"
        else
            echo "  [bot] FAILED to start. Check $LOG_DIR/herald-bot.log"
            echo "  Last 10 lines:"
            tail -10 "$LOG_DIR/herald-bot.log" 2>/dev/null | sed 's/^/    /'
        fi

        echo "  Waiting for herald-ui on port $UI_PORT..."
        if wait_for_port "$UI_PORT" "herald-ui"; then
            echo "  [ui]  Ready: http://localhost:$UI_PORT"
        else
            echo "  [ui]  FAILED to start. Check $LOG_DIR/herald-ui.log"
            echo "  Last 10 lines:"
            tail -10 "$LOG_DIR/herald-ui.log" 2>/dev/null | sed 's/^/    /'
        fi

        echo ""
        echo "=== Herald is running ==="
        echo "  Bot: http://localhost:$BOT_PORT"
        echo "  UI:  http://localhost:$UI_PORT"
        echo ""
        echo "  Ctrl+C to stop. Tailing logs..."
        echo ""

        # Tail both logs with prefixes
        tail -f "$LOG_DIR/herald-bot.log" 2>/dev/null | sed 's/^/[bot] /' &
        TAIL1=$!
        tail -f "$LOG_DIR/herald-ui.log" 2>/dev/null | sed 's/^/[ui]  /' &
        TAIL2=$!

        # Wait for either Maven process to exit (crash = exit)
        wait $BOT_PID $UI_PID 2>/dev/null
        kill $TAIL1 $TAIL2 2>/dev/null || true
        ;;
    stop)
        echo "Stopping herald services..."
        stop_module "herald-bot" "$BOT_PORT"
        stop_module "herald-ui" "$UI_PORT"
        echo "Done."
        ;;
    status)
        echo "Herald status:"
        bot_pid=$(lsof -ti :$BOT_PORT 2>/dev/null || true)
        if [ -n "$bot_pid" ]; then
            echo "  [bot] RUNNING (PID $bot_pid) on port $BOT_PORT"
        else
            echo "  [bot] STOPPED"
        fi
        ui_pid=$(lsof -ti :$UI_PORT 2>/dev/null || true)
        if [ -n "$ui_pid" ]; then
            echo "  [ui]  RUNNING (PID $ui_pid) on port $UI_PORT"
        else
            echo "  [ui]  STOPPED"
        fi
        ;;
    logs)
        if [ ! -d "$LOG_DIR" ]; then
            echo "No logs directory. Start services first: ./run.sh all"
            exit 1
        fi
        module="${2:-all}"
        case "$module" in
            bot)  tail -f "$LOG_DIR/herald-bot.log" ;;
            ui)   tail -f "$LOG_DIR/herald-ui.log" ;;
            all)
                tail -f "$LOG_DIR/herald-bot.log" | sed 's/^/[bot] /' &
                tail -f "$LOG_DIR/herald-ui.log" | sed 's/^/[ui]  /' &
                trap "kill %1 %2 2>/dev/null" EXIT INT TERM
                wait
                ;;
            *)    echo "Usage: ./run.sh logs [bot|ui|all]"; exit 1 ;;
        esac
        ;;
    build)
        echo "Building all modules..."
        cd "$SCRIPT_DIR"
        ./mvnw package -DskipTests
        ;;
    restart)
        module="${2:-all}"
        case "$module" in
            bot)
                check_env
                stop_module "herald-bot" "$BOT_PORT"
                echo "Starting herald-bot on port $BOT_PORT..."
                cd "$SCRIPT_DIR"
                ./mvnw -pl herald-bot spring-boot:run
                ;;
            ui)
                stop_module "herald-ui" "$UI_PORT"
                echo "Starting herald-ui on port $UI_PORT..."
                cd "$SCRIPT_DIR"
                ./mvnw -pl herald-ui spring-boot:run
                ;;
            all)
                "$0" stop
                sleep 1
                exec "$0" all
                ;;
            *)  echo "Usage: ./run.sh restart [bot|ui|all]"; exit 1 ;;
        esac
        ;;
    *)
        echo "Usage: ./run.sh [command]"
        echo ""
        echo "  all            Start bot + ui (default)"
        echo "  bot            Start herald-bot only"
        echo "  ui             Start herald-ui only"
        echo "  stop           Stop all services"
        echo "  restart [mod]  Restart bot, ui, or all"
        echo "  status         Show running services"
        echo "  logs [mod]     Tail logs for bot, ui, or all"
        echo "  build          Build all modules"
        exit 1
        ;;
esac
