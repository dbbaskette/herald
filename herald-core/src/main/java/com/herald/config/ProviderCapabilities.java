package com.herald.config;

import java.net.URI;
import java.util.*;
import java.util.function.Function;
import org.springframework.core.env.Environment;

/** Shared, local-only requirements for the five supported provider implementations. */
public final class ProviderCapabilities {
    public static final List<String> ORDER = List.of("anthropic", "openai", "gemini", "ollama", "lmstudio");
    private static final String DOCS = "docs/provider-capabilities.md";
    private ProviderCapabilities() {}

    public static ProviderResolution resolve(Environment environment) {
        return resolve(environment.getProperty("herald.agent.default-provider", "anthropic"), environment::getProperty);
    }
    public static ProviderResolution resolve(HeraldConfig config) {
        var p = config.providers();
        Map<String, String> values = new HashMap<>();
        if (p != null) {
            if (p.anthropic() != null) put(values, "anthropic", p.anthropic().apiKey(), null);
            if (p.openai() != null) put(values, "openai", p.openai().apiKey(), p.openai().baseUrl());
            if (p.gemini() != null) put(values, "gemini", p.gemini().apiKey(), p.gemini().baseUrl());
            if (p.ollama() != null) put(values, "ollama", p.ollama().apiKey(), p.ollama().baseUrl());
            if (p.lmstudio() != null) put(values, "lmstudio", p.lmstudio().apiKey(), p.lmstudio().baseUrl());
        }
        return resolve(config.defaultProvider(), values::get);
    }
    private static void put(Map<String,String> values, String provider, String key, String url) {
        if (key != null) values.put("herald.providers." + provider + ".api-key", key);
        if (url != null) values.put("herald.providers." + provider + ".base-url", url);
    }
    public static ProviderResolution resolve(String requested, Function<String,String> properties) {
        requested = hasText(requested) ? requested.trim().toLowerCase(Locale.ROOT) : "anthropic";
        List<CapabilityStatus> statuses = ORDER.stream().map(p -> provider(p, properties)).toList();
        final String selected = requested;
        String effective = statuses.stream().filter(s -> s.id().equals(selected) && s.state() == CapabilityState.HEALTHY)
                .map(CapabilityStatus::id).findFirst().orElseGet(() -> statuses.stream()
                        .filter(s -> s.state() == CapabilityState.HEALTHY).map(CapabilityStatus::id).findFirst().orElse(null));
        return new ProviderResolution(requested, effective, effective != null && !requested.equals(effective), statuses);
    }
    public static CapabilityStatus provider(String provider, Function<String,String> properties) {
        if (!ORDER.contains(provider)) return new CapabilityStatus(provider, CapabilityState.UNAVAILABLE, "Unsupported provider", DOCS);
        String prefix = "herald.providers." + provider;
        boolean local = provider.equals("ollama") || provider.equals("lmstudio");
        String required = local ? ".base-url" : ".api-key";
        String value = properties.apply(prefix + required);
        if (!hasText(value)) return new CapabilityStatus(provider, CapabilityState.UNCONFIGURED,
                variable(provider, local) + " is not set.", DOCS);
        String url = properties.apply(prefix + ".base-url");
        if (hasText(url) && !validUrl(url)) return new CapabilityStatus(provider, CapabilityState.FAILED,
                provider + " base URL must be an absolute HTTP(S) URL without credentials, query or fragment.", DOCS);
        return new CapabilityStatus(provider, CapabilityState.HEALTHY, "Configured; remote availability has not been probed.", DOCS);
    }
    public static boolean configured(Environment environment, String provider) {
        return provider(provider, environment::getProperty).state() == CapabilityState.HEALTHY;
    }
    public static CapabilityStatus telegram(Environment environment) {
        if (hasText(environment.getProperty("agents"))) return new CapabilityStatus("telegram", CapabilityState.DISABLED,
                "Telegram is disabled in task-agent mode.", "docs/getting-started-101.md");
        String token = environment.getProperty("herald.telegram.bot-token");
        String chat = environment.getProperty("herald.telegram.allowed-chat-id");
        if (hasText(token) && hasText(chat)) return new CapabilityStatus("telegram", CapabilityState.HEALTHY,
                "Configured; Telegram has not been probed.", "docs/getting-started-101.md");
        return new CapabilityStatus("telegram", CapabilityState.UNCONFIGURED,
                "Telegram is inactive: configure both HERALD_TELEGRAM_BOT_TOKEN and HERALD_TELEGRAM_ALLOWED_CHAT_ID, or continue without Telegram.",
                "docs/getting-started-101.md");
    }
    public static boolean hasText(String value) { return value != null && !value.isBlank(); }
    private static String variable(String provider, boolean local) {
        return provider.toUpperCase(Locale.ROOT) + (local ? "_BASE_URL" : "_API_KEY");
    }
    private static boolean validUrl(String value) {
        try {
            URI uri = URI.create(value.trim());
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null && uri.getQuery() == null && uri.getFragment() == null;
        } catch (IllegalArgumentException invalid) { return false; }
    }
}
