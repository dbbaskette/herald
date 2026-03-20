package com.herald.agent;

import com.herald.tools.TodoProgressEvent;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.PrintStream;

/**
 * Prints {@link TodoProgressEvent} summaries to stdout when no Telegram transport is configured.
 * When herald-telegram is on the classpath and configured, its {@code TodoProgressListener}
 * is registered as a {@link MessageSender} bean, and this console fallback is skipped.
 */
@Component
@ConditionalOnMissingBean(MessageSender.class)
public class ConsoleTodoProgressListener {

    private final PrintStream out;

    public ConsoleTodoProgressListener() {
        this(System.out);
    }

    ConsoleTodoProgressListener(PrintStream out) {
        this.out = out;
    }

    @EventListener
    public void onTodoProgress(TodoProgressEvent event) {
        out.print(event.getSummary());
    }
}
