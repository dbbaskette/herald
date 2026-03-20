package com.herald.tools;

import org.springframework.context.ApplicationEvent;

/**
 * Published whenever a TodoWriteTool operation changes the task list.
 * Listeners (e.g. the Telegram transport) can forward these as status messages.
 */
public class TodoProgressEvent extends ApplicationEvent {

    private final String summary;

    public TodoProgressEvent(Object source, String summary) {
        super(source);
        this.summary = summary;
    }

    public String getSummary() {
        return summary;
    }
}
