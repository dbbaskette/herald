package com.herald.agent;

import java.util.List;

import com.herald.config.HeraldConfig;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PrimaryModelProviderTest {

    @Test
    void startsWithOpenAiWhenRequestedAnthropicIsUnconfigured() {
        ChatModel openAi = mock(ChatModel.class);
        HeraldConfig config = config("anthropic", new HeraldConfig.Providers(
                new HeraldConfig.ProviderConfig(""),
                new HeraldConfig.OpenAiProviderConfig("openai-key", "https://api.openai.com"),
                null, null, null));

        ChatModel selected = new HeraldAgentConfig().primaryChatModel(config,
                empty(), provider(openAi), empty(), empty(), empty());

        assertThat(selected).isSameAs(openAi);
    }

    @Test
    void honorsConfiguredGeminiAsRequestedPrimary() {
        ChatModel anthropic = mock(ChatModel.class);
        ChatModel gemini = mock(ChatModel.class);
        HeraldConfig config = config("gemini", new HeraldConfig.Providers(
                new HeraldConfig.ProviderConfig("anthropic-key"),
                null, null,
                new HeraldConfig.OpenAiProviderConfig("gemini-key", "https://generativelanguage.googleapis.com/v1beta/openai"),
                null));

        ChatModel selected = new HeraldAgentConfig().primaryChatModel(config,
                provider(anthropic), empty(), empty(), provider(gemini), empty());

        assertThat(selected).isSameAs(gemini);
    }

    private static HeraldConfig config(String requested, HeraldConfig.Providers providers) {
        return new HeraldConfig(null, null,
                new HeraldConfig.Agent(null, null, null, null, requested, List.of(), List.of()),
                providers, null, null, null, null, null, null, null, null);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ChatModel> empty() {
        return mock(ObjectProvider.class);
    }

    @SuppressWarnings("unchecked")
    private static ObjectProvider<ChatModel> provider(ChatModel model) {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(model);
        return provider;
    }
}
