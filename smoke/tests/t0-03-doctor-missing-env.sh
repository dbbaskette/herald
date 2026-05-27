# T0-03 — doctor catches a missing ANTHROPIC_API_KEY.
#
# Negative test for T0-02: prove the doctor command actually fails when it
# should, not just rubber-stamps. We unset the key for one invocation, run
# the command, then restore — both on success and on every error path via
# trap.

test_name() { echo "doctor detects missing env"; }

test_run() {
    # Snapshot the current value so we can restore it. `unset` doesn't leak
    # out of this subshell, but `./run.sh doctor` reads the parent shell's
    # env from .env — so we have to actually edit the running process env.
    local had_key="0" prior=""
    if [ -n "${ANTHROPIC_API_KEY:-}" ]; then
        had_key="1"
        prior="$ANTHROPIC_API_KEY"
    fi

    # shellcheck disable=SC2317  # called via trap
    restore_key() {
        if [ "$had_key" = "1" ]; then
            export ANTHROPIC_API_KEY="$prior"
        fi
    }
    trap restore_key EXIT

    unset ANTHROPIC_API_KEY

    # doctor reads $HOME/.herald/.env on top of process env, so for this
    # test to mean anything the .env must not silently re-supply the key.
    # We rename the file for the duration of the run and restore in trap.
    local env_path="$HERALD_DIR/.env" env_backup=""
    if [ -f "$env_path" ] && grep -q '^ANTHROPIC_API_KEY=' "$env_path" 2>/dev/null; then
        env_backup="$env_path.smoke-bak-$$"
        mv "$env_path" "$env_backup"
    fi
    # shellcheck disable=SC2317
    restore_env() {
        [ -n "$env_backup" ] && [ -f "$env_backup" ] && mv "$env_backup" "$env_path"
    }
    trap 'restore_env; restore_key' EXIT

    local out rc
    out=$( cd "$HERALD_DIR" && ./run.sh doctor 2>&1 )
    rc=$?

    # Should be non-zero. doctor uses 1 = warnings, 2 = failures. Either is
    # acceptable here as long as the key is named in the output.
    if [ "$rc" -eq 0 ]; then
        echo "doctor unexpectedly exited 0 with no ANTHROPIC_API_KEY"
        printf '%s\n' "$out" | head -30
        return 2
    fi

    assert_contains "$out" "ANTHROPIC_API_KEY" "doctor output" || return 2
    return 0
}
