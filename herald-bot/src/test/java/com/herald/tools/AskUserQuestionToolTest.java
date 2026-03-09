package com.herald.tools;

import com.herald.telegram.TelegramQuestionHandler;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AskUserQuestionToolTest {

    @Test
    void askUserDelegatesToQuestionHandler() {
        TelegramQuestionHandler handler = mock(TelegramQuestionHandler.class);
        when(handler.askQuestion("Which calendar?")).thenReturn("Work");

        AskUserQuestionTool tool = new AskUserQuestionTool(Optional.of(handler));

        String result = tool.ask_user("Which calendar?");

        assertThat(result).isEqualTo("Work");
        verify(handler).askQuestion("Which calendar?");
    }

    @Test
    void askUserReturnsTimeoutMessageOnEmptyAnswer() {
        TelegramQuestionHandler handler = mock(TelegramQuestionHandler.class);
        when(handler.askQuestion("Which calendar?")).thenReturn("");

        AskUserQuestionTool tool = new AskUserQuestionTool(Optional.of(handler));

        String result = tool.ask_user("Which calendar?");

        assertThat(result).contains("TIMEOUT");
        assertThat(result).contains("Which calendar?");
    }

    @Test
    void askUserReturnsPendingMessageWhenHandlerNotAvailable() {
        AskUserQuestionTool tool = new AskUserQuestionTool(Optional.empty());

        String result = tool.ask_user("Which calendar?");

        assertThat(result).contains("PENDING");
        assertThat(result).contains("Which calendar?");
    }
}
