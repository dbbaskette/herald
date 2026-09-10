package com.herald.api;

import java.util.List;

import com.herald.config.HeraldConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CapabilityControllerTest {

    @Test
    void reportsDisabledOptionalCapabilitiesWithoutProbingMemory() {
        var environment = new MockEnvironment()
                .withProperty("herald.persistence.enabled", "false")
                .withProperty("herald.google.enabled", "false")
                .withProperty("herald.meetingnotes.enabled", "false")
                .withProperty("herald.cron.enabled", "false")
                .withProperty("spring.ai.mcp.client.enabled", "false")
                .withProperty("herald.a2a.client.enabled", "false");
        var controller = new CapabilityController(environment, config(null),
                new StaticListableBeanFactory().getBeanProvider(JdbcTemplate.class), List.of("shell"));

        var result = controller.capabilities();
        var capabilities = (List<java.util.Map<String, String>>) result.get("capabilities");
        assertThat(capabilities)
                .extracting(item -> item.get("state"))
                .contains("disabled")
                .doesNotContain("failed");
    }

    @Test
    void distinguishesUnavailableGoogleAndSuccessfullyProbedMemory() {
        var environment = new MockEnvironment()
                .withProperty("herald.memory.db-path", "/tmp/herald-test.db")
                .withProperty("herald.persistence.enabled", "true")
                .withProperty("herald.google.enabled", "true")
                .withProperty("herald.cron.enabled", "true")
                .withProperty("herald.meetingnotes.enabled", "false");
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(eq("SELECT 1"), eq(Integer.class))).thenReturn(1);
        var beans = new StaticListableBeanFactory();
        beans.addBean("jdbcTemplate", jdbc);
        var controller = new CapabilityController(environment, config(null),
                beans.getBeanProvider(JdbcTemplate.class), List.of("shell"));

        var capabilities = (List<java.util.Map<String, String>>) controller.capabilities().get("capabilities");
        assertThat(capabilities).anySatisfy(item -> assertThat(item)
                .containsEntry("id", "memory").containsEntry("state", "healthy"));
        assertThat(capabilities).anySatisfy(item -> assertThat(item)
                .containsEntry("id", "google-workspace").containsEntry("state", "unavailable"));
    }

    @Test
    void reportsProviderFallbackWithoutLeakingCredentialValues() {
        var environment = new MockEnvironment()
                .withProperty("herald.agent.default-provider", "anthropic")
                .withProperty("herald.providers.openai.api-key", "secret-value")
                .withProperty("herald.persistence.enabled", "false");
        var controller = new CapabilityController(environment, config(null),
                new StaticListableBeanFactory().getBeanProvider(JdbcTemplate.class), List.of());

        String output = controller.capabilities().toString();
        assertThat(output).contains("effective=openai", "fallback=true");
        assertThat(output).doesNotContain("secret-value");
    }

    private static HeraldConfig config(HeraldConfig.A2a a2a) {
        return new HeraldConfig(null, null, null, null, null, null,
                null, null, null, null, a2a, null);
    }
}
