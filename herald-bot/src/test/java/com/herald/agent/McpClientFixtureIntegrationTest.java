package com.herald.agent;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpSyncServer;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.ServerCapabilities;
import io.modelcontextprotocol.spec.McpSchema.Tool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.mcp.SyncMcpToolCallbackProvider;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.tomcat.autoconfigure.servlet.TomcatServletWebServerAutoConfiguration;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = McpClientFixtureIntegrationTest.FixtureApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class McpClientFixtureIntegrationTest {

    @LocalServerPort
    int port;

    @Test
    void initializesServerAndInvokesDiscoveredToolThroughSpringAiCallbackProvider() {
        var transport = HttpClientStreamableHttpTransport
                .builder("http://localhost:" + port)
                .endpoint("/mcp")
                .build();

        try (McpSyncClient client = McpClient.sync(transport)
                .initializationTimeout(Duration.ofSeconds(5))
                .requestTimeout(Duration.ofSeconds(5))
                .build()) {
            var initialized = client.initialize();
            assertThat(initialized.serverInfo().name()).isEqualTo("herald-fixture");

            ToolCallback[] callbacks = new SyncMcpToolCallbackProvider(client).getToolCallbacks();
            assertThat(callbacks).singleElement().satisfies(callback -> {
                assertThat(callback.getToolDefinition().name()).isEqualTo("fixture_echo");
                assertThat(callback.getToolDefinition().inputSchema())
                        .contains("message", "required");
                assertThat(callback.call("{\"message\":\"hello from Herald\"}"))
                        .contains("echo: hello from Herald");
            });
        }
    }

    @SpringBootConfiguration
    @ImportAutoConfiguration(TomcatServletWebServerAutoConfiguration.class)
    static class FixtureApplication {

        @Bean
        HttpServletStreamableServerTransportProvider mcpTransport() {
            return HttpServletStreamableServerTransportProvider.builder()
                    .mcpEndpoint("/mcp")
                    .build();
        }

        @Bean
        ServletRegistrationBean<HttpServletStreamableServerTransportProvider> mcpServlet(
                HttpServletStreamableServerTransportProvider transport) {
            return new ServletRegistrationBean<>(transport, "/mcp");
        }

        @Bean(destroyMethod = "close")
        McpSyncServer mcpServer(HttpServletStreamableServerTransportProvider transport) {
            Tool echo = Tool.builder("fixture_echo")
                    .description("Echo a message through the isolated MCP fixture")
                    .inputSchema(Map.of(
                            "type", "object",
                            "properties", Map.of("message", Map.of("type", "string")),
                            "required", List.of("message")))
                    .build();
            return McpServer.sync(transport)
                    .serverInfo("herald-fixture", "1.0.0")
                    .capabilities(ServerCapabilities.builder().tools(false).build())
                    .toolCall(echo, (exchange, request) -> CallToolResult.builder()
                            .addTextContent("echo: " + request.arguments().get("message"))
                            .build())
                    .build();
        }
    }
}
