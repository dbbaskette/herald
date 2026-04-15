package com.herald.telegram;

import io.modelcontextprotocol.spec.McpSchema.ElicitRequest;
import io.modelcontextprotocol.spec.McpSchema.ElicitResult;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

class TelegramMcpElicitationHandlerTest {

    @Test
    void acceptsWhenUserProvidesAnswer() {
        TelegramQuestionHandler qh = mock(TelegramQuestionHandler.class);
        when(qh.askQuestion(anyString())).thenReturn("large coffee please");

        TelegramMcpElicitationHandler handler = new TelegramMcpElicitationHandler(qh);

        ElicitRequest request = new ElicitRequest("What size coffee?", null);
        ElicitResult result = handler.handleElicitation(request);

        assertThat(result.action()).isEqualTo(ElicitResult.Action.ACCEPT);
        assertThat(result.content()).containsEntry("response", "large coffee please");
    }

    @Test
    void declinesWhenUserTimesOut() {
        TelegramQuestionHandler qh = mock(TelegramQuestionHandler.class);
        when(qh.askQuestion(anyString())).thenReturn("");

        TelegramMcpElicitationHandler handler = new TelegramMcpElicitationHandler(qh);

        ElicitRequest request = new ElicitRequest("Pick a size", null);
        ElicitResult result = handler.handleElicitation(request);

        assertThat(result.action()).isEqualTo(ElicitResult.Action.DECLINE);
        assertThat(result.content()).isNull();
    }

    @Test
    void declinesWhenUserSaysCancel() {
        TelegramQuestionHandler qh = mock(TelegramQuestionHandler.class);
        when(qh.askQuestion(anyString())).thenReturn("cancel");

        TelegramMcpElicitationHandler handler = new TelegramMcpElicitationHandler(qh);

        ElicitRequest request = new ElicitRequest("Choose option", null);
        ElicitResult result = handler.handleElicitation(request);

        assertThat(result.action()).isEqualTo(ElicitResult.Action.DECLINE);
    }

    @Test
    void messageIncludesElicitationText() {
        TelegramQuestionHandler qh = mock(TelegramQuestionHandler.class);
        when(qh.askQuestion(anyString())).thenReturn("Java");

        TelegramMcpElicitationHandler handler = new TelegramMcpElicitationHandler(qh);

        ElicitRequest request = new ElicitRequest("What is your preferred language?", null);
        handler.handleElicitation(request);

        verify(qh).askQuestion(argThat(msg ->
                msg.contains("What is your preferred language?")));
    }
}
