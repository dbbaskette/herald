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
import static org.mockito.Mockito.*;

class AgentServiceTest {

    private ChatClient chatClient;
    private ChatClientRequestSpec requestSpec;
    private CallResponseSpec callResponseSpec;
    private AgentMetrics agentMetrics;
    private AgentService agentService;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClientRequestSpec.class);
        callResponseSpec = mock(CallResponseSpec.class);
        agentMetrics = mock(AgentMetrics.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);

        agentService = new AgentService(chatClient, agentMetrics);
    }

    @Test
    void chatReturnsAgentResponse() {
        when(callResponseSpec.chatResponse()).thenReturn(mockChatResponse("Hello from Herald!"));

        String result = agentService.chat("Hi");

        assertThat(result).isEqualTo("Hello from Herald!");
        verify(requestSpec).user("Hi");
        verify(agentMetrics).recordTurn(anyString(), anyLong(), anyLong(), anyLong(), any());
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
    void chatPropagatesExceptions() {
        when(requestSpec.call()).thenThrow(new RuntimeException("API error"));

        assertThatThrownBy(() -> agentService.chat("hello"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("API error");
    }

    @Test
    void chatRecordsMetricsOnSuccess() {
        when(callResponseSpec.chatResponse()).thenReturn(mockChatResponse("reply"));

        agentService.chat("test");

        verify(agentMetrics).recordTurn(anyString(), anyLong(), anyLong(), anyLong(), any());
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
}
