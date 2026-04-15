package com.herald.agent;

import com.herald.config.HeraldConfig;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.credential.BearerTokenCredential;
import com.openai.credential.Credential;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.AbstractOpenAiOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
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
        String apiKey = openaiConfig.apiKey() != null ? openaiConfig.apiKey() : "";
        String baseUrl = openaiConfig.baseUrl() != null ? openaiConfig.baseUrl() : "https://api.openai.com";
        return buildOpenAiChatModel(apiKey, baseUrl);
    }

    @Bean("ollamaChatModel")
    @ConditionalOnExpression("!T(org.springframework.util.StringUtils).isEmpty('${herald.providers.ollama.base-url:}')")
    public ChatModel ollamaChatModel(HeraldConfig config) {
        var ollamaConfig = config.providers().ollama();
        String apiKey = ollamaConfig.apiKey() != null ? ollamaConfig.apiKey() : "ollama";
        String baseUrl = ollamaConfig.baseUrl() != null ? ollamaConfig.baseUrl() : "http://localhost:11434";
        return buildOpenAiChatModel(apiKey, baseUrl);
    }

    @Bean("geminiChatModel")
    @ConditionalOnExpression("!T(org.springframework.util.StringUtils).isEmpty('${herald.providers.gemini.api-key:}')")
    public ChatModel geminiChatModel(HeraldConfig config) {
        var geminiConfig = config.providers().gemini();
        String apiKey = geminiConfig.apiKey() != null ? geminiConfig.apiKey() : "";
        String baseUrl = geminiConfig.baseUrl() != null ? geminiConfig.baseUrl()
                : "https://generativelanguage.googleapis.com/v1beta/openai";
        return buildOpenAiChatModel(apiKey, baseUrl);
    }

    @Bean("lmstudioChatModel")
    @ConditionalOnExpression("!T(org.springframework.util.StringUtils).isEmpty('${herald.providers.lmstudio.base-url:}')")
    public ChatModel lmstudioChatModel(HeraldConfig config) {
        var lmstudioConfig = config.providers().lmstudio();
        String apiKey = lmstudioConfig.apiKey() != null ? lmstudioConfig.apiKey() : "lm-studio";
        String baseUrl = lmstudioConfig.baseUrl() != null ? lmstudioConfig.baseUrl() : "http://localhost:1234";
        return buildOpenAiChatModel(apiKey, baseUrl);
    }

    @Bean("lmstudioEmbeddingModel")
    @ConditionalOnExpression("!T(org.springframework.util.StringUtils).isEmpty('${herald.providers.lmstudio.base-url:}')")
    public EmbeddingModel lmstudioEmbeddingModel(HeraldConfig config) {
        var lmstudioConfig = config.providers().lmstudio();
        String apiKey = lmstudioConfig.apiKey() != null ? lmstudioConfig.apiKey() : "lm-studio";
        String baseUrl = lmstudioConfig.baseUrl() != null ? lmstudioConfig.baseUrl() : "http://localhost:1234";
        OpenAIClient client = buildSyncClient(apiKey, baseUrl);
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model("text-embedding-nomic-embed-text-v2-moe")
                .build();
        return new OpenAiEmbeddingModel(client, MetadataMode.EMBED, options);
    }

    private static ChatModel buildOpenAiChatModel(String apiKey, String baseUrl) {
        Credential credential = BearerTokenCredential.create(apiKey);
        OpenAIClient syncClient = buildSyncClient(apiKey, baseUrl);
        OpenAIClientAsync asyncClient = OpenAiSetup.setupAsyncClient(
                baseUrl, null, credential, null, null, null, false, false,
                null, AbstractOpenAiOptions.DEFAULT_TIMEOUT, 2, null, null);
        return OpenAiChatModel.builder()
                .openAiClient(syncClient)
                .openAiClientAsync(asyncClient)
                .build();
    }

    private static OpenAIClient buildSyncClient(String apiKey, String baseUrl) {
        Credential credential = BearerTokenCredential.create(apiKey);
        return OpenAiSetup.setupSyncClient(
                baseUrl, null, credential, null, null, null, false, false,
                null, AbstractOpenAiOptions.DEFAULT_TIMEOUT, 2, null, null);
    }
}
