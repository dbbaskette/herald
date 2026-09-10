package com.herald.agent;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agent.common.task.subagent.SubagentReference;
import org.springaicommunity.agent.common.task.subagent.SubagentType;
import org.springaicommunity.agent.subagent.a2a.A2ASubagentDefinition;
import org.springaicommunity.agent.subagent.a2a.A2ASubagentExecutor;
import org.springaicommunity.agent.subagent.a2a.A2ASubagentResolver;
import org.springaicommunity.agent.tools.task.TaskTool;
import org.springaicommunity.agent.tools.task.repository.DefaultTaskRepository;
import org.springframework.ai.tool.ToolCallback;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class A2aTaskToolIntegrationTest {

    private final JsonMapper json = JsonMapper.builder().build();
    private final AtomicReference<String> requestBody = new AtomicReference<>();
    private HttpServer server;
    private String agentUrl;
    private volatile boolean failMessages;

    @BeforeEach
    void startFixture() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        agentUrl = "http://127.0.0.1:" + server.getAddress().getPort() + "/fixture";
        server.createContext("/fixture/.well-known/agent-card.json", exchange -> respond(exchange, 200, agentCard()));
        server.createContext("/fixture", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            if (failMessages) {
                respond(exchange, 503, "{\"error\":\"fixture unavailable\"}");
                return;
            }
            JsonNode request = json.readTree(requestBody.get());
            Object id = json.treeToValue(request.path("id"), Object.class);
            respond(exchange, 200, json.writeValueAsString(Map.of(
                    "jsonrpc", "2.0",
                    "id", id,
                    "result", Map.of(
                            "id", "remote-task-1",
                            "contextId", "remote-context-1",
                            "kind", "task",
                            "status", Map.of("state", "completed"),
                            "artifacts", List.of(Map.of(
                                    "artifactId", "forecast",
                                    "parts", List.of(Map.of("kind", "text", "text", "Remote forecast: clear skies."))))))));
        });
        server.start();
    }

    @AfterEach
    void stopFixture() {
        if (server != null) server.stop(0);
    }

    @Test
    void realTaskToolSendsJsonRpcAndReturnsRemoteArtifact() {
        ToolCallback task = taskTool();

        String result = task.call("""
                {"description":"fetch forecast","prompt":"Weather for tomorrow",\
                "subagent_type":"Fixture Agent","run_in_background":false}
                """);

        assertThat(result).contains("Remote forecast: clear skies.");
        assertThat(requestBody.get()).contains("message/send", "Weather for tomorrow", "jsonrpc");
    }

    @Test
    void remoteFailureReturnsPromptlyAndDoesNotPoisonAnotherTaskTool() {
        ToolCallback task = taskTool();
        failMessages = true;

        long started = System.nanoTime();
        String failure = task.call("""
                {"description":"failing remote","prompt":"Fail promptly",\
                "subagent_type":"Fixture Agent","run_in_background":false}
                """);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(failure).containsIgnoringCase("error communicating");
        assertThat(elapsed).isLessThan(Duration.ofSeconds(5));
        failMessages = false;
        assertThatCode(() -> task.call("""
                {"description":"retry remote","prompt":"Try again",\
                "subagent_type":"Fixture Agent","run_in_background":false}
                """)).doesNotThrowAnyException();
    }

    private ToolCallback taskTool() {
        var reference = new SubagentReference(agentUrl, A2ASubagentDefinition.KIND);
        var type = new SubagentType(new A2ASubagentResolver(), new A2ASubagentExecutor());
        return TaskTool.builder()
                .taskRepository(new DefaultTaskRepository())
                .subagentReferences(reference)
                .subagentTypes(type)
                .build();
    }

    private String agentCard() {
        return """
                {
                  "name":"Fixture Agent",
                  "description":"Deterministic A2A fixture",
                  "url":"%s",
                  "version":"1.0.0",
                  "protocolVersion":"0.3.0",
                  "preferredTransport":"JSONRPC",
                  "capabilities":{"streaming":false,"pushNotifications":false},
                  "defaultInputModes":["text"],
                  "defaultOutputModes":["text"],
                  "skills":[]
                }
                """.formatted(agentUrl);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
