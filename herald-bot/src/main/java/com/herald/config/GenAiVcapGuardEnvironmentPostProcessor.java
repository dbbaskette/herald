package com.herald.config;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.PropertySource;

/**
 * Keep Boot's earlier CF flattener from parsing/logging raw credentials before our
 * config-data-aware validation (or explicit opt-out). No credentials are flattened
 * into vcap.services.*. The raw environment property is restored by the adapter.
 */
public final class GenAiVcapGuardEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    private static final String SOURCE = "heraldVcapGuard";

    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE + 4; }

    @Override public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        if (environment.getPropertySources().contains(SOURCE)) return;
        String raw = rawInput(environment);
        if (raw != null) environment.getPropertySources().addFirst(new Guard(raw));
    }

    static String takeInput(ConfigurableEnvironment environment) {
        var guard = environment.getPropertySources().remove(SOURCE);
        return guard instanceof Guard value ? value.raw : rawInput(environment);
    }

    private static String rawInput(ConfigurableEnvironment environment) {
        // Do not interpret placeholders embedded in JSON or credential values.
        for (var source : environment.getPropertySources()) {
            Object value = source.getProperty("VCAP_SERVICES");
            if (value != null) return value.toString();
        }
        return null;
    }

    private static final class Guard extends PropertySource<String> {
        private final String raw;
        private Guard(String raw) { super(SOURCE, "redacted"); this.raw = raw; }
        @Override public Object getProperty(String name) { return "VCAP_SERVICES".equals(name) ? "{}" : null; }
        @Override public String toString() { return "heraldVcapGuard[redacted]"; }
    }
}
