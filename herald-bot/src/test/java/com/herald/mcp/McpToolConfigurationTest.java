package com.herald.mcp;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.assertj.core.api.Assertions.assertThat;

class McpToolConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(McpToolConfiguration.class);
    @Test void disabledByDefaultAndEnabledWithoutProviderFailsUnavailable() {
        runner.run(context -> assertThat(context.getBean(McpToolRegistry.class).state()).isEqualTo(McpToolRegistry.State.DISABLED));
        runner.withPropertyValues("spring.ai.mcp.client.enabled=true", "herald.mcp.allowed-tools[0]=fixture_echo")
                .run(context -> {
                    var registry = context.getBean(McpToolRegistry.class);
                    assertThat(registry.appendTo(new org.springframework.ai.tool.ToolCallback[0])).isEmpty();
                    assertThat(registry.state()).isEqualTo(McpToolRegistry.State.UNAVAILABLE);
                });
    }
}
