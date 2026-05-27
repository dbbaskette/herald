# T0-02 — ./run.sh doctor exits 0 on a healthy install.

test_name() { echo "doctor healthy"; }

test_run() {
    local out rc
    out=$( cd "$HERALD_DIR" && ./run.sh doctor --quiet 2>&1 )
    rc=$?

    # Exit 0 = clean, 1 = warnings, 2 = failures.
    if [ "$rc" -eq 0 ]; then return 0; fi
    if [ "$rc" -eq 1 ]; then
        echo "doctor reports warnings (exit 1)"
        printf '%s\n' "$out" | head -20
        return 1
    fi

    echo "doctor reports failure (exit $rc)"
    printf '%s\n' "$out" | head -20
    return 2
}
