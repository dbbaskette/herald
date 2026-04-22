package com.herald.agent;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.definition.ToolDefinition;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LoggingMemoryToolCallbackTest {

    @TempDir
    Path tempDir;

    @Test
    void logsCreateEventWithPathExtractedFromJson() throws IOException {
        Path logFile = tempDir.resolve("log.md");
        ToolCallback delegate = mock(ToolCallback.class);
        when(delegate.getToolDefinition()).thenReturn(ToolDefinition.builder()
                .name("MemoryCreate").description("").inputSchema("{}").build());
        when(delegate.call(any())).thenReturn("ok");

        var cb = new LoggingMemoryToolCallback(delegate, logFile);
        cb.call("{\"path\":\"user_role.md\",\"content\":\"hi\"}");

        verify(delegate).call(any());
        List<String> lines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
        assertThat(lines).hasSize(1);
        assertThat(lines.get(0))
                .contains("CREATE")
                .contains("tool=MemoryCreate")
                .contains("path=user_role.md");
    }

    @Test
    void eventNameMapsKnownTools() {
        assertThat(LoggingMemoryToolCallback.eventNameFor("MemoryCreate")).isEqualTo("CREATE");
        assertThat(LoggingMemoryToolCallback.eventNameFor("MemoryStrReplace")).isEqualTo("STRREPLACE");
        assertThat(LoggingMemoryToolCallback.eventNameFor("MemoryInsert")).isEqualTo("INSERT");
        assertThat(LoggingMemoryToolCallback.eventNameFor("MemoryDelete")).isEqualTo("DELETE");
        assertThat(LoggingMemoryToolCallback.eventNameFor("MemoryRename")).isEqualTo("RENAME");
    }

    @Test
    void isMutatingMemoryToolGatesDecorator() {
        assertThat(LoggingMemoryToolCallback.isMutatingMemoryTool("MemoryView")).isFalse();
        assertThat(LoggingMemoryToolCallback.isMutatingMemoryTool("MemoryCreate")).isTrue();
        assertThat(LoggingMemoryToolCallback.isMutatingMemoryTool("MemoryDelete")).isTrue();
    }

    @Test
    void extractPathHandlesOldPathAndEscapes() {
        assertThat(LoggingMemoryToolCallback.extractPath("{\"old_path\":\"a.md\",\"new_path\":\"b.md\"}"))
                .isEqualTo("a.md");
        assertThat(LoggingMemoryToolCallback.extractPath("{\"path\":\"weird\\\"name.md\"}"))
                .isEqualTo("weird\"name.md");
        assertThat(LoggingMemoryToolCallback.extractPath("no json here")).isNull();
    }
}
