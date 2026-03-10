package com.herald.tools;

import com.herald.telegram.TelegramSender;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Tool for sending proactive messages to the Telegram chat.
 * Used by cron job executions and also available in regular conversations.
 */
@Component
public class TelegramSendTool {

    private final TelegramSender telegramSender;

    public TelegramSendTool(ObjectProvider<TelegramSender> telegramSenderProvider) {
        this.telegramSender = telegramSenderProvider.getIfAvailable();
    }

    @Tool(name = "telegram_send", description = "Send a proactive message to the Telegram chat")
    public String telegram_send(
            @ToolParam(description = "The message to send to the Telegram chat") String message) {
        if (telegramSender == null) {
            return "ERROR: TelegramSender is not configured — cannot send message.";
        }
        telegramSender.sendMessage(message);
        return "Message sent.";
    }
}
