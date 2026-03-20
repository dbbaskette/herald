package com.herald.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "herald")
public record HeraldConfig(Memory memory, Telegram telegram, Agent agent, Providers providers, Cron cron,
                           Weather weather, Obsidian obsidian) {

    public record Memory(String dbPath) {
    }

    public record Telegram(String botToken, String allowedChatId) {
    }

    public record Agent(String persona, String systemPromptExtra, String contextFile,
                        Integer maxContextTokens, String defaultProvider) {
    }

    public record Providers(ProviderConfig anthropic, OpenAiProviderConfig openai,
                            OpenAiProviderConfig ollama, OpenAiProviderConfig gemini,
                            OpenAiProviderConfig lmstudio) {
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

    public int maxContextTokens() {
        if (agent != null && agent.maxContextTokens() != null && agent.maxContextTokens() > 0) {
            return agent.maxContextTokens();
        }
        return 200_000; // Claude default context window
    }

    public String defaultProvider() {
        if (agent != null && agent.defaultProvider() != null && !agent.defaultProvider().isBlank()) {
            return agent.defaultProvider().toLowerCase();
        }
        return "anthropic";
    }

    public record Cron(String timezone) {
    }

    public record Weather(String location) {
    }

    public record Obsidian(String vaultPath) {
    }

    /**
     * Returns the Obsidian vault path if configured, or empty string.
     */
    public String obsidianVaultPath() {
        if (obsidian != null && obsidian.vaultPath() != null && !obsidian.vaultPath().isBlank()) {
            return obsidian.vaultPath();
        }
        return "";
    }

    public String weatherLocation() {
        if (weather != null && weather.location() != null && !weather.location().isBlank()) {
            return weather.location();
        }
        return "";
    }

    public String cronTimezone() {
        if (cron != null && cron.timezone() != null && !cron.timezone().isBlank()) {
            return cron.timezone();
        }
        return "America/New_York";
    }

    public String dbPath() {
        if (memory != null && memory.dbPath() != null) {
            return memory.dbPath();
        }
        return "~/.herald/herald.db";
    }
}
