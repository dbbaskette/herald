package com.herald.config;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class HeraldConfigA2aTest {

    @Test
    void a2aAgentsReturnsConfiguredListWhenPresent() {
        List<HeraldConfig.A2aAgent> agents = List.of(
                new HeraldConfig.A2aAgent("airbnb", "http://localhost:10001/airbnb",
                        Map.of("authorization", "Bearer token")),
                new HeraldConfig.A2aAgent("weather", "http://localhost:10002/weather", null));
        HeraldConfig config = configWithA2a(new HeraldConfig.A2a(agents));

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
        HeraldConfig config = configWithA2a(new HeraldConfig.A2a(null));
        assertThat(config.a2aAgents()).isEmpty();
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
