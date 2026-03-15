# AgentMetrics: wasteful counter re-registration and missing model/provider tags

## Summary

`AgentMetrics` re-registers Micrometer counters for `herald.tokens.in` and `herald.tokens.out` on every `recordTurn()` call. While Micrometer's `register()` is idempotent, it incurs a registry lookup each time — the counters should be cached as fields like `turnsCounter` and `latencyTimer` already are. Additionally, the latency timer lacks `provider` and `model` tags, making it impossible to slice latency by model in dashboards.

## Current Implementation

```java
// Called on every turn — unnecessary registry lookup
Counter.builder("herald.tokens.in")
    .tag("model", model)
    .register(meterRegistry)
    .increment(inputTokens);
```

vs. the cached pattern already used:

```java
// Cached at construction — good
private final Counter turnsCounter;
this.turnsCounter = Counter.builder("herald.agent.turns").register(meterRegistry);
```

## Issues

1. **Counter re-registration**: `herald.tokens.in` and `herald.tokens.out` re-register per call with dynamic `model` tags. Since model changes are rare (via `ModelSwitcher`), these could be cached and re-created only on model switch.
2. **Missing tags on latency timer**: `herald.agent.latency` has no `provider` or `model` tag — you cannot determine which model is slow in dashboards.
3. **Hand-rolled JSON serialization**: `toolCallsJson` is built via string concatenation with manual quote escaping. Tool names containing quotes, backslashes, or Unicode would break the JSON. Should use Jackson's `ObjectMapper`.
4. **Latency and tool calls not persisted**: `latencyMs` and `toolCalls` appear in the structured log but are **not** inserted into the `model_usage` DB table, losing data for post-hoc analysis.

## Proposed Fix

- Cache token counters per model (use a `ConcurrentHashMap<String, Counter>`)
- Add `provider` and `model` tags to `latencyTimer`
- Replace hand-rolled JSON with `ObjectMapper.writeValueAsString()`
- Add `latency_ms` and `tool_calls` columns to `model_usage` table

## Tasks

- [ ] Cache token counters using `ConcurrentHashMap<String, Counter>`
- [ ] Add model/provider tags to latency timer
- [ ] Replace string-concatenation JSON with Jackson ObjectMapper
- [ ] Extend `model_usage` table schema with `latency_ms` and `tool_calls` columns
- [ ] Update `recordTurn()` INSERT to include new columns

## References

- `herald-bot/src/main/java/com/herald/agent/AgentMetrics.java`
- `herald-bot/src/main/resources/schema.sql`
