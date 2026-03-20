package com.herald.cli;

import com.herald.agent.profile.AgentProfileParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.DefaultApplicationArguments;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class EphemeralRunnerTest {

    private ChatModel createMockModel(String responseText) {
        ChatModel mockModel = mock(ChatModel.class);
        // ToolCallAdvisor requires ToolCallingChatOptions from the model
        when(mockModel.getDefaultOptions())
                .thenReturn(DefaultToolCallingChatOptions.builder().build());
        if (responseText != null) {
            var response = new ChatResponse(List.of(
                    new Generation(new AssistantMessage(responseText))));
            when(mockModel.call(any(org.springframework.ai.chat.prompt.Prompt.class)))
                    .thenReturn(response);
        }
        return mockModel;
    }

    private Map<String, ChatModel> singleProviderMap(ChatModel model) {
        return Map.of("anthropic", model);
    }

    @Test
    void runsPromptAndPrintsResponse(@TempDir Path tempDir) throws Exception {
        Path agentFile = tempDir.resolve("agent.md");
        Files.writeString(agentFile, """
                ---
                name: test-agent
                description: A test agent
                model: sonnet
                tools: []
                ---

                You are a helpful test agent.
                """);

        ChatModel mockModel = createMockModel("Hello from agent!");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(baos);

        var runner = new EphemeralRunner(singleProviderMap(mockModel), out, System.err);
        var args = new DefaultApplicationArguments(
                "--agents=" + agentFile, "--prompt=Say hello");

        runner.run(args);

        String output = baos.toString();
        assertThat(output).contains("Hello from agent!");
    }

    @Test
    void exitsWithErrorForMissingAgentFile() throws Exception {
        ChatModel mockModel = createMockModel(null);
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream err = new PrintStream(baos);

        var runner = new EphemeralRunner(singleProviderMap(mockModel), System.out, err);
        var args = new DefaultApplicationArguments(
                "--agents=/nonexistent/agent.md", "--prompt=hello");

        runner.run(args);

        String output = baos.toString();
        assertThat(output).contains("not found");
    }

    @Test
    void doesNothingWithoutAgentsArg() throws Exception {
        ChatModel mockModel = createMockModel(null);
        var runner = new EphemeralRunner(singleProviderMap(mockModel), System.out, System.err);
        var args = new DefaultApplicationArguments();

        // Should return without doing anything
        runner.run(args);
    }

    @Test
    void endToEndEphemeralAgentFromClasspathResource() throws Exception {
        // Load agent definition from classpath
        var resource = getClass().getResource("/test-agent.md");
        assertThat(resource).isNotNull();

        ChatModel mockModel = createMockModel("Found 3 files in the current directory.");

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(baos);

        var runner = new EphemeralRunner(singleProviderMap(mockModel), out, System.err);
        ApplicationArguments args = new DefaultApplicationArguments(
                "--agents=" + Path.of(resource.toURI()),
                "--prompt=List all files in the current directory");

        runner.run(args);

        String output = baos.toString();
        assertThat(output).isNotEmpty();
    }

    @Test
    void ephemeralAgentDoesNotHavePersistenceTools() throws Exception {
        var resource = getClass().getResource("/test-agent.md");
        assertThat(resource).isNotNull();

        var parsed = AgentProfileParser.parseFile(Path.of(resource.toURI()));

        // Profile tools should NOT include memory, cron, telegram
        assertThat(parsed.profile().tools()).doesNotContain("memory", "cron", "telegram_send");
        assertThat(parsed.profile().tools()).contains("filesystem", "web");
    }
}
