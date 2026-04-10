# Issue #252 — Remove TodoProgressEvent Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Delete the custom `TodoProgressEvent` wrapper and its two `@EventListener` beans; have `HeraldAgentConfig.todoEventHandler` call `MessageSender` directly (falling back to stdout).

**Architecture:** The lambda builds the formatted todo summary, then dispatches to `Optional<MessageSender>` — if present (`TelegramSender`) it calls `sendMessage`; otherwise it prints to `System.out`. This removes the Spring `ApplicationEventPublisher` hop and both listener classes.

**Tech Stack:** Java 21 · Spring Boot · spring-ai-agent-utils 0.7.0 · Maven multi-module (herald-bot, herald-core, herald-telegram)

**Spec:** `docs/superpowers/specs/2026-04-10-issue-252-todo-progress-event-removal.md`

---

## File Structure

**Files to delete (5)**
- `herald-core/src/main/java/com/herald/tools/TodoProgressEvent.java`
- `herald-core/src/main/java/com/herald/agent/ConsoleTodoProgressListener.java`
- `herald-core/src/test/java/com/herald/agent/ConsoleTodoProgressListenerTest.java`
- `herald-telegram/src/main/java/com/herald/telegram/TodoProgressListener.java`
- `herald-telegram/src/test/java/com/herald/telegram/TodoProgressListenerTest.java`

**Files to modify (6)**
- `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java` — rewrite lambda, swap `ApplicationEventPublisher` for `Optional<MessageSender>`
- `herald-telegram/pom.xml` — update the outdated dependency comment
- `README.md` — update the TodoWrite row in the patterns table
- `docs/module-inventory.md` — drop the `TodoProgressEvent` and `TodoProgressListener` rows
- `docs/herald-patterns-comparison.md` — rewrite "Real-time progress events" paragraph
- `docs/spring-ai-patterns-assessment.md` — update the "Real-time progress events" row

---

## Task 1: Remove listener tests

These tests exercise only the wrapper class being deleted. Removing them first lets subsequent tasks delete production code without breaking the build.

**Files:**
- Delete: `herald-core/src/test/java/com/herald/agent/ConsoleTodoProgressListenerTest.java`
- Delete: `herald-telegram/src/test/java/com/herald/telegram/TodoProgressListenerTest.java`

- [ ] **Step 1: Delete the console listener test**

```bash
rm herald-core/src/test/java/com/herald/agent/ConsoleTodoProgressListenerTest.java
```

- [ ] **Step 2: Delete the telegram listener test**

```bash
rm herald-telegram/src/test/java/com/herald/telegram/TodoProgressListenerTest.java
```

- [ ] **Step 3: Verify no other tests reference the deleted classes**

Use the Grep tool:
- Pattern: `TodoProgressListener|ConsoleTodoProgressListener|TodoProgressEvent`
- Glob: `**/src/test/**/*.java`

Expected: no results.

- [ ] **Step 4: Commit**

```bash
git add -A herald-core/src/test/java/com/herald/agent/ConsoleTodoProgressListenerTest.java \
            herald-telegram/src/test/java/com/herald/telegram/TodoProgressListenerTest.java
git commit -m "test: remove TodoProgressListener tests (#252)"
```

---

## Task 2: Rewrite HeraldAgentConfig lambda to call MessageSender directly

This is the behavior change. It must land before we delete the listener and event classes (otherwise the build breaks on the import of `TodoProgressEvent`).

**Files:**
- Modify: `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java`

**Current relevant snippets** (for reference — do not copy verbatim if line numbers drift, use the Edit tool with exact old_string):

Line 13 (import):
```java
import com.herald.tools.TodoProgressEvent;
```

Line 39 (import):
```java
import org.springframework.context.ApplicationEventPublisher;
```

Line 133 (parameter inside `modelSwitcher(...)` signature):
```java
            ApplicationEventPublisher eventPublisher,
```

Lines 233-247 (todoEventHandler lambda):
```java
        // Build upstream TodoWriteTool with event handler bridging to Telegram via TodoProgressEvent
        org.springaicommunity.agent.tools.TodoWriteTool todoTool = org.springaicommunity.agent.tools.TodoWriteTool.builder()
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
                    eventPublisher.publishEvent(new TodoProgressEvent(todos, sb.toString()));
                })
                .build();
```

- [ ] **Step 1: Remove the `TodoProgressEvent` import**

Use the Edit tool on `herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java`:

- old_string:
```
import com.herald.tools.TelegramSendTool;
import com.herald.tools.TodoProgressEvent;
import com.herald.tools.WebTools;
```
- new_string:
```
import com.herald.tools.TelegramSendTool;
import com.herald.tools.WebTools;
```

Note: `MessageSender` does **not** need a new import. `HeraldAgentConfig` is already in package `com.herald.agent` (same package as `MessageSender`), so it is reachable as a simple name.

- [ ] **Step 2: Remove the `ApplicationEventPublisher` import**

Use the Edit tool:

- old_string:
```
import org.springframework.context.ApplicationEventPublisher;
```
- new_string: (empty — delete the line)

Leave the rest of the `org.springframework.context.*` imports untouched.

- [ ] **Step 3: Swap the `modelSwitcher` method parameter**

Use the Edit tool:

- old_string:
```
            FileSystemTools fsTools,
            ApplicationEventPublisher eventPublisher,
            ObjectProvider<TelegramQuestionHandler> questionHandlerProvider,
```
- new_string:
```
            FileSystemTools fsTools,
            Optional<MessageSender> messageSenderOpt,
            ObjectProvider<TelegramQuestionHandler> questionHandlerProvider,
```

`Optional` is already imported in the file (line 65 in the current code). Verify with the Grep tool (pattern: `import java.util.Optional;`, file: `HeraldAgentConfig.java`) if unsure.

- [ ] **Step 4: Rewrite the todoEventHandler lambda**

Use the Edit tool:

- old_string:
```
        // Build upstream TodoWriteTool with event handler bridging to Telegram via TodoProgressEvent
        org.springaicommunity.agent.tools.TodoWriteTool todoTool = org.springaicommunity.agent.tools.TodoWriteTool.builder()
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
                    eventPublisher.publishEvent(new TodoProgressEvent(todos, sb.toString()));
                })
                .build();
```
- new_string:
```
        // Build upstream TodoWriteTool with a handler that dispatches formatted
        // progress directly to MessageSender (Telegram) or stdout as a fallback.
        org.springaicommunity.agent.tools.TodoWriteTool todoTool = org.springaicommunity.agent.tools.TodoWriteTool.builder()
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
                .build();
```

- [ ] **Step 5: Compile `herald-bot` to confirm the rewrite builds**

Run:
```bash
./mvnw -pl herald-bot -am compile -q
```

Expected: BUILD SUCCESS. If you see `cannot find symbol: class TodoProgressEvent`, a stray reference was missed — grep the file for `TodoProgressEvent` and remove it. If you see `cannot find symbol: variable eventPublisher`, the lambda still references the old parameter — re-run Step 5.

- [ ] **Step 6: Commit**

```bash
git add herald-bot/src/main/java/com/herald/agent/HeraldAgentConfig.java
git commit -m "refactor: call MessageSender directly from todoEventHandler (#252)"
```

---

## Task 3: Delete the production listener and event classes

Now that nothing references them, delete the three production files.

**Files:**
- Delete: `herald-telegram/src/main/java/com/herald/telegram/TodoProgressListener.java`
- Delete: `herald-core/src/main/java/com/herald/agent/ConsoleTodoProgressListener.java`
- Delete: `herald-core/src/main/java/com/herald/tools/TodoProgressEvent.java`

- [ ] **Step 1: Delete the telegram listener**

```bash
rm herald-telegram/src/main/java/com/herald/telegram/TodoProgressListener.java
```

- [ ] **Step 2: Delete the console listener**

```bash
rm herald-core/src/main/java/com/herald/agent/ConsoleTodoProgressListener.java
```

- [ ] **Step 3: Delete the event class**

```bash
rm herald-core/src/main/java/com/herald/tools/TodoProgressEvent.java
```

- [ ] **Step 4: Verify no lingering references in production code**

Use the Grep tool:
- Pattern: `TodoProgressEvent|TodoProgressListener|ConsoleTodoProgressListener`
- Glob: `**/src/main/**/*.java`

Expected: no results.

- [ ] **Step 5: Build the three affected modules together**

Run:
```bash
./mvnw -pl herald-bot,herald-core,herald-telegram -am verify -q
```

Expected: BUILD SUCCESS, all tests green. If a test fails referencing one of the deleted classes, that test file must be deleted too — grep for the class name under `**/src/test/**/*.java` to locate it.

- [ ] **Step 6: Commit**

```bash
git add -A herald-telegram/src/main/java/com/herald/telegram/TodoProgressListener.java \
            herald-core/src/main/java/com/herald/agent/ConsoleTodoProgressListener.java \
            herald-core/src/main/java/com/herald/tools/TodoProgressEvent.java
git commit -m "refactor: delete TodoProgressEvent and its listeners (#252)"
```

---

## Task 4: Update herald-telegram/pom.xml comment

**Files:**
- Modify: `herald-telegram/pom.xml`

- [ ] **Step 1: Update the outdated dependency comment**

Use the Edit tool on `herald-telegram/pom.xml`:

- old_string:
```
        <!-- Herald Core (MessageSender, AgentService, HeraldConfig, TodoProgressEvent, etc.) -->
```
- new_string:
```
        <!-- Herald Core (MessageSender, AgentService, HeraldConfig, etc.) -->
```

- [ ] **Step 2: Commit**

```bash
git add herald-telegram/pom.xml
git commit -m "chore: drop TodoProgressEvent from herald-telegram pom comment (#252)"
```

---

## Task 5: Update documentation

**Files:**
- Modify: `README.md`
- Modify: `docs/module-inventory.md`
- Modify: `docs/herald-patterns-comparison.md`
- Modify: `docs/spring-ai-patterns-assessment.md`

- [ ] **Step 1: Update the TodoWrite row in README.md**

Use the Edit tool on `README.md`:

- old_string:
```
| **TodoWrite** | [Part 3: Why Your AI Agent Forgets Tasks](https://spring.io/blog/2026/01/20/spring-ai-agentic-patterns-3-todowrite) | ✅ | Upstream `TodoWriteTool` with structured states (`pending` → `in_progress` → `completed`). A `todoEventHandler` bridges to `TodoProgressEvent` → `TodoProgressListener` → Telegram for real-time progress with status symbols. |
```
- new_string:
```
| **TodoWrite** | [Part 3: Why Your AI Agent Forgets Tasks](https://spring.io/blog/2026/01/20/spring-ai-agentic-patterns-3-todowrite) | ✅ | Upstream `TodoWriteTool` with structured states (`pending` → `in_progress` → `completed`). A `todoEventHandler` dispatches formatted progress directly to `MessageSender` (Telegram) with status symbols, or prints to stdout when no transport is configured. |
```

- [ ] **Step 2: Remove the `TodoProgressEvent` row from `docs/module-inventory.md`**

Use the Edit tool:

- old_string:
```
| ShellCommandExecutor | com.herald.tools | Core | Functional interface for shell execution; no DB dependency |
| TodoProgressEvent | com.herald.tools | Core | Spring ApplicationEvent for todo list changes; no DB dependency |
| GwsAvailabilityChecker | com.herald.tools | Core | Checks gws CLI presence via process call; no DB dependency |
```
- new_string:
```
| ShellCommandExecutor | com.herald.tools | Core | Functional interface for shell execution; no DB dependency |
| GwsAvailabilityChecker | com.herald.tools | Core | Checks gws CLI presence via process call; no DB dependency |
```

- [ ] **Step 3: Remove the `TodoProgressListener` row from `docs/module-inventory.md`**

Use the Edit tool:

- old_string:
```
| MessageFormatter | com.herald.telegram | Telegram | Splits and escapes text for Telegram's message length/format limits |
| TodoProgressListener | com.herald.telegram | Telegram | Forwards TodoProgressEvent to Telegram via TelegramSender |
| TelegramPoller | com.herald.telegram | Telegram | Polls Telegram for updates; depends on TelegramBot (pengrad) |
```
- new_string:
```
| MessageFormatter | com.herald.telegram | Telegram | Splits and escapes text for Telegram's message length/format limits |
| TelegramPoller | com.herald.telegram | Telegram | Polls Telegram for updates; depends on TelegramBot (pengrad) |
```

- [ ] **Step 4: Update `docs/herald-patterns-comparison.md`**

Use the Edit tool:

- old_string:
```
**Herald:** ✅ **Implemented.** Herald's `TodoProgressListener` listens for `TodoProgressEvent` via Spring's `ApplicationEventPublisher`. Each update is forwarded to Telegram, so the user sees live task progress (e.g., `[→] Analyzing repo structure  2/4 complete`) as the agent works.
```
- new_string:
```
**Herald:** ✅ **Implemented.** Herald's `todoEventHandler` lambda formats each update and dispatches it directly to the `MessageSender` bean (normally `TelegramSender`), so the user sees live task progress (e.g., `[→] Analyzing repo structure  2/4 complete`) as the agent works. When no transport is configured the summary prints to stdout.
```

- [ ] **Step 5: Update `docs/spring-ai-patterns-assessment.md`**

Use the Edit tool:

- old_string:
```
| Real-time progress events | `todoEventHandler` callback → Spring `ApplicationEvent` | ✅ | `TodoProgressListener` forwards to Telegram (e.g. `[→] Analyzing... 2/4`) |
```
- new_string:
```
| Real-time progress events | `todoEventHandler` callback → `MessageSender` | ✅ | Lambda dispatches formatted summary directly to Telegram (e.g. `[→] Analyzing... 2/4`) or stdout fallback |
```

- [ ] **Step 6: Final grep — confirm no stray references remain in docs/README**

Use the Grep tool:
- Pattern: `TodoProgressEvent|TodoProgressListener|ConsoleTodoProgressListener`
- Glob: `{README.md,docs/**/*.md}`

Expected: the only hits should be under `docs/superpowers/specs/2026-04-10-issue-252-*` and `docs/superpowers/plans/2026-04-10-issue-252-*` (this spec/plan themselves), and inside `docs/superpowers/plans/2026-03-20-issues-222-225.md` (a historical plan that originally introduced `ConsoleTodoProgressListener` — leave it untouched; historical plans are append-only). Any other hit means a doc was missed — Edit it.

- [ ] **Step 7: Commit**

```bash
git add README.md docs/module-inventory.md docs/herald-patterns-comparison.md docs/spring-ai-patterns-assessment.md
git commit -m "docs: remove TodoProgressEvent references (#252)"
```

---

## Task 6: Final verification

- [ ] **Step 1: Clean build of affected modules**

Run:
```bash
./mvnw -pl herald-bot,herald-core,herald-telegram -am verify
```

Expected: BUILD SUCCESS, all tests green. If this is the first full verify in the session it may take a few minutes.

- [ ] **Step 2: Full-repo grep for any stragglers**

Use the Grep tool:
- Pattern: `TodoProgressEvent|TodoProgressListener|ConsoleTodoProgressListener`
- Glob: (omit — search everything)

Expected hits only in:
- `docs/superpowers/specs/2026-04-10-issue-252-*.md` (the spec — self-references)
- `docs/superpowers/plans/2026-04-10-issue-252-*.md` (this plan — self-references)
- `docs/superpowers/plans/2026-03-20-issues-222-225.md` (historical plan, intentionally preserved)
- `.claude/worktrees/**` (stale worktree copies, not tracked by git — ignore)

If any other hit appears, open that file and resolve the reference before proceeding.

- [ ] **Step 3: Confirm the issue's acceptance criteria are satisfied**

Re-read the "Alignment Plan" in `gh issue view 252`:

1. ✅ Delete `herald-core/src/main/java/com/herald/tools/TodoProgressEvent.java` → Task 3 Step 3
2. ✅ In `HeraldAgentConfig`, publish the formatted message directly … or simply call `TelegramSender` directly from the `todoEventHandler` lambda → Task 2 (direct `MessageSender.sendMessage` via `Optional`)
3. ✅ Update `TodoProgressListener` accordingly → Task 3 Step 1 (deleted)
4. ✅ Update any tests that reference `TodoProgressEvent` → Task 1 (deleted)

- [ ] **Step 4: No new commit needed**

This task is verification only. No files changed.
