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
import org.junit.jupiter.api.io.TempDir;
import com.herald.config.HeraldConfig;
import com.herald.cron.CronTools;
import com.herald.tools.*;
import java.nio.file.Path;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"hello from Herald", "error"})
    void configuredHeraldAgentInvokesDiscoveredRemoteToolAndConsumesItsResult(String remoteInput, @TempDir Path tempDir) {
        var transport = HttpClientStreamableHttpTransport.builder("http://localhost:" + port).endpoint("/mcp").build();
        try (var client = McpClient.sync(transport).initializationTimeout(Duration.ofSeconds(5))
                .requestTimeout(Duration.ofSeconds(5)).build()) {
            // Production uses initialized=false: discovery initializes on demand.
            var expectedResult = remoteInput.equals("error") ? "failed" : "echo: hello from Herald";
            var registry = new com.herald.mcp.McpToolRegistry(true, List.of("fixture_echo"),
                    () -> List.of(new SyncMcpToolCallbackProvider(client)));
            HeraldAgentConfig agentConfig = new HeraldAgentConfig();
            agentConfig.setMcpTools(registry);
            ChatModel mockModel = mock(ChatModel.class);
            when(mockModel.getOptions()).thenReturn(org.springframework.ai.model.tool.ToolCallingChatOptions.builder().build());
            var calls = new java.util.concurrent.atomic.AtomicInteger();
            when(mockModel.call(any(Prompt.class))).thenAnswer(invocation -> {
                Prompt prompt = invocation.getArgument(0);
                if (calls.getAndIncrement() == 0) {
                    var options = (org.springframework.ai.model.tool.ToolCallingChatOptions) prompt.getOptions();
                    assertThat(options.getToolCallbacks()).extracting(c -> c.getToolDefinition().name()).contains("fixture_echo");
                    return new ChatResponse(List.of(new Generation(AssistantMessage.builder().content("")
                            .toolCalls(List.of(new AssistantMessage.ToolCall("fixture-call", "function", "fixture_echo", "{\"message\":\"" + remoteInput + "\"}"))).build())));
                }
                assertThat(prompt.getInstructions()).anyMatch(message -> message instanceof ToolResponseMessage tool
                        && tool.getResponses().stream().anyMatch(result -> result.responseData().contains(expectedResult)));
                return new ChatResponse(List.of(new Generation(new AssistantMessage("Remote fixture replied: " + expectedResult))));
            });
            HeraldConfig config = new HeraldConfig(null, null,
                    new HeraldConfig.Agent("Fixture", null, tempDir.resolve("CONTEXT.md").toString(), null, null, null, null),
                    null, null, null, null, null, null, new HeraldConfig.LongTermMemory(tempDir.toString()));
            JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
            when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class))).thenReturn(List.of());
        ModelSwitcher switcher = agentConfig.modelSwitcher(
                mockModel, new org.springaicommunity.agent.tools.task.repository.DefaultTaskRepository(), config, Optional.empty(), new com.herald.agent.PromptDumpAdvisor(false), Optional.empty(),
                mock(HeraldShellDecorator.class),
                new FileSystemTools(), Optional.empty(), mock(ObjectProvider.class),
                Optional.of(mock(TelegramSendTool.class)),
                Optional.of(mock(GwsTools.class)),
                Optional.empty(), mock(com.herald.tools.RemindersAvailabilityChecker.class),
                new WebTools(""), Optional.of(mock(CronTools.class)),
                Optional.of(jdbcTemplate),
                new ClassPathResource("prompts/MAIN_AGENT_SYSTEM_PROMPT.md"),
                tempDir.resolve("agents").toString(), new ReloadableSkillsTool(tempDir.resolve("skills").toString()),
                new ValidateSkillTool(tempDir.resolve("skills").toString()),
                "fixture", "fixture", "fixture", "fixture",
                "fixture", "fixture", "fixture", "fixture",
                "", "", "", "", "",
                "system_and_tools",
                "daily",
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                List.of("shell", "filesystem", "todoWrite", "askUserQuestion", "task", "taskOutput", "skills", "web"),
                Optional.empty(),
                new com.herald.agent.ToolEventBus());
            String reply = new AgentService(switcher, null).chat("Use the remote echo fixture", "fixture-owner");
            assertThat(reply).contains(expectedResult);
            assertThat(calls).hasValue(2);
            assertThat(registry.state()).isEqualTo(com.herald.mcp.McpToolRegistry.State.AVAILABLE);
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
                    .toolCall(echo, (exchange, request) -> "error".equals(request.arguments().get("message"))
                            ? CallToolResult.builder().isError(true).addTextContent("fixture remote failure").build()
                            : CallToolResult.builder().addTextContent("echo: " + request.arguments().get("message")).build())
                    .build();
        }
    }
}
