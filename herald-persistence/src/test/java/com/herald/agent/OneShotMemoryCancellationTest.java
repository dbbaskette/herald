package com.herald.agent;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.StreamAdvisorChain;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class OneShotMemoryCancellationTest {
    @Test void cancelledStreamOnAnotherSchedulerDoesNotSuppressNextConversationMemory() {
        var memory = mock(ChatMemory.class);
        when(memory.get(anyString())).thenReturn(List.of());
        var advisor = new OneShotMemoryAdvisor(memory, 20);
        var chain = mock(StreamAdvisorChain.class);
        when(chain.nextStream(any())).thenReturn(Flux.never());
        var cancelled = ChatClientRequest.builder().prompt(new Prompt("cancel"))
                .context("chat_memory_conversation_id", "web-cancelled").build();
        var subscription = advisor.adviseStream(cancelled, chain).subscribe();
        Schedulers.boundedElastic().schedule(subscription::dispose);
        var next = ChatClientRequest.builder().prompt(new Prompt("next"))
                .context("chat_memory_conversation_id", "web-next").build();
        when(chain.nextStream(any())).thenReturn(Flux.just(ChatClientResponse.builder()
                .chatResponse(new ChatResponse(List.of(new Generation(new AssistantMessage("done"))))).build()));
        advisor.adviseStream(next, chain).blockLast(Duration.ofSeconds(2));
        verify(memory).get("web-cancelled");
        verify(memory).get("web-next");
        verify(memory, never()).add(eq("web-cancelled"), anyList());
        verify(memory).add(eq("web-next"), anyList());
    }
}
