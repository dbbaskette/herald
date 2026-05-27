#!/usr/bin/env bash
# Seed a memory concept page that T0-05 expects.
#
# Usage (sourced from a test):
#   . "$SMOKE_DIR/lib/memory.sh"
#   . "$SMOKE_DIR/fixtures/seed-memory.sh"
#   seed_smoke_marker
#
# Standalone:
#   ./smoke/fixtures/seed-memory.sh

# The marker is intentionally a high-entropy token so a generic-knowledge
# answer can't satisfy the assertion. If the agent says "BIRD-7741", it
# learned it from MEMORY.md, not from training data.
SMOKE_MARKER_SLUG="smoke-marker"
SMOKE_MARKER_TOKEN="BIRD-7741"
SMOKE_MARKER_BODY=$'---\nname: smoke-marker\ndescription: Token referenced by the smoke harness to verify memory injection.\nmetadata:\n  type: project\n---\n\nThe smoke marker is `BIRD-7741`. This page exists only so the smoke harness\ncan verify that memory injection actually shapes replies. Cleaned up at the\nend of every smoke run.\n'

seed_smoke_marker() {
    memory_write_concept "$SMOKE_MARKER_SLUG" "$SMOKE_MARKER_BODY" >/dev/null
}

# When executed directly, seed and print the path.
if [ "${BASH_SOURCE[0]}" = "$0" ]; then
    # shellcheck disable=SC1091
    . "$(dirname "$0")/../lib/memory.sh"
    seed_smoke_marker
    echo "$(memory_dir)/concepts/$SMOKE_MARKER_SLUG.md"
fi
