# Memory-page helpers. The bot's memory dir defaults to ~/.herald/memories
# but can be overridden by HERALD_MEMORIES_DIR — matches the Java resolution
# in MemoryDirCheck.java.
#
# Conventions used by the tests:
#   - Concept pages live at        memories/concepts/<slug>.md
#   - Sources live at              memories/sources/...
#   - The consolidated index is    memories/MEMORY.md
#   - The append-only event log is memories/log.md
#   - Compacted hot context is     memories/hot.md
#
# Smoke artifacts use the prefix `smoke-` in the slug so cleanup-by-prefix
# is unambiguous.

memory_dir() {
    local raw="${HERALD_MEMORIES_DIR:-$HOME/.herald/memories}"
    case "$raw" in
        "~/"*) raw="$HOME/${raw#\~/}" ;;
    esac
    printf '%s\n' "$raw"
}

memory_index_file() { echo "$(memory_dir)/MEMORY.md"; }
memory_log_file()   { echo "$(memory_dir)/log.md"; }
memory_hot_file()   { echo "$(memory_dir)/hot.md"; }

# Count lines in log.md. 0 if missing.
log_line_count() {
    local f; f="$(memory_log_file)"
    [ -f "$f" ] || { echo 0; return; }
    wc -l <"$f" | tr -d ' '
}

# Write a concept page. Caller supplies the slug (without .md) and the body.
# Returns the absolute path on stdout.
memory_write_concept() {
    local slug="$1" body="$2"
    local dir="$(memory_dir)/concepts"
    mkdir -p "$dir"
    local path="$dir/$slug.md"
    printf '%s' "$body" >"$path"
    printf '%s\n' "$path"
}

# Find any memory pages (under memories/) whose contents match a substring.
# Used by tests that don't know exactly where the bot decided to write a new
# entry. Echoes matching paths one per line.
memory_grep() {
    local needle="$1"
    grep -lr --include='*.md' -F "$needle" "$(memory_dir)" 2>/dev/null || true
}

# Cleanup: remove every concept page whose filename starts with `smoke-`,
# every source whose filename starts with `smoke-`, and any path that grep
# turns up matching one of the test markers passed as args.
memory_cleanup_smoke() {
    local root; root="$(memory_dir)"
    find "$root" -type f -name 'smoke-*.md' -delete 2>/dev/null || true
    for marker in "$@"; do
        # Drop any other pages that mention the marker — covers entries the
        # agent created with names we don't control.
        while IFS= read -r f; do
            [ -n "$f" ] && rm -f "$f"
        done < <(memory_grep "$marker")
    done
}
