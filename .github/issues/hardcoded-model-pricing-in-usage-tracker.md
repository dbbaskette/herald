# Hardcoded model pricing in UsageTracker will silently break on model updates

## Summary

`UsageTracker` maintains a `PRICING` map with hardcoded per-token costs keyed to specific model IDs (e.g., `claude-sonnet-4-5-20250514`, `gpt-4o-2024-11-20`). When Anthropic or OpenAI publish new model versions with different IDs, the map won't match and costs will silently report as `$0.00`. There is no warning when an unknown model is encountered.

## Current Implementation

```java
private static final Map<String, double[]> PRICING = Map.ofEntries(
    Map.entry("claude-sonnet-4-5-20250514", new double[]{3.0, 15.0}),
    Map.entry("claude-haiku-3-5-20241022", new double[]{0.80, 4.0}),
    // ...
);
```

`estimateDailyCost()` looks up `model` in this map — if not found, input/output cost defaults to 0.

## Additional Issues

1. **Timezone mismatch**: SQL uses `date('now')` (SQLite UTC) but the JVM timezone may differ, causing "today" to span the wrong calendar day
2. **Redundant DB queries**: `getDailyUsage()` and `getDailyUsageByAgent()` issue separate queries; calling both (e.g., in `/status`) makes two round trips where one could suffice
3. **No model version normalization**: If the same model is called with and without a date suffix, they're tracked as separate entries

## Proposed Fix

- Externalize pricing to `application.yaml` or a separate `pricing.yaml` that can be updated without code changes
- Add a prefix-match fallback: if `claude-sonnet-4-5-20250514` isn't found, try `claude-sonnet-4-5`, then `claude-sonnet`
- Log a warning when a model has no pricing entry
- Use `date('now')` consistently or parameterize the date with JVM's local date

## Tasks

- [ ] Externalize pricing configuration (YAML or properties)
- [ ] Add prefix-match fallback for model ID lookup
- [ ] Add WARN log when model pricing is unknown
- [ ] Fix timezone alignment between SQLite `date('now')` and JVM
- [ ] Consider merging daily usage queries to reduce DB round trips

## References

- `herald-bot/src/main/java/com/herald/agent/UsageTracker.java`
- `herald-bot/src/main/resources/application.yaml`
