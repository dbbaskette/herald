package com.herald.agent;

import com.herald.config.HeraldConfig;
import com.herald.cron.CronTools;
import org.springframework.beans.factory.ObjectProvider;
import com.herald.tools.FileSystemTools;
import com.herald.tools.GwsTools;
import com.herald.tools.HeraldShellDecorator;
import com.herald.tools.RemindersAvailabilityChecker;
import com.herald.tools.RemindersTools;
import com.herald.tools.TelegramSendTool;
import com.herald.tools.WebTools;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springaicommunity.agent.common.task.subagent.SubagentReference;
import org.springframework.ai.chat.memory.ChatMemory;

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
        assertThat(chatMemory).isInstanceOf(TurnSafeChatMemory.class);

        ChatModel mockModel = mock(ChatModel.class);

        HeraldConfig config = new HeraldConfig(null, null,
                new HeraldConfig.Agent("TestBot", null, null, null, null, null, null), null, null, null, null, null, null, null);

        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of());

        ModelSwitcher switcher = agentConfig.modelSwitcher(
                mockModel, new org.springaicommunity.agent.tools.task.repository.DefaultTaskRepository(), config, Optional.empty(), new com.herald.agent.PromptDumpAdvisor(false), Optional.of(chatMemory),
                mock(HeraldShellDecorator.class),
                new FileSystemTools(), Optional.empty(), mock(ObjectProvider.class),
                Optional.of(mock(TelegramSendTool.class)),
                Optional.of(mock(GwsTools.class)),
                Optional.empty(), mock(RemindersAvailabilityChecker.class),
                new WebTools(""), Optional.of(mock(CronTools.class)),
                Optional.of(jdbcTemplate),
                new ClassPathResource("prompts/MAIN_AGENT_SYSTEM_PROMPT.md"),
                tempDir.toString(), new ReloadableSkillsTool(tempDir.resolve("skills").toString()),
                new ValidateSkillTool(tempDir.resolve("skills").toString()),
                SONNET_MODEL, HAIKU_MODEL, SONNET_MODEL, OPUS_MODEL,
                OPENAI_MODEL, OLLAMA_MODEL, GEMINI_MODEL, LMSTUDIO_MODEL,
                "", "", "", "", "",
                "system_and_tools",
                "daily",
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                List.of("shell", "filesystem", "todoWrite", "askUserQuestion", "task", "taskOutput", "skills", "web", "toolSearchTool"),
                Optional.empty(),
                new com.herald.agent.ToolEventBus());

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
                mockModel, new org.springaicommunity.agent.tools.task.repository.DefaultTaskRepository(), config, Optional.empty(), new com.herald.agent.PromptDumpAdvisor(false), Optional.of(chatMemory),
                mock(HeraldShellDecorator.class),
                new FileSystemTools(), Optional.empty(), mock(ObjectProvider.class),
                Optional.of(mock(TelegramSendTool.class)),
                Optional.of(mock(GwsTools.class)),
                Optional.empty(), mock(RemindersAvailabilityChecker.class),
                new WebTools(""), Optional.of(mock(CronTools.class)),
                Optional.of(jdbcTemplate),
                new ClassPathResource("prompts/MAIN_AGENT_SYSTEM_PROMPT.md"),
                tempDir.toString(), new ReloadableSkillsTool(tempDir.resolve("skills").toString()),
                new ValidateSkillTool(tempDir.resolve("skills").toString()),
                SONNET_MODEL, HAIKU_MODEL, SONNET_MODEL, OPUS_MODEL,
                OPENAI_MODEL, OLLAMA_MODEL, GEMINI_MODEL, LMSTUDIO_MODEL,
                "", "", "", "", "",
                "system_and_tools",
                "daily",
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                List.of("shell", "filesystem", "todoWrite", "askUserQuestion", "task", "taskOutput", "skills", "web", "toolSearchTool"),
                Optional.empty(),
                new com.herald.agent.ToolEventBus());

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
                mockAnthropicModel, new org.springaicommunity.agent.tools.task.repository.DefaultTaskRepository(), config, Optional.empty(), new com.herald.agent.PromptDumpAdvisor(false), Optional.of(chatMemory),
                mock(HeraldShellDecorator.class),
                new FileSystemTools(), Optional.empty(), mock(ObjectProvider.class),
                Optional.of(mock(TelegramSendTool.class)),
                Optional.of(mock(GwsTools.class)),
                Optional.empty(), mock(RemindersAvailabilityChecker.class),
                new WebTools(""), Optional.of(mock(CronTools.class)),
                Optional.of(jdbcTemplate),
                new ClassPathResource("prompts/MAIN_AGENT_SYSTEM_PROMPT.md"),
                tempDir.toString(), new ReloadableSkillsTool(tempDir.resolve("skills").toString()),
                new ValidateSkillTool(tempDir.resolve("skills").toString()),
                SONNET_MODEL, HAIKU_MODEL, SONNET_MODEL, OPUS_MODEL,
                OPENAI_MODEL, OLLAMA_MODEL, GEMINI_MODEL, LMSTUDIO_MODEL,
                "", "", "", "", "",
                "system_and_tools",
                "daily",
                Optional.of(mockOpenAiModel), Optional.of(mockOllamaModel), Optional.empty(), Optional.empty(),
                List.of("shell", "filesystem", "todoWrite", "askUserQuestion", "task", "taskOutput", "skills", "web", "toolSearchTool"),
                Optional.empty(),
                new com.herald.agent.ToolEventBus());

        assertThat(switcher).isNotNull();
        assertThat(switcher.getActiveClient()).isNotNull();
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"local", "bound", "persisted"})
    void modelSwitcherUsesResolvedBindingAndRetainsPersistedOverrides(String mode, @TempDir Path tempDir) throws Exception {
        HeraldAgentConfig agentConfig = new HeraldAgentConfig();

        ChatModel mockAnthropicModel = mock(ChatModel.class);
        ChatModel mockOpenAiModel = mock(OpenAiChatModel.class);
        ChatModel mockOllamaModel = mock(OpenAiChatModel.class);

        String binding;
        try (var input = getClass().getResourceAsStream("/genai/single-model.json")) {
            binding = new String(input.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
        var env = com.herald.doctor.DiagnosticEnvironment.load(
                new String[]{"--spring.config.location=classpath:/application.yaml"},
                java.util.Map.of("VCAP_SERVICES", mode.equals("local") ? "{}" : binding,
                        "HERALD_DEFAULT_PROVIDER", "openai", "OPENAI_API_KEY", "local-key",
                        "HERALD_MODEL_OPENAI", "local-model", "HERALD_AGENT_MODEL_CATALOG_OPENAI", "local-model",
                        "HERALD_MEMORIES_DIR", tempDir.resolve("memories").toString(),
                        "HERALD_AGENT_CONTEXT_FILE", tempDir.resolve("CONTEXT.md").toString()));
        HeraldConfig config = org.springframework.boot.context.properties.bind.Binder.get(env)
                .bind("herald", HeraldConfig.class).get();
        String resolvedModel = env.getProperty("herald.agent.model.openai");
        String catalog = env.getProperty("herald.agent.model-catalog.openai");

        JdbcChatMemoryRepository chatMemoryRepository = mock(JdbcChatMemoryRepository.class);
        ChatMemory chatMemory = agentConfig.chatMemory(chatMemoryRepository);

        JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
        when(jdbcTemplate.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(mode.equals("persisted") ? java.util.Collections.singletonList(new String[]{"openai", "persisted-model"}) : List.of());

        ModelSwitcher switcher = agentConfig.modelSwitcher(
                mockAnthropicModel, new org.springaicommunity.agent.tools.task.repository.DefaultTaskRepository(), config, Optional.empty(), new com.herald.agent.PromptDumpAdvisor(false), Optional.of(chatMemory),
                mock(HeraldShellDecorator.class),
                new FileSystemTools(), Optional.empty(), mock(ObjectProvider.class),
                Optional.of(mock(TelegramSendTool.class)),
                Optional.of(mock(GwsTools.class)),
                Optional.empty(), mock(RemindersAvailabilityChecker.class),
                new WebTools(""), Optional.of(mock(CronTools.class)),
                Optional.of(jdbcTemplate),
                new ClassPathResource("prompts/MAIN_AGENT_SYSTEM_PROMPT.md"),
                tempDir.toString(), new ReloadableSkillsTool(tempDir.resolve("skills").toString()),
                new ValidateSkillTool(tempDir.resolve("skills").toString()),
                SONNET_MODEL, HAIKU_MODEL, SONNET_MODEL, OPUS_MODEL,
                resolvedModel, OLLAMA_MODEL, GEMINI_MODEL, LMSTUDIO_MODEL,
                "", catalog, "", "", "",
                "system_and_tools",
                "daily",
                Optional.of(mockOpenAiModel), Optional.of(mockOllamaModel), Optional.empty(), Optional.empty(),
                List.of("shell", "filesystem", "todoWrite", "askUserQuestion", "task", "taskOutput", "skills", "web", "toolSearchTool"),
                Optional.empty(),
                new com.herald.agent.ToolEventBus());

        assertThat(switcher.getActiveProvider()).isEqualTo("openai");
        assertThat(switcher.getActiveModel()).isEqualTo(mode.equals("persisted") ? "persisted-model" : resolvedModel);
        assertThat(switcher.getAvailableProviderDefaults()).containsEntry("openai", resolvedModel);
        assertThat(switcher.getProviderModelCatalog().get("openai")).containsExactly(resolvedModel);

        // Exercise the same builder used for OpenAI subagents, inspecting the outgoing prompt.
        when(mockOpenAiModel.call(any(org.springframework.ai.chat.prompt.Prompt.class))).thenReturn(
                new org.springframework.ai.chat.model.ChatResponse(List.of(new org.springframework.ai.chat.model.Generation(
                        new org.springframework.ai.chat.messages.AssistantMessage("PONG")))));
        org.springframework.ai.chat.client.ChatClient.Builder subagentBuilder =
                org.springframework.test.util.ReflectionTestUtils.invokeMethod(agentConfig,
                        "chatClientBuilderForModel", mockOpenAiModel, resolvedModel);
        assertThat(subagentBuilder.build().prompt().user("ping").call().content()).isEqualTo("PONG");
        var prompt = org.mockito.ArgumentCaptor.forClass(org.springframework.ai.chat.prompt.Prompt.class);
        org.mockito.Mockito.verify(mockOpenAiModel).call(prompt.capture());
        assertThat(prompt.getValue().getOptions().getModel()).isEqualTo(resolvedModel);
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
                mockModel, new org.springaicommunity.agent.tools.task.repository.DefaultTaskRepository(), config, Optional.empty(), new com.herald.agent.PromptDumpAdvisor(false),
                Optional.empty(),  // chatMemory
                mock(HeraldShellDecorator.class),
                new FileSystemTools(),
                Optional.empty(),
                mock(ObjectProvider.class),
                Optional.empty(),  // telegramSendTool
                Optional.empty(),  // gwsTools
                Optional.empty(), mock(RemindersAvailabilityChecker.class),
                new WebTools(""),
                Optional.empty(),  // cronTools
                Optional.empty(),  // jdbcTemplate
                new ClassPathResource("prompts/MAIN_AGENT_SYSTEM_PROMPT.md"),
                tempDir.toString(),
                new ReloadableSkillsTool(tempDir.resolve("skills").toString()),
                new ValidateSkillTool(tempDir.resolve("skills").toString()),
                SONNET_MODEL, HAIKU_MODEL, SONNET_MODEL, OPUS_MODEL,
                OPENAI_MODEL, OLLAMA_MODEL, GEMINI_MODEL, LMSTUDIO_MODEL,
                "", "", "", "", "",
                "system_and_tools",
                "daily",
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.empty(),
                List.of("shell", "filesystem", "todoWrite", "askUserQuestion", "task", "taskOutput", "skills", "web", "toolSearchTool"),
                Optional.empty(),
                new com.herald.agent.ToolEventBus());

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
                tempDir.resolve("log.md"), tempDir.resolve("hot.md"),
                mockModel, config,
                Optional.empty(), new com.herald.agent.PromptDumpAdvisor(false),
                Optional.empty(), false, Optional.empty());

        // Persistence is optional; execution safety remains active on every client.
        assertThat(advisors).hasSize(9);
        assertThat(advisors).anyMatch(a -> a instanceof LeadingTurnSanitizingAdvisor);
        assertThat(advisors).noneMatch(a -> a instanceof OneShotMemoryAdvisor);
        assertThat(advisors).noneMatch(a -> a instanceof ContextCompactionAdvisor);
    }

    @Test
    void advisorChainHasOneBoundedToolLoop(@TempDir Path tempDir) {
        // Explicit upstream loop prevents auto-registration and places the step guard inside it.
        HeraldAgentConfig agentConfig = new HeraldAgentConfig();
        ContextMdAdvisor contextMdAdvisor = new ContextMdAdvisor(Path.of("/tmp/test-context.md"));
        ChatModel mockModel = mock(ChatModel.class);
        HeraldConfig config = new HeraldConfig(null, null,
                new HeraldConfig.Agent("TestBot", null, null, null, null, null, null), null, null, null, null, null, null, null);

        var advisors = agentConfig.buildAdvisorChain(
                Optional.empty(), contextMdAdvisor, tempDir,
                tempDir.resolve("log.md"), tempDir.resolve("hot.md"),
                mockModel, config,
                Optional.empty(), new com.herald.agent.PromptDumpAdvisor(false),
                Optional.empty(), false, Optional.empty());

        assertThat(advisors.stream().filter(a -> a instanceof org.springframework.ai.chat.client.advisor.ToolCallingAdvisor)).hasSize(1);
        assertThat(advisors).anyMatch(ExecutionBoundaryAdvisor.class::isInstance);
        assertThat(advisors).anyMatch(ExecutionStepAdvisor.class::isInstance);
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
                Optional.empty(), mock(RemindersAvailabilityChecker.class),
                new WebTools(""),
                Optional.empty(),
                new ValidateSkillTool("skills"));

        // shellDecorator, fsTools, todoTool, askTool, webTools, validateSkillTool = 6
        assertThat(tools).hasSize(6);
    }
}
