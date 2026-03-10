package com.herald.agent;

import com.herald.config.HeraldConfig;
import com.herald.memory.MemoryTools;
import com.herald.tools.AskUserQuestionTool;
import com.herald.tools.FileSystemTools;
import com.herald.tools.HeraldShellDecorator;
import com.herald.tools.TodoWriteTool;
import org.springaicommunity.agent.common.task.subagent.SubagentReference;
import org.springaicommunity.agent.tools.SkillsTool;
import org.springaicommunity.agent.tools.task.TaskOutputTool;
import org.springaicommunity.agent.tools.task.TaskTool;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentReferences;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentType;
import org.springaicommunity.agent.tools.task.repository.DefaultTaskRepository;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

@Configuration
class HeraldAgentConfig {

    private static final ZoneId DEFAULT_TIMEZONE = ZoneId.of("America/New_York");
    private static final DateTimeFormatter DATETIME_FORMAT =
            DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a z");
    private static final int MAX_CONVERSATION_MESSAGES = 100;

    // Keep in sync with .defaultTools() and .defaultToolCallbacks() in modelSwitcher()
    @Bean
    @Qualifier("activeToolNames")
    List<String> activeToolNames() {
        return List.of("memory", "shell", "filesystem", "todo", "ask", "task", "taskOutput", "skills");
    }

    @Bean
    ChatMemory chatMemory(JdbcChatMemoryRepository repository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(MAX_CONVERSATION_MESSAGES)
                .build();
    }

    @Bean
    @Qualifier("skillsDirectory")
    String skillsDirectory(@Value("${herald.agent.skills-directory:.claude/skills}") String skillsDirectory) {
        return skillsDirectory;
    }

    @Bean
    ModelSwitcher modelSwitcher(
            ChatModel chatModel,
            HeraldConfig config,
            ChatMemory chatMemory,
            MemoryTools memoryTools,
            HeraldShellDecorator shellDecorator,
            FileSystemTools fsTools,
            TodoWriteTool todoTool,
            AskUserQuestionTool askTool,
            JdbcTemplate jdbcTemplate,
            @Value("classpath:prompts/MAIN_AGENT_SYSTEM_PROMPT.md") Resource promptResource,
            @Value("${herald.agent.agents-directory:.claude/agents}") String agentsDirectory,
            @Qualifier("skillsDirectory") String skillsDirectory,
            @Value("${spring.ai.anthropic.chat.options.model:claude-sonnet-4-5}") String defaultModel,
            @Value("${herald.agent.model.haiku:claude-haiku-4-5}") String haikuModel,
            @Value("${herald.agent.model.sonnet:claude-sonnet-4-5}") String sonnetModel,
            @Value("${herald.agent.model.opus:claude-opus-4-5}") String opusModel,
            @Value("${herald.agent.model.openai:gpt-4o}") String openaiModel,
            @Value("${herald.agent.model.ollama:llama3.2}") String ollamaModel,
            @Qualifier("openaiChatModel") Optional<ChatModel> openaiChatModel,
            @Qualifier("ollamaChatModel") Optional<ChatModel> ollamaChatModel) {

        String promptTemplate = loadPromptTemplate(promptResource);
        String systemPrompt = resolvePrompt(promptTemplate, config);

        // Set up CONTEXT.md advisor — reads standing brief from disk each turn
        Path contextFilePath = resolveTildePath(config.contextFile());
        ContextMdAdvisor contextMdAdvisor = new ContextMdAdvisor(contextFilePath);
        contextMdAdvisor.ensureTemplateExists(loadContextTemplate());

        // Configure multi-model routing for subagent delegation
        var taskRepository = new DefaultTaskRepository();

        var subagentTypeBuilder = ClaudeSubagentType.builder()
                .chatClientBuilder("default", ChatClient.builder(chatModel))
                .chatClientBuilder("haiku", chatClientBuilderForModel(chatModel, haikuModel))
                .chatClientBuilder("sonnet", chatClientBuilderForModel(chatModel, sonnetModel))
                .chatClientBuilder("opus", chatClientBuilderForModel(chatModel, opusModel));

        openaiChatModel.ifPresent(model ->
                subagentTypeBuilder.chatClientBuilder("openai", chatClientBuilderForModel(model, openaiModel)));
        ollamaChatModel.ifPresent(model ->
                subagentTypeBuilder.chatClientBuilder("ollama", chatClientBuilderForModel(model, ollamaModel)));

        var claudeSubagentType = subagentTypeBuilder.build();

        var subagentRefs = loadSubagentReferences(agentsDirectory);

        var taskToolBuilder = TaskTool.builder()
                .subagentTypes(claudeSubagentType)
                .taskRepository(taskRepository);

        if (!subagentRefs.isEmpty()) {
            taskToolBuilder.subagentReferences(subagentRefs);
        }

        ToolCallback taskTool = taskToolBuilder.build();

        ToolCallback taskOutputTool = TaskOutputTool.builder()
                .taskRepository(taskRepository)
                .build();

        ToolCallback skillsTool = buildSkillsTool(skillsDirectory);

        // Factory that creates a ChatClient.Builder with all shared config for any ChatModel
        Function<ChatModel, ChatClient.Builder> clientBuilderFactory = cm -> {
                var builder = ChatClient.builder(cm)
                        .defaultSystem(systemPrompt)
                        .defaultTools(memoryTools, shellDecorator, fsTools, todoTool, askTool);
                if (skillsTool != null) {
                    builder.defaultToolCallbacks(taskTool, taskOutputTool, skillsTool);
                } else {
                    builder.defaultToolCallbacks(taskTool, taskOutputTool);
                }
                return builder
                        .defaultAdvisors(
                                new DateTimePromptAdvisor(DEFAULT_TIMEZONE, DATETIME_FORMAT),
                                contextMdAdvisor,
                                new MemoryBlockAdvisor(memoryTools),
                                MessageChatMemoryAdvisor.builder(chatMemory).build(),
                                ToolCallAdvisor.builder()
                                        .conversationHistoryEnabled(false)
                                        .build()
                        );
        };

        // Build the initial client from the default Anthropic ChatModel
        ChatClient initialClient = clientBuilderFactory.apply(chatModel).build();

        // Register available provider ChatModels
        Map<String, ChatModel> availableModels = new LinkedHashMap<>();
        availableModels.put("anthropic", chatModel);
        openaiChatModel.ifPresent(model -> availableModels.put("openai", model));
        ollamaChatModel.ifPresent(model -> availableModels.put("ollama", model));

        var switcher = new ModelSwitcher(availableModels, jdbcTemplate, clientBuilderFactory,
                initialClient, "anthropic", defaultModel);
        switcher.loadPersistedOverride();
        return switcher;
    }


    private ChatClient.Builder chatClientBuilderForModel(ChatModel chatModel, String modelId) {
        return ChatClient.builder(chatModel)
                .defaultOptions(chatOptionsForModel(chatModel, modelId));
    }

    static org.springframework.ai.chat.prompt.ChatOptions chatOptionsForModel(ChatModel chatModel, String modelId) {
        if (chatModel instanceof OpenAiChatModel) {
            return OpenAiChatOptions.builder().model(modelId).build();
        }
        // Default to Anthropic (includes AnthropicChatModel and any future Anthropic-compatible models)
        return AnthropicChatOptions.builder().model(modelId).build();
    }

    /**
     * Resolves static placeholders in the prompt template. Dynamic placeholders like
     * {@code {current_datetime}} and {@code {timezone}} are intentionally left unresolved
     * here and handled per-turn by {@link DateTimePromptAdvisor}.
     */
    String resolvePrompt(String template, HeraldConfig config) {
        return template
                .replace("{persona}", config.persona())
                .replace("{system_prompt_extra}", config.systemPromptExtra());
    }

    static ToolCallback buildSkillsTool(String skillsDirectory) {
        Path skillsPath = Path.of(skillsDirectory);
        if (!Files.isDirectory(skillsPath)) {
            return null;
        }
        var skills = org.springaicommunity.agent.utils.Skills.loadDirectory(skillsDirectory);
        if (skills.isEmpty()) {
            return null;
        }
        return SkillsTool.builder()
                .addSkillsDirectory(skillsDirectory)
                .build();
    }

    List<SubagentReference> loadSubagentReferences(String agentsDirectory) {
        Path agentsPath = Path.of(agentsDirectory);
        if (Files.isDirectory(agentsPath)) {
            return ClaudeSubagentReferences.fromRootDirectory(agentsDirectory);
        }
        return List.of();
    }

    private String loadPromptTemplate(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load system prompt template", e);
        }
    }

    String loadContextTemplate() {
        try (InputStream in = getClass().getResourceAsStream("/prompts/CONTEXT_TEMPLATE.md")) {
            if (in == null) {
                throw new IllegalStateException("CONTEXT_TEMPLATE.md not found on classpath");
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load CONTEXT.md template", e);
        }
    }

    static Path resolveTildePath(String path) {
        if (path.startsWith("~/") || path.equals("~")) {
            return Path.of(System.getProperty("user.home")).resolve(path.substring(2));
        }
        return Path.of(path);
    }
}
