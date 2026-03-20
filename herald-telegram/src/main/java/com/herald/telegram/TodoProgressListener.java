package com.herald.telegram;

import com.herald.tools.TodoProgressEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * Forwards TodoWriteTool progress events to Telegram as status messages.
 */
@Component
@ConditionalOnProperty("herald.telegram.bot-token")
public class TodoProgressListener {

    private static final Logger log = LoggerFactory.getLogger(TodoProgressListener.class);

    private final TelegramSender sender;

    public TodoProgressListener(TelegramSender sender) {
        this.sender = sender;
    }

    @EventListener
    void onTodoProgress(TodoProgressEvent event) {
        log.debug("Forwarding todo progress to Telegram: {}", event.getSummary());
        sender.sendMessage(event.getSummary());
    }
}
