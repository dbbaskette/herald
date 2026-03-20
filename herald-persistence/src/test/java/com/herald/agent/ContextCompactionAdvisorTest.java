package com.herald.agent;

import com.herald.memory.MemoryTools;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class ContextCompactionAdvisorTest {

    private ChatMemory chatMemory;
    private MemoryTools memoryTools;
    private CallAdvisorChain chain;
    private ChatClientRequest request;
    private ChatClientResponse response;

    @BeforeEach
    void setUp() {
        chatMemory = mock(ChatMemory.class);
        memoryTools = mock(MemoryTools.class);
        chain = mock(CallAdvisorChain.class);
        request = mock(ChatClientRequest.class);
        response = mock(ChatClientResponse.class);

        when(request.context()).thenReturn(Map.of("chat_memory_conversation_id", "default"));
        when(chain.nextCall(any())).thenReturn(response);
        when(memoryTools.memory_set(anyString(), anyString())).thenReturn("Stored");
    }

    @Test
    void doesNotCompactWhenUnderCeiling() {
        // 100 tokens max, 80% ceiling = 80 tokens
        var advisor = new ContextCompactionAdvisor(chatMemory, memoryTools, null, 100);

        // Each message ~6 tokens (24 chars / 4), total ~12 tokens — well under ceiling
        List<Message> history = List.of(
                new UserMessage("Hello, how are you today?"),
                new AssistantMessage("I'm doing well, thanks!"));
        when(chatMemory.get("default")).thenReturn(history);

        advisor.adviseCall(request, chain);

        verify(chatMemory, never()).clear(anyString());
        verify(memoryTools, never()).memory_set(anyString(), anyString());
        verify(chain).nextCall(request);
    }

    @Test
    void compactsWhenOverCeiling() {
        // Small token limit so our test messages exceed the 80% ceiling
        var advisor = new ContextCompactionAdvisor(chatMemory, memoryTools, null, 50);

        // Each message is ~25 chars = ~6 tokens, 4 messages = ~24 tokens
        // But let's use longer messages to be clearly over 80% of 50 = 40 tokens
        String longText = "a".repeat(200); // 50 tokens each
        List<Message> history = new ArrayList<>(List.of(
                new UserMessage(longText),
                new AssistantMessage(longText),
                new UserMessage("Recent message"),
                new AssistantMessage("Recent reply")));
        when(chatMemory.get("default")).thenReturn(history);

        advisor.adviseCall(request, chain);

        // Should have cleared and re-added remaining messages (summary archived to Obsidian or hot memory)
        verify(chatMemory).clear("default");
        verify(chatMemory).add(eq("default"), anyList());
        verify(chain).nextCall(request);
    }

    @Test
    void summaryContainsMessageContent() {
        var advisor = new ContextCompactionAdvisor(chatMemory, memoryTools, null, 20);

        String longText = "a".repeat(200);
        List<Message> history = new ArrayList<>(List.of(
                new UserMessage("What is the weather?"),
                new AssistantMessage(longText),
                new UserMessage("Thanks"),
                new AssistantMessage("You're welcome")));
        when(chatMemory.get("default")).thenReturn(history);

        advisor.adviseCall(request, chain);

        // Should have compacted — verify clear + re-add
        verify(chatMemory).clear("default");
        verify(chatMemory).add(eq("default"), anyList());
    }

    @Test
    void keepsAtLeastTwoMessages() {
        // Even if all messages exceed the ceiling, keep the last 2
        var advisor = new ContextCompactionAdvisor(chatMemory, memoryTools, null, 10);

        String longText = "a".repeat(200);
        List<Message> history = new ArrayList<>(List.of(
                new UserMessage(longText),
                new AssistantMessage(longText)));
        when(chatMemory.get("default")).thenReturn(history);

        advisor.adviseCall(request, chain);

        // Should not compact — can't remove any messages while keeping at least 2
        verify(chatMemory, never()).clear(anyString());
    }

    @Test
    void estimateTokensReturnsZeroForEmpty() {
        assertThat(ContextCompactionAdvisor.estimateTokens(Collections.emptyList())).isZero();
        assertThat(ContextCompactionAdvisor.estimateTokens(null)).isZero();
    }

    @Test
    void estimateTokensCalculatesFromCharLength() {
        List<Message> messages = List.of(
                new UserMessage("a".repeat(400)),  // 100 tokens
                new AssistantMessage("b".repeat(200)));  // 50 tokens

        assertThat(ContextCompactionAdvisor.estimateTokens(messages)).isEqualTo(150);
    }

    @Test
    void ceilingRatioIsEightyPercent() {
        assertThat(ContextCompactionAdvisor.CEILING_RATIO).isEqualTo(0.8);
    }

    @Test
    void nameReturnsExpectedValue() {
        var advisor = new ContextCompactionAdvisor(chatMemory, memoryTools, null, 100);
        assertThat(advisor.getName()).isEqualTo("ContextCompactionAdvisor");
    }

    @Test
    void usesDefaultConversationIdWhenNotInContext() {
        var advisor = new ContextCompactionAdvisor(chatMemory, memoryTools, null, 100);
        when(request.context()).thenReturn(Collections.emptyMap());
        when(chatMemory.get("default")).thenReturn(Collections.emptyList());

        advisor.adviseCall(request, chain);

        verify(chatMemory).get("default");
    }
}
