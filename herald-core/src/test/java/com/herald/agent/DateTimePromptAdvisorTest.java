package com.herald.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.ai.chat.client.ChatClientResponse;
import org.springframework.ai.chat.client.advisor.api.CallAdvisorChain;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.Ordered;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class DateTimePromptAdvisorTest {

    private static final ZoneId TIMEZONE = ZoneId.of("America/New_York");
    private static final DateTimeFormatter FORMAT =
            DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy 'at' h:mm a z");

    @Test
    void resolvesDatetimePlaceholderPerTurn() {
        var advisor = new DateTimePromptAdvisor(TIMEZONE, FORMAT);

        Prompt prompt = new Prompt(new SystemMessage("Current time: {current_datetime}, zone: {timezone}"));
        ChatClientRequest request = new ChatClientRequest(prompt, Map.of());

        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenAnswer(inv -> mock(ChatClientResponse.class));

        advisor.adviseCall(request, chain);

        verify(chain).nextCall(argThat(req -> {
            String text = req.prompt().getSystemMessage().getText();
            return !text.contains("{current_datetime}")
                    && !text.contains("{timezone}")
                    && text.contains("America/New_York");
        }));
    }

    @Test
    void preservesTextWithoutPlaceholders() {
        var advisor = new DateTimePromptAdvisor(TIMEZONE, FORMAT);

        Prompt prompt = new Prompt(new SystemMessage("You are Herald. No placeholders here."));
        ChatClientRequest request = new ChatClientRequest(prompt, Map.of());

        CallAdvisorChain chain = mock(CallAdvisorChain.class);
        when(chain.nextCall(any())).thenAnswer(inv -> mock(ChatClientResponse.class));

        advisor.adviseCall(request, chain);

        verify(chain).nextCall(argThat(req -> {
            String text = req.prompt().getSystemMessage().getText();
            return text.equals("You are Herald. No placeholders here.");
        }));
    }

    @Test
    void runsBeforeMemoryBlockAdvisor() {
        var advisor = new DateTimePromptAdvisor(TIMEZONE, FORMAT);

        // MemoryBlockAdvisor (in herald-persistence) uses HIGHEST_PRECEDENCE + 100
        assertThat(advisor.getOrder()).isLessThan(Ordered.HIGHEST_PRECEDENCE + 100);
    }

    @Test
    void nameIsCorrect() {
        var advisor = new DateTimePromptAdvisor(TIMEZONE, FORMAT);
        assertThat(advisor.getName()).isEqualTo("DateTimePromptAdvisor");
    }

    @Test
    void orderIsHighPrecedence() {
        var advisor = new DateTimePromptAdvisor(TIMEZONE, FORMAT);
        assertThat(advisor.getOrder()).isEqualTo(Ordered.HIGHEST_PRECEDENCE + 50);
    }
}
