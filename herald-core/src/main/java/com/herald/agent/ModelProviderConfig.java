package com.herald.agent;

import com.herald.config.HeraldConfig;
import com.openai.client.OpenAIClient;
import com.openai.client.OpenAIClientAsync;
import com.openai.credential.BearerTokenCredential;
import com.openai.credential.Credential;
import io.micrometer.core.instrument.Metrics;
import io.micrometer.observation.ObservationRegistry;
import java.util.List;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.openai.AbstractOpenAiOptions;
import org.springframework.ai.openai.OpenAiChatModel;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.setup.OpenAiSetup;
import com.herald.config.ConditionalOnProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Creates ChatModel beans for OpenAI and Ollama providers based on {@code herald.providers} config.
 * Both use the OpenAI-compatible {@code /v1/chat/completions} format — Ollama differs only in base URL.
 */
@Configuration
public class ModelProviderConfig {

    @Bean("openaiChatModel")
    @ConditionalOnProvider("openai")
    public ChatModel openaiChatModel(HeraldConfig config) {
        var openaiConfig = config.providers().openai();
        String apiKey = valueOrDefault(openaiConfig.apiKey(), "");
        String baseUrl = valueOrDefault(openaiConfig.baseUrl(), "https://api.openai.com");
        return buildOpenAiChatModel(apiKey, baseUrl);
    }

    @Bean("ollamaChatModel")
    @ConditionalOnProvider("ollama")
    public ChatModel ollamaChatModel(HeraldConfig config) {
        var ollamaConfig = config.providers().ollama();
        String apiKey = valueOrDefault(ollamaConfig.apiKey(), "ollama");
        String baseUrl = valueOrDefault(ollamaConfig.baseUrl(), "http://localhost:11434");
        return buildOpenAiChatModel(apiKey, baseUrl);
    }

    @Bean("geminiChatModel")
    @ConditionalOnProvider("gemini")
    public ChatModel geminiChatModel(HeraldConfig config) {
        var geminiConfig = config.providers().gemini();
        String apiKey = valueOrDefault(geminiConfig.apiKey(), "");
        String baseUrl = valueOrDefault(geminiConfig.baseUrl(), "https://generativelanguage.googleapis.com/v1beta/openai");
        // Gemini 3.x requires thought_signature round-trip on assistant tool_calls.
        // Wrap the OpenAI HttpClient with an interceptor that captures + replays it.
        GeminiThoughtSignatureHttpClient sharedSig = new GeminiThoughtSignatureHttpClient(
                com.openai.client.okhttp.OkHttpClient.builder()
                        .timeout(AbstractOpenAiOptions.DEFAULT_TIMEOUT)
                        .build());
        // Pass the resolved key explicitly: OpenAiSetup otherwise re-reads OPENAI_API_KEY.
        Credential credential = BearerTokenCredential.create(apiKey);
        OpenAIClient syncClient = OpenAiSetup.setupSyncClient(
                baseUrl, apiKey, credential, null, null, null, false, false,
                null, AbstractOpenAiOptions.DEFAULT_TIMEOUT, 2, null, null,
                ObservationRegistry.NOOP, Metrics.globalRegistry, List.of())
                .withOptions(b -> b.httpClient(sharedSig));
        OpenAIClientAsync asyncClient = OpenAiSetup.setupAsyncClient(
                baseUrl, apiKey, credential, null, null, null, false, false,
                null, AbstractOpenAiOptions.DEFAULT_TIMEOUT, 2, null, null,
                ObservationRegistry.NOOP, Metrics.globalRegistry, List.of())
                .withOptions(b -> b.httpClient(sharedSig));
        return OpenAiChatModel.builder()
                .openAiClient(syncClient)
                .openAiClientAsync(asyncClient)
                .build();
    }

    @Bean("lmstudioChatModel")
    @ConditionalOnProvider("lmstudio")
    public ChatModel lmstudioChatModel(HeraldConfig config) {
        var lmstudioConfig = config.providers().lmstudio();
        String apiKey = valueOrDefault(lmstudioConfig.apiKey(), "lm-studio");
        String baseUrl = valueOrDefault(lmstudioConfig.baseUrl(), "http://localhost:1234");
        return buildOpenAiChatModel(apiKey, baseUrl);
    }

    @Bean("lmstudioEmbeddingModel")
    @ConditionalOnProvider("lmstudio")
    public EmbeddingModel lmstudioEmbeddingModel(HeraldConfig config) {
        var lmstudioConfig = config.providers().lmstudio();
        String apiKey = valueOrDefault(lmstudioConfig.apiKey(), "lm-studio");
        String baseUrl = valueOrDefault(lmstudioConfig.baseUrl(), "http://localhost:1234");
        OpenAIClient client = buildSyncClient(apiKey, baseUrl);
        OpenAiEmbeddingOptions options = OpenAiEmbeddingOptions.builder()
                .model("text-embedding-nomic-embed-text-v2-moe")
                .build();
        return new OpenAiEmbeddingModel(client, MetadataMode.EMBED, options);
    }

    private static String valueOrDefault(String value, String fallback) {
        return value != null && !value.isBlank() ? value.trim() : fallback;
    }

    private static ChatModel buildOpenAiChatModel(String apiKey, String baseUrl) {
        // Pass the resolved key explicitly: OpenAiSetup otherwise re-reads OPENAI_API_KEY.
        Credential credential = BearerTokenCredential.create(apiKey);
        OpenAIClient syncClient = buildSyncClient(apiKey, baseUrl);
        OpenAIClientAsync asyncClient = OpenAiSetup.setupAsyncClient(
                baseUrl, apiKey, credential, null, null, null, false, false,
                null, AbstractOpenAiOptions.DEFAULT_TIMEOUT, 2, null, null,
                ObservationRegistry.NOOP, Metrics.globalRegistry, List.of());
        return OpenAiChatModel.builder()
                .openAiClient(syncClient)
                .openAiClientAsync(asyncClient)
                .build();
    }

    private static OpenAIClient buildSyncClient(String apiKey, String baseUrl) {
        // Pass the resolved key explicitly: OpenAiSetup otherwise re-reads OPENAI_API_KEY.
        Credential credential = BearerTokenCredential.create(apiKey);
        return OpenAiSetup.setupSyncClient(
                baseUrl, apiKey, credential, null, null, null, false, false,
                null, AbstractOpenAiOptions.DEFAULT_TIMEOUT, 2, null, null,
                ObservationRegistry.NOOP, Metrics.globalRegistry, List.of());
    }
}
