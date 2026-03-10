package com.herald.agent;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ModelSwitcherTest {

    private ChatModel anthropicModel;
    private ChatModel openaiModel;
    private ChatModel ollamaModel;
    private JdbcTemplate jdbcTemplate;
    private ChatClient initialClient;
    private ChatClient.Builder mockBuilder;
    private ModelSwitcher switcher;

    @BeforeEach
    void setUp() {
        anthropicModel = mock(ChatModel.class);
        openaiModel = mock(ChatModel.class);
        ollamaModel = mock(ChatModel.class);
        jdbcTemplate = mock(JdbcTemplate.class);
        initialClient = mock(ChatClient.class);
        mockBuilder = mock(ChatClient.Builder.class);

        Map<String, ChatModel> availableModels = new LinkedHashMap<>();
        availableModels.put("anthropic", anthropicModel);
        availableModels.put("openai", openaiModel);
        availableModels.put("ollama", ollamaModel);

        ChatClient switchedClient = mock(ChatClient.class);
        when(mockBuilder.defaultOptions(any())).thenReturn(mockBuilder);
        when(mockBuilder.build()).thenReturn(switchedClient);

        Function<ChatModel, ChatClient.Builder> factory = cm -> mockBuilder;

        switcher = new ModelSwitcher(availableModels, jdbcTemplate, factory,
                initialClient, "anthropic", "claude-sonnet-4-5");
    }

    @Test
    void initialStateReflectsDefaults() {
        assertThat(switcher.getActiveClient()).isSameAs(initialClient);
        assertThat(switcher.getActiveProvider()).isEqualTo("anthropic");
        assertThat(switcher.getActiveModel()).isEqualTo("claude-sonnet-4-5");
    }

    @Test
    void getAvailableProvidersReturnsAllConfigured() {
        assertThat(switcher.getAvailableProviders()).containsExactly("anthropic", "openai", "ollama");
    }

    @Test
    void switchModelUpdatesActiveState() {
        switcher.switchModel("openai", "gpt-4o");

        assertThat(switcher.getActiveProvider()).isEqualTo("openai");
        assertThat(switcher.getActiveModel()).isEqualTo("gpt-4o");
        assertThat(switcher.getActiveClient()).isNotSameAs(initialClient);
    }

    @Test
    void switchModelPersistsOverride() {
        switcher.switchModel("ollama", "qwen2.5-coder");

        verify(jdbcTemplate).update("DELETE FROM model_overrides");
        verify(jdbcTemplate).update(
                "INSERT INTO model_overrides (provider, model) VALUES (?, ?)",
                "ollama", "qwen2.5-coder");
    }

    @Test
    void switchModelThrowsForUnconfiguredProvider() {
        assertThatThrownBy(() -> switcher.switchModel("azure", "gpt-4"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("azure")
                .hasMessageContaining("not configured");
    }

    @Test
    void switchModelBuildsClientWithCorrectModel() {
        switcher.switchModel("anthropic", "claude-haiku-4-5");

        verify(mockBuilder).defaultOptions(any());
        verify(mockBuilder).build();
        assertThat(switcher.getActiveModel()).isEqualTo("claude-haiku-4-5");
    }

    @Test
    void switchModelToOllamaSucceeds() {
        switcher.switchModel("ollama", "llama3.2");

        assertThat(switcher.getActiveProvider()).isEqualTo("ollama");
        assertThat(switcher.getActiveModel()).isEqualTo("llama3.2");
    }

    @Test
    void multipleSwitchesUpdateState() {
        switcher.switchModel("openai", "gpt-4o");
        assertThat(switcher.getActiveProvider()).isEqualTo("openai");

        switcher.switchModel("anthropic", "claude-haiku-4-5");
        assertThat(switcher.getActiveProvider()).isEqualTo("anthropic");
        assertThat(switcher.getActiveModel()).isEqualTo("claude-haiku-4-5");
    }
}
