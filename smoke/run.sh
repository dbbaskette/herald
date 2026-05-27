#!/usr/bin/env bash
# Herald smoke harness — entry point.
#
# Exercises Tier 0 (default) or Tier 1 capabilities end-to-end against a
# live herald-bot instance. See NORTHSTAR.md for the tier definitions and
# docs/smoke-harness-plan.md for the per-test specs.
#
# Exit codes:
#   0 — all tests passed
#   1 — pass with warnings (slow tests; not yet wired)
#   2 — at least one test failed
#
# Usage:
#   ./smoke/run.sh                # human output, Tier 0
#   ./smoke/run.sh --tier 1       # weekly Tier 1 (not yet implemented)
#   ./smoke/run.sh --tier 0,1     # both
#   ./smoke/run.sh --only T0-04   # single test by id
#   ./smoke/run.sh --json         # machine-readable
#   ./smoke/run.sh --quiet        # exit codes only

set -uo pipefail

SMOKE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
HERALD_DIR="$(cd "$SMOKE_DIR/.." && pwd)"
export SMOKE_DIR HERALD_DIR

# ─── Defaults + arg parsing ────────────────────────────────────────────────
TIERS="0"
ONLY=""
OUTPUT="human"   # human | json | quiet

while [ $# -gt 0 ]; do
    case "$1" in
        --tier)   TIERS="$2"; shift 2 ;;
        --tier=*) TIERS="${1#*=}"; shift ;;
        --only)   ONLY="$2"; shift 2 ;;
        --only=*) ONLY="${1#*=}"; shift ;;
        --json)   OUTPUT="json"; shift ;;
        --quiet)  OUTPUT="quiet"; shift ;;
        -h|--help)
            sed -n '2,20p' "${BASH_SOURCE[0]}" | sed 's/^# \?//'
            exit 0
            ;;
        *)
            echo "Unknown option: $1" >&2
            exit 2
            ;;
    esac
done
export OUTPUT

# ─── Load env (.env.smoke wins over .env) ──────────────────────────────────
if [ -f "$SMOKE_DIR/.env.smoke" ]; then
    set -a; . "$SMOKE_DIR/.env.smoke"; set +a
elif [ -f "$HERALD_DIR/.env" ]; then
    set -a; . "$HERALD_DIR/.env"; set +a
fi

# Defaults — only set if the env didn't supply.
: "${HERALD_BOT_URL:=http://127.0.0.1:8081}"
: "${HERALD_SMOKE_TIMEOUT:=15}"
export HERALD_BOT_URL HERALD_SMOKE_TIMEOUT

# ─── Helpers ───────────────────────────────────────────────────────────────
. "$SMOKE_DIR/lib/colors.sh"
. "$SMOKE_DIR/lib/assert.sh"
. "$SMOKE_DIR/lib/bot.sh"
. "$SMOKE_DIR/lib/chat.sh"
. "$SMOKE_DIR/lib/tg.sh"
. "$SMOKE_DIR/lib/db.sh"
. "$SMOKE_DIR/lib/memory.sh"

# Per-test contract: each test file defines test_name() and test_run().
# test_run returns 0 (pass), 1 (warn), 2 (fail), 77 (skip).
TEST_PASSED=0
TEST_FAILED=0
TEST_WARNED=0
TEST_SKIPPED=0
FAILED_IDS=()

# Buffer JSON results so we can emit a single array at the end.
JSON_RESULTS=()

run_one_test() {
    local file="$1"
    local id="$(basename "$file" | cut -d- -f1-2 | tr 'a-z' 'A-Z')"
    # File names are t0-04-basic-reply.sh → T0-04
    id="$(echo "$(basename "$file")" | sed -E 's/^(t[0-9]+-[0-9]+).*/\1/' | tr 'a-z' 'A-Z')"

    # Skip-by-filter
    if [ -n "$ONLY" ] && [ "$id" != "$ONLY" ]; then return 0; fi

    # Each test runs in a subshell so failures + env leaks don't bleed.
    local name="" rc=0 ms=0 detail=""
    local start_ns end_ns

    # shellcheck disable=SC1090
    name="$(. "$file"; test_name)"

    [ "$OUTPUT" = "human" ] && printf "  %s %-44s " "${CYAN}▸${RESET}" "$id $name"

    start_ns=$(date +%s%N 2>/dev/null || python3 -c 'import time;print(int(time.time()*1e9))')
    detail=$(
        # shellcheck disable=SC1090
        ( . "$file"; test_run ) 2>&1
    )
    rc=$?
    end_ns=$(date +%s%N 2>/dev/null || python3 -c 'import time;print(int(time.time()*1e9))')
    ms=$(( (end_ns - start_ns) / 1000000 ))

    local status_human=""
    case $rc in
        0)  TEST_PASSED=$((TEST_PASSED+1));   status_human="${GREEN}PASS${RESET}" ;;
        1)  TEST_WARNED=$((TEST_WARNED+1));   status_human="${YELLOW}WARN${RESET}" ;;
        77) TEST_SKIPPED=$((TEST_SKIPPED+1)); status_human="${DIM}SKIP${RESET}" ;;
        *)  TEST_FAILED=$((TEST_FAILED+1));   status_human="${RED}FAIL${RESET}"
            FAILED_IDS+=("$id") ;;
    esac

    if [ "$OUTPUT" = "human" ]; then
        printf "%s  %5dms\n" "$status_human" "$ms"
        if [ $rc -ne 0 ] && [ $rc -ne 77 ] && [ -n "$detail" ]; then
            echo "$detail" | sed 's/^/      /'
        fi
    elif [ "$OUTPUT" = "json" ]; then
        local status_json="pass"
        case $rc in 1) status_json="warn" ;; 77) status_json="skip" ;; 2|*) [ $rc -ne 0 ] && status_json="fail" ;; esac
        # Escape detail for JSON
        local detail_esc
        detail_esc=$(printf '%s' "$detail" | python3 -c 'import json,sys;print(json.dumps(sys.stdin.read()))')
        JSON_RESULTS+=("{\"id\":\"$id\",\"name\":\"$(printf '%s' "$name" | sed 's/"/\\"/g')\",\"status\":\"$status_json\",\"latency_ms\":$ms,\"detail\":$detail_esc}")
    fi
}

# ─── Header ────────────────────────────────────────────────────────────────
if [ "$OUTPUT" = "human" ]; then
    printf "\n%sHerald smoke%s — tiers: %s   bot: %s\n\n" \
        "${BOLD}" "${RESET}" "$TIERS" "$HERALD_BOT_URL"
fi

# ─── Collect tests for requested tiers ─────────────────────────────────────
TEST_FILES=()
for tier in $(echo "$TIERS" | tr ',' ' '); do
    while IFS= read -r f; do
        TEST_FILES+=("$f")
    done < <(find "$SMOKE_DIR/tests" -maxdepth 1 -name "t${tier}-*.sh" -type f 2>/dev/null | sort)
done

if [ ${#TEST_FILES[@]} -eq 0 ]; then
    echo "No tests found for tier(s): $TIERS" >&2
    exit 2
fi

# ─── Run ───────────────────────────────────────────────────────────────────
for f in "${TEST_FILES[@]}"; do
    run_one_test "$f"
done

# ─── Output ────────────────────────────────────────────────────────────────
if [ "$OUTPUT" = "human" ]; then
    echo
    printf "  %sPassed:%s  %d   " "${GREEN}" "${RESET}" "$TEST_PASSED"
    printf "%sFailed:%s  %d   " "${RED}"   "${RESET}" "$TEST_FAILED"
    printf "%sWarn:%s    %d   " "${YELLOW}" "${RESET}" "$TEST_WARNED"
    printf "%sSkip:%s    %d\n\n" "${DIM}"  "${RESET}" "$TEST_SKIPPED"
    if [ ${#FAILED_IDS[@]} -gt 0 ]; then
        echo "  Failed: ${FAILED_IDS[*]}"
        echo
    fi
elif [ "$OUTPUT" = "json" ]; then
    # Manually compose array — keep zero deps.
    printf '{"tiers":"%s","results":[' "$TIERS"
    for i in "${!JSON_RESULTS[@]}"; do
        [ $i -gt 0 ] && printf ','
        printf '%s' "${JSON_RESULTS[$i]}"
    done
    printf '],"summary":{"passed":%d,"failed":%d,"warned":%d,"skipped":%d}}\n' \
        "$TEST_PASSED" "$TEST_FAILED" "$TEST_WARNED" "$TEST_SKIPPED"
fi

# ─── Exit ──────────────────────────────────────────────────────────────────
if [ "$TEST_FAILED" -gt 0 ]; then exit 2; fi
if [ "$TEST_WARNED" -gt 0 ]; then exit 1; fi
exit 0
