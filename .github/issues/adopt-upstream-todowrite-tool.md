# Adopt upstream TodoWriteTool from spring-ai-agent-utils

## Summary

Herald's `TodoWriteTool` is a self-described stub (`"Stub implementation until spring-ai-agent-utils provides the canonical version"`). The upstream `spring-ai-agent-utils` now provides a full-featured `TodoWriteTool` with structured task states, progress tracking, and an event-driven `todoEventHandler` callback. Herald should adopt it.

## Current Implementation

Herald's stub (`herald-bot/src/main/java/com/herald/tools/TodoWriteTool.java`):
- Simple `todo_add` / `todo_complete` / `todo_list` methods
- Boolean done/not-done state only
- Uses Spring `ApplicationEventPublisher` → `TodoProgressEvent` → `TodoProgressListener` → Telegram

## Upstream API

```java
TodoWriteTool.builder()
    .todoEventHandler(event -> { /* handle updates */ })
    .build()
```

Upstream features Herald is missing:
- **Three task states**: `pending`, `in_progress`, `completed` (vs boolean done/not-done)
- **Structured tasks**: `content` + `activeForm` + `status` per task
- **Single active task enforcement**: only one task can be `in_progress` at a time
- **Progress visibility**: percentage complete and status symbols
- **Event handler callback**: pluggable `todoEventHandler` for UI updates

## Tasks

- [ ] Replace Herald's `TodoWriteTool` with the upstream version via `TodoWriteTool.builder()`
- [ ] Implement a `todoEventHandler` that bridges to Herald's existing `TodoProgressEvent` / `TodoProgressListener` for Telegram status updates
- [ ] Remove Herald's custom `TodoWriteTool`, `TodoItem` record
- [ ] Evaluate whether `TodoProgressEvent` can be simplified or replaced by the upstream event model
- [ ] Update `HeraldAgentConfig` wiring
- [ ] Update tests

## References

- [TodoWriteTool docs](https://github.com/spring-ai-community/spring-ai-agent-utils/blob/main/spring-ai-agent-utils/docs/TodoWriteTool.md)
- [Spring AI blog post](https://spring.io/blog/2026/01/20/spring-ai-agentic-patterns-3-todowrite)
- Herald's current impl: `herald-bot/src/main/java/com/herald/tools/TodoWriteTool.java`
