package com.herald.agent;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

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
public class AgentMetrics implements AgentTurnListener {

    private static final Logger log = LoggerFactory.getLogger(AgentMetrics.class);

    private final Counter turnsCounter;
    private final Timer latencyTimer;
    private final MeterRegistry meterRegistry;
    private final JdbcTemplate jdbcTemplate;

    public AgentMetrics(MeterRegistry meterRegistry, JdbcTemplate jdbcTemplate) {
        this.meterRegistry = meterRegistry;
        this.turnsCounter = Counter.builder("herald.agent.turns")
                .description("Number of agent turns processed")
                .register(meterRegistry);
        this.latencyTimer = Timer.builder("herald.agent.latency")
                .description("Agent turn latency")
                .register(meterRegistry);
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Record metrics for a completed agent turn.
     */
    @Override
    public void recordTurn(String provider, String model, long tokensIn, long tokensOut,
                    long latencyMs, List<String> toolCalls, String subagentId) {
        String turnId = UUID.randomUUID().toString();

        // Micrometer counters
        turnsCounter.increment();
        Counter.builder("herald.tokens.in")
                .tag("provider", provider)
                .tag("model", model)
                .description("Input tokens consumed")
                .register(meterRegistry)
                .increment(tokensIn);
        Counter.builder("herald.tokens.out")
                .tag("provider", provider)
                .tag("model", model)
                .description("Output tokens produced")
                .register(meterRegistry)
                .increment(tokensOut);
        latencyTimer.record(Duration.ofMillis(latencyMs));

        // Structured JSON log entry
        String toolCallsJson = toolCalls.stream()
                .map(name -> "\"" + name.replace("\"", "\\\"") + "\"")
                .collect(Collectors.joining(",", "[", "]"));
        log.info("{\"turn_id\":\"{}\",\"provider\":\"{}\",\"model\":\"{}\",\"subagent_id\":\"{}\",\"tokens_in\":{},\"tokens_out\":{},\"latency_ms\":{},\"tool_calls\":{}}",
                turnId, provider, model, subagentId, tokensIn, tokensOut, latencyMs, toolCallsJson);

        // Persist to model_usage table
        jdbcTemplate.update(
                "INSERT INTO model_usage (subagent_id, provider, model, tokens_in, tokens_out) VALUES (?, ?, ?, ?, ?)",
                subagentId, provider, model, tokensIn, tokensOut);
    }

    /**
     * Derive the provider name from a model identifier.
     */
    static String deriveProvider(String model) {
        if (model == null || model.isBlank()) {
            return "unknown";
        }
        String lower = model.toLowerCase();
        if (lower.startsWith("claude") || lower.contains("anthropic")) {
            return "anthropic";
        }
        if (lower.startsWith("gpt") || lower.startsWith("o1") || lower.startsWith("o3")
                || lower.startsWith("o4") || lower.contains("openai")) {
            return "openai";
        }
        if (lower.startsWith("llama") || lower.startsWith("mistral") || lower.startsWith("gemma")
                || lower.startsWith("phi") || lower.startsWith("qwen") || lower.startsWith("codellama")) {
            return "ollama";
        }
        return "unknown";
    }
}
