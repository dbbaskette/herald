package com.herald.tools;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class TodoWriteToolTest {

    private ApplicationEventPublisher publisher;
    private TodoWriteTool tool;

    @BeforeEach
    void setUp() {
        publisher = mock(ApplicationEventPublisher.class);
        tool = new TodoWriteTool(publisher);
    }

    @Test
    void addPublishesProgressEvent() {
        String result = tool.todo_add("Write tests");

        assertThat(result).contains("Write tests");
        verify(publisher).publishEvent(any(TodoProgressEvent.class));
    }

    @Test
    void completePublishesProgressEvent() {
        tool.todo_add("Write tests");
        reset(publisher);

        String result = tool.todo_complete(1);

        assertThat(result).contains("Write tests");
        verify(publisher).publishEvent(any(TodoProgressEvent.class));
    }

    @Test
    void completeWithInvalidIndexDoesNotPublish() {
        String result = tool.todo_complete(5);

        assertThat(result).contains("ERROR");
        verify(publisher, never()).publishEvent(any(TodoProgressEvent.class));
    }
}
