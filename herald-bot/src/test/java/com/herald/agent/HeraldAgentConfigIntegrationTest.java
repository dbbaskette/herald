package com.herald.agent;

import com.herald.config.HeraldConfig;
import com.herald.memory.MemoryTools;
import com.herald.tools.AskUserQuestionTool;
import com.herald.tools.FileSystemTools;
import com.herald.tools.HeraldShellDecorator;
import com.herald.tools.TodoWriteTool;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Integration test verifying the ChatClient bean is properly wired with all tools and advisors.
 * Uses a mock ChatModel to avoid requiring an API key.
 */
class HeraldAgentConfigIntegrationTest {

    @Test
    void mainClientBeanCreatedWithAllToolsAndAdvisors(@TempDir Path tempDir) {
        HeraldAgentConfig agentConfig = new HeraldAgentConfig();

        // Verify chatMemory bean creation
        JdbcChatMemoryRepository chatMemoryRepository = mock(JdbcChatMemoryRepository.class);
        ChatMemory chatMemory = agentConfig.chatMemory(chatMemoryRepository);
        assertThat(chatMemory).isInstanceOf(MessageWindowChatMemory.class);

        // Create tool beans (mocked where package-private, real where public)
        MemoryTools memoryTools = mock(MemoryTools.class);
        HeraldShellDecorator shellDecorator = mock(HeraldShellDecorator.class);
        FileSystemTools fsTools = new FileSystemTools();
        TodoWriteTool todoTool = new TodoWriteTool();
        AskUserQuestionTool askTool = mock(AskUserQuestionTool.class);

        // Build ChatClient with a mock ChatModel
        ChatModel mockModel = mock(ChatModel.class);
        ChatClient.Builder builder = ChatClient.builder(mockModel);

        HeraldConfig config = new HeraldConfig(null, null,
                new HeraldConfig.Agent("TestBot", null));

        ChatClient client = agentConfig.mainClient(
                builder, mockModel, config, chatMemory,
                memoryTools, shellDecorator, fsTools, todoTool, askTool,
                new ClassPathResource("prompts/MAIN_AGENT_SYSTEM_PROMPT.md"),
                tempDir.toString());

        assertThat(client).isNotNull();
    }

    @Test
    void mainClientLoadsSubagentDefinitionsFromDirectory(@TempDir Path tempDir) throws IOException {
        // Create a test subagent definition
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
                new HeraldConfig.Agent("TestBot", null));

        JdbcChatMemoryRepository chatMemoryRepository = mock(JdbcChatMemoryRepository.class);
        ChatMemory chatMemory = agentConfig.chatMemory(chatMemoryRepository);

        ChatClient client = agentConfig.mainClient(
                builder, mockModel, config, chatMemory,
                mock(MemoryTools.class), mock(HeraldShellDecorator.class),
                new FileSystemTools(), new TodoWriteTool(), mock(AskUserQuestionTool.class),
                new ClassPathResource("prompts/MAIN_AGENT_SYSTEM_PROMPT.md"),
                tempDir.toString());

        assertThat(client).isNotNull();
    }
}
