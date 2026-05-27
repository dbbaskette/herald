# Assertion primitives. Each prints a diagnostic on failure and returns 2.
# Tests use these directly: `assert_contains "$reply" "PONG" || return 2`.

assert_eq() {
    local actual="$1" expected="$2" label="${3:-value}"
    if [ "$actual" = "$expected" ]; then return 0; fi
    echo "ASSERT $label: expected '$expected', got '$actual'"
    return 2
}

assert_contains() {
    local haystack="$1" needle="$2" label="${3:-output}"
    case "$haystack" in
        *"$needle"*) return 0 ;;
        *) echo "ASSERT $label: expected to contain '$needle'"
           echo "  got: $(printf '%s' "$haystack" | head -c 300)"
           return 2
           ;;
    esac
}

assert_not_empty() {
    local value="$1" label="${2:-value}"
    if [ -n "$value" ]; then return 0; fi
    echo "ASSERT $label: expected non-empty"
    return 2
}

assert_lt() {
    local actual="$1" max="$2" label="${3:-value}"
    if [ "$actual" -lt "$max" ]; then return 0; fi
    echo "ASSERT $label: expected < $max, got $actual"
    return 2
}

assert_exit_code() {
    local actual="$1" expected="$2" label="${3:-exit code}"
    if [ "$actual" -eq "$expected" ]; then return 0; fi
    echo "ASSERT $label: expected exit $expected, got $actual"
    return 2
}
