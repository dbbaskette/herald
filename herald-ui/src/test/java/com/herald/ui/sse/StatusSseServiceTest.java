package com.herald.ui.sse;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class StatusSseServiceTest {
    @TempDir Path directory;

    private StatusSseService service(JdbcTemplate jdbc) {
        var service = spy(new StatusSseService(jdbc, 1, directory.toString(), ""));
        doReturn(false).when(service).checkBotHealth(); // Never contact a live bot.
        return service;
    }

    @Test void reportsUnknownTelemetryAndFailedReadsInsteadOfClaimingEmpty() {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenThrow(new IllegalStateException("database unavailable"));
        when(jdbc.queryForList(anyString())).thenThrow(new IllegalStateException("database unavailable"));
        var snapshot = service(jdbc).buildStatus();
        var capabilities = (Map<?, ?>) snapshot.get("capabilities");
        assertThat(((Map<?, ?>) capabilities.get("mcp")).get("state")).isEqualTo("unknown");
        assertThat(((Map<?, ?>) capabilities.get("memory")).get("state")).isEqualTo("failed");
        assertThat(((Map<?, ?>) capabilities.get("cron")).get("state")).isEqualTo("failed");
    }

    @Test void reportsConcreteCronAndAvailableObservedEmptyMemory() throws Exception {
        var jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(0);
        when(jdbc.queryForList(anyString())).thenReturn(List.of());
        when(jdbc.queryForList(contains("LEFT JOIN cron_execution"))).thenReturn(List.of(Map.of(
                "name", "morning", "last_run", "2026-09-09T10:00:00Z", "last_result", "completed")));
        Files.createDirectories(directory.resolve("example"));
        Files.writeString(directory.resolve("example/SKILL.md"), "example");
        var snapshot = service(jdbc).buildStatus();
        var jobs = (List<Map<String, Object>>) snapshot.get("cron");
        assertThat(jobs).hasSize(1);
        assertThat(jobs.getFirst()).containsEntry("name", "morning").containsEntry("lastResult", "completed").containsEntry("nextRun", null);
        assertThat(((Map<?, ?>) ((Map<?, ?>) snapshot.get("capabilities")).get("memory")).get("state")).isEqualTo("available");
        assertThat(((Map<?, ?>) snapshot.get("skills")).get("totalLoaded")).isEqualTo(1);
    }
}
