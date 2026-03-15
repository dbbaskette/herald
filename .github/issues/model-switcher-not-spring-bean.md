# ModelSwitcher is manually instantiated — not a Spring bean, limiting testability

## Summary

`ModelSwitcher` is created manually inside `HeraldAgentConfig.modelSwitcher()` via `new ModelSwitcher(...)` rather than being a Spring-managed `@Component`. This makes it harder to inject, mock, or test in isolation. Other components that need the current model/provider information (e.g., `AgentMetrics`, `CommandHandler`) must receive it indirectly.

## Current State

```java
// In HeraldAgentConfig
@Bean
public ModelSwitcher modelSwitcher(...) {
    return new ModelSwitcher(models, defaultKey, jdbcTemplate);
}
```

`ModelSwitcher` manages:
- Runtime model switching with `switchModel(provider, model)`
- Persistence of model override to `model_overrides` table
- Thread-safe `volatile` fields for current model/provider
- `loadPersistedOverride()` at startup

## Issues

1. **Not discoverable as a bean type**: While it is returned from a `@Bean` method (so it IS a Spring bean), the class itself has no Spring annotations, making it unclear without reading the config
2. **Constructor complexity**: Takes a `Map<String, ChatModel>`, a default key, and `JdbcTemplate` — all of which are wired in `HeraldAgentConfig`, creating tight coupling
3. **Testing requires full config**: Integration tests need to set up the entire `HeraldAgentConfig` to get a `ModelSwitcher` instance
4. **Provider info not propagated**: `AgentMetrics.deriveProvider()` re-derives the provider from the model name rather than getting it from `ModelSwitcher` which already knows

## Proposed Fix

- Add `@Component` to `ModelSwitcher` and inject dependencies directly
- Expose `getCurrentProvider()` method for use by `AgentMetrics`
- Simplify `HeraldAgentConfig` by removing the manual bean creation
- Consider extracting model registry into its own bean for cleaner separation

## Tasks

- [ ] Convert `ModelSwitcher` to a `@Component` with constructor injection
- [ ] Add `getCurrentProvider()` method
- [ ] Wire `AgentMetrics` to use `ModelSwitcher.getCurrentProvider()` instead of `deriveProvider()`
- [ ] Simplify `HeraldAgentConfig`
- [ ] Update tests

## References

- `herald-bot/src/main/java/com/herald/agent/ModelSwitcher.java`
- `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java`
- `herald-bot/src/main/java/com/herald/agent/AgentMetrics.java`
