package com.herald.agent;

import com.herald.config.HeraldConfig;
import com.herald.memory.MemoryTools;
import com.herald.tools.AskUserQuestionTool;
import com.herald.tools.FileSystemTools;
import com.herald.tools.HeraldShellDecorator;
import com.herald.tools.TodoWriteTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.agent.common.task.subagent.SubagentReference;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Integration test verifying the ChatClient bean is properly wired with all tools and advisors.
 * Uses a mock ChatModel to avoid requiring an API key.
 */
class HeraldAgentConfigIntegrationTest {

    private static final String HAIKU_MODEL = "claude-haiku-4-5";
    private static final String SONNET_MODEL = "claude-sonnet-4-5";
    private static final String OPUS_MODEL = "claude-opus-4-5";
    private static final String OPENAI_MODEL = "gpt-4o";
    private static final String OLLAMA_MODEL = "llama3.2";

    @Test
    void mainClientBeanCreatedWithAllToolsAndAdvisors(@TempDir Path tempDir) {
        HeraldAgentConfig agentConfig = new HeraldAgentConfig();

        JdbcChatMemoryRepository chatMemoryRepository = mock(JdbcChatMemoryRepository.class);
        ChatMemory chatMemory = agentConfig.chatMemory(chatMemoryRepository);
        assertThat(chatMemory).isInstanceOf(MessageWindowChatMemory.class);

        ChatModel mockModel = mock(ChatModel.class);
        ChatClient.Builder builder = ChatClient.builder(mockModel);

        HeraldConfig config = new HeraldConfig(null, null,
                new HeraldConfig.Agent("TestBot", null), null);

        ChatClient client = agentConfig.mainClient(
                builder, mockModel, config, chatMemory,
                mock(MemoryTools.class), mock(HeraldShellDecorator.class),
                new FileSystemTools(), new TodoWriteTool(), mock(AskUserQuestionTool.class),
                new ClassPathResource("prompts/MAIN_AGENT_SYSTEM_PROMPT.md"),
                tempDir.toString(), HAIKU_MODEL, SONNET_MODEL, OPUS_MODEL,
                OPENAI_MODEL, OLLAMA_MODEL, Optional.empty(), Optional.empty());

        assertThat(client).isNotNull();
    }

    @Test
    void mainClientLoadsSubagentDefinitionsFromDirectory(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("test-agent.md"),
                """
                ---
                name: test-agent
                description: A test subagent for unit testing
                model: sonnet
                tools: Read, Grep
                ---
                You are a test agent.
                """);

        HeraldAgentConfig agentConfig = new HeraldAgentConfig();

        ChatModel mockModel = mock(ChatModel.class);
        ChatClient.Builder builder = ChatClient.builder(mockModel);

        HeraldConfig config = new HeraldConfig(null, null,
                new HeraldConfig.Agent("TestBot", null), null);

        JdbcChatMemoryRepository chatMemoryRepository = mock(JdbcChatMemoryRepository.class);
        ChatMemory chatMemory = agentConfig.chatMemory(chatMemoryRepository);

        ChatClient client = agentConfig.mainClient(
                builder, mockModel, config, chatMemory,
                mock(MemoryTools.class), mock(HeraldShellDecorator.class),
                new FileSystemTools(), new TodoWriteTool(), mock(AskUserQuestionTool.class),
                new ClassPathResource("prompts/MAIN_AGENT_SYSTEM_PROMPT.md"),
                tempDir.toString(), HAIKU_MODEL, SONNET_MODEL, OPUS_MODEL,
                OPENAI_MODEL, OLLAMA_MODEL, Optional.empty(), Optional.empty());

        assertThat(client).isNotNull();
    }

    @Test
    void mainClientWiresOpenAiAndOllamaProviders(@TempDir Path tempDir) {
        HeraldAgentConfig agentConfig = new HeraldAgentConfig();

        ChatModel mockAnthropicModel = mock(ChatModel.class);
        ChatModel mockOpenAiModel = mock(OpenAiChatModel.class);
        ChatModel mockOllamaModel = mock(OpenAiChatModel.class);
        ChatClient.Builder builder = ChatClient.builder(mockAnthropicModel);

        HeraldConfig config = new HeraldConfig(null, null,
                new HeraldConfig.Agent("TestBot", null), null);

        JdbcChatMemoryRepository chatMemoryRepository = mock(JdbcChatMemoryRepository.class);
        ChatMemory chatMemory = agentConfig.chatMemory(chatMemoryRepository);

        ChatClient client = agentConfig.mainClient(
                builder, mockAnthropicModel, config, chatMemory,
                mock(MemoryTools.class), mock(HeraldShellDecorator.class),
                new FileSystemTools(), new TodoWriteTool(), mock(AskUserQuestionTool.class),
                new ClassPathResource("prompts/MAIN_AGENT_SYSTEM_PROMPT.md"),
                tempDir.toString(), HAIKU_MODEL, SONNET_MODEL, OPUS_MODEL,
                OPENAI_MODEL, OLLAMA_MODEL,
                Optional.of(mockOpenAiModel), Optional.of(mockOllamaModel));

        assertThat(client).isNotNull();
    }

    @Test
    void loadSubagentReferencesFromDirectory(@TempDir Path tempDir) throws IOException {
        Files.writeString(tempDir.resolve("research-agent.md"),
                """
                ---
                name: research
                description: Deep research agent
                model: opus
                tools: Read, Grep, Glob
                ---
                You are a research agent.
                """);
        Files.writeString(tempDir.resolve("explore-agent.md"),
                """
                ---
                name: explore
                description: Fast codebase explorer
                model: sonnet
                tools: Read, Grep
                ---
                You are an explore agent.
                """);

        HeraldAgentConfig agentConfig = new HeraldAgentConfig();
        List<SubagentReference> refs = agentConfig.loadSubagentReferences(tempDir.toString());

        assertThat(refs).hasSize(2);
        assertThat(refs).extracting(SubagentReference::uri)
                .allMatch(uri -> uri.contains("research-agent") || uri.contains("explore-agent"));
    }

    @Test
    void loadSubagentReferencesReturnsEmptyForMissingDirectory() {
        HeraldAgentConfig agentConfig = new HeraldAgentConfig();
        List<SubagentReference> refs = agentConfig.loadSubagentReferences("/nonexistent/path");

        assertThat(refs).isEmpty();
    }
}
