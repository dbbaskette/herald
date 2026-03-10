package com.herald.agent;

import com.herald.config.HeraldConfig;
import com.herald.memory.MemoryTools;
import com.herald.tools.AskUserQuestionTool;
import com.herald.tools.FileSystemTools;
import com.herald.tools.GwsTools;
import com.herald.tools.HeraldShellDecorator;
import com.herald.tools.TodoWriteTool;
import com.herald.tools.WebTools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.agent.common.task.subagent.SubagentReference;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import org.springframework.context.ApplicationEventPublisher;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

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
    void modelSwitcherBeanCreatedWithAllToolsAndAdvisors(@TempDir Path tempDir) {
        HeraldAgentConfig agentConfig = new HeraldAgentConfig();

        JdbcChatMemoryRepository chatMemoryRepository = mock(JdbcChatMemoryRepository.class);
        ChatMemory chatMemory = agentConfig.chatMemory(chatMemoryRepository);
        assertThat(chatMemory).isInstanceOf(MessageWindowChatMemory.class);

        ChatModel mockModel = mock(ChatModel.class);

        HeraldConfig config = new HeraldConfig(null, null,
                new HeraldConfig.Agent("TestBot", null, null, null), null, null);

        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of());

        ModelSwitcher switcher = agentConfig.modelSwitcher(
                mockModel, config, chatMemory,
                mock(MemoryTools.class), mock(HeraldShellDecorator.class),
                new FileSystemTools(), new TodoWriteTool(mock(ApplicationEventPublisher.class)), mock(AskUserQuestionTool.class),
                mock(GwsTools.class), new WebTools(""), jdbcTemplate,
                new ClassPathResource("prompts/MAIN_AGENT_SYSTEM_PROMPT.md"),
                tempDir.toString(), new ReloadableSkillsTool(tempDir.resolve("skills").toString()),
                SONNET_MODEL, HAIKU_MODEL, SONNET_MODEL, OPUS_MODEL,
                OPENAI_MODEL, OLLAMA_MODEL, Optional.empty(), Optional.empty());

        assertThat(switcher).isNotNull();
        assertThat(switcher.getActiveClient()).isNotNull();
    }

    @Test
    void modelSwitcherLoadsSubagentDefinitionsFromDirectory(@TempDir Path tempDir) throws IOException {
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

        HeraldConfig config = new HeraldConfig(null, null,
                new HeraldConfig.Agent("TestBot", null, null, null), null, null);

        JdbcChatMemoryRepository chatMemoryRepository = mock(JdbcChatMemoryRepository.class);
        ChatMemory chatMemory = agentConfig.chatMemory(chatMemoryRepository);

        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of());

        ModelSwitcher switcher = agentConfig.modelSwitcher(
                mockModel, config, chatMemory,
                mock(MemoryTools.class), mock(HeraldShellDecorator.class),
                new FileSystemTools(), new TodoWriteTool(mock(ApplicationEventPublisher.class)), mock(AskUserQuestionTool.class),
                mock(GwsTools.class), new WebTools(""), jdbcTemplate,
                new ClassPathResource("prompts/MAIN_AGENT_SYSTEM_PROMPT.md"),
                tempDir.toString(), new ReloadableSkillsTool(tempDir.resolve("skills").toString()),
                SONNET_MODEL, HAIKU_MODEL, SONNET_MODEL, OPUS_MODEL,
                OPENAI_MODEL, OLLAMA_MODEL, Optional.empty(), Optional.empty());

        assertThat(switcher).isNotNull();
        assertThat(switcher.getActiveClient()).isNotNull();
    }

    @Test
    void modelSwitcherWiresOpenAiAndOllamaProviders(@TempDir Path tempDir) {
        HeraldAgentConfig agentConfig = new HeraldAgentConfig();

        ChatModel mockAnthropicModel = mock(ChatModel.class);
        ChatModel mockOpenAiModel = mock(OpenAiChatModel.class);
        ChatModel mockOllamaModel = mock(OpenAiChatModel.class);

        HeraldConfig config = new HeraldConfig(null, null,
                new HeraldConfig.Agent("TestBot", null, null, null), null, null);

        JdbcChatMemoryRepository chatMemoryRepository = mock(JdbcChatMemoryRepository.class);
        ChatMemory chatMemory = agentConfig.chatMemory(chatMemoryRepository);

        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of());

        ModelSwitcher switcher = agentConfig.modelSwitcher(
                mockAnthropicModel, config, chatMemory,
                mock(MemoryTools.class), mock(HeraldShellDecorator.class),
                new FileSystemTools(), new TodoWriteTool(mock(ApplicationEventPublisher.class)), mock(AskUserQuestionTool.class),
                mock(GwsTools.class), new WebTools(""), jdbcTemplate,
                new ClassPathResource("prompts/MAIN_AGENT_SYSTEM_PROMPT.md"),
                tempDir.toString(), new ReloadableSkillsTool(tempDir.resolve("skills").toString()),
                SONNET_MODEL, HAIKU_MODEL, SONNET_MODEL, OPUS_MODEL,
                OPENAI_MODEL, OLLAMA_MODEL,
                Optional.of(mockOpenAiModel), Optional.of(mockOllamaModel));

        assertThat(switcher).isNotNull();
        assertThat(switcher.getActiveClient()).isNotNull();
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
