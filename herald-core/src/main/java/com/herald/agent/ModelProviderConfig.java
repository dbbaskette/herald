package com.herald.agent;

import com.herald.config.HeraldConfig;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates ChatModel beans for OpenAI and Ollama providers based on {@code herald.providers} config.
 * Both use the OpenAI-compatible {@code /v1/chat/completions} format — Ollama differs only in base URL.
 */
@Configuration
public class ModelProviderConfig {

    @Bean("openaiChatModel")
    @ConditionalOnExpression("!T(org.springframework.util.StringUtils).isEmpty('${herald.providers.openai.api-key:}')")
    public ChatModel openaiChatModel(HeraldConfig config) {
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
    @ConditionalOnExpression("!T(org.springframework.util.StringUtils).isEmpty('${herald.providers.ollama.base-url:}')")
    public ChatModel ollamaChatModel(HeraldConfig config) {
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

    @Bean("geminiChatModel")
    @ConditionalOnExpression("!T(org.springframework.util.StringUtils).isEmpty('${herald.providers.gemini.api-key:}')")
    public ChatModel geminiChatModel(HeraldConfig config) {
        var geminiConfig = config.providers().gemini();
        String baseUrl = geminiConfig.baseUrl() != null ? geminiConfig.baseUrl()
                : "https://generativelanguage.googleapis.com/v1beta/openai";
        OpenAiApi api = OpenAiApi.builder()
                .apiKey(geminiConfig.apiKey())
                .baseUrl(baseUrl)
                .build();
        return OpenAiChatModel.builder()
                .openAiApi(api)
                .build();
    }
}
