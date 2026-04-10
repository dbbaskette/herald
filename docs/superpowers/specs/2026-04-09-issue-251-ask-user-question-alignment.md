# Issue #251: Align AskUserQuestion with library spec

**Status:** Draft
**Date:** 2026-04-09
**Issue:** anthropics/herald#251
**Reference:** [Spring AI AskUserQuestion Tool blog (Jan 16, 2026)](https://spring.io/blog/2026/01/16/spring-ai-ask-user-question-tool), [spring-ai-agent-utils 0.7.0](https://github.com/spring-ai-community/spring-ai-agent-utils/tree/main/examples/ask-user-question-demo)

## Problem

Herald integrates `AskUserQuestionTool` from spring-ai-agent-utils 0.7.0 via `TelegramQuestionHandler`, but two aspects have drifted from the upstream library's recommended usage:

1. **Validation exceptions are swallowed.** The upstream library throws `AskUserQuestionTool$InvalidUserAnswerException` when a user's answer fails validation (e.g., picks an option not in the list). The library docs recommend configuring `spring.ai.tools.throw-exception-on-error` so Spring AI propagates the exception back to the user rather than handing it to the model. Herald's `application.yaml` does not set this.

2. **`TelegramQuestionHandler` duplicates the upstream `Question` type.** The class defines its own `record Question(String id, String text, List<String> options, SelectionType selectionType)` that mirrors `AskUserQuestionTool.Question`. Every call to `handle(List<AskUserQuestionTool.Question>)` converts upstream → internal before doing any work. The duplicate type creates drift risk (upstream fields added in future versions get silently dropped), extra conversion code, and two parallel constructors the tests have to maintain.

## Goals

- Propagate `InvalidUserAnswerException` to users via Spring AI's configured error handling.
- Remove `TelegramQuestionHandler.Question` and its `SelectionType` enum entirely.
- Operate directly on `AskUserQuestionTool.Question` throughout `TelegramQuestionHandler`.
- Preserve all existing behavior: inline keyboard for single-select, text formatting for multi-select / free-text, 5-minute timeout, single-question-at-a-time concurrency guard, `CommandLineQuestionHandler` fallback when Telegram isn't configured.

## Non-goals

- Changing the `CommandLineQuestionHandler` fallback (it's upstream code and out of scope).
- Changing `TelegramPoller`, `TelegramSender`, or `HeraldAgentConfig` — none touch the internal `Question` type.
- Adding new question types or selection modes.
- Refactoring the batch-answer limitation (one reply → all keys for multi-question batches). That's tracked elsewhere.

## Design

### Change 1: Configure exception propagation

Add to `herald-bot/src/main/resources/application.yaml`, alongside the existing `spring.ai` block:

```yaml
spring:
  ai:
    tools:
      throw-exception-on-error: org.springaicommunity.agent.tools.AskUserQuestionTool$InvalidUserAnswerException
```

This tells Spring AI's tool-calling machinery that when this specific exception is thrown during tool execution, the exception should surface to the caller rather than being captured and fed back to the model as a tool-call error. For `AskUserQuestion`, the caller is the user — which is the intended UX.

### Change 2: Remove `TelegramQuestionHandler.Question`

`AskUserQuestionTool.Question` (verified against the 0.7.0 jar) has shape:

```java
public record Question(
    String question,
    String header,
    List<Question.Option> options,
    Boolean multiSelect
)
```

with `Option(String label, String description)`. This covers everything the internal record did:

| Internal `Question` field | Upstream equivalent |
|---|---|
| `id` | `question()` — the text itself is the map key |
| `text` | `question()` |
| `options` (`List<String>`) | `options().stream().map(Option::label).toList()` |
| `selectionType` (enum) | derived: no options → free text; `multiSelect==true` → multi; else → single |

**Refactoring steps in `TelegramQuestionHandler.java`:**

1. Delete the nested `Question` record and its `SelectionType` enum.
2. Change `handleInternal` to take `List<AskUserQuestionTool.Question>`. Remove the upstream→internal conversion loop in `handle()`.
3. Change `handleWithKeyboard` to take `AskUserQuestionTool.Question`. Pull option labels with `q.options().stream().map(AskUserQuestionTool.Question.Option::label).toList()`.
4. Change `formatQuestions` to take `List<AskUserQuestionTool.Question>`. Inline the selection-type decision:
   - No options → free text (no hint line).
   - `Boolean.TRUE.equals(q.multiSelect())` → print "(Select multiple: e.g. A, C)".
   - Else → print "(Select one: e.g. A)".
5. Change `buildAnswerMap` to key by `q.question()`. This matches what the current `handle()` path already does (it passed `q.question()` as the ID when building internal questions) and matches what upstream validation expects.
6. Rewrite the `askQuestion(String)` convenience method to construct `new AskUserQuestionTool.Question(text, text, List.of(), false)` and route through `handleInternal`. The `header` field is required by the upstream constructor; reusing the question text is fine for a free-text prompt with no UI affordance for headers.
7. Delete the `askQuestion(Question)` overload — the only non-test caller is `askQuestion(String)`, which becomes self-contained.
8. The "use keyboard?" decision in `handle()` stays: single question, has options, not multi-select.

After refactoring, the only question type in the class is the upstream one. No conversion, no drift risk.

### Change 3: Update `TelegramQuestionHandlerTest`

Every test that constructs the internal `Question` must switch to `AskUserQuestionTool.Question`. Common pattern:

```java
// Before
new Question("Which calendar?", List.of("Work", "Personal", "Family"))

// After
new AskUserQuestionTool.Question(
    "Which calendar?",
    "Calendar",
    List.of(
        new AskUserQuestionTool.Question.Option("Work", ""),
        new AskUserQuestionTool.Question.Option("Personal", ""),
        new AskUserQuestionTool.Question.Option("Family", "")),
    false)
```

Assertion updates driven by the key change (positional `q1`/`q2` → question text):

- `handleMultipleQuestionsFormatsCorrectly`: `containsKeys("q1", "q2")` → `containsKeys("Which calendar?", "What time?")`; `result.get("q1")` → `result.get("Which calendar?")`.
- `handleWithCustomQuestionIdsUsesThemAsKeys`: delete this test. Upstream `Question` has no ID field separate from its text, so "custom IDs" no longer exists as a concept. The keying-by-text behavior is covered by the other tests.
- `handleRejectsSecondConcurrentQuestion`: update the `new Question("second?")` call to the upstream constructor.
- `handleReturnsEmptyMapOnTimeout`: same.
- `handleInternalReturnsEmptyMap*`: no change (null/empty list, no construction).
- `formatQuestionsIncludesOptionsAndSelectionHint`, `formatQuestionsMultiSelectShowsHint`, `formatQuestionsNumbersMultipleQuestions`, `formatQuestionsSingleQuestionNotNumbered`: update constructors only, assertions unchanged.
- `askQuestionSendsFormattedMessageAndReturnsAnswer`, `pendingQuestionStateIsManagedCorrectly`: these use `askQuestion(String)` — no change.
- The three `handleUpstream*` tests already use `AskUserQuestionTool.Question` — no change.

Remove the `import com.herald.telegram.TelegramQuestionHandler.Question;` line at the top.

## Data flow (unchanged)

```
Agent → AskUserQuestionTool → questionHandler.handle(List<Question>)
         ↓ (if telegram configured)
       TelegramQuestionHandler.handle
         ↓
       handleWithKeyboard OR handleInternal → sendAndAwaitReply
         ↓                                         ↓
       TelegramSender.sendMessage*          future.get(5 min)
                                                   ↑
                                    TelegramPoller.onUpdate → resolveAnswer
```

The refactor changes only the types moving through these calls, not the structure.

## Error handling

- Validation failures from the upstream `AskUserQuestionTool` now reach the user via the new `throw-exception-on-error` config (Change 1).
- Existing `TelegramQuestionHandler` error paths (timeout → empty map, interrupt → empty map, concurrent question → `IllegalStateException`) are unchanged.

## Testing

- `./mvnw test -pl herald-telegram` — full `TelegramQuestionHandlerTest` suite must pass after the constructor/assertion updates.
- `./mvnw test -pl herald-bot` — verifies the bot module still wires up the `AskUserQuestionTool` bean correctly and the new YAML property is parsed.
- Manual smoke test (optional): start the bot with Telegram configured, trigger a tool that asks a question, confirm the keyboard still appears and the answer flows back.

## Rollout

Single PR, no feature flags, no migration. The YAML change is additive (new property). The Java refactor is type-internal to `TelegramQuestionHandler` and has no external callers that construct the internal `Question` record.
