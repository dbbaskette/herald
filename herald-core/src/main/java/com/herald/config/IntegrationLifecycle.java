package com.herald.config;

import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;

/** Side-effect-free startup decisions shared by bean conditions and capability reporting. */
public final class IntegrationLifecycle {
    private IntegrationLifecycle() {}
    public static boolean taskMode(Environment env) { return StringUtils.hasText(env.getProperty("agents")); }
    public static boolean persistenceEnabled(Environment env) {
        return !taskMode(env) && enabled(env, "herald.persistence.enabled", true)
                && StringUtils.hasText(env.getProperty("herald.memory.db-path"));
    }
    public static boolean cronEnabled(Environment env) {
        return persistenceEnabled(env) && enabled(env, "herald.cron.enabled", true);
    }
    public static boolean meetingNotesEnabled(Environment env) {
        return persistenceEnabled(env) && enabled(env, "herald.meetingnotes.enabled", false);
    }
    public static boolean googleEnabled(Environment env) {
        return !taskMode(env) && enabled(env, "herald.google.enabled", false);
    }
    public static boolean remindersEnabled(Environment env) {
        return !taskMode(env) && enabled(env, "herald.reminders.enabled", false);
    }
    private static boolean enabled(Environment env, String name, boolean defaultValue) {
        String value = env.getProperty(name);
        return value == null ? defaultValue : "true".equalsIgnoreCase(value.trim());
    }
}
