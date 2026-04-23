package com.herald.agent;

import com.herald.config.HeraldConfig;
import com.herald.cron.CronTools;
import com.herald.telegram.TelegramQuestionHandler;
import org.springaicommunity.agent.tools.AskUserQuestionTool;
import org.springaicommunity.agent.utils.CommandLineQuestionHandler;
import com.herald.tools.FileSystemTools;
import com.herald.tools.GwsTools;
import com.herald.tools.RemindersAvailabilityChecker;
import com.herald.tools.RemindersTools;
import com.herald.tools.HeraldShellDecorator;
import com.herald.tools.TelegramSendTool;
import com.herald.tools.WebTools;
import org.springaicommunity.agent.common.task.subagent.SubagentReference;
import org.springaicommunity.agent.common.task.subagent.SubagentType;
import org.springaicommunity.agent.subagent.a2a.A2ASubagentDefinition;
import org.springaicommunity.agent.subagent.a2a.A2ASubagentExecutor;
import org.springaicommunity.agent.subagent.a2a.A2ASubagentResolver;
import org.springaicommunity.agent.tools.task.TaskOutputTool;
import org.springaicommunity.agent.tools.task.TaskTool;
import org.springaicommunity.agent.tools.task.claude.ClaudeSubagentType;
import com.herald.agent.subagent.HeraldSubagentReferences;
import org.springaicommunity.agent.tools.task.repository.DefaultTaskRepository;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.chat.client.ChatClient;
// MessageChatMemoryAdvisor replaced by OneShotMemoryAdvisor to prevent
// re-loading/re-saving memory on each ToolSearchToolCallAdvisor iteration
import org.springaicommunity.tool.search.ToolSearchToolCallAdvisor;
import org.springaicommunity.tool.searcher.LuceneToolSearcher;
import org.springframework.ai.chat.client.advisor.api.Advisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.ChatMemoryRepository;
import org.springframework.ai.chat.memory.InMemoryChatMemoryRepository;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.ResourcePatternResolver;
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
            Optional<CronTools> cronTools,
            Optional<TelegramSendTool> telegramSendTool,
            Optional<GwsTools> gwsTools,
            Optional<RemindersTools> remindersTools,
            RemindersAvailabilityChecker remindersAvailabilityChecker) {
        List<String> names = new ArrayList<>(List.of(
                "shell", "filesystem", "todoWrite", "askUserQuestion",
                "task", "taskOutput", "skills", "web", "toolSearchTool",
                "validateSkill",
                "MemoryView", "MemoryCreate", "MemoryStrReplace",
                "MemoryInsert", "MemoryDelete", "MemoryRename"));
        cronTools.ifPresent(t -> names.add("cron"));
        telegramSendTool.ifPresent(t -> names.add("telegram_send"));
        gwsTools.ifPresent(t -> names.add("gws"));
        if (remindersTools.isPresent() && remindersAvailabilityChecker.isAvailable()) {
            names.add("reminders");
        }
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
            @Value("${herald.agent.skills-directory:skills}") String skillsDirectory,
            ResourcePatternResolver resourceResolver,
            ApprovalGate approvalGate,
            HeraldConfig config) throws IOException {
        Resource[] skillMdFiles = resourceResolver.getResources("classpath:skills/*/SKILL.md");
        List<Resource> classpathSkillDirs = new ArrayList<>();
        for (Resource r : skillMdFiles) {
            try {
                classpathSkillDirs.add(new FileSystemResource(r.getFile().getParentFile()));
            } catch (IOException e) {
                log.warn("Could not resolve classpath skill resource to filesystem: {}", r, e);
            }
        }
        return new ReloadableSkillsTool(skillsDirectory, classpathSkillDirs,
                approvalGate, config.skillsRequiringApproval());
    }

    @Bean
    public ModelSwitcher modelSwitcher(
            @Qualifier("anthropicChatModel") ChatModel chatModel,
            HeraldConfig config,
            @Value("${herald.agent.prompt-dump:false}") boolean promptDump,
            Optional<ChatMemory> chatMemoryOpt,
            HeraldShellDecorator shellDecorator,
            FileSystemTools fsTools,
            Optional<MessageSender> messageSenderOpt,
            ObjectProvider<TelegramQuestionHandler> questionHandlerProvider,
            Optional<TelegramSendTool> telegramSendToolOpt,
            Optional<GwsTools> gwsToolsOpt,
            Optional<RemindersTools> remindersToolsOpt,
            RemindersAvailabilityChecker remindersAvailabilityChecker,
            WebTools webTools,
            Optional<CronTools> cronToolsOpt,
            Optional<JdbcTemplate> jdbcTemplateOpt,
            @Value("classpath:prompts/MAIN_AGENT_SYSTEM_PROMPT.md") Resource promptResource,
            @Value("${herald.agent.agents-directory:.claude/agents}") String agentsDirectory,
            ReloadableSkillsTool reloadableSkillsTool,
            ValidateSkillTool validateSkillTool,
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
            @Qualifier("lmstudioChatModel") Optional<ChatModel> lmstudioChatModel,
            @Qualifier("activeToolNames") List<String> activeToolNames) {

        // Set up long-term memory (HeraldAutoMemoryAdvisor owns the tools)
        Path memoriesDir = resolveTildePath(config.memoriesDir());
        Path memoryLogPath = memoriesDir.resolve("log.md");
        Path hotMdPath = memoriesDir.resolve("hot.md");
        try {
            Files.createDirectories(memoriesDir);
            Path memoryMdPath = memoriesDir.resolve("MEMORY.md");
            if (!Files.exists(memoryMdPath)) {
                Files.writeString(memoryMdPath, """
                        # Memory Index

                        _Catalog of long-term memory pages, grouped by type. \
                        Edit via the `Memory*` tools only._

                        ## User

                        ## Feedback

                        ## Projects

                        ## References

                        ## Concepts

                        ## Entities

                        ## Sources
                        """);
                log.info("Created starter MEMORY.md at {}", memoryMdPath);
            }
            if (!Files.exists(memoryLogPath)) {
                Files.writeString(memoryLogPath,
                        "# Memory Log\n\n"
                                + "_Append-only. One event per line. "
                                + "Format: `<timestamp> <EVENT> key=value ...`_\n\n");
                log.info("Created starter log.md at {}", memoryLogPath);
            }
            if (!Files.exists(hotMdPath)) {
                Files.writeString(hotMdPath,
                        "# Hot Context\n\n"
                                + "_Session-continuity cache. Refreshed automatically "
                                + "when the conversation is compacted._\n");
                log.info("Created starter hot.md at {}", hotMdPath);
            }
        } catch (IOException e) {
            log.warn("Failed to bootstrap memories directory at {}: {}", memoriesDir, e.getMessage());
        }

        // Resolve system prompt (memory instructions are injected by AutoMemoryToolsAdvisor)
        String promptTemplate = loadPromptTemplate(promptResource);
        String systemPrompt = resolvePrompt(promptTemplate, config, defaultModel,
                reloadableSkillsTool.getSkillsDirectory());

        // Set up CONTEXT.md advisor — reads standing brief from disk each turn
        Path contextFilePath = resolveTildePath(config.contextFile());
        ContextMdAdvisor contextMdAdvisor = new ContextMdAdvisor(contextFilePath);
        contextMdAdvisor.ensureTemplateExists(loadContextTemplate());
        contextMdAdvisor.updateObsidianConfig(config.obsidianVaultPath());
        boolean obsidianVaultModeEnabled = config.resolveObsidianVaultMode(memoriesDir.toString());
        contextMdAdvisor.updateMemoryStorageMode(obsidianVaultModeEnabled);
        log.info("Memory storage mode: {} (preference={}, vaultPath={})",
                obsidianVaultModeEnabled ? "obsidian-vault (wikilinks)" : "plain-markdown",
                config.obsidianVaultModePreference(),
                config.obsidianVaultPath().isEmpty() ? "<unset>" : config.obsidianVaultPath());

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
        lmstudioChatModel.ifPresent(model ->
                subagentTypeBuilder.chatClientBuilder("lmstudio", chatClientBuilderForModel(model, lmstudioModel)));

        var subagentType = subagentTypeBuilder
                .skillsDirectories(reloadableSkillsTool.getSkillsDirectory())
                .build();

        var subagentRefs = loadSubagentReferences(agentsDirectory);
        var a2aAgents = config.a2aAgents();

        var taskToolBuilder = TaskTool.builder().taskRepository(taskRepository);

        if (a2aAgents.isEmpty()) {
            taskToolBuilder.subagentTypes(subagentType);
            if (!subagentRefs.isEmpty()) {
                taskToolBuilder.subagentReferences(subagentRefs);
            }
        } else {
            var a2aType = new SubagentType(new A2ASubagentResolver(), new A2ASubagentExecutor());
            taskToolBuilder.subagentTypes(subagentType, a2aType);

            var combinedRefs = new ArrayList<>(subagentRefs);
            for (var agent : a2aAgents) {
                combinedRefs.add(new SubagentReference(agent.url(), A2ASubagentDefinition.KIND, agent.metadata()));
            }
            taskToolBuilder.subagentReferences(combinedRefs);
            log.info("Registered {} A2A agent(s) alongside {} local subagent(s)",
                    a2aAgents.size(), subagentRefs.size());
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

        // Build upstream TodoWriteTool with a handler that dispatches formatted
        // progress directly to MessageSender (Telegram) or stdout as a fallback.
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
                    String summary = sb.toString();
                    messageSenderOpt.ifPresentOrElse(
                            s -> s.sendMessage(summary),
                            () -> System.out.print(summary));
                })
                .build();

        // Build advisor chain and tool list dynamically based on available beans
        var advisorChain = buildAdvisorChain(chatMemoryOpt,
                contextMdAdvisor, memoriesDir, memoryLogPath, hotMdPath,
                chatModel, config, promptDump);

        var toolList = buildToolList(shellDecorator, fsTools,
                todoTool, askTool, telegramSendToolOpt, gwsToolsOpt,
                remindersToolsOpt, remindersAvailabilityChecker,
                webTools, cronToolsOpt, validateSkillTool);

        // Factory that creates a ChatClient.Builder with all shared config for any ChatModel
        Function<ChatModel, ChatClient.Builder> clientBuilderFactory = cm ->
                ChatClient.builder(cm)
                        .defaultSystem(systemPrompt)
                        .defaultTools(toolList.toArray())
                        .defaultToolCallbacks(buildToolCallbacks(taskTool, taskOutputTool,
                                reloadableSkillsTool, activeToolNames))
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

        // Build the initial client from the resolved provider (apply Anthropic skills for main agent)
        ChatModel initialChatModel = availableModels.get(initialProvider);
        ChatClient initialClient = clientBuilderFactory.apply(initialChatModel)
                .defaultOptions(ModelSwitcher.chatOptionsForModel(initialChatModel, initialModel, config.anthropicSkills()))
                .build();

        var switcher = new ModelSwitcher(availableModels, providerDefaultModels, jdbcTemplateOpt.orElse(null),
                clientBuilderFactory, initialClient, initialProvider, initialModel, config.anthropicSkills());
        switcher.loadPersistedOverride();
        return switcher;
    }


    List<Advisor> buildAdvisorChain(
            Optional<ChatMemory> chatMemoryOpt,
            ContextMdAdvisor contextMdAdvisor,
            Path memoriesDir,
            Path memoryLogPath,
            Path hotMdPath,
            ChatModel chatModel,
            HeraldConfig config,
            boolean promptDump) {

        List<Advisor> advisors = new ArrayList<>();

        // Always active
        advisors.add(new DateTimePromptAdvisor(DEFAULT_TIMEZONE, DATETIME_FORMAT));
        advisors.add(contextMdAdvisor);
        advisors.add(new HotMdAdvisor(hotMdPath));

        // Long-term memory — Herald-owned advisor mirrors upstream AutoMemoryToolsAdvisor
        // but wraps each memory tool callback so mutations land in log.md.
        advisors.add(HeraldAutoMemoryAdvisor.builder()
                .memoriesRootDirectory(memoriesDir)
                .logFile(memoryLogPath)
                .memorySystemPrompt(new ClassPathResource("prompts/AUTO_MEMORY_SYSTEM_PROMPT.md"))
                .order(Ordered.HIGHEST_PRECEDENCE + 100)
                .memoryConsolidationTrigger((req, instant) -> false)
                .build());

        if (chatMemoryOpt.isPresent()) {
            ChatMemory chatMemory = chatMemoryOpt.get();
            advisors.add(new ContextCompactionAdvisor(
                    chatMemory, chatModel, config.maxContextTokens(),
                    memoryLogPath, hotMdPath));
            advisors.add(new OneShotMemoryAdvisor(chatMemory, MAX_CONVERSATION_MESSAGES));
        }

        // Conditional on property (not persistence)
        advisors.add(new PromptDumpAdvisor(promptDump));

        // ToolSearchToolCallAdvisor replaces ToolCallAdvisor — indexes all registered
        // tools via LuceneToolSearcher and exposes a toolSearchTool for on-demand
        // discovery. Explicit order just before ChatModelCallAdvisor (LOWEST_PRECEDENCE).
        advisors.add(ToolSearchToolCallAdvisor.builder()
                .toolSearcher(new LuceneToolSearcher())
                .advisorOrder(Ordered.LOWEST_PRECEDENCE - 1)
                .build());

        return advisors;
    }

    List<Object> buildToolList(
            HeraldShellDecorator shellDecorator,
            FileSystemTools fsTools,
            org.springaicommunity.agent.tools.TodoWriteTool todoTool,
            AskUserQuestionTool askTool,
            Optional<TelegramSendTool> telegramSendToolOpt,
            Optional<GwsTools> gwsToolsOpt,
            Optional<RemindersTools> remindersToolsOpt,
            RemindersAvailabilityChecker remindersAvailabilityChecker,
            WebTools webTools,
            Optional<CronTools> cronToolsOpt,
            ValidateSkillTool validateSkillTool) {

        List<Object> tools = new ArrayList<>();
        tools.add(shellDecorator);
        tools.add(fsTools);
        tools.add(todoTool);
        tools.add(askTool);
        tools.add(webTools);
        tools.add(validateSkillTool);

        telegramSendToolOpt.ifPresent(tools::add);
        gwsToolsOpt.ifPresent(tools::add);
        if (remindersAvailabilityChecker.isAvailable()) {
            remindersToolsOpt.ifPresent(tools::add);
        }
        cronToolsOpt.ifPresent(tools::add);

        return tools;
    }

    private ChatClient.Builder chatClientBuilderForModel(ChatModel chatModel, String modelId) {
        return ChatClient.builder(chatModel)
                .defaultOptions(chatOptionsForModel(chatModel, modelId));
    }

    // Used for subagent ChatClient builders — skills not needed for subagents
    static org.springframework.ai.chat.prompt.ChatOptions.Builder<?> chatOptionsForModel(ChatModel chatModel, String modelId) {
        return ModelSwitcher.chatOptionsForModel(chatModel, modelId, List.of());
    }

    /**
     * Resolves static placeholders in the prompt template. Dynamic placeholders like
     * {@code {current_datetime}} and {@code {timezone}} are intentionally left unresolved
     * here and handled per-turn by {@link DateTimePromptAdvisor}.
     */
    String resolvePrompt(String template, HeraldConfig config, String modelId, String skillsDirectory) {
        return template
                .replace("{persona}", config.persona())
                .replace("{model_id}", modelId)
                .replace("{system_prompt_extra}", config.systemPromptExtra())
                .replace("{skills_directory}", skillsDirectory)
                .replace("{task_management_guidance}", TaskManagementGuidance.load());
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

    /**
     * Builds the array of ToolCallbacks: core tools + aliases for hallucinated names.
     */
    private static ToolCallback[] buildToolCallbacks(
            ToolCallback taskTool,
            ToolCallback taskOutputTool,
            ReloadableSkillsTool skillsTool,
            List<String> registeredToolNames) {
        List<ToolCallback> callbacks = new ArrayList<>();
        callbacks.add(taskTool);
        callbacks.add(taskOutputTool);
        callbacks.add(skillsTool);
        callbacks.addAll(buildToolAliases(skillsTool, registeredToolNames));
        return callbacks.toArray(new ToolCallback[0]);
    }

    /**
     * Creates alias ToolCallbacks for tool names that local models commonly hallucinate.
     * Each alias delegates to the correct real tool, rewriting the input if needed.
     */
    private static List<ToolCallback> buildToolAliases(
            ReloadableSkillsTool skillsTool,
            List<String> registeredToolNames) {

        List<ToolCallback> aliases = new ArrayList<>();

        // Static aliases: common hallucinated name → real tool + input rewrite
        // "skill" → skills (singular vs plural)
        aliases.add(toolAlias("skill", skillsTool));

        // Models often call skill names directly as tool names (e.g., "google-calendar",
        // "gmail", "obsidian", "weather") instead of calling "skills" with the skill name.
        // Generate an alias for each loaded skill that routes through the skills tool.
        if (skillsTool.hasSkills()) {
            for (var skill : skillsTool.getSkills()) {
                String skillName = skill.name();
                // Don't create alias if it collides with a real tool name
                if (!registeredToolNames.contains(skillName)) {
                    aliases.add(skillRouterAlias(skillName, skillsTool));
                }
            }
        }

        return aliases;
    }

    /** Simple alias: different name, same behavior */
    private static ToolCallback toolAlias(String aliasName, ToolCallback delegate) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                ToolDefinition original = delegate.getToolDefinition();
                return ToolDefinition.builder()
                        .name(aliasName)
                        .description(original.description())
                        .inputSchema(original.inputSchema())
                        .build();
            }

            @Override
            public String call(String toolInput) {
                return delegate.call(toolInput);
            }
        };
    }

    /**
     * Creates an alias that routes a hallucinated skill-as-tool-name through the skills tool.
     * When the model calls "google-calendar" as a tool, this invokes skills("google-calendar").
     */
    private static ToolCallback skillRouterAlias(String skillName, ReloadableSkillsTool skillsTool) {
        return new ToolCallback() {
            @Override
            public ToolDefinition getToolDefinition() {
                return ToolDefinition.builder()
                        .name(skillName)
                        .description("Alias — loads the " + skillName + " skill")
                        .inputSchema("{\"type\":\"object\",\"properties\":{\"command\":{\"type\":\"string\"}},\"required\":[]}")
                        .build();
            }

            @Override
            public String call(String toolInput) {
                // Route through the skills tool with this skill name as the command
                String rewrittenInput = "{\"command\":\"" + skillName + "\"}";
                return skillsTool.call(rewrittenInput);
            }
        };
    }
}
