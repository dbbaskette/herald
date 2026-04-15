# Issue #252 — Remove custom TodoProgressEvent

**Issue:** [dbbaskette/herald#252](https://github.com/dbbaskette/herald/issues/252)
**Status:** Design approved 2026-04-10

## Background

`HeraldAgentConfig` currently bridges `TodoWriteTool`'s `todoEventHandler` callback
into a custom `TodoProgressEvent` (a Spring `ApplicationEvent` in `herald-core`).
Two `@EventListener` beans consume it:

- `TodoProgressListener` (herald-telegram) forwards the formatted summary to
  `TelegramSender`.
- `ConsoleTodoProgressListener` (herald-core, active via
  `@ConditionalOnMissingBean(MessageSender.class)`) prints the summary to stdout
  when no messaging transport is configured.

### Problems

1. **Redundant wrapper.** `TodoProgressEvent` adds no functional value beyond
   what the `todoEventHandler` callback already gives us. The pipeline goes
   `Todos → format → custom event → listener → MessageSender`, when it could be
   `Todos → format → MessageSender`.
2. **Incorrect `ApplicationEvent.source`.** The current code calls
   `new TodoProgressEvent(todos, summary)`, which passes the upstream
   `TodoWriteTool.Todos` record as the Spring `ApplicationEvent.source`. By
   Spring convention `source` should be the publishing bean, not an unrelated
   payload.
3. **Unnecessary fan-out.** Both listeners are mutually exclusive
   (`@ConditionalOnMissingBean(MessageSender.class)` vs. `TelegramSender`), so
   the event bus is only ever delivering to a single subscriber. The bus buys
   us nothing.

## Goal

Remove the custom event wrapper and the two listener classes. Let the
`todoEventHandler` lambda in `HeraldAgentConfig` call `MessageSender` directly,
falling back to `System.out.print` when no `MessageSender` bean is present.

## Non-goals

- Changing the `MessageSender` interface.
- Touching the upstream `TodoWriteTool` or the `spring-ai-agent-utils` library.
- Refactoring `CronService` or `HeraldShellDecorator`, which already inject
  `Optional<MessageSender>` and are unaffected.
- Adding new tests. The deleted listener tests only exercised the wrapper
  class; no behavior is left uncovered that wasn't already uncovered.

## Architecture

### Before

```
TodoWriteTool.todoEventHandler(Todos)
  └─ HeraldAgentConfig lambda formats summary
       └─ ApplicationEventPublisher.publishEvent(new TodoProgressEvent(todos, summary))
            ├─ TodoProgressListener (telegram)    → TelegramSender.sendMessage
            └─ ConsoleTodoProgressListener (core) → System.out.print
```

### After

```
TodoWriteTool.todoEventHandler(Todos)
  └─ HeraldAgentConfig lambda formats summary
       └─ Optional<MessageSender>
            ├─ present (TelegramSender) → sender.sendMessage(summary)
            └─ absent                   → System.out.print(summary)
```

## Changes

### Deletions

- `herald-core/src/main/java/com/herald/tools/TodoProgressEvent.java`
- `herald-core/src/main/java/com/herald/agent/ConsoleTodoProgressListener.java`
- `herald-core/src/test/java/com/herald/agent/ConsoleTodoProgressListenerTest.java`
- `herald-telegram/src/main/java/com/herald/telegram/TodoProgressListener.java`
- `herald-telegram/src/test/java/com/herald/telegram/TodoProgressListenerTest.java`

### `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java`

- Remove `import com.herald.tools.TodoProgressEvent`.
- Remove `ApplicationEventPublisher eventPublisher` parameter from
  `modelSwitcher(...)`. Drop the
  `org.springframework.context.ApplicationEventPublisher` import if it has no
  other usages.
- Add `Optional<MessageSender> messageSenderOpt` parameter to
  `modelSwitcher(...)`. Import `com.herald.agent.MessageSender`.
- Rewrite the `todoEventHandler` lambda to format the summary and dispatch
  directly:

```java
.todoEventHandler(todos -> {
    StringBuilder sb = new StringBuilder();
    for (var item : todos.todos()) {
        String symbol = switch (item.status()) {
            case pending -> "\u2B1A";
            case in_progress -> "\u25B6";
            case completed -> "\u2713";
        };
        sb.append(symbol).append(" ").append(item.content()).append("\n");
    }
    String summary = sb.toString();
    messageSenderOpt.ifPresentOrElse(
            s -> s.sendMessage(summary),
            () -> System.out.print(summary));
})
```

### `herald-telegram/pom.xml`

- Update the dependency comment that still mentions `TodoProgressEvent` in the
  `herald-core` entry.

### Documentation

Remove or adjust references to `TodoProgressEvent`, `TodoProgressListener`,
and `ConsoleTodoProgressListener` where they describe the progress pipeline:

- `README.md`
- `docs/module-inventory.md`
- `docs/herald-patterns-comparison.md`

## Wiring notes

`TelegramSender` is already `@ConditionalOnProperty("herald.telegram.bot-token")`,
so `Optional<MessageSender>` resolves to empty when Telegram isn't configured.
This is the exact pattern already used by `CronService` and
`HeraldShellDecorator`, so no new conditional bean machinery is required.

## Verification

- Build affected modules together:
  `./mvnw -pl herald-bot,herald-core,herald-telegram -am verify`
- Manual smoke with Telegram configured: trigger a `TodoWrite` update and
  confirm the summary reaches Telegram.
- Manual smoke without Telegram configured: trigger a `TodoWrite` update and
  confirm the summary prints to stdout.

## Risks

- **Missed doc references.** `TodoProgressEvent` is mentioned in several
  markdown files; a grep after the code changes is required to catch any
  stragglers.
- **Formatting regression.** The lambda's output format must match the current
  behavior (status symbol + space + content + newline). Covered by the manual
  smoke tests above.
