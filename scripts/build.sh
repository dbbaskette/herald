#!/usr/bin/env bash
# The same source-to-package verification used locally, by Make, and in CI.
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
exec ./mvnw --batch-mode clean verify "$@"
