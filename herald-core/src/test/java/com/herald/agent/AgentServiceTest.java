package com.herald.agent;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.EmptyUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

class AgentServiceTest {

    private ChatClient chatClient;
    private ChatClientRequestSpec requestSpec;
    private CallResponseSpec callResponseSpec;
    private AgentTurnListener agentTurnListener;
    private ModelSwitcher modelSwitcher;
    private AgentService agentService;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClientRequestSpec.class);
        callResponseSpec = mock(CallResponseSpec.class);
        agentTurnListener = mock(AgentTurnListener.class);
        modelSwitcher = mock(ModelSwitcher.class);

        when(modelSwitcher.getActiveClient()).thenReturn(chatClient);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);

        agentService = new AgentService(modelSwitcher, agentTurnListener);
    }

    @Test
    void chatReturnsAgentResponse() {
        when(callResponseSpec.chatResponse()).thenReturn(mockChatResponse("Hello from Herald!"));

        String result = agentService.chat("Hi");

        assertThat(result).isEqualTo("Hello from Herald!");
        verify(requestSpec).user("Hi");
        verify(agentTurnListener).recordTurn(anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any(), isNull());
    }

    @Test
    void chatWithConversationId() {
        when(callResponseSpec.chatResponse()).thenReturn(mockChatResponse("Response"));

        String result = agentService.chat("test", "session-123");

        assertThat(result).isEqualTo("Response");
        verify(requestSpec).user("test");
    }

    @Test
    void chatReturnsEmptyStringWhenResponseIsNull() {
        when(callResponseSpec.chatResponse()).thenReturn(null);

        String result = agentService.chat("Hi");

        assertThat(result).isEmpty();
    }

    @Test
    void chatPropagatesExceptionsAndRecordsMetrics() {
        when(requestSpec.call()).thenThrow(new RuntimeException("API error"));

        assertThatThrownBy(() -> agentService.chat("hello"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("API error");

        // Metrics should still be recorded on failure with zero tokens
        verify(agentTurnListener).recordTurn(eq("unknown"), eq("unknown"), eq(0L), eq(0L), eq(0L), eq(0L), anyLong(), eq(List.of()), isNull());
    }

    @Test
    void chatRecordsMetricsOnSuccess() {
        when(callResponseSpec.chatResponse()).thenReturn(mockChatResponse("reply"));

        agentService.chat("test");

        verify(agentTurnListener).recordTurn(anyString(), anyString(), anyLong(), anyLong(), anyLong(), anyLong(), anyLong(), any(), isNull());
    }

    @Test
    void chatExtractsToolCallNames() {
        List<AssistantMessage.ToolCall> toolCalls = List.of(
                new AssistantMessage.ToolCall("tc1", "function", "shell_exec", "{}"),
                new AssistantMessage.ToolCall("tc2", "function", "file_read", "{}"));
        AssistantMessage assistantMessage = AssistantMessage.builder()
                .content("result")
                .toolCalls(toolCalls)
                .build();
        Generation generation = new Generation(assistantMessage);
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new EmptyUsage())
                .model("claude-sonnet-4-5")
                .build();
        ChatResponse response = new ChatResponse(List.of(generation), metadata);
        when(callResponseSpec.chatResponse()).thenReturn(response);

        agentService.chat("run tools");

        verify(agentTurnListener).recordTurn(anyString(), anyString(), anyLong(), anyLong(),
                anyLong(), anyLong(), anyLong(),
                eq(List.of("shell_exec", "file_read")), isNull());
    }

    @Test
    void chatUsesActiveClientFromModelSwitcher() {
        when(callResponseSpec.chatResponse()).thenReturn(mockChatResponse("response"));

        agentService.chat("test");

        verify(modelSwitcher).getActiveClient();
        verify(chatClient).prompt();
    }

    private ChatResponse mockChatResponse(String content) {
        AssistantMessage assistantMessage = new AssistantMessage(content);
        Generation generation = new Generation(assistantMessage);
        ChatResponseMetadata metadata = ChatResponseMetadata.builder()
                .usage(new EmptyUsage())
                .model("claude-sonnet-4-5")
                .build();
        return new ChatResponse(List.of(generation), metadata);
    }

    @Test
    void stripThinkTagsRemovesReasoningBlock() {
        String input = "<think>User is greeting me, respond concisely.</think>\nHello! How can I help?";
        assertThat(AgentService.stripThinkTags(input)).isEqualTo("Hello! How can I help?");
    }

    @Test
    void stripThinkTagsHandlesMultilineThinking() {
        String input = "<think>\nLine 1\nLine 2\n</think>\n\nActual response here.";
        assertThat(AgentService.stripThinkTags(input)).isEqualTo("Actual response here.");
    }

    @Test
    void stripThinkTagsPassesThroughCleanText() {
        String input = "No think tags here.";
        assertThat(AgentService.stripThinkTags(input)).isEqualTo("No think tags here.");
    }

    @Test
    void stripThinkTagsHandlesMultipleBlocks() {
        String input = "<think>first</think>Hello <think>second</think>world";
        assertThat(AgentService.stripThinkTags(input)).isEqualTo("Hello world");
    }

    // --- #313 cache-token extraction ---

    @Test
    void extractCacheTokensReturnsZerosWhenNativeUsageNull() {
        assertThat(AgentService.extractCacheTokens(null)).containsExactly(0L, 0L);
    }

    @Test
    void extractCacheTokensReturnsZerosForForeignUsageShape() {
        // Non-Anthropic native usage without the Anthropic-specific methods.
        Object fakeUsage = new Object() {
            public int promptTokens() { return 100; }
            public int completionTokens() { return 50; }
        };
        assertThat(AgentService.extractCacheTokens(fakeUsage)).containsExactly(0L, 0L);
    }

    @Test
    void extractCacheTokensReadsAnthropicShapedUsage() {
        // Anthropic's AnthropicApi$Usage exposes cacheReadInputTokens() +
        // cacheCreationInputTokens(). Build a structurally compatible stub.
        Object anthropicUsage = new Object() {
            public Integer cacheReadInputTokens() { return 4_000; }
            public Integer cacheCreationInputTokens() { return 200; }
        };
        assertThat(AgentService.extractCacheTokens(anthropicUsage)).containsExactly(4_000L, 200L);
    }

    @Test
    void extractCacheTokensHandlesNullReturnValues() {
        Object anthropicUsage = new Object() {
            public Integer cacheReadInputTokens() { return null; }
            public Integer cacheCreationInputTokens() { return null; }
        };
        assertThat(AgentService.extractCacheTokens(anthropicUsage)).containsExactly(0L, 0L);
    }
}
