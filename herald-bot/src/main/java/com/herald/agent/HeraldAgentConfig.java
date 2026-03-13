package com.herald.agent;

import com.herald.config.HeraldConfig;
import com.herald.cron.CronTools;
import com.herald.memory.MemoryTools;
import com.herald.telegram.TelegramQuestionHandler;
import org.springaicommunity.agent.tools.AskUserQuestionTool;
import com.herald.tools.FileSystemTools;
import com.herald.tools.GwsTools;
import com.herald.tools.HeraldShellDecorator;
import com.herald.tools.TelegramSendTool;
import com.herald.tools.TodoProgressEvent;
import com.herald.tools.WebTools;
import org.springaicommunity.agent.common.task.subagent.SubagentReference;
import org.springaicommunity.agent.tools.task.TaskOutputTool;
import org.springaicommunity.agent.tools.task.TaskTool;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentReferences;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentType;
import org.springaicommunity.agent.tools.task.repository.DefaultTaskRepository;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
// MessageChatMemoryAdvisor replaced by OneShotMemoryAdvisor to prevent
// re-loading/re-saving memory on each ToolCallAdvisor iteration
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

@Configuration
public class HeraldAgentConfig {

    private static final Logger log = LoggerFactory.getLogger(HeraldAgentConfig.class);
    private static final ZoneId DEFAULT_TIMEZONE = ZoneId.of("America/New_York");
    private static final DateTimeFormatter DATETIME_FORMAT =
            DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a z");
    private static final int MAX_CONVERSATION_MESSAGES = 100;

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("cron-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }

    // Keep in sync with .defaultTools() and .defaultToolCallbacks() in modelSwitcher()
    @Bean
    @Qualifier("activeToolNames")
    public List<String> activeToolNames() {
        return List.of("memory", "shell", "filesystem", "todoWrite", "askUserQuestion", "task", "taskOutput", "skills", "gws", "web", "telegram_send", "cron");
    }

    @Bean
    public ChatMemory chatMemory(ChatMemoryRepository repository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(MAX_CONVERSATION_MESSAGES)
                .build();
    }

    @Bean
    public ReloadableSkillsTool reloadableSkillsTool(
            @Value("${herald.agent.skills-directory:skills}") String skillsDirectory) {
        return new ReloadableSkillsTool(skillsDirectory);
    }

    @Bean
    public ModelSwitcher modelSwitcher(
            @Qualifier("anthropicChatModel") ChatModel chatModel,
            HeraldConfig config,
            @Value("${herald.agent.prompt-dump:false}") boolean promptDump,
            ChatMemory chatMemory,
            MemoryTools memoryTools,
            HeraldShellDecorator shellDecorator,
            FileSystemTools fsTools,
            ApplicationEventPublisher eventPublisher,
            ObjectProvider<TelegramQuestionHandler> questionHandlerProvider,
            TelegramSendTool telegramSendTool,
            GwsTools gwsTools,
            WebTools webTools,
            CronTools cronTools,
            JdbcTemplate jdbcTemplate,
            @Value("classpath:prompts/MAIN_AGENT_SYSTEM_PROMPT.md") Resource promptResource,
            @Value("${herald.agent.agents-directory:.claude/agents}") String agentsDirectory,
            ReloadableSkillsTool reloadableSkillsTool,
            @Value("${spring.ai.anthropic.chat.options.model:claude-sonnet-4-5}") String defaultModel,
            @Value("${herald.agent.model.haiku:claude-haiku-4-5}") String haikuModel,
            @Value("${herald.agent.model.sonnet:claude-sonnet-4-5}") String sonnetModel,
            @Value("${herald.agent.model.opus:claude-opus-4-5}") String opusModel,
            @Value("${herald.agent.model.openai:gpt-4o}") String openaiModel,
            @Value("${herald.agent.model.ollama:llama3.2}") String ollamaModel,
            @Value("${herald.agent.model.gemini:gemini-2.5-flash}") String geminiModel,
            @Qualifier("openaiChatModel") Optional<ChatModel> openaiChatModel,
            @Qualifier("ollamaChatModel") Optional<ChatModel> ollamaChatModel,
            @Qualifier("geminiChatModel") Optional<ChatModel> geminiChatModel) {

        String promptTemplate = loadPromptTemplate(promptResource);
        String systemPrompt = resolvePrompt(promptTemplate, config, defaultModel);

        // Set up CONTEXT.md advisor — reads standing brief from disk each turn
        Path contextFilePath = resolveTildePath(config.contextFile());
        ContextMdAdvisor contextMdAdvisor = new ContextMdAdvisor(contextFilePath);
        contextMdAdvisor.ensureTemplateExists(loadContextTemplate());

        // Set up context compaction advisor — backstop against context window overflow
        ContextCompactionAdvisor compactionAdvisor =
                new ContextCompactionAdvisor(chatMemory, memoryTools, config.maxContextTokens());

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
        geminiChatModel.ifPresent(model ->
                subagentTypeBuilder.chatClientBuilder("gemini", chatClientBuilderForModel(model, geminiModel)));

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

        // Build upstream AskUserQuestionTool with Telegram-backed handler (or no-op fallback)
        TelegramQuestionHandler telegramHandler = questionHandlerProvider.getIfAvailable();
        AskUserQuestionTool.QuestionHandler questionHandler = telegramHandler != null
                ? telegramHandler
                : questions -> {
                    log.warn("AskUserQuestion called but Telegram is not configured — returning empty");
                    return Map.of();
                };
        AskUserQuestionTool askTool = AskUserQuestionTool.builder()
                .questionHandler(questionHandler)
                .answersValidation(telegramHandler != null)
                .build();

        // Build upstream TodoWriteTool with event handler bridging to Telegram via TodoProgressEvent
        org.springaicommunity.agent.tools.TodoWriteTool todoTool = org.springaicommunity.agent.tools.TodoWriteTool.builder()
                .todoEventHandler(todos -> {
                    StringBuilder sb = new StringBuilder();
                    for (var item : todos.todos()) {
                        String symbol = switch (item.status()) {
                            case pending -> "\u2B1A";
                            case in_progress -> "\u25B6";
                            case completed -> "\u2713";
                        };
                        sb.append(symbol).append(" ").append(item.content()).append("\n");
                    }
                    eventPublisher.publishEvent(new TodoProgressEvent(todos, sb.toString()));
                })
                .build();

        // Factory that creates a ChatClient.Builder with all shared config for any ChatModel
        Function<ChatModel, ChatClient.Builder> clientBuilderFactory = cm ->
                ChatClient.builder(cm)
                        .defaultSystem(systemPrompt)
                        .defaultTools(memoryTools, shellDecorator, fsTools, todoTool, askTool, telegramSendTool, gwsTools, webTools, cronTools)
                        .defaultToolCallbacks(taskTool, taskOutputTool, reloadableSkillsTool)
                        .defaultAdvisors(
                                new DateTimePromptAdvisor(DEFAULT_TIMEZONE, DATETIME_FORMAT),
                                contextMdAdvisor,
                                new MemoryBlockAdvisor(memoryTools),
                                compactionAdvisor,
                                new OneShotMemoryAdvisor(chatMemory),
                                new PromptDumpAdvisor(promptDump),
                                ToolCallAdvisor.builder().build()
                        );

        // Register available provider ChatModels and their default model names
        Map<String, ChatModel> availableModels = new LinkedHashMap<>();
        availableModels.put("anthropic", chatModel);
        openaiChatModel.ifPresent(model -> availableModels.put("openai", model));
        ollamaChatModel.ifPresent(model -> availableModels.put("ollama", model));
        geminiChatModel.ifPresent(model -> availableModels.put("gemini", model));

        Map<String, String> providerDefaultModels = new LinkedHashMap<>();
        providerDefaultModels.put("anthropic", defaultModel);
        if (openaiChatModel.isPresent()) providerDefaultModels.put("openai", openaiModel);
        if (ollamaChatModel.isPresent()) providerDefaultModels.put("ollama", ollamaModel);
        if (geminiChatModel.isPresent()) providerDefaultModels.put("gemini", geminiModel);

        // Resolve the default provider from env var (falls back to anthropic)
        String requestedProvider = config.defaultProvider();
        String initialProvider;
        String initialModel;
        if (availableModels.containsKey(requestedProvider)) {
            initialProvider = requestedProvider;
            initialModel = providerDefaultModels.getOrDefault(requestedProvider, defaultModel);
        } else {
            log.warn("Requested default provider '{}' is not available (missing API key?), falling back to anthropic",
                    requestedProvider);
            initialProvider = "anthropic";
            initialModel = defaultModel;
        }

        // Build the initial client from the resolved provider
        ChatModel initialChatModel = availableModels.get(initialProvider);
        ChatClient initialClient = clientBuilderFactory.apply(initialChatModel)
                .defaultOptions(chatOptionsForModel(initialChatModel, initialModel))
                .build();

        var switcher = new ModelSwitcher(availableModels, providerDefaultModels, jdbcTemplate,
                clientBuilderFactory, initialClient, initialProvider, initialModel);
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
    String resolvePrompt(String template, HeraldConfig config, String modelId) {
        return template
                .replace("{persona}", config.persona())
                .replace("{model_id}", modelId)
                .replace("{system_prompt_extra}", config.systemPromptExtra());
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
