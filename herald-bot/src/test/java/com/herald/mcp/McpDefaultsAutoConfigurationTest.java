package com.herald.mcp;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.client.common.autoconfigure.McpClientAutoConfiguration;
import org.springframework.ai.mcp.client.common.autoconfigure.McpToolCallbackAutoConfiguration;
import org.springframework.ai.mcp.client.httpclient.autoconfigure.SseHttpClientTransportAutoConfiguration;
import org.springframework.ai.mcp.client.httpclient.autoconfigure.StreamableHttpHttpClientTransportAutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.io.ClassPathResource;
import static org.assertj.core.api.Assertions.assertThat;

/** Exercises real transport auto-configuration with packaged defaults, not a manually constructed client. */
class McpDefaultsAutoConfigurationTest {
    private ApplicationContextRunner defaults() {
        return new ApplicationContextRunner()
                .withInitializer(context -> {
                    try {
                        var sources=new YamlPropertySourceLoader().load("bundled-bot",new ClassPathResource("application.yaml"));
                        sources.forEach(source -> context.getEnvironment().getPropertySources().addLast(source));
                    } catch(IOException e) { throw new IllegalStateException(e); }
                })
                .withConfiguration(AutoConfigurations.of(McpClientAutoConfiguration.class,McpToolCallbackAutoConfiguration.class,
                        SseHttpClientTransportAutoConfiguration.class,StreamableHttpHttpClientTransportAutoConfiguration.class))
                .withBean(tools.jackson.databind.json.JsonMapper.class,() -> tools.jackson.databind.json.JsonMapper.builder().build())
                .withUserConfiguration(McpToolConfiguration.class);
    }
    @Test void packagedDefaultsRemainDisabledWithoutTransportCreation() {
        defaults().withPropertyValues("spring.ai.mcp.client.enabled=false").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context.getBean(McpToolRegistry.class).state()).isEqualTo(McpToolRegistry.State.DISABLED);
            assertThat(context).doesNotHaveBean("sseHttpClientTransports");
        });
    }
    @Test void enabledStreamableHttpDoesNotRequireUnrelatedEmptySseUrlsOrRemoteHandshake() {
        defaults().withPropertyValues("spring.ai.mcp.client.enabled=true",
                "spring.ai.mcp.client.streamable-http.connections.fixture.url=http://127.0.0.1:1",
                "spring.ai.mcp.client.streamable-http.connections.fixture.endpoint=/mcp")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context.getBean("sseHttpClientTransports",List.class)).isEmpty();
                    assertThat(context.getBean("streamableHttpHttpClientTransports",List.class)).hasSize(1);
                    assertThat(context.getBean("mcpSyncClients",List.class)).hasSize(1);
                });
    }
}
