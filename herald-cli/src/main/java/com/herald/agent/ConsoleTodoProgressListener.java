package com.herald.agent;

import com.herald.tools.TodoProgressEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.io.PrintStream;

/**
 * Prints {@link TodoProgressEvent} summaries to stdout in CLI (ephemeral) mode.
 */
@Component
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
