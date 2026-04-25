package com.herald.doctor.checks;

import java.util.Map;
import java.util.function.Function;

import com.herald.doctor.HealthCheck;

/**
 * Checks the presence (and optionally the shape) of a single environment
 * variable. The value itself is never logged — only a redacted preview ("sk-…abcd")
 * so users can confirm at a glance they're using the right key without leaking it.
 */
public class EnvVarCheck implements HealthCheck {

    private final String displayName;
    private final String envKey;
    private final Severity missingSeverity;
    private final String fixHint;
    private final Function<String, String> shapeValidator;
    private final Map<String, String> env;

    public enum Severity { WARN, FAIL }

    public EnvVarCheck(String displayName, String envKey, Severity missingSeverity, String fixHint) {
        this(displayName, envKey, missingSeverity, fixHint, v -> null, System.getenv());
    }

    public EnvVarCheck(String displayName, String envKey, Severity missingSeverity, String fixHint,
                       Function<String, String> shapeValidator, Map<String, String> env) {
        this.displayName = displayName;
        this.envKey = envKey;
        this.missingSeverity = missingSeverity;
        this.fixHint = fixHint;
        this.shapeValidator = shapeValidator == null ? v -> null : shapeValidator;
        this.env = env == null ? Map.of() : env;
    }

    @Override
    public String name() {
        return displayName;
    }

    @Override
    public Result run() {
        String raw = env.get(envKey);
        if (raw == null || raw.isBlank()) {
            return missingSeverity == Severity.FAIL
                    ? Result.fail(envKey + " not set", fixHint)
                    : Result.warn(envKey + " not set", fixHint);
        }
        String validationError = shapeValidator.apply(raw);
        if (validationError != null) {
            return Result.fail(envKey + " looks malformed: " + validationError, fixHint);
        }
        return Result.ok(envKey + " set (" + redact(raw) + ")");
    }

    /**
     * @return the last 4 characters with the rest replaced by ellipsis.
     *         Keeps the signal (enough to eyeball rotation) while dropping the leak.
     */
    static String redact(String secret) {
        if (secret == null || secret.isEmpty()) return "empty";
        if (secret.length() <= 4) return "***" + secret.charAt(secret.length() - 1);
        return "…" + secret.substring(secret.length() - 4);
    }
}
