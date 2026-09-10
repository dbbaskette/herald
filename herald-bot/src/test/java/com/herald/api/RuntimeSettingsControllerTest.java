package com.herald.api;

import java.util.Map;
import com.herald.config.HeraldConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import static org.assertj.core.api.Assertions.*;

class RuntimeSettingsControllerTest {
    private StandardEnvironment environment() {
        var env = new StandardEnvironment();
        env.getPropertySources().remove("systemEnvironment");
        env.getPropertySources().remove("systemProperties");
        env.getPropertySources().addLast(new MapPropertySource("fixture.yaml", Map.of(
                "herald.agent.persona", "${HERALD_AGENT_PERSONA:Herald}",
                "herald.agent.max-context-tokens", "${HERALD_AGENT_MAX_CONTEXT_TOKENS:200000}",
                "herald.cron.timezone", "${HERALD_CRON_TIMEZONE:America/New_York}",
                "herald.obsidian.vault-path", "${HERALD_OBSIDIAN_VAULT_PATH:}",
                "herald.weather.location", "${HERALD_WEATHER_LOCATION:}")));
        return env;
    }
    private RuntimeSettingsController start(StandardEnvironment env) {
        HeraldConfig bound = Binder.get(env).bind("herald", HeraldConfig.class).get();
        return new RuntimeSettingsController(bound, env);
    }
    @Test void environmentOverridesYamlAndSnapshotOnlyChangesAfterRestart() {
        var env = environment();
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource("systemEnvironment", Map.of(
                "HERALD_AGENT_PERSONA", "Fixture Persona", "HERALD_CRON_TIMEZONE", "Pacific/Auckland",
                "HERALD_AGENT_MAX_CONTEXT_TOKENS", "12345", "HERALD_OBSIDIAN_VAULT_PATH", "/fixture/vault",
                "HERALD_WEATHER_LOCATION", "Fixture City")));
        var running = start(env);
        assertThat(running.settings().get("agent.persona").effective()).isEqualTo("Fixture Persona");
        assertThat(running.settings().get("cron.timezone").effective()).isEqualTo("Pacific/Auckland");
        assertThat(running.settings().get("agent.max-context-tokens").effective()).isEqualTo("12345");
        assertThat(running.settings().get("obsidian.vault-path").effective()).isEqualTo("/fixture/vault");
        assertThat(running.settings().get("weather.location").effective()).isEqualTo("Fixture City");
        assertThat(running.settings().values()).allMatch(value -> value.source().startsWith("environment:"));
        env.getPropertySources().replace("systemEnvironment", new SystemEnvironmentPropertySource("systemEnvironment",
                Map.of("HERALD_AGENT_PERSONA", "Restarted")));
        assertThat(running.settings().get("agent.persona").effective()).isEqualTo("Fixture Persona");
        assertThat(start(env).settings().get("agent.persona").effective()).isEqualTo("Restarted");
    }
    @Test void higherPriorityExplicitConfigurationWinsOverEnvironmentPlaceholder() {
        var env = environment();
        env.getPropertySources().addFirst(new SystemEnvironmentPropertySource("systemEnvironment",
                Map.of("HERALD_AGENT_PERSONA", "Environment Persona")));
        env.getPropertySources().addFirst(new MapPropertySource("commandLineArgs",
                Map.of("herald.agent.persona", "Command Persona")));
        var setting = start(env).settings().get("agent.persona");
        assertThat(setting.effective()).isEqualTo("Command Persona");
        assertThat(setting.source()).isEqualTo("configuration:commandLineArgs");
    }
    @Test void defaultsAndExplicitYamlValuesAreReportedWithoutReadingAnyDatabase() {
        var env = environment();
        env.getPropertySources().addFirst(new MapPropertySource("operator.yaml", Map.of("herald.cron.timezone", "UTC")));
        var snapshot = start(env).settings();
        assertThat(snapshot.get("cron.timezone").effective()).isEqualTo("UTC");
        assertThat(snapshot.get("cron.timezone").source()).isEqualTo("configuration:operator.yaml");
        assertThat(snapshot.get("agent.max-context-tokens").source()).isEqualTo("default");
        assertThat(snapshot.get("agent.max-context-tokens").effective()).isEqualTo("200000");
    }
}
