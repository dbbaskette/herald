package com.herald.agent;

import com.herald.config.HeraldConfig;
import com.herald.memory.MemoryTools;
import com.herald.tools.HeraldShellDecorator;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.ToolCallAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.memory.repository.jdbc.JdbcChatMemoryRepository;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.Resource;
import org.springframework.beans.factory.annotation.Value;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

@Configuration
class HeraldAgentConfig {

    private static final ZoneId DEFAULT_TIMEZONE = ZoneId.of("America/New_York");
    private static final DateTimeFormatter DATETIME_FORMAT =
            DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a z");

    @Bean
    ChatMemory chatMemory(JdbcChatMemoryRepository repository) {
        return MessageWindowChatMemory.builder()
                .chatMemoryRepository(repository)
                .build();
    }

    @Bean
    ChatClient chatClient(
            ChatClient.Builder builder,
            HeraldConfig config,
            ChatMemory chatMemory,
            MemoryTools memoryTools,
            HeraldShellDecorator shellDecorator,
            @Value("classpath:prompts/MAIN_AGENT_SYSTEM_PROMPT.md") Resource promptResource) {

        String promptTemplate = loadPromptTemplate(promptResource);
        String systemPrompt = resolvePrompt(promptTemplate, config);

        return builder
                .defaultSystem(systemPrompt)
                .defaultTools(memoryTools, shellDecorator)
                .defaultAdvisors(
                        new MemoryBlockAdvisor(memoryTools),
                        MessageChatMemoryAdvisor.builder(chatMemory).build(),
                        ToolCallAdvisor.builder()
                                .conversationHistoryEnabled(false)
                                .build()
                )
                .build();
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

    private String loadPromptTemplate(Resource resource) {
        try {
            return resource.getContentAsString(StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load system prompt template", e);
        }
    }
}
