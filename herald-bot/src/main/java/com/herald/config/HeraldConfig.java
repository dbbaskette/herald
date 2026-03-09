package com.herald.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "herald")
public record HeraldConfig(Memory memory, Telegram telegram, Agent agent) {

    public record Memory(String dbPath) {
    }

    public record Telegram(String botToken, String allowedChatId) {
    }

    public record Agent(String persona, String systemPromptExtra) {
    }

    public String persona() {
        if (agent != null && agent.persona() != null) {
            return agent.persona();
        }
        return "Herald — Dan's personal AI agent. Autonomous, capable, dry wit. "
                + "Occasionally reference your namesake as a herald who delivers important messages.";
    }

    public String systemPromptExtra() {
        if (agent != null && agent.systemPromptExtra() != null) {
            return agent.systemPromptExtra();
        }
        return "";
    }

    public String dbPath() {
        if (memory != null && memory.dbPath() != null) {
            return memory.dbPath();
        }
        return "~/.herald/herald.db";
    }
}
