package com.herald.agent;

import com.herald.config.HeraldConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates ChatModel beans for OpenAI and Ollama providers based on {@code herald.providers} config.
 * Both use the OpenAI-compatible {@code /v1/chat/completions} format — Ollama differs only in base URL.
 */
@Configuration
class ModelProviderConfig {

    @Bean("openaiChatModel")
    @ConditionalOnProperty("herald.providers.openai.api-key")
    ChatModel openaiChatModel(HeraldConfig config) {
        var openaiConfig = config.providers().openai();
        String baseUrl = openaiConfig.baseUrl() != null ? openaiConfig.baseUrl() : "https://api.openai.com";
        OpenAiApi api = OpenAiApi.builder()
                .apiKey(openaiConfig.apiKey())
                .baseUrl(baseUrl)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .build();
    }

    @Bean("ollamaChatModel")
    @ConditionalOnProperty("herald.providers.ollama.base-url")
    ChatModel ollamaChatModel(HeraldConfig config) {
        var ollamaConfig = config.providers().ollama();
        String apiKey = ollamaConfig.apiKey() != null ? ollamaConfig.apiKey() : "ollama";
        String baseUrl = ollamaConfig.baseUrl() != null ? ollamaConfig.baseUrl() : "http://localhost:11434";
        OpenAiApi api = OpenAiApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .build();
    }
}
