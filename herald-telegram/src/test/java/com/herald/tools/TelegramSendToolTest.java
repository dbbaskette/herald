package com.herald.tools;

import com.herald.telegram.TelegramSender;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class TelegramSendToolTest {

    @SuppressWarnings("unchecked")
    private ObjectProvider<TelegramSender> providerOf(TelegramSender sender) {
        ObjectProvider<TelegramSender> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(sender);
        return provider;
    }

    @Test
    void telegramSendDelegatesToSender() {
        TelegramSender sender = mock(TelegramSender.class);

        TelegramSendTool tool = new TelegramSendTool(providerOf(sender));

        String result = tool.telegram_send("hello");

        assertThat(result).isEqualTo("Message sent.");
        verify(sender).sendMessage("hello");
    }

    @Test
    void telegramSendReturnsErrorWhenSenderNotConfigured() {
        TelegramSendTool tool = new TelegramSendTool(providerOf(null));

        String result = tool.telegram_send("hello");

        assertThat(result).contains("ERROR").contains("not configured");
    }
}
