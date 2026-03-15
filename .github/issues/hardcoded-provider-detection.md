# AgentMetrics.deriveProvider() uses hardcoded prefix list — silent fallback to "unknown"

## Summary

`AgentMetrics.deriveProvider(String model)` maps model IDs to provider names using a hardcoded if/else chain (`claude-` → anthropic, `gpt-`/`o1`/`o3` → openai, etc.). Any model from a new provider (e.g., Gemini, DeepSeek, Mistral) or an unexpected naming scheme silently falls through to `"unknown"`, breaking cost attribution, dashboard filtering, and the `/model status` display.

## Current Implementation

```java
static String deriveProvider(String model) {
    if (model == null) return "unknown";
    if (model.startsWith("claude-")) return "anthropic";
    if (model.startsWith("gpt-") || model.startsWith("o1") || model.startsWith("o3")) return "openai";
    return "unknown";
}
```

## Issues

1. **Silent failure**: No log or metric when an unknown model is encountered
2. **Not extensible**: Adding a new provider requires a code change and rebuild
3. **Incomplete OpenAI coverage**: Models like `chatgpt-4o-latest`, `gpt-4-turbo` would match, but future naming changes wouldn't
4. **No Ollama detection**: Ollama models (arbitrary names like `llama3`, `codestral`) always map to `"unknown"`

## Proposed Fix

1. **Configuration-driven mapping**: Allow `herald.agent.provider-prefixes` in YAML:
   ```yaml
   herald:
     agent:
       provider-prefixes:
         anthropic: ["claude-"]
         openai: ["gpt-", "o1", "o3", "chatgpt-"]
         ollama: ["llama", "codestral", "mistral"]
   ```
2. **ChatModel-aware detection**: Since `ModelSwitcher` already knows which provider/model is active, pass the provider alongside the model ID to `recordTurn()` rather than deriving it
3. **Log a WARN** when falling through to `"unknown"`

## Tasks

- [ ] Pass provider from `ModelSwitcher` context into `AgentMetrics.recordTurn()` rather than deriving
- [ ] Add WARN log for unrecognized models as a safety net
- [ ] Remove or simplify `deriveProvider()` once provider is passed explicitly
- [ ] Update tests

## References

- `herald-bot/src/main/java/com/herald/agent/AgentMetrics.java`
- `herald-bot/src/main/java/com/herald/agent/ModelSwitcher.java`
