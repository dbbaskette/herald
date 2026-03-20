package com.herald.agent;

import com.herald.config.HeraldConfig;
import com.herald.cron.CronTools;
import com.herald.memory.MemoryTools;
import com.herald.telegram.TelegramQuestionHandler;
import org.springaicommunity.agent.tools.AskUserQuestionTool;
import org.springaicommunity.agent.utils.CommandLineQuestionHandler;
import com.herald.tools.FileSystemTools;
import com.herald.tools.GwsTools;
import com.herald.tools.HeraldShellDecorator;
import com.herald.tools.TelegramSendTool;
import com.herald.tools.TodoProgressEvent;
import com.herald.tools.WebTools;
import org.springaicommunity.agent.common.task.subagent.SubagentReference;
import org.springaicommunity.agent.tools.task.TaskOutputTool;
import org.springaicommunity.agent.tools.task.TaskTool;
import com.herald.agent.subagent.HeraldSubagentFactory;
import com.herald.agent.subagent.HeraldSubagentReferences;
import org.springaicommunity.agent.tools.task.repository.DefaultTaskRepository;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
// MessageChatMemoryAdvisor replaced by OneShotMemoryAdvisor to prevent
// re-loading/re-saving memory on each ToolCallAdvisor iteration
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
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
import java.util.ArrayList;
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
    private static final int MAX_CONVERSATION_MESSAGES = 20;

    @Bean
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(4);
        scheduler.setThreadNamePrefix("cron-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(30);
        return scheduler;
    }

    @Bean
    @Qualifier("activeToolNames")
    public List<String> activeToolNames(
            Optional<MemoryTools> memoryTools,
            Optional<CronTools> cronTools,
            Optional<TelegramSendTool> telegramSendTool,
            Optional<GwsTools> gwsTools) {
        List<String> names = new ArrayList<>(List.of(
                "shell", "filesystem", "todoWrite", "askUserQuestion",
                "task", "taskOutput", "skills", "web"));
        memoryTools.ifPresent(t -> names.add("memory"));
        cronTools.ifPresent(t -> names.add("cron"));
        telegramSendTool.ifPresent(t -> names.add("telegram_send"));
        gwsTools.ifPresent(t -> names.add("gws"));
        return List.copyOf(names);
    }

    @Bean
    @ConditionalOnMissingBean(ChatMemoryRepository.class)
    public ChatMemoryRepository inMemoryChatMemoryRepository() {
        return new InMemoryChatMemoryRepository();
    }

    @Bean
    @ConditionalOnBean(ChatMemoryRepository.class)
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
            Optional<ChatMemory> chatMemoryOpt,
            Optional<MemoryTools> memoryToolsOpt,
            HeraldShellDecorator shellDecorator,
            FileSystemTools fsTools,
            ApplicationEventPublisher eventPublisher,
            ObjectProvider<TelegramQuestionHandler> questionHandlerProvider,
            Optional<TelegramSendTool> telegramSendToolOpt,
            Optional<GwsTools> gwsToolsOpt,
            WebTools webTools,
            Optional<CronTools> cronToolsOpt,
            Optional<JdbcTemplate> jdbcTemplateOpt,
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
            @Value("${herald.agent.model.lmstudio:qwen/qwen3.5-35b-a3b}") String lmstudioModel,
            @Qualifier("openaiChatModel") Optional<ChatModel> openaiChatModel,
            @Qualifier("ollamaChatModel") Optional<ChatModel> ollamaChatModel,
            @Qualifier("geminiChatModel") Optional<ChatModel> geminiChatModel,
            @Qualifier("lmstudioChatModel") Optional<ChatModel> lmstudioChatModel) {

        String promptTemplate = loadPromptTemplate(promptResource);
        String systemPrompt = resolvePrompt(promptTemplate, config, defaultModel);

        // Set up CONTEXT.md advisor — reads standing brief from disk each turn
        Path contextFilePath = resolveTildePath(config.contextFile());
        ContextMdAdvisor contextMdAdvisor = new ContextMdAdvisor(contextFilePath);
        contextMdAdvisor.ensureTemplateExists(loadContextTemplate());

        // Configure multi-model routing for subagent delegation
        var taskRepository = new DefaultTaskRepository();

        var subagentTypeBuilder = HeraldSubagentFactory.builder()
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
        lmstudioChatModel.ifPresent(model ->
                subagentTypeBuilder.chatClientBuilder("lmstudio", chatClientBuilderForModel(model, lmstudioModel)));

        var subagentType = subagentTypeBuilder.build();

        var subagentRefs = loadSubagentReferences(agentsDirectory);

        var taskToolBuilder = TaskTool.builder()
                .subagentTypes(subagentType)
                .taskRepository(taskRepository);

        if (!subagentRefs.isEmpty()) {
            taskToolBuilder.subagentReferences(subagentRefs);
        }

        ToolCallback taskTool = taskToolBuilder.build();

        ToolCallback taskOutputTool = TaskOutputTool.builder()
                .taskRepository(taskRepository)
                .build();

        // Build upstream AskUserQuestionTool with Telegram-backed handler (or console fallback)
        TelegramQuestionHandler telegramHandler = questionHandlerProvider.getIfAvailable();
        AskUserQuestionTool.QuestionHandler questionHandler = telegramHandler != null
                ? telegramHandler
                : new CommandLineQuestionHandler();
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

        // Build advisor chain and tool list dynamically based on available beans
        var advisorChain = buildAdvisorChain(memoryToolsOpt, chatMemoryOpt,
                contextMdAdvisor, chatModel, config, promptDump);

        var toolList = buildToolList(memoryToolsOpt, shellDecorator, fsTools,
                todoTool, askTool, telegramSendToolOpt, gwsToolsOpt, webTools, cronToolsOpt);

        // Factory that creates a ChatClient.Builder with all shared config for any ChatModel
        Function<ChatModel, ChatClient.Builder> clientBuilderFactory = cm ->
                ChatClient.builder(cm)
                        .defaultSystem(systemPrompt)
                        .defaultTools(toolList.toArray())
                        .defaultToolCallbacks(taskTool, taskOutputTool, reloadableSkillsTool)
                        .defaultAdvisors(advisorChain);

        // Register available provider ChatModels and their default model names
        Map<String, ChatModel> availableModels = new LinkedHashMap<>();
        availableModels.put("anthropic", chatModel);
        openaiChatModel.ifPresent(model -> availableModels.put("openai", model));
        ollamaChatModel.ifPresent(model -> availableModels.put("ollama", model));
        geminiChatModel.ifPresent(model -> availableModels.put("gemini", model));
        lmstudioChatModel.ifPresent(model -> availableModels.put("lmstudio", model));

        Map<String, String> providerDefaultModels = new LinkedHashMap<>();
        providerDefaultModels.put("anthropic", defaultModel);
        if (openaiChatModel.isPresent()) providerDefaultModels.put("openai", openaiModel);
        if (ollamaChatModel.isPresent()) providerDefaultModels.put("ollama", ollamaModel);
        if (geminiChatModel.isPresent()) providerDefaultModels.put("gemini", geminiModel);
        if (lmstudioChatModel.isPresent()) providerDefaultModels.put("lmstudio", lmstudioModel);

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

        var switcher = new ModelSwitcher(availableModels, providerDefaultModels, jdbcTemplateOpt.orElse(null),
                clientBuilderFactory, initialClient, initialProvider, initialModel);
        switcher.loadPersistedOverride();
        return switcher;
    }


    List<Advisor> buildAdvisorChain(
            Optional<MemoryTools> memoryToolsOpt,
            Optional<ChatMemory> chatMemoryOpt,
            ContextMdAdvisor contextMdAdvisor,
            ChatModel chatModel,
            HeraldConfig config,
            boolean promptDump) {

        List<Advisor> advisors = new ArrayList<>();

        // Always active
        advisors.add(new DateTimePromptAdvisor(DEFAULT_TIMEZONE, DATETIME_FORMAT));
        advisors.add(contextMdAdvisor);

        // Persistence-dependent
        memoryToolsOpt.ifPresent(mt -> advisors.add(new MemoryBlockAdvisor(mt)));

        if (chatMemoryOpt.isPresent()) {
            ChatMemory chatMemory = chatMemoryOpt.get();
            if (memoryToolsOpt.isPresent()) {
                advisors.add(new ContextCompactionAdvisor(
                        chatMemory, memoryToolsOpt.get(), chatModel, config.maxContextTokens()));
            }
            advisors.add(new OneShotMemoryAdvisor(chatMemory, MAX_CONVERSATION_MESSAGES));
        }

        // Conditional on property (not persistence)
        advisors.add(new PromptDumpAdvisor(promptDump));

        // Explicit order just before ChatModelCallAdvisor (LOWEST_PRECEDENCE).
        // Default order is HIGHEST_PRECEDENCE+300 which wraps AROUND
        // OneShotMemoryAdvisor, causing memory to load/save on every
        // tool-call iteration and duplicating messages.
        advisors.add(ToolCallAdvisor.builder()
                .advisorOrder(Ordered.LOWEST_PRECEDENCE - 1)
                .build());

        return advisors;
    }

    List<Object> buildToolList(
            Optional<MemoryTools> memoryToolsOpt,
            HeraldShellDecorator shellDecorator,
            FileSystemTools fsTools,
            org.springaicommunity.agent.tools.TodoWriteTool todoTool,
            AskUserQuestionTool askTool,
            Optional<TelegramSendTool> telegramSendToolOpt,
            Optional<GwsTools> gwsToolsOpt,
            WebTools webTools,
            Optional<CronTools> cronToolsOpt) {

        List<Object> tools = new ArrayList<>();
        tools.add(shellDecorator);
        tools.add(fsTools);
        tools.add(todoTool);
        tools.add(askTool);
        tools.add(webTools);

        memoryToolsOpt.ifPresent(tools::add);
        telegramSendToolOpt.ifPresent(tools::add);
        gwsToolsOpt.ifPresent(tools::add);
        cronToolsOpt.ifPresent(tools::add);

        return tools;
    }

    private ChatClient.Builder chatClientBuilderForModel(ChatModel chatModel, String modelId) {
        return ChatClient.builder(chatModel)
                .defaultOptions(chatOptionsForModel(chatModel, modelId));
    }

    static org.springframework.ai.chat.prompt.ChatOptions chatOptionsForModel(ChatModel chatModel, String modelId) {
        return ModelSwitcher.chatOptionsForModel(chatModel, modelId);
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
        return HeraldSubagentReferences.fromDirectory(agentsDirectory);
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
