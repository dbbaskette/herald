package com.herald.tools;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Tool for managing a task list during multi-step operations.
 * Stub implementation until spring-ai-agent-utils provides the canonical version.
 */
@Component
public class TodoWriteTool {

    private final CopyOnWriteArrayList<TodoItem> items = new CopyOnWriteArrayList<>();
    private final ApplicationEventPublisher eventPublisher;

    public TodoWriteTool(ApplicationEventPublisher eventPublisher) {
        this.eventPublisher = eventPublisher;
    }

    @Tool(description = "Add a new TODO item to the task list.")
    public String todo_add(
            @ToolParam(description = "Description of the task") String task) {
        items.add(new TodoItem(task, false));
        String result = "Added TODO: " + task;
        eventPublisher.publishEvent(new TodoProgressEvent(this, result));
        return result;
    }

    @Tool(description = "Mark a TODO item as complete by its 1-based index.")
    public String todo_complete(
            @ToolParam(description = "1-based index of the task to complete") int index) {
        if (index < 1 || index > items.size()) {
            return "ERROR: Invalid index " + index + ". List has " + items.size() + " items.";
        }
        TodoItem old = items.get(index - 1);
        items.set(index - 1, new TodoItem(old.task(), true));
        String result = "Completed: " + old.task();
        eventPublisher.publishEvent(new TodoProgressEvent(this, result));
        return result;
    }

    @Tool(description = "List all TODO items with their status.")
    public String todo_list() {
        if (items.isEmpty()) {
            return "No TODO items.";
        }
        return IntStream.range(0, items.size())
                .mapToObj(i -> {
                    TodoItem item = items.get(i);
                    return (i + 1) + ". [" + (item.done() ? "x" : " ") + "] " + item.task();
                })
                .collect(Collectors.joining("\n"));
    }

    private record TodoItem(String task, boolean done) {}
}
