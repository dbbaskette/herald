package com.herald.agent;

import com.herald.config.HeraldConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;

import static org.assertj.core.api.Assertions.assertThat;

class ModelProviderConfigTest {

    private final ModelProviderConfig providerConfig = new ModelProviderConfig();

    @Test
    void openaiChatModelCreatedWithApiKey() {
        HeraldConfig config = configWithOpenAi("sk-test-key", "https://api.openai.com");
        ChatModel model = providerConfig.openaiChatModel(config);
        assertThat(model).isInstanceOf(OpenAiChatModel.class);
    }

    @Test
    void openaiChatModelUsesDefaultBaseUrlWhenNull() {
        HeraldConfig config = configWithOpenAi("sk-test-key", null);
        ChatModel model = providerConfig.openaiChatModel(config);
        assertThat(model).isInstanceOf(OpenAiChatModel.class);
    }

    @Test
    void ollamaChatModelCreatedWithBaseUrl() {
        HeraldConfig config = configWithOllama("http://localhost:11434", "ollama");
        ChatModel model = providerConfig.ollamaChatModel(config);
        assertThat(model).isInstanceOf(OpenAiChatModel.class);
    }

    @Test
    void ollamaChatModelUsesDefaultApiKeyWhenNull() {
        HeraldConfig config = configWithOllama("http://localhost:11434", null);
        ChatModel model = providerConfig.ollamaChatModel(config);
        assertThat(model).isInstanceOf(OpenAiChatModel.class);
    }

    @Test
    void ollamaChatModelUsesDefaultBaseUrlWhenNull() {
        HeraldConfig config = configWithOllama(null, "ollama");
        ChatModel model = providerConfig.ollamaChatModel(config);
        assertThat(model).isInstanceOf(OpenAiChatModel.class);
    }

    private HeraldConfig configWithOpenAi(String apiKey, String baseUrl) {
        var providers = new HeraldConfig.Providers(null,
                new HeraldConfig.OpenAiProviderConfig(apiKey, baseUrl), null);
        return new HeraldConfig(null, null, null, providers, null, null);
    }

    private HeraldConfig configWithOllama(String baseUrl, String apiKey) {
        var providers = new HeraldConfig.Providers(null, null,
                new HeraldConfig.OpenAiProviderConfig(apiKey, baseUrl));
        return new HeraldConfig(null, null, null, providers, null, null);
    }
}
