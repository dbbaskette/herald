package com.herald.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "herald")
public record HeraldConfig(Memory memory, Telegram telegram, Agent agent, Providers providers) {

    public record Memory(String dbPath) {
    }

    public record Telegram(String botToken, String allowedChatId) {
    }

    public record Agent(String persona, String systemPromptExtra, String contextFile) {
    }

    public record Providers(ProviderConfig anthropic, OpenAiProviderConfig openai,
                            OpenAiProviderConfig ollama) {
    }

    public record ProviderConfig(String apiKey) {
    }

    public record OpenAiProviderConfig(String apiKey, String baseUrl) {
    }

    public String persona() {
        if (agent != null && agent.persona() != null && !agent.persona().isBlank()) {
            return agent.persona();
        }
        return "Herald — Dan's personal AI agent. Autonomous, capable, dry wit. "
                + "Occasionally reference your namesake as a herald who delivers important messages.";
    }

    public String systemPromptExtra() {
        if (agent != null && agent.systemPromptExtra() != null && !agent.systemPromptExtra().isBlank()) {
            return agent.systemPromptExtra();
        }
        return "";
    }

    public String contextFile() {
        if (agent != null && agent.contextFile() != null && !agent.contextFile().isBlank()) {
            return agent.contextFile();
        }
        return "~/.herald/CONTEXT.md";
    }

    public String dbPath() {
        if (memory != null && memory.dbPath() != null) {
            return memory.dbPath();
        }
        return "~/.herald/herald.db";
    }
}
