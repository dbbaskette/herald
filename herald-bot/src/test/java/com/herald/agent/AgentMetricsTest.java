package com.herald.agent;

import java.util.List;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class AgentMetricsTest {

    private MeterRegistry meterRegistry;
    private JdbcTemplate jdbcTemplate;
    private AgentMetrics agentMetrics;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        jdbcTemplate = mock(JdbcTemplate.class);
        agentMetrics = new AgentMetrics(meterRegistry, jdbcTemplate);
    }

    @Test
    void recordTurnIncrementsTurnsCounter() {
        agentMetrics.recordTurn("claude-sonnet-4-5", 100, 50, 200, List.of());

        assertThat(meterRegistry.counter("herald.agent.turns").count()).isEqualTo(1.0);
    }

    @Test
    void recordTurnIncrementsTokenCounters() {
        agentMetrics.recordTurn("claude-sonnet-4-5", 100, 50, 200, List.of());

        assertThat(meterRegistry.counter("herald.tokens.total", "direction", "in").count())
                .isEqualTo(100.0);
        assertThat(meterRegistry.counter("herald.tokens.total", "direction", "out").count())
                .isEqualTo(50.0);
    }

    @Test
    void recordTurnRecordsLatency() {
        agentMetrics.recordTurn("claude-sonnet-4-5", 100, 50, 200, List.of());

        assertThat(meterRegistry.timer("herald.agent.latency").count()).isEqualTo(1);
        assertThat(meterRegistry.timer("herald.agent.latency").totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isEqualTo(200.0);
    }

    @Test
    void recordTurnPersistsToDatabase() {
        agentMetrics.recordTurn("claude-sonnet-4-5", 100, 50, 200, List.of("memory_get"));

        verify(jdbcTemplate).update(
                "INSERT INTO model_usage (provider, model, tokens_in, tokens_out) VALUES (?, ?, ?, ?)",
                "anthropic", "claude-sonnet-4-5", 100L, 50L);
    }

    @Test
    void recordTurnAccumulatesAcrossMultipleCalls() {
        agentMetrics.recordTurn("claude-sonnet-4-5", 100, 50, 200, List.of());
        agentMetrics.recordTurn("claude-sonnet-4-5", 200, 100, 300, List.of());

        assertThat(meterRegistry.counter("herald.agent.turns").count()).isEqualTo(2.0);
        assertThat(meterRegistry.counter("herald.tokens.total", "direction", "in").count())
                .isEqualTo(300.0);
        assertThat(meterRegistry.counter("herald.tokens.total", "direction", "out").count())
                .isEqualTo(150.0);
    }
}
