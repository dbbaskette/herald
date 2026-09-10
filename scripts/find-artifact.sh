#!/usr/bin/env bash
# Resolve the one freshly built executable; never guess a stale version.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
case "${1:-}" in bot|ui) module="herald-$1" ;; *) echo 'Usage: find-artifact.sh bot|ui' >&2; exit 2 ;; esac
shopt -s nullglob
artifacts=("$ROOT/$module/target/$module-"*.jar)
if [ "${#artifacts[@]}" -ne 1 ]; then
    echo "Expected one $module executable JAR; run ./scripts/build.sh to clean and rebuild." >&2
    exit 1
fi
printf '%s\n' "${artifacts[0]}"
