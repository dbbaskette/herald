package com.herald.agent;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Records per-turn metrics via Micrometer, writes structured JSON log entries,
 * and persists token usage to the model_usage table.
 */
@Component
class AgentMetrics {

    private static final Logger log = LoggerFactory.getLogger(AgentMetrics.class);

    private final Counter turnsCounter;
    private final Counter tokensInCounter;
    private final Counter tokensOutCounter;
    private final Timer latencyTimer;
    private final JdbcTemplate jdbcTemplate;

    AgentMetrics(MeterRegistry meterRegistry, JdbcTemplate jdbcTemplate) {
        this.turnsCounter = Counter.builder("herald.agent.turns")
                .description("Number of agent turns processed")
                .register(meterRegistry);
        this.latencyTimer = Timer.builder("herald.agent.latency")
                .description("Agent turn latency")
                .register(meterRegistry);
        this.tokensInCounter = Counter.builder("herald.tokens.total")
                .tag("direction", "in")
                .description("Total input tokens consumed")
                .register(meterRegistry);
        this.tokensOutCounter = Counter.builder("herald.tokens.total")
                .tag("direction", "out")
                .description("Total output tokens produced")
                .register(meterRegistry);
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Record metrics for a completed agent turn.
     */
    void recordTurn(String model, long tokensIn, long tokensOut,
                    long latencyMs, List<String> toolCalls) {
        String turnId = UUID.randomUUID().toString();

        // Micrometer counters
        turnsCounter.increment();
        tokensInCounter.increment(tokensIn);
        tokensOutCounter.increment(tokensOut);
        latencyTimer.record(Duration.ofMillis(latencyMs));

        // Structured JSON log entry
        log.info("""
                {"turn_id":"{}","model":"{}","tokens_in":{},"tokens_out":{},"latency_ms":{},"tool_calls":{}}""",
                turnId, model, tokensIn, tokensOut, latencyMs, toolCalls);

        // Persist to model_usage table
        jdbcTemplate.update(
                "INSERT INTO model_usage (provider, model, tokens_in, tokens_out) VALUES (?, ?, ?, ?)",
                "anthropic", model, tokensIn, tokensOut);
    }
}
