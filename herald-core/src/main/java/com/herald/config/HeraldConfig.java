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

    public record A2a(List<A2aAgent> agents, Server server) {
        /**
         * Backwards-compatible constructor for callers that predate the A2A
         * server-side config. Leaves {@code server} null (server defaults to
         * disabled).
         */
        public A2a(List<A2aAgent> agents) {
            this(agents, null);
        }

        /**
         * Server-side A2A configuration — when {@code enabled}, Herald exposes
         * a JSON-RPC {@code message/send} endpoint at {@code /} and an
         * AgentCard at {@code /.well-known/agent-card.json}.
         */
        public record Server(Boolean enabled, String baseUrl, String name, String description,
                             String version, Auth auth) {
        }

        public record Auth(String bearerToken) {
        }
    }

    public record A2aAgent(String name, String url, Map<String, String> metadata) {
        public A2aAgent {
            metadata = metadata != null ? metadata : Map.of();
        }
    }

    public record Memory(String dbPath) {
    }

    public record LongTermMemory(String memoriesDir) {
    }

    public record Telegram(String botToken, String allowedChatId) {
    }

    public record Agent(String persona, String systemPromptExtra, String contextFile,
                        Integer maxContextTokens, String defaultProvider,
                        List<String> anthropicSkills,
                        List<String> skillsRequiringApproval,
                        ModelFailover modelFailover) {
        /**
         * Backwards-compatible constructor predating the model-failover block.
         * Keeps existing test fixtures + wiring code working without churn.
         */
        public Agent(String persona, String systemPromptExtra, String contextFile,
                     Integer maxContextTokens, String defaultProvider,
                     List<String> anthropicSkills,
                     List<String> skillsRequiringApproval) {
            this(persona, systemPromptExtra, contextFile, maxContextTokens, defaultProvider,
                    anthropicSkills, skillsRequiringApproval, null);
        }
    }

    /**
     * Opt-in model-failover chain. When {@code enabled} and {@code chain} has
     * two or more entries, the main agent's {@code ChatModel} is replaced by a
     * {@code FailoverChatModel} that retries against the next entry on
     * rate-limit / server-error / timeout / unavailable.
     *
     * @param enabled          off by default — existing single-provider users see no change
     * @param chain            ordered list; entry 0 is primary, rest are fallbacks
     * @param retryOn          canonical reason names ({@code rate-limit}, {@code server-error},
     *                         {@code timeout}, {@code unavailable}, {@code other}).
     *                         Null / empty defaults to the first four.
     * @param failureThreshold consecutive failures per entry before it gets circuit-opened (default 3)
     * @param openForSeconds   how long to skip a circuit-open entry (default 60)
     */
    public record ModelFailover(Boolean enabled, List<FailoverChainEntry> chain,
                                List<String> retryOn,
                                Integer failureThreshold, Integer openForSeconds) {
    }

    public record FailoverChainEntry(String provider, String model) {
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

    public List<String> anthropicSkills() {
        if (agent != null && agent.anthropicSkills() != null) {
            return agent.anthropicSkills();
        }
        return List.of();
    }

    public List<String> skillsRequiringApproval() {
        if (agent != null && agent.skillsRequiringApproval() != null) {
            return agent.skillsRequiringApproval();
        }
        return List.of();
    }

    /** @return the configured failover block, or {@code null} when unset. */
    public ModelFailover modelFailover() {
        return agent == null ? null : agent.modelFailover();
    }

    /**
     * @return {@code true} only when the failover block is present, explicitly
     *         enabled, and has at least two chain entries. One-entry chains
     *         degenerate to a plain {@link org.springframework.ai.chat.model.ChatModel}.
     */
    public boolean modelFailoverEnabled() {
        ModelFailover mf = modelFailover();
        if (mf == null || mf.enabled() == null || !mf.enabled()) {
            return false;
        }
        return mf.chain() != null && mf.chain().size() >= 2;
    }

    public record Cron(String timezone) {
    }

    public record Weather(String location) {
    }

    public record Obsidian(String vaultPath, String vaultMode) {
        /**
         * Backwards-compatible constructor predating the Phase E vault-mode flag.
         * Leaves {@code vaultMode} null so the resolver falls back to {@code auto}.
         */
        public Obsidian(String vaultPath) {
            this(vaultPath, null);
        }
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

    /**
     * Returns the configured Obsidian vault-mode preference — one of
     * {@code auto}, {@code on}, or {@code off}. Defaults to {@code auto}.
     * This is the user preference only; the effective mode is resolved by
     * {@link #resolveObsidianVaultMode(String)} which also factors in whether
     * the memories dir actually overlaps the vault.
     */
    public String obsidianVaultModePreference() {
        if (obsidian != null && obsidian.vaultMode() != null && !obsidian.vaultMode().isBlank()) {
            String mode = obsidian.vaultMode().toLowerCase();
            if (mode.equals("auto") || mode.equals("on") || mode.equals("off")) {
                return mode;
            }
        }
        return "auto";
    }

    /**
     * Resolves the effective Obsidian vault mode given the memories dir.
     *
     * <ul>
     *   <li>{@code off} — plain markdown links always ({@code [text](path.md)}).</li>
     *   <li>{@code on} — wikilinks always ({@code [[path]]}), even without a vault path.</li>
     *   <li>{@code auto} — wikilinks when a vault path is set AND overlaps the memories dir.</li>
     * </ul>
     *
     * @param memoriesDir absolute path to the long-term memories directory; may be empty
     * @return {@code true} when vault-mode link conventions should apply
     */
    public boolean resolveObsidianVaultMode(String memoriesDir) {
        String pref = obsidianVaultModePreference();
        if (pref.equals("off")) {
            return false;
        }
        if (pref.equals("on")) {
            return true;
        }
        // auto
        String vault = obsidianVaultPath();
        if (vault.isEmpty() || memoriesDir == null || memoriesDir.isBlank()) {
            return false;
        }
        String vaultNorm = normalizeForCompare(vault);
        String memNorm = normalizeForCompare(memoriesDir);
        return memNorm.startsWith(vaultNorm) || vaultNorm.startsWith(memNorm);
    }

    private static String normalizeForCompare(String raw) {
        String expanded = raw;
        if (expanded.startsWith("~/")) {
            expanded = System.getProperty("user.home") + expanded.substring(1);
        }
        return expanded.replaceAll("/+$", "");
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
     * Agents are returned sorted by name (case-insensitive) so their order in the
     * system prompt is stable across restarts and YAML-map shuffles — critical
     * for Anthropic prompt-cache hits. See issue #313.
     */
    public List<A2aAgent> a2aAgents() {
        if (a2a != null && a2a.agents() != null) {
            return a2a.agents().stream()
                    .sorted(java.util.Comparator.comparing(
                            A2aAgent::name,
                            java.util.Comparator.nullsFirst(String.CASE_INSENSITIVE_ORDER)))
                    .toList();
        }
        return List.of();
    }
}
