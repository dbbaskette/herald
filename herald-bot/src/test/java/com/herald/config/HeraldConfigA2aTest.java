package com.herald.config;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

class HeraldConfigA2aTest {

    @Test
    void a2aAgentsReturnsConfiguredListWhenPresent() {
        List<HeraldConfig.A2aAgent> agents = List.of(
                new HeraldConfig.A2aAgent("airbnb", "http://localhost:10001/airbnb",
                        Map.of("authorization", "Bearer token")),
                new HeraldConfig.A2aAgent("weather", "http://localhost:10002/weather", null));
        HeraldConfig config = configWithA2a(new HeraldConfig.A2a(agents, null, new HeraldConfig.A2a.Client(true)));

        assertThat(config.a2aAgents()).hasSize(2);
        assertThat(config.a2aAgents().get(0).name()).isEqualTo("airbnb");
        assertThat(config.a2aAgents().get(0).url()).isEqualTo("http://localhost:10001/airbnb");
        assertThat(config.a2aAgents().get(0).metadata()).containsEntry("authorization", "Bearer token");
        assertThat(config.a2aAgents().get(1).metadata()).isEmpty();
    }

    @Test
    void a2aAgentsReturnsEmptyListWhenA2aIsNull() {
        HeraldConfig config = configWithA2a(null);
        assertThat(config.a2aAgents()).isEmpty();
    }

    @Test
    void a2aAgentsReturnsEmptyListWhenAgentsListIsNull() {
        HeraldConfig config = configWithA2a(new HeraldConfig.A2a(null, null, new HeraldConfig.A2a.Client(true)));
        assertThat(config.a2aAgents()).isEmpty();
    }

    @Test
    void configuredAgentsRemainInactiveUntilClientIsExplicitlyEnabled() {
        HeraldConfig config = configWithA2a(new HeraldConfig.A2a(List.of(
                new HeraldConfig.A2aAgent("weather", "http://localhost:10002/weather", null))));
        assertThat(config.a2aClientEnabled()).isFalse();
        assertThat(config.a2aAgents()).isEmpty();
    }

    @Test
    void bindsExplicitClientSwitchIndependentlyFromAgentEntries() {
        HeraldConfig config = new Binder(new MapConfigurationPropertySource(Map.of(
                "herald.a2a.client.enabled", "true",
                "herald.a2a.agents[0].name", "weather",
                "herald.a2a.agents[0].url", "http://localhost:10002/weather")))
                .bind("herald", HeraldConfig.class).get();

        assertThat(config.a2aClientEnabled()).isTrue();
        assertThat(config.a2aAgents()).extracting(HeraldConfig.A2aAgent::name).containsExactly("weather");
        assertThat(config.a2a().server()).isNull();
    }

    @Test
    void backwardsCompatibleConstructorLeavesA2aNull() {
        // 10-arg constructor path used by existing call sites
        HeraldConfig config = new HeraldConfig(null, null, null, null, null, null, null, null, null, null);
        assertThat(config.a2a()).isNull();
        assertThat(config.a2aAgents()).isEmpty();
    }

    private HeraldConfig configWithA2a(HeraldConfig.A2a a2a) {
        // 11-arg canonical constructor
        return new HeraldConfig(null, null, null, null, null, null, null, null, null, null, a2a);
    }
}
