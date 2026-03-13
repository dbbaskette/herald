#!/usr/bin/env bash
# Kill ALL Herald-related processes — Java, Maven, tail — everything.
# Usage: ./stop.sh

echo "=== Stopping all Herald processes ==="

# 1. Kill by port (catches the running Spring Boot apps)
for port in 8080 8081; do
    pids=$(lsof -ti :"$port" 2>/dev/null || true)
    if [ -n "$pids" ]; then
        echo "  Port $port: killing PIDs $(echo $pids | tr '\n' ' ')"
        echo "$pids" | xargs kill 2>/dev/null || true
    fi
done

sleep 1

# 2. Kill any Java processes related to Herald
for pattern in herald-bot herald-ui HeraldApplication HeraldUiApplication; do
    pids=$(pgrep -f "java.*${pattern}" 2>/dev/null || true)
    if [ -n "$pids" ]; then
        echo "  Java ($pattern): killing PIDs $(echo $pids | tr '\n' ' ')"
        echo "$pids" | xargs kill 2>/dev/null || true
    fi
done

# 3. Kill Maven wrapper processes for Herald modules
for pattern in "herald-bot" "herald-ui"; do
    pids=$(pgrep -f "classworlds.*${pattern}" 2>/dev/null || true)
    if [ -n "$pids" ]; then
        echo "  Maven ($pattern): killing PIDs $(echo $pids | tr '\n' ' ')"
        echo "$pids" | xargs kill 2>/dev/null || true
    fi
done

sleep 2

# 4. Force-kill anything still alive
for port in 8080 8081; do
    pids=$(lsof -ti :"$port" 2>/dev/null || true)
    if [ -n "$pids" ]; then
        echo "  Port $port still occupied — force-killing PIDs $(echo $pids | tr '\n' ' ')"
        echo "$pids" | xargs kill -9 2>/dev/null || true
    fi
done

for pattern in herald-bot herald-ui HeraldApplication HeraldUiApplication; do
    pids=$(pgrep -f "java.*${pattern}" 2>/dev/null || true)
    if [ -n "$pids" ]; then
        echo "  Force-killing Java ($pattern): PIDs $(echo $pids | tr '\n' ' ')"
        echo "$pids" | xargs kill -9 2>/dev/null || true
    fi
done

# 5. Status check
echo ""
bot_alive=$(lsof -ti :8081 2>/dev/null || true)
ui_alive=$(lsof -ti :8080 2>/dev/null || true)
if [ -z "$bot_alive" ] && [ -z "$ui_alive" ]; then
    echo "=== All Herald processes stopped ==="
else
    echo "WARNING: Some processes survived:"
    [ -n "$bot_alive" ] && echo "  Port 8081: $bot_alive"
    [ -n "$ui_alive" ] && echo "  Port 8080: $ui_alive"
fi
