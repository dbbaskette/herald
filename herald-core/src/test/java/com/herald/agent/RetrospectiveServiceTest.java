package com.herald.agent;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RetrospectiveServiceTest {

    private static final String TEMPLATE =
            "Explain this turn.\nUser said: {{user_message}}\nYou responded: {{assistant_response}}";

    @Test
    void findLastPairReturnsLatestUserAssistantPair() {
        ChatMemory memory = mock(ChatMemory.class);
        List<Message> history = List.of(
                new UserMessage("old question"),
                new AssistantMessage("old answer"),
                new UserMessage("catch me up"),
                new AssistantMessage("here's what happened"));
        when(memory.get(anyString())).thenReturn(history);

        var service = new RetrospectiveService(memory, mock(ModelSwitcher.class), TEMPLATE);
        var pair = service.findLastPair("default");

        assertThat(pair).isNotNull();
        assertThat(pair.userText()).isEqualTo("catch me up");
        assertThat(pair.assistantText()).isEqualTo("here's what happened");
    }

    @Test
    void findLastPairSkipsBlankAssistantMessages() {
        // Tool-call assistant messages can have empty text bodies. Walk past them.
        ChatMemory memory = mock(ChatMemory.class);
        List<Message> history = List.of(
                new UserMessage("run X"),
                new AssistantMessage("running X..."),
                new AssistantMessage(""));  // blank tool-call shell
        when(memory.get(anyString())).thenReturn(history);

        var service = new RetrospectiveService(memory, mock(ModelSwitcher.class), TEMPLATE);
        var pair = service.findLastPair("default");

        assertThat(pair).isNotNull();
        assertThat(pair.assistantText()).isEqualTo("running X...");
    }

    @Test
    void findLastPairReturnsNullOnEmptyHistory() {
        ChatMemory memory = mock(ChatMemory.class);
        when(memory.get(anyString())).thenReturn(List.of());

        var service = new RetrospectiveService(memory, mock(ModelSwitcher.class), TEMPLATE);

        assertThat(service.findLastPair("default")).isNull();
    }

    @Test
    void findLastPairReturnsNullWhenOnlyAssistantMessages() {
        ChatMemory memory = mock(ChatMemory.class);
        when(memory.get(anyString())).thenReturn(List.of(
                new AssistantMessage("hello")));

        var service = new RetrospectiveService(memory, mock(ModelSwitcher.class), TEMPLATE);

        assertThat(service.findLastPair("default")).isNull();
    }

    @Test
    void renderPromptSubstitutesPlaceholders() {
        var service = new RetrospectiveService(
                mock(ChatMemory.class), mock(ModelSwitcher.class), TEMPLATE);

        String rendered = service.renderPrompt("what's up", "lots");

        assertThat(rendered).contains("User said: what's up");
        assertThat(rendered).contains("You responded: lots");
        assertThat(rendered).doesNotContain("{{");
    }

    @Test
    void explainLastTurnEmitsFriendlyMessageWhenNoPriorTurn() {
        ChatMemory memory = mock(ChatMemory.class);
        when(memory.get(anyString())).thenReturn(List.of());

        var service = new RetrospectiveService(memory, mock(ModelSwitcher.class), TEMPLATE);

        List<String> chunks = service.explainLastTurn("default").collectList().block();
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0)).contains("No prior turn");
    }
}
