# ANSI color helpers. Disabled automatically when stdout isn't a TTY.

if [ -t 1 ] && [ "${OUTPUT:-human}" = "human" ]; then
    RESET=$'\033[0m'
    BOLD=$'\033[1m'
    DIM=$'\033[2m'
    RED=$'\033[31m'
    GREEN=$'\033[32m'
    YELLOW=$'\033[33m'
    CYAN=$'\033[36m'
else
    RESET=""; BOLD=""; DIM=""; RED=""; GREEN=""; YELLOW=""; CYAN=""
fi
export RESET BOLD DIM RED GREEN YELLOW CYAN
