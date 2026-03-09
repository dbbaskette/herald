package com.herald.agent;

import com.herald.config.HeraldConfig;
import com.herald.memory.MemoryTools;
import com.herald.tools.AskUserQuestionTool;
import com.herald.tools.FileSystemTools;
import com.herald.tools.HeraldShellDecorator;
import com.herald.tools.TodoWriteTool;
import org.springaicommunity.agent.common.task.subagent.SubagentReference;
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
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Configuration
class HeraldAgentConfig {

    private static final ZoneId DEFAULT_TIMEZONE = ZoneId.of("America/New_York");
    private static final DateTimeFormatter DATETIME_FORMAT =
            DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a z");
    private static final int MAX_CONVERSATION_MESSAGES = 100;

    @Bean
    ChatMemory chatMemory(JdbcChatMemoryRepository repository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .maxMessages(MAX_CONVERSATION_MESSAGES)
                .build();
    }

    @Bean
    ChatClient mainClient(
            ChatClient.Builder builder,
            ChatModel chatModel,
            HeraldConfig config,
            ChatMemory chatMemory,
            MemoryTools memoryTools,
            HeraldShellDecorator shellDecorator,
            FileSystemTools fsTools,
            TodoWriteTool todoTool,
            AskUserQuestionTool askTool,
            @Value("classpath:prompts/MAIN_AGENT_SYSTEM_PROMPT.md") Resource promptResource,
            @Value("${herald.agent.agents-directory:.claude/agents}") String agentsDirectory,
            @Value("${herald.agent.model.haiku:claude-haiku-4-5}") String haikuModel,
            @Value("${herald.agent.model.sonnet:claude-sonnet-4-5}") String sonnetModel,
            @Value("${herald.agent.model.opus:claude-opus-4-5}") String opusModel) {

        String promptTemplate = loadPromptTemplate(promptResource);
        String systemPrompt = resolvePrompt(promptTemplate, config);

        // Configure multi-model routing for subagent delegation
        var taskRepository = new DefaultTaskRepository();

        var claudeSubagentType = ClaudeSubagentType.builder()
                .chatClientBuilder("default", ChatClient.builder(chatModel))
                .chatClientBuilder("haiku", chatClientBuilderForModel(chatModel, haikuModel))
                .chatClientBuilder("sonnet", chatClientBuilderForModel(chatModel, sonnetModel))
                .chatClientBuilder("opus", chatClientBuilderForModel(chatModel, opusModel))
                .build();

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

        return builder
                .defaultSystem(systemPrompt)
                .defaultTools(memoryTools, shellDecorator, fsTools, todoTool, askTool)
                .defaultToolCallbacks(taskTool, taskOutputTool)
                .defaultAdvisors(
                        new MemoryBlockAdvisor(memoryTools),
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        ToolCallAdvisor.builder()
                                .conversationHistoryEnabled(false)
                                .build()
                )
                .build();
    }

    private ChatClient.Builder chatClientBuilderForModel(ChatModel chatModel, String modelId) {
        return ChatClient.builder(chatModel)
                .defaultOptions(AnthropicChatOptions.builder().model(modelId).build());
    }

    String resolvePrompt(String template, HeraldConfig config) {
        ZonedDateTime now = ZonedDateTime.now(DEFAULT_TIMEZONE);

        // Substitution is chained: values injected early could contain later placeholders.
        // This is acceptable because persona and systemPromptExtra come from controlled config,
        // but if user-supplied values are ever allowed, switch to a proper template engine.
        return template
                .replace("{persona}", config.persona())
                .replace("{current_datetime}", now.format(DATETIME_FORMAT))
                .replace("{timezone}", DEFAULT_TIMEZONE.getId())
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
}
