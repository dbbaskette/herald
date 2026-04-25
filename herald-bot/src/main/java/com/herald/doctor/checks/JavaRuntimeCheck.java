package com.herald.doctor.checks;

import com.herald.doctor.HealthCheck;

/**
 * Ensures the JVM meets Herald's minimum bar (Java 21) and reports the
 * running version + architecture for debugging mixed-architecture setups
 * (e.g. x86 JDK on Apple Silicon).
 */
public class JavaRuntimeCheck implements HealthCheck {

    private static final int MIN_FEATURE_VERSION = 21;

    @Override
    public String name() {
        return "Java runtime";
    }

    @Override
    public Result run() {
        String version = System.getProperty("java.version", "?");
        String arch = System.getProperty("os.arch", "?");
        String vendor = System.getProperty("java.vendor", "?");
        int feature = Runtime.version().feature();
        String summary = String.format("%s %s (%s, %s)",
                vendor, version, arch, Runtime.version().toString());
        if (feature < MIN_FEATURE_VERSION) {
            return Result.fail(summary,
                    "Herald requires Java " + MIN_FEATURE_VERSION + " or newer");
        }
        return Result.ok(summary);
    }
}
