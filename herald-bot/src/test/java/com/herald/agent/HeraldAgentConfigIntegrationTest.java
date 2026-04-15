package com.herald.agent;

import com.herald.config.HeraldConfig;
import com.herald.cron.CronTools;
import org.springframework.beans.factory.ObjectProvider;
import com.herald.tools.FileSystemTools;
import com.herald.tools.GwsTools;
import com.herald.tools.HeraldShellDecorator;
import com.herald.tools.TelegramSendTool;
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
    private static final String GEMINI_MODEL = "gemini-2.5-flash";
    private static final String LMSTUDIO_MODEL = "qwen/qwen3.5-35b-a3b";

    @Test
    void modelSwitcherBeanCreatedWithAllToolsAndAdvisors(@TempDir Path tempDir) {
        HeraldAgentConfig agentConfig = new HeraldAgentConfig();

        JdbcChatMemoryRepository chatMemoryRepository = mock(JdbcChatMemoryRepository.class);
        ChatMemory chatMemory = agentConfig.chatMemory(chatMemoryRepository);
        assertThat(chatMemory).isInstanceOf(MessageWindowChatMemory.class);

        ChatModel mockModel = mock(ChatModel.class);

        HeraldConfig config = new HeraldConfig(null, null,
                new HeraldConfig.Agent("TestBot", null, null, null, null, null, null), null, null, null, null, null, null, null);

        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of());

        ModelSwitcher switcher = agentConfig.modelSwitcher(
                mockModel, config, false, Optional.of(chatMemory),
                mock(HeraldShellDecorator.class),
                new FileSystemTools(), Optional.empty(), mock(ObjectProvider.class),
                Optional.of(mock(TelegramSendTool.class)),
                Optional.of(mock(GwsTools.class)), new WebTools(""), Optional.of(mock(CronTools.class)),
                Optional.of(jdbcTemplate),
                new ClassPathResource("prompts/MAIN_AGENT_SYSTEM_PROMPT.md"),
                tempDir.toString(), new ReloadableSkillsTool(tempDir.resolve("skills").toString()),
                new ValidateSkillTool(tempDir.resolve("skills").toString()),
                SONNET_MODEL, HAIKU_MODEL, SONNET_MODEL, OPUS_MODEL,
                OPENAI_MODEL, OLLAMA_MODEL, GEMINI_MODEL, LMSTUDIO_MODEL,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                List.of("shell", "filesystem", "todoWrite", "askUserQuestion", "task", "taskOutput", "skills", "web", "toolSearchTool"));

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
                new HeraldConfig.Agent("TestBot", null, null, null, null, null, null), null, null, null, null, null, null, null);

        JdbcChatMemoryRepository chatMemoryRepository = mock(JdbcChatMemoryRepository.class);
        ChatMemory chatMemory = agentConfig.chatMemory(chatMemoryRepository);

        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of());

        ModelSwitcher switcher = agentConfig.modelSwitcher(
                mockModel, config, false, Optional.of(chatMemory),
                mock(HeraldShellDecorator.class),
                new FileSystemTools(), Optional.empty(), mock(ObjectProvider.class),
                Optional.of(mock(TelegramSendTool.class)),
                Optional.of(mock(GwsTools.class)), new WebTools(""), Optional.of(mock(CronTools.class)),
                Optional.of(jdbcTemplate),
                new ClassPathResource("prompts/MAIN_AGENT_SYSTEM_PROMPT.md"),
                tempDir.toString(), new ReloadableSkillsTool(tempDir.resolve("skills").toString()),
                new ValidateSkillTool(tempDir.resolve("skills").toString()),
                SONNET_MODEL, HAIKU_MODEL, SONNET_MODEL, OPUS_MODEL,
                OPENAI_MODEL, OLLAMA_MODEL, GEMINI_MODEL, LMSTUDIO_MODEL,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                List.of("shell", "filesystem", "todoWrite", "askUserQuestion", "task", "taskOutput", "skills", "web", "toolSearchTool"));

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
                new HeraldConfig.Agent("TestBot", null, null, null, null, null, null), null, null, null, null, null, null, null);

        JdbcChatMemoryRepository chatMemoryRepository = mock(JdbcChatMemoryRepository.class);
        ChatMemory chatMemory = agentConfig.chatMemory(chatMemoryRepository);

        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of());

        ModelSwitcher switcher = agentConfig.modelSwitcher(
                mockAnthropicModel, config, false, Optional.of(chatMemory),
                mock(HeraldShellDecorator.class),
                new FileSystemTools(), Optional.empty(), mock(ObjectProvider.class),
                Optional.of(mock(TelegramSendTool.class)),
                Optional.of(mock(GwsTools.class)), new WebTools(""), Optional.of(mock(CronTools.class)),
                Optional.of(jdbcTemplate),
                new ClassPathResource("prompts/MAIN_AGENT_SYSTEM_PROMPT.md"),
                tempDir.toString(), new ReloadableSkillsTool(tempDir.resolve("skills").toString()),
                new ValidateSkillTool(tempDir.resolve("skills").toString()),
                SONNET_MODEL, HAIKU_MODEL, SONNET_MODEL, OPUS_MODEL,
                OPENAI_MODEL, OLLAMA_MODEL, GEMINI_MODEL, LMSTUDIO_MODEL,
                Optional.of(mockOpenAiModel), Optional.of(mockOllamaModel), Optional.empty(), Optional.empty(),
                List.of("shell", "filesystem", "todoWrite", "askUserQuestion", "task", "taskOutput", "skills", "web", "toolSearchTool"));

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

    @Test
    void modelSwitcherBuildsSuccessfullyWithNoPersistenceBeans(@TempDir Path tempDir) {
        HeraldAgentConfig agentConfig = new HeraldAgentConfig();
        ChatModel mockModel = mock(ChatModel.class);
        HeraldConfig config = new HeraldConfig(null, null,
                new HeraldConfig.Agent("TestBot", null, null, null, null, null, null), null, null, null, null, null, null, null);

        ModelSwitcher switcher = agentConfig.modelSwitcher(
                mockModel, config, false,
                Optional.empty(),  // chatMemory
                mock(HeraldShellDecorator.class),
                new FileSystemTools(),
                Optional.empty(),
                mock(ObjectProvider.class),
                Optional.empty(),  // telegramSendTool
                Optional.empty(),  // gwsTools
                new WebTools(""),
                Optional.empty(),  // cronTools
                Optional.empty(),  // jdbcTemplate
                new ClassPathResource("prompts/MAIN_AGENT_SYSTEM_PROMPT.md"),
                tempDir.toString(),
                new ReloadableSkillsTool(tempDir.resolve("skills").toString()),
                new ValidateSkillTool(tempDir.resolve("skills").toString()),
                SONNET_MODEL, HAIKU_MODEL, SONNET_MODEL, OPUS_MODEL,
                OPENAI_MODEL, OLLAMA_MODEL, GEMINI_MODEL, LMSTUDIO_MODEL,
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                List.of("shell", "filesystem", "todoWrite", "askUserQuestion", "task", "taskOutput", "skills", "web", "toolSearchTool"));

        assertThat(switcher).isNotNull();
        assertThat(switcher.getActiveClient()).isNotNull();
    }

    @Test
    void buildAdvisorChainExcludesPersistenceAdvisorsWhenNoBeans(@TempDir Path tempDir) {
        HeraldAgentConfig agentConfig = new HeraldAgentConfig();
        ContextMdAdvisor contextMdAdvisor = new ContextMdAdvisor(Path.of("/tmp/test-context.md"));
        ChatModel mockModel = mock(ChatModel.class);
        HeraldConfig config = new HeraldConfig(null, null,
                new HeraldConfig.Agent("TestBot", null, null, null, null, null, null), null, null, null, null, null, null, null);

        var advisors = agentConfig.buildAdvisorChain(
                Optional.empty(), contextMdAdvisor, tempDir,
                mockModel, config, false);

        // Should have: DateTimePromptAdvisor, ContextMdAdvisor, AutoMemoryToolsAdvisor, PromptDumpAdvisor, ToolCallAdvisor
        assertThat(advisors).hasSize(5);
        assertThat(advisors).noneMatch(a -> a instanceof OneShotMemoryAdvisor);
        assertThat(advisors).noneMatch(a -> a instanceof ContextCompactionAdvisor);
    }

    @Test
    void advisorChainUsesToolSearchToolCallAdvisor(@TempDir Path tempDir) {
        HeraldAgentConfig agentConfig = new HeraldAgentConfig();
        ContextMdAdvisor contextMdAdvisor = new ContextMdAdvisor(Path.of("/tmp/test-context.md"));
        ChatModel mockModel = mock(ChatModel.class);
        HeraldConfig config = new HeraldConfig(null, null,
                new HeraldConfig.Agent("TestBot", null, null, null, null, null, null), null, null, null, null, null, null, null);

        var advisors = agentConfig.buildAdvisorChain(
                Optional.empty(), contextMdAdvisor, tempDir,
                mockModel, config, false);

        assertThat(advisors)
                .filteredOn(a -> a instanceof org.springaicommunity.tool.search.ToolSearchToolCallAdvisor)
                .hasSize(1);
        // ToolSearchToolCallAdvisor replaces ToolCallAdvisor — none of the base type should remain
        assertThat(advisors)
                .filteredOn(a -> a.getClass().equals(org.springframework.ai.chat.client.advisor.ToolCallAdvisor.class))
                .isEmpty();
    }

    @Test
    void buildToolListContainsStatelessTools() {
        HeraldAgentConfig agentConfig = new HeraldAgentConfig();
        var todoTool = org.springaicommunity.agent.tools.TodoWriteTool.builder().build();
        var askTool = org.springaicommunity.agent.tools.AskUserQuestionTool.builder()
                .questionHandler(q -> java.util.Map.of())
                .build();

        var tools = agentConfig.buildToolList(
                mock(HeraldShellDecorator.class),
                new FileSystemTools(),
                todoTool, askTool,
                Optional.empty(), Optional.empty(),
                new WebTools(""),
                Optional.empty(),
                new ValidateSkillTool("skills"));

        // shellDecorator, fsTools, todoTool, askTool, webTools, validateSkillTool = 6
        assertThat(tools).hasSize(6);
    }
}
