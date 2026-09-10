package com.herald.api;

import java.util.LinkedHashMap;
import java.util.Map;
import com.herald.config.HeraldConfig;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Startup configuration only: SQLite preferences are not runtime configuration. */
@RestController
public class RuntimeSettingsController {
    public record EffectiveSetting(String effective, String source, String environmentVariable) {}
    private final Map<String, EffectiveSetting> snapshot;
    public RuntimeSettingsController(HeraldConfig config, ConfigurableEnvironment environment) {
        var values = new LinkedHashMap<String, EffectiveSetting>();
        add(values, environment, "agent.persona", config.persona(), "HERALD_AGENT_PERSONA");
        add(values, environment, "agent.max-context-tokens", String.valueOf(config.maxContextTokens()), "HERALD_AGENT_MAX_CONTEXT_TOKENS");
        add(values, environment, "cron.timezone", config.cronTimezone(), "HERALD_CRON_TIMEZONE");
        add(values, environment, "obsidian.vault-path", config.obsidianVaultPath(), "HERALD_OBSIDIAN_VAULT_PATH");
        add(values, environment, "weather.location", config.weatherLocation(), "HERALD_WEATHER_LOCATION");
        snapshot = Map.copyOf(values);
    }
    private static void add(Map<String, EffectiveSetting> values, ConfigurableEnvironment environment,
                            String key, String effective, String variable) {
        String property = "herald." + key;
        String source = "default";
        String resolved = environment.getProperty(property);
        if (resolved != null && !resolved.isBlank()) {
            for (var candidate : environment.getPropertySources()) {
                if (candidate.getName().equals("configurationProperties")) continue;
                Object raw = candidate.getProperty(property);
                if (raw == null) continue;
                String text = raw.toString();
                if (candidate.getName().equals("systemEnvironment")) source = "environment:" + variable;
                else if (text.startsWith("${" + variable + ":") || text.equals("${" + variable + "}")) {
                    for (var variableSource : environment.getPropertySources()) {
                        if (variableSource.getName().equals("configurationProperties")) continue;
                        if (variableSource.getProperty(variable) == null) continue;
                        source = variableSource.getName().equals("systemEnvironment")
                                ? "environment:" + variable : "configuration:" + variableSource.getName();
                        break;
                    }
                }
                else source = "configuration:" + candidate.getName();
                break;
            }
        }
        values.put(key, new EffectiveSetting(effective, source, variable));
    }
    @GetMapping("/api/runtime/settings")
    public Map<String, EffectiveSetting> settings() { return snapshot; }
}
