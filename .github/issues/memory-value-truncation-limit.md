# Memory value truncation at 500 characters may be too restrictive

## Summary

`MemoryTools.formatForSystemPrompt()` truncates each memory value to 500 characters when injecting memories into the system prompt via `MemoryBlockAdvisor`. This limit is hardcoded and may be too restrictive for storing structured data like project summaries, meeting notes, or code snippets that the agent is asked to remember.

## Current Implementation

```java
public String formatForSystemPrompt() {
    Map<String, String> all = repository.listAll();
    if (all.isEmpty()) return "";
    StringBuilder sb = new StringBuilder();
    all.forEach((key, value) -> {
        String sanitized = sanitize(value);
        if (sanitized.length() > 500) {
            sanitized = sanitized.substring(0, 500) + "...";
        }
        sb.append("- ").append(key).append(": ").append(sanitized).append("\n");
    });
    return sb.toString();
}
```

## Issues

1. **Hardcoded 500-char limit**: Not configurable; too short for rich context (e.g., a project description with tech stack, team, and goals)
2. **No total size limit**: While individual values are truncated, there is no limit on the total number of memories or total formatted size. 100 memories × 500 chars = 50KB injected into every system prompt, consuming token budget
3. **Silent truncation**: The user/agent has no indication that a stored value was truncated in the system prompt — the full value is in the DB but the agent only sees the truncated version
4. **Sanitization removes useful formatting**: `sanitize()` collapses newlines and strips control characters, which may destroy intentional formatting in stored values

## Proposed Fix

- Make the per-value limit configurable: `herald.memory.max-value-display-length` (default 500)
- Add a total memory budget: `herald.memory.max-total-display-length` (default 10000)
- When total budget is exceeded, include most recently updated memories first
- Add a `[truncated]` marker that the agent can see to know the full value exists
- Consider keeping newlines in sanitization (collapse consecutive newlines but allow single newlines)

## Tasks

- [ ] Make per-value truncation limit configurable
- [ ] Add total formatted size limit with priority ordering
- [ ] Add visible truncation marker
- [ ] Review sanitization to preserve intentional newlines
- [ ] Add tests for edge cases (many memories, very long values, empty values)

## References

- `herald-bot/src/main/java/com/herald/memory/MemoryTools.java`
- `herald-bot/src/main/java/com/herald/agent/MemoryBlockAdvisor.java`
- `herald-bot/src/main/java/com/herald/config/HeraldConfig.java`
