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
        agentMetrics.recordTurn("anthropic", "claude-sonnet-4-5", 100, 50, 200, List.of(), null);

        assertThat(meterRegistry.counter("herald.agent.turns").count()).isEqualTo(1.0);
    }

    @Test
    void recordTurnIncrementsTaggedTokenCounters() {
        agentMetrics.recordTurn("anthropic", "claude-sonnet-4-5", 100, 50, 200, List.of(), null);

        assertThat(meterRegistry.counter("herald.tokens.in", "provider", "anthropic", "model", "claude-sonnet-4-5").count())
                .isEqualTo(100.0);
        assertThat(meterRegistry.counter("herald.tokens.out", "provider", "anthropic", "model", "claude-sonnet-4-5").count())
                .isEqualTo(50.0);
    }

    @Test
    void recordTurnRecordsLatency() {
        agentMetrics.recordTurn("anthropic", "claude-sonnet-4-5", 100, 50, 200, List.of(), null);

        assertThat(meterRegistry.timer("herald.agent.latency").count()).isEqualTo(1);
        assertThat(meterRegistry.timer("herald.agent.latency").totalTime(java.util.concurrent.TimeUnit.MILLISECONDS))
                .isEqualTo(200.0);
    }

    @Test
    void recordTurnPersistsToDatabaseWithSubagentId() {
        agentMetrics.recordTurn("anthropic", "claude-sonnet-4-5", 100, 50, 200, List.of("memory_get"), null);

        verify(jdbcTemplate).update(
                "INSERT INTO model_usage (subagent_id, provider, model, tokens_in, tokens_out) VALUES (?, ?, ?, ?, ?)",
                null, "anthropic", "claude-sonnet-4-5", 100L, 50L);
    }

    @Test
    void recordTurnPersistsSubagentId() {
        agentMetrics.recordTurn("anthropic", "claude-haiku-4-5", 50, 20, 100, List.of(), "research-agent");

        verify(jdbcTemplate).update(
                "INSERT INTO model_usage (subagent_id, provider, model, tokens_in, tokens_out) VALUES (?, ?, ?, ?, ?)",
                "research-agent", "anthropic", "claude-haiku-4-5", 50L, 20L);
    }

    @Test
    void recordTurnAccumulatesAcrossMultipleCalls() {
        agentMetrics.recordTurn("anthropic", "claude-sonnet-4-5", 100, 50, 200, List.of(), null);
        agentMetrics.recordTurn("anthropic", "claude-sonnet-4-5", 200, 100, 300, List.of(), null);

        assertThat(meterRegistry.counter("herald.agent.turns").count()).isEqualTo(2.0);
        assertThat(meterRegistry.counter("herald.tokens.in", "provider", "anthropic", "model", "claude-sonnet-4-5").count())
                .isEqualTo(300.0);
        assertThat(meterRegistry.counter("herald.tokens.out", "provider", "anthropic", "model", "claude-sonnet-4-5").count())
                .isEqualTo(150.0);
    }

    @Test
    void recordTurnTracksDifferentModelsWithSeparateCounters() {
        agentMetrics.recordTurn("anthropic", "claude-sonnet-4-5", 100, 50, 200, List.of(), null);
        agentMetrics.recordTurn("openai", "gpt-4o", 80, 40, 150, List.of(), null);

        assertThat(meterRegistry.counter("herald.tokens.in", "provider", "anthropic", "model", "claude-sonnet-4-5").count())
                .isEqualTo(100.0);
        assertThat(meterRegistry.counter("herald.tokens.in", "provider", "openai", "model", "gpt-4o").count())
                .isEqualTo(80.0);
    }

    @Test
    void deriveProviderFromAnthropicModel() {
        assertThat(AgentMetrics.deriveProvider("claude-sonnet-4-5")).isEqualTo("anthropic");
        assertThat(AgentMetrics.deriveProvider("claude-haiku-4-5")).isEqualTo("anthropic");
        assertThat(AgentMetrics.deriveProvider("claude-opus-4-5")).isEqualTo("anthropic");
    }

    @Test
    void deriveProviderFromOpenAiModel() {
        assertThat(AgentMetrics.deriveProvider("gpt-4o")).isEqualTo("openai");
        assertThat(AgentMetrics.deriveProvider("gpt-4o-mini")).isEqualTo("openai");
        assertThat(AgentMetrics.deriveProvider("o1")).isEqualTo("openai");
        assertThat(AgentMetrics.deriveProvider("o3-mini")).isEqualTo("openai");
    }

    @Test
    void deriveProviderFromOllamaModel() {
        assertThat(AgentMetrics.deriveProvider("llama3.2")).isEqualTo("ollama");
        assertThat(AgentMetrics.deriveProvider("mistral")).isEqualTo("ollama");
        assertThat(AgentMetrics.deriveProvider("phi")).isEqualTo("ollama");
    }

    @Test
    void deriveProviderReturnsUnknownForBlank() {
        assertThat(AgentMetrics.deriveProvider("")).isEqualTo("unknown");
        assertThat(AgentMetrics.deriveProvider(null)).isEqualTo("unknown");
    }
}
