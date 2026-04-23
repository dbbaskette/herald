package com.herald.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.anthropic.AnthropicChatOptions;
import org.springframework.ai.anthropic.AnthropicCacheOptions;
import org.springframework.ai.anthropic.AnthropicCacheStrategy;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Manages runtime model switching for the main agent.
 * Persists overrides to the model_overrides table so they survive restarts.
 */
public class ModelSwitcher {

    private static final Logger log = LoggerFactory.getLogger(ModelSwitcher.class);

    private final Map<String, ChatModel> availableModels;
    private final Map<String, String> providerDefaultModels;
    private final JdbcTemplate jdbcTemplate;
    private final Function<ChatModel, ChatClient.Builder> clientBuilderFactory;
    private final List<String> anthropicSkills;

    private volatile ChatClient activeClient;
    private volatile String activeProvider;
    private volatile String activeModel;
    private volatile ThinkingTier thinkingTier = ThinkingTier.OFF;

    /**
     * Extended-thinking budget tier applied to Anthropic models on each turn.
     * Set via the {@code /think} Telegram command or {@link #setThinkingTier(ThinkingTier)}.
     * See issue #307.
     */
    public enum ThinkingTier {
        /** Thinking disabled — default. */
        OFF(0),
        /** Low budget — short, simple reasoning. */
        LOW(1024),
        /** Medium budget — balanced cost / depth. */
        MEDIUM(4096),
        /** High budget — deep analysis. */
        HIGH(16384);

        private final long budgetTokens;

        ThinkingTier(long budgetTokens) {
            this.budgetTokens = budgetTokens;
        }

        public long budgetTokens() {
            return budgetTokens;
        }
    }

    ModelSwitcher(Map<String, ChatModel> availableModels,
                  Map<String, String> providerDefaultModels,
                  JdbcTemplate jdbcTemplate,
                  Function<ChatModel, ChatClient.Builder> clientBuilderFactory,
                  ChatClient initialClient,
                  String defaultProvider,
                  String defaultModel,
                  List<String> anthropicSkills) {
        this.availableModels = new LinkedHashMap<>(availableModels);
        this.providerDefaultModels = new LinkedHashMap<>(providerDefaultModels);
        this.jdbcTemplate = jdbcTemplate;
        this.clientBuilderFactory = clientBuilderFactory;
        this.activeClient = initialClient;
        this.activeProvider = defaultProvider;
        this.activeModel = defaultModel;
        this.anthropicSkills = anthropicSkills != null ? anthropicSkills : List.of();
    }

    /**
     * Attempt to load a persisted model override from the database.
     * If one exists and the provider is available, switch to it.
     */
    void loadPersistedOverride() {
        if (jdbcTemplate == null) {
            log.info("No database configured — skipping persisted model override");
            return;
        }
        try {
            var overrides = jdbcTemplate.query(
                    "SELECT provider, model FROM model_overrides ORDER BY updated_at DESC LIMIT 1",
                    (rs, rowNum) -> new String[]{rs.getString("provider"), rs.getString("model")});

            if (!overrides.isEmpty()) {
                String provider = overrides.get(0)[0];
                String model = overrides.get(0)[1];
                if (availableModels.containsKey(provider)) {
                    log.info("Restoring persisted model override: {}/{}", provider, model);
                    switchModel(provider, model);
                } else {
                    log.warn("Persisted model override references unavailable provider '{}', using defaults", provider);
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load persisted model override, using defaults: {}", e.getMessage());
        }
    }

    /**
     * Switch the main agent to the specified provider and model.
     *
     * @throws IllegalArgumentException if the provider is not configured
     */
    public void switchModel(String provider, String model) {
        ChatModel chatModel = availableModels.get(provider);
        if (chatModel == null) {
            throw new IllegalArgumentException("Provider '" + provider + "' is not configured. "
                    + "Available providers: " + availableModels.keySet());
        }

        rebuildClient(chatModel, provider, model);
        persistOverride(provider, model);
        log.info("Switched main agent to {}/{}", provider, model);
    }

    /**
     * Set the extended-thinking budget tier for subsequent turns. Rebuilds the
     * active {@link ChatClient} with the new thinking options. A no-op when
     * the active provider isn't Anthropic — thinking-budget is Anthropic-only.
     */
    public void setThinkingTier(ThinkingTier tier) {
        if (tier == null) {
            tier = ThinkingTier.OFF;
        }
        this.thinkingTier = tier;
        ChatModel chatModel = availableModels.get(activeProvider);
        if (chatModel != null) {
            rebuildClient(chatModel, activeProvider, activeModel);
        }
        log.info("Set thinking tier to {} (budget={} tokens)", tier, tier.budgetTokens());
    }

    public ThinkingTier getThinkingTier() {
        return thinkingTier;
    }

    private void rebuildClient(ChatModel chatModel, String provider, String model) {
        var builder = chatOptionsForModel(chatModel, model, anthropicSkills);
        if (builder instanceof org.springframework.ai.anthropic.AnthropicChatOptions.Builder anthropicBuilder) {
            if (thinkingTier == ThinkingTier.OFF) {
                anthropicBuilder.thinkingDisabled();
            } else {
                anthropicBuilder.thinkingEnabled(thinkingTier.budgetTokens());
            }
        }
        ChatClient newClient = clientBuilderFactory.apply(chatModel)
                .defaultOptions(builder)
                .build();

        this.activeClient = newClient;
        this.activeProvider = provider;
        this.activeModel = model;
    }

    public ChatClient getActiveClient() {
        return activeClient;
    }

    public String getActiveProvider() {
        return activeProvider;
    }

    public String getActiveModel() {
        return activeModel;
    }

    public Set<String> getAvailableProviders() {
        return availableModels.keySet();
    }

    public Map<String, String> getAvailableProviderDefaults() {
        return Map.copyOf(providerDefaultModels);
    }

    /**
     * Replace the ChatModel for a provider (e.g. after an OAuth token refresh).
     * If this provider is currently active, the ChatClient is rebuilt automatically.
     */
    public void updateProviderModel(String provider, ChatModel newModel) {
        availableModels.put(provider, newModel);
        if (provider.equals(activeProvider)) {
            log.info("Refreshing active ChatClient for provider '{}'", provider);
            switchModel(provider, activeModel);
        }
    }

    /**
     * Build vendor-specific ChatOptions for the given model.
     * Extracted here so herald-core has no dependency on HeraldAgentConfig.
     * Anthropic skills are applied when provided; pass {@code List.of()} for subagent builders.
     *
     * <p>For Anthropic models, applies {@link AnthropicCacheStrategy#SYSTEM_AND_TOOLS}
     * by default so Herald's large system prompt (skills, memory index, context) and
     * tool catalog are cached across turns. Without this, every turn pays full-price
     * input tokens. See issue #313.</p>
     */
    public static ChatOptions.Builder<?> chatOptionsForModel(ChatModel chatModel, String modelId, List<String> anthropicSkills) {
        return chatOptionsForModel(chatModel, modelId, anthropicSkills, AnthropicCacheStrategy.SYSTEM_AND_TOOLS);
    }

    /**
     * Variant that lets callers pick a cache strategy — useful when the user has
     * set {@code herald.agent.anthropic.cache-strategy} to a non-default value.
     */
    public static ChatOptions.Builder<?> chatOptionsForModel(ChatModel chatModel, String modelId,
                                                             List<String> anthropicSkills,
                                                             AnthropicCacheStrategy cacheStrategy) {
        if (chatModel instanceof OpenAiChatModel) {
            return ChatOptions.builder().model(modelId);
        }
        // Default to Anthropic
        var builder = AnthropicChatOptions.builder().model(modelId);
        for (String skillId : anthropicSkills) {
            builder.skill(skillId);
        }
        if (cacheStrategy != null && cacheStrategy != AnthropicCacheStrategy.NONE) {
            builder.cacheOptions(AnthropicCacheOptions.builder()
                    .strategy(cacheStrategy)
                    .build());
        }
        return builder;
    }

    private void persistOverride(String provider, String model) {
        if (jdbcTemplate == null) {
            log.debug("No database configured — model override not persisted");
            return;
        }
        try {
            jdbcTemplate.update("DELETE FROM model_overrides");
            jdbcTemplate.update(
                    "INSERT INTO model_overrides (provider, model) VALUES (?, ?)",
                    provider, model);
        } catch (Exception e) {
            log.warn("Failed to persist model override: {}", e.getMessage());
        }
    }
}
