package com.herald.config;

import java.util.Map;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.boot.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

/** Applies a complete binding before configuration binding and provider conditions run. */
public final class GenAiBindingEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    private static final String SOURCE = "heraldGenAiBinding";

    @Override public int getOrder() { return ConfigDataEnvironmentPostProcessor.ORDER + 1; }

    @Override public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        applyTo(environment);
    }

    public static void applyTo(ConfigurableEnvironment environment) {
        environment.getPropertySources().remove(SOURCE);
        String input = GenAiVcapGuardEnvironmentPostProcessor.takeInput(environment);
        String enabled = setting(environment, "enabled", "true");
        if ("false".equalsIgnoreCase(enabled)) return;
        if (!"true".equalsIgnoreCase(enabled)) throw VcapServicesParser.failure("INVALID_SETTING", "herald.genai.binding.enabled");
        VcapServicesParser.parse(input,
                setting(environment, "service-name", null), setting(environment, "model", null))
                .ifPresent(binding -> environment.getPropertySources().addFirst(new MapPropertySource(SOURCE, Map.of(
                        "herald.providers.openai.base-url", binding.baseUrl(),
                        "herald.providers.openai.api-key", binding.apiKey(),
                        "herald.agent.model.openai", binding.model(),
                        "herald.agent.model-catalog.openai", binding.model(),
                        "herald.agent.default-provider", "openai")) {
                    @Override public String toString() { return "heraldGenAiBinding[redacted]"; }
                }));
    }

    private static String setting(ConfigurableEnvironment environment, String suffix, String fallback) {
        String value = environment.getProperty("herald.genai.binding." + suffix);
        if (value == null) value = environment.getProperty("HERALD_GENAI_BINDING_" + suffix.replace('-', '_').toUpperCase(java.util.Locale.ROOT));
        return value == null ? fallback : value;
    }
}
