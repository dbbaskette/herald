package com.herald.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
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

    private volatile ChatClient activeClient;
    private volatile String activeProvider;
    private volatile String activeModel;

    ModelSwitcher(Map<String, ChatModel> availableModels,
                  Map<String, String> providerDefaultModels,
                  JdbcTemplate jdbcTemplate,
                  Function<ChatModel, ChatClient.Builder> clientBuilderFactory,
                  ChatClient initialClient,
                  String defaultProvider,
                  String defaultModel) {
        this.availableModels = new LinkedHashMap<>(availableModels);
        this.providerDefaultModels = new LinkedHashMap<>(providerDefaultModels);
        this.jdbcTemplate = jdbcTemplate;
        this.clientBuilderFactory = clientBuilderFactory;
        this.activeClient = initialClient;
        this.activeProvider = defaultProvider;
        this.activeModel = defaultModel;
    }

    /**
     * Attempt to load a persisted model override from the database.
     * If one exists and the provider is available, switch to it.
     */
    void loadPersistedOverride() {
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

        var options = HeraldAgentConfig.chatOptionsForModel(chatModel, model);
        ChatClient newClient = clientBuilderFactory.apply(chatModel)
                .defaultOptions(options)
                .build();

        this.activeClient = newClient;
        this.activeProvider = provider;
        this.activeModel = model;

        persistOverride(provider, model);
        log.info("Switched main agent to {}/{}", provider, model);
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

    private void persistOverride(String provider, String model) {
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
