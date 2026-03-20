package com.herald.agent;

import com.herald.tools.TodoProgressEvent;
import org.junit.jupiter.api.Test;
import org.springaicommunity.agent.tools.TodoWriteTool;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ConsoleTodoProgressListenerTest {

    @Test
    void printsTodoProgressToStdout() {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(baos);
        var listener = new ConsoleTodoProgressListener(out);

        // Create a TodoProgressEvent with summary text
        var todos = new TodoWriteTool.Todos(List.of(
                new TodoWriteTool.Todos.TodoItem("Analyze repo", TodoWriteTool.Todos.Status.completed, ""),
                new TodoWriteTool.Todos.TodoItem("Write tests", TodoWriteTool.Todos.Status.in_progress, "")
        ));
        var event = new TodoProgressEvent(todos, "✓ Analyze repo\n▶ Write tests\n");

        listener.onTodoProgress(event);

        String output = baos.toString();
        assertThat(output).contains("Analyze repo");
        assertThat(output).contains("Write tests");
    }
}
