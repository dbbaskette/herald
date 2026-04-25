package com.herald.agent;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClientRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class DailyConsolidationTriggerTest {

    private JdbcTemplate jdbc;
    private DailyConsolidationTrigger trigger;
    private ChatClientRequest request;

    @BeforeEach
    void setUp() {
        jdbc = mock(JdbcTemplate.class);
        trigger = new DailyConsolidationTrigger(jdbc);
        request = mock(ChatClientRequest.class);
    }

    @SuppressWarnings("unchecked")
    @Test
    void firesWhenNoPreviousRunRecorded() {
        when(jdbc.queryForList(anyString(), eq(String.class), any()))
                .thenReturn(List.of());

        boolean fired = trigger.test(request, Instant.parse("2026-04-23T12:00:00Z"));

        assertThat(fired).isTrue();
        verify(jdbc).update(anyString(), eq("memory.consolidation.last-fired-ymd"), eq("2026-04-23"));
    }

    @SuppressWarnings("unchecked")
    @Test
    void suppressesSecondFireSameUtcDay() {
        when(jdbc.queryForList(anyString(), eq(String.class), any()))
                .thenReturn(List.of("2026-04-23"));

        boolean fired = trigger.test(request, Instant.parse("2026-04-23T23:59:59Z"));

        assertThat(fired).isFalse();
        verify(jdbc, never()).update(anyString(), any(), any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void firesAgainAfterUtcDayRollsOver() {
        when(jdbc.queryForList(anyString(), eq(String.class), any()))
                .thenReturn(List.of("2026-04-22"));

        boolean fired = trigger.test(request, Instant.parse("2026-04-23T00:00:01Z"));

        assertThat(fired).isTrue();
        verify(jdbc).update(anyString(), eq("memory.consolidation.last-fired-ymd"), eq("2026-04-23"));
    }

    @Test
    void returnsFalseWhenJdbcTemplateMissing() {
        DailyConsolidationTrigger nullJdbc = new DailyConsolidationTrigger(null);

        boolean fired = nullJdbc.test(request, Instant.parse("2026-04-23T12:00:00Z"));

        assertThat(fired).isFalse();
    }

    @Test
    void returnsFalseWhenWhenIsNull() {
        boolean fired = trigger.test(request, null);

        assertThat(fired).isFalse();
        verifyNoInteractions(jdbc);
    }

    @SuppressWarnings("unchecked")
    @Test
    void returnsFalseWhenReadThrows() {
        when(jdbc.queryForList(anyString(), eq(String.class), any()))
                .thenThrow(new RuntimeException("db offline"));

        boolean fired = trigger.test(request, Instant.parse("2026-04-23T12:00:00Z"));

        assertThat(fired).isFalse();
        verify(jdbc, never()).update(anyString(), any(), any());
    }

    @SuppressWarnings("unchecked")
    @Test
    void returnsFalseWhenWriteThrows() {
        when(jdbc.queryForList(anyString(), eq(String.class), any()))
                .thenReturn(List.of());
        when(jdbc.update(anyString(), eq("memory.consolidation.last-fired-ymd"), anyString()))
                .thenThrow(new RuntimeException("write failed"));

        boolean fired = trigger.test(request, Instant.parse("2026-04-23T12:00:00Z"));

        assertThat(fired).isFalse();
    }
}
