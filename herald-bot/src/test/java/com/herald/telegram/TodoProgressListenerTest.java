package com.herald.telegram;

import com.herald.tools.TodoProgressEvent;
import org.junit.jupiter.api.Test;

import static org.mockito.Mockito.*;

class TodoProgressListenerTest {

    @Test
    void forwardsEventSummaryToTelegram() {
        TelegramSender sender = mock(TelegramSender.class);
        TodoProgressListener listener = new TodoProgressListener(sender);

        listener.onTodoProgress(new TodoProgressEvent(this, "Added TODO: deploy app"));

        verify(sender).sendMessage("Added TODO: deploy app");
    }
}
