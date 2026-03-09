package com.herald.agent;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.ChatClient.CallResponseSpec;
import org.springframework.ai.chat.client.ChatClient.ChatClientRequestSpec;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class AgentServiceTest {

    private ChatClient chatClient;
    private ChatClientRequestSpec requestSpec;
    private CallResponseSpec callResponseSpec;
    private AgentService agentService;

    @BeforeEach
    void setUp() {
        chatClient = mock(ChatClient.class);
        requestSpec = mock(ChatClientRequestSpec.class);
        callResponseSpec = mock(CallResponseSpec.class);

        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.advisors(any(java.util.function.Consumer.class))).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);

        agentService = new AgentService(chatClient);
    }

    @Test
    void chatReturnsAgentResponse() {
        when(callResponseSpec.content()).thenReturn("Hello from Herald!");

        String result = agentService.chat("Hi");

        assertThat(result).isEqualTo("Hello from Herald!");
        verify(requestSpec).user("Hi");
    }

    @Test
    void chatWithConversationId() {
        when(callResponseSpec.content()).thenReturn("Response");

        String result = agentService.chat("test", "session-123");

        assertThat(result).isEqualTo("Response");
        verify(requestSpec).user("test");
    }

    @Test
    void chatReturnsEmptyStringWhenContentIsNull() {
        when(callResponseSpec.content()).thenReturn(null);

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
}
