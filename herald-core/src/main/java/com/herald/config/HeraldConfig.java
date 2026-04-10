package com.herald.config;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "herald")
public record HeraldConfig(Memory memory, Telegram telegram, Agent agent, Providers providers, Cron cron,
                           Weather weather, Obsidian obsidian, Vault vault, Archival archival,
                           LongTermMemory longTermMemory, A2a a2a) {

    /**
     * Backwards-compatible constructor for callers that predate the A2A addition.
     * Delegates to the canonical constructor with a null a2a value so existing
     * test fixtures and wiring code keep working unchanged.
     */
    public HeraldConfig(Memory memory, Telegram telegram, Agent agent, Providers providers, Cron cron,
                        Weather weather, Obsidian obsidian, Vault vault, Archival archival,
                        LongTermMemory longTermMemory) {
        this(memory, telegram, agent, providers, cron, weather, obsidian, vault, archival, longTermMemory, null);
    }

    public record A2a(List<A2aAgent> agents) {
    }

    public record A2aAgent(String name, String url, Map<String, String> metadata) {
    }

    public record Memory(String dbPath) {
    }

    public record LongTermMemory(String memoriesDir) {
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

    public record Vault(
        String vectorStorePath,
        String indexHashesPath,
        Boolean autoIndexOnStartup,
        Boolean fileWatcherEnabled,
        VaultSearch search,
        VaultChunking chunking
    ) {
        public record VaultSearch(Integer autoTopK, Integer toolDefaultTopK, Double minSimilarity) {}
        public record VaultChunking(Integer smallFileThreshold, Integer maxChunkSize) {}
    }

    public record Archival(
        Integer messageThreshold,
        Integer idleTimeoutMinutes,
        Integer minMessagesForIdle,
        Integer keepRecentMessages
    ) {}

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

    public String vaultVectorStorePath() {
        return vault != null && vault.vectorStorePath() != null
            ? vault.vectorStorePath() : "~/.herald/vector-store.json";
    }

    public String vaultIndexHashesPath() {
        return vault != null && vault.indexHashesPath() != null
            ? vault.indexHashesPath() : "~/.herald/vault-index-hashes.json";
    }

    public boolean vaultAutoIndexOnStartup() {
        return vault == null || vault.autoIndexOnStartup() == null || vault.autoIndexOnStartup();
    }

    public boolean vaultFileWatcherEnabled() {
        return vault == null || vault.fileWatcherEnabled() == null || vault.fileWatcherEnabled();
    }

    public int vaultAutoTopK() {
        return vault != null && vault.search() != null && vault.search().autoTopK() != null
            ? vault.search().autoTopK() : 2;
    }

    public int vaultToolDefaultTopK() {
        return vault != null && vault.search() != null && vault.search().toolDefaultTopK() != null
            ? vault.search().toolDefaultTopK() : 5;
    }

    public double vaultMinSimilarity() {
        return vault != null && vault.search() != null && vault.search().minSimilarity() != null
            ? vault.search().minSimilarity() : 0.7;
    }

    public int vaultSmallFileThreshold() {
        return vault != null && vault.chunking() != null && vault.chunking().smallFileThreshold() != null
            ? vault.chunking().smallFileThreshold() : 500;
    }

    public int vaultMaxChunkSize() {
        return vault != null && vault.chunking() != null && vault.chunking().maxChunkSize() != null
            ? vault.chunking().maxChunkSize() : 1000;
    }

    public int archivalMessageThreshold() {
        return archival != null && archival.messageThreshold() != null
            ? archival.messageThreshold() : 5;
    }

    public int archivalIdleTimeoutMinutes() {
        return archival != null && archival.idleTimeoutMinutes() != null
            ? archival.idleTimeoutMinutes() : 30;
    }

    public int archivalMinMessagesForIdle() {
        return archival != null && archival.minMessagesForIdle() != null
            ? archival.minMessagesForIdle() : 2;
    }

    public int archivalKeepRecentMessages() {
        return archival != null && archival.keepRecentMessages() != null
            ? archival.keepRecentMessages() : 5;
    }

    public String memoriesDir() {
        if (longTermMemory != null && longTermMemory.memoriesDir() != null
                && !longTermMemory.memoriesDir().isBlank()) {
            return longTermMemory.memoriesDir();
        }
        return "~/.herald/memories";
    }

    public String dbPath() {
        if (memory != null && memory.dbPath() != null) {
            return memory.dbPath();
        }
        return "~/.herald/herald.db";
    }

    /**
     * Returns the configured A2A agents, or an empty list if none are configured.
     */
    public java.util.List<A2aAgent> a2aAgents() {
        if (a2a != null && a2a.agents() != null) {
            return a2a.agents();
        }
        return java.util.List.of();
    }
}
