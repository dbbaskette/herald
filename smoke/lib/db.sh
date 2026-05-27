# SQLite helpers — Herald's runtime DB lives at HERALD_DB_PATH (defaults to
# ~/.herald/herald.db). These helpers keep the smoke tests from having to
# repeat the path-resolution dance.

# Resolve the runtime DB path. Echoes the absolute path on stdout; non-zero
# if the file doesn't exist.
db_path() {
    local raw="${HERALD_DB_PATH:-$HOME/.herald/herald.db}"
    # Expand a leading ~/ — in case someone literally exported "~/.herald/...".
    case "$raw" in
        "~/"*) raw="$HOME/${raw#\~/}" ;;
    esac
    [ -f "$raw" ] || return 1
    printf '%s\n' "$raw"
}

# Run a SQL statement against the bot's DB. Pipes the SQL on stdin so quoting
# is the caller's problem only when interpolating strings.
#
#   db_exec "INSERT INTO cron_jobs (name, schedule, prompt) VALUES ('x','* * * * *','y');"
db_exec() {
    local sql="$1" db
    db="$(db_path)" || { echo "db_exec: $HERALD_DB_PATH not found" >&2; return 2; }
    sqlite3 "$db" "$sql"
}

# Insert an every-minute test cron job. Echoes the inserted id.
#   db_seed_cron <name> <prompt>
db_seed_cron() {
    local name="$1" prompt="$2"
    db_exec "INSERT INTO cron_jobs (name, schedule, prompt, enabled, built_in)
             VALUES ('$name', '* * * * *', '$(printf '%s' "$prompt" | sed "s/'/''/g")', 1, 0);
             SELECT last_insert_rowid();"
}

# Read the last_run column for a cron job by name. Empty string if NULL or absent.
db_cron_last_run() {
    local name="$1"
    db_exec "SELECT COALESCE(last_run, '') FROM cron_jobs WHERE name = '$name';"
}

# Drop a cron job by name. Idempotent.
db_drop_cron() {
    local name="$1"
    db_exec "DELETE FROM cron_jobs WHERE name = '$name';"
}
