package com.herald.agent;

import com.herald.memory.MemoryTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class MemoryBlockAdvisorTest {

    @Test
    void augmentsSystemMessageWithMemoryBlock() {
        MemoryTools memoryTools = mock(MemoryTools.class);
        when(memoryTools.formatForSystemPrompt()).thenReturn("## Known Facts\n- **name**: Dan\n");

        MemoryBlockAdvisor advisor = new MemoryBlockAdvisor(memoryTools);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenAnswer(inv -> mock(ChatClientResponse.class));

        Prompt prompt = new Prompt(new SystemMessage("You are Herald."));
        ChatClientRequest request = new ChatClientRequest(prompt, Map.of());

        advisor.adviseCall(request, chain);

        verify(chain).nextCall(argThat(req -> {
            String systemText = req.prompt().getSystemMessage().getText();
            return systemText.contains("You are Herald.") && systemText.contains("Known Facts");
        }));
    }

    @Test
    void skipsAugmentationWhenMemoryEmpty() {
        MemoryTools memoryTools = mock(MemoryTools.class);
        when(memoryTools.formatForSystemPrompt()).thenReturn("");

        MemoryBlockAdvisor advisor = new MemoryBlockAdvisor(memoryTools);
        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenAnswer(inv -> mock(ChatClientResponse.class));

        Prompt prompt = new Prompt(new SystemMessage("You are Herald."));
        ChatClientRequest request = new ChatClientRequest(prompt, Map.of());

        advisor.adviseCall(request, chain);

        // Original request passed through unchanged
        verify(chain).nextCall(request);
    }

    @Test
    void nameIsMemoryBlockAdvisor() {
        MemoryTools memoryTools = mock(MemoryTools.class);
        MemoryBlockAdvisor advisor = new MemoryBlockAdvisor(memoryTools);

        assertThat(advisor.getName()).isEqualTo("MemoryBlockAdvisor");
    }

    @Test
    void orderIsHighPrecedence() {
        MemoryTools memoryTools = mock(MemoryTools.class);
        MemoryBlockAdvisor advisor = new MemoryBlockAdvisor(memoryTools);

        assertThat(advisor.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 100);
    }
}
