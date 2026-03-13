# Consider replacing ClaudeSubagentType with a generic SubagentType

## Summary

Herald uses `ClaudeSubagentType` from `spring-ai-agent-utils` to configure task subagents, but Herald is model-agnostic and already wires in OpenAI and Ollama models. The "Claude" naming is misleading — it works fine with any model, but a generic `SubagentType` implementation would be cleaner.

**This is optional / low priority.** The current setup is functional.

## Current State

```java
var subagentTypeBuilder = ClaudeSubagentType.builder()
    .chatClientBuilder("anthropic", ...)
    .chatClientBuilder("openai", ...)
    .chatClientBuilder("ollama", ...);
```

`ClaudeSubagentType` registers Claude-specific built-in subagent references (GENERAL_PURPOSE, EXPLORE, PLAN, BASH) and uses the `"claude"` kind identifier.

## Options

1. **Wait for upstream** — `SubagentType` is a generic interface; the community may add a `GenericSubagentType` or `DefaultSubagentType`
2. **Implement our own** — Create a Herald-specific `SubagentType` with model-neutral naming
3. **Do nothing** — It works as-is, just has a misleading class name

## References

- [Spring AI Task Subagents blog](https://spring.io/blog/2026/01/27/spring-ai-agentic-patterns-4-task-subagents)
- [TaskTool source](https://github.com/spring-ai-community/spring-ai-agent-utils/blob/main/spring-ai-agent-utils/src/main/java/org/springaicommunity/agent/tools/task/TaskTool.java)
- Herald's wiring: `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java`
